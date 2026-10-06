package network.lapis.cloud.shared.domain

import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.serialization.Serializable

/** How the time axis of the member-count history is divided (calendar months, quarters or years in the organization zone). */
@Serializable
enum class MemberCountGranularity { MONTH, QUARTER, YEAR }

/**
 * Welle V1.9.59 "Mitgliederzahlen ueber Zeit" -- the request of `IMemberStatisticsService.getMemberCountHistory`.
 * [from] and [to] are calendar dates (class D, organization zone); the response covers every whole period of [granularity]
 * from the one that contains [from] to the one that contains [to].
 */
@Serializable
data class MemberCountHistoryQuery(
    val from: LocalDate,
    val to: LocalDate,
    val granularity: MemberCountGranularity,
)

/**
 * One period of the history: the number of members per status at the END of the period. [periodStart] is the first calendar day
 * of the period, [periodEnd] the first day of the NEXT period (exclusive). The current period ends "now". [counts] always carries
 * all [MemberStatus] keys. [reconstructed] marks a point whose figure rests (at least partly) on a reconstruction of the past (the
 * V72 backfill or a CSV import) rather than on changes recorded live.
 */
@Serializable
data class MemberCountPointDto(
    val periodStart: LocalDate,
    val periodEnd: LocalDate,
    val current: Boolean,
    val counts: Map<MemberStatus, Int>,
    val reconstructed: Boolean,
)

/**
 * The answer: one point per period, plus what the client needs to explain the figures. [earliestDate] is the day of the oldest
 * history row (class D, null when there is none), [today] the current day in the organization zone (class D), [reconstructedBefore]
 * the instant up to which the history is a reconstruction (class A, UTC), null when everything was recorded live.
 *
 * No member id, name or single instant of a member ever appears here -- only counts per period.
 */
@Serializable
data class MemberCountHistoryDto(
    val granularity: MemberCountGranularity,
    val points: List<MemberCountPointDto>,
    val earliestDate: LocalDate?,
    val today: LocalDate,
    val reconstructedBefore: LocalDateTime?,
)

/** Rules shared by the client (to disable impossible choices) and the server (to enforce them). */
object MemberStatisticsRules {
    /** Upper bound of points per response (20 years of months). */
    const val MAX_POINTS = 240

    /** The earliest accepted `from`. */
    val MIN_FROM = LocalDate(1900, 1, 1)

    /** The first day of the period of [granularity] that contains [date]. */
    fun periodStart(
        date: LocalDate,
        granularity: MemberCountGranularity,
    ): LocalDate =
        when (granularity) {
            MemberCountGranularity.MONTH -> LocalDate(date.year, date.month.ordinal + 1, 1)
            MemberCountGranularity.QUARTER -> LocalDate(date.year, (date.month.ordinal / 3) * 3 + 1, 1)
            MemberCountGranularity.YEAR -> LocalDate(date.year, 1, 1)
        }

    /** The first day of the period AFTER the one starting at [periodStart]. */
    fun nextPeriodStart(
        periodStart: LocalDate,
        granularity: MemberCountGranularity,
    ): LocalDate {
        val months =
            when (granularity) {
                MemberCountGranularity.MONTH -> 1
                MemberCountGranularity.QUARTER -> 3
                MemberCountGranularity.YEAR -> 12
            }
        val index = periodStart.year * 12 + periodStart.month.ordinal + months
        return LocalDate(index / 12, index % 12 + 1, 1)
    }

    /** The number of periods from the one containing [from] to the one containing [to], inclusive; 0 when [from] is after [to]. */
    fun pointCount(
        from: LocalDate,
        to: LocalDate,
        granularity: MemberCountGranularity,
    ): Int {
        if (from > to) return 0
        val monthsPerPeriod =
            when (granularity) {
                MemberCountGranularity.MONTH -> 1
                MemberCountGranularity.QUARTER -> 3
                MemberCountGranularity.YEAR -> 12
            }
        val first = periodStart(date = from, granularity = granularity)
        val last = periodStart(date = to, granularity = granularity)
        val months = (last.year * 12 + last.month.ordinal) - (first.year * 12 + first.month.ordinal)
        return months / monthsPerPeriod + 1
    }
}
