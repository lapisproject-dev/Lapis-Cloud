package network.lapis.cloud.server.rpc

import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.server.application.ApplicationCall
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import network.lapis.cloud.server.db.generated.LtrLedgerEntryTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.server.db.generated.PriceOracleConfigTable
import network.lapis.cloud.server.db.generated.PriceOracleConversionTable
import network.lapis.cloud.server.economy.oracle.PriceOracleOrchestrator
import network.lapis.cloud.server.economy.oracle.QuoteOutcome
import network.lapis.cloud.server.security.requireRole
import network.lapis.cloud.server.security.resolveCurrentMember
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.AnchorAsset
import network.lapis.cloud.shared.domain.DonationConversionInput
import network.lapis.cloud.shared.domain.LtrLedgerEntryType
import network.lapis.cloud.shared.domain.OraclePriceStatusDto
import network.lapis.cloud.shared.domain.PriceOracleConfigDto
import network.lapis.cloud.shared.domain.PriceOracleConfigInput
import network.lapis.cloud.shared.domain.PriceOracleConversionDto
import network.lapis.cloud.shared.rpc.IPriceOracleService
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import java.math.BigDecimal
import java.math.RoundingMode
import kotlin.time.Clock
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
 * V0.6.5 Price-Oracle fuer die Anker-Bindung RPC surface -- see [IPriceOracleService] KDoc and
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
        return transaction {
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
            loadConfig()
        }
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
                    priceTimestamp = outcome.quote.priceTimestamp.toLocalDateTime(TimeZone.currentSystemDefault()),
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

        val ltrMinted = computeLtrMinted(donationAmount, config.anchorUnitsPerLtr, quote.medianPrice)
        val now = nowLocalDateTime()
        val priceTimestampLocal = quote.priceTimestamp.toLocalDateTime(TimeZone.currentSystemDefault())
        val sourcesUsed = quote.contributingSourceIds.joinToString(",")

        return transaction {
            MemberTable.selectAll().where { MemberTable.id eq targetId }.singleOrNull()
                ?: throw NotFoundException("Member ${input.memberId} not found")

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

    private fun validateConfigInput(input: PriceOracleConfigInput) {
        if (input.minQuorum < 2) throw ConflictException("minQuorum must be at least 2")
        if (input.cacheTtlSeconds <= 0) throw ConflictException("cacheTtlSeconds must be positive")
        if (input.outlierThresholdBps !in 1..10_000) throw ConflictException("outlierThresholdBps must be between 1 and 10000")
        if (input.maxSpreadBps < input.outlierThresholdBps) {
            throw ConflictException("maxSpreadBps (${input.maxSpreadBps}) must be >= outlierThresholdBps (${input.outlierThresholdBps})")
        }
        if (input.donationCurrency !in SUPPORTED_DONATION_CURRENCIES) {
            throw ConflictException("donationCurrency must be one of $SUPPORTED_DONATION_CURRENCIES")
        }
        if (input.anchorUnitsPerLtr <= BigDecimal.ZERO) throw ConflictException("anchorUnitsPerLtr must be positive")
        if (input.anchorAsset != AnchorAsset.BITCOIN_BTC) {
            throw ConflictException(
                "anchorAsset ${input.anchorAsset} is not yet implemented -- only BITCOIN_BTC has wired price sources " +
                    "(see network.lapis.cloud.server.economy.oracle.PriceOracleSource KDoc)",
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

    private fun nowLocalDateTime(): LocalDateTime = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault())

    private fun String.toMemberUuidOrThrow(): Uuid =
        runCatching { Uuid.parse(this) }.getOrElse { throw NotFoundException("Invalid id: $this") }
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
