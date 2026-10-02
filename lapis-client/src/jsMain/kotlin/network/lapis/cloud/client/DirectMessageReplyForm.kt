package network.lapis.cloud.client

import io.kvision.core.onClick
import io.kvision.html.Button
import io.kvision.html.ButtonStyle
import io.kvision.i18n.tr
import io.kvision.panel.SimplePanel
import network.lapis.cloud.shared.domain.DirectMessageDto
import network.lapis.cloud.shared.rpc.IDirectMessageService

/**
 * Welle V1.9.36 -- the reply form below a message or a conversation (extracted from `CommunicationScreen`'s inbox row, unchanged in
 * behavior). A successful send toasts, refreshes the unread pill and calls [onSent]. A failed one (e.g. the partner is no longer an
 * active member: the server rejects it) is toasted exactly once by `guarded` (typed message, never the server's text); the form and its
 * text stay untouched, and [onFailed] is called.
 */
internal fun SimplePanel.directMessageReplyForm(
    recipientId: String,
    onSent: () -> Unit,
    onFailed: () -> Unit = {},
    send: suspend (String, String) -> DirectMessageDto = { recipient, body ->
        rpcService<IDirectMessageService>().sendDirectMessage(recipient, body)
    },
) {
    val replyForm = lapisForm()
    val replyField = replyForm.textAreaField(label = tr("Antwort"), rows = 2, required = true)
    val replyButton = Button(tr("Antworten"), style = ButtonStyle.OUTLINEPRIMARY)
    replyForm.buttons(primary = replyButton)
    replyButton.onClick {
        replyForm.submit(replyButton) {
            val result = guarded { send(recipientId, replyField.value.trim()) }
            if (result != null) {
                notifySuccess(tr("Antwort wurde gesendet."))
                UnreadMessages.refresh()
                onSent()
            } else {
                onFailed()
            }
        }
    }
}
