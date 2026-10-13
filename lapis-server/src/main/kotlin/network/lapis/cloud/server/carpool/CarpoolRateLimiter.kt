package network.lapis.cloud.server.carpool

import network.lapis.cloud.shared.rpc.ConflictException
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant
import kotlin.uuid.Uuid

/** The rate-limited actions of the carpool participation feature. Confirm, decline and withdraw share ONE budget ([DECIDE]). */
internal enum class CarpoolAction(
    val perHour: Int,
) {
    REQUEST(perHour = 20),
    INVITE(perHour = 20),
    FIND(perHour = 10),
    DECIDE(perHour = 60),
}

/**
 * V1.9.125 -- sliding-window limiter per `(member, action)`: in-memory, bounded map, opportunistic eviction instead of a scheduler (same
 * shape as `AiQuestionRateLimiter`). State is per JVM instance, so a multi-instance deployment multiplies the effective limits (documented
 * known limitation; this codebase targets a single instance). [checkAndRecord] is called BEFORE the transaction: a rate-limit write inside
 * an Exposed block would be repeated whenever Exposed retries the block.
 */
internal class CarpoolRateLimiter(
    private val maxTrackedKeys: Int = 50_000,
    private val now: () -> Instant = { Clock.System.now() },
) {
    private val lock = Any()
    private val windows = HashMap<Pair<Uuid, CarpoolAction>, ArrayDeque<Instant>>()

    /** `true` and the call is counted, or `false` when the limit of [action] is reached (nothing is counted then). */
    fun tryAcquire(
        memberId: Uuid,
        action: CarpoolAction,
    ): Boolean =
        synchronized(lock) {
            val current = now()
            val window = windows.getOrPut(memberId to action) { ArrayDeque() }
            prune(window = window, cutoff = current - HOUR)
            if (window.size >= action.perHour) return@synchronized false
            window.addLast(current)
            if (windows.size > maxTrackedKeys) evict(current)
            true
        }

    /** Like [tryAcquire] but throws the user-facing [ConflictException] (every action except `findInvitee`, which answers an empty list). */
    fun checkAndRecord(
        memberId: Uuid,
        action: CarpoolAction,
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
        val cutoff = current - HOUR
        windows.entries.removeIf { (_, window) ->
            prune(window = window, cutoff = cutoff)
            window.isEmpty()
        }
    }

    private companion object {
        val HOUR: Duration = 1.hours
    }
}
