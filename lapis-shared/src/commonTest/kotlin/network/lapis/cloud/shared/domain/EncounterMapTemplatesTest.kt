package network.lapis.cloud.shared.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** V1.9.166 -- the three room templates of the grid map: size, free tiles, anchors and their connectivity. */
class EncounterMapTemplatesTest {
    private val mapLayouts = listOf(EncounterRoomLayout.HALL, EncounterRoomLayout.FOYER, EncounterRoomLayout.SMALL_GROUP)

    @Test
    fun theNaveHasNoMap_everyOtherLayoutHasOne() {
        assertNull(EncounterMapTemplates.of(EncounterRoomLayout.NAVE))
        mapLayouts.forEach { assertNotNull(EncounterMapTemplates.of(it)) }
    }

    @Test
    fun everyTemplate_is12By8_andOffersAtLeast40FreeTiles() {
        assertEquals(12, ENCOUNTER_MAP_COLUMNS)
        assertEquals(8, ENCOUNTER_MAP_ROWS)
        mapLayouts.forEach { layout ->
            val t = EncounterMapTemplates.of(layout)!!
            assertEquals(layout, t.layout)
            val counted =
                (0 until ENCOUNTER_MAP_ROWS).sumOf { y ->
                    (0 until ENCOUNTER_MAP_COLUMNS).count { x -> t.walkable(x = x, y = y) }
                }
            assertEquals(counted, t.freeTiles(), "$layout")
            assertTrue(t.freeTiles() >= ENCOUNTER_MAP_MIN_FREE_TILES, "$layout has ${t.freeTiles()} free tiles")
        }
    }

    @Test
    fun outsideTheGrid_isAWall() {
        val t = EncounterMapTemplates.of(EncounterRoomLayout.HALL)!!
        assertEquals(EncounterMapCell.WALL, t.cell(x = -1, y = 0))
        assertEquals(EncounterMapCell.WALL, t.cell(x = 0, y = 8))
        assertFalse(t.walkable(x = 12, y = 3))
        assertNull(t.neighbor(index = 0, dir = EncounterMapFacing.NORTH))
        assertNull(t.neighbor(index = 0, dir = EncounterMapFacing.WEST))
        assertEquals(1, t.neighbor(index = 0, dir = EncounterMapFacing.EAST))
        assertEquals(12, t.neighbor(index = 0, dir = EncounterMapFacing.SOUTH))
    }

    @Test
    fun allThreeAnchors_areWalkable_andReachableFromTheEntrance() {
        mapLayouts.forEach { layout ->
            val t = EncounterMapTemplates.of(layout)!!
            assertEquals(EncounterMapAnchor.entries.toSet(), t.anchors.keys)
            val entrance = t.anchors.getValue(EncounterMapAnchor.ENTRANCE)
            EncounterMapAnchor.entries.forEach { anchor ->
                val tile = t.anchors.getValue(anchor)
                assertTrue(t.walkable(tile), "$layout $anchor must be walkable")
                assertNotNull(
                    encounterMapPath(t = t, occupied = emptySet(), from = entrance, to = tile, maxSteps = 40),
                    "$layout $anchor must be reachable",
                )
            }
        }
    }

    @Test
    fun everyFreeTile_isReachableFromTheEntrance() {
        mapLayouts.forEach { layout ->
            val t = EncounterMapTemplates.of(layout)!!
            val entrance = t.anchors.getValue(EncounterMapAnchor.ENTRANCE)
            for (y in 0 until ENCOUNTER_MAP_ROWS) {
                for (x in 0 until ENCOUNTER_MAP_COLUMNS) {
                    if (!t.walkable(x = x, y = y)) continue
                    assertNotNull(
                        encounterMapPath(
                            t = t,
                            occupied = emptySet(),
                            from = entrance,
                            to = encounterMapIndex(x = x, y = y),
                            maxSteps = 60,
                        ),
                        "$layout ($x,$y)",
                    )
                }
            }
        }
    }

    @Test
    fun theTemplateRowsMustBe12By8() {
        val error =
            runCatching {
                EncounterMapTemplate(layout = EncounterRoomLayout.HALL, rows = listOf("##"), anchors = emptyMap())
            }.exceptionOrNull()
        assertTrue(error is IllegalArgumentException)
    }
}
