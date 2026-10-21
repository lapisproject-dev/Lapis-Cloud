package network.lapis.cloud.client

import io.kvision.html.ButtonStyle
import io.kvision.i18n.gettext
import io.kvision.i18n.tr
import io.kvision.panel.SimplePanel
import kotlinx.datetime.LocalDate
import network.lapis.cloud.shared.domain.TravelExpenseAmountRules
import network.lapis.cloud.shared.domain.TravelExpenseReportDto

// V1.9.49: the report header form of `TravelExpenseScreen.kt` (shared by the create form behind the page header button and the draft
// editor), moved out of the screen. With `collapse` it is built into the host of the collapsible create form and gets a Cancel.
// R24 (W4d): migrated to the form grammar -- Zweck (required text), Von/Bis (required date text, cross-field rule).
// V1.9.132: Von/Bis are locale date fields (`localDateField`), not ISO text fields.
internal fun renderReportHeaderForm(
    panel: SimplePanel,
    existing: TravelExpenseReportDto?,
    collapse: ((Boolean) -> Unit)? = null,
    onRangeDirtyChanged: (Boolean) -> Unit = {},
    onSave: suspend (purpose: String, travelFrom: LocalDate, travelTo: LocalDate) -> Unit,
): FormSnapshot {
    val form = panel.lapisForm()
    val purposeField =
        form.textField(
            label = tr("Zweck der Reise"),
            value = existing?.purpose,
            required = true,
            rule = { FormRules.maxLength(it, TravelExpenseAmountRules.MAX_PURPOSE_LENGTH) },
        )
    // V1.9.132: Von/Bis in der Reihenfolge des Browser-Locales (LocaleDate.kt); gespeichert wird weiterhin ein ISO-LocalDate.
    val fromField = form.localDateField(label = tr("Von"), value = existing?.travelFrom, required = true)
    val toField = form.localDateField(label = tr("Bis"), value = existing?.travelTo, required = true)
    linkDateRange(fromField, toField)
    // V1.9.181: tells the day grid when Von/Bis differ from the saved range (editing only; a half-typed date counts as different). Only
    // notifies -- it never writes to a field, so it cannot feed back into linkDateRange.
    var rangeDirty = false
    val emitRangeDirty = {
        val dirty =
            existing != null &&
                (fromField.localDateOrNull() != existing.travelFrom || toField.localDateOrNull() != existing.travelTo)
        if (dirty != rangeDirty) {
            rangeDirty = dirty
            onRangeDirtyChanged(dirty)
        }
    }
    fromField.subscribe { emitRangeDirty() }
    toField.subscribe { emitRangeDirty() }
    // Das Bis-Datum darf nicht vor dem Von-Datum liegen -- am Bis-Feld gezeigt, geprüft sobald beide echte Daten sind.
    // V1.9.122: höchstens 31 Reisetage, und kein bereits ausgefüllter Reisetag darf aus dem Zeitraum fallen.
    form.crossFieldRule(field = toField) {
        val from = fromField.localDateOrNull()
        val to = toField.localDateOrNull()
        when {
            from == null || to == null -> FieldCheck.Ok
            to < from -> FieldCheck.Invalid(gettext("Das Bis-Datum darf nicht vor dem Von-Datum liegen."))
            travelExpenseSpanDays(from, to) > TravelExpenseAmountRules.MAX_TRAVEL_SPAN_DAYS ->
                FieldCheck.Invalid(gettext("Eine Reise darf höchstens %1 Tage umfassen.", TravelExpenseAmountRules.MAX_TRAVEL_SPAN_DAYS))
            existing != null && travelExpenseDayLinesOutsideRange(existing, from, to) ->
                FieldCheck.Invalid(gettext("Für Tage außerhalb des neuen Zeitraums sind bereits Angaben erfasst -- bitte zuerst leeren."))
            else -> FieldCheck.Ok
        }
    }
    val saveButton =
        newActionButton(
            if (existing == null) ActionIcon.ADD else ActionIcon.SAVE,
            if (existing == null) tr("Entwurf anlegen") else tr("Entwurf speichern"),
            ButtonStyle.PRIMARY,
        )
    form.buttons(primary = saveButton, cancel = collapse?.let { collapseCancelButton(it) })
    saveButton.onClick {
        form.submit(saveButton) {
            val purpose = purposeField.value.trim()
            val from = checkNotNull(fromField.localDateOrNull())
            val to = checkNotNull(toField.localDateOrNull())
            onSave(purpose, from, to)
        }
    }
    return form.snapshot()
}
