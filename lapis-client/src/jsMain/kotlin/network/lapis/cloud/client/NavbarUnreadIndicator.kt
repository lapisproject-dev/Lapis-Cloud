package network.lapis.cloud.client

import io.kvision.html.TAG
import io.kvision.html.Tag
import io.kvision.i18n.gettext
import io.kvision.navbar.Nav
import network.lapis.cloud.shared.domain.SessionInfoDto

/** The key under which the navbar envelope registers with [UnreadMessages]. */
internal const val NAVBAR_UNREAD_DISPLAY_KEY = "navbar"

/** `null`/0 -> no pill, 1..99 -> "n", 100+ -> "99+". Pure. */
internal fun unreadPillText(count: Int?): String? =
    when {
        count == null || count <= 0 -> null
        count >= 100 -> "99+"
        else -> count.toString()
    }

/** The accessible name (and tooltip) of the navbar envelope -- `gettext`, not `tr`: it goes into attributes, which KVision does not translate. Pure. */
internal fun navbarUnreadAccessibleName(count: Int?): String =
    unreadPillText(count)?.let { gettext("Nachrichten, %1 ungelesen", it) } ?: gettext("Nachrichten")

/**
 * Welle V1.9.36 -- the envelope with the unread pill in the navbar (always visible, also on a phone and with a collapsed sidebar).
 * Shown only where the sidebar shows the COMMUNICATION link (organization members; an inactive member's `unreadCount` is rejected
 * anyway). It goes to `#/communication?section=messages` -- a parameter free of personal data. No `aria-live` (the pill is not
 * announced on every refresh), no animation.
 */
internal fun Nav.navbarUnreadIndicator(session: SessionInfoDto) {
    if (!NavVisibility.showsMembershipSection(session.status)) {
        UnreadMessages.unregister(NAVBAR_UNREAD_DISPLAY_KEY)
        return
    }
    // A plain anchor `Tag`, not `navLink`: a `Link` renders no child widgets and has no rich label, and the pill must sit inside the
    // anchor. The markup contains nothing but fixed classes and [unreadPillText] (digits and "+"), so nothing foreign reaches the HTML.
    val link = Tag(TAG.A, rich = true, className = "nav-link lapis-navbar-unread")
    link.setAttribute("href", "#${Routes.COMMUNICATION}?section=$MESSAGES_SECTION")
    add(link)
    UnreadMessages.register(NAVBAR_UNREAD_DISPLAY_KEY) { count ->
        val text = unreadPillText(count)
        val pill = if (text == null) "" else """<span class="badge rounded-pill text-bg-danger lapis-unread-pill">$text</span>"""
        link.content = """<i class="fas fa-envelope" aria-hidden="true"></i>$pill"""
        val name = navbarUnreadAccessibleName(count)
        link.setAttribute("aria-label", name)
        link.setAttribute("title", name)
    }
}
