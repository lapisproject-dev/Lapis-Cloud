package network.lapis.cloud.server.auction.federation.pull

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import network.lapis.cloud.shared.domain.CarpoolPullErrorCode
import network.lapis.cloud.shared.domain.CarpoolPullState
import network.lapis.cloud.shared.domain.CarpoolPullStates
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/** V1.9.322 c3 -- the schedule of the pull stays far below the origin's 12 requests per hour and per partner. */
class AuctionPullPollerBackoffTest :
    FunSpec({
        fun delay(
            failures: Int,
            error: CarpoolPullErrorCode = CarpoolPullErrorCode.TIMEOUT,
            jitter: Duration = Duration.ZERO,
        ) = AuctionPullPoller.failureDelay(failures = failures, error = error, jitter = jitter)

        test("failures back off 15 minutes, 30, 60 ... and stop at 24 hours") {
            delay(1) shouldBe 15.minutes
            delay(2) shouldBe 30.minutes
            delay(3) shouldBe 1.hours
            delay(4) shouldBe 2.hours
            delay(7) shouldBe 16.hours
            delay(8) shouldBe 24.hours
            delay(500) shouldBe 24.hours
            delay(0) shouldBe 15.minutes
        }

        test("a rate limit waits at least an hour; the jitter is added on top") {
            delay(1, CarpoolPullErrorCode.RATE_LIMITED) shouldBe 1.hours
            delay(5, CarpoolPullErrorCode.RATE_LIMITED) shouldBe 4.hours
            delay(1, jitter = 90.seconds) shouldBe 15.minutes + 90.seconds
        }

        test("the regular interval stays under the origin's per-partner budget even with the shortest jitter") {
            val perHour = 3600.0 / AuctionPullPoller.INTERVAL.inWholeSeconds
            (perHour <= 12) shouldBe true
            (AuctionPullPoller.INTERVAL.inWholeMinutes >= 30) shouldBe true
        }

        test("the delay of the first ten failures, up to the 24 hour cap, and the rate-limit floor on top of each") {
            (1..10).map { delay(it) } shouldBe
                listOf(15.minutes, 30.minutes, 1.hours, 2.hours, 4.hours, 8.hours, 16.hours, 24.hours, 24.hours, 24.hours)
            (1..10).map { delay(it, CarpoolPullErrorCode.RATE_LIMITED) } shouldBe
                listOf(1.hours, 1.hours, 1.hours, 2.hours, 4.hours, 8.hours, 16.hours, 24.hours, 24.hours, 24.hours)
            delay(3, jitter = 10.minutes) shouldBe 1.hours + 10.minutes
        }

        test(
            "the state follows the failure counter: ready, backing off, paused exactly where the delay reaches the 24 hour cap",
        ) {
            listOf(-1, 0).forEach { CarpoolPullStates.of(it) shouldBe CarpoolPullState.READY }
            (1..7).forEach { CarpoolPullStates.of(it) shouldBe CarpoolPullState.BACKING_OFF }
            listOf(8, 9, 100).forEach { CarpoolPullStates.of(it) shouldBe CarpoolPullState.PAUSED }
            // the boundary is no coincidence: failure 8 is the first delay at the cap
            delay(CarpoolPullStates.PAUSE_AFTER_FAILURES) shouldBe AuctionPullPoller.FAILURE_MAX
            (delay(CarpoolPullStates.PAUSE_AFTER_FAILURES - 1) < AuctionPullPoller.FAILURE_MAX) shouldBe true
        }

        test("the budget and the sweep size are positive and the budget covers at least five full answers") {
            (AuctionPullPoller.TICK_ITEM_BUDGET >= 5 * 1000) shouldBe true
            (AuctionPullPoller.EXPIRED_SWEEP_LIMIT > 0) shouldBe true
        }
    })
