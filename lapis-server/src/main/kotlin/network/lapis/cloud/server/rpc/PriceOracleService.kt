package network.lapis.cloud.server.rpc

import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.server.application.ApplicationCall
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import network.lapis.cloud.server.db.DbClock
import network.lapis.cloud.server.db.generated.LtrLedgerEntryTable
import network.lapis.cloud.server.db.generated.PriceOracleConfigTable
import network.lapis.cloud.server.db.generated.PriceOracleConversionTable
import network.lapis.cloud.server.db.truncatedToDbPrecision
import network.lapis.cloud.server.economy.oracle.PriceOracleOrchestrator
import network.lapis.cloud.server.economy.oracle.QuoteOutcome
import network.lapis.cloud.server.economy.oracle.plausiblePegBand
import network.lapis.cloud.server.security.requireRole
import network.lapis.cloud.server.security.resolveCurrentMember
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.AnchorAsset
import network.lapis.cloud.shared.domain.AnchorPolicy
import network.lapis.cloud.shared.domain.DonationConversionInput
import network.lapis.cloud.shared.domain.LtrLedgerEntryType
import network.lapis.cloud.shared.domain.OraclePriceStatusDto
import network.lapis.cloud.shared.domain.PriceOracleConfigDto
import network.lapis.cloud.shared.domain.PriceOracleConfigInput
import network.lapis.cloud.shared.domain.PriceOracleConversionDto
import network.lapis.cloud.shared.rpc.ConflictException
import network.lapis.cloud.shared.rpc.IPriceOracleService
import network.lapis.cloud.shared.rpc.NotFoundException
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import java.math.BigDecimal
import java.math.RoundingMode
import kotlin.uuid.Uuid

/** The single seeded [PriceOracleConfigTable] row's fixed id -- see `V1__baseline.sql`'s unconditional seed `INSERT` and `19-price-oracle.kuml.kts`'s file header for the exactly-one-row-by-convention rationale. Next unused `...-0000-0000000000fN` slot after `crowdfunding_submission_gate`'s own `...-f4`. */
val PRICE_ORACLE_CONFIG_ID: Uuid = Uuid.parse("00000000-0000-0000-0000-0000000000f5")

private val PRICE_ORACLE_TREASURY_ROLES = arrayOf(AccountRole.TREASURER, AccountRole.BOARD, AccountRole.ADMIN)

private val SUPPORTED_DONATION_CURRENCIES = setOf("EUR", "USD")

/** "Aktuelle Annahme, vor Produktiveinsatz zu verifizieren" -- same disclaimer class as [LtrLedgerService]'s own `MIN_MINT_LTR`. Pure dust/spam floor: a computed `ltrMinted` this small is more likely a fat-fingered donation amount or a badly mis-set peg than a real intent. */
private val MIN_LTR_MINTED = BigDecimal("0.01")

private const val LTR_MINTED_SCALE = 2

private val logger = KotlinLogging.logger {}

/**
 * V0.6.5 Price-Oracle fuer die Anker-Bindung RPC surface (V0.6.6 "Gold- und Fiat-Anker" extends
 * [validateConfigInput] to be anchor-generic, see that function's own KDoc) -- see [IPriceOracleService] KDoc and
 * `19-price-oracle.kuml.kts` file header for the full fachlich model. [orchestrator] is a
 * singleton constructed once by `Application.module` (owns the pooled HTTP client and the
 * in-memory quote cache) and passed in here, never constructed per-call.
 *
 * **`convertDonationToLtr` ordering is load-bearing**: the oracle network fan-out
 * ([PriceOracleOrchestrator.currentQuote]) runs OUTSIDE any DB `transaction {}` -- it can take
 * several seconds (three parallel HTTP calls, each with its own multi-second timeout) and must
 * never hold a DB connection/transaction open for that long. Only the final read-config /
 * write-ledger-and-provenance steps run inside `transaction {}` blocks.
 */
class PriceOracleService(
    private val call: ApplicationCall,
    private val orchestrator: PriceOracleOrchestrator,
) : IPriceOracleService {
    override suspend fun getOracleConfig(): PriceOracleConfigDto {
        val current = resolveCurrentMember(call)
        current.requireRole(*PRICE_ORACLE_TREASURY_ROLES)
        return transaction { loadConfig() }
    }

    override suspend fun updateOracleConfig(input: PriceOracleConfigInput): PriceOracleConfigDto {
        val current = resolveCurrentMember(call)
        current.requireRole(AccountRole.ADMIN)
        validateConfigInput(input)
        val now = nowLocalDateTime()
        val (updated, quoteAffectingFieldsChanged) =
            transaction {
                // Security-Audit-Runde 1 / S1 (first half): read the CURRENTLY persisted row before
                // applying the update, so we can tell a genuine quote-affecting change apart from a
                // no-op (or peg-only) re-save below -- see quoteOutcomeAffectingFieldsChanged() KDoc.
                val before = loadConfig()
                PriceOracleConfigTable.update({ PriceOracleConfigTable.id eq PRICE_ORACLE_CONFIG_ID }) {
                    it[anchorAsset] = input.anchorAsset
                    it[donationCurrency] = input.donationCurrency
                    it[anchorUnitsPerLtr] = input.anchorUnitsPerLtr
                    it[cacheTtlSeconds] = input.cacheTtlSeconds
                    it[minQuorum] = input.minQuorum
                    it[outlierThresholdBps] = input.outlierThresholdBps
                    it[maxSpreadBps] = input.maxSpreadBps
                    it[updatedAt] = now
                }
                loadConfig() to quoteOutcomeAffectingFieldsChanged(before = before, input = input)
            }
        // Review Round 2 / NEW-1 (second facet), narrowed by Security-Audit-Runde 1 / S1 (first
        // half): a config change that actually affects a quote outcome takes effect on the very next
        // currentQuote() call once the orchestrator's replay/cache state for every anchor is dropped
        // here -- otherwise a tightened threshold or lowered TTL could be masked by a stale
        // LastAttempt replay for up to one refreshIntervalSeconds. See invalidateReplayState() KDoc.
        // Unconditionally invalidating on EVERY save (including a no-op re-save, or one that only
        // touches anchorUnitsPerLtr) was itself the S1 finding -- it let a "tune a field, save, click
        // preview" admin workflow (or a rapid malicious save-loop) burn through the free-tier gold/
        // fiat API quotas via unlimited real fan-outs; see PriceOracleOrchestrator.lastFanoutAt for
        // the second, complementary half of this fix (a hard floor that still applies even when this
        // check correctly determines invalidation IS needed, e.g. a rapid sequence of genuinely
        // different threshold tweaks).
        if (quoteAffectingFieldsChanged) {
            orchestrator.invalidateReplayState()
        }
        return updated
    }

    override suspend fun previewCurrentPrice(): OraclePriceStatusDto {
        val current = resolveCurrentMember(call)
        current.requireRole(*PRICE_ORACLE_TREASURY_ROLES)
        val config = transaction { loadConfig() }
        return when (val outcome = orchestrator.currentQuote(config)) {
            is QuoteOutcome.Ok ->
                OraclePriceStatusDto(
                    status = outcome.quote.status,
                    halted = false,
                    haltReason = null,
                    medianPrice = outcome.quote.medianPrice,
                    sourceIds = outcome.quote.contributingSourceIds,
                    priceTimestamp =
                        outcome.quote.priceTimestamp
                            .toLocalDateTime(TimeZone.currentSystemDefault())
                            .truncatedToDbPrecision(),
                )
            is QuoteOutcome.Halt ->
                OraclePriceStatusDto(
                    status = null,
                    halted = true,
                    haltReason = outcome.reason,
                    medianPrice = null,
                    sourceIds = emptyList(),
                    priceTimestamp = null,
                )
        }
    }

    override suspend fun convertDonationToLtr(input: DonationConversionInput): PriceOracleConversionDto {
        val current = resolveCurrentMember(call)
        current.requireRole(*PRICE_ORACLE_TREASURY_ROLES)
        val targetId = input.memberId.toMemberUuidOrThrow()
        val donationAmount = validateDonationAmount(input.donationAmount)

        val config = transaction { loadConfig() }

        // Network fan-out -- deliberately OUTSIDE any transaction, see class KDoc.
        val quote =
            when (val outcome = orchestrator.currentQuote(config)) {
                is QuoteOutcome.Halt -> throw ConflictException("Donation conversion halted: ${outcome.reason} -- no LTR minted")
                is QuoteOutcome.Ok -> outcome.quote
            }

        val ltrMinted =
            computeLtrMinted(donationAmount = donationAmount, anchorUnitsPerLtr = config.anchorUnitsPerLtr, anchorPrice = quote.medianPrice)
        val now = nowLocalDateTime()
        val priceTimestampLocal = quote.priceTimestamp.toLocalDateTime(TimeZone.currentSystemDefault()).truncatedToDbPrecision()
        val sourcesUsed = quote.contributingSourceIds.joinToString(",")

        return transaction {
            // Security-Audit-Runde 2, G1 (2026-08-19): this was an inline existence-only check --
            // the exact pattern Security-Audit-Runde 1's F4 already replaced in
            // LtrLedgerService.mintLtr/PeerTransferService.transferLtr. A discretionary donation
            // conversion is a fresh MINT, not the settlement of a pre-existing obligation (unlike
            // e.g. AUCTION_SALE_IN), so it belongs on the gated side just like mintLtr does -- see
            // requireLtrEligibleRecipient's own KDoc for why a GUEST/APPLICATION/WITHDRAWN/REJECTED
            // target must not receive LTR it could then neither see nor spend.
            requireLtrEligibleRecipient(memberId = targetId)

            val ledgerEntryId = Uuid.random()
            LtrLedgerEntryTable.insert {
                it[id] = ledgerEntryId
                it[memberId] = targetId
                it[entryType] = LtrLedgerEntryType.MINT
                it[amountLtr] = ltrMinted
                it[referenceType] = null
                it[referenceId] = null
                it[note] =
                    "Price-Oracle-Konvertierung: $donationAmount ${config.donationCurrency} @ ${quote.medianPrice} " +
                    "${config.anchorAsset} (${quote.status}) -> $ltrMinted LTR" +
                    (input.note?.let { " -- $it" } ?: "")
                it[createdBy] = current.memberId
                it[createdAt] = now
            }

            val conversionId = Uuid.random()
            PriceOracleConversionTable.insert {
                it[id] = conversionId
                it[memberId] = targetId
                // Qualified with the table name here (unlike the other columns in this block) --
                // donationAmount/ltrMinted/sourcesUsed are also local vals in this function's
                // scope, which would otherwise shadow the implicit PriceOracleConversionTable
                // receiver and break the DSL's Column<S> resolution (a local BigDecimal/String
                // is not a Column).
                it[PriceOracleConversionTable.donationAmount] = donationAmount
                it[donationCurrency] = config.donationCurrency
                it[anchorAsset] = config.anchorAsset
                it[anchorPrice] = quote.medianPrice
                it[anchorUnitsPerLtr] = config.anchorUnitsPerLtr
                it[PriceOracleConversionTable.ltrMinted] = ltrMinted
                it[priceStatus] = quote.status
                it[sourceCount] = quote.contributingSourceIds.size
                it[PriceOracleConversionTable.sourcesUsed] = sourcesUsed
                it[priceTimestamp] = priceTimestampLocal
                it[ltrLedgerEntryId] = ledgerEntryId
                it[createdById] = current.memberId
                it[createdAt] = now
            }

            logger.info {
                "Donation conversion by ${current.memberId}: $donationAmount ${config.donationCurrency} -> $ltrMinted LTR " +
                    "for member $targetId (conversionId=$conversionId, status=${quote.status}, sources=$sourcesUsed)"
            }

            loadConversion(conversionId)
        }
    }

    /**
     * V0.6.6: the old hardcoded `anchorAsset != BITCOIN_BTC` rejection is replaced by a generic,
     * anchor-aware set of checks -- see [AnchorPolicy] KDoc and [IPriceOracleService] KDoc "Anchor
     * coverage". Order matters: the cheap scalar checks run first, then the two checks that need
     * [orchestrator]'s configured-source counts.
     */
    private fun validateConfigInput(input: PriceOracleConfigInput) {
        val floor = AnchorPolicy.quorumFloor(input.anchorAsset)
        if (input.minQuorum < floor) {
            throw ConflictException("minQuorum must be at least $floor for anchorAsset ${input.anchorAsset}")
        }
        if (input.cacheTtlSeconds <= 0) throw ConflictException("cacheTtlSeconds must be positive")
        val refreshInterval = AnchorPolicy.refreshIntervalSeconds(input.anchorAsset)
        if (input.cacheTtlSeconds < refreshInterval) {
            throw ConflictException(
                "cacheTtlSeconds (${input.cacheTtlSeconds}) must be at least the ${input.anchorAsset} refresh " +
                    "interval (${refreshInterval}s) -- a shorter TTL guarantees a halt window between refreshes " +
                    "(recommended: ${AnchorPolicy.recommendedCacheTtlSeconds(input.anchorAsset)}s)",
            )
        }
        if (input.outlierThresholdBps !in 1..10_000) throw ConflictException("outlierThresholdBps must be between 1 and 10000")
        if (input.maxSpreadBps < input.outlierThresholdBps) {
            throw ConflictException("maxSpreadBps (${input.maxSpreadBps}) must be >= outlierThresholdBps (${input.outlierThresholdBps})")
        }
        if (input.donationCurrency !in SUPPORTED_DONATION_CURRENCIES) {
            throw ConflictException("donationCurrency must be one of $SUPPORTED_DONATION_CURRENCIES")
        }
        if (input.anchorUnitsPerLtr <= BigDecimal.ZERO) throw ConflictException("anchorUnitsPerLtr must be positive")
        // Review Round 1 / MAJOR-2: an anchor switch left paired with a peg from the OLD anchor's
        // scale (e.g. a BTC-scale 0.000001 carried over onto a freshly-selected FIAT anchor) is
        // structurally indistinguishable from a valid config by every check above -- it is positive,
        // and every other field validates independently. See plausiblePegBand's own KDoc for exactly
        // this failure mode (a ~50,000x over-mint for FIAT, ~25x for GOLD_XAU) and how the bounds
        // below were chosen.
        val pegBand = plausiblePegBand(input.anchorAsset)
        if (input.anchorUnitsPerLtr !in pegBand) {
            throw ConflictException(
                "anchorUnitsPerLtr ${input.anchorUnitsPerLtr} is implausible for anchorAsset ${input.anchorAsset} " +
                    "(expected roughly $pegBand ${input.anchorAsset} units per LTR) -- this usually means the peg " +
                    "was left over from a DIFFERENT anchor after switching anchorAsset; update anchorUnitsPerLtr to " +
                    "match the new anchor before saving",
            )
        }

        // "Fail clearly, not silently": selecting an anchor whose sources THIS DEPLOYMENT has not
        // configured would otherwise produce a confusing runtime HALT on the first donation
        // conversion instead of an actionable error here.
        val available = orchestrator.configuredSourceCount(input.anchorAsset)
        if (available < floor) {
            throw ConflictException(
                "anchorAsset ${input.anchorAsset} has only $available configured price source(s) on this " +
                    "deployment, below the required minimum of $floor. ${envHint(input.anchorAsset)}",
            )
        }
        if (available < input.minQuorum) {
            throw ConflictException(
                "minQuorum ${input.minQuorum} exceeds the $available configured price source(s) for " +
                    "anchorAsset ${input.anchorAsset} -- the oracle could never reach quorum",
            )
        }
    }

    private fun validateDonationAmount(amount: BigDecimal): BigDecimal {
        if (amount.scale() > 2) throw ConflictException("donationAmount must have at most 2 decimal places")
        val normalized = amount.setScale(2, RoundingMode.UNNECESSARY)
        if (normalized <= BigDecimal.ZERO) throw ConflictException("donationAmount must be positive")
        return normalized
    }

    private fun computeLtrMinted(
        donationAmount: BigDecimal,
        anchorUnitsPerLtr: BigDecimal,
        anchorPrice: BigDecimal,
    ): BigDecimal {
        // ltrMinted = donationAmount / (anchorUnitsPerLtr * anchorPrice)
        val ltrPriceInDonationCurrency = anchorUnitsPerLtr.multiply(anchorPrice)
        val ltrMinted = donationAmount.divide(ltrPriceInDonationCurrency, LTR_MINTED_SCALE, RoundingMode.HALF_UP)
        if (ltrMinted < MIN_LTR_MINTED) {
            throw ConflictException(
                "Computed ltrMinted $ltrMinted is below the minimum mintable amount $MIN_LTR_MINTED (dust) -- " +
                    "donationAmount too small at the current price",
            )
        }
        return ltrMinted
    }

    private fun loadConfig(): PriceOracleConfigDto =
        PriceOracleConfigTable
            .selectAll()
            .where { PriceOracleConfigTable.id eq PRICE_ORACLE_CONFIG_ID }
            .singleOrNull()
            ?.toPriceOracleConfigDto()
            ?: throw NotFoundException("PriceOracleConfig row $PRICE_ORACLE_CONFIG_ID not found -- baseline seed missing?")

    private fun loadConversion(id: Uuid): PriceOracleConversionDto =
        PriceOracleConversionTable
            .selectAll()
            .where { PriceOracleConversionTable.id eq id }
            .single()
            .toPriceOracleConversionDto()

    private fun nowLocalDateTime(): LocalDateTime = DbClock.nowLocalDateTime()

    private fun String.toMemberUuidOrThrow(): Uuid =
        runCatching { Uuid.parse(this) }.getOrElse { throw NotFoundException("Invalid id: $this") }
}

/**
 * Security-Audit-Runde 1 / S1 (first half): does [input], if applied on top of [before], change ANY
 * field that [PriceOracleOrchestrator.currentQuote]'s median/outlier/spread/cache algorithm actually
 * reads? Used by [PriceOracleService.updateOracleConfig] to decide whether
 * `orchestrator.invalidateReplayState()` is genuinely needed -- see that call site's own comment.
 *
 * **Deliberately excludes [PriceOracleConfigInput.anchorUnitsPerLtr]**: the peg is read ONLY by
 * [PriceOracleService.computeLtrMinted], strictly downstream of [orchestrator]'s quote -- it never
 * enters [PriceOracleOrchestrator.currentQuote] (not the fan-out, not [plausibilityBand], not the
 * median/outlier/spread math, not the cache key). An ADMIN who *only* re-pegs LTR (a routine,
 * expected operation whenever the anchor's real-world price has moved a lot) must not pay for a
 * fresh network fan-out on the next preview -- there is nothing about the ORACLE'S quote that peg
 * change could possibly have invalidated.
 *
 * Every field actually compared below IS read by [PriceOracleOrchestrator.currentQuote] (directly or
 * via [CacheKey]/`effectiveQuorum`/[plausibilityBand]'s caller): [PriceOracleConfigInput.anchorAsset]
 * and [PriceOracleConfigInput.donationCurrency] select the [CacheKey] and the source set;
 * [PriceOracleConfigInput.cacheTtlSeconds] bounds the cache-fallback window;
 * [PriceOracleConfigInput.minQuorum] feeds `effectiveQuorum`;
 * [PriceOracleConfigInput.outlierThresholdBps]/[PriceOracleConfigInput.maxSpreadBps] gate the
 * outlier/spread survivor checks. [before]'s own `id`/`updatedAt` are intentionally never compared
 * (the former is the fixed singleton row id, the latter always differs by construction and carries
 * no quote-relevant information).
 */
private fun quoteOutcomeAffectingFieldsChanged(
    before: PriceOracleConfigDto,
    input: PriceOracleConfigInput,
): Boolean =
    before.anchorAsset != input.anchorAsset ||
        before.donationCurrency != input.donationCurrency ||
        before.cacheTtlSeconds != input.cacheTtlSeconds ||
        before.minQuorum != input.minQuorum ||
        before.outlierThresholdBps != input.outlierThresholdBps ||
        before.maxSpreadBps != input.maxSpreadBps

/** Never names a key VALUE, only the env var NAME -- see class KDoc "Secrets in logs / exceptions". */
private fun envHint(anchor: AnchorAsset): String =
    when (anchor) {
        AnchorAsset.GOLD_XAU ->
            "Configure at least two of LAPIS_ORACLE_GOLDAPI_KEY, LAPIS_ORACLE_METALPRICEAPI_KEY, " +
                "LAPIS_ORACLE_ALPHAVANTAGE_KEY and restart the server -- a gold anchor requires two " +
                "independent sources, and configuring all three leaves margin for one being down."
        AnchorAsset.BITCOIN_BTC ->
            "The three Bitcoin sources need no configuration -- this indicates a wiring bug in Application.module."
        AnchorAsset.FIAT ->
            "The ECB reference-rate source needs no configuration -- this indicates a wiring bug in Application.module."
    }

private fun ResultRow.toPriceOracleConfigDto(): PriceOracleConfigDto =
    PriceOracleConfigDto(
        id = this[PriceOracleConfigTable.id].toString(),
        anchorAsset = this[PriceOracleConfigTable.anchorAsset],
        donationCurrency = this[PriceOracleConfigTable.donationCurrency],
        anchorUnitsPerLtr = this[PriceOracleConfigTable.anchorUnitsPerLtr],
        cacheTtlSeconds = this[PriceOracleConfigTable.cacheTtlSeconds],
        minQuorum = this[PriceOracleConfigTable.minQuorum],
        outlierThresholdBps = this[PriceOracleConfigTable.outlierThresholdBps],
        maxSpreadBps = this[PriceOracleConfigTable.maxSpreadBps],
        updatedAt = this[PriceOracleConfigTable.updatedAt],
    )

private fun ResultRow.toPriceOracleConversionDto(): PriceOracleConversionDto =
    PriceOracleConversionDto(
        id = this[PriceOracleConversionTable.id].toString(),
        memberId = this[PriceOracleConversionTable.memberId].toString(),
        donationAmount = this[PriceOracleConversionTable.donationAmount],
        donationCurrency = this[PriceOracleConversionTable.donationCurrency],
        anchorAsset = this[PriceOracleConversionTable.anchorAsset],
        anchorPrice = this[PriceOracleConversionTable.anchorPrice],
        anchorUnitsPerLtr = this[PriceOracleConversionTable.anchorUnitsPerLtr],
        ltrMinted = this[PriceOracleConversionTable.ltrMinted],
        priceStatus = this[PriceOracleConversionTable.priceStatus],
        sourceCount = this[PriceOracleConversionTable.sourceCount],
        sourcesUsed = this[PriceOracleConversionTable.sourcesUsed],
        priceTimestamp = this[PriceOracleConversionTable.priceTimestamp],
        ltrLedgerEntryId = this[PriceOracleConversionTable.ltrLedgerEntryId].toString(),
        createdById = this[PriceOracleConversionTable.createdById]?.toString(),
        createdAt = this[PriceOracleConversionTable.createdAt],
    )
