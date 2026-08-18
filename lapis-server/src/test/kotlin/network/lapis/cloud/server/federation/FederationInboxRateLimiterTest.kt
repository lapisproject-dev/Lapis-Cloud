package network.lapis.cloud.server.federation

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes

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

        test(
            "M3: overflowing with many FRESH (non-expired) keys evicts a whole BATCH down to the " +
                "0.9 target load factor, not merely the one entry that pushed the map over capacity",
        ) {
            // A long window means none of these keys ever expire during the test -- this is exactly
            // the "distributed fresh-IP flood" scenario the M3 finding describes: the ONLY eviction
            // path reachable is the oldest-`windowStart` fallback, never the cheap expired-only one.
            val maxTrackedKeys = 100
            val limiter = FederationInboxRateLimiter(maxRequests = 1_000, window = 10.minutes, maxTrackedKeys = maxTrackedKeys)
            repeat(maxTrackedKeys) { i -> limiter.checkAndRecord("fresh-host-$i") }
            limiter.trackedKeyCountForTest() shouldBe maxTrackedKeys // exactly at capacity, no eviction triggered yet

            // The 101st DIFFERENT key pushes the map to 101 entries and triggers the fallback. The
            // OLD behaviour removed exactly the one entry over capacity (would leave 100 here); the
            // fix evicts a whole batch down to 0.9 * maxTrackedKeys = 90 instead, so this expensive
            // path is next paid only after ~10 more distinct keys arrive, not on every single call.
            limiter.checkAndRecord("fresh-host-100")
            limiter.trackedKeyCountForTest() shouldBe (maxTrackedKeys * 0.9).toInt()

            // Confirms the batch eviction is amortized: a handful of further distinct keys stays
            // comfortably under maxTrackedKeys without immediately re-triggering another sweep.
            repeat(5) { i -> limiter.checkAndRecord("post-eviction-host-$i") }
            (limiter.trackedKeyCountForTest() <= maxTrackedKeys) shouldBe true
        }
    })
