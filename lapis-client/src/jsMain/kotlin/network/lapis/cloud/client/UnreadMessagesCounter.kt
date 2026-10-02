package network.lapis.cloud.client

import kotlinx.browser.document
import kotlinx.browser.window
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import network.lapis.cloud.shared.rpc.IDirectMessageService

/** The one call the unread indicator needs -- an interface so Karma tests can replace it. */
internal fun interface UnreadCountRpc {
    suspend fun unreadCount(): Int
}

internal fun liveUnreadCountRpc(): UnreadCountRpc = UnreadCountRpc { rpcService<IDirectMessageService>().unreadCount() }

/** Coming back to the tab refreshes at most once per this many milliseconds (a plain timestamp comparison, no timer). */
internal const val TAB_RETURN_REFRESH_THROTTLE_MS = 30_000.0

/**
 * Welle V1.9.34/V1.9.36 -- the unread direct-message count: ONE state, any number of displays (V1.9.36: the navbar envelope; the
 * sidebar link no longer carries a number). No timer, no websocket: it refreshes when a display registers, on every route change
 * ([onRouteShown], unthrottled), after the inbox/conversation marks messages as read or a reply was sent, and when the tab comes back
 * to the foreground ([onTabReturn], throttled by a timestamp). Calls are coalesced: at most one in flight plus one follow-up.
 * Failures are silent on purpose (an inactive member's `unreadCount` is rejected; a toast each time would be noise): the displays
 * then render without a pill.
 *
 * Displays are registered under a KEY, not as an object reference: the navbar is rebuilt on every `refreshNavbar` (language switch,
 * every session change) and the new build simply replaces the old display under the same key -- nothing leaks.
 */
internal object UnreadMessages {
    internal var rpc: UnreadCountRpc = liveUnreadCountRpc()
    internal var now: () -> Double = { kotlin.js.Date.now() }
    internal var isTabVisible: () -> Boolean = { document.asDynamic().visibilityState == "visible" }

    var count: Int? = null
        private set
    private val displays = linkedMapOf<String, (Int?) -> Unit>()
    private var inFlight = false
    private var again = false
    private var generation = 0
    private var listenersInstalled = false

    /** How many displays are registered (a test seam: a rebuilt navbar must replace its display, not add one). */
    internal val displayCount: Int get() = displays.size
    private var lastTabReturnRefreshAt = Double.NEGATIVE_INFINITY

    /** Registers (or replaces) the display [key], renders the current state at once and requests a refresh. */
    fun register(
        key: String,
        render: (Int?) -> Unit,
    ) {
        displays[key] = render
        render(count)
        installTabReturnListenersOnce()
        refresh()
    }

    fun unregister(key: String) {
        displays.remove(key)
    }

    /** Logout / empty shell: no display, no value. */
    fun clear() {
        displays.clear()
        count = null
        again = false
        generation++
    }

    /** Called by `Routing.kt`'s `show()` on every screen render: one coalesced refresh per route change, no timer. */
    fun onRouteShown() = refresh()

    /** Tab came back (visibility or focus): refreshes unless the last tab-return refresh is younger than the throttle. */
    internal fun onTabReturn() {
        if (displays.isEmpty() || !isTabVisible()) return
        val at = now()
        if (at - lastTabReturnRefreshAt < TAB_RETURN_REFRESH_THROTTLE_MS) return
        lastTabReturnRefreshAt = at
        refresh()
    }

    /** `visibilitychange` and `focus` often fire together; both go through [onTabReturn]'s throttle. Installed exactly once. */
    internal fun installTabReturnListenersOnce() {
        if (listenersInstalled) return
        listenersInstalled = true
        document.addEventListener("visibilitychange", { onTabReturn() })
        window.addEventListener("focus", { onTabReturn() })
    }

    fun refresh() {
        if (displays.isEmpty() || AppState.session == null) return
        if (inFlight) {
            again = true
            return
        }
        inFlight = true
        val startedIn = generation
        AppScope.launch {
            var result: Int? = null
            try {
                result = rpc.unreadCount()
            } catch (e: CancellationException) {
                inFlight = false
                throw e
            } catch (_: Exception) {
                result = null
            }
            inFlight = false
            if (AppState.session != null && startedIn == generation) {
                count = result
                displays.values.toList().forEach { it(result) }
            }
            if (again) {
                again = false
                refresh()
            }
        }
    }

    internal fun resetForTest() {
        displays.clear()
        count = null
        generation++
        inFlight = false
        again = false
        lastTabReturnRefreshAt = Double.NEGATIVE_INFINITY
        rpc = liveUnreadCountRpc()
        now = { kotlin.js.Date.now() }
        isTabVisible = { document.asDynamic().visibilityState == "visible" }
    }
}
