package network.lapis.cloud.shared.domain

import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlinx.datetime.daysUntil
import kotlinx.datetime.plus
import kotlinx.serialization.Serializable

/*
 * Welle V1.9.122 "Reisekostenabrechnung nach PdV-Formular" -- the pure calculation core.
 *
 * Lives in commonMain on purpose: the server is the ONLY authority that writes amounts
 * (`TravelExpenseService.recomputeReport`), the client calls the very same functions for its live
 * preview and NEVER carries calculation code of its own. Everything here works in whole cents
 * (`Long`) and whole minutes (`Int`) -- there is no `Decimal`/`BigDecimal` in the core (the JS
 * client has no exact decimal type). The single rounding rule is HALF_UP to whole cents per
 * deduction.
 */

/**
 * Frozen set of calculation parameters. After `submitReport` the server serialises exactly this
 * object as `travel_expense_report.rates_snapshot`; nothing reads `organization_settings` again
 * for that report.
 *
 * @property tierHours the three ascending hour thresholds (default 8/14/24), INCLUSIVE ("ab",
 *   as printed on the PdV form) -- see [PerDiemTierRule].
 * @property tierCents the day allowance paid at or above the matching threshold.
 * @property mileageCentsPerKm `null` == not configured (mileage lines then refuse to calculate).
 * @property legacyPerDiemCents only for pre-V1.9.122 PER_DIEM lines, `null` == not configured.
 */
@Serializable
data class TravelExpenseRateSet(
    val version: Int = 1,
    val tierHours: List<Int> = TravelExpenseDefaults.TIER_HOURS,
    val tierCents: List<Long> = TravelExpenseDefaults.TIER_CENTS,
    val breakfastPercent: Int = TravelExpenseDefaults.BREAKFAST_PERCENT,
    val lunchPercent: Int = TravelExpenseDefaults.LUNCH_PERCENT,
    val dinnerPercent: Int = TravelExpenseDefaults.DINNER_PERCENT,
    val mileageCentsPerKm: Long? = null,
    val passengerCentsPerKm: Long = TravelExpenseDefaults.PASSENGER_CENTS_PER_KM,
    val legacyPerDiemCents: Long? = null,
)

/**
 * The PdV template values (Reisekostenabrechnung 2024 + Hinweise vom 23.07.2025). PROVISIONAL and
 * PdV-specific -- explicitly NOT the statutory values (those are 14/28 EUR, section 9 paragraph 4a
 * EStG). The footnote on the form mentions 24 EUR in one place and 28 EUR in another; 28 EUR is
 * used. An operator confirms or changes these in the settings screen.
 */
object TravelExpenseDefaults {
    val TIER_HOURS: List<Int> = listOf(8, 14, 24)
    val TIER_CENTS: List<Long> = listOf(600L, 1200L, 2800L)
    const val BREAKFAST_PERCENT = 20
    const val LUNCH_PERCENT = 40
    const val DINNER_PERCENT = 40
    const val PASSENGER_CENTS_PER_KM = 2L

    /** The PdV template as a complete set; mileage stays unconfigured on purpose (see CHANGELOG). */
    fun pdvTemplate(mileageCentsPerKm: Long? = null): TravelExpenseRateSet = TravelExpenseRateSet(mileageCentsPerKm = mileageCentsPerKm)
}

enum class DayPosition { SINGLE, FIRST, MIDDLE, LAST }

/** A user-correctable input problem. Never thrown -- see [CalcResult]. */
sealed interface CalcError {
    /** [key] names the offending input (e.g. `kilometers`), [code] is a stable machine code. */
    data class Field(
        val key: String,
        val code: String,
    ) : CalcError
}

/** Minimal result type: user errors are values, never exceptions. */
sealed interface CalcResult<out T> {
    data class Ok<T>(
        val value: T,
    ) : CalcResult<T>

    data class Err(
        val error: CalcError.Field,
    ) : CalcResult<Nothing>

    fun getOrNull(): T? = (this as? Ok<T>)?.value
}

private fun fail(
    key: String,
    code: String,
): CalcResult.Err = CalcResult.Err(CalcError.Field(key = key, code = code))

/**
 * Overflow-checked multiplication (no `Math.multiplyExact` in commonMain). Returns `null` on
 * overflow. Both factors must be non-negative.
 */
fun mulExact(
    a: Long,
    b: Long,
): Long? {
    if (a < 0 || b < 0) return null
    if (a == 0L || b == 0L) return 0L
    if (a > Long.MAX_VALUE / b) return null
    return a * b
}

/** The ONE place that decides how a travel day is positioned inside the trip. */
object MultiDayRule {
    const val MINUTES_PER_DAY = 1440

    fun positionOf(
        day: LocalDate,
        from: LocalDate,
        to: LocalDate,
    ): DayPosition =
        when {
            from == to -> DayPosition.SINGLE
            day == from -> DayPosition.FIRST
            day == to -> DayPosition.LAST
            else -> DayPosition.MIDDLE
        }

    /**
     * Minutes of absence on a day of the given [pos]. Wall clock without time zone; a daylight
     * saving shift is deliberately ignored (documented), as are night trips across midnight -- they
     * are NOT added up (the form is per calendar day).
     * - SINGLE: `to - from` (both required, `to >= from`)
     * - FIRST: `1440 - from` (the day runs until 24:00)
     * - MIDDLE: 1440, times are ignored
     * - LAST: `to` (the day starts at 00:00)
     */
    fun absenceMinutes(
        pos: DayPosition,
        from: LocalTime?,
        to: LocalTime?,
    ): Int =
        when (pos) {
            DayPosition.SINGLE -> minutesOf(requireNotNull(to)) - minutesOf(requireNotNull(from))
            DayPosition.FIRST -> MINUTES_PER_DAY - minutesOf(requireNotNull(from))
            DayPosition.MIDDLE -> MINUTES_PER_DAY
            DayPosition.LAST -> minutesOf(requireNotNull(to))
        }

    /** Whether the times required for [pos] are present. */
    fun hasRequiredTimes(
        pos: DayPosition,
        from: LocalTime?,
        to: LocalTime?,
    ): Boolean =
        when (pos) {
            DayPosition.SINGLE -> from != null && to != null
            DayPosition.FIRST -> from != null
            DayPosition.MIDDLE -> true
            DayPosition.LAST -> to != null
        }

    fun travelDays(
        from: LocalDate,
        to: LocalDate,
    ): List<LocalDate> {
        if (to < from) return emptyList()
        val span = from.daysUntil(to) + 1
        return (0 until span).map { from.plus(it, DateTimeUnit.DAY) }
    }

    internal fun minutesOf(t: LocalTime): Int = t.hour * 60 + t.minute
}

/** The ONE place that maps absence minutes to the tiered day allowance. Inclusive ("ab"). */
object PerDiemTierRule {
    fun tierFor(
        minutes: Int,
        rates: TravelExpenseRateSet,
    ): Long {
        val hours = rates.tierHours
        val cents = rates.tierCents
        for (i in 2 downTo 0) {
            if (minutes >= hours[i] * 60) return cents[i]
        }
        return 0L
    }
}

/** Meal deductions: percentages of the 24 h tier, HALF_UP per deduction, capped at the day base. */
object MealDeductionRule {
    fun deductionCents(
        base: Long,
        breakfast: Boolean,
        lunch: Boolean,
        dinner: Boolean,
        rates: TravelExpenseRateSet,
    ): Long {
        val full = rates.tierCents[2]
        var sum = 0L
        if (breakfast) sum += percentOf(value = full, percent = rates.breakfastPercent)
        if (lunch) sum += percentOf(value = full, percent = rates.lunchPercent)
        if (dinner) sum += percentOf(value = full, percent = rates.dinnerPercent)
        return minOf(sum, base)
    }

    /** HALF_UP on whole cents: `(value * pct + 50) / 100` for non-negative operands. */
    internal fun percentOf(
        value: Long,
        percent: Int,
    ): Long = (value * percent + 50) / 100
}

data class DayInput(
    val date: LocalDate,
    val timeFrom: LocalTime?,
    val timeTo: LocalTime?,
    val breakfastProvided: Boolean,
    val lunchProvided: Boolean,
    val dinnerProvided: Boolean,
)

data class DayResult(
    val date: LocalDate,
    val position: DayPosition,
    val minutes: Int,
    val baseCents: Long,
    val deductionCents: Long,
    val netCents: Long,
    val breakfastForcedByAccommodation: Boolean,
)

object TravelExpenseCalculator {
    const val MIN_KM = 1L
    const val MAX_KM = 5000L
    const val MAX_PASSENGERS = 8
    const val MAX_RECEIPTED_CENTS = 1_000_000L
    const val MAX_TRAVEL_DAYS = 31

    /**
     * One travel day. Validates the times for the day's position (single day: both; first day:
     * `from` only; last day: `to` only; middle day: none) and `to >= from`. A breakfast supplied by
     * an overnight stay with breakfast ([forcedBreakfastDates]) is ALWAYS deducted, whatever the
     * caller passes.
     */
    fun day(
        input: DayInput,
        travelFrom: LocalDate,
        travelTo: LocalDate,
        forcedBreakfastDates: Set<LocalDate>,
        rates: TravelExpenseRateSet,
    ): CalcResult<DayResult> {
        if (input.date < travelFrom || input.date > travelTo) return fail(key = "date", code = "day_outside_range")
        val pos = MultiDayRule.positionOf(day = input.date, from = travelFrom, to = travelTo)
        when (pos) {
            DayPosition.SINGLE ->
                if (input.timeFrom == null || input.timeTo == null) return fail(key = "time", code = "times_required")
            DayPosition.FIRST -> {
                if (input.timeFrom == null) return fail(key = "timeFrom", code = "time_from_required")
                if (input.timeTo != null) return fail(key = "timeTo", code = "time_to_not_allowed")
            }
            DayPosition.LAST -> {
                if (input.timeTo == null) return fail(key = "timeTo", code = "time_to_required")
                if (input.timeFrom != null) return fail(key = "timeFrom", code = "time_from_not_allowed")
            }
            DayPosition.MIDDLE ->
                if (input.timeFrom != null || input.timeTo != null) return fail(key = "time", code = "times_not_allowed")
        }
        if (input.timeFrom != null && input.timeTo != null && input.timeTo < input.timeFrom) {
            return fail(key = "timeTo", code = "time_to_before_from")
        }
        val minutes = MultiDayRule.absenceMinutes(pos = pos, from = input.timeFrom, to = input.timeTo)
        val base = PerDiemTierRule.tierFor(minutes = minutes, rates = rates)
        val forced = input.date in forcedBreakfastDates
        val deduction =
            MealDeductionRule.deductionCents(
                base = base,
                breakfast = input.breakfastProvided || forced,
                lunch = input.lunchProvided,
                dinner = input.dinnerProvided,
                rates = rates,
            )
        return CalcResult.Ok(
            DayResult(
                date = input.date,
                position = pos,
                minutes = minutes,
                baseCents = base,
                deductionCents = deduction,
                netCents = base - deduction,
                breakfastForcedByAccommodation = forced,
            ),
        )
    }

    /** `km * rate + km * passengers * passengerRate`, whole kilometres only. */
    fun mileage(
        km: Long,
        passengers: Int,
        rates: TravelExpenseRateSet,
    ): CalcResult<Long> {
        val rate = rates.mileageCentsPerKm ?: return fail(key = "mileageRate", code = "mileage_rate_not_configured")
        if (km < MIN_KM || km > MAX_KM) return fail(key = "kilometers", code = "kilometers_out_of_range")
        if (passengers < 0 || passengers > MAX_PASSENGERS) return fail(key = "passengerCount", code = "passengers_out_of_range")
        val own = mulExact(a = km, b = rate) ?: return fail(key = "kilometers", code = "amount_overflow")
        val perPassenger = mulExact(a = km, b = rates.passengerCentsPerKm) ?: return fail(key = "kilometers", code = "amount_overflow")
        val passengersTotal =
            mulExact(a = perPassenger, b = passengers.toLong()) ?: return fail(key = "kilometers", code = "amount_overflow")
        return CalcResult.Ok(own + passengersTotal)
    }

    fun receipted(amountCents: Long): CalcResult<Long> =
        if (amountCents < 1 || amountCents > MAX_RECEIPTED_CENTS) {
            fail(key = "amount", code = "amount_out_of_range")
        } else {
            CalcResult.Ok(amountCents)
        }

    /** Pre-V1.9.122 PER_DIEM lines: `days * flat rate`. */
    fun legacyPerDiem(
        days: Int,
        rates: TravelExpenseRateSet,
    ): CalcResult<Long> {
        val rate = rates.legacyPerDiemCents ?: return fail(key = "perDiemRate", code = "per_diem_rate_not_configured")
        if (days < 1) return fail(key = "days", code = "days_out_of_range")
        val total = mulExact(a = rate, b = days.toLong()) ?: return fail(key = "days", code = "amount_overflow")
        return CalcResult.Ok(total)
    }

    /**
     * For every overnight stay (first night date, number of nights) the breakfast of the mornings
     * `d+1 .. d+n` is already provided by the hotel.
     */
    fun forcedBreakfastDates(accommodations: List<Pair<LocalDate, Int>>): Set<LocalDate> {
        val out = mutableSetOf<LocalDate>()
        for ((first, nights) in accommodations) {
            for (i in 1..nights) out += first.plus(i, DateTimeUnit.DAY)
        }
        return out
    }

    /**
     * @param lineNetCents the net amount of EVERY line (day lines already net of their meal deduction)
     * @param deductionCents the meal deductions (informational split of what was taken off)
     */
    fun totals(
        lineNetCents: List<Long>,
        deductionCents: List<Long>,
        advanceCents: Long,
    ): Totals {
        val net = lineNetCents.sum()
        val deduction = deductionCents.sum()
        val payout = maxOf(0L, net - advanceCents)
        return Totals(
            grossCents = net + deduction,
            deductionCents = deduction,
            subtotalCents = net,
            advanceCents = advanceCents,
            payoutCents = payout,
            advanceExceedsTotal = advanceCents > net,
        )
    }

    data class Totals(
        val grossCents: Long,
        val deductionCents: Long,
        val subtotalCents: Long,
        val advanceCents: Long,
        val payoutCents: Long,
        val advanceExceedsTotal: Boolean,
    )
}

/** Submission-deadline hint (31 December of the travel year). Informational, never blocks. */
object TravelExpenseDeadline {
    fun deadlineFor(travelTo: LocalDate): LocalDate = LocalDate(travelTo.year, 12, 31)

    fun isOverdue(
        travelTo: LocalDate,
        today: LocalDate,
    ): Boolean = today > deadlineFor(travelTo)
}

/** Whole-cent <-> decimal-string helpers that behave identically on JVM and JS. */
object CentsConversion {
    /** `1234` -> `"12.34"`, `5` -> `"0.05"`. */
    fun toDecimalString(cents: Long): String {
        val abs = if (cents < 0) -cents else cents
        val sign = if (cents < 0) "-" else ""
        val whole = abs / 100
        val frac = (abs % 100).toString().padStart(2, '0')
        return "$sign$whole.$frac"
    }

    /** Parses `"12.34"`/`"12.3"`/`"12"` exactly; `null` when it has more than 2 fractional digits or is malformed. */
    fun parseExact(text: String): Long? {
        val t = text.trim()
        if (t.isEmpty()) return null
        val negative = t.startsWith("-")
        val body = if (negative) t.substring(1) else t
        val parts = body.split('.')
        if (parts.size > 2) return null
        val whole = parts[0].ifEmpty { "0" }
        val frac = if (parts.size == 2) parts[1] else ""
        if (frac.length > 2) return null
        if (!whole.all { it in '0'..'9' } || !frac.all { it in '0'..'9' }) return null
        if (whole.length > 15) return null
        val cents = whole.toLong() * 100 + frac.padEnd(2, '0').toLong()
        return if (negative) -cents else cents
    }
}
