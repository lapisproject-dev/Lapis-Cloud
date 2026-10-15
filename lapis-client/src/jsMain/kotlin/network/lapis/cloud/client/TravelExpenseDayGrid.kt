package network.lapis.cloud.client

import io.kvision.html.ButtonStyle
import io.kvision.html.Div
import io.kvision.html.InputType
import io.kvision.html.Span
import io.kvision.html.TAG
import io.kvision.html.Tag
import io.kvision.i18n.gettext
import io.kvision.i18n.tr
import io.kvision.panel.SimplePanel
import io.kvision.panel.vPanel
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import network.lapis.cloud.shared.domain.DayPosition
import network.lapis.cloud.shared.domain.DayResult
import network.lapis.cloud.shared.domain.MultiDayRule
import network.lapis.cloud.shared.domain.TravelExpenseAmountRules
import network.lapis.cloud.shared.domain.TravelExpenseDayInput
import network.lapis.cloud.shared.domain.TravelExpenseLineKind
import network.lapis.cloud.shared.domain.TravelExpenseRateSet
import network.lapis.cloud.shared.domain.TravelExpenseReportDto
import network.lapis.cloud.shared.rpc.ITravelExpenseService

/**
 * Welle V1.9.122 -- the day grid of the travel expense editor: one `<fieldset>` with a `<legend>` ("Dienstag, 14.10.2026") per travel
 * day, never a `<table>` (an input grid is a form, the guideline forbids tables for input). Laid out by the CSS grid class
 * `lapis-te-day-grid`: aligned columns from 768 px up, stacked below.
 *
 * What a day shows depends on its position in the trip ([MultiDayRule]): a single-day trip asks for departure and arrival; the first day
 * only for the departure ("bis 24:00" is fixed text); the last day only for the arrival ("00:00 bis" fixed); a middle day is "ganztägig
 * (24 Std.)" and asks for nothing but the meals. A breakfast that an overnight stay with breakfast already provides is checked, locked
 * and says why.
 *
 * **The numbers shown next to the fields (hours, day amount, totals) come ONLY from the shared `TravelExpenseCalculator`**
 * ([travelExpensePreview]); there is no calculation code in this file. They are a PREVIEW until "Tage speichern" has run -- the server
 * recomputes and is the only authority, which the totals bar says out loud ("Vorschau – nicht gespeichert").
 */
internal class TravelExpenseDayGrid(
    private val currentEntries: () -> List<TravelExpenseDayEntry>,
    private val savedEntries: List<TravelExpenseDayEntry>,
) {
    /** What the grid shows right now. */
    fun entries(): List<TravelExpenseDayEntry> = currentEntries()

    /** `true` while the grid differs from what the server holds (an unsaved preview). */
    fun isDirty(): Boolean = currentEntries() != savedEntries
}

private var dayGridIdCounter = 0

internal fun renderTravelExpenseDayGrid(
    panel: SimplePanel,
    draft: TravelExpenseReportDto,
    rates: TravelExpenseRateSet,
    onPreviewChanged: () -> Unit,
    onSaved: () -> Unit,
): TravelExpenseDayGrid {
    val dayLines =
        draft.lines
            .filter { it.kind == TravelExpenseLineKind.DAY_ALLOWANCE && it.dayDate != null }
            .sortedBy { it.dayDate }
    val saved =
        dayLines.map { line ->
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
    val forced = travelExpenseForcedBreakfastDates(draft.lines)

    val host = panel.vPanel(spacing = 6)
    Tag(TAG.H3, tr("Reisetage"), className = "h6 mb-0").also { host.add(it) }
    if (dayLines.isEmpty()) {
        host.add(Div(tr("Für diese Reise gibt es keine Tage."), className = "text-muted small"))
        return TravelExpenseDayGrid({ emptyList() }, saved)
    }

    val form = host.lapisForm()
    val grid = Div(className = "lapis-te-day-grid")
    form.panel.add(grid)

    class DayWidgets(
        val date: LocalDate,
        val position: DayPosition,
        val storedBreakfast: Boolean,
        val isForced: Boolean,
        val destination: LapisField,
        val from: LapisField?,
        val to: LapisField?,
        val breakfast: LapisField,
        val lunch: LapisField,
        val dinner: LapisField,
        val hours: Span,
        val amount: Div,
        val deductionNote: Div,
    ) {
        /** The sum of the single-meal amounts of the meals that count today (a breakfast the stay provides always counts). */
        fun mealCents(rates: TravelExpenseRateSet): Long =
            (if (isForced || breakfast.value == "true") mealDeductionCents(Meal.BREAKFAST, rates) else 0L) +
                (if (lunch.value == "true") mealDeductionCents(Meal.LUNCH, rates) else 0L) +
                (if (dinner.value == "true") mealDeductionCents(Meal.DINNER, rates) else 0L)
    }

    val widgets = mutableListOf<DayWidgets>()
    for (line in dayLines) {
        val date = requireNotNull(line.dayDate)
        val position = MultiDayRule.positionOf(date, draft.travelFrom, draft.travelTo)
        val isForced = date in forced
        val fieldset = Tag(TAG.FIELDSET, className = "lapis-te-day border rounded p-2")
        // V1.9.132: the date in the browser-locale order, as a token child (it follows a language switch; Tag renders children, not a mixed string).
        fieldset.add(
            Tag(TAG.LEGEND, className = "h6 mb-1 w-auto px-1").also { legend ->
                legend.add(Span(travelExpenseWeekdayLabel(date) + ", "))
                legend.add(Span(localeDateToken(date)))
            },
        )
        grid.add(fieldset)

        val destination =
            form.textField(
                label = tr("Ziel / Zweck (optional)"),
                value = line.destination,
                host = fieldset,
                rule = { FormRules.maxLength(it, TravelExpenseAmountRules.MAX_DESTINATION_LENGTH) },
            )

        var fromField: LapisField? = null
        var toField: LapisField? = null
        when (position) {
            DayPosition.SINGLE -> {
                fromField = timeField(form, fieldset, tr("Abfahrt (Uhrzeit)"), line.timeFrom)
                toField = timeField(form, fieldset, tr("Rückkehr (Uhrzeit)"), line.timeTo)
            }
            DayPosition.FIRST -> {
                fromField = timeField(form, fieldset, tr("Abfahrt (Uhrzeit)"), line.timeFrom)
                fieldset.add(Div(tr("bis 24:00"), className = "text-muted small"))
            }
            DayPosition.LAST -> {
                fieldset.add(Div(tr("ab 00:00"), className = "text-muted small"))
                toField = timeField(form, fieldset, tr("Rückkehr (Uhrzeit)"), line.timeTo)
            }
            DayPosition.MIDDLE -> fieldset.add(Div(tr("ganztägig (24 Std.)"), className = "text-muted small"))
        }
        if (position == DayPosition.SINGLE) {
            form.crossFieldRule(field = toField) {
                val a = travelExpenseParseTime(fromField?.value)
                val b = travelExpenseParseTime(toField?.value)
                if (a != null && b != null && b < a) {
                    FieldCheck.Invalid(gettext("Die Rückkehr darf nicht vor der Abfahrt liegen."))
                } else {
                    FieldCheck.Ok
                }
            }
        }

        // The meals: the locked breakfast of an overnight stay shows as checked and says why (aria-describedby via `hint`).
        // V1.9.132: every meal says what it takes off ("20 % = −5,60 €"), the shared calculator's own amount -- see mealDeductionCents.
        val breakfast =
            form.checkField(
                label = mealLabel(Meal.BREAKFAST, rates),
                value = line.breakfastProvided || isForced,
                hint = if (isForced) tr("gesetzt durch Übernachtung mit Frühstück") else null,
                host = fieldset,
                init = { if (isForced) it.disabled = true },
            )
        val lunch = form.checkField(label = mealLabel(Meal.LUNCH, rates), value = line.lunchProvided, host = fieldset)
        val dinner = form.checkField(label = mealLabel(Meal.DINNER, rates), value = line.dinnerProvided, host = fieldset)
        // Says why a checked meal changes less than its label promises (no allowance that day / capped at the day's allowance).
        val deductionNote = Div(className = "small text-muted").also { it.hide() }
        val noteId = "lapis-te-day-note-${dayGridIdCounter++}"
        deductionNote.id = noteId
        fieldset.add(deductionNote)
        listOf(breakfast, lunch, dinner).forEach { describeBy(it, noteId) }

        val outputId = "lapis-te-day-out-${dayGridIdCounter++}"
        val hours = Span(className = "lapis-tnum").also { it.id = "$outputId-hours" }
        val amount = Div(className = "fw-bold lapis-tnum").also { it.id = "$outputId-amount" }
        val hoursRow =
            Div(className = "small").also { row ->
                row.add(Span(tr("Dauer: ")))
                row.add(hours)
            }
        fieldset.add(hoursRow)
        fieldset.add(amount)
        // The duration is the description of the LAST time field of the day (what a screen reader reads after the input).
        (toField ?: fromField)?.let { describeBy(it, "$outputId-hours") }

        widgets +=
            DayWidgets(
                date = date,
                position = position,
                storedBreakfast = line.breakfastProvided,
                isForced = isForced,
                destination = destination,
                from = fromField,
                to = toField,
                breakfast = breakfast,
                lunch = lunch,
                dinner = dinner,
                hours = hours,
                amount = amount,
                deductionNote = deductionNote,
            )
    }

    fun entries(): List<TravelExpenseDayEntry> =
        widgets.map { w ->
            TravelExpenseDayEntry(
                date = w.date,
                timeFrom = travelExpenseParseTime(w.from?.value),
                timeTo = travelExpenseParseTime(w.to?.value),
                // A locked breakfast keeps the member's OWN stored value: the lock shows the stay's breakfast, it is not the member's choice.
                breakfastProvided = if (w.isForced) w.storedBreakfast else w.breakfast.value == "true",
                lunchProvided = w.lunch.value == "true",
                dinnerProvided = w.dinner.value == "true",
                destination =
                    w.destination.value
                        .trim()
                        .ifEmpty { null },
            )
        }

    fun refresh() {
        val preview = travelExpensePreview(draft, rates, entries(), advanceCents = draft.advanceCents)
        preview.days.forEachIndexed { i, day ->
            val w = widgets[i]
            val result = day.result
            applyDeductionNote(w.deductionNote, result, w.mealCents(rates))
            w.hours.content = if (result != null) travelExpenseDuration(result.minutes) else "–"
            w.amount.content =
                if (result != null) {
                    gettext(
                        "Tagesbetrag: %1 (Abzüge: %2)",
                        formatMoney(centsToDecimal(result.netCents)),
                        formatMoney(centsToDecimal(result.deductionCents)),
                    )
                } else {
                    gettext("Tagesbetrag: %1", formatMoney(centsToDecimal(0)))
                }
        }
        onPreviewChanged()
    }
    widgets.forEach { w ->
        listOfNotNull(w.destination, w.from, w.to, w.breakfast, w.lunch, w.dinner).forEach { it.subscribe { refresh() } }
    }
    refresh()

    val saveButton = newActionButton(ActionIcon.SAVE, tr("Tage speichern"), ButtonStyle.PRIMARY)
    form.buttons(primary = saveButton)
    saveButton.onClick {
        form.submit(saveButton) {
            val inputs =
                entries().map { e ->
                    TravelExpenseDayInput(
                        dayDate = e.date,
                        timeFrom = e.timeFrom,
                        timeTo = e.timeTo,
                        breakfastProvided = e.breakfastProvided,
                        lunchProvided = e.lunchProvided,
                        dinnerProvided = e.dinnerProvided,
                        destination = e.destination,
                    )
                }
            val result = guarded { rpcService<ITravelExpenseService>().saveDays(draft.id, inputs) }
            if (result != null) {
                notifySuccess(tr("Tage gespeichert."))
                onSaved()
            }
        }
    }
    return TravelExpenseDayGrid(currentEntries = ::entries, savedEntries = saved)
}

/** "Frühstück gestellt (20 % = −5,60 €)": the label of a meal checkbox with its percentage and the amount it takes off (plain text, no `tr()` marker). */
internal fun mealLabel(
    meal: Meal,
    rates: TravelExpenseRateSet,
): String {
    val percent = mealPercent(meal, rates)
    val amount = formatMoney(centsToDecimal(mealDeductionCents(meal, rates)))
    return when (meal) {
        Meal.BREAKFAST -> gettext("Frühstück gestellt (%1 % = −%2)", percent, amount)
        Meal.LUNCH -> gettext("Mittagessen gestellt (%1 % = −%2)", percent, amount)
        Meal.DINNER -> gettext("Abendessen gestellt (%1 % = −%2)", percent, amount)
    }
}

private fun applyDeductionNote(
    note: Div,
    result: DayResult?,
    selectedMealCents: Long,
) {
    val info = dayDeductionNote(result, selectedMealCents)
    when (info.kind) {
        DayDeductionKind.NONE -> {
            note.content = null
            note.hide()
        }
        DayDeductionKind.NO_ALLOWANCE -> {
            note.content = gettext("Kein Tagegeld an diesem Tag, daher kein Abzug.")
            note.show()
        }
        DayDeductionKind.CAPPED -> {
            note.content = gettext("Abzug auf den Tagessatz von %1 begrenzt.", formatMoney(centsToDecimal(info.baseCents)))
            note.show()
        }
    }
}

private fun timeField(
    form: LapisForm,
    host: Tag,
    label: String,
    value: LocalTime?,
): LapisField =
    form.textField(
        label = label,
        type = InputType.TIME,
        value = value?.let { "${pad2(it.hour)}:${pad2(it.minute)}" },
        host = host,
    )

/** Appends [id] to the field's `aria-describedby` (the hint/error slots the form grammar already put there stay). */
private fun describeBy(
    field: LapisField,
    id: String,
) {
    val widget = field.control.input as? io.kvision.core.Widget ?: return
    val existing = widget.getAttribute("aria-describedby").orEmpty()
    widget.setAttribute("aria-describedby", if (existing.isBlank()) id else "$existing $id")
}
