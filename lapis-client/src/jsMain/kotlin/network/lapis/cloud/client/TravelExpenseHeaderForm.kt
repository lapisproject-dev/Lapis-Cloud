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
internal fun renderReportHeaderForm(
    panel: SimplePanel,
    existing: TravelExpenseReportDto?,
    collapse: ((Boolean) -> Unit)? = null,
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
    val fromField =
        form.textField(
            label = tr("Von (JJJJ-MM-TT)"),
            value = existing?.travelFrom?.toString(),
            required = true,
            hint = tr("Beispiel: 2026-03-14."),
            rule = { FormRules.isoDate(it) },
        )
    val toField =
        form.textField(
            label = tr("Bis (JJJJ-MM-TT)"),
            value = existing?.travelTo?.toString(),
            required = true,
            hint = tr("Beispiel: 2026-03-14."),
            rule = { FormRules.isoDate(it) },
        )
    // Das Bis-Datum darf nicht vor dem Von-Datum liegen -- am Bis-Feld gezeigt, geprüft sobald beide echte Daten sind.
    form.crossFieldRule(field = toField) {
        val from = runCatching { LocalDate.parse(fromField.value.trim()) }.getOrNull()
        val to = runCatching { LocalDate.parse(toField.value.trim()) }.getOrNull()
        if (from != null && to != null && to < from) {
            FieldCheck.Invalid(gettext("Das Bis-Datum darf nicht vor dem Von-Datum liegen."))
        } else {
            FieldCheck.Ok
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
            val from = LocalDate.parse(fromField.value.trim())
            val to = LocalDate.parse(toField.value.trim())
            onSave(purpose, from, to)
        }
    }
    return form.snapshot()
}
