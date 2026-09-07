package network.lapis.cloud.server.accounting.export.lexoffice

import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.ComparableTimeMark
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeSource

/**
 * Welle V1.4.5.3 "lexoffice-Live-Anbindung" -- defense-in-depth against lexoffice's own documented
 * limit (2 requests/second, token bucket, live-verified -- see the adoc "Verified API facts"). The
 * PRIMARY throttle is `AccountingExportPoller`'s own tick cadence (at most
 * [network.lapis.cloud.server.accounting.export.AccountingExportPoller.MAX_ITEMS_PER_TICK] sends
 * per [network.lapis.cloud.server.accounting.export.AccountingExportConfig.pollIntervalSeconds]
 * seconds, sequential, never concurrent) -- this limiter is the SECOND layer, because
 * `testConnection`/`listCategories` reach lexoffice from the synchronous RPC path too, entirely
 * outside the poller's own cadence. A single, module-scoped instance MUST be shared across every
 * caller ([network.lapis.cloud.server.Application.module] constructs it once) -- a per-call
 * instance would defeat the whole point.
 *
 * A plain [Mutex] serializing a minimum inter-request gap, not a real token-bucket with burst
 * capacity -- deliberately the simplest thing that cannot exceed the limit, at the cost of a
 * request occasionally waiting slightly longer than strictly necessary. [MIN_GAP] is set
 * comfortably under the nominal 500ms/request (2/s) to leave headroom for network jitter (see the
 * live docs' own "various layers of network infrastructure... will result in jitter" caveat) while
 * still keeping the poller's own 3-items/2s cadence (~1.5 req/s) as the dominant, much lower actual
 * rate in practice.
 */
internal class LexofficeRateLimiter(
    private val minGap: kotlin.time.Duration = MIN_GAP,
    private val timeSource: TimeSource.WithComparableMarks = TimeSource.Monotonic,
) {
    private val mutex = Mutex()
    private var lastRequestMark: ComparableTimeMark? = null

    /** Suspends until at least [minGap] has elapsed since the previous [acquire] call returned --
     * callers must call this immediately before every actual HTTP request to lexoffice. */
    suspend fun acquire() {
        mutex.withLock {
            val now = timeSource.markNow()
            val last = lastRequestMark
            if (last != null) {
                val elapsed = now - last
                if (elapsed < minGap) {
                    delay(minGap - elapsed)
                }
            }
            lastRequestMark = timeSource.markNow()
        }
    }

    private companion object {
        val MIN_GAP = 600.milliseconds
    }
}
