package network.lapis.cloud.server.mcp.ratelimit

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlin.time.Duration.Companion.hours
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
 *
 * **Welle V1.8.2 wave 2 addition**: the FOURTH, per-write-tool [McpToolCallRateLimiter.writeQuotas]
 * tier this wave added (see that class's own "Welle V1.8.2 amendment" KDoc) had zero coverage --
 * this file was not touched when it shipped. The tests below pin: a write tool's own token/member/
 * server sub-limits reject independently of the three global windows; an unmatched/`null` `toolName`
 * (a read tool, or an unknown name) skips the fourth tier entirely; and two DIFFERENT write tools'
 * quotas never leak into each other.
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

        test(
            "writeQuotas: a write tool's own per-token/hour quota rejects TokenLimited once exhausted, even though the global token/minute window is nowhere near its own limit",
        ) {
            val limiter =
                McpToolCallRateLimiter(
                    perTokenPerMinute = 1_000,
                    perMemberPerHour = 1_000,
                    perServerPerDay = 1_000,
                    writeQuotas =
                        mapOf(
                            "register_for_event" to
                                McpToolCallRateLimiter.WriteQuota(perTokenPerHour = 2, perMemberPerDay = 1_000, perServerPerDay = 1_000),
                        ),
                    now = clock,
                )
            val member = Uuid.random()
            val token = Uuid.random()
            limiter.checkAndRecord(memberId = member, tokenId = token, toolName = "register_for_event") shouldBe
                McpRateLimitDecision.Allowed
            limiter.checkAndRecord(memberId = member, tokenId = token, toolName = "register_for_event") shouldBe
                McpRateLimitDecision.Allowed
            limiter
                .checkAndRecord(memberId = member, tokenId = token, toolName = "register_for_event")
                .shouldBeInstanceOf<McpRateLimitDecision.TokenLimited>()
        }

        test("writeQuotas: the per-member/day quota catches a member calling the SAME write tool with two different tokens") {
            val limiter =
                McpToolCallRateLimiter(
                    perTokenPerMinute = 1_000,
                    perMemberPerHour = 1_000,
                    perServerPerDay = 1_000,
                    writeQuotas =
                        mapOf(
                            "create_post_draft" to
                                McpToolCallRateLimiter.WriteQuota(perTokenPerHour = 1_000, perMemberPerDay = 2, perServerPerDay = 1_000),
                        ),
                    now = clock,
                )
            val member = Uuid.random()
            limiter.checkAndRecord(memberId = member, tokenId = Uuid.random(), toolName = "create_post_draft") shouldBe
                McpRateLimitDecision.Allowed
            limiter.checkAndRecord(memberId = member, tokenId = Uuid.random(), toolName = "create_post_draft") shouldBe
                McpRateLimitDecision.Allowed
            limiter
                .checkAndRecord(memberId = member, tokenId = Uuid.random(), toolName = "create_post_draft")
                .shouldBeInstanceOf<McpRateLimitDecision.MemberLimited>()
        }

        test("writeQuotas: the per-server/day quota catches two different members calling the same write tool") {
            val limiter =
                McpToolCallRateLimiter(
                    perTokenPerMinute = 1_000,
                    perMemberPerHour = 1_000,
                    perServerPerDay = 1_000,
                    writeQuotas =
                        mapOf(
                            "register_for_event" to
                                McpToolCallRateLimiter.WriteQuota(perTokenPerHour = 1_000, perMemberPerDay = 1_000, perServerPerDay = 2),
                        ),
                    now = clock,
                )
            limiter.checkAndRecord(memberId = Uuid.random(), tokenId = Uuid.random(), toolName = "register_for_event") shouldBe
                McpRateLimitDecision.Allowed
            limiter.checkAndRecord(memberId = Uuid.random(), tokenId = Uuid.random(), toolName = "register_for_event") shouldBe
                McpRateLimitDecision.Allowed
            limiter
                .checkAndRecord(memberId = Uuid.random(), tokenId = Uuid.random(), toolName = "register_for_event")
                .shouldBeInstanceOf<McpRateLimitDecision.ServerLimited>()
        }

        test(
            "writeQuotas: a read tool (toolName not in writeQuotas) skips the fourth tier entirely -- only the three global windows apply",
        ) {
            val limiter =
                McpToolCallRateLimiter(
                    perTokenPerMinute = 1_000,
                    perMemberPerHour = 1_000,
                    perServerPerDay = 1_000,
                    writeQuotas =
                        mapOf(
                            "register_for_event" to
                                McpToolCallRateLimiter.WriteQuota(perTokenPerHour = 1, perMemberPerDay = 1, perServerPerDay = 1),
                        ),
                    now = clock,
                )
            val member = Uuid.random()
            val token = Uuid.random()
            // "list_upcoming_events" is a read tool -- not a key in writeQuotas -- so it must never
            // be rejected by the register_for_event quota above, however many times it is called.
            repeat(5) {
                limiter.checkAndRecord(memberId = member, tokenId = token, toolName = "list_upcoming_events") shouldBe
                    McpRateLimitDecision.Allowed
            }
            // A `null` toolName (McpToolDispatcher's own default for an unknown tool name) behaves
            // identically.
            limiter.checkAndRecord(memberId = member, tokenId = token, toolName = null) shouldBe McpRateLimitDecision.Allowed
        }

        test("writeQuotas: two DIFFERENT write tools' quotas are independent -- exhausting one never affects the other") {
            val limiter =
                McpToolCallRateLimiter(
                    perTokenPerMinute = 1_000,
                    perMemberPerHour = 1_000,
                    perServerPerDay = 1_000,
                    writeQuotas =
                        mapOf(
                            "register_for_event" to
                                McpToolCallRateLimiter.WriteQuota(perTokenPerHour = 1, perMemberPerDay = 1_000, perServerPerDay = 1_000),
                            "create_post_draft" to
                                McpToolCallRateLimiter.WriteQuota(
                                    perTokenPerHour = 1_000,
                                    perMemberPerDay = 1_000,
                                    perServerPerDay = 1_000,
                                ),
                        ),
                    now = clock,
                )
            val member = Uuid.random()
            val token = Uuid.random()
            limiter.checkAndRecord(memberId = member, tokenId = token, toolName = "register_for_event") shouldBe
                McpRateLimitDecision.Allowed
            limiter
                .checkAndRecord(memberId = member, tokenId = token, toolName = "register_for_event")
                .shouldBeInstanceOf<McpRateLimitDecision.TokenLimited>()
            // The SAME token, now calling the OTHER write tool -- unaffected by register_for_event's exhausted quota.
            limiter.checkAndRecord(memberId = member, tokenId = token, toolName = "create_post_draft") shouldBe
                McpRateLimitDecision.Allowed
        }

        test("writeQuotas: the write-tool token window (1 hour) slides independently of the global token window (1 minute)") {
            val limiter =
                McpToolCallRateLimiter(
                    perTokenPerMinute = 1_000,
                    perMemberPerHour = 1_000,
                    perServerPerDay = 1_000,
                    writeQuotas =
                        mapOf(
                            "register_for_event" to
                                McpToolCallRateLimiter.WriteQuota(perTokenPerHour = 1, perMemberPerDay = 1_000, perServerPerDay = 1_000),
                        ),
                    now = clock,
                )
            val member = Uuid.random()
            val token = Uuid.random()
            limiter.checkAndRecord(memberId = member, tokenId = token, toolName = "register_for_event") shouldBe
                McpRateLimitDecision.Allowed
            limiter
                .checkAndRecord(memberId = member, tokenId = token, toolName = "register_for_event")
                .shouldBeInstanceOf<McpRateLimitDecision.TokenLimited>()
            now += 61.minutes
            limiter.checkAndRecord(memberId = member, tokenId = token, toolName = "register_for_event") shouldBe
                McpRateLimitDecision.Allowed
        }

        test("writeQuotas: the write-tool member window (1 day) requires a full day to slide, unlike the global member/hour window") {
            val limiter =
                McpToolCallRateLimiter(
                    perTokenPerMinute = 1_000,
                    perMemberPerHour = 1_000,
                    perServerPerDay = 1_000,
                    writeQuotas =
                        mapOf(
                            "create_post_draft" to
                                McpToolCallRateLimiter.WriteQuota(perTokenPerHour = 1_000, perMemberPerDay = 1, perServerPerDay = 1_000),
                        ),
                    now = clock,
                )
            val member = Uuid.random()
            limiter.checkAndRecord(memberId = member, tokenId = Uuid.random(), toolName = "create_post_draft") shouldBe
                McpRateLimitDecision.Allowed
            now += 23.hours
            limiter
                .checkAndRecord(memberId = member, tokenId = Uuid.random(), toolName = "create_post_draft")
                .shouldBeInstanceOf<McpRateLimitDecision.MemberLimited>()
            now += 2.hours // now 25h after the first call -- past the 1-day window
            limiter.checkAndRecord(memberId = member, tokenId = Uuid.random(), toolName = "create_post_draft") shouldBe
                McpRateLimitDecision.Allowed
        }

        test(
            "writeQuotas: the three global windows are still checked FIRST -- a global rejection wins even when the write quota alone would have allowed the call",
        ) {
            val limiter =
                McpToolCallRateLimiter(
                    perTokenPerMinute = 1,
                    perMemberPerHour = 1_000,
                    perServerPerDay = 1_000,
                    writeQuotas =
                        mapOf(
                            "register_for_event" to
                                McpToolCallRateLimiter.WriteQuota(
                                    perTokenPerHour = 1_000,
                                    perMemberPerDay = 1_000,
                                    perServerPerDay = 1_000,
                                ),
                        ),
                    now = clock,
                )
            val member = Uuid.random()
            val token = Uuid.random()
            limiter.checkAndRecord(memberId = member, tokenId = token, toolName = "register_for_event") shouldBe
                McpRateLimitDecision.Allowed
            // The write quota (1_000/hour) is nowhere near exhausted, but the global token/minute
            // window (1) already is.
            limiter
                .checkAndRecord(memberId = member, tokenId = token, toolName = "register_for_event")
                .shouldBeInstanceOf<McpRateLimitDecision.TokenLimited>()
        }
    })
