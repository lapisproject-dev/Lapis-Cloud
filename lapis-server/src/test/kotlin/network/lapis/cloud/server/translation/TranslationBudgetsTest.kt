package network.lapis.cloud.server.translation

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

class TranslationBudgetsTest :
    FunSpec({
        test("the daily budget reserves, refuses beyond the limit and frees after 24 hours") {
            var now = Instant.parse("2031-01-01T00:00:00Z")
            val budget = TranslationDailyBudget(dailyChars = 100) { now }
            budget.tryReserve(60) shouldBe true
            budget.remaining() shouldBe 40
            budget.tryReserve(50) shouldBe false
            budget.tryReserve(40) shouldBe true
            budget.remaining() shouldBe 0
            now += 23.hours
            budget.tryReserve(1) shouldBe false
            now += 2.hours
            budget.remaining() shouldBe 100
            budget.tryReserve(100) shouldBe true
        }

        test("a negative reservation is refused") {
            TranslationDailyBudget(dailyChars = 100).tryReserve(-1) shouldBe false
        }

        test("the IP limiter allows N per minute, then refuses, then recovers") {
            var now = Instant.parse("2031-01-01T00:00:00Z")
            val limiter = TranslationIpRateLimiter(perMinute = 3) { now }
            repeat(3) { limiter.checkAndRecord("1.2.3.4") shouldBe true }
            limiter.checkAndRecord("1.2.3.4") shouldBe false
            limiter.checkAndRecord("5.6.7.8") shouldBe true
            now += 61.seconds
            limiter.checkAndRecord("1.2.3.4") shouldBe true
        }

        test("the IP limiter stays bounded under a flood of distinct keys") {
            val limiter = TranslationIpRateLimiter(perMinute = 3, maxTrackedKeys = 50)
            repeat(500) { limiter.checkAndRecord("ip-$it") shouldBe true }
        }
    })
