package network.lapis.cloud.server.rpc

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.datetime.LocalDateTime
import java.math.BigDecimal

/**
 * Pure unit tests for [CrowdfundingWeightDecay] -- no database needed. See that object's KDoc for
 * the "current understanding, verify" scope of its two constants.
 */
class CrowdfundingWeightDecayTest :
    FunSpec({
        val submittedAt = LocalDateTime(2026, 1, 1, 12, 0, 0)
        val initial = BigDecimal("100.00")

        test("currentWeight at exactly submittedAt (0 days elapsed) equals the initial weight exactly") {
            CrowdfundingWeightDecay
                .currentWeight(
                    initialWeightLtr = initial,
                    submittedAt = submittedAt,
                    now = submittedAt,
                ).compareTo(initial) shouldBe
                0
        }

        test("currentWeight after exactly 1 day equals initial * 0.9") {
            val oneDayLater = LocalDateTime(2026, 1, 2, 12, 0, 0)
            val expected = BigDecimal("90.00")
            CrowdfundingWeightDecay
                .currentWeight(
                    initialWeightLtr = initial,
                    submittedAt = submittedAt,
                    now = oneDayLater,
                ).compareTo(expected) shouldBe
                0
        }

        test("currentWeight after exactly 2 days equals initial * 0.9^2") {
            val twoDaysLater = LocalDateTime(2026, 1, 3, 12, 0, 0)
            val expected = BigDecimal("81.00")
            CrowdfundingWeightDecay
                .currentWeight(
                    initialWeightLtr = initial,
                    submittedAt = submittedAt,
                    now = twoDaysLater,
                ).compareTo(expected) shouldBe
                0
        }

        test("currentWeight is strictly monotonically decreasing as elapsed days grow") {
            val samples = (0..30).map { days -> LocalDateTime(2026, 1, 1 + days, 12, 0, 0) }
            val weights =
                samples.map {
                    CrowdfundingWeightDecay.currentWeight(
                        initialWeightLtr = initial,
                        submittedAt = submittedAt,
                        now = it,
                    )
                }
            weights.zipWithNext().all { (a, b) -> a > b } shouldBe true
        }

        test("currentWeight for a very old project (1000 days) is a tiny but valid, non-throwing, non-negative value") {
            val farFuture = LocalDateTime(2028, 9, 27, 12, 0, 0) // ~1000 days after submittedAt
            val weight = CrowdfundingWeightDecay.currentWeight(initialWeightLtr = initial, submittedAt = submittedAt, now = farFuture)
            (weight.signum() >= 0) shouldBe true
            (weight < BigDecimal("0.01")) shouldBe true
        }

        test("daysElapsed is a whole, 24h-rolling count, never negative even if now is before submittedAt") {
            CrowdfundingWeightDecay.daysElapsed(from = submittedAt, now = submittedAt) shouldBe 0L
            CrowdfundingWeightDecay.daysElapsed(from = submittedAt, now = LocalDateTime(2026, 1, 1, 11, 59, 59)) shouldBe 0L
            CrowdfundingWeightDecay.daysElapsed(from = submittedAt, now = LocalDateTime(2026, 1, 2, 12, 0, 0)) shouldBe 1L
            CrowdfundingWeightDecay.daysElapsed(from = submittedAt, now = LocalDateTime(2025, 12, 31, 0, 0, 0)) shouldBe 0L
        }

        test("isAutoApproved boundary: 13 days 23:59:59 elapsed is false, exactly 14 days 00:00:00 elapsed is true") {
            val justBefore = LocalDateTime(2026, 1, 15, 11, 59, 59)
            val exactlyAtBoundary = LocalDateTime(2026, 1, 15, 12, 0, 0)
            CrowdfundingWeightDecay.isAutoApproved(submittedAt = submittedAt, now = justBefore) shouldBe false
            CrowdfundingWeightDecay.isAutoApproved(submittedAt = submittedAt, now = exactlyAtBoundary) shouldBe true
        }

        test("isAutoApproved stays true well past the boundary") {
            val wayLater = LocalDateTime(2026, 6, 1, 0, 0, 0)
            CrowdfundingWeightDecay.isAutoApproved(submittedAt = submittedAt, now = wayLater) shouldBe true
        }

        test("isAutoApproved is false immediately at submission") {
            CrowdfundingWeightDecay.isAutoApproved(submittedAt = submittedAt, now = submittedAt) shouldBe false
        }

        test("DECAY_KEEP_RATE_PER_DAY is the current-understanding 10%/day decay (0.9 kept)") {
            CrowdfundingWeightDecay.DECAY_KEEP_RATE_PER_DAY.compareTo(BigDecimal("0.9")) shouldBe 0
        }

        test("BOARD_REVIEW_WINDOW_DAYS is the current-understanding 14-day silence-is-approval window") {
            CrowdfundingWeightDecay.BOARD_REVIEW_WINDOW_DAYS shouldBe 14L
        }
    })
