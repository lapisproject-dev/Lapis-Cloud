package network.lapis.cloud.server.memberfinder

import network.lapis.cloud.shared.rpc.MemberFinderRateLimitedException
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant
import kotlin.uuid.Uuid

/** The rate-limited actions of the member finder. Opting OUT is deliberately not among them: a withdrawal of consent is never throttled. */
enum class MemberFinderAction(
    val limit: Int,
    val window: Duration,
) {
    SEND_HOUR(limit = 3, window = 1.hours),
    SEND_DAY(limit = 10, window = 24.hours),
    RESPOND(limit = 30, window = 1.hours),
    BLOCK(limit = 20, window = 1.hours),
    CONSENT(limit = 10, window = 1.hours),
    READ(limit = 240, window = 1.hours),
}

/**
 * V1.9.144 -- sliding-window limiter per `(member, action)`, same shape as `CarpoolRateLimiter`: in-memory, bounded map, state per JVM instance
 * (a multi-instance deployment multiplies the limits -- documented known limitation). Checked BEFORE the transaction: a write inside an Exposed
 * block would be repeated whenever Exposed retries the block. An overrun concerns only the caller's own behaviour, so the typed
 * [MemberFinderRateLimitedException] tells nothing about any other member.
 */
class MemberFinderRateLimiter(
    private val maxTrackedKeys: Int = 50_000,
    private val now: () -> Instant = { Clock.System.now() },
) {
    private val lock = Any()
    private val windows = HashMap<Pair<Uuid, MemberFinderAction>, ArrayDeque<Instant>>()

    /** All-or-nothing: counts one call for every action of [actions], or counts nothing and answers `false` if any of them is at its limit. */
    fun tryAcquire(
        memberId: Uuid,
        vararg actions: MemberFinderAction,
    ): Boolean =
        synchronized(lock) {
            val current = now()
            val deques =
                actions.map { action ->
                    val window = windows.getOrPut(memberId to action) { ArrayDeque() }
                    prune(window = window, cutoff = current - action.window)
                    action to window
                }
            if (deques.any { (action, window) -> window.size >= action.limit }) return@synchronized false
            deques.forEach { (_, window) -> window.addLast(current) }
            if (windows.size > maxTrackedKeys) evict(current)
            true
        }

    fun checkAndRecord(
        memberId: Uuid,
        vararg actions: MemberFinderAction,
    ) {
        if (!tryAcquire(memberId = memberId, *actions)) throw MemberFinderRateLimitedException()
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
