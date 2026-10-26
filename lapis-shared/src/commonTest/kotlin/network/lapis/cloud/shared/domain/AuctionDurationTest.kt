package network.lapis.cloud.shared.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** V1.9.222 -- the duration conversion of the auction create form; runs on the JVM and on JS (it is the only source of the bound for
 * both).
 * */
class AuctionDurationTest {
    @Test
    fun theBoundIsUnchanged() {
        assertEquals(1, AuctionDurationRules.MIN_HOURS)
        assertEquals(2160, AuctionDurationRules.MAX_HOURS)
    }

    @Test
    fun oneWeekIs168HoursAndTwelveWeeksFit() {
        assertEquals(168, durationToHours(amount = 1, unit = DurationUnit.WEEKS))
        assertEquals(2016, durationToHours(amount = 12, unit = DurationUnit.WEEKS))
    }

    @Test
    fun thirteenWeeksAreRefusedNotShortened() {
        assertNull(durationToHours(amount = 13, unit = DurationUnit.WEEKS)) // 2184 h > 2160 h
    }

    @Test
    fun ninetyDaysFitAndNinetyOneDoNot() {
        assertEquals(2160, durationToHours(amount = 90, unit = DurationUnit.DAYS))
        assertNull(durationToHours(amount = 91, unit = DurationUnit.DAYS))
    }

    @Test
    fun hoursUpToTheMaximum() {
        assertEquals(1, durationToHours(amount = 1, unit = DurationUnit.HOURS))
        assertEquals(2160, durationToHours(amount = 2160, unit = DurationUnit.HOURS))
        assertNull(durationToHours(amount = 2161, unit = DurationUnit.HOURS))
    }

    @Test
    fun zeroNegativeAndOverflowAreNull() {
        assertNull(durationToHours(amount = 0, unit = DurationUnit.DAYS))
        assertNull(durationToHours(amount = -1, unit = DurationUnit.HOURS))
        assertNull(durationToHours(amount = Int.MAX_VALUE, unit = DurationUnit.WEEKS))
        assertNull(durationToHours(amount = Int.MIN_VALUE, unit = DurationUnit.HOURS))
    }

    @Test
    fun maxAmountPerUnit() {
        assertEquals(2160, maxAmount(DurationUnit.HOURS))
        assertEquals(90, maxAmount(DurationUnit.DAYS))
        assertEquals(12, maxAmount(DurationUnit.WEEKS))
    }

    @Test
    fun everyAmountUpToTheMaximumConvertsAndTheNextOneDoesNot() {
        for (unit in DurationUnit.entries) {
            val max = maxAmount(unit)
            assertEquals(max * unit.hours, durationToHours(amount = max, unit = unit))
            assertNull(durationToHours(amount = max + 1, unit = unit))
        }
    }
}
