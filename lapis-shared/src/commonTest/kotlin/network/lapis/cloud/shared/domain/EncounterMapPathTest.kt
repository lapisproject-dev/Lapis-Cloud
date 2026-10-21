package network.lapis.cloud.shared.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** V1.9.166 -- the breadth-first searches of the grid map. */
class EncounterMapPathTest {
    private val hall = EncounterMapTemplates.of(EncounterRoomLayout.HALL)!!
    private val entrance = hall.anchors.getValue(EncounterMapAnchor.ENTRANCE)

    private fun apply(
        start: Int,
        steps: List<EncounterMapFacing>,
    ): Int = steps.fold(start) { at, dir -> hall.neighbor(index = at, dir = dir)!! }

    @Test
    fun shortestPath_walksStraightAndEndsOnTheTarget() {
        val to = encounterMapIndex(x = 5, y = 5)
        val path = encounterMapPath(t = hall, occupied = emptySet(), from = entrance, to = to, maxSteps = ENCOUNTER_MAP_PATH_MAX_STEPS)
        assertNotNull(path)
        assertEquals(2, path.size)
        assertEquals(to, apply(start = entrance, steps = path))
    }

    @Test
    fun theSameTile_isAnEmptyPath() {
        assertEquals(
            emptyList(),
            encounterMapPath(t = hall, occupied = emptySet(), from = entrance, to = entrance, maxSteps = ENCOUNTER_MAP_PATH_MAX_STEPS),
        )
    }

    @Test
    fun aWall_anObject_andTheEdge_areNotTargets() {
        assertNull(
            encounterMapPath(t = hall, occupied = emptySet(), from = entrance, to = encounterMapIndex(x = 0, y = 0), maxSteps = 20),
            "wall",
        )
        assertNull(
            encounterMapPath(t = hall, occupied = emptySet(), from = entrance, to = encounterMapIndex(x = 3, y = 1), maxSteps = 20),
            "object",
        )
    }

    @Test
    fun anOccupiedTarget_isNull_butTheStartItselfDoesNotBlock() {
        val to = encounterMapIndex(x = 5, y = 5)
        assertNull(encounterMapPath(t = hall, occupied = setOf(to), from = entrance, to = to, maxSteps = 20))
        assertNotNull(encounterMapPath(t = hall, occupied = setOf(entrance), from = entrance, to = to, maxSteps = 20))
    }

    @Test
    fun people_blockTheWay_andThePathGoesAround() {
        val to = encounterMapIndex(x = 5, y = 5)
        // block the straight line (5,6): the path must be longer than the straight one but still exist
        val path = encounterMapPath(t = hall, occupied = setOf(encounterMapIndex(x = 5, y = 6)), from = entrance, to = to, maxSteps = 20)
        assertNotNull(path)
        assertTrue(path.size > 2)
        assertEquals(to, apply(start = entrance, steps = path))
    }

    @Test
    fun aPathLongerThanTheLimit_isNull() {
        val far = encounterMapIndex(x = 10, y = 2)
        assertNotNull(encounterMapPath(t = hall, occupied = emptySet(), from = entrance, to = far, maxSteps = 20))
        assertNull(encounterMapPath(t = hall, occupied = emptySet(), from = entrance, to = far, maxSteps = 3))
    }

    @Test
    fun aClosedGap_makesTheTargetUnreachable() {
        // the entrance gap is 4 tiles wide (x 4..7 in row 7): fill the three tiles of row 6 in front of it and every route inside is blocked
        val wall = (1..10).map { encounterMapIndex(x = it, y = 6) }.toSet()
        assertNull(encounterMapPath(t = hall, occupied = wall, from = entrance, to = encounterMapIndex(x = 5, y = 2), maxSteps = 60))
    }

    @Test
    fun nearestFree_isTheStartWhenFree_otherwiseTheClosestNeighbour_andNullWhenNothingIsFree() {
        assertEquals(entrance, encounterMapNearestFree(t = hall, occupied = emptySet(), from = entrance))
        val next = encounterMapNearestFree(t = hall, occupied = setOf(entrance), from = entrance)
        assertNotNull(next)
        assertTrue(next != entrance)
        assertTrue(hall.walkable(next))
        val everyone = (0 until ENCOUNTER_MAP_COLUMNS * ENCOUNTER_MAP_ROWS).toSet()
        assertNull(encounterMapNearestFree(t = hall, occupied = everyone, from = entrance))
    }

    @Test
    fun nearestFree_isDeterministic() {
        val taken = setOf(entrance)
        assertEquals(
            encounterMapNearestFree(t = hall, occupied = taken, from = entrance),
            encounterMapNearestFree(t = hall, occupied = taken, from = entrance),
        )
    }

    @Test
    fun initials_areTheSameAsBefore_andTheWireVariantIsSafe() {
        assertEquals("AB", encounterMapInitials("Anna Maria Beispiel"))
        assertEquals("A", encounterMapInitials("Anna"))
        assertEquals("AB", encounterMapInitials("Anna-Lena Beispiel"))
        assertEquals("?", encounterMapInitials("   "))
        assertTrue(encounterMapWireInitials("ß Beispiel").length <= ENCOUNTER_MAP_INITIALS_MAX)
        assertEquals("?", encounterMapWireInitials(""))
        assertTrue(encounterMapWireInitials("😀 Name").none { it.isSurrogate() })
    }
}
