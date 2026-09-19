package network.lapis.cloud.server.ai.ratelimit

import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant
import kotlin.uuid.Uuid

internal sealed interface RateLimitDecision {
    data object Allowed : RateLimitDecision

    data class MemberLimited(
        val retryAfterSeconds: Int,
    ) : RateLimitDecision

    data class ServerLimited(
        val retryAfterSeconds: Int,
    ) : RateLimitDecision
}

/**
 * Sliding-window limiter for AI questions: [perMemberPerHour] per member and [perServerPerDay] for
 * the whole server (a cost ceiling). Same shape as
 * [network.lapis.cloud.server.security.LoginRateLimiter] -- in-memory, bounded map, opportunistic
 * eviction instead of a scheduler -- and the same documented scope cut: state is per JVM instance,
 * so a multi-instance deployment would multiply the effective limits (this codebase targets a
 * single instance).
 *
 * [checkAndRecord] counts a question the moment it is allowed (before the model call), so a
 * failing provider cannot be used to bypass the limit.
 */
internal class AiQuestionRateLimiter(
    private val perMemberPerHour: Int,
    private val perServerPerDay: Int,
    private val maxTrackedKeys: Int = 100_000,
    private val now: () -> Instant = { Clock.System.now() },
) {
    private val lock = Any()
    private val perMember = HashMap<Uuid, ArrayDeque<Instant>>()
    private val server = ArrayDeque<Instant>()

    fun checkAndRecord(memberId: Uuid): RateLimitDecision =
        synchronized(lock) {
            val current = now()
            prune(window = server, cutoff = current - DAY)
            val memberWindow = perMember.getOrPut(memberId) { ArrayDeque() }
            prune(window = memberWindow, cutoff = current - HOUR)

            if (memberWindow.size >= perMemberPerHour) {
                return@synchronized RateLimitDecision.MemberLimited(
                    retryAfterSeconds = retryAfter(oldest = memberWindow.first(), window = HOUR, current = current),
                )
            }
            if (server.size >= perServerPerDay) {
                return@synchronized RateLimitDecision.ServerLimited(
                    retryAfterSeconds = retryAfter(oldest = server.first(), window = DAY, current = current),
                )
            }
            memberWindow.addLast(current)
            server.addLast(current)
            evictIfOverCapacity(current)
            RateLimitDecision.Allowed
        }

    private fun prune(
        window: ArrayDeque<Instant>,
        cutoff: Instant,
    ) {
        while (window.isNotEmpty() && window.first() <= cutoff) window.removeFirst()
    }

    private fun retryAfter(
        oldest: Instant,
        window: Duration,
        current: Instant,
    ): Int = ((oldest + window - current).inWholeSeconds + 1).coerceAtLeast(1).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()

    private fun evictIfOverCapacity(current: Instant) {
        if (perMember.size <= maxTrackedKeys) return
        val cutoff = current - HOUR
        perMember.entries.removeIf { (_, window) ->
            prune(window = window, cutoff = cutoff)
            window.isEmpty()
        }
    }

    private companion object {
        val HOUR: Duration = 1.hours
        val DAY: Duration = 1.days
    }
}
