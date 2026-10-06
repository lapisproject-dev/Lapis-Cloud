package network.lapis.cloud.client.encounter

import io.kvision.core.Container
import io.kvision.core.Widget
import io.kvision.core.onEvent
import io.kvision.form.text.Text
import io.kvision.form.text.text
import io.kvision.html.ButtonStyle
import io.kvision.html.Div
import io.kvision.html.InputType
import io.kvision.html.div
import io.kvision.i18n.gettext
import io.kvision.i18n.tr
import kotlinx.coroutines.launch
import network.lapis.cloud.client.ActionIcon
import network.lapis.cloud.client.AppScope
import network.lapis.cloud.client.actionButton
import network.lapis.cloud.client.lapisToolbar
import network.lapis.cloud.client.notifyError
import network.lapis.cloud.client.untrustedSpan

/**
 * V1.9.62 Begegnungsraum (B2) -- the in-memory chat of one visit (data-channel topic `lapis-chat`, the conference's own, so no new wire
 * format). What it deliberately does NOT do: show a time (nothing in the room says when something was said), store a line anywhere,
 * make a link clickable, render markup. Every name and text goes through the `untrusted*` helpers, i.e. as plain text with the KVision
 * i18n marker neutralised -- a message `<img src=x onerror=...>` is shown as exactly that text.
 *
 * [onSend] returns `false` when the line was not sent (the person may not publish data). A silenced person's input and button are
 * disabled with a visible explanation; reading continues.
 */
internal class EncounterChatPanel(
    parent: Container,
    private val onSend: suspend (String) -> Boolean,
) {
    val root: Div = parent.div(className = "lapis-encounter-chat")
    private val log = EncounterChatLog()
    private val rows = ArrayDeque<Div>()
    private val logView: Div = root.div(className = "lapis-encounter-chat-log")
    private val silencedNote: Div = root.div(tr("Sie wurden von einem Ordner stummgeschaltet."), className = "text-muted small")
    private val counter: Div = root.div(className = "text-muted small")
    private val composer = root.lapisToolbar { }
    private val field: Text = composer.text(type = InputType.TEXT)
    private val sendAction = composer.actionButton(ActionIcon.SEND, tr("Senden"), style = ButtonStyle.PRIMARY)

    init {
        logView.setAttribute("role", "log")
        logView.setAttribute("aria-live", "polite")
        logView.setAttribute("aria-relevant", "additions")
        logView.setAttribute("aria-label", gettext("Chat"))
        logView.setAttribute("tabindex", "0")
        silencedNote.hide()
        counter.hide()
        // The attributes belong on the <input> itself, not on the form-group wrapper `Text` renders around it.
        (field.input as? Widget)?.setAttribute("aria-label", gettext("Nachricht schreiben"))
        (field.input as? Widget)?.setAttribute("autocomplete", "off")
        field.placeholder = gettext("Nachricht schreiben")
        field.onEvent {
            keydown = { event ->
                // Enter sends; a composition (IME) Enter never does.
                if (event.key == "Enter" && !event.isComposing) {
                    event.preventDefault()
                    send()
                }
            }
            input = { updateCounter() }
        }
        sendAction.onClick { send() }
    }

    /** Adds a received or own line (the text is cut to the limit by the log). */
    fun add(entry: EncounterChatEntry) {
        log.add(entry)
        val shown = log.entries.last()
        val row = logView.div(className = "lapis-encounter-chat-line")
        row.untrustedSpan(shown.senderName, className = "fw-bold")
        row.untrustedSpan(shown.text, className = "ms-2")
        rows.addLast(row)
        while (rows.size > MAX_ROWS) logView.remove(rows.removeFirst())
        logView.getElement()?.let { it.scrollTop = it.scrollHeight.toDouble() }
    }

    /** Silenced people can read but not write: input and button are disabled and the reason is shown. */
    fun setSendingEnabled(enabled: Boolean) {
        field.disabled = !enabled
        sendAction.disabled = !enabled
        if (enabled) silencedNote.hide() else silencedNote.show()
    }

    fun focusInput() {
        field.focus()
    }

    private fun updateCounter() {
        val length = (field.value ?: "").length
        if (length > COUNTER_FROM) {
            counter.content = gettext("%1 von %2 Zeichen", length, ENCOUNTER_CHAT_MAX_CHARS)
            counter.show()
        } else {
            counter.hide()
        }
    }

    private fun send() {
        when (val draft = encounterChatDraft(field.value ?: "")) {
            EncounterChatDraft.Empty -> Unit
            EncounterChatDraft.TooLong ->
                notifyError(
                    gettext("Die Nachricht ist zu lang (höchstens %1 Zeichen).", ENCOUNTER_CHAT_MAX_CHARS),
                )
            is EncounterChatDraft.Ready ->
                AppScope.launch {
                    if (onSend(draft.text)) {
                        field.value = ""
                        updateCounter()
                    }
                }
        }
    }

    private companion object {
        const val MAX_ROWS = 200
        const val COUNTER_FROM = 400
    }
}
