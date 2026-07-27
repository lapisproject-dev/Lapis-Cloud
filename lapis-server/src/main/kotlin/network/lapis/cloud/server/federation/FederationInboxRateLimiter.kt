package network.lapis.cloud.server.federation

import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/**
 * In-memory, per-instance flood guard for `POST /federation/inbox` (V0.8.1 Federation-Grundgerüst)
 * -- the public inbox is reachable by any server on the internet by design (any self-declared
 * remote server can attempt delivery), so it needs a pure request-RATE limiter, unlike
 * [network.lapis.cloud.server.security.LoginRateLimiter] which counts FAILURES only (correct for
 * a brute-force guard, wrong here -- a legitimate remote server delivering many valid Activities in
 * a burst must not be treated the same as an attacker). Same bounded [ConcurrentHashMap] +
 * sliding-window + opportunistic-eviction idiom as [LoginRateLimiter], reused rather than
 * duplicated in spirit; keyed by remote IP only (no per-actor key -- signature verification has
 * not run yet at the point this check happens, see `FederationRoutes` inbox handler ordering).
 *
 * **Known scope-cut (documented, not fixed this wave)**: per-JVM-instance state, same as
 * [LoginRateLimiter]'s own documented limitation -- a multi-server deployment would need a shared
 * store for this to be effective across instances.
 */
class FederationInboxRateLimiter(
    private val maxRequests: Int = 60,
    private val window: Duration = 1.minutes,
    private val maxTrackedKeys: Int = 10_000,
) {
    private data class RequestWindow(
        val count: Int,
        val windowStart: Instant,
    )

    private val requestsByKey = ConcurrentHashMap<String, RequestWindow>()

    /** `true` iff [remoteHost] is still under [maxRequests] within the current [window] -- records this attempt atomically either way. */
    fun checkAndRecord(remoteHost: String): Boolean {
        val now = Clock.System.now()
        var allowed = true
        requestsByKey.compute(remoteHost) { _, existing ->
            val current =
                if (existing == null || isExpired(existing, now)) {
                    RequestWindow(count = 0, windowStart = now)
                } else {
                    existing
                }
            allowed = current.count < maxRequests
            RequestWindow(count = current.count + 1, windowStart = current.windowStart)
        }
        evictExpiredIfOverCapacity(now)
        return allowed
    }

    private fun isExpired(
        entry: RequestWindow,
        now: Instant,
    ): Boolean = now - entry.windowStart >= window

    private fun evictExpiredIfOverCapacity(now: Instant) {
        if (requestsByKey.size <= maxTrackedKeys) return
        requestsByKey.entries.removeIf { (_, entry) -> isExpired(entry, now) }
    }
}
