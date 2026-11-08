package network.lapis.cloud.shared.domain

import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** V1.9.291 (R6) -- the deadline levels at their borders, the proposal, the valid range and the strict ISO parser (client and server use the same rules). */
class PartgDeadlineRulesTest {
    private val deadline = LocalDate(2027, 9, 30)

    private fun level(
        daysBefore: Int,
        final: Boolean = false,
    ): PartgDeadlineLevel {
        // today = deadline minus daysBefore days (negative = after the deadline)
        val today = LocalDate.fromEpochDays(deadline.toEpochDays() - daysBefore)
        return PartgDeadlineRules.level(today = today, deadline = deadline, final = final)
    }

    @Test
    fun theProposalIsThe30thOfSeptemberOfTheYearAfter() {
        assertEquals(LocalDate(2027, 9, 30), PartgDeadlineRules.proposal(2026))
    }

    @Test
    fun theLevelsAtTheirBorders() {
        assertEquals(PartgDeadlineLevel.NONE, level(daysBefore = 91))
        assertEquals(PartgDeadlineLevel.INFO, level(daysBefore = 90))
        assertEquals(PartgDeadlineLevel.INFO, level(daysBefore = 31))
        assertEquals(PartgDeadlineLevel.WARNING, level(daysBefore = 30))
        assertEquals(PartgDeadlineLevel.WARNING, level(daysBefore = 1))
        assertEquals(PartgDeadlineLevel.WARNING, level(daysBefore = 0))
        assertEquals(PartgDeadlineLevel.DANGER, level(daysBefore = -1))
        assertEquals(PartgDeadlineLevel.DANGER, level(daysBefore = -400))
    }

    @Test
    fun aFinalYearIsNeverLoud() {
        assertEquals(PartgDeadlineLevel.NONE, level(daysBefore = -1, final = true))
        assertEquals(PartgDeadlineLevel.NONE, level(daysBefore = 0, final = true))
    }

    @Test
    fun daysUntilIsNegativeWhenOverdue() {
        assertEquals(-1, PartgDeadlineRules.daysUntil(today = LocalDate(2027, 10, 1), deadline = deadline))
        assertEquals(0, PartgDeadlineRules.daysUntil(today = deadline, deadline = deadline))
    }

    @Test
    fun theValidRangeIsFirstOfJanuaryOfTheNextYearToTheEndOfTheThirdYear() {
        assertTrue(PartgDeadlineRules.valid(year = 2026, deadline = LocalDate(2027, 1, 1)))
        assertTrue(PartgDeadlineRules.valid(year = 2026, deadline = LocalDate(2029, 12, 31)))
        assertFalse(PartgDeadlineRules.valid(year = 2026, deadline = LocalDate(2026, 12, 31)))
        assertFalse(PartgDeadlineRules.valid(year = 2026, deadline = LocalDate(2030, 1, 1)))
    }

    @Test
    fun isoParsingIsStrict() {
        assertEquals(LocalDate(2027, 9, 30), PartgDeadlineRules.parseIso("2027-09-30"))
        assertNull(PartgDeadlineRules.parseIso("2027-9-30"))
        assertNull(PartgDeadlineRules.parseIso("2027-02-30"))
        assertNull(PartgDeadlineRules.parseIso(" 2027-09-30"))
        assertNull(PartgDeadlineRules.parseIso("30.09.2027"))
        assertNull(PartgDeadlineRules.parseIso(""))
    }
}
