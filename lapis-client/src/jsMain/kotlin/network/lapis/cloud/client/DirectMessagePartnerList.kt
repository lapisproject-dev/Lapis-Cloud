package network.lapis.cloud.client

import io.kvision.core.onClick
import io.kvision.html.Button
import io.kvision.html.ButtonStyle
import io.kvision.html.H2
import io.kvision.html.div
import io.kvision.i18n.gettext
import io.kvision.i18n.tr
import io.kvision.panel.SimplePanel
import io.kvision.panel.hPanel
import io.kvision.panel.vPanel
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
 * Welle V1.9.36 -- "Gespräche": everyone the viewer has exchanged messages with, newest activity first, with name, last-activity time
 * and an unread count -- never a message text. Opening a row shows the conversation ([conversationView]) and a reply form below it;
 * only one conversation is open at a time. The list loads FIRST ([onFirstLoadSettled] runs after its first load, success or failure):
 * the screen builds the inbox only then, because the inbox marks everything read on load and the "n neu" hint would otherwise
 * already be empty. The heading is the target of `?section=messages` (focusable, `tabindex=-1`; [focusHeading] takes the focus on insert).
 */
internal fun renderDirectMessagePartnerList(
    root: SimplePanel,
    rpc: DirectMessagePartnerListRpc = liveDirectMessagePartnerListRpc(),
    conversationRpc: DirectMessageConversationRpc = liveDirectMessageConversationRpc(),
    onFirstLoadSettled: () -> Unit = {},
    focusHeading: Boolean = false,
    replySend: suspend (String, String) -> DirectMessageDto = { recipient, body ->
        rpcService<IDirectMessageService>().sendDirectMessage(recipient, body)
    },
) {
    val heading = H2(content = tr("Gespräche"), className = "h5")
    heading.setAttribute("id", PARTNER_LIST_HEADING_ID)
    heading.setAttribute("tabindex", "-1")
    // Inserted AFTER the page title's own focus hook (document order), so `?section=messages` wins the focus.
    root.addWithLifecycle(
        heading,
        onInsert = { vnode -> if (focusHeading) focusAndScrollIntoView(vnode.elm as? HTMLElement) },
    )
    var firstSettled = false
    var openPartnerId: String? = null
    val reloadedAfterMark = mutableSetOf<String>()
    lateinit var section: DataSection
    section =
        root.dataSection<List<DirectMessagePartnerDto>>(
            emptyText = tr("Noch keine Gespräche."),
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
                    reload = { section.reload() },
                    replySend = replySend,
                )
            },
        )
    section.reload()
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
    reload: () -> Unit,
    replySend: suspend (String, String) -> DirectMessageDto,
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
        row.body.conversationView(
            otherMemberId = partner.partnerId,
            knownUnread = partner.unreadCount,
            rpc = conversationRpc,
            // One reload per open conversation: a reopened view after that reload must not trigger the next one, whatever it sees.
            onMarked = { if (reloadedAfterMark.add(partner.partnerId)) reload() },
        )
        row.body.directMessageReplyForm(recipientId = partner.partnerId, onSent = reload, send = replySend)
    }

    partners.forEach { partner ->
        val unread = partner.unreadCount > 0
        val card = list.vPanel(spacing = 4) { addCssClasses(if (unread) "border border-primary rounded p-2" else "border rounded p-2") }
        val head = card.hPanel(spacing = 8) { addCssClass("align-items-center") }
        val toggle = Button(sanitizeUntrustedI18nText(partner.partnerDisplayName), style = ButtonStyle.LINK)
        toggle.addCssClasses("flex-grow-1 text-start p-0")
        if (unread) toggle.addCssClass("fw-bold")
        toggle.setAttribute("aria-expanded", "false")
        head.add(toggle)
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
