package network.lapis.cloud.client

import io.kvision.core.Container
import io.kvision.core.Widget
import io.kvision.core.onEvent
import io.kvision.form.text.Text
import io.kvision.form.text.text
import io.kvision.html.Button
import io.kvision.html.ButtonStyle
import io.kvision.html.Div
import io.kvision.html.InputType
import io.kvision.i18n.gettext
import io.kvision.i18n.tr

/** The handles of one chat input row, see [lapisChatComposer]. */
internal class ChatComposer(
    val root: Div,
    val input: Text,
    val sendButton: Button,
) {
    fun focus() {
        input.focus()
    }

    fun clear() {
        input.value = null
    }

    var value: String
        get() = input.value.orEmpty()
        set(text) {
            input.value = text
        }
}

/**
 * V1.9.66 (R56 toolbar + R58 named exception a): the one chat input row of the client -- conference chat and encounter-room chat.
 * A field without a visible label (`aria-label` + placeholder) and a square, icon-only send button as high as the field. Enter sends
 * unless a composition (IME) is running. Shift+Enter has no special handling. The button is not disabled on an empty field.
 */
internal fun Container.lapisChatComposer(onSend: () -> Unit): ChatComposer {
    val root = lapisToolbar { addCssClass("lapis-chat-composer") }
    val input = root.text(type = InputType.TEXT) { addCssClass("flex-grow-1") }
    // The attributes belong on the <input> itself, not on the form-group wrapper `Text` renders around it.
    (input.input as? Widget)?.setAttribute("aria-label", gettext("Nachricht"))
    (input.input as? Widget)?.setAttribute("autocomplete", "off")
    input.placeholder = gettext("Nachricht")
    val sendButton = newIconOnlyActionButton(ActionIcon.SEND, tr("Senden"), ButtonStyle.PRIMARY)
    sendButton.addCssClass("lapis-chat-composer-send")
    root.add(sendButton)
    sendButton.onClick { onSend() }
    input.onEvent {
        keydown = { event ->
            // Enter sends; a composition (IME) Enter never does.
            if (event.key == "Enter" && !event.isComposing) {
                event.preventDefault()
                onSend()
            }
        }
    }
    return ChatComposer(root, input, sendButton)
}
