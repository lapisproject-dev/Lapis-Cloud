package network.lapis.cloud.client.livekit

import kotlinx.browser.window
import kotlin.js.Date
import kotlin.math.ceil

/**
 * V1.9.24 -- rate limiter for the `lapis-vote-nudge` data-channel signal. Pure and DOM-free (clock and
 * scheduler are injected), so it is unit-testable without a browser.
 *
 * Guarantee: [onRefresh] runs AT MOST once per [minIntervalMs], regardless of how fast peers flood
 * nudges. A nudge arriving inside the window is NOT lost: exactly ONE trailing call is scheduled
 * (coalescing), which matters for closely spaced `openVoting`/`closeVoting` events. The first nudge
 * after a quiet period fires immediately (leading edge).
 */
class VoteNudgeThrottle(
    private val minIntervalMs: Double = DEFAULT_MIN_INTERVAL_MS,
    private val now: () -> Double = { Date.now() },
    private val schedule: (delayMs: Int, block: () -> Unit) -> Any = { delayMs, block -> window.setTimeout(block, delayMs) },
    private val cancelScheduled: (Any) -> Unit = { handle -> window.clearTimeout(handle.unsafeCast<Int>()) },
    private val onRefresh: () -> Unit,
) {
    private var lastFiredAt: Double? = null
    private var pending: Any? = null

    /** Call for every received nudge. */
    fun trigger() {
        if (pending != null) return
        val t = now()
        val last = lastFiredAt
        if (last == null || t - last >= minIntervalMs) {
            lastFiredAt = t
            onRefresh()
            return
        }
        val delay = ceil(minIntervalMs - (t - last)).toInt().coerceAtLeast(0)
        pending =
            schedule(delay) {
                pending = null
                lastFiredAt = now()
                onRefresh()
            }
    }

    /** Discards a pending trailing call (disconnect). Safe to call repeatedly. */
    fun cancel() {
        val handle = pending ?: return
        pending = null
        cancelScheduled(handle)
    }

    companion object {
        const val DEFAULT_MIN_INTERVAL_MS = 2_000.0
    }
}
