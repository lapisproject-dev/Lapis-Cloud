package network.lapis.cloud.server.bulletin

import network.lapis.cloud.shared.rpc.ConflictException
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant
import kotlin.uuid.Uuid

/** The rate-limited actions of the bulletin board. */
internal enum class NoticeBoardAction(
    val limit: Int,
    val window: Duration,
) {
    /** Create, edit, withdraw. */
    WRITE(limit = 10, window = 1.hours),
    BOOST(limit = 20, window = 1.hours),
    REPLY(limit = 20, window = 1.hours),
    READ(limit = 120, window = 1.minutes),

    /** Pin, unpin, remove. */
    MODERATE(limit = 30, window = 1.minutes),
}

/**
 * V1.9.297 -- sliding-window limiter per `(member, action)`: in-memory, bounded map, opportunistic eviction (same shape as `CarpoolRateLimiter`).
 * State is per JVM instance, so a multi-instance deployment multiplies the effective limits (documented limitation; this codebase targets one
 * instance). [checkAndRecord] is called BEFORE the transaction: a write inside an Exposed block would be repeated whenever Exposed retries it.
 */
internal class NoticeBoardRateLimiter(
    private val maxTrackedKeys: Int = 50_000,
    private val now: () -> Instant = { Clock.System.now() },
) {
    private val lock = Any()
    private val windows = HashMap<Pair<Uuid, NoticeBoardAction>, ArrayDeque<Instant>>()

    /** `true` and the call is counted, or `false` when the limit of [action] is reached (nothing is counted then). */
    fun tryAcquire(
        memberId: Uuid,
        action: NoticeBoardAction,
    ): Boolean =
        synchronized(lock) {
            val current = now()
            val window = windows.getOrPut(memberId to action) { ArrayDeque() }
            prune(window = window, cutoff = current - action.window)
            if (window.size >= action.limit) return@synchronized false
            window.addLast(current)
            if (windows.size > maxTrackedKeys) evict(current)
            true
        }

    /** Like [tryAcquire] but throws the user-facing [ConflictException]. */
    fun checkAndRecord(
        memberId: Uuid,
        action: NoticeBoardAction,
    ) {
        if (!tryAcquire(memberId = memberId, action = action)) {
            throw ConflictException("Zu viele Aktionen in kurzer Zeit. Bitte versuchen Sie es später erneut.")
        }
    }

    private fun prune(
        window: ArrayDeque<Instant>,
        cutoff: Instant,
    ) {
        while (window.isNotEmpty() && window.first() <= cutoff) window.removeFirst()
    }

    private fun evict(current: Instant) {
        windows.entries.removeIf { (key, window) ->
            prune(window = window, cutoff = current - key.second.window)
            window.isEmpty()
        }
    }
}
