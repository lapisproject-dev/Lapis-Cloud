package network.lapis.cloud.client

import kotlinx.datetime.LocalDate
import network.lapis.cloud.shared.domain.MemberCountGranularity
import network.lapis.cloud.shared.domain.MemberCountHistoryDto
import network.lapis.cloud.shared.domain.MemberStatisticsRules
import network.lapis.cloud.shared.domain.MemberStatus

/*
 * Welle V1.9.59 "Mitgliederzahlen ueber Zeit" -- the pure part of the member-statistics screen: which period a preset means, which
 * combinations are possible, how a period is labelled and what the key figures say. No widget, no clock of its own (today is a parameter).
 */

/** The time-range presets of the screen. */
internal enum class StatisticsRange { LAST_12_MONTHS, THIS_YEAR, LAST_5_YEARS, SINCE_START }

/** Statuses in the order of the stacked bars, the legend and the table: the ones people look for first at the bottom. */
internal val STATISTICS_STATUS_ORDER: List<MemberStatus> =
    listOf(
        MemberStatus.ACTIVE,
        MemberStatus.APPLICATION,
        MemberStatus.FRIEND,
        MemberStatus.DONOR,
        MemberStatus.GUEST,
        MemberStatus.WITHDRAWN,
        MemberStatus.REJECTED,
        MemberStatus.DECEASED,
    )

/** Statuses whose bars start hidden (a click on the legend shows them): people who are no longer part of the organization. */
internal val STATISTICS_STATUS_HIDDEN_BY_DEFAULT: Set<MemberStatus> =
    setOf(MemberStatus.WITHDRAWN, MemberStatus.REJECTED, MemberStatus.DECEASED)

/** `from` / `to` of [range] as of [today]; [earliest] is the oldest day the log knows (null until a first answer arrived). */
internal fun statisticsBounds(
    range: StatisticsRange,
    today: LocalDate,
    earliest: LocalDate?,
): Pair<LocalDate, LocalDate> {
    val from =
        when (range) {
            StatisticsRange.LAST_12_MONTHS -> {
                val index = today.year * 12 + today.month.ordinal - 11
                LocalDate(index / 12, index % 12 + 1, 1)
            }
            StatisticsRange.THIS_YEAR -> LocalDate(today.year, 1, 1)
            StatisticsRange.LAST_5_YEARS -> LocalDate(today.year - 4, 1, 1)
            StatisticsRange.SINCE_START -> earliest ?: LocalDate(today.year - 4, 1, 1)
        }
    val bounded = if (from < MemberStatisticsRules.MIN_FROM) MemberStatisticsRules.MIN_FROM else from
    return bounded to today
}

/** Whether [range] with [granularity] stays within the server's limit of periods. */
internal fun statisticsCombinationPossible(
    range: StatisticsRange,
    granularity: MemberCountGranularity,
    today: LocalDate,
    earliest: LocalDate?,
): Boolean {
    val (from, to) = statisticsBounds(range, today, earliest)
    return MemberStatisticsRules.pointCount(from, to, granularity) <= MemberStatisticsRules.MAX_POINTS
}

/**
 * The axis / table label of a period: `2026-10`, `Q4 2026` or `2026`; a running period carries [runningSuffix] (e.g. "laufend") in
 * brackets. Language-neutral on purpose -- no month names to translate.
 */
internal fun statisticsPeriodLabel(
    start: LocalDate,
    granularity: MemberCountGranularity,
    current: Boolean,
    runningSuffix: String,
): String {
    val base =
        when (granularity) {
            MemberCountGranularity.MONTH -> "${start.year}-${(start.month.ordinal + 1).toString().padStart(2, '0')}"
            MemberCountGranularity.QUARTER -> quarterLabel(start)
            MemberCountGranularity.YEAR -> "${start.year}"
        }
    return if (current) "$base ($runningSuffix)" else base
}

private fun quarterLabel(start: LocalDate): String = "Q${start.month.ordinal / 3 + 1} ${start.year}"

/** The slug of [granularity] used in the CSV file name (German, like the rest of the export). */
internal fun statisticsGranularitySlug(granularity: MemberCountGranularity): String =
    when (granularity) {
        MemberCountGranularity.MONTH -> "monat"
        MemberCountGranularity.QUARTER -> "quartal"
        MemberCountGranularity.YEAR -> "jahr"
    }

/** Active members now (the last point) and their change since the end of the first period, or null with fewer than two points. */
internal data class StatisticsKeyFigures(
    val activeNow: Int,
    val firstPeriodStart: LocalDate,
    val activeChange: Int?,
)

internal fun statisticsKeyFigures(dto: MemberCountHistoryDto): StatisticsKeyFigures? {
    val first = dto.points.firstOrNull() ?: return null
    val last = dto.points.last()
    val now = last.counts[MemberStatus.ACTIVE] ?: 0
    val change = if (dto.points.size >= 2) now - (first.counts[MemberStatus.ACTIVE] ?: 0) else null
    return StatisticsKeyFigures(activeNow = now, firstPeriodStart = first.periodStart, activeChange = change)
}

/** A calendar day as `YYYY-MM-DD` -- the machine-readable form (CSV cells, file names), never shown as display text. */
internal fun isoDay(date: LocalDate): String = date.toString()

/** `+5`, `-3`, `0`. */
internal fun signedNumber(value: Int): String = if (value > 0) "+$value" else value.toString()
