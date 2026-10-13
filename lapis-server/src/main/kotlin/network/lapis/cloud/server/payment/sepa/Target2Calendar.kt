package network.lapis.cloud.server.payment.sepa

import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.Month
import kotlinx.datetime.plus

/**
 * Welle V1.9.123 -- the TARGET2 (T2) closing days that matter for the requested execution date of a SEPA credit transfer.
 * Saturday, Sunday, 1 January, Good Friday, Easter Monday, 1 May, 25 December and 26 December. Pure calendar arithmetic, no
 * state. An execution date on any other day is "open"; the export never moves a date silently, it refuses a closed one.
 */
object Target2Calendar {
    fun isOpen(date: LocalDate): Boolean {
        if (date.dayOfWeek == DayOfWeek.SATURDAY || date.dayOfWeek == DayOfWeek.SUNDAY) return false
        return date !in closingHolidays(date.year)
    }

    /** The first open day strictly after [date]. */
    fun nextOpenDayAfter(date: LocalDate): LocalDate {
        var candidate = date.plus(1, DateTimeUnit.DAY)
        while (!isOpen(candidate)) candidate = candidate.plus(1, DateTimeUnit.DAY)
        return candidate
    }

    private fun closingHolidays(year: Int): Set<LocalDate> {
        val easter = easterSunday(year)
        return setOf(
            LocalDate(year, Month.JANUARY, 1),
            easter.plus(-2, DateTimeUnit.DAY),
            easter.plus(1, DateTimeUnit.DAY),
            LocalDate(year, Month.MAY, 1),
            LocalDate(year, Month.DECEMBER, 25),
            LocalDate(year, Month.DECEMBER, 26),
        )
    }

    /** Anonymous Gregorian algorithm (Meeus/Jones/Butcher). */
    @Suppress("MagicNumber")
    internal fun easterSunday(year: Int): LocalDate {
        val a = year % 19
        val b = year / 100
        val c = year % 100
        val d = b / 4
        val e = b % 4
        val f = (b + 8) / 25
        val g = (b - f + 1) / 3
        val h = (19 * a + b - d - g + 15) % 30
        val i = c / 4
        val k = c % 4
        val l = (32 + 2 * e + 2 * i - h - k) % 7
        val m = (a + 11 * h + 22 * l) / 451
        val month = (h + l - 7 * m + 114) / 31
        val day = (h + l - 7 * m + 114) % 31 + 1
        return LocalDate(year, month, day)
    }
}
