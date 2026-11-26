package network.lapis.cloud.server.rpc

import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.server.application.ApplicationCall
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.minus
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import network.lapis.cloud.server.audit.AuditLogRecorder
import network.lapis.cloud.server.db.DbClock
import network.lapis.cloud.server.db.generated.LtrLedgerEntryTable
import network.lapis.cloud.server.db.generated.PaymentTransactionTable
import network.lapis.cloud.server.db.generated.PriceOracleConfigTable
import network.lapis.cloud.server.db.generated.PriceOracleConversionTable
import network.lapis.cloud.server.db.isUniqueViolation
import network.lapis.cloud.server.db.truncatedToDbPrecision
import network.lapis.cloud.server.economy.oracle.DonationChannelRules
import network.lapis.cloud.server.economy.oracle.DonationPaymentLink
import network.lapis.cloud.server.economy.oracle.DonationRow
import network.lapis.cloud.server.economy.oracle.DonationStore
import network.lapis.cloud.server.economy.oracle.PriceOracleOrchestrator
import network.lapis.cloud.server.economy.oracle.PriceOracleReferenceRateResolver
import network.lapis.cloud.server.economy.oracle.PriceOracleReferenceRateStore
import network.lapis.cloud.server.economy.oracle.PriceOracleSnapshotStore
import network.lapis.cloud.server.economy.oracle.QuoteOutcome
import network.lapis.cloud.server.economy.oracle.ReferenceRateConversion
import network.lapis.cloud.server.economy.oracle.plausiblePegBand
import network.lapis.cloud.server.payment.sepa.IbanText
import network.lapis.cloud.server.security.CurrentMember
import network.lapis.cloud.server.security.requireRole
import network.lapis.cloud.server.security.resolveCurrentMember
import network.lapis.cloud.server.time.OrganizationTimeZone
import network.lapis.cloud.server.time.ServerClock
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.AnchorAsset
import network.lapis.cloud.shared.domain.AnchorPolicy
import network.lapis.cloud.shared.domain.AuditAction
import network.lapis.cloud.shared.domain.AuditEntityType
import network.lapis.cloud.shared.domain.ConversionRateBasis
import network.lapis.cloud.shared.domain.DonationConversionInput
import network.lapis.cloud.shared.domain.DonationPaymentCandidateDto
import network.lapis.cloud.shared.domain.LtrLedgerEntryType
import network.lapis.cloud.shared.domain.OraclePriceStatusDto
import network.lapis.cloud.shared.domain.PaymentIntent
import network.lapis.cloud.shared.domain.PaymentTransactionStatus
import network.lapis.cloud.shared.domain.PriceHistoryRange
import network.lapis.cloud.shared.domain.PriceOracleConfigDto
import network.lapis.cloud.shared.domain.PriceOracleConfigInput
import network.lapis.cloud.shared.domain.PriceOracleConversionDto
import network.lapis.cloud.shared.domain.PriceSnapshotDto
import network.lapis.cloud.shared.domain.PriceStatus
import network.lapis.cloud.shared.domain.ReferenceDateSource
import network.lapis.cloud.shared.domain.ReferenceRateBasis
import network.lapis.cloud.shared.domain.ReferenceRateConfirmationInput
import network.lapis.cloud.shared.domain.ReferenceRateOutcome
import network.lapis.cloud.shared.domain.ReferenceRateResolutionDto
import network.lapis.cloud.shared.domain.ReferenceRateRules
import network.lapis.cloud.shared.rpc.ConflictException
import network.lapis.cloud.shared.rpc.ForbiddenException
import network.lapis.cloud.shared.rpc.IPriceOracleService
import network.lapis.cloud.shared.rpc.NotFoundException
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.exceptions.ExposedSQLException
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import java.math.BigDecimal
import java.math.RoundingMode
import java.security.MessageDigest
import kotlin.uuid.Uuid

/** The single seeded [PriceOracleConfigTable] row's fixed id -- see `V1__baseline.sql`'s unconditional seed `INSERT` and `19-price-oracle.kuml.kts`'s file header for the exactly-one-row-by-convention rationale. Next unused `...-0000-0000000000fN` slot after `crowdfunding_submission_gate`'s own `...-f4`. */
val PRICE_ORACLE_CONFIG_ID: Uuid = Uuid.parse("00000000-0000-0000-0000-0000000000f5")

private val PRICE_ORACLE_TREASURY_ROLES = arrayOf(AccountRole.TREASURER, AccountRole.BOARD, AccountRole.ADMIN)

private val SUPPORTED_DONATION_CURRENCIES = setOf("EUR", "USD")

/** "Aktuelle Annahme, vor Produktiveinsatz zu verifizieren" -- same disclaimer class as [LtrLedgerService]'s own `MIN_MINT_LTR`. Pure dust/spam floor: a computed `ltrMinted` this small is more likely a fat-fingered donation amount or a badly mis-set peg than a real intent. */
private val MIN_LTR_MINTED = BigDecimal("0.01")

/** Welle "Price-Oracle-Preishistorie". Server-side hard cap for [PriceOracleService.getPriceHistory] -- see that function's KDoc. */
private const val MAX_PRICE_HISTORY_LIMIT = 5_000

/** V173/V174: the UNIQUE constraints that make a donation (and its bank payment) convertible once. */
private val CONVERSION_ONCE_CONSTRAINT_NAMES = listOf("uq_poc_payment_transaction", "uq_poc_donation", "uq_donation_payment_transaction")

/** `sources_used` of a conversion that used a manually confirmed rate. */
private const val MANUAL_SOURCES_USED = "manual"

/** V174: stable (English) message of every "this donation was converted before" refusal; the client maps it to a localized text. */
private const val ALREADY_CONVERTED = "This donation was already converted"

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
    /** V1.9.324: whether the hourly snapshot poller is enabled on this server -- shown to the ADMIN form only (without it every reference-date rate is manual). */
    private val snapshotPollerEnabled: Boolean = false,
) : IPriceOracleService {
    override suspend fun getOracleConfig(): PriceOracleConfigDto {
        val current = resolveCurrentMember(call)
        current.requireRole(*PRICE_ORACLE_TREASURY_ROLES)
        return transaction { loadConfig() }.copy(snapshotPollerEnabled = snapshotPollerEnabled)
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
                // V1.9.324: `null` leaves the stored switch alone (a client that does not know the field must never reset it). The reference-date
                // mode exists only for the gold anchor, so the switch and the anchor are checked together, on the values that will be stored.
                val newBasis = input.conversionRateBasis ?: before.conversionRateBasis
                if (newBasis == ConversionRateBasis.REFERENCE_DATE && input.anchorAsset != AnchorAsset.GOLD_XAU) {
                    throw ConflictException(
                        "The reference-date conversion needs the GOLD_XAU anchor -- switch the conversion back to the current rate before changing the anchor",
                    )
                }
                PriceOracleConfigTable.update({ PriceOracleConfigTable.id eq PRICE_ORACLE_CONFIG_ID }) {
                    it[conversionRateBasis] = newBasis
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
        return updated.copy(snapshotPollerEnabled = snapshotPollerEnabled)
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
                            .toLocalDateTime(ServerClock.zone)
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

        // V1.9.324: the first branch. CURRENT keeps the code below byte for byte (a reference-date field in that mode is an error, not ignored);
        // REFERENCE_DATE never touches the network and resolves the rate from the stored data inside one transaction.
        if (config.conversionRateBasis == ConversionRateBasis.REFERENCE_DATE) {
            return convertAtReferenceDate(current = current, input = input, targetId = targetId, donationAmount = donationAmount)
        }
        if (input.referenceDate != null ||
            input.expectedPrice != null ||
            input.expectedRateRefId != null ||
            input.paymentTransactionId != null ||
            input.donationId != null ||
            input.referenceDateOverrideReason != null
        ) {
            throw ConflictException(
                "referenceDate, expectedPrice, expectedRateRefId, paymentTransactionId, donationId and referenceDateOverrideReason are only valid when the conversion mode is REFERENCE_DATE",
            )
        }

        // Network fan-out -- deliberately OUTSIDE any transaction, see class KDoc.
        val quote =
            when (val outcome = orchestrator.currentQuote(config)) {
                is QuoteOutcome.Halt -> throw ConflictException("Donation conversion halted: ${outcome.reason} -- no LTR minted")
                is QuoteOutcome.Ok -> outcome.quote
            }

        val ltrMinted =
            computeLtrMinted(donationAmount = donationAmount, anchorUnitsPerLtr = config.anchorUnitsPerLtr, anchorPrice = quote.medianPrice)
        val now = nowLocalDateTime()
        val priceTimestampLocal = quote.priceTimestamp.toLocalDateTime(ServerClock.zone).truncatedToDbPrecision()
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
     * V1.9.324: which rate would apply for [referenceDate] -- read only, no network. [memberId] is the intended recipient and only decides
     * `selfConfirmationBlocked`.
     */
    override suspend fun previewReferenceRate(
        referenceDate: LocalDate,
        memberId: String?,
    ): ReferenceRateResolutionDto {
        val current = resolveCurrentMember(call)
        current.requireRole(*PRICE_ORACLE_TREASURY_ROLES)
        val recipient = memberId?.toMemberUuidOrThrow()
        return transaction {
            val config = loadConfig()
            requireReferenceDateMode(config)
            ReferenceRateConversion.toResolutionDto(
                resolution = PriceOracleReferenceRateResolver.resolve(config = config, referenceDate = referenceDate),
                recipient = recipient,
            )
        }
    }

    /** V1.9.327: the open bank donations of one member -- the choices of the reference-date form. Read only. */
    override suspend fun listDonationPaymentCandidates(memberId: String): List<DonationPaymentCandidateDto> {
        val current = resolveCurrentMember(call)
        current.requireRole(*PRICE_ORACLE_TREASURY_ROLES)
        val targetId = memberId.toMemberUuidOrThrow()
        return transaction {
            val config = loadConfig()
            requireReferenceDateMode(config)
            DonationStore.candidatesOf(memberId = targetId, currency = config.donationCurrency).map {
                DonationPaymentCandidateDto(
                    id = it.paymentId.toString(),
                    amount = it.amount,
                    currency = it.currency,
                    bookingDate = it.bookingDate,
                    donationId = it.donationId.toString(),
                    channel = DonationChannelRules.channelOf(bookingDate = it.bookingDate, provider = it.provider),
                    receivedDate = OrganizationTimeZone.dateOf(it.receivedAt),
                )
            }
        }
    }

    /** V1.9.324: records a manual reference rate. Allowed only when no automatic rate exists for the day (a gap, never an override). */
    override suspend fun confirmReferenceRate(input: ReferenceRateConfirmationInput): ReferenceRateResolutionDto {
        val current = resolveCurrentMember(call)
        current.requireRole(*PRICE_ORACLE_TREASURY_ROLES)
        val now = nowLocalDateTime()
        try {
            return transaction {
                val config = loadConfig()
                requireReferenceDateMode(config)
                val resolution =
                    ReferenceRateConversion.confirmManualRate(
                        config = config,
                        referenceDate = input.referenceDate,
                        price = input.price,
                        evidenceText = input.evidenceText,
                        acknowledgeDeviation = input.acknowledgeDeviation,
                        actor = current,
                        now = now,
                    )
                ReferenceRateConversion.toResolutionDto(resolution = resolution, recipient = current.memberId)
            }
        } catch (e: ExposedSQLException) {
            if (e.message?.contains(PriceOracleReferenceRateStore.UNIQUE_CONSTRAINT_NAME, ignoreCase = true) == true) {
                throw ConflictException("A rate for that day was just confirmed by someone else")
            }
            throw e
        }
    }

    /** Reference-date mode: the whole conversion in ONE transaction, no network. Anything but a resolved rate that matches the preview mints nothing. */
    private fun convertAtReferenceDate(
        current: CurrentMember,
        input: DonationConversionInput,
        targetId: Uuid,
        donationAmount: BigDecimal,
    ): PriceOracleConversionDto {
        val referenceDate =
            input.referenceDate ?: throw ConflictException("referenceDate is required in the reference-date conversion mode")
        val now = nowLocalDateTime()
        try {
            return convertAtReferenceDateInTransaction(
                current = current,
                input = input,
                targetId = targetId,
                donationAmount = donationAmount,
                referenceDate = referenceDate,
                now = now,
            )
        } catch (e: ExposedSQLException) {
            // Two requests converting the same payment at once: the UNIQUE constraint lets exactly one win, the whole other transaction (including its
            // MINT) is rolled back. Caught OUTSIDE the transaction, see confirmReferenceRate.
            if (e.isUniqueViolation() && CONVERSION_ONCE_CONSTRAINT_NAMES.any { e.message?.contains(it, ignoreCase = true) == true }) {
                throw ConflictException(ALREADY_CONVERTED)
            }
            throw e
        }
    }

    private fun convertAtReferenceDateInTransaction(
        current: CurrentMember,
        input: DonationConversionInput,
        targetId: Uuid,
        donationAmount: BigDecimal,
        referenceDate: LocalDate,
        now: LocalDateTime,
    ): PriceOracleConversionDto =
        transaction {
            val config = loadConfig()
            requireReferenceDateMode(config)
            requireLtrEligibleRecipient(memberId = targetId)

            // V1.9.328: the donation is mandatory. Lock order is payment first, donation second (see DonationStore), whichever id the caller sent.
            val donationRaw =
                input.donationId?.let { raw ->
                    runCatching { Uuid.parse(raw) }.getOrElse { throw ConflictException(DONATION_NOT_AVAILABLE) }
                }
            val paymentRaw =
                input.paymentTransactionId?.let { raw ->
                    runCatching { Uuid.parse(raw) }.getOrElse { throw ConflictException(DONATION_NOT_AVAILABLE) }
                }
            val paymentId =
                when {
                    donationRaw != null -> {
                        val existing = DonationStore.peekById(donationRaw) ?: throw ConflictException(DONATION_NOT_AVAILABLE)
                        if (paymentRaw != null && paymentRaw != existing.paymentTransactionId) {
                            throw ConflictException("donationId and paymentTransactionId do not match")
                        }
                        existing.paymentTransactionId
                    }
                    paymentRaw != null -> paymentRaw
                    else -> throw ConflictException("A donation is required in mode REFERENCE_DATE")
                }
            val (donation, donationCreated) =
                requireConvertibleDonation(
                    paymentId = paymentId,
                    targetId = targetId,
                    donationAmount = donationAmount,
                    config = config,
                    actor = current,
                    now = now,
                )

            // The origin of the date is derived HERE, never taken from the client. A date other than the booking day needs a justification.
            val dateSource =
                if (donation.bookingDate != null &&
                    referenceDate == donation.bookingDate
                ) {
                    ReferenceDateSource.BANK_BOOKING
                } else {
                    ReferenceDateSource.MANUAL
                }
            val overrideReason = requireOverrideReason(dateSource = dateSource, raw = input.referenceDateOverrideReason)
            val dateSourceNote = if (dateSource == ReferenceDateSource.BANK_BOOKING) "aus Bankbuchung" else "manuell (begründet)"

            val resolution = PriceOracleReferenceRateResolver.resolve(config = config, referenceDate = referenceDate)
            val price = resolution.price
            val refId = PriceOracleReferenceRateResolver.refIdOf(resolution)
            if (resolution.outcome != ReferenceRateOutcome.RESOLVED || price == null || refId == null) {
                throw ConflictException("No rate for $referenceDate (${resolution.outcome}) -- nothing minted; confirm a rate first")
            }
            // Bound to the preview: the rate must still be the one the treasurer looked at. The price makes a round trip through the RPC
            // `Decimal`, which travels as a JSON double (about 17 significant digits), while a stored median can have 18 decimals -- so the
            // comparison is at double precision. The ref id already pins the (immutable) snapshot or manual row; the price is a second check.
            val expectedPrice = input.expectedPrice
            if (expectedPrice == null || price.toDouble() != expectedPrice.toDouble() || input.expectedRateRefId != refId.toString()) {
                throw ConflictException("The rate changed since the preview -- check the preview again")
            }
            val manual = resolution.manualRate
            if (manual != null && manual.confirmedBy == targetId) {
                throw ForbiddenException("A member must not receive LTR converted at a rate he confirmed himself")
            }

            val ltrMinted =
                computeLtrMinted(donationAmount = donationAmount, anchorUnitsPerLtr = config.anchorUnitsPerLtr, anchorPrice = price)
            val snapshot = resolution.snapshot
            val basis = resolution.basis!!
            val ledgerEntryId = Uuid.random()
            LtrLedgerEntryTable.insert {
                it[id] = ledgerEntryId
                it[memberId] = targetId
                it[entryType] = LtrLedgerEntryType.MINT
                it[amountLtr] = ltrMinted
                it[referenceType] = null
                it[referenceId] = null
                it[note] =
                    "Price-Oracle-Konvertierung zum Stichtag $referenceDate (Kurstag ${resolution.effectiveDate}, $basis): " +
                    "$donationAmount ${config.donationCurrency} @ $price ${config.anchorAsset} -> $ltrMinted LTR" +
                    // No free text of the override reason here: the ledger note carries ids and the origin only.
                    ", Spende ${donation.id}, Stichtag $dateSourceNote" +
                    (input.note?.let { " -- $it" } ?: "")
                it[createdBy] = current.memberId
                it[createdAt] = now
            }
            val conversionId = Uuid.random()
            PriceOracleConversionTable.insert {
                it[id] = conversionId
                it[memberId] = targetId
                it[PriceOracleConversionTable.donationAmount] = donation.amount
                it[donationCurrency] = donation.currency
                it[anchorAsset] = config.anchorAsset
                it[anchorPrice] = price
                it[anchorUnitsPerLtr] = config.anchorUnitsPerLtr
                it[PriceOracleConversionTable.ltrMinted] = ltrMinted
                // A manual rate has no oracle status; DEFERRED ("priced afterwards") is the literal for it, `rateBasis` says which case it is.
                it[priceStatus] = snapshot?.status ?: PriceStatus.DEFERRED
                it[sourceCount] = snapshot?.sourceCount ?: 0
                it[sourcesUsed] = snapshot?.sourcesUsed ?: MANUAL_SOURCES_USED
                it[priceTimestamp] = snapshot?.priceTimestamp ?: LocalDateTime(resolution.effectiveDate, LocalTime(0, 0))
                it[ltrLedgerEntryId] = ledgerEntryId
                it[createdById] = current.memberId
                it[createdAt] = now
                it[rateBasis] = basis
                it[PriceOracleConversionTable.referenceDate] = referenceDate
                it[effectiveDate] = resolution.effectiveDate
                it[fallbackReason] = resolution.fallbackReason
                it[snapshotId] = snapshot?.id
                it[referenceRateId] = manual?.id
                it[quorumRequired] = resolution.quorumRequired
                it[paymentTransactionId] = donation.paymentTransactionId
                it[referenceDateSource] = dateSource
                it[donationId] = donation.id
                it[referenceDateOverrideReason] = overrideReason
            }
            if (donationCreated) {
                AuditLogRecorder.record(
                    actorMemberId = current.memberId,
                    actorRole = current.role,
                    entityType = AuditEntityType.DONATION,
                    entityId = donation.id,
                    action = AuditAction.CREATE,
                    after =
                        buildJsonObject {
                            put("donationId", donation.id.toString())
                            put("paymentTransactionId", donation.paymentTransactionId.toString())
                            put("bookingDate", donation.bookingDate?.toString() ?: "none")
                        }.toString(),
                    occurredAt = now,
                )
            }
            AuditLogRecorder.record(
                actorMemberId = current.memberId,
                actorRole = current.role,
                entityType = AuditEntityType.PRICE_ORACLE_CONVERSION,
                entityId = conversionId,
                action = AuditAction.CREATE,
                after =
                    buildJsonObject {
                        put("conversionId", conversionId.toString())
                        put("basis", basis.name)
                        put("referenceDate", referenceDate.toString())
                        put("effectiveDate", resolution.effectiveDate.toString())
                        put("fallbackReason", resolution.fallbackReason.name)
                        put(if (snapshot != null) "snapshotId" else "referenceRateId", refId.toString())
                        // Ids and flags only -- never an IBAN, a payer reference or an amount of the payment.
                        put("donationId", donation.id.toString())
                        put("paymentTransactionId", donation.paymentTransactionId.toString())
                        put("referenceDateSource", dateSource.name)
                        // Length and digest of the justification, never the text itself.
                        if (overrideReason != null) {
                            put("overrideReasonLength", overrideReason.length)
                            put("overrideReasonSha256", sha256Hex(overrideReason))
                        }
                        put("rawValuesStored", resolution.rawValuesStored)
                    }.toString(),
                occurredAt = now,
            )
            loadConversion(conversionId)
        }

    /**
     * V1.9.328: loads the payment with a row lock (first lock), then finds or creates the donation (second lock) and checks that the donation may be
     * converted for [targetId] with exactly [donationAmount]. Every failure is a [ConflictException] that names no foreign data (an unknown donation, a
     * donation of another member and a payment of another member look the same). Returns the donation and whether this call created it.
     */
    private fun requireConvertibleDonation(
        paymentId: Uuid,
        targetId: Uuid,
        donationAmount: BigDecimal,
        config: PriceOracleConfigDto,
        actor: CurrentMember,
        now: LocalDateTime,
    ): Pair<DonationRow, Boolean> {
        val row =
            PaymentTransactionTable
                .selectAll()
                .where { PaymentTransactionTable.id eq paymentId }
                .forUpdate()
                .singleOrNull()
        if (row == null || row[PaymentTransactionTable.memberId] != targetId) throw ConflictException(DONATION_NOT_AVAILABLE)
        if (row[PaymentTransactionTable.intent] != PaymentIntent.DONATION ||
            row[PaymentTransactionTable.status] != PaymentTransactionStatus.CAPTURED ||
            !row[PaymentTransactionTable.currency].equals(config.donationCurrency, ignoreCase = true)
        ) {
            throw ConflictException(DONATION_NOT_AVAILABLE)
        }
        if (row[PaymentTransactionTable.amount].compareTo(donationAmount) != 0) {
            throw ConflictException("donationAmount must equal the amount of the selected donation")
        }
        val (donation, created) =
            DonationStore.ensureForPayment(paymentId = paymentId, createdBy = actor.memberId, now = now)
                ?: throw ConflictException(DONATION_NOT_AVAILABLE)
        // An anonymous donation (no member) is never convertible; a stored donation keeps the booking day it was created with. Without one (no bank line
        // when it was created) it stays without: nothing is checked against a bank line that appeared later (the donation row is insert only).
        if (donation.memberId != targetId ||
            donation.amount.compareTo(donationAmount) != 0 ||
            !donation.currency.equals(config.donationCurrency, ignoreCase = true) ||
            (donation.bookingDate != null && donation.bookingDate != DonationPaymentLink.bookingDateOf(paymentId))
        ) {
            throw ConflictException(DONATION_NOT_AVAILABLE)
        }
        val alreadyConverted =
            PriceOracleConversionTable
                .selectAll()
                .where {
                    (PriceOracleConversionTable.paymentTransactionId eq paymentId) or (PriceOracleConversionTable.donationId eq donation.id)
                }.limit(1)
                .any()
        if (alreadyConverted) throw ConflictException(ALREADY_CONVERTED)
        return donation to created
    }

    /**
     * V1.9.328: the justification of a manually chosen reference date. Required (10..500 characters after trimming, no IBAN) for [ReferenceDateSource.MANUAL],
     * forbidden for [ReferenceDateSource.BANK_BOOKING]. Returns the normalized text, or `null` when none applies. The length is bounded BEFORE the IBAN scan.
     */
    private fun requireOverrideReason(
        dateSource: ReferenceDateSource,
        raw: String?,
    ): String? {
        if (dateSource == ReferenceDateSource.BANK_BOOKING) {
            if (raw != null) throw ConflictException("No reason is allowed when the reference date is the bank booking day")
            return null
        }
        val text = ReferenceRateRules.normalizeOverrideReason(raw ?: "")
        if (!ReferenceRateRules.isOverrideReasonLengthValid(text)) {
            throw ConflictException(
                "The reason must be between ${ReferenceRateRules.OVERRIDE_REASON_MIN} and ${ReferenceRateRules.OVERRIDE_REASON_MAX} characters",
            )
        }
        if (IbanText.containsIban(text)) throw ConflictException("The reason must not contain an IBAN")
        return text
    }

    private fun requireReferenceDateMode(config: PriceOracleConfigDto) {
        if (config.conversionRateBasis != ConversionRateBasis.REFERENCE_DATE) {
            throw ConflictException("The conversion mode is not REFERENCE_DATE")
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
        val ltrMinted = ReferenceRateConversion.ltrFor(amount = donationAmount, anchorUnitsPerLtr = anchorUnitsPerLtr, price = anchorPrice)
        if (ReferenceRateConversion.isBelowMinimum(ltrMinted)) {
            throw ConflictException(
                "Computed ltrMinted $ltrMinted is below the minimum mintable amount $MIN_LTR_MINTED (dust) -- " +
                    "donationAmount too small at the current price",
            )
        }
        return ltrMinted
    }

    /**
     * Welle "Price-Oracle-Preishistorie". Pure read of already-persisted `price_oracle_snapshot`
     * rows ([PriceOracleSnapshotStore.loadHistory]) -- NEVER touches [orchestrator], NEVER
     * triggers a network fan-out. [limit] is clamped to `1..MAX_PRICE_HISTORY_LIMIT` (same
     * `.coerceIn` bounds-clamping discipline as [SepaConfig]'s poll-interval floor); a
     * non-supported [donationCurrency] is rejected exactly like [updateOracleConfig] rejects one.
     */
    override suspend fun getPriceHistory(
        anchorAsset: AnchorAsset,
        range: PriceHistoryRange,
        donationCurrency: String?,
        limit: Int,
    ): List<PriceSnapshotDto> {
        val current = resolveCurrentMember(call)
        current.requireRole(*PRICE_ORACLE_TREASURY_ROLES)
        val boundedLimit = limit.coerceIn(1, MAX_PRICE_HISTORY_LIMIT)
        val currency = (donationCurrency ?: transaction { loadConfig().donationCurrency }).uppercase()
        if (currency !in SUPPORTED_DONATION_CURRENCIES) {
            throw ConflictException("donationCurrency must be one of $SUPPORTED_DONATION_CURRENCIES")
        }
        val since =
            range.days?.let { days ->
                DbClock
                    .nowLocalDateTime()
                    .toInstant(ServerClock.zone)
                    .minus(days, DateTimeUnit.DAY, ServerClock.zone)
                    .toLocalDateTime(ServerClock.zone)
                    .truncatedToDbPrecision()
            }
        return PriceOracleSnapshotStore.loadHistory(
            anchorAsset = anchorAsset,
            donationCurrency = currency,
            since = since,
            limit = boundedLimit,
        )
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

/** V1.9.325: the single oracle configuration row, or `null` when it is missing; inside an open transaction. Shared with the travel expense refund in LTR. */
internal fun loadPriceOracleConfigOrNull(): PriceOracleConfigDto? =
    PriceOracleConfigTable
        .selectAll()
        .where { PriceOracleConfigTable.id eq PRICE_ORACLE_CONFIG_ID }
        .singleOrNull()
        ?.toPriceOracleConfigDto()

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
        conversionRateBasis = this[PriceOracleConfigTable.conversionRateBasis],
    )

/** SHA-256 over the UTF-8 bytes, lower-case hex. A new [MessageDigest] per call (not thread-safe). */
private fun sha256Hex(text: String): String =
    MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

private const val DONATION_NOT_AVAILABLE = "The selected donation cannot be converted for this member"

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
        rateBasis = this[PriceOracleConversionTable.rateBasis],
        referenceDate = this[PriceOracleConversionTable.referenceDate],
        effectiveDate = this[PriceOracleConversionTable.effectiveDate],
        fallbackReason = this[PriceOracleConversionTable.fallbackReason],
        snapshotId = this[PriceOracleConversionTable.snapshotId]?.toString(),
        referenceRateId = this[PriceOracleConversionTable.referenceRateId]?.toString(),
        quorumRequired = this[PriceOracleConversionTable.quorumRequired],
        paymentTransactionId = this[PriceOracleConversionTable.paymentTransactionId]?.toString(),
        referenceDateSource = this[PriceOracleConversionTable.referenceDateSource],
        donationId = this[PriceOracleConversionTable.donationId]?.toString(),
        // Every caller is treasury-gated (convertDonationToLtr); a member-facing reader must blank this field.
        referenceDateOverrideReason = this[PriceOracleConversionTable.referenceDateOverrideReason],
        // No column: a snapshot is immutable, so "no stored single values" is read from the snapshot itself (needs the open transaction of the caller).
        rawValuesStored =
            this[PriceOracleConversionTable.rateBasis] != ReferenceRateBasis.SNAPSHOT ||
                this[PriceOracleConversionTable.snapshotId]?.let { PriceOracleSnapshotStore.sourcesOf(it).isNotEmpty() } != false,
    )
