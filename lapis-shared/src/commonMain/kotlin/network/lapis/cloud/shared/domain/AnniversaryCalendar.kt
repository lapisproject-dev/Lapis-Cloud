package network.lapis.cloud.shared.domain

import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.Month
import kotlinx.datetime.periodUntil
import kotlinx.datetime.plus
import kotlinx.serialization.Serializable

/**
 * Welle V1.4.4.2 "Geburtstage & Jubiläen" -- reine Kalenderarithmetik, absichtlich OHNE jede
 * Datenbank-/Ktor-Abhängigkeit (Atkinson/Kay im Design-Review: Jahreswechsel-Bug und 29.02.-Fall
 * gehören in einen Vier-Zeilen-Unit-Test, nicht in einen Testcontainer-Integrationstest; siehe
 * `DbClock` KDoc für die bereits einmal erlittene Datums-/Dialekt-Lehre dieser Codebase).
 */
object AnniversaryCalendar {
    const val DEFAULT_WINDOW_DAYS = 30
    const val MAX_WINDOW_DAYS = 90
    val WINDOW_PRESETS = listOf(30, 60, 90)

    /**
     * Nächstes tatsächliches Vorkommen von [anniversary] (nur Monat/Tag zählen) als echtes Datum,
     * IMMER >= [today] -- heute zählt (Norman: "heute" ist eine Handlungsaufforderung, keine
     * Ausnahme). Ein 29.02.-Jubiläum fällt in einem Nicht-Schaltjahr auf den 28.02. (Raskin: "wir
     * berechnen keine Rechtsfrist"), niemals auf den 1. März.
     */
    fun nextOccurrence(
        anniversary: LocalDate,
        today: LocalDate,
    ): LocalDate {
        val thisYear = occurrenceInYear(anniversary = anniversary, year = today.year)
        return if (thisYear >= today) thisYear else occurrenceInYear(anniversary = anniversary, year = today.year + 1)
    }

    private fun occurrenceInYear(
        anniversary: LocalDate,
        year: Int,
    ): LocalDate {
        val isLeapDay = anniversary.month == Month.FEBRUARY && anniversary.day == 29
        return if (isLeapDay && !isLeapYear(year)) {
            LocalDate(year, Month.FEBRUARY, 28)
        } else {
            LocalDate(year, anniversary.month, anniversary.day)
        }
    }

    private fun isLeapYear(year: Int): Boolean = (year % 4 == 0 && year % 100 != 0) || year % 400 == 0

    fun isLeapDayAnniversary(anniversary: LocalDate): Boolean = anniversary.month == Month.FEBRUARY && anniversary.day == 29

    /** Erreichte Jahre zwischen [anniversary] und [onDate] -- ganzzahlige, kalendarisch korrekte Differenz. */
    fun yearsBetween(
        anniversary: LocalDate,
        onDate: LocalDate,
    ): Int = anniversary.periodUntil(onDate).years

    /** 1 Jahr ("erstes Jahr", kein "Jubiläum" im eigentlichen Sinn, siehe Zhuo/Rams) oder jedes 5. Jahr. */
    fun isMilestoneYear(years: Int): Boolean = years == 1 || (years > 0 && years % 5 == 0)

    fun milestoneEmphasis(years: Int): AnniversaryEmphasis =
        when {
            years <= 0 -> AnniversaryEmphasis.STANDARD
            years == 1 -> AnniversaryEmphasis.FIRST_YEAR
            years % 25 == 0 -> AnniversaryEmphasis.MAJOR
            years % 10 == 0 -> AnniversaryEmphasis.NOTABLE
            else -> AnniversaryEmphasis.STANDARD
        }

    /** [windowDays] Tage nach [today], beide Enden inklusive -- Wrapper um `LocalDate.plus(DatePeriod)`. */
    fun windowEnd(
        today: LocalDate,
        windowDays: Int,
    ): LocalDate = today.plus(DatePeriod(days = windowDays))
}

@Serializable
enum class AnniversaryEmphasis { STANDARD, FIRST_YEAR, NOTABLE, MAJOR }
