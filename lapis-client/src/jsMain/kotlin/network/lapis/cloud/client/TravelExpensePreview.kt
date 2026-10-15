package network.lapis.cloud.client

import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlinx.datetime.daysUntil
import network.lapis.cloud.shared.domain.CalcResult
import network.lapis.cloud.shared.domain.CentsConversion
import network.lapis.cloud.shared.domain.DayInput
import network.lapis.cloud.shared.domain.DayResult
import network.lapis.cloud.shared.domain.TravelExpenseCalculator
import network.lapis.cloud.shared.domain.TravelExpenseDeadline
import network.lapis.cloud.shared.domain.TravelExpenseLineDto
import network.lapis.cloud.shared.domain.TravelExpenseLineKind
import network.lapis.cloud.shared.domain.TravelExpenseRateSet
import network.lapis.cloud.shared.domain.TravelExpenseReportDto

/**
 * Welle V1.9.122 -- the CLIENT side of the travel expense calculation, deliberately tiny: this file
 * contains NO calculation rule of its own. Every number comes from the shared
 * `TravelExpenseCalculator` (the very code the server runs and persists with), this file only
 * assembles its inputs from what the day grid currently shows. The result is a PREVIEW -- the
 * server recomputes everything on save and is the only authority.
 */
internal data class TravelExpenseDayEntry(
    val date: LocalDate,
    val timeFrom: LocalTime?,
    val timeTo: LocalTime?,
    val breakfastProvided: Boolean,
    val lunchProvided: Boolean,
    val dinnerProvided: Boolean,
    val destination: String?,
)

/** One day of the preview: [result] is `null` while the day is not filled in completely (it then counts as 0). */
internal data class TravelExpenseDayPreview(
    val entry: TravelExpenseDayEntry,
    val result: DayResult?,
)

internal data class TravelExpensePreview(
    val days: List<TravelExpenseDayPreview>,
    val totals: TravelExpenseCalculator.Totals,
)

/** The mornings that an overnight stay WITH breakfast already provides (first night, nights) -- the shared rule, fed from the DTOs. */
internal fun travelExpenseForcedBreakfastDates(lines: List<TravelExpenseLineDto>): Set<LocalDate> =
    TravelExpenseCalculator.forcedBreakfastDates(
        lines
            .filter { it.kind == TravelExpenseLineKind.ACCOMMODATION && it.breakfastIncluded == true }
            .mapNotNull { line ->
                val first = line.dayDate
                val nights = line.nights
                if (first != null && nights != null) first to nights else null
            },
    )

internal fun travelExpensePreview(
    report: TravelExpenseReportDto,
    rates: TravelExpenseRateSet,
    entries: List<TravelExpenseDayEntry>,
    advanceCents: Long,
): TravelExpensePreview {
    val forced = travelExpenseForcedBreakfastDates(report.lines)
    val days =
        entries.map { entry ->
            val result =
                TravelExpenseCalculator.day(
                    input =
                        DayInput(
                            date = entry.date,
                            timeFrom = entry.timeFrom,
                            timeTo = entry.timeTo,
                            breakfastProvided = entry.breakfastProvided,
                            lunchProvided = entry.lunchProvided,
                            dinnerProvided = entry.dinnerProvided,
                        ),
                    travelFrom = report.travelFrom,
                    travelTo = report.travelTo,
                    forcedBreakfastDates = forced,
                    rates = rates,
                )
            TravelExpenseDayPreview(entry = entry, result = (result as? CalcResult.Ok)?.value)
        }
    // Every non-day line keeps the amount the server computed (mileage, receipts, legacy lines).
    val others = report.lines.filter { it.kind != TravelExpenseLineKind.DAY_ALLOWANCE }
    val totals =
        TravelExpenseCalculator.totals(
            lineNetCents = days.map { it.result?.netCents ?: 0L } + others.map { it.amountCents },
            deductionCents = days.map { it.result?.deductionCents ?: 0L },
            advanceCents = advanceCents,
        )
    return TravelExpensePreview(days = days, totals = totals)
}

/** `1234` minutes -> `20:34`; the "Stunden h:mm" shown next to a day's times. */
internal fun travelExpenseDuration(minutes: Int): String = "${minutes / 60}:${pad2(minutes % 60)}"

/** `"08:30"` (a time typed in the locale of [locale], V1.9.137; en-US also `8:30 AM`) -> [LocalTime]; blank or malformed -> `null`. */
internal fun travelExpenseParseTime(
    raw: String?,
    locale: TemporalLocale = currentTemporalLocale(),
): LocalTime? {
    val text = raw?.trim().orEmpty()
    if (text.isEmpty()) return null
    return (parseLocalTime(text, locale) as? TimeParse.Ok)?.time
}

/** `"12,50"` / `"12.5"` -> 1250 cents; blank -> 0; malformed or more than 2 decimals -> `null`. Whole cents, never a `Double`. */
internal fun travelExpenseParseCents(raw: String?): Long? {
    val text = raw?.trim().orEmpty().replace(',', '.')
    if (text.isEmpty()) return 0L
    if (text.startsWith("-")) return null
    return CentsConversion.parseExact(text)
}

/** Cents -> the text a person types back (`1250` -> `12,50`), used to prefill the advance field. */
internal fun travelExpenseCentsText(cents: Long): String = CentsConversion.toDecimalString(cents).replace('.', ',')

/** `true` once the informational submission deadline (31 December of the travel year) has passed. Never blocks. */
internal fun travelExpenseDeadlineOverdue(
    report: TravelExpenseReportDto,
    today: LocalDate,
): Boolean = TravelExpenseDeadline.isOverdue(report.travelTo, today)

/** `true` if the travel period would orphan a day line that already carries data (mirrors the server's `day_lines_outside_range`). */
internal fun travelExpenseDayLinesOutsideRange(
    report: TravelExpenseReportDto,
    newFrom: LocalDate,
    newTo: LocalDate,
): Boolean =
    report.lines.any { line ->
        val day = line.dayDate
        line.kind == TravelExpenseLineKind.DAY_ALLOWANCE &&
            day != null &&
            (day < newFrom || day > newTo) &&
            (
                line.timeFrom != null ||
                    line.timeTo != null ||
                    line.breakfastProvided ||
                    line.lunchProvided ||
                    line.dinnerProvided ||
                    !line.destination.isNullOrBlank()
            )
    }

/** Whole days of the travel period, 1 for a single-day trip. */
internal fun travelExpenseSpanDays(
    from: LocalDate,
    to: LocalDate,
): Int = from.daysUntil(to) + 1

internal val TRAVEL_EXPENSE_DAY_UNIT = DateTimeUnit.DAY
