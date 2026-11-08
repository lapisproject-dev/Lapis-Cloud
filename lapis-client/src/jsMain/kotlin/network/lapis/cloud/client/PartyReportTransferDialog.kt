package network.lapis.cloud.client

import io.kvision.html.ButtonStyle
import io.kvision.i18n.tr
import io.kvision.modal.Modal
import network.lapis.cloud.shared.domain.AmountParse
import network.lapis.cloud.shared.domain.PartgConsolidationDto
import network.lapis.cloud.shared.domain.PartgIntraTransferDto
import network.lapis.cloud.shared.domain.PartgIntraTransferInputDto
import network.lapis.cloud.shared.domain.PartgTransferKind
import network.lapis.cloud.shared.domain.PartgUnitDto
import network.lapis.cloud.shared.domain.parseGermanAmount

/**
 * V1.9.290 (R5) -- "Übertragung erfassen": one pair of the matching table (the yearly sum of ONE relationship between two units). Both amounts
 * are optional but at least one is needed; a pair with one side only is shown as "Gegenseite fehlt" and is NOT eliminated. The same rules as
 * the server ([network.lapis.cloud.shared.domain.PartgConsolidationRules]); the server stays the authority.
 */
internal fun openPartyReportTransferDialog(
    context: PartyReportContext,
    units: List<PartgUnitDto>,
    revision: Long,
    existing: PartgIntraTransferDto?,
    onSaved: (PartgConsolidationDto) -> Unit,
) {
    val modal = Modal(caption = if (existing == null) tr("Übertragung erfassen") else tr("Übertragung bearbeiten"))
    val form = modal.lapisForm()
    val unitOptions = units.map { it.unitKey to unitLabel(it) }
    val kindField =
        form.selectField(
            label = tr("Art"),
            options = PartgTransferKind.entries.map { it.name to partgTransferKindLabel(it) },
            value = (existing?.kind ?: PartgTransferKind.SUBSIDY).name,
            required = true,
        )
    val payerField =
        form.selectField(
            label = tr("Zahler"),
            options = unitOptions,
            value = existing?.payerUnitKey ?: units.first().unitKey,
            required = true,
        )
    val payeeField =
        form.selectField(
            label = tr("Empfänger"),
            options = unitOptions,
            value = existing?.payeeUnitKey ?: units.getOrElse(1) { units.first() }.unitKey,
            required = true,
        )

    fun amountRule(text: String): FieldCheck =
        if (text.isBlank()) {
            FieldCheck.Ok
        } else {
            when (val parsed = parseGermanAmount(text)) {
                is AmountParse.Error -> FieldCheck.Invalid(resolvedAttributeText(partgAmountErrorText(parsed.reason)))
                is AmountParse.Ok ->
                    if (parsed.cents < 1L) FieldCheck.Invalid(partgConsolidationCodeText("AMOUNT_RANGE")) else FieldCheck.Ok
            }
        }
    val payerAmountField =
        form.textField(
            label = tr("Betrag laut Zahler (€)"),
            value = existing?.payerAmountCents?.let(::partgInputText),
            rule = ::amountRule,
            init = { it.inputMode("decimal") },
        )
    val payeeAmountField =
        form.textField(
            label = tr("Betrag laut Empfänger (€)"),
            value = existing?.payeeAmountCents?.let(::partgInputText),
            rule = ::amountRule,
            init = { it.inputMode("decimal") },
        )
    val noteField =
        form.textField(
            label = tr("Notiz (freiwillig)"),
            value = existing?.note,
            hint = tr("Bitte keine Namen von Personen eintragen. Die Notiz erscheint weder im Bericht noch im Änderungsverlauf."),
        )
    form.finish()

    modal.addButton(newActionButton(ActionIcon.CANCEL, tr("Abbrechen"), ButtonStyle.SECONDARY).apply { onClick { modal.hide() } })
    val saveButton = newActionButton(ActionIcon.SAVE, tr("Speichern"), ButtonStyle.PRIMARY)
    saveButton.onClick {
        form.submit(saveButton) {
            val input =
                PartgIntraTransferInputDto(
                    id = existing?.id,
                    kind = PartgTransferKind.valueOf(kindField.value),
                    payerUnitKey = payerField.value,
                    payeeUnitKey = payeeField.value,
                    payerAmountCents = centsOf(payerAmountField.value),
                    payeeAmountCents = centsOf(payeeAmountField.value),
                    note = noteField.value.trim().takeIf { it.isNotEmpty() },
                )
            partgTransferInputCheck(input)?.let {
                form.showFormError(it)
                return@submit
            }
            val result = guarded { partyReportRpc().saveIntraTransfer(context.year, revision, input) }
            if (result == null) {
                form.showFormError(saveFailedText())
                return@submit
            }
            modal.hide()
            notifySuccess(tr("Gespeichert."))
            onSaved(result)
        }
    }
    modal.addButton(saveButton)
    modal.show()
}
