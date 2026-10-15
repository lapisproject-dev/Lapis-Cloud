package network.lapis.cloud.client

import io.kvision.core.Container
import io.kvision.html.Div
import io.kvision.html.TAG
import io.kvision.html.Tag
import io.kvision.i18n.gettext
import io.kvision.i18n.tr
import kotlinx.datetime.LocalDate
import network.lapis.cloud.shared.domain.CentsConversion
import network.lapis.cloud.shared.domain.DayResult
import network.lapis.cloud.shared.domain.TravelExpenseCalculator
import network.lapis.cloud.shared.domain.TravelExpenseLineDto
import network.lapis.cloud.shared.domain.TravelExpenseLineKind
import network.lapis.cloud.shared.domain.TravelExpensePayoutChoice
import network.lapis.cloud.shared.domain.TravelExpenseRateSet
import network.lapis.cloud.shared.domain.TravelExpenseReportDto

/**
 * V1.9.132 -- the live breakdown "So setzt sich der Betrag zusammen": the daily allowance, the meal deductions, every other cost line, the sum, the
 * advance and the payout, as a real table, plus one collapsed `<details>` per travel day with the day's own calculation.
 *
 * **No calculation rule of its own.** Every figure comes from the shared `TravelExpenseCalculator` (via [travelExpensePreview]) or from a stored
 * line; this file only arranges them. The one thing it derives is the SPLIT of a mileage line into "own km" and "passenger km" for display -- and
 * only when the two parts add up to the stored amount exactly; otherwise it shows the stored amount without a formula.
 *
 * **Which rates.** A draft shows the CURRENT rates ([rates] from the settings); a submitted/decided report ONLY its frozen `ratesSnapshot` -- never the
 * current instance rates (they may have changed since). A report without a snapshot is [BreakdownModel.NoSnapshot]: the page says so instead of
 * guessing.
 *
 * Not a live region: the sentence a screen reader hears stays the totals bar's job.
 */
internal data class BreakdownMealRow(
    val meal: Meal,
    val percent: Int,
    val cents: Long,
)

internal data class DayBreakdown(
    val date: LocalDate,
    /** `null` while the day is not filled in completely. */
    val minutes: Int?,
    val baseCents: Long,
    val meals: List<BreakdownMealRow>,
    /** The day's allowance when the meals together exceeded it and the deduction was capped; `null` otherwise. */
    val cappedAtCents: Long?,
    val deductionCents: Long,
    val netCents: Long,
    val complete: Boolean,
    /** The 24 h tier the meal percentages are taken of. */
    val fullTierCents: Long,
)

internal enum class BreakdownRowKind { GROSS, DEDUCTION, LINE, SUBTOTAL, ADVANCE, PAYOUT }

/** [cents] is signed as shown: a deduction and the advance are negative. */
internal data class BreakdownRow(
    val kind: BreakdownRowKind,
    val label: String,
    val cents: Long,
)

internal sealed interface BreakdownModel {
    data class Ok(
        val days: List<DayBreakdown>,
        val rows: List<BreakdownRow>,
        val totals: TravelExpenseCalculator.Totals,
    ) : BreakdownModel

    data object NoSnapshot : BreakdownModel
}

/**
 * [rates] is the rate set in force (current settings for a draft, `report.ratesSnapshot` otherwise), `null` when there is none.
 * [entries] is what the day grid shows right now (a draft); `null` reads the day entries from the report's stored day lines.
 */
internal fun buildBreakdown(
    report: TravelExpenseReportDto,
    rates: TravelExpenseRateSet?,
    entries: List<TravelExpenseDayEntry>?,
    advanceCents: Long,
): BreakdownModel {
    if (rates == null) return BreakdownModel.NoSnapshot
    val dayEntries = entries ?: storedDayEntries(report)
    val preview = travelExpensePreview(report, rates, dayEntries, advanceCents)
    val forced = travelExpenseForcedBreakfastDates(report.lines)

    val days =
        preview.days.map { day ->
            val entry = day.entry
            val result = day.result
            val meals =
                buildList {
                    if (entry.breakfastProvided || entry.date in forced) add(mealRow(Meal.BREAKFAST, rates))
                    if (entry.lunchProvided) add(mealRow(Meal.LUNCH, rates))
                    if (entry.dinnerProvided) add(mealRow(Meal.DINNER, rates))
                }
            dayBreakdown(entry.date, result, meals, rates)
        }

    val rows = mutableListOf<BreakdownRow>()
    if (days.isNotEmpty()) {
        rows += BreakdownRow(BreakdownRowKind.GROSS, gettext("Tagegeld gesamt (brutto)"), days.sumOf { it.baseCents })
        rows += BreakdownRow(BreakdownRowKind.DEDUCTION, gettext("Abzüge für gestellte Mahlzeiten"), -days.sumOf { it.deductionCents })
    }
    val others = report.lines.filter { it.kind != TravelExpenseLineKind.DAY_ALLOWANCE }
    others.filter { it.kind == TravelExpenseLineKind.MILEAGE }.forEach { rows += mileageRows(it, rates) }
    OTHER_KIND_ORDER.forEach { kind ->
        val group = others.filter { it.kind == kind }
        if (group.isNotEmpty()) {
            rows +=
                BreakdownRow(BreakdownRowKind.LINE, resolvedAttributeText(travelExpenseLineKindLabel(kind)), group.sumOf { it.amountCents })
        }
    }
    others.filter { it.kind == TravelExpenseLineKind.PER_DIEM }.forEach { rows += legacyPerDiemRow(it) }

    val totals = preview.totals
    rows += BreakdownRow(BreakdownRowKind.SUBTOTAL, gettext("Summe"), totals.subtotalCents)
    if (totals.advanceCents > 0L) rows += BreakdownRow(BreakdownRowKind.ADVANCE, gettext("Vorschuss"), -totals.advanceCents)
    val payoutLabel =
        if (report.payoutChoice == TravelExpensePayoutChoice.DONATION_WAIVER) {
            gettext("Verzicht zugunsten einer Spende")
        } else {
            gettext("Auszahlungsbetrag")
        }
    rows += BreakdownRow(BreakdownRowKind.PAYOUT, payoutLabel, totals.payoutCents)
    return BreakdownModel.Ok(days = days, rows = rows, totals = totals)
}

private val OTHER_KIND_ORDER =
    listOf(
        TravelExpenseLineKind.ACCOMMODATION,
        TravelExpenseLineKind.PUBLIC_TRANSPORT,
        TravelExpenseLineKind.HOSPITALITY,
        TravelExpenseLineKind.OTHER,
        TravelExpenseLineKind.RECEIPTED,
    )

private fun storedDayEntries(report: TravelExpenseReportDto): List<TravelExpenseDayEntry> =
    report.lines
        .filter { it.kind == TravelExpenseLineKind.DAY_ALLOWANCE && it.dayDate != null }
        .sortedBy { it.dayDate }
        .map { line ->
            TravelExpenseDayEntry(
                date = requireNotNull(line.dayDate),
                timeFrom = line.timeFrom,
                timeTo = line.timeTo,
                breakfastProvided = line.breakfastProvided,
                lunchProvided = line.lunchProvided,
                dinnerProvided = line.dinnerProvided,
                destination = line.destination,
            )
        }

private fun mealRow(
    meal: Meal,
    rates: TravelExpenseRateSet,
) = BreakdownMealRow(meal = meal, percent = mealPercent(meal, rates), cents = mealDeductionCents(meal, rates))

private fun dayBreakdown(
    date: LocalDate,
    result: DayResult?,
    meals: List<BreakdownMealRow>,
    rates: TravelExpenseRateSet,
): DayBreakdown {
    val full = rates.tierCents[2]
    if (result == null) {
        return DayBreakdown(date, null, 0L, meals, null, 0L, 0L, complete = false, fullTierCents = full)
    }
    val capped = if (meals.sumOf { it.cents } > result.deductionCents) result.baseCents else null
    return DayBreakdown(
        date = date,
        minutes = result.minutes,
        baseCents = result.baseCents,
        meals = meals,
        cappedAtCents = capped,
        deductionCents = result.deductionCents,
        netCents = result.netCents,
        complete = true,
        fullTierCents = full,
    )
}

/**
 * A mileage line as one row ("Fahrtkosten: 120 km × 0,30 €"), plus a passenger row when it has passengers. The rate of the line itself wins, the rate
 * set's second. The split is shown ONLY when it adds up to the stored amount to the cent (an old line, a rate that changed, a fractional rate: plain
 * amount, no formula).
 */
private fun mileageRows(
    line: TravelExpenseLineDto,
    rates: TravelExpenseRateSet,
): List<BreakdownRow> {
    val km =
        line.kilometers
            ?.let { CentsConversion.parseExact(displayDigits(it)) }
            ?.takeIf { it % 100L == 0L }
            ?.div(100L)
    val rateCents = line.rateSnapshot?.let { CentsConversion.parseExact(displayDigits(it)) } ?: rates.mileageCentsPerKm
    val passengers = line.passengerCount ?: 0
    if (km != null && rateCents != null) {
        val own = km * rateCents
        val passengerRate = rates.passengerCentsPerKm
        val perPassengers = km * passengerRate * passengers
        if (own + perPassengers == line.amountCents) {
            val rows =
                mutableListOf(
                    BreakdownRow(
                        BreakdownRowKind.LINE,
                        gettext("Fahrtkosten: %1 km × %2", km, formatMoney(centsToDecimal(rateCents))),
                        own,
                    ),
                )
            if (passengers > 0 && perPassengers > 0L) {
                rows +=
                    BreakdownRow(
                        BreakdownRowKind.LINE,
                        gettext("Mitfahrerzuschlag: %1 km × %2 × %3", km, formatMoney(centsToDecimal(passengerRate)), passengers),
                        perPassengers,
                    )
            }
            return rows
        }
    }
    return listOf(BreakdownRow(BreakdownRowKind.LINE, gettext("Fahrtkosten"), line.amountCents))
}

/** A pre-V1.9.122 flat-rate line: shown on its own so the sum stays reproducible. */
private fun legacyPerDiemRow(line: TravelExpenseLineDto): BreakdownRow {
    val days = line.days
    val rateCents = line.rateSnapshot?.let { CentsConversion.parseExact(displayDigits(it)) }
    val label =
        if (days != null && rateCents != null && days * rateCents == line.amountCents) {
            gettext("Tagegeld (alte Erfassung): %1 Tage × %2", days, formatMoney(centsToDecimal(rateCents)))
        } else {
            gettext("Tagegeld (alte Erfassung)")
        }
    return BreakdownRow(BreakdownRowKind.LINE, label, line.amountCents)
}

// ---- DOM ---------------------------------------------------------------------------------------------------------------------------

private val AMOUNT_HEADERS = listOf(TableHeader(title = tr("Posten")), TableHeader(title = tr("Betrag"), numeric = true))

private fun signedMoney(cents: Long): ReportCell = ReportCell.Money(centsToDecimal(cents))

private fun overviewRows(model: BreakdownModel.Ok): List<ReportRow> =
    model.rows.map { row ->
        val cells = listOf(ReportCell.Text(row.label), signedMoney(row.cents))
        when (row.kind) {
            BreakdownRowKind.SUBTOTAL -> ReportRow(ReportRowKind.TOTAL, cells)
            BreakdownRowKind.PAYOUT -> ReportRow(ReportRowKind.TOTAL, cells, strong = true)
            else -> ReportRow(ReportRowKind.DATA, cells, rowHeader = true)
        }
    }

private fun mealName(
    row: BreakdownMealRow,
    day: DayBreakdown,
): String {
    val full = formatMoney(centsToDecimal(day.fullTierCents))
    return when (row.meal) {
        Meal.BREAKFAST -> gettext("Frühstück gestellt: %1 % von %2", row.percent, full)
        Meal.LUNCH -> gettext("Mittagessen gestellt: %1 % von %2", row.percent, full)
        Meal.DINNER -> gettext("Abendessen gestellt: %1 % von %2", row.percent, full)
    }
}

private fun dayRows(day: DayBreakdown): List<ReportRow> {
    val rows = mutableListOf<ReportRow>()
    val duration = travelExpenseDuration(day.minutes ?: 0)
    rows +=
        ReportRow(
            ReportRowKind.DATA,
            listOf(ReportCell.Text(gettext("Tagegeld bei %1 Std. Abwesenheit", duration)), signedMoney(day.baseCents)),
            rowHeader = true,
        )
    day.meals.forEach { meal ->
        rows += ReportRow(ReportRowKind.DATA, listOf(ReportCell.Text(mealName(meal, day)), signedMoney(-meal.cents)), rowHeader = true)
    }
    day.cappedAtCents?.let { cap ->
        val addBack = day.meals.sumOf { it.cents } - day.deductionCents
        rows +=
            ReportRow(
                ReportRowKind.DATA,
                listOf(
                    ReportCell.Text(gettext("Abzug auf den Tagessatz von %1 begrenzt.", formatMoney(centsToDecimal(cap)))),
                    signedMoney(addBack),
                ),
                rowHeader = true,
            )
    }
    rows += ReportRow(ReportRowKind.TOTAL, listOf(ReportCell.Text(gettext("Tagesbetrag")), signedMoney(day.netCents)))
    return rows
}

private class DayView(
    val details: Tag,
    val summary: Tag,
    val body: Div,
)

/**
 * The breakdown on the page. [update] may be called on every keystroke of the day grid: the overview table is rebuilt (it holds nothing focusable),
 * the per-day `<details>` and `<summary>` elements are KEPT and only refilled, so an open day stays open and a focused summary keeps its focus.
 * [collapsible] wraps the whole block in one more `<details>` (the cards of a report list, where ten open tables would drown the list).
 */
internal class TravelExpenseBreakdownView(
    host: Container,
    collapsible: Boolean = false,
) {
    private val root: Div
    private val content: Div
    private val overview: Div
    private val daysHost: Div
    private var dayViews = LinkedHashMap<LocalDate, DayView>()

    init {
        val outer = Div(className = "lapis-te-breakdown")
        host.add(outer)
        root = outer
        if (collapsible) {
            val details = Tag(TAG.DETAILS, className = "small")
            details.add(Tag(TAG.SUMMARY, tr("So setzt sich der Betrag zusammen"), className = "text-primary"))
            outer.add(details)
            content = Div().also { details.add(it) }
        } else {
            content = Div().also { outer.add(it) }
        }
        overview = Div().also { content.add(it) }
        daysHost = Div(className = "mt-2").also { content.add(it) }
    }

    /** The rendered root, for tests. */
    internal val element: Div get() = root

    fun update(model: BreakdownModel) {
        overview.removeAll()
        when (model) {
            BreakdownModel.NoSnapshot -> {
                daysHost.removeAll()
                dayViews = LinkedHashMap()
                overview.add(
                    Div(
                        tr("Für diese Abrechnung sind keine gespeicherten Sätze vorhanden; die Aufschlüsselung wird nicht berechnet."),
                        className = "text-muted small",
                    ),
                )
            }
            is BreakdownModel.Ok -> {
                val table = overview.reportTable(caption = tr("So setzt sich der Betrag zusammen"), headers = AMOUNT_HEADERS)
                table.reportRows(overviewRows(model), AMOUNT_HEADERS)
                updateDays(model.days)
            }
        }
    }

    private fun updateDays(days: List<DayBreakdown>) {
        if (dayViews.keys.toList() != days.map { it.date }) {
            val wasOpen =
                dayViews
                    .filterValues {
                        it.details
                            .getElement()
                            ?.asDynamic()
                            ?.open == true
                    }.keys
            daysHost.removeAll()
            dayViews = LinkedHashMap()
            days.forEach { day ->
                val details = Tag(TAG.DETAILS, className = "small mb-1")
                if (day.date in wasOpen) details.setAttribute("open", "")
                val summary = Tag(TAG.SUMMARY)
                details.add(summary)
                val body = Div()
                details.add(body)
                daysHost.add(details)
                dayViews[day.date] = DayView(details, summary, body)
            }
        }
        days.forEach { day ->
            val view = dayViews.getValue(day.date)
            view.summary.content =
                if (day.complete) {
                    gettext("%1: netto %2", localeDateString(day.date), formatMoney(centsToDecimal(day.netCents)))
                } else {
                    gettext("%1: noch nicht vollständig", localeDateString(day.date))
                }
            view.body.removeAll()
            if (day.complete) {
                val headers = AMOUNT_HEADERS
                val table =
                    view.body.reportTable(
                        caption = gettext("Tagesrechnung %1", localeDateString(day.date)),
                        headers = headers,
                        captionVisible = false,
                    )
                table.reportRows(dayRows(day), headers)
            } else {
                view.body.add(Div(tr("Für diesen Tag fehlen noch Angaben."), className = "text-muted"))
            }
        }
    }
}
