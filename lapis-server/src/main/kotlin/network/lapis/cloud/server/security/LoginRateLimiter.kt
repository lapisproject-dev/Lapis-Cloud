package network.lapis.cloud.server.security

import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/**
 * In-memory, per-instance brute-force guard for
 * [network.lapis.cloud.server.routes.registerAuthRoutes]'s login endpoint (V0.7.1
 * Authentifizierung). No scheduler/cron infrastructure exists in this codebase (see CLAUDE.md) —
 * this uses a bounded [ConcurrentHashMap] with opportunistic eviction instead of a background job.
 *
 * **Keyed independently by normalized email AND by client IP** — the caller (`AuthRoutes`) calls
 * [checkAllowed]/[recordFailure]/[reset] once per key, once for `"email:<lowercased-email>"` and
 * once for `"ip:<remote-host>"`, so a single email being hammered from many IPs (or a single IP
 * hammering many emails) both trip the limiter.
 *
 * **Known scope-cut (documented, not fixed this wave)**: this is per-JVM-instance state. A
 * multi-server deployment would need a shared store (e.g. Redis) for the limiter to be effective
 * across instances — out of scope for V0.7.1 (no such infrastructure exists in this codebase yet);
 * a single-instance deployment (this codebase's current target) is fully protected.
 */
class LoginRateLimiter(
    private val maxFailures: Int = 5,
    private val window: Duration = 15.minutes,
    private val maxTrackedKeys: Int = 100_000,
    private val clock: Clock = Clock.System,
) {
    private data class FailureWindow(
        val count: Int,
        val windowStart: Instant,
    )

    private val failuresByKey = ConcurrentHashMap<String, FailureWindow>()

    /** `false` iff [key] has already accumulated [maxFailures] (or more) failures within the current [window]. Does not itself record anything. */
    fun checkAllowed(key: String): Boolean {
        val entry = failuresByKey[key] ?: return true
        if (isExpired(entry)) return true
        return entry.count < maxFailures
    }

    /** Records one failed attempt for [key], starting (or continuing) its sliding window. */
    fun recordFailure(key: String) {
        val now = clock.now()
        failuresByKey.compute(key) { _, existing ->
            if (existing == null || isExpired(existing)) {
                FailureWindow(count = 1, windowStart = now)
            } else {
                FailureWindow(count = existing.count + 1, windowStart = existing.windowStart)
            }
        }
        evictExpiredIfOverCapacity()
    }

    /**
     * Atomically reserves one attempt for [key]: `false` (and nothing recorded) iff [maxFailures] is already reached within the
     * current [window], otherwise the attempt is counted and `true` returned. Unlike the [checkAllowed] + [recordFailure] pair,
     * concurrent callers cannot all pass the check before the first one is recorded. Pair with [reset] on success and
     * [release] when the reserved attempt turns out not to count.
     */
    fun tryAcquire(key: String): Boolean {
        val now = clock.now()
        var allowed = true
        failuresByKey.compute(key) { _, existing ->
            if (existing == null || isExpired(existing)) {
                FailureWindow(count = 1, windowStart = now)
            } else if (existing.count >= maxFailures) {
                allowed = false
                existing
            } else {
                FailureWindow(count = existing.count + 1, windowStart = existing.windowStart)
            }
        }
        if (allowed) evictExpiredIfOverCapacity()
        return allowed
    }

    /** Gives back one attempt reserved by [tryAcquire] for [key]. */
    fun release(key: String) {
        failuresByKey.computeIfPresent(key) { _, existing ->
            if (existing.count <= 1) null else FailureWindow(count = existing.count - 1, windowStart = existing.windowStart)
        }
    }

    /** Clears [key]'s failure count — called on a successful login so a legitimate user is never penalized by earlier, unrelated failed attempts once they DO get in. */
    fun reset(key: String) {
        failuresByKey.remove(key)
    }

    private fun isExpired(entry: FailureWindow): Boolean = clock.now() - entry.windowStart >= window

    /**
     * Bounds unbounded memory growth (a malicious caller cycling through many distinct emails/IPs
     * could otherwise fill the heap) — once the map exceeds [maxTrackedKeys], sweep out every
     * already-expired entry. A best-effort, O(n) sweep triggered only occasionally (map growth is
     * the trigger, not a timer), acceptable since login traffic is inherently low-volume compared
     * to, say, a read-heavy RPC endpoint.
     */
    private fun evictExpiredIfOverCapacity() {
        if (failuresByKey.size <= maxTrackedKeys) return
        val now = clock.now()
        failuresByKey.entries.removeIf { (_, entry) -> now - entry.windowStart >= window }
    }
}
