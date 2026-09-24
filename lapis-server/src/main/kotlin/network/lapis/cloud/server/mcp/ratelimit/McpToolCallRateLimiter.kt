package network.lapis.cloud.server.mcp.ratelimit

import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant
import kotlin.uuid.Uuid

internal sealed interface McpRateLimitDecision {
    data object Allowed : McpRateLimitDecision

    data class TokenLimited(
        val retryAfterSeconds: Int,
    ) : McpRateLimitDecision

    data class MemberLimited(
        val retryAfterSeconds: Int,
    ) : McpRateLimitDecision

    data class ServerLimited(
        val retryAfterSeconds: Int,
    ) : McpRateLimitDecision
}

/**
 * Three-tier sliding-window limiter for `tools/call` -- per token, per member, and a server-wide
 * cost ceiling, same in-memory/bounded-map/opportunistic-eviction shape as
 * [network.lapis.cloud.server.ai.ratelimit.AiQuestionRateLimiter] (see that class KDoc for the
 * documented per-JVM-instance scope cut this inherits unchanged).
 *
 * [checkAndRecord] counts the call the moment it is allowed, BEFORE the tool actually runs -- a
 * failing/timing-out tool must not be usable to bypass the limit, same posture
 * `AiQuestionRateLimiter.checkAndRecord` KDoc documents.
 */
internal class McpToolCallRateLimiter(
    private val perTokenPerMinute: Int,
    private val perMemberPerHour: Int,
    private val perServerPerDay: Int,
    private val maxTrackedKeys: Int = 100_000,
    private val now: () -> Instant = { Clock.System.now() },
) {
    private val lock = Any()
    private val perToken = HashMap<Uuid, ArrayDeque<Instant>>()
    private val perMember = HashMap<Uuid, ArrayDeque<Instant>>()
    private val server = ArrayDeque<Instant>()

    fun checkAndRecord(
        memberId: Uuid,
        tokenId: Uuid,
    ): McpRateLimitDecision =
        synchronized(lock) {
            val current = now()
            prune(window = server, cutoff = current - DAY)
            val tokenWindow = perToken.getOrPut(tokenId) { ArrayDeque() }
            prune(window = tokenWindow, cutoff = current - MINUTE)
            val memberWindow = perMember.getOrPut(memberId) { ArrayDeque() }
            prune(window = memberWindow, cutoff = current - HOUR)

            // `evictIfOverCapacity` runs at the end of EVERY call, not just the Allowed branch below
            // -- both `perToken` and `perMember` grow via `getOrPut` above regardless of the eventual
            // decision, so a server permanently pinned at `perServerPerDay` (every call rejected as
            // ServerLimited before ever reaching the old Allowed-only eviction call) must still get its
            // tracked-key maps reclaimed, or `maxTrackedKeys` stops being a real bound in exactly the
            // sustained-load scenario it exists for. See McpToolCallRateLimiterTest for the pinned
            // regression.
            val decision =
                when {
                    tokenWindow.size >= perTokenPerMinute ->
                        McpRateLimitDecision.TokenLimited(
                            retryAfterSeconds = retryAfter(oldest = tokenWindow.first(), window = MINUTE, current = current),
                        )
                    memberWindow.size >= perMemberPerHour ->
                        McpRateLimitDecision.MemberLimited(
                            retryAfterSeconds = retryAfter(oldest = memberWindow.first(), window = HOUR, current = current),
                        )
                    server.size >= perServerPerDay ->
                        McpRateLimitDecision.ServerLimited(
                            retryAfterSeconds = retryAfter(oldest = server.first(), window = DAY, current = current),
                        )
                    else -> null
                }
            if (decision == null) {
                tokenWindow.addLast(current)
                memberWindow.addLast(current)
                server.addLast(current)
            }
            evictIfOverCapacity(current)
            decision ?: McpRateLimitDecision.Allowed
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
        if (perToken.size > maxTrackedKeys) {
            val cutoff = current - MINUTE
            perToken.entries.removeIf { (_, window) ->
                prune(window = window, cutoff = cutoff)
                window.isEmpty()
            }
        }
        if (perMember.size > maxTrackedKeys) {
            val cutoff = current - HOUR
            perMember.entries.removeIf { (_, window) ->
                prune(window = window, cutoff = cutoff)
                window.isEmpty()
            }
        }
    }

    private companion object {
        val MINUTE: Duration = 1.minutes
        val HOUR: Duration = 1.hours
        val DAY: Duration = 1.days
    }
}
