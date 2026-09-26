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
 *
 * **Welle V1.8.2 amendment**: [writeQuotas] adds a FOURTH, per-write-tool tier on top of the three
 * global windows above -- a write-tool call must clear BOTH its own per-tool quota (token/hour,
 * member/day, server/day) AND the three pre-existing global windows (token/minute, member/hour,
 * server/day). [toolName] is `null`/unmatched (a read tool, or an unknown name) simply skips this
 * fourth tier and counts only against the three global windows -- see `McpToolDispatcher.dispatch`
 * KDoc "an unbekannter Name zählt weiterhin nur gegen die globalen Fenster".
 */
internal class McpToolCallRateLimiter(
    private val perTokenPerMinute: Int,
    private val perMemberPerHour: Int,
    private val perServerPerDay: Int,
    private val writeQuotas: Map<String, WriteQuota> = emptyMap(),
    private val maxTrackedKeys: Int = 100_000,
    private val now: () -> Instant = { Clock.System.now() },
) {
    /** One write tool's own three-tier budget, additive on top of the global windows. */
    data class WriteQuota(
        val perTokenPerHour: Int,
        val perMemberPerDay: Int,
        val perServerPerDay: Int,
    )

    private val lock = Any()
    private val perToken = HashMap<Uuid, ArrayDeque<Instant>>()
    private val perMember = HashMap<Uuid, ArrayDeque<Instant>>()
    private val server = ArrayDeque<Instant>()

    /** Keyed by `toolName` -> per-token deque; only entries in [writeQuotas] are ever populated. */
    private val writeToolPerToken = HashMap<String, HashMap<Uuid, ArrayDeque<Instant>>>()
    private val writeToolPerMember = HashMap<String, HashMap<Uuid, ArrayDeque<Instant>>>()
    private val writeToolPerServer = HashMap<String, ArrayDeque<Instant>>()

    fun checkAndRecord(
        memberId: Uuid,
        tokenId: Uuid,
        toolName: String? = null,
    ): McpRateLimitDecision =
        synchronized(lock) {
            val current = now()
            prune(window = server, cutoff = current - DAY)
            val tokenWindow = perToken.getOrPut(tokenId) { ArrayDeque() }
            prune(window = tokenWindow, cutoff = current - MINUTE)
            val memberWindow = perMember.getOrPut(memberId) { ArrayDeque() }
            prune(window = memberWindow, cutoff = current - HOUR)

            val quota = toolName?.let { writeQuotas[it] }
            val writeTokenWindow = quota?.let { writeToolPerToken.getOrPut(toolName) { HashMap() }.getOrPut(tokenId) { ArrayDeque() } }
            writeTokenWindow?.let { prune(window = it, cutoff = current - HOUR) }
            val writeMemberWindow = quota?.let { writeToolPerMember.getOrPut(toolName) { HashMap() }.getOrPut(memberId) { ArrayDeque() } }
            writeMemberWindow?.let { prune(window = it, cutoff = current - DAY) }
            val writeServerWindow = quota?.let { writeToolPerServer.getOrPut(toolName) { ArrayDeque() } }
            writeServerWindow?.let { prune(window = it, cutoff = current - DAY) }

            // `evictIfOverCapacity` runs at the end of EVERY call, not just the Allowed branch below
            // -- both `perToken` and `perMember` grow via `getOrPut` above regardless of the eventual
            // decision, so a server permanently pinned at `perServerPerDay` (every call rejected as
            // ServerLimited before ever reaching the old Allowed-only eviction call) must still get its
            // tracked-key maps reclaimed, or `maxTrackedKeys` stops being a real bound in exactly the
            // sustained-load scenario it exists for. See McpToolCallRateLimiterTest for the pinned
            // regression. The same discipline applies to the new write-tool maps below.
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
                    quota != null && writeTokenWindow!!.size >= quota.perTokenPerHour ->
                        McpRateLimitDecision.TokenLimited(
                            retryAfterSeconds = retryAfter(oldest = writeTokenWindow.first(), window = HOUR, current = current),
                        )
                    quota != null && writeMemberWindow!!.size >= quota.perMemberPerDay ->
                        McpRateLimitDecision.MemberLimited(
                            retryAfterSeconds = retryAfter(oldest = writeMemberWindow.first(), window = DAY, current = current),
                        )
                    quota != null && writeServerWindow!!.size >= quota.perServerPerDay ->
                        McpRateLimitDecision.ServerLimited(
                            retryAfterSeconds = retryAfter(oldest = writeServerWindow.first(), window = DAY, current = current),
                        )
                    else -> null
                }
            if (decision == null) {
                tokenWindow.addLast(current)
                memberWindow.addLast(current)
                server.addLast(current)
                writeTokenWindow?.addLast(current)
                writeMemberWindow?.addLast(current)
                writeServerWindow?.addLast(current)
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
        writeToolPerToken.values.forEach { byToken ->
            if (byToken.size > maxTrackedKeys) {
                val cutoff = current - HOUR
                byToken.entries.removeIf { (_, window) ->
                    prune(window = window, cutoff = cutoff)
                    window.isEmpty()
                }
            }
        }
        writeToolPerMember.values.forEach { byMember ->
            if (byMember.size > maxTrackedKeys) {
                val cutoff = current - DAY
                byMember.entries.removeIf { (_, window) ->
                    prune(window = window, cutoff = cutoff)
                    window.isEmpty()
                }
            }
        }
    }

    private companion object {
        val MINUTE: Duration = 1.minutes
        val HOUR: Duration = 1.hours
        val DAY: Duration = 1.days
    }
}
