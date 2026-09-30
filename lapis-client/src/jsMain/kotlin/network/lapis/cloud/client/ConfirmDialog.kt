package network.lapis.cloud.client

import io.kvision.form.text.text
import io.kvision.html.Button
import io.kvision.html.ButtonStyle
import io.kvision.html.div
import io.kvision.i18n.gettext
import io.kvision.i18n.tr
import io.kvision.modal.Modal

/**
 * Reusable Bootstrap-modal confirmation for destructive/irreversible actions (Austritt, reject
 * application, delete document) -- a real second step, not a bare button, per the V0.7.3 plan.
 * [message] is shown as plain body text; [confirmLabel] labels the destructive action button.
 * [onConfirm] runs (and the modal hides) only when the user clicks that button; cancelling or
 * closing the modal runs nothing. [Modal] attaches itself directly to the KVision root (see its
 * own KDoc), so this needs no parent container argument.
 *
 * [confirmStyle] (V1.9.19) defaults to [ButtonStyle.DANGER], the style every destructive caller
 * has always had; a non-destructive confirmation (publishing a photo) passes
 * [ButtonStyle.PRIMARY]. Existing callers are unchanged.
 */
fun confirmDialog(
    title: String,
    message: String,
    confirmLabel: String = tr("Bestätigen"),
    confirmStyle: ButtonStyle = ButtonStyle.DANGER,
    onConfirm: () -> Unit,
) {
    val modal = Modal(caption = title)
    modal.div(message)
    modal.addButton(
        Button(tr("Abbrechen"), style = ButtonStyle.SECONDARY).apply {
            onClick { modal.hide() }
        },
    )
    val once = ConfirmOnce()
    val confirmButton = Button(confirmLabel, style = confirmStyle)
    confirmButton.onClick {
        once.run(confirmButton) {
            modal.hide()
            onConfirm()
        }
    }
    modal.addButton(confirmButton)
    modal.show()
}

/**
 * Doppelklick-Sperre der Bestätigungsdialoge (R29): Der Dialog ist ein Einmal-Objekt -- ist er bestätigt, ist die Aktion
 * ausgelöst. Ein zweiter Klick auf denselben Knopf (Doppelklick, oder ein Klick während das Modal noch ausgeblendet wird: das
 * Element bleibt bis zum Ende der Animation im DOM und klickbar) dürfte [onConfirm] sonst ein zweites Mal aufrufen -- bei den
 * Geld- und Buchungspfaden also eine zweite Buchung. Die Sperre sitzt im Dialog selbst und deckt damit alle Aufrufstellen ab,
 * auch die, deren [onConfirm] nur einen ungeschützten `AppScope.launch` startet. Ein späterer Fehler der Aktion ändert daran
 * nichts: der Dialog ist dann schon zu, ein neuer Versuch öffnet einen neuen Dialog.
 */
internal class ConfirmOnce {
    private var fired = false

    /** Führt [action] höchstens EINMAL aus und sperrt [button] dabei; jeder weitere Aufruf ist wirkungslos. */
    fun run(
        button: Button,
        action: () -> Unit,
    ) {
        if (fired) return
        fired = true
        button.disabled = true
        action()
    }
}

/**
 * V1.2.2 SEPA-Client-UI wave -- like [confirmDialog], but additionally collects a free-text reason
 * (e.g. mandate revocation, batch cancellation). [dangerNote], when given, renders as its own
 * bold/red line ABOVE [message] -- same "name exactly what freezes/breaks" grammar
 * `AuctionScreen.auctionDisableConfirmDialog` already established, not a generic warning icon.
 *
 * Raskin-Auflage (dieser Wellen-Plan §3): der Bestätigen-Knopf sitzt NICHT an der Stelle des
 * Auslösers -- Modal-Footer, `SECONDARY` links ("Abbrechen"), `DANGER` rechts ([confirmLabel]).
 * When [reasonRequired] is `true` and [reasonMinLength] is `null`, a click with a blank reason
 * reports the error at the field (no `disabled` button, W4b) -- [onConfirm] is only ever invoked
 * with a non-blank, trimmed reason in that case.
 *
 * **[reasonMinLength] (Welle V1.4.36) -- a SECOND, stricter gate on top of [reasonRequired].**
 * When given, the confirm button starts (and stays) `disabled` until the trimmed reason reaches at
 * least [reasonMinLength] characters (and, if [reasonMaxLength] is also set, does not exceed it) --
 * unlike the W4b "click and see the error at the field" posture above, this mirrors
 * [confirmWithTypedConfirmationDialog]'s disabled-until-valid grammar: `ArticlesScreen`'s
 * "Depublizieren" dialog needs a mandatory 10..1000-character reason, and a board member who has
 * not yet typed enough must see that the action is not yet available, not discover it by clicking
 * and getting a field error. Abwärtskompatibel: every existing caller passes neither
 * [reasonMinLength] nor [reasonPlaceholder] nor [showCounter], so their behavior is byte-for-byte
 * unchanged (`null`/`false` defaults).
 */
fun confirmWithReasonDialog(
    title: String,
    message: String,
    dangerNote: String? = null,
    reasonLabel: String,
    reasonRequired: Boolean,
    confirmLabel: String = tr("Bestätigen"),
    reasonMaxLength: Int? = null,
    reasonMinLength: Int? = null,
    reasonPlaceholder: String? = null,
    showCounter: Boolean = false,
    onConfirm: (String?) -> Unit,
) {
    val modal = Modal(caption = title)
    dangerNote?.let { modal.div(it) { addCssClasses("fw-bold text-danger") } }
    modal.div(message)

    // Welle V1.4.29 (W4b): die Begründung ist ein Feld der Formular-Grammatik. Der Bestätigen-Knopf ist NICHT mehr `disabled`
    // (ein grauer Knopf ohne Erklärung ist eine Lüge über den Systemzustand, Raskin); ein Klick auf ein ungültiges Feld
    // meldet den Fehler AM Feld und setzt den Fokus dorthin. Die Invariante bleibt: [onConfirm] erhält ausschließlich eine
    // nicht-leere, getrimmte Begründung -- bzw. bei `reasonRequired = false` und leerem Feld `null`.
    val form = modal.lapisForm()
    val minLength = reasonMinLength ?: if (reasonRequired) 1 else 0
    val reasonField =
        form.textField(
            label = reasonLabel,
            required = reasonRequired,
            // Welle V1.4.21: optionale Obergrenze (z. B. `reason` <= 500 Zeichen bei den Offene-Posten-Stornos) -- eine
            // Überschreitung wird vor dem Round-Trip abgewiesen. `null` = unverändertes Verhalten für alle Aufrufer.
            hint =
                reasonMaxLength?.let {
                    if (minLength > 0) gettext("%1 bis %2 Zeichen.", minLength, it) else gettext("Höchstens %1 Zeichen.", it)
                },
            rule = { value ->
                if (reasonMaxLength != null || minLength > 0) {
                    FormRules.reasonText(value = value, min = minLength, max = reasonMaxLength ?: Int.MAX_VALUE)
                } else {
                    FieldCheck.Ok
                }
            },
            init = { control -> reasonPlaceholder?.let { control.placeholder = it } },
        )

    // Welle V1.4.36 -- live character counter, e.g. "42/1000". Purely cosmetic (the actual gate is
    // `rule`/the disabled-button check below), so a missing/late-updating counter can never let an
    // out-of-range reason through.
    val counterSlot: io.kvision.html.Div? =
        if (showCounter && reasonMaxLength != null) {
            modal.div("0/$reasonMaxLength") { addCssClasses("text-muted small text-end") }
        } else {
            null
        }

    form.finish()

    val cancelButton = Button(tr("Abbrechen"), style = ButtonStyle.SECONDARY).apply { onClick { modal.hide() } }
    val once = ConfirmOnce()
    val confirmButton = Button(confirmLabel, style = ButtonStyle.DANGER)
    if (reasonMinLength != null) confirmButton.disabled = true

    reasonField.subscribe { raw ->
        val trimmedLength = raw.trim().length
        if (counterSlot != null) counterSlot.content = "$trimmedLength/$reasonMaxLength"
        if (reasonMinLength != null) {
            val withinMax = reasonMaxLength == null || trimmedLength <= reasonMaxLength
            confirmButton.disabled = trimmedLength < reasonMinLength || !withinMax
        }
    }

    confirmButton.onClick {
        // Erst prüfen, dann sperren: ein ungültiger Klick verbraucht die Einmal-Sperre nicht (der Nutzer korrigiert und klickt erneut).
        if (!form.validateAndReport()) return@onClick
        val reason = reasonField.value.trim().takeIf { it.isNotBlank() }
        once.run(confirmButton) {
            modal.hide()
            onConfirm(reason)
        }
    }
    // Raskin-Auflage: Abbrechen links, die eigentliche (rote) Aktion rechts -- kein Knopf an der
    // Stelle des Auslösers, unabhängig davon, wo im Bildschirm dieses Modal geöffnet wurde.
    modal.addButton(cancelButton)
    modal.addButton(confirmButton)
    modal.show()
}

/**
 * Welle V1.4.2 "Interessenten-/Sympathisanten-CRM" -- a THIRD, stricter confirmation grammar for
 * the single most irreversible action this codebase has: [CrmContactsScreen]'s Art.-17 contact
 * erasure. Unlike [confirmDialog]/[confirmWithReasonDialog] (one click past a modal), the confirm
 * button here stays disabled until the caller has TYPED [expectedText] (typically the contact's own
 * display name) exactly -- the same "make the destructive path deliberately slower than the safe
 * one" posture, one notch stricter because there is no undo (see `CrmPersonalData.erase` KDoc: a
 * real DELETE, not anonymize-with-retained-row).
 */
fun confirmWithTypedConfirmationDialog(
    title: String,
    message: String,
    expectedText: String,
    confirmLabel: String = tr("Endgültig löschen"),
    onConfirm: () -> Unit,
) {
    val modal = Modal(caption = title)
    modal.div(message)
    modal.div(gettext("Zum Bestätigen bitte \"%1\" eingeben:", expectedText)) { addCssClasses("fw-bold") }
    val typedInput = modal.text()

    val cancelButton = Button(tr("Abbrechen"), style = ButtonStyle.SECONDARY).apply { onClick { modal.hide() } }
    val once = ConfirmOnce()
    val confirmButton = Button(confirmLabel, style = ButtonStyle.DANGER).apply { disabled = true }
    confirmButton.onClick {
        if (typedInput.value != expectedText) return@onClick
        once.run(confirmButton) {
            modal.hide()
            onConfirm()
        }
    }
    typedInput.subscribe { value -> confirmButton.disabled = value != expectedText }
    modal.addButton(cancelButton)
    modal.addButton(confirmButton)
    modal.show()
}
