package network.lapis.cloud.client

import io.kvision.i18n.tr
import io.kvision.panel.SimplePanel
import kotlinx.browser.window
import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.shared.domain.DirectMessagePartnerDto

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
) {
    val root = container.pageShell(PageWidth.WIDE, spacing = 14)
    root.pageHeader(tr("Nachrichten"), intro = PageIntros.forRoute(Routes.MESSAGES))
    renderDirectMessagePartnerList(
        root,
        focusHeading = parseHashQueryParam(deepLinkHash, "section") == MESSAGES_SECTION,
    )
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
