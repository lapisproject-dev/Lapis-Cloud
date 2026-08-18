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
 * Also reused, unmodified as a class, for Welle V1.1.3's `network.lapis.cloud.server.routes
 * .SocialPublicRoutes` -- the first OTHER caller of this class, and the first one to key it by an
 * IPv6-normalized value (`network.lapis.cloud.server.routes.rateLimitKeyFor`) rather than a raw
 * remote-host string.
 *
 * **Known scope-cut (documented, not fixed this wave)**: per-JVM-instance state, same as
 * [LoginRateLimiter]'s own documented limitation -- a multi-server deployment would need a shared
 * store for this to be effective across instances.
 *
 * **Bounded-eviction hardening (Welle V1.1.3, Security-Loop finding on the public read path)**:
 * [evictExpiredIfOverCapacity] used to remove ONLY expired entries once [maxTrackedKeys] was
 * exceeded -- harmless for every caller before this wave, because every prior instance was either
 * member-keyed (key space bounded by the membership count) or gated behind a valid HTTP Signature
 * (Federation inbox). `SocialPublicRoutes` is the first caller whose key space is bounded only by
 * "the internet": a distributed flood using many DIFFERENT, all-fresh source IPs would previously
 * grow [requestsByKey] past [maxTrackedKeys] without bound, because none of those entries are
 * EXPIRED yet. [evictExpiredIfOverCapacity] now falls back to evicting the entries with the OLDEST
 * [RequestWindow.windowStart] once expired-only eviction still leaves the map over capacity -- a
 * no-op for every existing caller (none of them ever reaches [maxTrackedKeys]), and a hard memory
 * ceiling for the new one.
 *
 * **Amortized batch eviction (Review-Runde-1 finding M3, 2026-08-18)**: the fallback above used to
 * evict EXACTLY the one entry over capacity, every single call, once the map sat at
 * [maxTrackedKeys] -- which turned this hardening into a CPU amplifier in EXACTLY the scenario it
 * was built to defend against. Under a distributed fresh-IP flood, [requestsByKey] sits pinned at
 * capacity and EVERY further request pays a full `entries.sortedBy { windowStart }` (an O(n log n)
 * sort over up to 50 000 elements, several MB of allocation) just to remove ONE entry -- unbounded
 * memory growth traded for unbounded CPU/GC load, no better. [evictExpiredIfOverCapacity] now
 * evicts a whole BATCH -- down to [EVICTION_TARGET_LOAD_FACTOR] of [maxTrackedKeys] -- whenever the
 * expensive fallback path runs at all, so that path is paid roughly once per
 * `(1 - EVICTION_TARGET_LOAD_FACTOR) * maxTrackedKeys` new keys instead of on every single request:
 * with the chosen 0.9 factor and `maxTrackedKeys = 50_000`, that is once per ~5 000 requests, i.e.
 * the same total sorting work amortized over thousands of calls instead of paid in full on each
 * one. A no-op for every existing member-keyed/signature-gated caller, exactly as before -- none of
 * them ever reaches [maxTrackedKeys], so the early return above still short-circuits for them.
 */
class FederationInboxRateLimiter(
    private val maxRequests: Int = 60,
    private val window: Duration = 1.minutes,
    private val maxTrackedKeys: Int = 10_000,
) {
    companion object {
        /**
         * Fraction of [maxTrackedKeys] the map is evicted DOWN TO once the expensive fallback path
         * in [evictExpiredIfOverCapacity] runs -- see the class KDoc "Amortized batch eviction" for
         * why evicting only the exact overflow amount (one entry) turned this hardening into a CPU
         * amplifier under a distributed fresh-IP flood.
         */
        private const val EVICTION_TARGET_LOAD_FACTOR = 0.9
    }

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
                if (existing == null || isExpired(entry = existing, now = now)) {
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

    /** Test-only introspection of the current tracked-key count -- `internal`, not part of the public rate-limiting contract; used by [FederationInboxRateLimiterTest] to verify the amortized batch-eviction target (M3). */
    internal fun trackedKeyCountForTest(): Int = requestsByKey.size

    /**
     * See class KDoc "Bounded-eviction hardening" for why the oldest-`windowStart` fallback exists,
     * and "Amortized batch eviction" for why it evicts down to a target BELOW [maxTrackedKeys]
     * rather than removing only the single entry that pushed the map over capacity.
     */
    private fun evictExpiredIfOverCapacity(now: Instant) {
        if (requestsByKey.size <= maxTrackedKeys) return
        requestsByKey.entries.removeIf { (_, entry) -> isExpired(entry = entry, now = now) }
        if (requestsByKey.size <= maxTrackedKeys) return
        val target = (maxTrackedKeys * EVICTION_TARGET_LOAD_FACTOR).toInt()
        val overCapacityBy = requestsByKey.size - target
        requestsByKey.entries
            .sortedBy { it.value.windowStart }
            .take(overCapacityBy)
            .forEach { requestsByKey.remove(it.key) }
    }
}
