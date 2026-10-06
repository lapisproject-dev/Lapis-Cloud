package network.lapis.cloud.client.encounter

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** V1.9.62 -- the stable pew plan: nobody ever moves, new people fill the first free seat. */
class EncounterSeatingTest {
    @Test
    fun theFillOrder_alternatesBetweenTheTwoBlocks_frontToBack() {
        val seating = EncounterSeating()
        // row 0: left block seat 0, right block seat 3, left 1, right 4, left 2, right 5
        val seats = (0 until 6).map { seating.assign("p$it") }
        assertEquals(listOf(0, 3, 1, 4, 2, 5), seats)
        assertEquals(6, seating.assign("p6"), "the second row starts at seat 6")
    }

    @Test
    fun aLeavingPerson_leavesAnEmptySeat_andNobodyMoves() {
        val seating = EncounterSeating()
        val first = (0 until 6).associate { "p$it" to seating.assign("p$it") }
        seating.release("p1")
        first.filterKeys { it != "p1" }.forEach { (id, seat) -> assertEquals(seat, seating.seatOf(id), "$id must not move") }
        assertNull(seating.seatOf("p1"))
        assertNull(seating.occupantOf(first.getValue("p1")))
        // the next arrival takes the first FREE seat in the fill order -- the gap, not a new seat at the back
        assertEquals(first.getValue("p1"), seating.assign("newcomer"))
    }

    @Test
    fun aKnownPerson_keepsTheSeat_whenAssignedAgain() {
        val seating = EncounterSeating()
        val seat = seating.assign("anna")
        assertEquals(seat, seating.assign("anna"))
        assertEquals(1, (0 until seating.seatCount).count { seating.occupantOf(it) == "anna" })
    }

    @Test
    fun whenAllSeatsAreTaken_theRoomGrowsByOneRowOfSix_andNeverShrinks() {
        val seating = EncounterSeating()
        assertEquals(24, seating.seatCount)
        (0 until 24).forEach { seating.assign("p$it") }
        assertEquals(24, seating.seatCount)
        val extra = seating.assign("p24")
        assertEquals(30, seating.seatCount)
        assertTrue(extra in 24 until 30, "the new seat is in the new row")
        (0 until 25).forEach { seating.release("p$it") }
        assertEquals(30, seating.seatCount, "the plan never shrinks")
    }

    @Test
    fun noSeatIsEverGivenTwice() {
        val seating = EncounterSeating()
        val seats = (0 until 40).map { seating.assign("p$it") }
        assertEquals(40, seats.toSet().size)
        assertNotEquals(seats[0], seats[1])
    }

    @Test
    fun initials_areLettersOnly_twoCharacters_upperCase() {
        assertEquals("AK", encounterInitials("Anna Keller"))
        assertEquals("AM", encounterInitials("anna maria keller"))
        assertEquals("BE", encounterInitials("Ben"))
        assertEquals("X", encounterInitials("X"))
        assertEquals("ÄÖ", encounterInitials("Änne Özdemir"))
        assertEquals("?", encounterInitials(""))
        assertEquals("?", encounterInitials("  123 ---  "))
        assertTrue(encounterInitials("<img src=x onerror=1>").all { it.isLetter() || it == '?' })
    }
}
