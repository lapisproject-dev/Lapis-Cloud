package network.lapis.cloud.client.encounter

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** V1.9.159 -- the seats of a table stand on a ring: first seat at 12 o'clock, clockwise, evenly spaced, never closer than a seat's width. */
class EncounterTableRingTest {
    @Test
    fun firstSeatIsAtTwelveOClockAndTheNextOnesGoClockwise() {
        assertEquals(0.0 to -1.0, encounterTableRingUnit(0, 4))
        assertEquals(1.0 to 0.0, encounterTableRingUnit(1, 4))
        assertEquals(0.0 to 1.0, encounterTableRingUnit(2, 4))
        assertEquals(-1.0 to 0.0, encounterTableRingUnit(3, 4))
    }

    @Test
    fun everySeatLiesOnTheUnitCircle() {
        for (seats in 2..8) {
            for (seat in 0 until seats) {
                val (x, y) = encounterTableRingUnit(seat, seats)
                assertTrue(abs(x * x + y * y - 1.0) < 0.001, "seat $seat of $seats off the ring")
            }
        }
    }

    @Test
    fun neighbouringSeatsOfTheLargestTableDoNotTouchInTheSmallestCard() {
        // Card 172 px: ring box 172 - 4 border = 168 px, ring radius (168 - 44) / 2 = 62 px; chord of 8 seats must be at least a seat (44 px).
        val radius = 62.0
        val (x0, y0) = encounterTableRingUnit(0, 8)
        val (x1, y1) = encounterTableRingUnit(1, 8)
        val chord = radius * kotlin.math.sqrt((x1 - x0) * (x1 - x0) + (y1 - y0) * (y1 - y0))
        assertTrue(chord >= 44.0, "chord $chord px")
    }

    @Test
    fun aSingleSeatDoesNotDivideByZero() {
        assertEquals(0.0 to -1.0, encounterTableRingUnit(0, 0))
    }
}
