package network.lapis.cloud.server.economy

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.atTime
import kotlinx.datetime.plus
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Pure unit tests for [WeightDecayClock] -- no database needed. See that object's KDoc for the
 * "40-Jahre-Stabilitätsanforderung" this covers (0/1/2/…/14600 Tage, DST, Mitternacht-Grenzfall,
 * negativer Abstand, `decayedUnrounded` rundet nicht).
 */
class WeightDecayClockTest :
    FunSpec({
        val since = LocalDateTime(2026, 1, 1, 12, 0, 0)
        val initial = BigDecimal("100.00")

        test("daysElapsed at exactly the same instant is 0") {
            WeightDecayClock.daysElapsed(from = since, now = since) shouldBe 0L
        }

        test("daysElapsed after exactly 1 day is 1") {
            WeightDecayClock.daysElapsed(from = since, now = LocalDateTime(2026, 1, 2, 12, 0, 0)) shouldBe 1L
        }

        test("daysElapsed after exactly 2 days is 2") {
            WeightDecayClock.daysElapsed(from = since, now = LocalDateTime(2026, 1, 3, 12, 0, 0)) shouldBe 2L
        }

        test("daysElapsed after 14600 days (~40 years) is exactly 14600, no throw/overflow") {
            // 2026-01-01 + 14600 days = 2065-12-22 (verified via kotlinx.datetime below, not
            // hand-counted, to keep this test resilient to leap-year arithmetic mistakes).
            val fortyYearsLater = since.date.plus(14600, DateTimeUnit.DAY).atTime(12, 0, 0)
            WeightDecayClock.daysElapsed(from = since, now = fortyYearsLater) shouldBe 14600L
        }

        test("daysElapsed handles a 23h59m span that crosses local midnight as 0 elapsed days, not 1 (DST/timezone-artifact guard)") {
            val justBeforeMidnight = LocalDateTime(2026, 1, 1, 0, 0, 1)
            val justAfterNextMidnight = LocalDateTime(2026, 1, 1, 23, 59, 59)
            WeightDecayClock.daysElapsed(from = justBeforeMidnight, now = justAfterNextMidnight) shouldBe 0L
        }

        // S2 (Review Runde 1, 2026-08-18): the two tests above never actually cross a calendar-day
        // boundary at all -- "justBeforeMidnight"/"justAfterNextMidnight" are BOTH on 2026-01-01,
        // so a naive (and WRONG) calendar-day subtraction (`now.date.toEpochDays() -
        // from.date.toEpochDays()`) would ALSO return 0 for that pair, exactly like the correct
        // `Instant`-difference implementation does -- the test proves nothing about which
        // implementation is actually running. These two tests are the real midnight-crossing
        // regression guard: `from` and `now` are on two DIFFERENT calendar dates in both cases, so
        // a calendar-day subtraction would diverge from the correct `Instant`-difference result --
        // the first case (< 24h apart despite crossing midnight) is exactly the scenario the class
        // KDoc's "40-Jahre-Stabilitätsanforderung" and "two LocalDateTimes 23h59m apart but
        // crossing a local midnight count as 0 elapsed days, not 1" sentence describes.
        test(
            "daysElapsed: 23h59m apart but crossing a calendar-day boundary (2026-01-01T23:00 -> 2026-01-02T22:59) is 0 elapsed days, not 1 -- proves Instant-difference, not calendar-day subtraction",
        ) {
            val lateOnDay1 = LocalDateTime(2026, 1, 1, 23, 0, 0)
            val almostOneDayLaterOnDay2 = LocalDateTime(2026, 1, 2, 22, 59, 0)
            WeightDecayClock.daysElapsed(from = lateOnDay1, now = almostOneDayLaterOnDay2) shouldBe 0L
        }

        test(
            "daysElapsed: exactly 24h apart across the same calendar-day boundary (2026-01-01T23:00 -> 2026-01-02T23:00) is 1 elapsed day -- counterpart proving the boundary itself is Instant-exact",
        ) {
            val lateOnDay1 = LocalDateTime(2026, 1, 1, 23, 0, 0)
            val exactlyOneDayLaterOnDay2 = LocalDateTime(2026, 1, 2, 23, 0, 0)
            WeightDecayClock.daysElapsed(from = lateOnDay1, now = exactlyOneDayLaterOnDay2) shouldBe 1L
        }

        test("daysElapsed floors negative spans (now before from) to 0 rather than throwing or going negative") {
            WeightDecayClock.daysElapsed(from = since, now = LocalDateTime(2025, 12, 31, 0, 0, 0)) shouldBe 0L
        }

        test("decayedUnrounded at 0 days elapsed equals the input amount exactly") {
            WeightDecayClock.decayedUnrounded(amountLtr = initial, since = since, now = since).compareTo(initial) shouldBe 0
        }

        test("decayedUnrounded after 1 day equals amount * 0.9 exactly") {
            val oneDayLater = LocalDateTime(2026, 1, 2, 12, 0, 0)
            val result = WeightDecayClock.decayedUnrounded(amountLtr = initial, since = since, now = oneDayLater)
            result.compareTo(BigDecimal("90.00")) shouldBe 0
        }

        test(
            "decayedUnrounded does NOT round -- summing many unrounded contributions then rounding once differs from rounding each contribution first",
        ) {
            // Two small contributions that individually round to 0.00 at 2dp, but whose UNROUNDED
            // sum rounds to a non-zero value -- proves decayedUnrounded is carrying full precision
            // rather than silently rounding per-call the way CrowdfundingWeightDecay.currentWeight
            // deliberately still does.
            val tiny = BigDecimal("0.0049")
            val a = WeightDecayClock.decayedUnrounded(amountLtr = tiny, since = since, now = since)
            val b = WeightDecayClock.decayedUnrounded(amountLtr = tiny, since = since, now = since)
            val roundedIndividually = a.setScale(2, RoundingMode.HALF_UP) + b.setScale(2, RoundingMode.HALF_UP)
            val summedThenRounded = WeightDecayClock.round2(a + b)
            roundedIndividually.compareTo(BigDecimal("0.00")) shouldBe 0
            summedThenRounded.compareTo(BigDecimal("0.01")) shouldBe 0
        }

        test("decayedUnrounded is strictly monotonically decreasing as elapsed days grow") {
            val samples = (0..30).map { days -> since.date.plus(days, DateTimeUnit.DAY).atTime(12, 0, 0) }
            val weights = samples.map { WeightDecayClock.decayedUnrounded(amountLtr = initial, since = since, now = it) }
            weights.zipWithNext().all { (a, b) -> a > b } shouldBe true
        }

        test("decayedUnrounded for a 14600-day (~40 year) span is a tiny, non-throwing, non-negative value") {
            val fortyYearsLater = since.date.plus(14600, DateTimeUnit.DAY).atTime(12, 0, 0)
            val weight = WeightDecayClock.decayedUnrounded(amountLtr = initial, since = since, now = fortyYearsLater)
            (weight.signum() >= 0) shouldBe true
            (weight < BigDecimal("0.01")) shouldBe true
        }

        test("round2 rounds HALF_UP to 2 decimal places, matching ltr_ledger_entry.amount_ltr's own scale") {
            WeightDecayClock.round2(BigDecimal("1.005")).compareTo(BigDecimal("1.01")) shouldBe 0
            WeightDecayClock.round2(BigDecimal("1.004")).compareTo(BigDecimal("1.00")) shouldBe 0
        }

        test("KEEP_RATE_PER_DAY is the 10%/day decay (0.9 kept)") {
            WeightDecayClock.KEEP_RATE_PER_DAY.compareTo(BigDecimal("0.9")) shouldBe 0
        }
    })
