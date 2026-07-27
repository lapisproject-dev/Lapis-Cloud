package network.lapis.cloud.server.federation

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlin.time.Duration.Companion.milliseconds

/** Pure tests of [FederationInboxRateLimiter] -- no DB/network access. Mirrors [network.lapis.cloud.server.security.LoginRateLimiterTest]'s shape, adapted for a pure request-RATE limiter (every request counts, not just failures). */
class FederationInboxRateLimiterTest :
    FunSpec({
        test("checkAndRecord() is true for a never-seen host") {
            val limiter = FederationInboxRateLimiter()
            limiter.checkAndRecord("203.0.113.7") shouldBe true
        }

        test("the (N+1)th request within the window from one host is rejected") {
            val limiter = FederationInboxRateLimiter(maxRequests = 3)
            val host = "203.0.113.8"

            limiter.checkAndRecord(host) shouldBe true
            limiter.checkAndRecord(host) shouldBe true
            limiter.checkAndRecord(host) shouldBe true
            // 4th request within the window exceeds maxRequests=3 -- rejected.
            limiter.checkAndRecord(host) shouldBe false
        }

        test("different hosts are tracked independently -- flooding one host never blocks another") {
            val limiter = FederationInboxRateLimiter(maxRequests = 1)
            limiter.checkAndRecord("203.0.113.9") shouldBe true
            limiter.checkAndRecord("203.0.113.9") shouldBe false
            limiter.checkAndRecord("198.51.100.1") shouldBe true
        }

        test("the sliding window expires -- a host blocked inside the window is allowed again after it") {
            val limiter = FederationInboxRateLimiter(maxRequests = 1, window = 10.milliseconds)
            val host = "203.0.113.10"

            limiter.checkAndRecord(host) shouldBe true
            limiter.checkAndRecord(host) shouldBe false

            Thread.sleep(50)
            limiter.checkAndRecord(host) shouldBe true
        }

        test(
            "a successful (allowed) request still counts toward the limit -- unlike LoginRateLimiter, there is no separate recordFailure step",
        ) {
            val limiter = FederationInboxRateLimiter(maxRequests = 2)
            val host = "203.0.113.11"

            limiter.checkAndRecord(host) shouldBe true
            limiter.checkAndRecord(host) shouldBe true
            limiter.checkAndRecord(host) shouldBe false
        }

        test("opportunistic eviction bounds the tracked-key map size (best-effort, not exact)") {
            val limiter = FederationInboxRateLimiter(maxRequests = 100, window = 5.milliseconds, maxTrackedKeys = 5)
            repeat(20) { i -> limiter.checkAndRecord("host-$i") }
            Thread.sleep(20)
            // One more call triggers the eviction sweep -- must not throw, and the limiter must
            // stay usable afterward (the actual map size is an implementation detail we don't pin).
            limiter.checkAndRecord("host-final") shouldBe true
        }
    })
