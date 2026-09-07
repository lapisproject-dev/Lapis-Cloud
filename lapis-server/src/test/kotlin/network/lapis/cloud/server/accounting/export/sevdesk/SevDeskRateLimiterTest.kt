package network.lapis.cloud.server.accounting.export.sevdesk

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeSource

/** Welle V1.4.5.4 "sevDesk-Live-Anbindung" -- same timing-test shape
 * [network.lapis.cloud.server.accounting.export.lexoffice.LexofficeApiClientTest]'s own
 * `LexofficeRateLimiter` test establishes. */
class SevDeskRateLimiterTest :
    FunSpec({
        test("SevDeskRateLimiter: five consecutive acquire() calls take at least 4 * minGap of real wall-clock time") {
            // Deliberately kotlinx.coroutines.runBlocking -- SevDeskRateLimiter measures elapsed time
            // via TimeSource.Monotonic (real wall-clock nanoTime), so this needs an actual
            // suspension/measurement.
            runBlocking {
                val minGap = 30.milliseconds
                val limiter = SevDeskRateLimiter(minGap = minGap)
                val start = TimeSource.Monotonic.markNow()
                repeat(5) { limiter.acquire() }
                val elapsed = start.elapsedNow()
                (elapsed >= minGap * 4) shouldBe true
            }
        }
    })
