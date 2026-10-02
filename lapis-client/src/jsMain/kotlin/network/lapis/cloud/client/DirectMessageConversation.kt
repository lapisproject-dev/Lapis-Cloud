package network.lapis.cloud.client

import io.kvision.core.onClick
import io.kvision.html.ButtonStyle
import io.kvision.html.button
import io.kvision.html.div
import io.kvision.i18n.tr
import io.kvision.panel.SimplePanel
import io.kvision.panel.hPanel
import io.kvision.panel.vPanel
import network.lapis.cloud.shared.domain.DirectMessageDto
import network.lapis.cloud.shared.rpc.IDirectMessageService

/** The RPC surface of the conversation view -- an interface so DOM tests can drive it without a server. Exceptions propagate. */
internal interface DirectMessageConversationRpc {
    suspend fun listConversation(otherMemberId: String): List<DirectMessageDto>

    suspend fun markRead(messageId: String)
}

internal fun liveDirectMessageConversationRpc(): DirectMessageConversationRpc =
    object : DirectMessageConversationRpc {
        override suspend fun listConversation(otherMemberId: String) = rpcService<IDirectMessageService>().listConversation(otherMemberId)

        override suspend fun markRead(messageId: String) = rpcService<IDirectMessageService>().markRead(messageId)
    }

/** Mirrors `MAX_CONVERSATION_MESSAGES` in the server's `DirectMessageService.kt`. */
internal const val DIRECT_MESSAGE_CONVERSATION_CAP = 200

/**
 * Marks [ids] as read one after another in ONE coroutine and only then refreshes the sidebar counter exactly once -- so the counter
 * can never be refreshed before the marks landed, and N messages never cause N counter calls. An empty list just refreshes.
 */
internal fun markReadThenRefreshCounter(
    ids: List<String>,
    rpc: DirectMessageConversationRpc = liveDirectMessageConversationRpc(),
) = runGuardedAction(null) {
    ids.forEach { id -> guarded { rpc.markRead(id) } }
    UnreadMessages.refresh()
}

/**
 * Welle V1.9.34 -- "Verlauf anzeigen" below an inbox message: the whole conversation with that member, oldest first, loaded only on
 * expand (so a closed disclosure costs no call). All foreign text (body, names) goes through [untrustedDiv]; the viewer's own messages
 * are labelled "Sie". Nothing from a message ever reaches a toast, the URL or the console. The server returns at most the newest 200
 * messages (see `MAX_CONVERSATION_MESSAGES`).
 */
internal fun SimplePanel.conversationDisclosure(
    otherMemberId: String,
    otherDisplayName: String,
    selfMemberId: String? = AppState.session?.memberId,
    rpc: DirectMessageConversationRpc = liveDirectMessageConversationRpc(),
) {
    val head = hPanel(spacing = 8) { addCssClass("align-items-center") }
    val toggle = head.button(tr("Verlauf anzeigen"), style = ButtonStyle.LINK)
    toggle.setAttribute("aria-expanded", "false")
    head.untrustedDiv(otherDisplayName, className = "text-muted small")
    val body = vPanel(spacing = 4)
    var open = false
    toggle.onClick {
        open = !open
        toggle.text = if (open) tr("Verlauf ausblenden") else tr("Verlauf anzeigen")
        toggle.setAttribute("aria-expanded", open.toString())
        body.removeAll()
        if (!open) return@onClick
        val section =
            body.dataSection<List<DirectMessageDto>>(
                emptyText = tr("Noch keine Nachrichten in diesem Verlauf."),
                isEmpty = { it.isEmpty() },
                load = { guarded { rpc.listConversation(otherMemberId) } },
                render = { panel, messages ->
                    if (messages.size >= DIRECT_MESSAGE_CONVERSATION_CAP) {
                        panel.div(tr("Es werden die neuesten 200 Nachrichten angezeigt.")) { addCssClasses("text-muted small") }
                    }
                    messages.asReversed().forEach { renderConversationMessage(panel, it, selfMemberId) }
                    markReadThenRefreshCounter(
                        ids = messages.filter { it.recipientId == selfMemberId && it.readAt == null }.map { it.id },
                        rpc = rpc,
                    )
                },
            )
        section.reload()
    }
}

private fun renderConversationMessage(
    panel: SimplePanel,
    message: DirectMessageDto,
    selfMemberId: String?,
) {
    val entry = panel.vPanel(spacing = 2) { addCssClasses("border-start ps-2") }
    if (message.senderId == selfMemberId) {
        entry.div(tr("Sie")) { addCssClass("fw-bold") }
    } else {
        entry.untrustedDiv(message.senderDisplayName, className = "fw-bold")
    }
    entry.div(formatDateTime(message.sentAt)) { addCssClasses("text-muted small") }
    entry.untrustedDiv(message.body)
}
