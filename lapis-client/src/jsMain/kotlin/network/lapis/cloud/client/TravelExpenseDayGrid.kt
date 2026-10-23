package network.lapis.cloud.client

import io.kvision.html.ButtonStyle
import io.kvision.html.Div
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
 * Welle V1.9.122 -- the day grid of the travel expense editor: one ROW (a `<fieldset>` with a `<legend>` "Dienstag, 14.10.2026") per travel
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
    private val staleSetter: (Boolean) -> Unit = {},
) {
    /** What the grid shows right now. */
    fun entries(): List<TravelExpenseDayEntry> = currentEntries()

    /** `true` while the grid differs from what the server holds (an unsaved preview). */
    fun isDirty(): Boolean = currentEntries() != savedEntries

    /**
     * V1.9.181: tells the grid that the trip dates in the header form differ from the saved ones, so the day rows below still show the
     * OLD range. Only a notice -- the rows are created by the server on "Entwurf speichern", the client never invents them.
     */
    fun setRangeStale(stale: Boolean) = staleSetter(stale)
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

    // The notice about changed trip dates lives in the DOM permanently (empty) so a screen reader announces its first text.
    val staleNote = Div(className = "lapis-te-day-stale small")
    staleNote.id = "lapis-te-day-stale-${dayGridIdCounter++}"
    staleNote.setAttribute("role", "status")
    host.add(staleNote)

    host.add(Div(tr("* Ohne diese Angabe wird für den Tag kein Tagegeld berechnet."), className = "small text-muted"))
    val form = host.lapisForm()
    val grid = Div(className = "lapis-te-day-grid")
    form.panel.add(grid)

    var staleShown = false
    val staleSetter: (Boolean) -> Unit = { stale ->
        if (stale != staleShown) {
            staleShown = stale
            if (stale) {
                staleNote.content = tr("Die Reisetage werden nach dem Speichern aktualisiert.")
                staleNote.addCssClass("alert")
                staleNote.addCssClass("alert-info")
                staleNote.addCssClass("py-1")
                staleNote.addCssClass("px-2")
                staleNote.addCssClass("mb-2")
                grid.setAttribute("data-stale", "true")
            } else {
                staleNote.content = null
                staleNote.removeCssClass("alert")
                staleNote.removeCssClass("alert-info")
                staleNote.removeCssClass("py-1")
                staleNote.removeCssClass("px-2")
                staleNote.removeCssClass("mb-2")
                grid.removeAttribute("data-stale")
            }
        }
    }

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
        // V1.9.181: one ROW per day (no card border): legend, destination, times, meals, notes, result -- named wrappers that theme.css
        // places in one line from 50rem of container width up and stacks below.
        val fieldset = Tag(TAG.FIELDSET, className = "lapis-te-day py-2 border-top")
        // V1.9.132: the date in the browser-locale order, as a token child (it follows a language switch; Tag renders children, not a mixed string).
        fieldset.add(
            Tag(TAG.LEGEND, className = "h6 mb-1 w-auto px-1").also { legend ->
                legend.add(Span(travelExpenseWeekdayLabel(date) + ", "))
                legend.add(Span(dateToken(date)))
            },
        )
        grid.add(fieldset)
        val destDiv = Div(className = "lapis-te-day-dest").also { fieldset.add(it) }
        val timesDiv = Div(className = "lapis-te-day-times").also { fieldset.add(it) }
        val mealsDiv = Div(className = "lapis-te-day-meals").also { fieldset.add(it) }
        val notesDiv = Div(className = "lapis-te-day-notes").also { fieldset.add(it) }
        val resultDiv = Div(className = "lapis-te-day-result").also { fieldset.add(it) }

        val destination =
            form.textField(
                label = tr("Ziel / Zweck (optional)"),
                value = line.destination,
                host = destDiv,
                rule = { FormRules.maxLength(it, TravelExpenseAmountRules.MAX_DESTINATION_LENGTH) },
            )

        fun slot(span: Boolean = false): Div =
            Div(className = if (span) "lapis-te-day-slot lapis-te-day-slot--span" else "lapis-te-day-slot").also { timesDiv.add(it) }

        fun slotText(
            host: Div,
            text: String,
        ) = host.add(Div(text, className = "lapis-te-day-slot-text text-muted small"))

        var fromField: LapisField? = null
        var toField: LapisField? = null
        when (position) {
            DayPosition.SINGLE -> {
                // V1.9.194: "Beginn/Ende der Reise" -- the same pair of terms on every day; the hint hangs on the END field.
                val departure = timeField(form, slot(), tr("Beginn der Reise"), line.timeFrom)
                val arrival = timeField(form, slot(), tr("Ende der Reise"), line.timeTo)
                fromField = departure
                toField = arrival
                describeEndOfTrip(arrival, notesDiv)
            }
            DayPosition.FIRST -> {
                fromField = timeField(form, slot(), tr("Beginn der Reise"), line.timeFrom)
                slotText(slot(), tr("bis 24:00"))
            }
            DayPosition.LAST -> {
                // V1.9.189: field first, the fixed "ab 00:00" to its right -- the same order as the FIRST day ("bis 24:00").
                val end = timeField(form, slot(), tr("Ende der Reise"), line.timeTo)
                toField = end
                slotText(slot(), tr("ab 00:00"))
                describeEndOfTrip(end, notesDiv)
            }
            DayPosition.MIDDLE -> slotText(slot(span = true), tr("ganztägig (24 Std.)"))
        }
        if (position == DayPosition.SINGLE) {
            form.crossFieldRule(field = toField) {
                val a = fromField?.localTimeOrNull()
                val b = toField?.localTimeOrNull()
                if (a != null && b != null && b < a) {
                    FieldCheck.Invalid(gettext("Die Rückkehr darf nicht vor der Abfahrt liegen."))
                } else {
                    FieldCheck.Ok
                }
            }
        }

        // The meals: a short label per checkbox, the amount it takes off as its description (V1.9.181: name = "Frühstück", description =
        // "20 % · −5,60 €", the shared calculator's own amount -- see mealDeductionCents). Each meal sits in its own cell, so the hint/error
        // slots of the form grammar stay inside the cell and never break the three-column row.
        val noteId = "lapis-te-day-note-${dayGridIdCounter++}"

        fun mealField(
            meal: Meal,
            value: Boolean,
            locked: Boolean,
        ): LapisField {
            val cell = Div(className = "lapis-te-day-meal").also { mealsDiv.add(it) }
            val field =
                form.checkField(
                    label = mealShortLabel(meal),
                    value = value,
                    host = cell,
                    init = { if (locked) it.disabled = true },
                )
            val amountId = "lapis-te-day-meal-amount-${dayGridIdCounter++}"
            cell.add(Span(mealAmountText(meal, rates), className = "d-block small text-muted lapis-tnum").also { it.id = amountId })
            describeBy(field, amountId)
            return field
        }

        val breakfast = mealField(Meal.BREAKFAST, line.breakfastProvided || isForced, locked = isForced)
        val lunch = mealField(Meal.LUNCH, line.lunchProvided, locked = false)
        val dinner = mealField(Meal.DINNER, line.dinnerProvided, locked = false)
        if (isForced) {
            val forcedId = "lapis-te-day-forced-${dayGridIdCounter++}"
            notesDiv.add(Div(tr("gesetzt durch Übernachtung mit Frühstück"), className = "small text-muted").also { it.id = forcedId })
            describeBy(breakfast, forcedId)
        }
        // Says why a checked meal changes less than its label promises (no allowance that day / capped at the day's allowance).
        val deductionNote = Div(className = "small text-muted").also { it.hide() }
        deductionNote.id = noteId
        notesDiv.add(deductionNote)
        listOf(breakfast, lunch, dinner).forEach { describeBy(it, noteId) }

        val outputId = "lapis-te-day-out-${dayGridIdCounter++}"
        val hours = Span(className = "lapis-tnum").also { it.id = "$outputId-hours" }
        val amount = Div(className = "fw-bold lapis-tnum lapis-te-day-amount").also { it.id = "$outputId-amount" }
        val hoursRow =
            Div(className = "small").also { row ->
                row.add(Span(tr("Dauer: ")))
                row.add(hours)
            }
        resultDiv.add(hoursRow)
        resultDiv.add(amount)
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
                timeFrom = w.from?.localTimeOrNull(),
                timeTo = w.to?.localTimeOrNull(),
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
            setAmountLines(
                w.amount,
                gettext("Tagesbetrag: %1", formatMoney(centsToDecimal(result?.netCents ?: 0L))),
                result?.let { gettext("(Abzüge: %1)", formatMoney(centsToDecimal(it.deductionCents))) },
            )
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
    return TravelExpenseDayGrid(currentEntries = ::entries, savedEntries = saved, staleSetter = staleSetter)
}

/** "Frühstück": the short name of a meal checkbox (V1.9.181; the amount it takes off is [mealAmountText], its description). */
internal fun mealShortLabel(meal: Meal): String =
    when (meal) {
        Meal.BREAKFAST -> gettext("Frühstück")
        Meal.LUNCH -> gettext("Mittag")
        Meal.DINNER -> gettext("Abend")
    }

/** The percentage of a meal and the amount it takes off, e.g. "20 % · 5,60 EUR" with the display minus (plain text). */
internal fun mealAmountText(
    meal: Meal,
    rates: TravelExpenseRateSet,
): String {
    val amount = formatMoney(centsToDecimal(mealDeductionCents(meal, rates)))
    return gettext("%1 % · −%2", mealPercent(meal, rates), amount)
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

/**
 * V1.9.194: both time fields carry the required marker and `aria-required`, but do NOT block saving -- an empty value has always meant
 * "no allowance that day" (0,00 EUR), the server and the calculator are unchanged. The marker says what the empty field costs.
 */
private fun timeField(
    form: LapisForm,
    host: io.kvision.core.Container,
    label: String,
    value: LocalTime?,
): LapisField =
    form.localTimeField(label = label, value = value, host = host).also { field ->
        field.appendRequiredMark()
        (field.control.input as? io.kvision.core.Widget)?.setAttribute("aria-required", "true")
    }

/** The hint of the END field: what "Ende der Reise" means (return or arrival), hung on it with `aria-describedby`. */
private fun describeEndOfTrip(
    end: LapisField,
    notes: Div,
) {
    val id = "lapis-te-day-time-hint-${dayGridIdCounter++}"
    notes.add(
        Div(tr("Rückkehr oder Ankunft; bei einer einfachen Fahrt die Ankunft am Ziel"), className = "small text-muted")
            .also { it.id = id },
    )
    describeBy(end, id)
}

/** Two lines in the amount cell: the day amount, and (only with a result) the deductions below it. No `small`, same size. */
private fun setAmountLines(
    cell: Div,
    first: String,
    second: String?,
) {
    cell.removeAll()
    cell.add(Span(first, className = "lapis-te-day-amount-line"))
    if (second != null) cell.add(Span(second, className = "lapis-te-day-amount-line"))
}

/** Appends [id] to the field's `aria-describedby` (the hint/error slots the form grammar already put there stay). */
private fun describeBy(
    field: LapisField,
    id: String,
) {
    val widget = field.control.input as? io.kvision.core.Widget ?: return
    val existing = widget.getAttribute("aria-describedby").orEmpty()
    widget.setAttribute("aria-describedby", if (existing.isBlank()) id else "$existing $id")
}
