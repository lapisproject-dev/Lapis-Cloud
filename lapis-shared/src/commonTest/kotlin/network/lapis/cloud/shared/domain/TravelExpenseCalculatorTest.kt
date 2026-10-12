package network.lapis.cloud.shared.domain

import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TravelExpenseCalculatorTest {
    private val rates = TravelExpenseDefaults.pdvTemplate(mileageCentsPerKm = 30)
    private val d1 = LocalDate(2026, 10, 14)
    private val d2 = LocalDate(2026, 10, 15)
    private val d3 = LocalDate(2026, 10, 16)

    private fun t(
        h: Int,
        m: Int = 0,
    ) = LocalTime(h, m)

    private fun single(
        from: LocalTime,
        to: LocalTime,
        b: Boolean = false,
        l: Boolean = false,
        d: Boolean = false,
        r: TravelExpenseRateSet = rates,
    ): CalcResult<DayResult> =
        TravelExpenseCalculator.day(
            input = DayInput(date = d1, timeFrom = from, timeTo = to, breakfastProvided = b, lunchProvided = l, dinnerProvided = d),
            travelFrom = d1,
            travelTo = d1,
            forcedBreakfastDates = emptySet(),
            rates = r,
        )

    private fun okDay(res: CalcResult<DayResult>): DayResult = (res as CalcResult.Ok).value

    // ── Stufen ──────────────────────────────────────────────────────────────────────────
    @Test
    fun tierBoundariesAreInclusive() {
        assertEquals(0L, PerDiemTierRule.tierFor(minutes = 0, rates = rates))
        assertEquals(0L, PerDiemTierRule.tierFor(minutes = 479, rates = rates))
        assertEquals(600L, PerDiemTierRule.tierFor(minutes = 480, rates = rates))
        assertEquals(600L, PerDiemTierRule.tierFor(minutes = 839, rates = rates))
        assertEquals(1200L, PerDiemTierRule.tierFor(minutes = 840, rates = rates))
        assertEquals(1200L, PerDiemTierRule.tierFor(minutes = 1439, rates = rates))
        assertEquals(2800L, PerDiemTierRule.tierFor(minutes = 1440, rates = rates))
    }

    @Test
    fun customThresholdsAreHonoured() {
        val custom = rates.copy(tierHours = listOf(6, 10, 20), tierCents = listOf(500L, 900L, 1800L))
        assertEquals(0L, PerDiemTierRule.tierFor(minutes = 359, rates = custom))
        assertEquals(500L, PerDiemTierRule.tierFor(minutes = 360, rates = custom))
        assertEquals(900L, PerDiemTierRule.tierFor(minutes = 600, rates = custom))
        assertEquals(1800L, PerDiemTierRule.tierFor(minutes = 1200, rates = custom))
    }

    // ── Positionen ──────────────────────────────────────────────────────────────────────
    @Test
    fun positions() {
        assertEquals(DayPosition.SINGLE, MultiDayRule.positionOf(day = d1, from = d1, to = d1))
        assertEquals(DayPosition.FIRST, MultiDayRule.positionOf(day = d1, from = d1, to = d3))
        assertEquals(DayPosition.MIDDLE, MultiDayRule.positionOf(day = d2, from = d1, to = d3))
        assertEquals(DayPosition.LAST, MultiDayRule.positionOf(day = d3, from = d1, to = d3))
    }

    @Test
    fun singleDayDuration() {
        assertEquals(0, okDay(single(from = t(h = 8), to = t(h = 8))).minutes)
        val r = okDay(single(from = t(h = 8), to = t(h = 16)))
        assertEquals(480, r.minutes)
        assertEquals(600L, r.baseCents)
    }

    @Test
    fun toBeforeFromIsAnError() {
        val res = single(from = t(h = 9), to = t(h = 8))
        assertTrue(res is CalcResult.Err)
        assertEquals("time_to_before_from", res.error.code)
    }

    @Test
    fun firstDayRunsUntilMidnight() {
        val r =
            okDay(
                TravelExpenseCalculator.day(
                    input =
                        DayInput(
                            date = d1,
                            timeFrom = t(h = 0),
                            timeTo = null,
                            breakfastProvided = false,
                            lunchProvided = false,
                            dinnerProvided = false,
                        ),
                    travelFrom = d1,
                    travelTo = d3,
                    forcedBreakfastDates = emptySet(),
                    rates = rates,
                ),
            )
        assertEquals(1440, r.minutes)
        assertEquals(2800L, r.baseCents)
        val r2 =
            okDay(
                TravelExpenseCalculator.day(
                    input =
                        DayInput(
                            date = d1,
                            timeFrom = t(h = 10),
                            timeTo = null,
                            breakfastProvided = false,
                            lunchProvided = false,
                            dinnerProvided = false,
                        ),
                    travelFrom = d1,
                    travelTo = d3,
                    forcedBreakfastDates = emptySet(),
                    rates = rates,
                ),
            )
        assertEquals(840, r2.minutes)
        assertEquals(1200L, r2.baseCents)
    }

    @Test
    fun lastDayStartsAtMidnight() {
        val r =
            okDay(
                TravelExpenseCalculator.day(
                    input =
                        DayInput(
                            date = d3,
                            timeFrom = null,
                            timeTo = t(h = 23, m = 59),
                            breakfastProvided = false,
                            lunchProvided = false,
                            dinnerProvided = false,
                        ),
                    travelFrom = d1,
                    travelTo = d3,
                    forcedBreakfastDates = emptySet(),
                    rates = rates,
                ),
            )
        assertEquals(1439, r.minutes)
        assertEquals(1200L, r.baseCents)
    }

    @Test
    fun middleDayIsFullAndTimesAreRejected() {
        val ok =
            okDay(
                TravelExpenseCalculator.day(
                    input =
                        DayInput(
                            date = d2,
                            timeFrom = null,
                            timeTo = null,
                            breakfastProvided = false,
                            lunchProvided = false,
                            dinnerProvided = false,
                        ),
                    travelFrom = d1,
                    travelTo = d3,
                    forcedBreakfastDates = emptySet(),
                    rates = rates,
                ),
            )
        assertEquals(1440, ok.minutes)
        assertEquals(2800L, ok.netCents)
        val bad =
            TravelExpenseCalculator.day(
                input =
                    DayInput(
                        date = d2,
                        timeFrom = t(h = 8),
                        timeTo = null,
                        breakfastProvided = false,
                        lunchProvided = false,
                        dinnerProvided = false,
                    ),
                travelFrom = d1,
                travelTo = d3,
                forcedBreakfastDates = emptySet(),
                rates = rates,
            )
        assertTrue(bad is CalcResult.Err)
    }

    @Test
    fun positionTimeRequirements() {
        assertTrue(
            TravelExpenseCalculator.day(
                input =
                    DayInput(
                        date = d1,
                        timeFrom = null,
                        timeTo = null,
                        breakfastProvided = false,
                        lunchProvided = false,
                        dinnerProvided = false,
                    ),
                travelFrom = d1,
                travelTo = d3,
                forcedBreakfastDates = emptySet(),
                rates = rates,
            ) is CalcResult.Err,
        )
        assertTrue(
            TravelExpenseCalculator.day(
                input =
                    DayInput(
                        date = d1,
                        timeFrom = t(h = 8),
                        timeTo = t(h = 9),
                        breakfastProvided = false,
                        lunchProvided = false,
                        dinnerProvided = false,
                    ),
                travelFrom = d1,
                travelTo = d3,
                forcedBreakfastDates = emptySet(),
                rates = rates,
            ) is CalcResult.Err,
        )
        assertTrue(
            TravelExpenseCalculator.day(
                input =
                    DayInput(
                        date = d3,
                        timeFrom = null,
                        timeTo = null,
                        breakfastProvided = false,
                        lunchProvided = false,
                        dinnerProvided = false,
                    ),
                travelFrom = d1,
                travelTo = d3,
                forcedBreakfastDates = emptySet(),
                rates = rates,
            ) is CalcResult.Err,
        )
        assertTrue(
            TravelExpenseCalculator.day(
                input =
                    DayInput(
                        date = d1,
                        timeFrom = null,
                        timeTo = t(h = 9),
                        breakfastProvided = false,
                        lunchProvided = false,
                        dinnerProvided = false,
                    ),
                travelFrom = d1,
                travelTo = d1,
                forcedBreakfastDates = emptySet(),
                rates = rates,
            ) is CalcResult.Err,
        )
        assertTrue(
            TravelExpenseCalculator.day(
                input =
                    DayInput(
                        date = d3,
                        timeFrom = null,
                        timeTo = null,
                        breakfastProvided = false,
                        lunchProvided = false,
                        dinnerProvided = false,
                    ),
                travelFrom = d1,
                travelTo = d2,
                forcedBreakfastDates = emptySet(),
                rates = rates,
            ) is CalcResult.Err,
        )
    }

    // ── Abzüge ──────────────────────────────────────────────────────────────────────────
    @Test
    fun mealDeductionsPerMealAndCombined() {
        // 2800 * 20% = 560, * 40% = 1120
        val full = rates
        assertEquals(560L, MealDeductionRule.deductionCents(base = 2800, breakfast = true, lunch = false, dinner = false, rates = full))
        assertEquals(1120L, MealDeductionRule.deductionCents(base = 2800, breakfast = false, lunch = true, dinner = false, rates = full))
        assertEquals(1120L, MealDeductionRule.deductionCents(base = 2800, breakfast = false, lunch = false, dinner = true, rates = full))
        assertEquals(2800L, MealDeductionRule.deductionCents(base = 2800, breakfast = true, lunch = true, dinner = true, rates = full))
    }

    @Test
    fun deductionIsCappedAtDayBase() {
        // 6 EUR day with breakfast: 560 deduction -> 40 left
        val b = okDay(single(from = t(h = 8), to = t(h = 16), b = true))
        assertEquals(600L, b.baseCents)
        assertEquals(560L, b.deductionCents)
        assertEquals(40L, b.netCents)
        // 12 EUR day with lunch: 1120 -> 80
        val l = okDay(single(from = t(h = 8), to = t(h = 22), l = true))
        assertEquals(1200L, l.baseCents)
        assertEquals(80L, l.netCents)
        // 6 EUR with all three -> 0, never negative
        val all = okDay(single(from = t(h = 8), to = t(h = 16), b = true, l = true, d = true))
        assertEquals(0L, all.netCents)
        assertEquals(600L, all.deductionCents)
    }

    @Test
    fun roundingIsHalfUp() {
        val odd = rates.copy(tierCents = listOf(600L, 1200L, 2799L))
        // 2799 * 20% = 559.8 -> 560
        assertEquals(560L, MealDeductionRule.deductionCents(base = 2799, breakfast = true, lunch = false, dinner = false, rates = odd))
        // 2795 * 20% = 559.0; 2797*20% = 559.4 -> 559; 2798 * 20% = 559.6 -> 560 ; x.5 -> up
        val half = rates.copy(tierCents = listOf(600L, 1200L, 2825L), breakfastPercent = 10)
        // 2825 * 10% = 282.5 -> 283
        assertEquals(283L, MealDeductionRule.deductionCents(base = 2825, breakfast = true, lunch = false, dinner = false, rates = half))
    }

    @Test
    fun percentExtremes() {
        val zero = rates.copy(breakfastPercent = 0)
        assertEquals(0L, MealDeductionRule.deductionCents(base = 2800, breakfast = true, lunch = false, dinner = false, rates = zero))
        val hundred = rates.copy(breakfastPercent = 100)
        assertEquals(2800L, MealDeductionRule.deductionCents(base = 2800, breakfast = true, lunch = false, dinner = false, rates = hundred))
    }

    // ── Frühstückssperre ────────────────────────────────────────────────────────────────
    @Test
    fun forcedBreakfastDatesCoverFollowingMornings() {
        val s = TravelExpenseCalculator.forcedBreakfastDates(listOf(d1 to 2))
        assertEquals(setOf(d2, d3), s)
        val overlap = TravelExpenseCalculator.forcedBreakfastDates(listOf(d1 to 2, d2 to 1))
        assertEquals(setOf(d2, d3), overlap)
        assertEquals(emptySet(), TravelExpenseCalculator.forcedBreakfastDates(emptyList()))
    }

    @Test
    fun forcedBreakfastIsDeductedRegardlessOfInput() {
        val r =
            okDay(
                TravelExpenseCalculator.day(
                    input =
                        DayInput(
                            date = d2,
                            timeFrom = null,
                            timeTo = null,
                            breakfastProvided = false,
                            lunchProvided = false,
                            dinnerProvided = false,
                        ),
                    travelFrom = d1,
                    travelTo = d3,
                    forcedBreakfastDates = setOf(d2),
                    rates = rates,
                ),
            )
        assertTrue(r.breakfastForcedByAccommodation)
        assertEquals(560L, r.deductionCents)
        assertEquals(2240L, r.netCents)
    }

    // ── Kilometer ───────────────────────────────────────────────────────────────────────
    @Test
    fun mileage() {
        assertEquals(30L, TravelExpenseCalculator.mileage(km = 1, passengers = 0, rates = rates).getOrNull())
        assertEquals(150_000L, TravelExpenseCalculator.mileage(km = 5000, passengers = 0, rates = rates).getOrNull())
        // 100 km, 2 passengers: 3000 + 100*2*2 = 3400
        assertEquals(3400L, TravelExpenseCalculator.mileage(km = 100, passengers = 2, rates = rates).getOrNull())
        assertEquals(8, TravelExpenseCalculator.MAX_PASSENGERS)
        assertEquals(100L * 30 + 100L * 8 * 2, TravelExpenseCalculator.mileage(km = 100, passengers = 8, rates = rates).getOrNull())
        assertNull(TravelExpenseCalculator.mileage(km = 0, passengers = 0, rates = rates).getOrNull())
        assertNull(TravelExpenseCalculator.mileage(km = 5001, passengers = 0, rates = rates).getOrNull())
        assertNull(TravelExpenseCalculator.mileage(km = 10, passengers = 9, rates = rates).getOrNull())
        assertNull(TravelExpenseCalculator.mileage(km = 10, passengers = -1, rates = rates).getOrNull())
    }

    @Test
    fun mileageWithoutRateIsAnError() {
        val res = TravelExpenseCalculator.mileage(km = 10, passengers = 0, rates = TravelExpenseDefaults.pdvTemplate())
        assertTrue(res is CalcResult.Err)
        assertEquals("mileage_rate_not_configured", res.error.code)
    }

    @Test
    fun overflowIsCaught() {
        val huge = rates.copy(mileageCentsPerKm = Long.MAX_VALUE / 2)
        assertTrue(TravelExpenseCalculator.mileage(km = 5000, passengers = 0, rates = huge) is CalcResult.Err)
        assertNull(mulExact(a = Long.MAX_VALUE, b = 2))
        assertEquals(6L, mulExact(a = 2, b = 3))
    }

    // ── Belege / Alt-Tagegeld ───────────────────────────────────────────────────────────
    @Test
    fun receipted() {
        assertEquals(1L, TravelExpenseCalculator.receipted(1).getOrNull())
        assertEquals(1_000_000L, TravelExpenseCalculator.receipted(1_000_000).getOrNull())
        assertNull(TravelExpenseCalculator.receipted(0).getOrNull())
        assertNull(TravelExpenseCalculator.receipted(-5).getOrNull())
        assertNull(TravelExpenseCalculator.receipted(1_000_001).getOrNull())
    }

    @Test
    fun legacyPerDiem() {
        val r = rates.copy(legacyPerDiemCents = 2400)
        assertEquals(7200L, TravelExpenseCalculator.legacyPerDiem(days = 3, rates = r).getOrNull())
        assertNull(TravelExpenseCalculator.legacyPerDiem(days = 3, rates = rates).getOrNull())
        assertNull(TravelExpenseCalculator.legacyPerDiem(days = 0, rates = r).getOrNull())
    }

    // ── Summen ──────────────────────────────────────────────────────────────────────────
    @Test
    fun totals() {
        val a = TravelExpenseCalculator.totals(lineNetCents = listOf(2240L, 3400L), deductionCents = listOf(560L, 0L), advanceCents = 0)
        assertEquals(5640L, a.subtotalCents)
        assertEquals(6200L, a.grossCents)
        assertEquals(560L, a.deductionCents)
        assertEquals(5640L, a.payoutCents)
        val b = TravelExpenseCalculator.totals(lineNetCents = listOf(5000L), deductionCents = listOf(0L), advanceCents = 2000L)
        assertEquals(3000L, b.payoutCents)
        val eq = TravelExpenseCalculator.totals(lineNetCents = listOf(5000L), deductionCents = listOf(0L), advanceCents = 5000L)
        assertEquals(0L, eq.payoutCents)
        assertTrue(!eq.advanceExceedsTotal)
        val over = TravelExpenseCalculator.totals(lineNetCents = listOf(5000L), deductionCents = listOf(0L), advanceCents = 6000L)
        assertEquals(0L, over.payoutCents)
        assertTrue(over.advanceExceedsTotal)
    }

    @Test
    fun threeDayTripByHand() {
        // d1 from 09:00 (900 min -> 12 EUR), d2 full (28 EUR, lunch+dinner -> -22.40), d3 until 07:30 (450 -> 0)
        val days =
            listOf(
                DayInput(
                    date = d1,
                    timeFrom = t(h = 9),
                    timeTo = null,
                    breakfastProvided = false,
                    lunchProvided = false,
                    dinnerProvided = false,
                ),
                DayInput(date = d2, timeFrom = null, timeTo = null, breakfastProvided = false, lunchProvided = true, dinnerProvided = true),
                DayInput(
                    date = d3,
                    timeFrom = null,
                    timeTo = t(h = 7, m = 30),
                    breakfastProvided = false,
                    lunchProvided = false,
                    dinnerProvided = false,
                ),
            )
        val results =
            days.map {
                okDay(
                    TravelExpenseCalculator.day(
                        input = it,
                        travelFrom = d1,
                        travelTo = d3,
                        forcedBreakfastDates = emptySet(),
                        rates = rates,
                    ),
                )
            }
        assertEquals(listOf(1200L, 2800L - 2240L, 0L), results.map { it.netCents })
        assertEquals(listOf(0L, 2240L, 0L), results.map { it.deductionCents })
        val tot =
            TravelExpenseCalculator.totals(
                lineNetCents =
                    results.map {
                        it.netCents
                    },
                deductionCents = results.map { it.deductionCents },
                advanceCents = 0,
            )
        assertEquals(1760L, tot.subtotalCents)
    }

    // ── Hilfen ──────────────────────────────────────────────────────────────────────────
    @Test
    fun travelDaysList() {
        assertEquals(3, MultiDayRule.travelDays(from = d1, to = d3).size)
        assertEquals(31, MultiDayRule.travelDays(from = LocalDate(2026, 10, 1), to = LocalDate(2026, 10, 31)).size)
        assertEquals(32, MultiDayRule.travelDays(from = LocalDate(2026, 10, 1), to = LocalDate(2026, 11, 1)).size)
        assertEquals(emptyList(), MultiDayRule.travelDays(from = d3, to = d1))
    }

    @Test
    fun deadline() {
        assertEquals(LocalDate(2026, 12, 31), TravelExpenseDeadline.deadlineFor(LocalDate(2026, 3, 1)))
        assertTrue(!TravelExpenseDeadline.isOverdue(travelTo = LocalDate(2026, 12, 31), today = LocalDate(2026, 12, 31)))
        assertTrue(TravelExpenseDeadline.isOverdue(travelTo = LocalDate(2026, 12, 31), today = LocalDate(2027, 1, 1)))
        assertTrue(!TravelExpenseDeadline.isOverdue(travelTo = LocalDate(2027, 1, 2), today = LocalDate(2027, 1, 1)))
        assertTrue(TravelExpenseDeadline.isOverdue(travelTo = LocalDate(2024, 2, 29), today = LocalDate(2025, 1, 1)))
    }

    @Test
    fun centsConversion() {
        assertEquals("12.34", CentsConversion.toDecimalString(1234))
        assertEquals("0.05", CentsConversion.toDecimalString(5))
        assertEquals("-0.50", CentsConversion.toDecimalString(-50))
        assertEquals(1234L, CentsConversion.parseExact("12.34"))
        assertEquals(1230L, CentsConversion.parseExact("12.3"))
        assertEquals(1200L, CentsConversion.parseExact("12"))
        assertNull(CentsConversion.parseExact("12.345"))
        assertNull(CentsConversion.parseExact("abc"))
        assertNull(CentsConversion.parseExact(""))
    }
}
