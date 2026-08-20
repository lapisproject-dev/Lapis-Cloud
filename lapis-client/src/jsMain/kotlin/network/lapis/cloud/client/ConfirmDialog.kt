package network.lapis.cloud.client

import io.kvision.form.text.text
import io.kvision.html.Button
import io.kvision.html.ButtonStyle
import io.kvision.html.div
import io.kvision.i18n.tr
import io.kvision.modal.Modal

/**
 * Reusable Bootstrap-modal confirmation for destructive/irreversible actions (Austritt, reject
 * application, delete document) -- a real second step, not a bare button, per the V0.7.3 plan.
 * [message] is shown as plain body text; [confirmLabel] labels the destructive action button.
 * [onConfirm] runs (and the modal hides) only when the user clicks that button; cancelling or
 * closing the modal runs nothing. [Modal] attaches itself directly to the KVision root (see its
 * own KDoc), so this needs no parent container argument.
 */
fun confirmDialog(
    title: String,
    message: String,
    confirmLabel: String = tr("Bestätigen"),
    onConfirm: () -> Unit,
) {
    val modal = Modal(caption = title)
    modal.div(message)
    modal.addButton(
        Button(tr("Abbrechen"), style = ButtonStyle.SECONDARY).apply {
            onClick { modal.hide() }
        },
    )
    modal.addButton(
        Button(confirmLabel, style = ButtonStyle.DANGER).apply {
            onClick {
                modal.hide()
                onConfirm()
            }
        },
    )
    modal.show()
}

/**
 * V1.2.2 SEPA-Client-UI wave -- like [confirmDialog], but additionally collects a free-text reason
 * (e.g. mandate revocation, batch cancellation). [dangerNote], when given, renders as its own
 * bold/red line ABOVE [message] -- same "name exactly what freezes/breaks" grammar
 * `AuctionScreen.auctionDisableConfirmDialog` already established, not a generic warning icon.
 *
 * Raskin-Auflage (dieser Wellen-Plan §3): der Bestätigen-Knopf sitzt NICHT an der Stelle des
 * Auslösers -- Modal-Footer, `SECONDARY` links ("Abbrechen"), `DANGER` rechts ([confirmLabel]).
 * When [reasonRequired] is `true`, that button starts (and stays) `disabled` while the reason field
 * is blank -- [onConfirm] is only ever invoked with a non-blank, trimmed reason in that case.
 */
fun confirmWithReasonDialog(
    title: String,
    message: String,
    dangerNote: String? = null,
    reasonLabel: String,
    reasonRequired: Boolean,
    confirmLabel: String = tr("Bestätigen"),
    onConfirm: (String?) -> Unit,
) {
    val modal = Modal(caption = title)
    dangerNote?.let { modal.div(it) { addCssClasses("fw-bold text-danger") } }
    modal.div(message)
    val reasonInput = modal.text(label = reasonLabel)

    val cancelButton = Button(tr("Abbrechen"), style = ButtonStyle.SECONDARY).apply { onClick { modal.hide() } }
    val confirmButton =
        Button(confirmLabel, style = ButtonStyle.DANGER).apply {
            disabled = reasonRequired
            onClick {
                val reason = reasonInput.value?.trim()?.takeIf { it.isNotBlank() }
                if (reasonRequired && reason == null) return@onClick
                modal.hide()
                onConfirm(reason)
            }
        }
    if (reasonRequired) {
        reasonInput.subscribe { value ->
            confirmButton.disabled = value?.trim().isNullOrBlank()
        }
    }
    // Raskin-Auflage: Abbrechen links, die eigentliche (rote) Aktion rechts -- kein Knopf an der
    // Stelle des Auslösers, unabhängig davon, wo im Bildschirm dieses Modal geöffnet wurde.
    modal.addButton(cancelButton)
    modal.addButton(confirmButton)
    modal.show()
}
