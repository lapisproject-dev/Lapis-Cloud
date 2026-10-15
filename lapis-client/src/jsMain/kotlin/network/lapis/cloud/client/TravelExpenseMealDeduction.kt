package network.lapis.cloud.client

import network.lapis.cloud.shared.domain.DayResult
import network.lapis.cloud.shared.domain.MealDeductionRule
import network.lapis.cloud.shared.domain.TravelExpenseRateSet

/**
 * V1.9.132 -- what ONE provided meal takes off, for display next to the checkbox ("Frühstück gestellt (20 % = −5,60 €)") and for the live
 * breakdown. **No calculation rule of its own**: the amount is the shared [MealDeductionRule.deductionCents] asked for exactly one meal against
 * the full 24-hour tier (its cap at the day base is then a cap at the tier itself, never reached by a single meal at a percentage of at most
 * 100), so the rounding (HALF_UP per deduction) is the calculator's own.
 */
internal enum class Meal { BREAKFAST, LUNCH, DINNER }

internal fun mealDeductionCents(
    meal: Meal,
    rates: TravelExpenseRateSet,
): Long =
    MealDeductionRule.deductionCents(
        base = rates.tierCents[2],
        breakfast = meal == Meal.BREAKFAST,
        lunch = meal == Meal.LUNCH,
        dinner = meal == Meal.DINNER,
        rates = rates,
    )

internal fun mealPercent(
    meal: Meal,
    rates: TravelExpenseRateSet,
): Int =
    when (meal) {
        Meal.BREAKFAST -> rates.breakfastPercent
        Meal.LUNCH -> rates.lunchPercent
        Meal.DINNER -> rates.dinnerPercent
    }

/** What a day's deduction needs to say beyond the numbers. */
internal enum class DayDeductionKind {
    /** Nothing to explain. */
    NONE,

    /** The day earns no allowance (under the first tier), so a provided meal takes nothing off. */
    NO_ALLOWANCE,

    /** The meals taken together exceed the day's allowance; the deduction was capped at it. */
    CAPPED,
}

internal data class DayDeductionNote(
    val kind: DayDeductionKind,
    /** The day's allowance (the cap), for the [DayDeductionKind.CAPPED] sentence. */
    val baseCents: Long,
)

/**
 * [selectedMealCents] is the SUM of the single-meal amounts of the meals that count on the day (checked ones plus a breakfast the stay provides).
 * `null` [result] (the day is not filled in) and a day without meals need no note.
 */
internal fun dayDeductionNote(
    result: DayResult?,
    selectedMealCents: Long,
): DayDeductionNote {
    if (result == null || selectedMealCents <= 0L) return DayDeductionNote(DayDeductionKind.NONE, 0L)
    return when {
        result.baseCents == 0L -> DayDeductionNote(DayDeductionKind.NO_ALLOWANCE, 0L)
        selectedMealCents > result.deductionCents -> DayDeductionNote(DayDeductionKind.CAPPED, result.baseCents)
        else -> DayDeductionNote(DayDeductionKind.NONE, result.baseCents)
    }
}
