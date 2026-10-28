package network.lapis.cloud.client

import io.kvision.core.onClick
import io.kvision.html.ButtonStyle
import io.kvision.i18n.tr
import io.kvision.panel.SimplePanel
import io.kvision.panel.simplePanel
import kotlinx.browser.window
import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.shared.domain.DirectMessagePartnerDto
import network.lapis.cloud.shared.rpc.IDirectMessageService

/**
 * V1.9.231 -- "Nachrichten": the member's personal conversations in ONE list (the former "Gespräche" list and the separate "Postfach",
 * which listed the same messages once more by message instead of by partner, are merged). One row per conversation partner; opening a row
 * shows the conversation and the reply form ([renderDirectMessagePartnerList]). Only opening a conversation marks it read, so the unread
 * figures (navbar envelope, sidebar pill) stay true until then.
 *
 * Mailing-list letters (Rundschreiben) are NOT part of this list yet: a member cannot read the sent messages of the lists they subscribed
 * to (`IMailingService.listMailingMessages` is board-only) and this wave adds no server call. [InboxRow] is an interface so that a
 * circular-letter row can join [ConversationRow] later without touching the sorting.
 *
 * `Routes.COMMUNICATION` (the old address) now redirects here; `?section=messages` is still honoured (the heading takes the focus).
 */
fun renderMessagesScreen(
    container: SimplePanel,
    deepLinkHash: String = window.location.hash,
) = renderMessagesScreen(container, deepLinkHash, MessagesScreenDependencies())

/** Everything the Nachrichten page talks to -- replaceable in a DOM test. */
internal class MessagesScreenDependencies(
    val listRpc: DirectMessagePartnerListRpc = liveDirectMessagePartnerListRpc(),
    val conversationRpc: DirectMessageConversationRpc = liveDirectMessageConversationRpc(),
    val composeRpc: DirectMessageComposeRpc = liveDirectMessageComposeRpc(),
    val discoverabilityRpc: DirectMessageDiscoverabilityRpc = liveDirectMessageDiscoverabilityRpc(),
    val blockSender: suspend (String) -> Unit = { rpcService<IDirectMessageService>().blockMessageSender(it) },
    val attachmentHttp: DirectMessageAttachmentHttp = DirectMessageHttp,
)

/** `?view=new` on [Routes.MESSAGES]: the compose view. The URL carries no id -- who the message goes to is never in the address. */
internal const val MESSAGES_VIEW_PARAM = "view"
internal const val MESSAGES_VIEW_NEW = "new"

/**
 * V1.9.237 -- the page switches between two views IN MEMORY: the list (default) and "Neue Nachricht", which replaces the list area (there is no
 * right-hand pane). `?view=new` opens the compose view directly and is kept in the address with `replaceState` (reload-safe, no history entry).
 * After a message to a person the list opens with that conversation; after a group message the list simply returns.
 */
internal fun renderMessagesScreen(
    container: SimplePanel,
    deepLinkHash: String,
    deps: MessagesScreenDependencies,
) {
    val root = container.pageShell(PageWidth.WIDE, spacing = 14)
    lateinit var showList: (String?) -> Unit
    lateinit var showCompose: () -> Unit
    root.pageHeader(
        tr("Nachrichten"),
        intro = PageIntros.forRoute(Routes.MESSAGES),
        primaryAction = {
            actionGroup {
                add(
                    newActionButton(
                        ActionIcon.COMPOSE,
                        tr("Neue Nachricht"),
                        ButtonStyle.OUTLINEPRIMARY,
                    ).also { it.onClick { showCompose() } },
                )
            }
        },
    )
    val host = root.simplePanel()
    val focusHeading = parseHashQueryParam(deepLinkHash, "section") == MESSAGES_SECTION

    fun replaceHash(hash: String) {
        runCatching { window.history.replaceState(null, "", hash) }
    }
    showList = { openPartnerId ->
        replaceHash("#${Routes.MESSAGES}")
        host.removeAll()
        val panel = host.simplePanel()
        renderDirectMessagePartnerList(
            panel,
            rpc = deps.listRpc,
            conversationRpc = deps.conversationRpc,
            focusHeading = focusHeading && openPartnerId == null,
            attachmentHttp = deps.attachmentHttp,
            openPartnerOnLoad = openPartnerId,
            blockSender = deps.blockSender,
        )
        renderDirectMessageDiscoverabilitySection(panel, deps.discoverabilityRpc)
    }
    showCompose = {
        replaceHash("#${Routes.MESSAGES}?$MESSAGES_VIEW_PARAM=$MESSAGES_VIEW_NEW")
        host.removeAll()
        val panel = host.simplePanel()
        panel.directMessageComposeView(
            rpc = deps.composeRpc,
            attachmentHttp = deps.attachmentHttp,
            onSentToPerson = { partnerId -> showList(partnerId) },
            onSentToGroup = { showList(null) },
            onBack = { showList(null) },
        )
    }
    if (parseHashQueryParam(deepLinkHash, MESSAGES_VIEW_PARAM) == MESSAGES_VIEW_NEW) showCompose() else showList(null)
}

/** One row of the message list. [typeRank] orders the types when two rows share a time (conversation before circular letter). */
internal interface InboxRow {
    val sortAt: LocalDateTime
    val typeRank: Int
    val id: String
}

/** A conversation with one partner (the row of the partner list). */
internal class ConversationRow(
    val partner: DirectMessagePartnerDto,
) : InboxRow {
    override val sortAt: LocalDateTime get() = partner.lastActivityAt
    override val typeRank: Int get() = 0
    override val id: String get() = partner.partnerId
}

/** Newest first; on the same time a conversation before any other type, then by id ascending. Deterministic for equal times. Pure. */
internal fun sortInboxRows(rows: List<InboxRow>): List<InboxRow> =
    rows.sortedWith(
        compareByDescending<InboxRow> { it.sortAt }
            .thenBy { it.typeRank }
            .thenBy { it.id },
    )

/** `#<route>` or `#<route>?<query>` from a Navigo match: Navigo runs the handler BEFORE it pushes the URL, so the hash is taken from the match. */
internal fun routeHashOfMatch(
    route: String,
    queryString: String?,
): String = if (queryString.isNullOrEmpty()) "#$route" else "#$route?$queryString"
