package network.lapis.cloud.client

import io.kvision.core.onClick
import io.kvision.html.Button
import io.kvision.html.ButtonStyle
import io.kvision.html.button
import io.kvision.html.div
import io.kvision.i18n.tr
import io.kvision.panel.SimplePanel
import io.kvision.panel.VPanel
import io.kvision.panel.hPanel
import io.kvision.panel.vPanel
import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.shared.domain.DirectMessageDto
import network.lapis.cloud.shared.domain.DirectMessagePageDto
import network.lapis.cloud.shared.rpc.IDirectMessageService

/** The RPC surface of the conversation view -- an interface so DOM tests can drive it without a server. Exceptions propagate. */
internal interface DirectMessageConversationRpc {
    suspend fun listConversationPage(
        otherMemberId: String,
        beforeSentAt: LocalDateTime?,
        beforeId: String?,
        limit: Int,
    ): DirectMessagePageDto

    suspend fun markConversationRead(otherMemberId: String): Int
}

internal fun liveDirectMessageConversationRpc(): DirectMessageConversationRpc =
    object : DirectMessageConversationRpc {
        override suspend fun listConversationPage(
            otherMemberId: String,
            beforeSentAt: LocalDateTime?,
            beforeId: String?,
            limit: Int,
        ) = rpcService<IDirectMessageService>().listConversationPage(otherMemberId, beforeSentAt, beforeId, limit)

        override suspend fun markConversationRead(otherMemberId: String) =
            rpcService<IDirectMessageService>().markConversationRead(otherMemberId)
    }

/** The single-message mark-read the inbox list still uses. */
internal fun interface MarkReadRpc {
    suspend fun markRead(messageId: String)
}

internal fun liveMarkReadRpc(): MarkReadRpc = MarkReadRpc { rpcService<IDirectMessageService>().markRead(it) }

/** Messages per page; mirrors nothing on the server (the server only clamps to its own 100). */
internal const val DIRECT_MESSAGE_PAGE_SIZE = 50

/** The client stops offering older messages once this many are loaded (a conversation view is not an archive). */
internal const val DIRECT_MESSAGE_CLIENT_CAP = 1000

/**
 * Marks [ids] as read one after another in ONE coroutine and only then refreshes the unread pill exactly once -- so the counter
 * can never be refreshed before the marks landed, and N messages never cause N counter calls. An empty list just refreshes.
 * (Used by the inbox list; the conversation view marks a whole conversation in one call instead.)
 */
internal fun markReadThenRefreshCounter(
    ids: List<String>,
    rpc: MarkReadRpc = liveMarkReadRpc(),
) = runGuardedAction(null) {
    ids.forEach { id -> guarded { rpc.markRead(id) } }
    UnreadMessages.refresh()
}

/**
 * Welle V1.9.34 -- "Verlauf anzeigen" below an inbox message: the conversation with that member, loaded only on expand (so a closed
 * disclosure costs no call). The body is [conversationView].
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
        body.conversationView(otherMemberId, selfMemberId = selfMemberId, rpc = rpc)
    }
}

/**
 * Welle V1.9.36 -- one conversation, oldest first, in pages of [DIRECT_MESSAGE_PAGE_SIZE] (newest page first; "Ältere Nachrichten
 * laden" prepends the next page via the server's keyset cursor and drops duplicates by id). Beyond [DIRECT_MESSAGE_CLIENT_CAP]
 * loaded messages the button gives way to a sentence. A failed older-page load shows one sentence and keeps what is on screen.
 * All foreign text (body, names) goes through [untrustedDiv]; the viewer's own messages are labelled "Sie". Nothing from a message
 * ever reaches a toast, the URL or the console.
 *
 * Marking as read: when the first page holds unread messages to the viewer, or [knownUnread] says so, ONE
 * `markConversationRead` call marks the whole conversation (older unread messages beyond the first page included), then the pill
 * refreshes once and [onMarked] runs.
 */
internal fun SimplePanel.conversationView(
    otherMemberId: String,
    knownUnread: Int = 0,
    selfMemberId: String? = AppState.session?.memberId,
    rpc: DirectMessageConversationRpc = liveDirectMessageConversationRpc(),
    onMarked: () -> Unit = {},
) {
    dataSection<DirectMessagePageDto>(
        emptyText = tr("Noch keine Nachrichten in diesem Verlauf."),
        isEmpty = { it.messages.isEmpty() },
        load = { guarded { rpc.listConversationPage(otherMemberId, null, null, DIRECT_MESSAGE_PAGE_SIZE) } },
        render = { panel, firstPage ->
            renderConversationPages(panel, otherMemberId, firstPage, selfMemberId, rpc)
            val hasUnread = firstPage.messages.any { it.recipientId == selfMemberId && it.readAt == null }
            if (hasUnread || knownUnread > 0) {
                runGuardedAction(null) {
                    val marked = guarded { rpc.markConversationRead(otherMemberId) }
                    if (marked != null) {
                        UnreadMessages.refresh()
                        onMarked()
                    }
                }
            }
        },
    ).reload()
}

private fun renderConversationPages(
    panel: SimplePanel,
    otherMemberId: String,
    firstPage: DirectMessagePageDto,
    selfMemberId: String?,
    rpc: DirectMessageConversationRpc,
) {
    val controls = panel.vPanel(spacing = 4)
    val messagesPanel = panel.vPanel(spacing = 4)
    val seen = firstPage.messages.map { it.id }.toMutableSet()
    var loadedCount = firstPage.messages.size
    var hasMore = firstPage.hasMore
    var cursor = firstPage.nextCursor
    var failed = false

    // One block per page: the first page is added, every older page is inserted ABOVE the existing blocks, so only the new page is rendered.
    fun addPageBlock(
        messagesNewestFirst: List<DirectMessageDto>,
        onTop: Boolean,
    ) {
        val block = VPanel(spacing = 4)
        messagesNewestFirst.asReversed().forEach { renderConversationMessage(block, it, selfMemberId) }
        if (onTop) messagesPanel.add(0, block) else messagesPanel.add(block)
    }

    fun renderControls(loadOlder: (Button) -> Unit) {
        controls.removeAll()
        if (hasMore && cursor != null) {
            if (loadedCount >= DIRECT_MESSAGE_CLIENT_CAP) {
                controls.div(tr("Ältere Nachrichten werden hier nicht angezeigt.")) { addCssClasses("text-muted small") }
            } else {
                val older = controls.button(tr("Ältere Nachrichten laden"), style = ButtonStyle.OUTLINESECONDARY)
                older.onClick { loadOlder(older) }
            }
        }
        if (failed) controls.div(tr("Ältere Nachrichten konnten nicht geladen werden.")) { addCssClasses("text-danger small") }
    }

    lateinit var loadOlder: (Button) -> Unit
    loadOlder = { button ->
        val c = cursor
        if (c != null) {
            runGuardedAction(button) {
                val page = guarded { rpc.listConversationPage(otherMemberId, c.sentAt, c.id, DIRECT_MESSAGE_PAGE_SIZE) }
                if (page == null) {
                    failed = true
                } else {
                    failed = false
                    val fresh = page.messages.filter { seen.add(it.id) }
                    if (fresh.isNotEmpty()) addPageBlock(fresh, onTop = true)
                    loadedCount += fresh.size
                    hasMore = page.hasMore
                    cursor = page.nextCursor
                }
                renderControls(loadOlder)
            }
        }
    }

    addPageBlock(firstPage.messages, onTop = false)
    renderControls(loadOlder)
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
