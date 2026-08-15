package network.lapis.cloud.server.rpc

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.shared.domain.BreachDeadlineStatus

/**
 * Pure unit tests for [BreachDeadlineCalculator] -- no database needed. See that object's KDoc for
 * the "shows the clock, does not decide the law" scope this exercises.
 */
class BreachDeadlineCalculatorTest :
    FunSpec({
        val discoveredAt = LocalDateTime(2026, 1, 1, 12, 0, 0)
        val exactDeadline = LocalDateTime(2026, 1, 4, 12, 0, 0)

        test("deadline() is exactly discoveredAt + 72h") {
            BreachDeadlineCalculator.deadline(discoveredAt) shouldBe exactDeadline
        }

        test("status() is SATISFIED whenever authorityNotifiedAt is non-null, even if it is past the deadline") {
            val wayPastDeadline = LocalDateTime(2026, 2, 1, 0, 0, 0)
            BreachDeadlineCalculator.status(
                discoveredAt = discoveredAt,
                authorityNotifiedAt = wayPastDeadline,
                now = wayPastDeadline,
            ) shouldBe
                BreachDeadlineStatus.SATISFIED
            BreachDeadlineCalculator.status(discoveredAt = discoveredAt, authorityNotifiedAt = discoveredAt, now = discoveredAt) shouldBe
                BreachDeadlineStatus.SATISFIED
        }

        test("status() is WITHIN_WINDOW well before the deadline (more than DUE_SOON_THRESHOLD_HOURS remaining)") {
            val now = LocalDateTime(2026, 1, 2, 0, 0, 0) // ~60.5h remaining
            BreachDeadlineCalculator.status(discoveredAt = discoveredAt, authorityNotifiedAt = null, now = now) shouldBe
                BreachDeadlineStatus.WITHIN_WINDOW
        }

        test("status() is DUE_SOON within DUE_SOON_THRESHOLD_HOURS of the deadline") {
            // Exactly at the DUE_SOON boundary (deadline - threshold): boundary itself counts as DUE_SOON (<=).
            val atThreshold = LocalDateTime(2026, 1, 4, 0, 0, 0) // exactDeadline - 12h
            BreachDeadlineCalculator.status(discoveredAt = discoveredAt, authorityNotifiedAt = null, now = atThreshold) shouldBe
                BreachDeadlineStatus.DUE_SOON
            val justInsideWindow = LocalDateTime(2026, 1, 3, 23, 59, 59)
            BreachDeadlineCalculator.status(discoveredAt = discoveredAt, authorityNotifiedAt = null, now = justInsideWindow) shouldBe
                BreachDeadlineStatus.WITHIN_WINDOW
        }

        test("status() is OVERDUE once now is past the deadline and no notification was recorded") {
            val now = LocalDateTime(2026, 1, 5, 0, 0, 0)
            BreachDeadlineCalculator.status(discoveredAt = discoveredAt, authorityNotifiedAt = null, now = now) shouldBe
                BreachDeadlineStatus.OVERDUE
        }

        test("status() at the exact deadline instant is still WITHIN_WINDOW's DUE_SOON band, not yet OVERDUE") {
            BreachDeadlineCalculator.status(discoveredAt = discoveredAt, authorityNotifiedAt = null, now = exactDeadline) shouldBe
                BreachDeadlineStatus.DUE_SOON
        }

        test("AUTHORITY_NOTIFICATION_WINDOW_HOURS is the statutory Art. 33(1) 72h figure") {
            BreachDeadlineCalculator.AUTHORITY_NOTIFICATION_WINDOW_HOURS shouldBe 72L
        }
    })
