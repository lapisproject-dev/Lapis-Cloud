package network.lapis.cloud.server.carpool.pull

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import network.lapis.cloud.shared.domain.CarpoolPullErrorCode
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/** V1.9.314 W4 -- the schedule of the pull stays far below the origin's 12 requests per hour and per partner. */
class CarpoolPullPollerBackoffTest :
    FunSpec({
        fun delay(
            failures: Int,
            error: CarpoolPullErrorCode = CarpoolPullErrorCode.TIMEOUT,
            jitter: Duration = Duration.ZERO,
        ) = CarpoolPullPoller.failureDelay(failures = failures, error = error, jitter = jitter)

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
            val perHour = 3600.0 / CarpoolPullPoller.INTERVAL.inWholeSeconds
            (perHour <= 12) shouldBe true
            (CarpoolPullPoller.INTERVAL.inWholeMinutes >= 30) shouldBe true
        }
    })
