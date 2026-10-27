package network.lapis.cloud.client

import io.kvision.core.onClick
import io.kvision.html.ButtonStyle
import io.kvision.i18n.tr
import io.kvision.panel.SimplePanel
import io.kvision.panel.simplePanel
import network.lapis.cloud.shared.domain.DirectMessageDto
import network.lapis.cloud.shared.rpc.DirectMessageAttachmentsUnavailableException
import network.lapis.cloud.shared.rpc.IDirectMessageService

/**
 * Welle V1.9.36 -- the reply form below a message or a conversation (extracted from `CommunicationScreen`'s inbox row, unchanged in
 * behavior). A successful send toasts, refreshes the unread pill, EMPTIES the field (V1.9.231) and calls [onSent]. Returns the reply field (the caller may hand it the focus). A failed one (e.g. the partner is no longer an
 * active member: the server rejects it) is toasted exactly once by `guarded` (typed message, never the server's text); the form and its
 * text stay untouched, and [onFailed] is called.
 *
 * **V1.9.233 attachments**: next to "Antworten" sits "Datei anhängen"; the chosen files upload at once as PENDING files ([attachments],
 * kept per partner by the caller so a rebuilt form finds them again) and travel with the message as their ids. While a file uploads, or
 * a failed one is still listed, sending is disabled and a hint says why, so a message never leaves without a file the person sees. On
 * success the list is emptied; on failure text AND files stay. If the server refuses the send because of the files (it answers all file
 * problems with one conflict), a fixed sentence says so.
 */
internal fun SimplePanel.directMessageReplyForm(
    recipientId: String,
    onSent: () -> Unit,
    onFailed: () -> Unit = {},
    attachments: DmComposerAttachments = DmComposerAttachments(recipientId),
    send: suspend (String, String, List<String>) -> DirectMessageDto = { recipient, body, attachmentIds ->
        rpcService<IDirectMessageService>().sendDirectMessageWithAttachments(recipient, body, attachmentIds)
    },
): LapisField {
    val replyForm = lapisForm()
    val replyField = replyForm.textAreaField(label = tr("Antwort"), rows = 2, required = true)
    val attachmentHost = replyForm.panel.simplePanel()
    val replyButton = newActionButton(ActionIcon.SEND, tr("Antworten"), ButtonStyle.OUTLINEPRIMARY)
    val hint = attachmentHost.dmComposerBlockHintHost()

    fun refreshSendState() {
        replyButton.disabled = attachments.sending || attachments.blocksSend
        hint.content = dmComposerBlockHint(attachments).orEmpty()
    }
    val attachmentsView = DmComposerAttachmentsView(attachmentHost, attachments, afterChange = ::refreshSendState)
    val row = replyForm.buttons(primary = replyButton)
    // "Datei anhängen" sits to the left of "Antworten" in the same button group.
    row.add(0, attachmentsView.attachButton)
    refreshSendState()
    replyButton.onClick {
        replyForm.submit(replyButton, restoreDisabled = { attachments.blocksSend }) {
            val attachmentIds = attachments.readyIds
            var attachmentsRefused = false
            // The list is frozen for the whole send: no file can be added or removed meanwhile (and "Antworten" stays disabled).
            attachments.setSending(true)
            try {
                val result =
                    guarded {
                        try {
                            send(recipientId, replyField.value.trim(), attachmentIds)
                        } catch (e: DirectMessageAttachmentsUnavailableException) {
                            // A distinct type (Kilua RPC transmits only the TYPE): other conflicts (bad body, recipient gone) go to `guarded`.
                            attachmentsRefused = true
                            null
                        }
                    }
                if (attachmentsRefused) {
                    notifyError(tr("Anhänge konnten nicht gesendet werden. Bitte laden Sie die Dateien erneut hoch."))
                    onFailed()
                } else if (result != null) {
                    notifySuccess(tr("Antwort wurde gesendet."))
                    UnreadMessages.refresh()
                    // V1.9.231: the field is empty after a send (the form stays open for the next reply); a failed send keeps the text.
                    replyField.reset()
                    attachments.clearAfterSend(attachmentIds)
                    onSent()
                } else {
                    onFailed()
                }
            } finally {
                attachments.setSending(false)
            }
            // Drop the drafts the server no longer has (expired, ...), so a resend does not fail the same way.
            if (attachmentsRefused) attachments.syncWithServer()
        }
    }
    return replyField
}
