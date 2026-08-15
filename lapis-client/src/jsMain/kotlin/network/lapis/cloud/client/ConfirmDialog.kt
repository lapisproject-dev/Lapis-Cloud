package network.lapis.cloud.client

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
