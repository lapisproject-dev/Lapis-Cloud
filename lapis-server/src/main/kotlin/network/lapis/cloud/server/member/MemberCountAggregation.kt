package network.lapis.cloud.server.member

import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.toInstant
import kotlinx.datetime.toJavaLocalDateTime
import kotlinx.datetime.toKotlinLocalDateTime
import kotlinx.datetime.toLocalDateTime
import network.lapis.cloud.shared.domain.MemberCountGranularity
import network.lapis.cloud.shared.domain.MemberStatisticsRules
import network.lapis.cloud.shared.domain.MemberStatus
import java.time.temporal.ChronoUnit

/** One aggregated group of the status log: [count] rows that moved from [previous] to [status] at the same instant. */
internal data class StatusDelta(
    val status: MemberStatus,
    val previous: MemberStatus?,
    val effectiveFrom: LocalDateTime,
    val count: Long,
)

/** A period of the history: [start] .. [endExclusive] are calendar days in the organization zone, [boundary] the UTC instant the count is taken at. */
internal data class CountPeriod(
    val start: LocalDate,
    val endExclusive: LocalDate,
    val current: Boolean,
    val boundary: LocalDateTime,
)

/**
 * Welle V1.9.59 -- the pure arithmetic behind the member-count history (no database, no clock of its own).
 *
 * The status log stores, per change, the new status and the previous status of the member. The number of members with status S at
 * an instant T is therefore the sum over all rows before T of `+1` (row.status == S) and `-1` (row.previous_status == S): a member
 * that moved A -> B contributes `-1` to A and `+1` to B exactly when it moved. One `GROUP BY (status, previous_status,
 * effective_from)` over the log is all the database has to do; this object folds the groups into the figures per period.
 *
 * Periods are calendar months, quarters or years **in the organization zone** (never the process zone); the count of a period is
 * taken at the first instant of the following period, converted to UTC with `atStartOfDayIn` (daylight-saving safe). The current
 * period is counted up to "now".
 */
internal object MemberCountAggregation {
    /**
     * Every whole period from the one containing [from] to the one containing [to]. `now` (UTC) decides which period is the
     * current one and is the boundary of that one (plus one microsecond, so a change stamped exactly "now" is included).
     */
    fun periods(
        from: LocalDate,
        to: LocalDate,
        granularity: MemberCountGranularity,
        orgZone: TimeZone,
        now: LocalDateTime,
    ): List<CountPeriod> {
        val today = now.toInstant(TimeZone.UTC).toLocalDateTime(orgZone).date
        val last = MemberStatisticsRules.periodStart(date = to, granularity = granularity)
        val result = mutableListOf<CountPeriod>()
        var start = MemberStatisticsRules.periodStart(date = from, granularity = granularity)
        while (start <= last) {
            val end = MemberStatisticsRules.nextPeriodStart(periodStart = start, granularity = granularity)
            val current = today >= start && today < end
            val boundary =
                if (current) {
                    now.toJavaLocalDateTime().plus(1, ChronoUnit.MICROS).toKotlinLocalDateTime()
                } else {
                    end.atStartOfDayIn(orgZone).toLocalDateTime(TimeZone.UTC)
                }
            result += CountPeriod(start = start, endExclusive = end, current = current, boundary = boundary)
            start = end
        }
        return result
    }

    /**
     * The member count per status at each of [boundaries] (ascending): the sum of the deltas of every group with
     * `effectiveFrom < boundary`. Every [MemberStatus] is a key of every map. A negative figure means the chain of
     * `previous_status` values is inconsistent -- an [IllegalStateException] (the caller logs only numbers).
     */
    fun countsAt(
        deltas: List<StatusDelta>,
        boundaries: List<LocalDateTime>,
    ): List<Map<MemberStatus, Int>> {
        val sorted = deltas.sortedBy { it.effectiveFrom }
        val running = LongArray(MemberStatus.entries.size)
        var next = 0
        return boundaries.map { boundary ->
            while (next < sorted.size && sorted[next].effectiveFrom < boundary) {
                val d = sorted[next]
                running[d.status.ordinal] += d.count
                d.previous?.let { running[it.ordinal] -= d.count }
                next++
            }
            MemberStatus.entries.associateWith { status ->
                val value = running[status.ordinal]
                check(value >= 0) { "member status history is inconsistent: a negative count for a status" }
                value.toInt()
            }
        }
    }
}
