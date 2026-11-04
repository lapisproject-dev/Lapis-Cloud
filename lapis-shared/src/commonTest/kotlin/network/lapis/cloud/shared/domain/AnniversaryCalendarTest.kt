package network.lapis.cloud.shared.domain

import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.minus
import kotlinx.datetime.periodUntil
import kotlinx.datetime.plus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Welle V1.4.4.2 "Geburtstage & Jubiläen" -- pure calendar-arithmetic tests, deliberately without
 * any DB/Ktor scaffolding (see [AnniversaryCalendar] KDoc). The year-wraparound and leap-day cases
 * are the two findings the Design-Team review named explicitly.
 */
class AnniversaryCalendarTest {
    @Test
    fun nextOccurrence_yearWraparound_findsNextYearOccurrenceInsteadOfNone() {
        val anniversary = LocalDate(1990, 12, 20)
        val today = LocalDate(2026, 12, 20)
        val next = AnniversaryCalendar.nextOccurrence(anniversary = anniversary, today = today)
        assertEquals(LocalDate(2026, 12, 20), next)
    }

    @Test
    fun nextOccurrence_dayAfterAnniversary_wrapsToFollowingYear() {
        val anniversary = LocalDate(1990, 12, 20)
        val today = LocalDate(2026, 12, 21)
        val next = AnniversaryCalendar.nextOccurrence(anniversary = anniversary, today = today)
        assertEquals(LocalDate(2027, 12, 20), next)
    }

    @Test
    fun nextOccurrence_todayIsInclusive() {
        val anniversary = LocalDate(1990, 5, 10)
        val today = LocalDate(2026, 5, 10)
        assertEquals(today, AnniversaryCalendar.nextOccurrence(anniversary = anniversary, today = today))
    }

    @Test
    fun nextOccurrence_leapDayAnniversary_inLeapYear_staysFeb29() {
        val anniversary = LocalDate(1992, 2, 29)
        // 2028 is the next leap year from 2026.
        val next = AnniversaryCalendar.nextOccurrence(anniversary = anniversary, today = LocalDate(2028, 1, 1))
        assertEquals(LocalDate(2028, 2, 29), next)
    }

    @Test
    fun nextOccurrence_leapDayAnniversary_inNonLeapYear_shiftsToFeb28() {
        val anniversary = LocalDate(1992, 2, 29)
        val today = LocalDate(2026, 1, 1)
        val next = AnniversaryCalendar.nextOccurrence(anniversary = anniversary, today = today)
        assertEquals(LocalDate(2026, 2, 28), next)
        assertTrue(AnniversaryCalendar.isLeapDayAnniversary(anniversary))
    }

    @Test
    fun isLeapDayAnniversary_isFalseForOrdinaryDate() {
        assertFalse(AnniversaryCalendar.isLeapDayAnniversary(LocalDate(1990, 3, 1)))
    }

    @Test
    fun isMilestoneYear_onesFivesAndMultiplesOfFiveAreMilestones() {
        assertTrue(AnniversaryCalendar.isMilestoneYear(1))
        assertTrue(AnniversaryCalendar.isMilestoneYear(5))
        assertTrue(AnniversaryCalendar.isMilestoneYear(10))
        assertTrue(AnniversaryCalendar.isMilestoneYear(25))
    }

    @Test
    fun isMilestoneYear_nonMultiplesAndNonPositiveAreNot() {
        assertFalse(AnniversaryCalendar.isMilestoneYear(2))
        assertFalse(AnniversaryCalendar.isMilestoneYear(3))
        assertFalse(AnniversaryCalendar.isMilestoneYear(4))
        assertFalse(AnniversaryCalendar.isMilestoneYear(6))
        assertFalse(AnniversaryCalendar.isMilestoneYear(0))
        assertFalse(AnniversaryCalendar.isMilestoneYear(-5))
    }

    @Test
    fun milestoneEmphasis_oneIsFirstYear() {
        assertEquals(AnniversaryEmphasis.FIRST_YEAR, AnniversaryCalendar.milestoneEmphasis(1))
    }

    @Test
    fun milestoneEmphasis_multiplesOf25AreMajor() {
        assertEquals(AnniversaryEmphasis.MAJOR, AnniversaryCalendar.milestoneEmphasis(25))
        assertEquals(AnniversaryEmphasis.MAJOR, AnniversaryCalendar.milestoneEmphasis(50))
        assertEquals(AnniversaryEmphasis.MAJOR, AnniversaryCalendar.milestoneEmphasis(75))
    }

    @Test
    fun milestoneEmphasis_multiplesOf10NotOf25AreNotable() {
        assertEquals(AnniversaryEmphasis.NOTABLE, AnniversaryCalendar.milestoneEmphasis(10))
        assertEquals(AnniversaryEmphasis.NOTABLE, AnniversaryCalendar.milestoneEmphasis(20))
        assertEquals(AnniversaryEmphasis.NOTABLE, AnniversaryCalendar.milestoneEmphasis(30))
    }

    @Test
    fun milestoneEmphasis_fiveAndFifteenAreStandard() {
        assertEquals(AnniversaryEmphasis.STANDARD, AnniversaryCalendar.milestoneEmphasis(5))
        assertEquals(AnniversaryEmphasis.STANDARD, AnniversaryCalendar.milestoneEmphasis(15))
    }

    @Test
    fun milestoneEmphasis_nonPositiveYearsAreStandard() {
        // years<=0 only ever reaches milestoneEmphasis via a data-error path (e.g. a birth date
        // mistakenly imported in the future, see MemberAnniversaryServiceTest) -- must never be
        // reported as MAJOR just because "0 % 25 == 0" is arithmetically true.
        assertEquals(AnniversaryEmphasis.STANDARD, AnniversaryCalendar.milestoneEmphasis(0))
        assertEquals(AnniversaryEmphasis.STANDARD, AnniversaryCalendar.milestoneEmphasis(-1))
        assertEquals(AnniversaryEmphasis.STANDARD, AnniversaryCalendar.milestoneEmphasis(-25))
    }

    @Test
    fun yearsBetween_simpleDifference() {
        assertEquals(
            10,
            AnniversaryCalendar.yearsBetween(anniversary = LocalDate(2016, 3, 1), onDate = LocalDate(2026, 3, 1)),
        )
    }

    @Test
    fun yearsBetween_exactlyTodayIsAlreadyXYearsAgo() {
        assertEquals(
            36,
            AnniversaryCalendar.yearsBetween(anniversary = LocalDate(1990, 3, 1), onDate = LocalDate(2026, 3, 1)),
        )
    }

    @Test
    fun yearsBetween_oneDayBeforeAnniversary_isOneLess() {
        assertEquals(
            35,
            AnniversaryCalendar.yearsBetween(anniversary = LocalDate(1990, 3, 1), onDate = LocalDate(2026, 2, 28)),
        )
    }

    private fun years(
        anniversary: LocalDate,
        onDate: LocalDate,
    ) = AnniversaryCalendar.yearsBetween(anniversary = anniversary, onDate = onDate)

    @Test
    fun yearsBetween_leapDayAnniversary_matrix() {
        val a = LocalDate(2000, 2, 29)
        assertEquals(25, years(anniversary = a, onDate = LocalDate(2025, 2, 28)))
        assertEquals(24, years(anniversary = a, onDate = LocalDate(2025, 2, 27)))
        assertEquals(25, years(anniversary = a, onDate = LocalDate(2025, 3, 1)))
        assertEquals(23, years(anniversary = a, onDate = LocalDate(2024, 2, 28)))
        assertEquals(24, years(anniversary = a, onDate = LocalDate(2024, 2, 29)))
        assertEquals(1, years(anniversary = a, onDate = LocalDate(2001, 2, 28)))
        assertEquals(0, years(anniversary = a, onDate = LocalDate(2001, 2, 27)))
        assertEquals(3, years(anniversary = a, onDate = LocalDate(2004, 2, 28)))
        assertEquals(4, years(anniversary = a, onDate = LocalDate(2004, 2, 29)))
        assertEquals(100, years(anniversary = a, onDate = LocalDate(2100, 2, 28)))
        assertEquals(400, years(anniversary = a, onDate = LocalDate(2400, 2, 29)))
        assertEquals(4, years(anniversary = LocalDate(1896, 2, 29), onDate = LocalDate(1900, 2, 28)))
    }

    @Test
    fun yearsBetween_ordinaryDates_matchPeriodUntil() {
        val anniversaries =
            listOf(LocalDate(2000, 2, 28), LocalDate(2000, 3, 1), LocalDate(1990, 12, 31), LocalDate(1995, 1, 31))
        for (a in anniversaries) {
            var d = a.plus(DatePeriod(days = 1))
            val end = a.plus(DatePeriod(years = 30))
            while (d <= end) {
                assertEquals(a.periodUntil(d).years, years(anniversary = a, onDate = d), "anniversary=$a onDate=$d")
                d = d.plus(DatePeriod(days = 1))
            }
        }
    }

    @Test
    fun yearsBetween_nonPositive_keepsPeriodUntilBehaviour() {
        val a = LocalDate(2026, 2, 28)
        assertEquals(0, years(anniversary = a, onDate = a))
        val future = LocalDate(2030, 3, 1)
        assertEquals(future.periodUntil(a).years, years(anniversary = future, onDate = a))
        assertTrue(years(anniversary = future, onDate = a) < 0)
        assertEquals(0, years(anniversary = LocalDate(2026, 3, 1), onDate = LocalDate(2026, 2, 28)))
    }

    @Test
    fun yearsBetween_leapDayJoiner_reachesMilestonesOnShiftedDate() {
        for (a in listOf(LocalDate(2000, 2, 29), LocalDate(2020, 2, 29))) {
            for (n in listOf(1, 5, 10)) {
                val d = AnniversaryCalendar.nthAnniversary(anniversary = a, years = n)
                assertEquals(n, years(anniversary = a, onDate = d), "anniversary=$a n=$n")
                assertTrue(AnniversaryCalendar.isMilestoneYear(years(anniversary = a, onDate = d)))
            }
        }
        assertEquals(1, years(anniversary = LocalDate(2024, 2, 29), onDate = LocalDate(2025, 2, 28)))
    }

    @Test
    fun yearsBetween_nthAnniversaryProperty_andMonotonicAroundOccurrence() {
        val anniversaries =
            listOf(
                LocalDate(2000, 2, 29),
                LocalDate(1996, 2, 29),
                LocalDate(2000, 3, 1),
                LocalDate(2000, 12, 31),
                LocalDate(2000, 2, 28),
            )
        for (a in anniversaries) {
            for (n in 1..200) {
                val occ = AnniversaryCalendar.nthAnniversary(anniversary = a, years = n)
                assertEquals(n, years(anniversary = a, onDate = occ), "anniversary=$a n=$n")
                val before = years(anniversary = a, onDate = occ.minus(DatePeriod(days = 1)))
                val after = years(anniversary = a, onDate = occ.plus(DatePeriod(days = 1)))
                assertEquals(n - 1, before, "day before: anniversary=$a n=$n")
                assertTrue(before <= n && n <= after, "monotonic: anniversary=$a n=$n")
            }
        }
    }

    @Test
    fun windowEnd_addsDaysToToday() {
        assertEquals(
            LocalDate(2026, 1, 31),
            AnniversaryCalendar.windowEnd(today = LocalDate(2026, 1, 1), windowDays = 30),
        )
    }

    // ── Welle V1.4.4.4 "Familienmitgliedschaften" -- nthAnniversary ─────────────────────────────

    @Test
    fun nthAnniversary_normalCase_addsYearsToBirthdate() {
        assertEquals(
            LocalDate(2026, 3, 1),
            AnniversaryCalendar.nthAnniversary(anniversary = LocalDate(2008, 3, 1), years = 18),
        )
    }

    @Test
    fun nthAnniversary_monthEnd_staysOnSameDay() {
        assertEquals(
            LocalDate(2026, 1, 31),
            AnniversaryCalendar.nthAnniversary(anniversary = LocalDate(2008, 1, 31), years = 18),
        )
    }

    @Test
    fun nthAnniversary_leapDayBirthdate_inLeapTargetYear_staysFeb29() {
        // 2008 is a leap year, +20 years = 2028, also a leap year.
        assertEquals(
            LocalDate(2028, 2, 29),
            AnniversaryCalendar.nthAnniversary(anniversary = LocalDate(2008, 2, 29), years = 20),
        )
    }

    @Test
    fun nthAnniversary_leapDayBirthdate_inNonLeapTargetYear_shiftsToFeb28() {
        // 2008 is a leap year, +18 years = 2026, NOT a leap year.
        assertEquals(
            LocalDate(2026, 2, 28),
            AnniversaryCalendar.nthAnniversary(anniversary = LocalDate(2008, 2, 29), years = 18),
        )
    }
}
