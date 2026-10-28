package network.lapis.cloud.client

import io.kvision.core.onClick
import io.kvision.html.Button
import io.kvision.html.ButtonStyle
import io.kvision.html.H2
import io.kvision.html.TAG
import io.kvision.html.div
import io.kvision.html.span
import io.kvision.html.tag
import io.kvision.i18n.gettext
import io.kvision.i18n.tr
import io.kvision.panel.SimplePanel
import io.kvision.panel.hPanel
import io.kvision.panel.simplePanel
import io.kvision.panel.vPanel
import kotlinx.coroutines.launch
import network.lapis.cloud.shared.domain.DirectMessageDto
import network.lapis.cloud.shared.domain.DirectMessagePartnerDto
import network.lapis.cloud.shared.rpc.IDirectMessageService
import org.w3c.dom.HTMLElement

/** The RPC surface of the partner list -- an interface so DOM tests can drive it without a server. Exceptions propagate. */
internal interface DirectMessagePartnerListRpc {
    suspend fun listConversationPartners(limit: Int): List<DirectMessagePartnerDto>
}

internal fun liveDirectMessagePartnerListRpc(): DirectMessagePartnerListRpc =
    object : DirectMessagePartnerListRpc {
        override suspend fun listConversationPartners(limit: Int) = rpcService<IDirectMessageService>().listConversationPartners(limit)
    }

/** Mirrors `MAX_CONVERSATION_PARTNERS` in the server's `DirectMessageService.kt`. */
internal const val DIRECT_MESSAGE_PARTNER_CAP = 100

/** The `?section=` value the navbar envelope links to, and the id of the heading it lands on. */
internal const val MESSAGES_SECTION = "messages"
internal const val PARTNER_LIST_HEADING_ID = "lapis-dm-partners-heading"

/** "n neu" for 1..99, "99+ neu" from 100. Pure. */
internal fun unreadPartnerBadge(n: Int): String = gettext("%1 neu", if (n >= 100) "99+" else n)

/**
 * Welle V1.9.36 (V1.9.231: heading "Nachrichten", type marker) -- everyone the viewer has exchanged messages with, newest activity first, with name, last-activity time
 * and an unread count -- never a message text. Opening a row shows the conversation ([conversationView]) and a reply form below it;
 * only one conversation is open at a time. Only OPENING a conversation marks its messages read (V1.9.231: the old
 * inbox that marked everything read on load is gone), so the "n neu" hint stays until then. The heading is the target of `?section=messages` (focusable, `tabindex=-1`; [focusHeading] takes the focus on insert).
 */
internal fun renderDirectMessagePartnerList(
    root: SimplePanel,
    rpc: DirectMessagePartnerListRpc = liveDirectMessagePartnerListRpc(),
    conversationRpc: DirectMessageConversationRpc = liveDirectMessageConversationRpc(),
    onFirstLoadSettled: () -> Unit = {},
    focusHeading: Boolean = false,
    replySend: suspend (String, String, List<String>) -> DirectMessageDto = { recipient, body, attachmentIds ->
        rpcService<IDirectMessageService>().sendDirectMessageWithAttachments(recipient, body, attachmentIds)
    },
    attachmentHttp: DirectMessageAttachmentHttp = DirectMessageHttp,
    openPartnerOnLoad: String? = null,
    blockSender: suspend (String) -> Unit = { rpcService<IDirectMessageService>().blockMessageSender(it) },
) {
    val heading = H2(content = tr("Nachrichten"), className = "h5")
    heading.setAttribute("id", PARTNER_LIST_HEADING_ID)
    heading.setAttribute("tabindex", "-1")
    // Inserted AFTER the page title's own focus hook (document order), so `?section=messages` wins the focus.
    root.addWithLifecycle(
        heading,
        onInsert = { vnode -> if (focusHeading) focusAndScrollIntoView(vnode.elm as? HTMLElement) },
    )
    var firstSettled = false
    // V1.9.237: after "Neue Nachricht" the conversation just written is open on arrival (in-memory state; no id travels in the URL).
    var openPartnerId: String? = openPartnerOnLoad
    val reloadedAfterMark = mutableSetOf<String>()
    val focusReplyAfterReload = ReplyFocusRequest()
    // V1.9.233: the composer's attachments live HERE, per partner -- a reload of the list rebuilds the form, and pending uploads must
    // survive that; after a page reload / navigation the state is rebuilt from the server's pending list (syncWithServer).
    val composerAttachments = mutableMapOf<String, DmComposerAttachments>()
    lateinit var section: DataSection
    section =
        root.dataSection<List<DirectMessagePartnerDto>>(
            emptyText = tr("Noch keine Nachrichten."),
            isEmpty = { it.isEmpty() },
            onSettled = {
                if (!firstSettled) {
                    firstSettled = true
                    onFirstLoadSettled()
                }
            },
            load = { guarded { rpc.listConversationPartners(DIRECT_MESSAGE_PARTNER_CAP) } },
            render = { panel, partners ->
                if (partners.size >= DIRECT_MESSAGE_PARTNER_CAP) {
                    panel.div(tr("Es werden die 100 zuletzt aktiven Gespräche angezeigt.")) { addCssClasses("text-muted small") }
                }
                renderPartnerRows(
                    panel = panel,
                    partners = partners,
                    conversationRpc = conversationRpc,
                    openPartnerId = { openPartnerId },
                    setOpenPartnerId = { openPartnerId = it },
                    reloadedAfterMark = reloadedAfterMark,
                    focusReplyAfterReload = focusReplyAfterReload,
                    reload = { section.reload() },
                    blockSender = blockSender,
                    replySend = replySend,
                    attachmentsFor = { id ->
                        // A first build (also after a page reload) asks the server for pending uploads this page has lost track of.
                        composerAttachments.getOrPut(id) { DmComposerAttachments(id, attachmentHttp).also { it.syncWithServer() } }
                    },
                )
            },
        )
    section.reload()
}

/** The type marker of an inbox row: the [ActionIcon.CHAT] glyph (decorative) and the visible word "Gespräch". */
internal fun SimplePanel.conversationTypeMarker() {
    val marker = hPanel(spacing = 4) { addCssClasses("align-items-center text-muted small flex-shrink-0") }
    marker.tag(TAG.I, className = ActionIcon.CHAT.css) { setAttribute("aria-hidden", "true") }
    marker.span(tr("Gespräch"))
}

/** The partner whose reply field takes the focus after the next reload (a send rebuilds the list and with it the form). */
private class ReplyFocusRequest {
    var value: String? = null
}

private class PartnerRow(
    val toggle: Button,
    val body: SimplePanel,
)

private fun renderPartnerRows(
    panel: SimplePanel,
    partners: List<DirectMessagePartnerDto>,
    conversationRpc: DirectMessageConversationRpc,
    openPartnerId: () -> String?,
    setOpenPartnerId: (String?) -> Unit,
    reloadedAfterMark: MutableSet<String>,
    focusReplyAfterReload: ReplyFocusRequest,
    reload: () -> Unit,
    blockSender: suspend (String) -> Unit,
    replySend: suspend (String, String, List<String>) -> DirectMessageDto,
    attachmentsFor: (String) -> DmComposerAttachments,
) {
    val rows = mutableMapOf<String, PartnerRow>()
    val list = panel.vPanel(spacing = 6)

    fun close(id: String) {
        rows[id]?.let {
            it.body.removeAll()
            it.toggle.setAttribute("aria-expanded", "false")
        }
    }

    fun open(partner: DirectMessagePartnerDto) {
        val row = rows[partner.partnerId] ?: return
        row.toggle.setAttribute("aria-expanded", "true")
        row.body.blockControl(
            block = { guarded { blockSender(partner.partnerId) } != null },
            onBlocked = {
                setOpenPartnerId(null)
                reload()
            },
        )
        row.body.conversationView(
            otherMemberId = partner.partnerId,
            knownUnread = partner.unreadCount,
            rpc = conversationRpc,
            // One reload per open conversation: a reopened view after that reload must not trigger the next one, whatever it sees.
            onMarked = { if (reloadedAfterMark.add(partner.partnerId)) reload() },
        )
        val replyField =
            row.body.directMessageReplyForm(
                recipientId = partner.partnerId,
                onSent = {
                    focusReplyAfterReload.value = partner.partnerId
                    reload()
                },
                attachments = attachmentsFor(partner.partnerId),
                send = replySend,
            )
        // A send reloads the list, which rebuilds this form: the focus goes back to the new, empty field (after the DOM was patched).
        if (focusReplyAfterReload.value == partner.partnerId) {
            focusReplyAfterReload.value = null
            replyField.focusWhenInserted()
        }
    }

    // Newest activity first; equal times resolve by id so the order never flickers between reloads.
    val ordered = sortInboxRows(partners.map(::ConversationRow)).filterIsInstance<ConversationRow>().map { it.partner }
    ordered.forEach { partner ->
        val unread = partner.unreadCount > 0
        val card = list.vPanel(spacing = 4) { addCssClasses(if (unread) "border border-primary rounded p-2" else "border rounded p-2") }
        val head = card.hPanel(spacing = 8) { addCssClass("align-items-center") }
        val toggle = Button(sanitizeUntrustedI18nText(partner.partnerDisplayName), style = ButtonStyle.LINK)
        toggle.addCssClasses("flex-grow-1 text-start p-0")
        if (unread) toggle.addCssClass("fw-bold")
        toggle.setAttribute("aria-expanded", "false")
        head.add(toggle)
        // V1.9.231: the row type, always icon AND word (never the icon alone). Circular letters get their own type in a later wave.
        head.conversationTypeMarker()
        head.div(formatSystemDateTime(partner.lastActivityAt)) { addCssClasses("text-muted small") }
        if (unread) head.statusBadge(unreadPartnerBadge(partner.unreadCount), "primary")
        val body = card.vPanel(spacing = 6)
        rows[partner.partnerId] = PartnerRow(toggle, body)
        toggle.onClick {
            val current = openPartnerId()
            reloadedAfterMark.remove(partner.partnerId)
            if (current == partner.partnerId) {
                close(partner.partnerId)
                setOpenPartnerId(null)
            } else {
                current?.let(::close)
                setOpenPartnerId(partner.partnerId)
                open(partner)
            }
        }
    }
    // A reload (after marking read / a reply) keeps the open conversation open.
    openPartnerId()?.let { id -> partners.firstOrNull { it.partnerId == id }?.let(::open) }
}

private fun focusAndScrollIntoView(element: HTMLElement?) {
    if (element == null) return
    element.focus()
    element.asDynamic().scrollIntoView(js("({ block: 'start', behavior: 'auto' })"))
}

/**
 * V1.9.237 -- "Blockieren" in the header of an open conversation: a plain text button (no warning colour), then an inline confirmation in the
 * R27 order -- "Abbrechen" on the left, "Blockieren" on the right. A blocked person can no longer write to the viewer (the server answers them
 * with the same sentence as for an unknown recipient). Lifting is on this page under "Blockierte Personen".
 */
internal fun SimplePanel.blockControl(
    block: suspend () -> Boolean,
    onBlocked: () -> Unit,
) {
    val host = simplePanel()
    lateinit var showButton: () -> Unit
    showButton = {
        host.removeAll()
        val open = Button(tr("Blockieren"), style = ButtonStyle.LINK)
        open.addCssClasses("p-0 ms-auto")
        open.setAttribute("type", "button")
        open.onClick {
            host.removeAll()
            host
                .div(
                    tr(
                        "Diese Person kann Ihnen danach nicht mehr schreiben. Sie können die Sperre unten auf dieser Seite wieder aufheben.",
                    ),
                    className = "text-muted small mb-1",
                ).setAttribute("role", "status")
            val cancel = newActionButton(ActionIcon.CANCEL, tr("Abbrechen"), ButtonStyle.OUTLINESECONDARY, small = true)
            val confirm = newActionButton(ActionIcon.REMOVE, tr("Blockieren"), ButtonStyle.OUTLINEPRIMARY, small = true)
            cancel.onClick { showButton() }
            confirm.onClick {
                AppScope.launch {
                    if (block()) {
                        notifySuccess(tr("Die Person wurde blockiert."))
                        onBlocked()
                    }
                }
            }
            val buttons = host.hPanel(spacing = 8)
            buttons.add(cancel)
            buttons.add(confirm)
        }
        host.add(open)
    }
    showButton()
}
