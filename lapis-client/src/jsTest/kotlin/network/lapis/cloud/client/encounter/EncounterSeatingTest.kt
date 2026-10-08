package network.lapis.cloud.client.encounter

import network.lapis.cloud.shared.domain.EncounterPresenceRole
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * V1.9.79 -- the seat plan is the SERVER's picture. This replaces the B2 tests of the local fill order ("assign", "release", "the plan
 * grows by one row"): the room no longer assigns anything, so there is nothing to fill or to release locally.
 */
class EncounterSeatingTest {
    private fun person(
        id: String,
        name: String = "Person $id",
        seat: Int? = null,
        role: EncounterPresenceRole = EncounterPresenceRole.CONGREGATION,
    ) = testPerson(id, role, name, seat = seat)

    @Test
    fun applyServer_mirrorsTheSeatsTheServerReports_andNothingElse() {
        val seating = EncounterSeating()
        seating.applyServer(listOf(person("a", seat = 3), person("b", seat = 0), person("c")))
        assertEquals(3, seating.seatOf("a"))
        assertEquals(0, seating.seatOf("b"))
        assertNull(seating.seatOf("c"), "nobody is seated without a choice")
        assertEquals("a", seating.occupantOf(3))
        assertNull(seating.occupantOf(1))
    }

    @Test
    fun anOfficeHolder_neverSits_evenIfAWrongSeatArrives() {
        val seating = EncounterSeating()
        seating.applyServer(listOf(person("p", seat = 2, role = EncounterPresenceRole.PULPIT), person("a", seat = 2)))
        assertNull(seating.seatOf("p"))
        assertEquals("a", seating.occupantOf(2))
        assertFalse(seating.isCongregation("p"))
        assertTrue(seating.isCongregation("a"))
    }

    @Test
    fun aSeatClaimedTwice_belongsToTheFirstByName_theOtherIsUnseated() {
        val seating = EncounterSeating()
        seating.applyServer(listOf(person("z", name = "Zeno", seat = 5), person("a", name = "Anna", seat = 5)))
        assertEquals("a", seating.occupantOf(5))
        assertEquals(listOf("z"), seating.unseated().map { it.memberId })
    }

    @Test
    fun unseated_isSortedByName_andFreeSeatsExcludeTheTakenOnes() {
        val seating = EncounterSeating()
        seating.applyServer(listOf(person("b", name = "Berta"), person("a", name = "Anna"), person("c", name = "Carl", seat = 1)))
        assertEquals(listOf("Anna", "Berta"), seating.unseated().map { it.displayName })
        assertFalse(1 in seating.freeSeats())
        assertTrue(0 in seating.freeSeats())
    }

    @Test
    fun gridSize_isTheCapacityInWholeRows_andNeverCutsAnOccupiedSeat() {
        val seating = EncounterSeating()
        seating.applyServer(emptyList())
        assertEquals(24, seating.gridSize, "the minimum is four rows")
        seating.applyServer((0 until 30).map { person("p$it") })
        assertEquals(36, seating.gridSize, "30 people: five rows plus one spare row")
        // the capacity shrinks when people leave, but somebody still sits at seat 40: the grid keeps showing that seat
        seating.applyServer(listOf(person("far", seat = 40)))
        assertEquals(42, seating.gridSize)
        assertEquals("far", seating.occupantOf(40))
    }

    @Test
    fun seatsBeyondTheShrunkenCapacity_areDrawn_butNotChoosable_andNotFree() {
        val seating = EncounterSeating()
        // 14 people: capacity 24; somebody still sits at seat 35, so the grid keeps 36 seats
        seating.applyServer((0 until 13).map { person("p$it") } + person("far", seat = 35))
        assertEquals(36, seating.gridSize)
        assertTrue(seating.isChoosable(23))
        assertFalse(seating.isChoosable(24))
        assertFalse(24 in seating.freeSeats())
        assertEquals((0 until 24).toList(), seating.freeSeats())
    }

    @Test
    fun forget_dropsThePersonLocally_untilTheNextList() {
        val seating = EncounterSeating()
        seating.applyServer(listOf(person("a", seat = 2), person("b")))
        seating.forget("a")
        assertNull(seating.seatOf("a"))
        assertNull(seating.occupantOf(2))
        assertFalse(seating.isCongregation("a"))
        assertEquals(listOf("b"), seating.unseated().map { it.memberId })
    }

    @Test
    fun theChoiceThrottle_allowsOneChangePerSecond() {
        var now = 10_000.0
        val throttle = EncounterSeatChoiceThrottle({ now })
        assertTrue(throttle.tryChoose())
        now += 999
        assertFalse(throttle.tryChoose(), "within the second: nothing happens")
        now += 2
        assertTrue(throttle.tryChoose())
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
