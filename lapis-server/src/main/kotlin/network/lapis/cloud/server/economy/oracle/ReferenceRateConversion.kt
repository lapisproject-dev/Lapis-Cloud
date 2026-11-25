package network.lapis.cloud.server.economy.oracle

import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import network.lapis.cloud.server.audit.AuditLogRecorder
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.server.security.CurrentMember
import network.lapis.cloud.shared.domain.AuditAction
import network.lapis.cloud.shared.domain.AuditEntityType
import network.lapis.cloud.shared.domain.PriceOracleConfigDto
import network.lapis.cloud.shared.domain.ReferenceRateBasis
import network.lapis.cloud.shared.domain.ReferenceRateOutcome
import network.lapis.cloud.shared.domain.ReferenceRateResolutionDto
import network.lapis.cloud.shared.domain.ReferenceRateRules
import network.lapis.cloud.shared.domain.ReferenceRateSourceDto
import network.lapis.cloud.shared.rpc.ConflictException
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import java.math.BigDecimal
import java.math.RoundingMode
import kotlin.uuid.Uuid

/**
 * V1.9.325 -- the facade over the reference-date rate machinery of V1.9.324, shared by the donation conversion
 * ([network.lapis.cloud.server.rpc.PriceOracleService]) and the travel expense refund in LTR
 * ([network.lapis.cloud.server.rpc.TravelExpenseLtrPayout]). It adds NO rate logic of its own: it delegates to
 * [PriceOracleReferenceRateResolver] and moves the conversion arithmetic, the manual-rate confirmation and the DTO mapping out of the
 * donation service so a second caller does not copy them. It deliberately does NOT check the donation conversion mode
 * (`conversionRateBasis`): that check stays in the donation RPCs, in front of the call.
 *
 * All functions expect an open transaction.
 */
internal object ReferenceRateConversion {
    /** Scale of an LTR amount (two decimals, `HALF_UP`) -- the same rounding as the donation conversion. */
    const val LTR_SCALE = 2
    const val RATE_PRICE_SCALE = 18

    /** Dust floor: a computed LTR amount below this is rejected. */
    val MIN_LTR: BigDecimal = BigDecimal("0.01")

    fun resolve(
        config: PriceOracleConfigDto,
        referenceDate: LocalDate,
    ): ReferenceRateResolution = PriceOracleReferenceRateResolver.resolve(config = config, referenceDate = referenceDate)

    /** `amount / (anchorUnitsPerLtr * price)`, scale 2, `HALF_UP`. Does not check the dust floor, see [isBelowMinimum]. */
    fun ltrFor(
        amount: BigDecimal,
        anchorUnitsPerLtr: BigDecimal,
        price: BigDecimal,
    ): BigDecimal = amount.divide(anchorUnitsPerLtr.multiply(price), LTR_SCALE, RoundingMode.HALF_UP)

    fun isBelowMinimum(ltr: BigDecimal): Boolean = ltr < MIN_LTR

    /** Deviation of [price] from [reference] in basis points, rounded down; 0 when [reference] is zero. */
    fun deviationBps(
        price: BigDecimal,
        reference: BigDecimal,
    ): Int {
        if (reference.signum() == 0) return 0
        return (price - reference)
            .abs()
            .multiply(BigDecimal(10_000))
            .divide(reference, 0, RoundingMode.DOWN)
            .toInt()
    }

    /**
     * Records a manual rate for the effective day of [referenceDate]. Allowed only when no automatic rate exists (a gap, never an override).
     * Returns the resolution after the insert. The evidence text and the price never reach the audit entry.
     */
    fun confirmManualRate(
        config: PriceOracleConfigDto,
        referenceDate: LocalDate,
        price: BigDecimal,
        evidenceText: String,
        acknowledgeDeviation: Boolean,
        actor: CurrentMember,
        now: LocalDateTime,
    ): ReferenceRateResolution {
        val evidence = ReferenceRateRules.normalizeEvidence(evidenceText)
        if (evidence.length < ReferenceRateRules.EVIDENCE_MIN || evidence.length > ReferenceRateRules.EVIDENCE_MAX) {
            throw ConflictException(
                "The evidence text must have between ${ReferenceRateRules.EVIDENCE_MIN} and ${ReferenceRateRules.EVIDENCE_MAX} characters",
            )
        }
        if (price <= BigDecimal.ZERO) throw ConflictException("price must be positive")
        if (price.stripTrailingZeros().scale() > RATE_PRICE_SCALE) {
            throw ConflictException("price must have at most $RATE_PRICE_SCALE decimal places")
        }
        if (price !in plausibilityBand(config.anchorAsset)) {
            throw ConflictException(
                "price ${price.toPlainString()} is implausible for ${config.anchorAsset} -- check the unit (price of one unit in ${config.donationCurrency})",
            )
        }
        val resolution = resolve(config = config, referenceDate = referenceDate)
        if (resolution.basis == ReferenceRateBasis.MANUAL) {
            throw ConflictException("A rate for ${resolution.effectiveDate} was already confirmed and cannot be changed")
        }
        if (resolution.outcome == ReferenceRateOutcome.RESOLVED) {
            throw ConflictException(
                "The price history already holds a rate for ${resolution.effectiveDate} -- no manual confirmation needed",
            )
        }
        val comparison = resolution.comparisonMedian
        val deviates = comparison != null && deviationBps(price = price, reference = comparison) > config.maxSpreadBps
        if (deviates && !acknowledgeDeviation) {
            throw ConflictException(
                "The rate deviates from the stored price of that day by more than ${config.maxSpreadBps} bps -- acknowledge the deviation to confirm it",
            )
        }
        val rateId = Uuid.random()
        PriceOracleReferenceRateStore.insert(
            ReferenceRateRow(
                id = rateId,
                anchorAsset = config.anchorAsset,
                donationCurrency = config.donationCurrency,
                effectiveDate = resolution.effectiveDate,
                price = price,
                evidenceText = evidence,
                confirmedBy = actor.memberId,
                confirmedAt = now,
                referenceSnapshotMedian = comparison,
                deviationAcknowledged = deviates && acknowledgeDeviation,
            ),
        )
        AuditLogRecorder.record(
            actorMemberId = actor.memberId,
            actorRole = actor.role,
            entityType = AuditEntityType.PRICE_ORACLE_REFERENCE_RATE,
            entityId = rateId,
            action = AuditAction.CREATE,
            after =
                buildJsonObject {
                    put("anchor", config.anchorAsset.name)
                    put("currency", config.donationCurrency)
                    put("referenceDate", referenceDate.toString())
                    put("effectiveDate", resolution.effectiveDate.toString())
                    put("referenceRateId", rateId.toString())
                    put("evidenceLength", evidence.length)
                    put("deviationAcknowledged", acknowledgeDeviation)
                }.toString(),
            occurredAt = now,
        )
        return resolve(config = config, referenceDate = referenceDate)
    }

    fun toResolutionDto(
        resolution: ReferenceRateResolution,
        recipient: Uuid?,
    ): ReferenceRateResolutionDto {
        val manual = resolution.manualRate
        val snapshot = resolution.snapshot
        val confirmerName =
            manual?.let { m ->
                MemberTable
                    .selectAll()
                    .where { MemberTable.id eq m.confirmedBy }
                    .singleOrNull()
                    ?.get(MemberTable.displayName)
            }
        val comparison = resolution.comparisonMedian
        return ReferenceRateResolutionDto(
            referenceDate = resolution.referenceDate,
            effectiveDate = resolution.effectiveDate,
            fallbackReason = resolution.fallbackReason,
            outcome = resolution.outcome,
            basis = resolution.basis,
            price = resolution.price,
            snapshotId = snapshot?.id?.toString(),
            referenceRateId = manual?.id?.toString(),
            sources =
                snapshot?.sources.orEmpty().map {
                    ReferenceRateSourceDto(
                        sourceId = it.sourceId,
                        rawPrice = it.rawPrice,
                        observedAt = it.observedAt,
                        included = it.included,
                    )
                },
            quorumRequired = resolution.quorumRequired,
            legacyMedianHint = resolution.legacyMedianHint,
            confirmedByDisplayName = confirmerName,
            confirmedAt = manual?.confirmedAt,
            evidenceText = manual?.evidenceText,
            selfConfirmationBlocked = manual != null && recipient != null && manual.confirmedBy == recipient,
            deviationBpsFromSnapshot =
                if (manual != null && comparison != null) deviationBps(price = manual.price, reference = comparison) else null,
            comparisonMedian = comparison,
        )
    }
}
