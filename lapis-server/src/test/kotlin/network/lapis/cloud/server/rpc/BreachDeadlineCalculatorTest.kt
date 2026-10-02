package network.lapis.cloud.server.rpc

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
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
            BreachDeadlineCalculator.deadline(discoveredAt = discoveredAt, orgZone = TimeZone.UTC) shouldBe exactDeadline
        }

        test("status() is SATISFIED whenever authorityNotifiedAt is non-null, even if it is past the deadline") {
            val wayPastDeadline = LocalDateTime(2026, 2, 1, 0, 0, 0)
            BreachDeadlineCalculator.status(
                discoveredAt = discoveredAt,
                authorityNotifiedAt = wayPastDeadline,
                now = wayPastDeadline,
                orgZone = TimeZone.UTC,
            ) shouldBe
                BreachDeadlineStatus.SATISFIED
            BreachDeadlineCalculator.status(
                discoveredAt = discoveredAt,
                authorityNotifiedAt = discoveredAt,
                now = discoveredAt,
                orgZone = TimeZone.UTC,
            ) shouldBe
                BreachDeadlineStatus.SATISFIED
        }

        test("status() is WITHIN_WINDOW well before the deadline (more than DUE_SOON_THRESHOLD_HOURS remaining)") {
            val now = LocalDateTime(2026, 1, 2, 0, 0, 0) // ~60.5h remaining
            BreachDeadlineCalculator.status(
                discoveredAt = discoveredAt,
                authorityNotifiedAt = null,
                now = now,
                orgZone = TimeZone.UTC,
            ) shouldBe
                BreachDeadlineStatus.WITHIN_WINDOW
        }

        test("status() is DUE_SOON within DUE_SOON_THRESHOLD_HOURS of the deadline") {
            // Exactly at the DUE_SOON boundary (deadline - threshold): boundary itself counts as DUE_SOON (<=).
            val atThreshold = LocalDateTime(2026, 1, 4, 0, 0, 0) // exactDeadline - 12h
            BreachDeadlineCalculator.status(
                discoveredAt = discoveredAt,
                authorityNotifiedAt = null,
                now = atThreshold,
                orgZone = TimeZone.UTC,
            ) shouldBe
                BreachDeadlineStatus.DUE_SOON
            val justInsideWindow = LocalDateTime(2026, 1, 3, 23, 59, 59)
            BreachDeadlineCalculator.status(
                discoveredAt = discoveredAt,
                authorityNotifiedAt = null,
                now = justInsideWindow,
                orgZone = TimeZone.UTC,
            ) shouldBe
                BreachDeadlineStatus.WITHIN_WINDOW
        }

        test("status() is OVERDUE once now is past the deadline and no notification was recorded") {
            val now = LocalDateTime(2026, 1, 5, 0, 0, 0)
            BreachDeadlineCalculator.status(
                discoveredAt = discoveredAt,
                authorityNotifiedAt = null,
                now = now,
                orgZone = TimeZone.UTC,
            ) shouldBe
                BreachDeadlineStatus.OVERDUE
        }

        test("status() at the exact deadline instant is still WITHIN_WINDOW's DUE_SOON band, not yet OVERDUE") {
            BreachDeadlineCalculator.status(
                discoveredAt = discoveredAt,
                authorityNotifiedAt = null,
                now = exactDeadline,
                orgZone = TimeZone.UTC,
            ) shouldBe
                BreachDeadlineStatus.DUE_SOON
        }

        // V1.9.38: discoveredAt is a wall-clock typed in in the organization zone, `now` is a UTC system stamp.
        val berlin = TimeZone.of("Europe/Berlin")

        test("Berlin: discovered 2026-07-01 10:00 local is overdue from 2026-07-04T08:00Z, not from 10:00Z") {
            val summerDiscovered = LocalDateTime(2026, 7, 1, 10, 0, 0)
            BreachDeadlineCalculator.deadline(discoveredAt = summerDiscovered, orgZone = berlin) shouldBe
                LocalDateTime(2026, 7, 4, 10, 0, 0)
            BreachDeadlineCalculator.status(
                discoveredAt = summerDiscovered,
                authorityNotifiedAt = null,
                now = LocalDateTime(2026, 7, 4, 7, 59, 59),
                orgZone = berlin,
            ) shouldBe BreachDeadlineStatus.DUE_SOON
            BreachDeadlineCalculator.status(
                discoveredAt = summerDiscovered,
                authorityNotifiedAt = null,
                now = LocalDateTime(2026, 7, 4, 8, 0, 1),
                orgZone = berlin,
            ) shouldBe BreachDeadlineStatus.OVERDUE
        }

        test("Berlin: the 72h window is real time across the 2026-10-25 DST change") {
            val discovered = LocalDateTime(2026, 10, 24, 12, 0, 0) // CEST, UTC+2
            // 72 real hours later it is 2026-10-27 11:00 local (CET, UTC+1), not 12:00.
            BreachDeadlineCalculator.deadline(discoveredAt = discovered, orgZone = berlin) shouldBe LocalDateTime(2026, 10, 27, 11, 0, 0)
        }

        test("AUTHORITY_NOTIFICATION_WINDOW_HOURS is the statutory Art. 33(1) 72h figure") {
            BreachDeadlineCalculator.AUTHORITY_NOTIFICATION_WINDOW_HOURS shouldBe 72L
        }
    })
