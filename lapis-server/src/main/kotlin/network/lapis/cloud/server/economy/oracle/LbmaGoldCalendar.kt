package network.lapis.cloud.server.economy.oracle

import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.Month
import kotlinx.datetime.minus
import kotlinx.datetime.plus
import network.lapis.cloud.server.payment.sepa.Target2Calendar
import network.lapis.cloud.shared.domain.ReferenceDateFallback

/**
 * V1.9.324 -- the trading days of the London gold market (LBMA), used to move a reference date that falls on a weekend or a bank holiday back
 * to the last trading day. Pure calendar arithmetic, no state, no network.
 *
 * **Holidays**: the bank holidays of England and Wales -- New Year's Day, Good Friday, Easter Monday (Easter via [Target2Calendar.easterSunday],
 * not duplicated), the early May bank holiday (first Monday of May), the spring bank holiday (last Monday of May), the summer bank holiday (last
 * Monday of August), Christmas Day and Boxing Day, each with the usual substitute day when it falls on a weekend.
 *
 * **One-off changes** ([ONE_OFF_ADDED], [ONE_OFF_REMOVED]): the government moved or added a few bank holidays for royal events. Without this table
 * the calendar would silently pick the wrong day (for example 4 May 2020 was a trading day, 8 May 2020 was not). Source: the official list at
 * gov.uk/bank-holidays (read once, nothing is fetched at run time). **A future one-off closure needs a code update here** -- the calendar does not
 * know it, so the rate of that day would not be found and the treasurer would have to confirm a rate by hand (never a wrong automatic rate).
 */
internal object LbmaGoldCalendar {
    /** The most days the search for a trading day goes back (a long weekend plus a holiday never comes close to this). */
    private const val MAX_LOOKBACK_DAYS = 10

    private val ONE_OFF_ADDED: Set<LocalDate> =
        setOf(
            LocalDate(2002, Month.JUNE, 3), // Golden Jubilee
            LocalDate(2002, Month.JUNE, 4), // spring bank holiday moved here
            LocalDate(2011, Month.APRIL, 29), // royal wedding
            LocalDate(2012, Month.JUNE, 4), // spring bank holiday moved here
            LocalDate(2012, Month.JUNE, 5), // Diamond Jubilee
            LocalDate(2020, Month.MAY, 8), // VE Day, early May bank holiday moved here
            LocalDate(2022, Month.JUNE, 2), // spring bank holiday moved here
            LocalDate(2022, Month.JUNE, 3), // Platinum Jubilee
            LocalDate(2022, Month.SEPTEMBER, 19), // state funeral
            LocalDate(2023, Month.MAY, 8), // coronation
        )

    private val ONE_OFF_REMOVED: Set<LocalDate> =
        setOf(
            LocalDate(2002, Month.MAY, 27), // spring bank holiday moved to 4 June
            LocalDate(2012, Month.MAY, 28), // spring bank holiday moved to 4 June
            LocalDate(2020, Month.MAY, 4), // early May bank holiday moved to 8 May
            LocalDate(2022, Month.MAY, 30), // spring bank holiday moved to 2 June
        )

    fun isTradingDay(date: LocalDate): Boolean = !isWeekend(date) && date !in holidays(date.year)

    /**
     * [date] itself if it is a trading day (reason NONE), otherwise the last trading day before it. The reason is that of [date] itself: WEEKEND on
     * a Saturday or Sunday, HOLIDAY on a bank holiday.
     */
    fun previousTradingDayOnOrBefore(date: LocalDate): Pair<LocalDate, ReferenceDateFallback> {
        if (isTradingDay(date)) return date to ReferenceDateFallback.NONE
        val reason = if (isWeekend(date)) ReferenceDateFallback.WEEKEND else ReferenceDateFallback.HOLIDAY
        var candidate = date
        repeat(MAX_LOOKBACK_DAYS) {
            candidate = candidate.minus(1, DateTimeUnit.DAY)
            if (isTradingDay(candidate)) return candidate to reason
        }
        error("No LBMA trading day within $MAX_LOOKBACK_DAYS days before $date -- calendar table is wrong")
    }

    private fun isWeekend(date: LocalDate): Boolean = date.dayOfWeek == DayOfWeek.SATURDAY || date.dayOfWeek == DayOfWeek.SUNDAY

    private fun holidays(year: Int): Set<LocalDate> {
        val easter = Target2Calendar.easterSunday(year)
        val regular =
            setOf(
                newYearsDay(year),
                easter.minus(2, DateTimeUnit.DAY), // Good Friday
                easter.plus(1, DateTimeUnit.DAY), // Easter Monday
                firstMonday(year = year, month = Month.MAY),
                lastMonday(year = year, month = Month.MAY),
                lastMonday(year = year, month = Month.AUGUST),
                christmasDay(year),
                boxingDay(year),
            )
        return (regular - ONE_OFF_REMOVED) + ONE_OFF_ADDED.filter { it.year == year }
    }

    private fun newYearsDay(year: Int): LocalDate {
        val first = LocalDate(year, Month.JANUARY, 1)
        return when (first.dayOfWeek) {
            DayOfWeek.SATURDAY -> first.plus(2, DateTimeUnit.DAY)
            DayOfWeek.SUNDAY -> first.plus(1, DateTimeUnit.DAY)
            else -> first
        }
    }

    /** 25 December, or on a weekend the Monday (Saturday) / Tuesday (Sunday, because Boxing Day takes the Monday) that follows. */
    private fun christmasDay(year: Int): LocalDate {
        val day = LocalDate(year, Month.DECEMBER, 25)
        return if (isWeekend(day)) LocalDate(year, Month.DECEMBER, 27) else day
    }

    /** 26 December, or on a weekend the 28th (Monday after a Saturday 26th is the 28th, Tuesday after a Sunday 26th is the 28th). */
    private fun boxingDay(year: Int): LocalDate {
        val day = LocalDate(year, Month.DECEMBER, 26)
        return if (isWeekend(day)) LocalDate(year, Month.DECEMBER, 28) else day
    }

    private fun firstMonday(
        year: Int,
        month: Month,
    ): LocalDate {
        var day = LocalDate(year, month, 1)
        while (day.dayOfWeek != DayOfWeek.MONDAY) day = day.plus(1, DateTimeUnit.DAY)
        return day
    }

    private fun lastMonday(
        year: Int,
        month: Month,
    ): LocalDate {
        var day = LocalDate(year, month, 1).plus(1, DateTimeUnit.MONTH).minus(1, DateTimeUnit.DAY)
        while (day.dayOfWeek != DayOfWeek.MONDAY) day = day.minus(1, DateTimeUnit.DAY)
        return day
    }
}
