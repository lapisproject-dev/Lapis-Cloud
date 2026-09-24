package network.lapis.cloud.server.mcp.ratelimit

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * White-box helper for the eviction tests below -- [McpToolCallRateLimiter] exposes no accessor for
 * its private `perToken` map, and the only way to actually prove `evictIfOverCapacity` reclaimed an
 * entry (as opposed to merely "no exception was thrown", the weaker sibling-file pattern in
 * `AiQuestionRateLimiterTest`) is to read the map's size directly.
 */
private fun trackedTokenCount(limiter: McpToolCallRateLimiter): Int {
    val field = McpToolCallRateLimiter::class.java.getDeclaredField("perToken")
    field.isAccessible = true
    return (field.get(limiter) as Map<*, *>).size
}

/**
 * Pure unit coverage for [McpToolCallRateLimiter]'s three sliding-window tiers and the
 * `retryAfterSeconds` computation -- none of it was pinned anywhere before this test, see the
 * finding this test closes. Same injectable-clock style as [network.lapis.cloud.server.ai.ratelimit
 * .AiQuestionRateLimiterTest], which this class's own KDoc names as its sibling limiter.
 */
class McpToolCallRateLimiterTest :
    FunSpec({
        var now = Instant.parse("2026-09-19T10:00:00Z")
        val clock = { now }

        beforeTest { now = Instant.parse("2026-09-19T10:00:00Z") }

        test("allows up to perTokenPerMinute calls for one token, then rejects the next one as TokenLimited") {
            val limiter = McpToolCallRateLimiter(perTokenPerMinute = 2, perMemberPerHour = 1_000, perServerPerDay = 1_000, now = clock)
            val member = Uuid.random()
            val token = Uuid.random()
            limiter.checkAndRecord(memberId = member, tokenId = token) shouldBe McpRateLimitDecision.Allowed
            limiter.checkAndRecord(memberId = member, tokenId = token) shouldBe McpRateLimitDecision.Allowed
            limiter.checkAndRecord(memberId = member, tokenId = token).shouldBeInstanceOf<McpRateLimitDecision.TokenLimited>()
        }

        test("the token window is per-token -- a second token for the SAME member is unaffected by the first token's limit") {
            val limiter = McpToolCallRateLimiter(perTokenPerMinute = 1, perMemberPerHour = 1_000, perServerPerDay = 1_000, now = clock)
            val member = Uuid.random()
            val tokenA = Uuid.random()
            val tokenB = Uuid.random()
            limiter.checkAndRecord(memberId = member, tokenId = tokenA) shouldBe McpRateLimitDecision.Allowed
            limiter.checkAndRecord(memberId = member, tokenId = tokenA).shouldBeInstanceOf<McpRateLimitDecision.TokenLimited>()
            limiter.checkAndRecord(memberId = member, tokenId = tokenB) shouldBe McpRateLimitDecision.Allowed
        }

        test("the member window catches a member with two different tokens, even though neither token alone is over its own limit") {
            val limiter = McpToolCallRateLimiter(perTokenPerMinute = 1_000, perMemberPerHour = 2, perServerPerDay = 1_000, now = clock)
            val member = Uuid.random()
            val tokenA = Uuid.random()
            val tokenB = Uuid.random()
            limiter.checkAndRecord(memberId = member, tokenId = tokenA) shouldBe McpRateLimitDecision.Allowed
            limiter.checkAndRecord(memberId = member, tokenId = tokenB) shouldBe McpRateLimitDecision.Allowed
            limiter.checkAndRecord(memberId = member, tokenId = tokenA).shouldBeInstanceOf<McpRateLimitDecision.MemberLimited>()
        }

        test("the server window catches two different members once the server-wide ceiling is exhausted") {
            val limiter = McpToolCallRateLimiter(perTokenPerMinute = 1_000, perMemberPerHour = 1_000, perServerPerDay = 2, now = clock)
            limiter.checkAndRecord(memberId = Uuid.random(), tokenId = Uuid.random()) shouldBe McpRateLimitDecision.Allowed
            limiter.checkAndRecord(memberId = Uuid.random(), tokenId = Uuid.random()) shouldBe McpRateLimitDecision.Allowed
            limiter
                .checkAndRecord(memberId = Uuid.random(), tokenId = Uuid.random())
                .shouldBeInstanceOf<McpRateLimitDecision.ServerLimited>()
        }

        test("the token tier is checked (and wins) before the member/server tiers") {
            val limiter = McpToolCallRateLimiter(perTokenPerMinute = 1, perMemberPerHour = 1_000, perServerPerDay = 1_000, now = clock)
            val member = Uuid.random()
            val token = Uuid.random()
            limiter.checkAndRecord(memberId = member, tokenId = token) shouldBe McpRateLimitDecision.Allowed
            limiter.checkAndRecord(memberId = member, tokenId = token).shouldBeInstanceOf<McpRateLimitDecision.TokenLimited>()
        }

        test("the token window slides -- once a full minute has passed, the same token is allowed again") {
            val limiter = McpToolCallRateLimiter(perTokenPerMinute = 1, perMemberPerHour = 1_000, perServerPerDay = 1_000, now = clock)
            val member = Uuid.random()
            val token = Uuid.random()
            limiter.checkAndRecord(memberId = member, tokenId = token) shouldBe McpRateLimitDecision.Allowed
            limiter.checkAndRecord(memberId = member, tokenId = token).shouldBeInstanceOf<McpRateLimitDecision.TokenLimited>()
            now += 61.seconds
            limiter.checkAndRecord(memberId = member, tokenId = token) shouldBe McpRateLimitDecision.Allowed
        }

        test("retryAfterSeconds is a positive, roughly-correct estimate of when the oldest call in the window ages out") {
            val limiter = McpToolCallRateLimiter(perTokenPerMinute = 1, perMemberPerHour = 1_000, perServerPerDay = 1_000, now = clock)
            val member = Uuid.random()
            val token = Uuid.random()
            limiter.checkAndRecord(memberId = member, tokenId = token) shouldBe McpRateLimitDecision.Allowed
            now += 10.seconds
            val decision = limiter.checkAndRecord(memberId = member, tokenId = token)
            decision.shouldBeInstanceOf<McpRateLimitDecision.TokenLimited>()
            // Called 10s into a 60s window -- roughly 50s remain (allow a couple of seconds of slack).
            (decision.retryAfterSeconds in 48..51) shouldBe true
        }

        test("a member-hour window uses the 1-hour bound, independent of the token-minute bound") {
            val limiter = McpToolCallRateLimiter(perTokenPerMinute = 1_000, perMemberPerHour = 1, perServerPerDay = 1_000, now = clock)
            val member = Uuid.random()
            limiter.checkAndRecord(memberId = member, tokenId = Uuid.random()) shouldBe McpRateLimitDecision.Allowed
            now += 30.minutes
            val decision = limiter.checkAndRecord(memberId = member, tokenId = Uuid.random())
            decision.shouldBeInstanceOf<McpRateLimitDecision.MemberLimited>()
            (decision.retryAfterSeconds in (29 * 60)..(31 * 60)) shouldBe true
        }

        test("after a full hour the member window slides too") {
            val limiter = McpToolCallRateLimiter(perTokenPerMinute = 1_000, perMemberPerHour = 1, perServerPerDay = 1_000, now = clock)
            val member = Uuid.random()
            limiter.checkAndRecord(memberId = member, tokenId = Uuid.random()) shouldBe McpRateLimitDecision.Allowed
            limiter.checkAndRecord(memberId = member, tokenId = Uuid.random()).shouldBeInstanceOf<McpRateLimitDecision.MemberLimited>()
            now += 61.minutes
            limiter.checkAndRecord(memberId = member, tokenId = Uuid.random()) shouldBe McpRateLimitDecision.Allowed
        }

        test(
            "evictIfOverCapacity reclaims a token whose window has fully expired once maxTrackedKeys is exceeded (Allowed path)",
        ) {
            val limiter =
                McpToolCallRateLimiter(
                    perTokenPerMinute = 1_000,
                    perMemberPerHour = 1_000,
                    perServerPerDay = 1_000,
                    maxTrackedKeys = 2,
                    now = clock,
                )

            val tokenA = Uuid.random()
            limiter.checkAndRecord(memberId = Uuid.random(), tokenId = tokenA) shouldBe McpRateLimitDecision.Allowed
            now += 61.seconds // tokenA's 1-minute window is now fully expired, but nothing has re-pruned it yet
            val tokenB = Uuid.random()
            limiter.checkAndRecord(memberId = Uuid.random(), tokenId = tokenB) shouldBe McpRateLimitDecision.Allowed
            trackedTokenCount(limiter) shouldBe 2 // exactly at maxTrackedKeys(2) -- eviction not triggered yet

            now += 1.seconds
            val tokenC = Uuid.random()
            limiter.checkAndRecord(memberId = Uuid.random(), tokenId = tokenC) shouldBe McpRateLimitDecision.Allowed
            // perToken.size was 3 > maxTrackedKeys(2) here -- eviction ran and reclaimed tokenA's now-empty
            // window, while tokenB/tokenC (still within their 1-minute window) were kept.
            trackedTokenCount(limiter) shouldBe 2
        }

        test(
            "evictIfOverCapacity also runs on a rejected (ServerLimited) call -- the tracked-token map must not " +
                "grow unbounded while the server stays permanently at its daily ceiling",
        ) {
            val limiter =
                McpToolCallRateLimiter(
                    perTokenPerMinute = 1_000,
                    perMemberPerHour = 1_000,
                    perServerPerDay = 1,
                    maxTrackedKeys = 2,
                    now = clock,
                )

            limiter.checkAndRecord(memberId = Uuid.random(), tokenId = Uuid.random()) shouldBe McpRateLimitDecision.Allowed
            now += 61.seconds // that one allowed call's token window is now fully expired

            repeat(10) {
                val decision = limiter.checkAndRecord(memberId = Uuid.random(), tokenId = Uuid.random())
                decision.shouldBeInstanceOf<McpRateLimitDecision.ServerLimited>()
                now += 1.seconds
            }

            // Every one of the 10 rejected calls still ran perToken.getOrPut for its OWN distinct token before
            // being turned away by the server-wide ceiling -- if eviction only ran on the Allowed branch, all 11
            // tokens (1 allowed + 10 rejected) would still be tracked here, five times past maxTrackedKeys(2).
            // Eviction now runs unconditionally, so only entries with a still-live (unexpired) window survive.
            (trackedTokenCount(limiter) <= 2) shouldBe true
        }
    })
