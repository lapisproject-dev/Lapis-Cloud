package network.lapis.cloud.server.economy.oracle

import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.plus
import network.lapis.cloud.server.time.ServerClock
import network.lapis.cloud.shared.domain.AnchorAsset
import network.lapis.cloud.shared.domain.AnchorPolicy
import network.lapis.cloud.shared.domain.PriceOracleConfigDto
import network.lapis.cloud.shared.domain.PriceStatus
import network.lapis.cloud.shared.domain.ReferenceDateFallback
import network.lapis.cloud.shared.domain.ReferenceRateBasis
import network.lapis.cloud.shared.domain.ReferenceRateOutcome
import network.lapis.cloud.shared.domain.ReferenceRateRules
import network.lapis.cloud.shared.rpc.ConflictException
import java.math.BigDecimal
import kotlin.uuid.Uuid

/** The calendar day of a reference date is a day of the organization's time zone, not of UTC. */
internal val REFERENCE_DATE_ZONE: TimeZone = TimeZone.of("Europe/Berlin")

/** What the resolver found for one reference date. Everything but [ReferenceRateOutcome.RESOLVED] means "no automatic rate". */
internal data class ReferenceRateResolution(
    val referenceDate: LocalDate,
    val effectiveDate: LocalDate,
    val fallbackReason: ReferenceDateFallback,
    val outcome: ReferenceRateOutcome,
    val basis: ReferenceRateBasis?,
    val price: BigDecimal?,
    val snapshot: SnapshotWithSources?,
    val manualRate: ReferenceRateRow?,
    val quorumRequired: Int,
    val legacyMedianHint: BigDecimal?,
    /** Median of the latest snapshot of the day, whatever its status -- only a yardstick for a manual rate, never a rate itself. */
    val comparisonMedian: BigDecimal?,
    /** V1.9.327: false when the rate is a legacy snapshot (taken before V171, no stored single values) accepted as a closing rate. */
    val rawValuesStored: Boolean = true,
)

/**
 * V1.9.324 -- the ONE place that turns a reference date into a rate. Read only (database only, never the network), expects an open
 * transaction. Nothing else may call [PriceOracleSnapshotStore.loadDay] or derive a reference-date rate (tripwire).
 *
 * Steps: (1) validate the date (not before 2000-01-01, strictly before today in the organization zone, anchor must be gold);
 * (2) move it back to the last London trading day ([LbmaGoldCalendar]); (3) a manually confirmed rate for that day wins; (4) otherwise the latest
 * qualifying snapshot of that calendar day (organization zone, converted to the UTC storage window with daylight-saving time) -- qualifying means
 * status LIVE, source count at least the quorum, and EITHER the stored raw values complete (`included` rows == `source_count`) OR a legacy snapshot
 * (no stored values at all and captured before the raw-values cutoff, see [RawValuesCutoff]; its median is accepted and flagged
 * `rawValuesStored = false`). Of several qualifying snapshots the latest wins. A snapshot captured after the cutoff with missing or incomplete values
 * never qualifies (defect or manipulation): the outcome is [ReferenceRateOutcome.RAW_VALUES_MISSING]; (5) otherwise an outcome that says why there
 * is no rate.
 */
internal object PriceOracleReferenceRateResolver {
    fun resolve(
        config: PriceOracleConfigDto,
        referenceDate: LocalDate,
    ): ReferenceRateResolution {
        if (referenceDate < ReferenceRateRules.earliest()) {
            throw ConflictException("referenceDate must not be before ${ReferenceRateRules.earliest()}")
        }
        // A day that is not over yet has no closing rate: its price still changes until midnight.
        if (referenceDate >= ServerClock.todayIn(REFERENCE_DATE_ZONE)) {
            throw ConflictException("referenceDate must be before today -- the rate of a day is only final once the day is over")
        }
        if (config.anchorAsset != AnchorAsset.GOLD_XAU) {
            throw ConflictException("A reference-date rate exists only for the GOLD_XAU anchor")
        }
        val (effectiveDate, fallback) = LbmaGoldCalendar.previousTradingDayOnOrBefore(referenceDate)
        val quorumRequired = maxOf(config.minQuorum, AnchorPolicy.quorumFloor(config.anchorAsset))
        val currency = config.donationCurrency.uppercase()

        val fromUtc = ServerClock.dateStartToSystem(date = effectiveDate, orgZone = REFERENCE_DATE_ZONE)
        val toUtc = ServerClock.dateStartToSystem(date = effectiveDate.plus(1, DateTimeUnit.DAY), orgZone = REFERENCE_DATE_ZONE)
        val snapshots =
            PriceOracleSnapshotStore.loadDay(
                anchorAsset = config.anchorAsset,
                donationCurrency = currency,
                fromUtc = fromUtc,
                toUtcExclusive = toUtc,
            )
        val comparison = snapshots.firstOrNull()?.medianPrice

        val manual =
            PriceOracleReferenceRateStore.find(
                anchorAsset = config.anchorAsset,
                donationCurrency = currency,
                effectiveDate = effectiveDate,
            )
        if (manual != null) {
            return ReferenceRateResolution(
                referenceDate = referenceDate,
                effectiveDate = effectiveDate,
                fallbackReason = fallback,
                outcome = ReferenceRateOutcome.RESOLVED,
                basis = ReferenceRateBasis.MANUAL,
                price = manual.price,
                snapshot = null,
                manualRate = manual,
                quorumRequired = quorumRequired,
                legacyMedianHint = null,
                comparisonMedian = comparison,
            )
        }

        val quorumOk = snapshots.filter { it.sourceCount >= quorumRequired }
        val statusAndQuorumOk = quorumOk.filter { it.status == PriceStatus.LIVE }
        val cutoff = RawValuesCutoffHolder.current.cutoffUtc()

        fun complete(snap: SnapshotWithSources) = snap.sourceCount > 0 && snap.sources.count { it.included } == snap.sourceCount

        fun legacy(snap: SnapshotWithSources) = snap.sources.isEmpty() && snap.capturedAt < cutoff
        // Snapshots are newest first: the latest QUALIFYING one wins (a defective newer one falls back to an older qualifying one).
        val qualified = statusAndQuorumOk.firstOrNull { complete(it) || legacy(it) }

        fun outcome(
            outcome: ReferenceRateOutcome,
            hint: BigDecimal? = null,
        ) = ReferenceRateResolution(
            referenceDate = referenceDate,
            effectiveDate = effectiveDate,
            fallbackReason = fallback,
            outcome = outcome,
            basis = null,
            price = null,
            snapshot = null,
            manualRate = null,
            quorumRequired = quorumRequired,
            legacyMedianHint = hint,
            comparisonMedian = comparison,
        )
        return when {
            qualified != null ->
                ReferenceRateResolution(
                    referenceDate = referenceDate,
                    effectiveDate = effectiveDate,
                    fallbackReason = fallback,
                    outcome = ReferenceRateOutcome.RESOLVED,
                    basis = ReferenceRateBasis.SNAPSHOT,
                    price = qualified.medianPrice,
                    snapshot = qualified,
                    manualRate = null,
                    quorumRequired = quorumRequired,
                    legacyMedianHint = null,
                    comparisonMedian = comparison,
                    rawValuesStored = complete(qualified),
                )
            snapshots.isEmpty() -> outcome(ReferenceRateOutcome.NO_DATA)
            statusAndQuorumOk.isNotEmpty() -> outcome(ReferenceRateOutcome.RAW_VALUES_MISSING, hint = statusAndQuorumOk.first().medianPrice)
            // The quorum was met, but no snapshot of the day is LIVE (a configured source was missing in every poll).
            quorumOk.isNotEmpty() -> outcome(ReferenceRateOutcome.STATUS_NOT_LIVE)
            else -> outcome(ReferenceRateOutcome.QUORUM_MISSING)
        }
    }

    /** The id a preview binds the conversion to: the snapshot id or the manual rate id. */
    fun refIdOf(resolution: ReferenceRateResolution): Uuid? = resolution.snapshot?.id ?: resolution.manualRate?.id
}
