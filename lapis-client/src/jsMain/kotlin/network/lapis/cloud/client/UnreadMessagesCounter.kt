package network.lapis.cloud.client

import io.kvision.html.Link
import io.kvision.i18n.gettext
import io.kvision.i18n.tr
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import network.lapis.cloud.shared.rpc.IDirectMessageService

/** The one call the sidebar counter needs -- an interface so Karma tests can replace it. */
internal fun interface UnreadCountRpc {
    suspend fun unreadCount(): Int
}

internal fun liveUnreadCountRpc(): UnreadCountRpc = UnreadCountRpc { rpcService<IDirectMessageService>().unreadCount() }

/** `null`/0 -> plain label, 1..99 -> "(n)", 100+ -> "(99+)". Pure. */
internal fun communicationSidebarLabel(unread: Int?): String =
    when {
        unread == null || unread <= 0 -> tr("Kommunikation")
        unread >= 100 -> gettext("Kommunikation (%1)", "99+")
        else -> gettext("Kommunikation (%1)", unread)
    }

/**
 * Welle V1.9.34 -- unread direct-message counter on the sidebar's COMMUNICATION link. No timer, no websocket: it refreshes when the
 * sidebar is built, on every route change ([onRouteShown], event-driven) and after the inbox/conversation marks messages as read or a reply was sent. Calls are coalesced: at most one in
 * flight plus one follow-up. Failures are silent on purpose (an inactive member's `unreadCount` is rejected on every sidebar build; a
 * toast each time would be noise) -- same posture as the other sidebar counters.
 */
internal object UnreadMessages {
    internal var rpc: UnreadCountRpc = liveUnreadCountRpc()
    private var link: Link? = null
    private var inFlight = false
    private var again = false

    fun attach(link: Link) {
        this.link = link
        refresh()
    }

    /** Called by `Routing.kt`'s `show()` on every screen render: one coalesced refresh per route change, no timer. */
    fun onRouteShown() = refresh()

    fun detach() {
        link = null
        again = false
    }

    fun refresh() {
        val target = link ?: return
        if (inFlight) {
            again = true
            return
        }
        inFlight = true
        AppScope.launch {
            var count: Int? = null
            try {
                count = rpc.unreadCount()
            } catch (e: CancellationException) {
                inFlight = false
                throw e
            } catch (_: Exception) {
                count = null
            }
            inFlight = false
            if (link === target && AppState.session != null) target.label = communicationSidebarLabel(count)
            if (again) {
                again = false
                refresh()
            }
        }
    }

    internal fun resetForTest() {
        link = null
        inFlight = false
        again = false
        rpc = liveUnreadCountRpc()
    }
}
