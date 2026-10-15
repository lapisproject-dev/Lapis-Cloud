package network.lapis.cloud.client

import kotlinx.datetime.LocalDate
import network.lapis.cloud.shared.domain.DayPosition
import network.lapis.cloud.shared.domain.DayResult
import network.lapis.cloud.shared.domain.MealDeductionRule
import network.lapis.cloud.shared.domain.TravelExpenseDefaults
import network.lapis.cloud.shared.domain.TravelExpenseRateSet
import kotlin.test.Test
import kotlin.test.assertEquals

/** V1.9.132 -- the single-meal amount is the shared calculator's own, and the day note says why a checked meal changes less than it promises. */
class TravelExpenseMealDeductionTest {
    private val rates = TravelExpenseDefaults.pdvTemplate()

    private fun result(
        base: Long,
        deduction: Long,
    ) = DayResult(LocalDate(2026, 3, 10), DayPosition.SINGLE, 600, base, deduction, base - deduction, false)

    @Test
    fun theSingleMealAmounts_areTwentyFortyForty_percentOfTheFullTier() {
        // Full tier 28,00 EUR: 20 % = 5,60, 40 % = 11,20 -- computed by hand, not by the code under test.
        assertEquals(560L, mealDeductionCents(Meal.BREAKFAST, rates))
        assertEquals(1120L, mealDeductionCents(Meal.LUNCH, rates))
        assertEquals(1120L, mealDeductionCents(Meal.DINNER, rates))
        assertEquals(20, mealPercent(Meal.BREAKFAST, rates))
        assertEquals(40, mealPercent(Meal.LUNCH, rates))
    }

    @Test
    fun theSingleMealAmount_isTheCalculatorsOwn_includingHalfUpRounding() {
        // 27,50 EUR full tier: 20 % = 5,50; 40 % = 11,00 -- and an odd tier makes HALF_UP visible: 27,55 * 20 % = 5,51.
        val odd = TravelExpenseRateSet(tierCents = listOf(600L, 1200L, 2755L))
        assertEquals(551L, mealDeductionCents(Meal.BREAKFAST, odd), "5,51 exactly")
        val halfUp = TravelExpenseRateSet(tierCents = listOf(600L, 1200L, 2753L))
        // 2753 * 20 / 100 = 550,6 -> 551 (HALF_UP); 2753 * 40 / 100 = 1101,2 -> 1101
        assertEquals(551L, mealDeductionCents(Meal.BREAKFAST, halfUp))
        assertEquals(1101L, mealDeductionCents(Meal.LUNCH, halfUp))
        listOf(rates, odd, halfUp).forEach { r ->
            Meal.entries.forEach { meal ->
                val expected =
                    MealDeductionRule.deductionCents(
                        base = r.tierCents[2],
                        breakfast = meal == Meal.BREAKFAST,
                        lunch = meal == Meal.LUNCH,
                        dinner = meal == Meal.DINNER,
                        rates = r,
                    )
                assertEquals(expected, mealDeductionCents(meal, r))
            }
        }
    }

    @Test
    fun theDayNote_saysNothing_withoutMeals_orWithoutAResult_orWhenTheDeductionIsExact() {
        assertEquals(DayDeductionKind.NONE, dayDeductionNote(null, 560).kind)
        assertEquals(DayDeductionKind.NONE, dayDeductionNote(result(2800, 0), 0).kind)
        assertEquals(DayDeductionKind.NONE, dayDeductionNote(result(2800, 560), 560).kind)
    }

    @Test
    fun theDayNote_saysThereIsNoAllowance_whenTheDayEarnsNone() {
        assertEquals(DayDeductionKind.NO_ALLOWANCE, dayDeductionNote(result(0, 0), 560).kind)
    }

    @Test
    fun theDayNote_saysCapped_whenTheMealsExceedTheDaysAllowance() {
        // 8-hour day earns 6,00 EUR; breakfast + lunch + dinner = 28,00 EUR are capped at 6,00.
        val note = dayDeductionNote(result(600, 600), 560 + 1120 + 1120)
        assertEquals(DayDeductionKind.CAPPED, note.kind)
        assertEquals(600L, note.baseCents)
    }
}
