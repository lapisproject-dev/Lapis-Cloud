package network.lapis.cloud.server.accounting.export.sevdesk

import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.ComparableTimeMark
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeSource

/**
 * Welle V1.4.5.4 "sevDesk-Live-Anbindung" -- same shape and role as
 * [network.lapis.cloud.server.accounting.export.lexoffice.LexofficeRateLimiter]: a plain [Mutex]
 * serializing a minimum inter-request gap, shared as a SINGLE module-scoped instance across every
 * caller ([network.lapis.cloud.server.Application.module] constructs it once). The PRIMARY
 * throttle remains `AccountingExportPoller`'s own tick cadence; this is the second layer for the
 * synchronous `testConnection`/`listCategories` RPC path.
 *
 * **Unlike the lexoffice limiter, [MIN_GAP] here is NOT derived from a documented provider limit.**
 * The sevDesk OpenAPI specification (`https://api.sevdesk.de/openapi.yaml`, retrieved 2026-09-07)
 * documents NO rate limit anywhere -- no `429` response is declared on any operation, no
 * "requests per second/minute" text appears anywhere in the document (verified by full-text
 * search). [MIN_GAP] is therefore a purely defensive, self-imposed value, carried over unchanged
 * from the lexoffice path's own (documented) 600ms figure as a conservative default. **Do not
 * treat this value as "sevDesk's documented limit" in any future edit -- it is not one.** If
 * sevDesk support ever provides a real figure, replace this constant then.
 */
internal class SevDeskRateLimiter(
    private val minGap: kotlin.time.Duration = MIN_GAP,
    private val timeSource: TimeSource.WithComparableMarks = TimeSource.Monotonic,
) {
    private val mutex = Mutex()
    private var lastRequestMark: ComparableTimeMark? = null

    /** Suspends until at least [minGap] has elapsed since the previous [acquire] call returned --
     * callers must call this immediately before every actual HTTP request to sevDesk. */
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
