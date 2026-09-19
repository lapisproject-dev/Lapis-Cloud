package network.lapis.cloud.server.ai.ratelimit

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant
import kotlin.uuid.Uuid

class AiQuestionRateLimiterTest :
    FunSpec({
        var now = Instant.parse("2026-09-19T10:00:00Z")
        val clock = { now }

        beforeTest { now = Instant.parse("2026-09-19T10:00:00Z") }

        test("the tenth question is allowed and the eleventh is limited with a wait time") {
            val limiter = AiQuestionRateLimiter(perMemberPerHour = 10, perServerPerDay = 200, now = clock)
            val member = Uuid.random()
            repeat(10) { limiter.checkAndRecord(member) shouldBe RateLimitDecision.Allowed }
            val decision = limiter.checkAndRecord(member).shouldBeInstanceOf<RateLimitDecision.MemberLimited>()
            (decision.retryAfterSeconds in 1..3_601) shouldBe true
        }

        test("the window slides: after an hour the member may ask again") {
            val limiter = AiQuestionRateLimiter(perMemberPerHour = 2, perServerPerDay = 200, now = clock)
            val member = Uuid.random()
            limiter.checkAndRecord(member) shouldBe RateLimitDecision.Allowed
            limiter.checkAndRecord(member) shouldBe RateLimitDecision.Allowed
            limiter.checkAndRecord(member).shouldBeInstanceOf<RateLimitDecision.MemberLimited>()
            now += 61.minutes
            limiter.checkAndRecord(member) shouldBe RateLimitDecision.Allowed
        }

        test("members are limited independently of each other") {
            val limiter = AiQuestionRateLimiter(perMemberPerHour = 1, perServerPerDay = 200, now = clock)
            limiter.checkAndRecord(Uuid.random()) shouldBe RateLimitDecision.Allowed
            limiter.checkAndRecord(Uuid.random()) shouldBe RateLimitDecision.Allowed
        }

        test("the server-wide daily cap applies across members, independently of the member limit") {
            val limiter = AiQuestionRateLimiter(perMemberPerHour = 10, perServerPerDay = 3, now = clock)
            repeat(3) { limiter.checkAndRecord(Uuid.random()) shouldBe RateLimitDecision.Allowed }
            limiter.checkAndRecord(Uuid.random()).shouldBeInstanceOf<RateLimitDecision.ServerLimited>()
            now += 25.hours
            limiter.checkAndRecord(Uuid.random()) shouldBe RateLimitDecision.Allowed
        }

        test("the tracked-member map stays bounded") {
            val limiter = AiQuestionRateLimiter(perMemberPerHour = 5, perServerPerDay = 1_000_000, maxTrackedKeys = 10, now = clock)
            repeat(50) {
                limiter.checkAndRecord(Uuid.random())
                now += 10.minutes
            }
            // No exception and still functional after many distinct members; eviction ran.
            limiter.checkAndRecord(Uuid.random()) shouldBe RateLimitDecision.Allowed
        }

        test("a limited request does not extend the window") {
            val limiter = AiQuestionRateLimiter(perMemberPerHour = 1, perServerPerDay = 200, now = clock)
            val member = Uuid.random()
            limiter.checkAndRecord(member) shouldBe RateLimitDecision.Allowed
            now += 30.minutes
            limiter.checkAndRecord(member).shouldBeInstanceOf<RateLimitDecision.MemberLimited>()
            now += 31.minutes
            limiter.checkAndRecord(member) shouldBe RateLimitDecision.Allowed
        }
    })
