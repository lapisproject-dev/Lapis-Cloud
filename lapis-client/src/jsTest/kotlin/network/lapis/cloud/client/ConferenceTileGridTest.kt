package network.lapis.cloud.client

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * V1.9.92 -- [computeConferenceTileGrid], [conferenceTileAreaHeight] and [conferenceRailInset]: pure arithmetic of the best-fit tile grid.
 * The DOM side (variables, node identity) is covered by `ConferenceTileGridDomTest`.
 */
class ConferenceTileGridTest {
    private val empty = ConferenceTileGrid(0, 0, 0, 0, false)

    private fun assertFits(
        grid: ConferenceTileGrid,
        count: Int,
        width: Double,
        height: Double,
    ) {
        assertTrue(grid.columns * grid.rows >= count, "all tiles have a cell: $grid for $count")
        val usedWidth = grid.tileWidth * grid.columns + CONFERENCE_TILE_GAP_PX * (grid.columns - 1)
        assertTrue(usedWidth <= width + 0.001, "width $usedWidth <= $width: $grid")
        if (!grid.scrolls) {
            val usedHeight = grid.tileHeight * grid.rows + CONFERENCE_TILE_GAP_PX * (grid.rows - 1)
            assertTrue(usedHeight <= height + 0.001, "height $usedHeight <= $height: $grid")
        }
        assertTrue(abs(grid.tileWidth / CONFERENCE_TILE_ASPECT - grid.tileHeight) <= 1.0, "16:9 within one pixel: $grid")
    }

    @Test
    fun everyParticipantCount_fitsTheArea_in16To9() {
        for (count in listOf(1, 2, 3, 5, 7, 25)) {
            val grid = computeConferenceTileGrid(count, 1280.0, 720.0)
            assertFits(grid, count, 1280.0, 720.0)
        }
    }

    @Test
    fun onePerson_fillsTheHeight_notTheWidth() {
        val grid = computeConferenceTileGrid(1, 1280.0, 720.0)
        assertEquals(1, grid.columns)
        assertEquals(1, grid.rows)
        assertEquals(1280, grid.tileWidth)
        assertEquals(720, grid.tileHeight)
        val wide = computeConferenceTileGrid(1, 3840.0, 400.0)
        assertEquals(711, wide.tileWidth)
        assertEquals(399, wide.tileHeight)
    }

    @Test
    fun veryWideArea_isOneRow() {
        val grid = computeConferenceTileGrid(3, 3840.0, 400.0)
        assertEquals(1, grid.rows)
        assertEquals(3, grid.columns)
        assertFits(grid, 3, 3840.0, 400.0)
    }

    @Test
    fun veryTallArea_isOneColumn() {
        val grid = computeConferenceTileGrid(3, 400.0, 2000.0)
        assertEquals(1, grid.columns)
        assertEquals(3, grid.rows)
        assertFits(grid, 3, 400.0, 2000.0)
    }

    @Test
    fun phonePortrait_matchesTheDesignValues() {
        val three = computeConferenceTileGrid(3, 344.0, 520.0)
        assertEquals(1, three.columns)
        assertEquals(298, three.tileWidth)
        assertTrue(three.tileHeight in 167..168, "height ${three.tileHeight}")
        val seven = computeConferenceTileGrid(7, 344.0, 520.0)
        assertEquals(2, seven.columns)
        assertTrue(seven.tileWidth >= 160)
        assertFits(seven, 7, 344.0, 520.0)
        assertFits(computeConferenceTileGrid(1, 344.0, 520.0), 1, 344.0, 520.0)
        assertFits(computeConferenceTileGrid(2, 344.0, 520.0), 2, 344.0, 520.0)
    }

    @Test
    fun phoneLandscape_fitsTheLowArea() {
        val grid = computeConferenceTileGrid(2, 624.0, 240.0)
        assertFits(grid, 2, 624.0, 240.0)
        assertEquals(2, grid.columns)
    }

    @Test
    fun degenerateInput_answersAnEmptyGrid_neverThrows() {
        assertEquals(empty, computeConferenceTileGrid(0, 100.0, 100.0))
        assertEquals(empty, computeConferenceTileGrid(-3, 100.0, 100.0))
        assertEquals(empty, computeConferenceTileGrid(3, 0.0, 100.0))
        assertEquals(empty, computeConferenceTileGrid(3, 100.0, 0.0))
        assertEquals(empty, computeConferenceTileGrid(3, -5.0, 100.0))
        assertEquals(empty, computeConferenceTileGrid(3, 100.0, -5.0))
        assertEquals(empty, computeConferenceTileGrid(3, Double.NaN, 100.0))
        assertEquals(empty, computeConferenceTileGrid(3, 100.0, Double.NaN))
        assertEquals(empty, computeConferenceTileGrid(3, Double.POSITIVE_INFINITY, 100.0))
        assertEquals(empty, computeConferenceTileGrid(3, 100.0, Double.NEGATIVE_INFINITY))
        assertEquals(empty, computeConferenceTileGrid(3, 100.0, 100.0, aspect = 0.0))
        assertEquals(empty, computeConferenceTileGrid(3, 100.0, 100.0, aspect = Double.NaN))
    }

    @Test
    fun hysteresis_gainBelowSixPercentStays_aboveChanges() {
        // 4 tiles in 1000 px: 4 columns give a tile of 244 px (width bound), 2 columns give min(496, (H - 8) / 2 * 16/9) (height bound).
        // H = 295: 2 columns reach 255.1, a gain of 4.5 percent over the previous 4 columns -> the previous layout stays
        val stays = computeConferenceTileGrid(4, 1000.0, 295.0, previousColumns = 4)
        assertEquals(4, stays.columns)
        assertEquals(244, stays.tileWidth)
        // without a previous layout the better one is taken at once
        assertEquals(2, computeConferenceTileGrid(4, 1000.0, 295.0).columns)
        // H = 310: 268.4, a gain of 10 percent -> the new layout wins
        assertEquals(2, computeConferenceTileGrid(4, 1000.0, 310.0, previousColumns = 4).columns)
        // exactly the boundary region: 5.5 percent stays (H = 298 -> 257.8), 6.4 percent changes (H = 300 -> 259.6)
        assertEquals(4, computeConferenceTileGrid(4, 1000.0, 298.0, previousColumns = 4).columns)
        assertEquals(2, computeConferenceTileGrid(4, 1000.0, 300.0, previousColumns = 4).columns)
    }

    @Test
    fun hysteresis_invalidPreviousColumns_isIgnored() {
        val plain = computeConferenceTileGrid(5, 1280.0, 720.0)
        assertEquals(plain, computeConferenceTileGrid(5, 1280.0, 720.0, previousColumns = 0))
        assertEquals(plain, computeConferenceTileGrid(5, 1280.0, 720.0, previousColumns = 9))
        assertEquals(plain, computeConferenceTileGrid(5, 1280.0, 720.0, previousColumns = -1))
    }

    @Test
    fun floor_liftsTheTile_andTheAreaScrolls() {
        val grid = computeConferenceTileGrid(25, 800.0, 300.0)
        assertTrue(grid.scrolls)
        assertEquals(160, grid.tileWidth)
        assertEquals(((800.0 + 8.0) / (160.0 + 8.0)).toInt(), grid.columns)
        assertTrue(grid.columns * grid.rows >= 25)
        assertFits(grid, 25, 800.0, 300.0)
    }

    @Test
    fun floor_neverMoreColumnsThanTiles() {
        val grid = computeConferenceTileGrid(2, 2000.0, 60.0)
        assertTrue(grid.scrolls)
        assertEquals(2, grid.columns)
        assertEquals(1, grid.rows)
    }

    @Test
    fun floor_areaNarrowerThanOneTile_isOneColumn() {
        val grid = computeConferenceTileGrid(4, 120.0, 400.0)
        assertTrue(grid.scrolls)
        assertEquals(1, grid.columns)
        // the width clamp: a tile is never wider than the area
        assertTrue(grid.tileWidth <= 120)
    }

    @Test
    fun tie_prefersFewerEmptyCells_thenFewerColumns() {
        // 5 tiles in a square area: 3 columns (1 empty) and 2 columns (1 empty) etc. -- whatever wins leaves no more empty cells than a rival of equal tile width
        val grid = computeConferenceTileGrid(4, 1000.0, 1000.0)
        assertEquals(2, grid.columns)
        assertEquals(2, grid.rows)
        // 1 tile: only one column count exists
        assertEquals(1, computeConferenceTileGrid(1, 500.0, 500.0).columns)
    }

    @Test
    fun monotone_aLargerWindowNeverShrinksTheTile() {
        for (count in listOf(1, 2, 3, 4, 6, 7, 9, 12)) {
            var previous = 0
            for (side in listOf(400.0, 600.0, 800.0, 1000.0, 1400.0, 2000.0)) {
                val tile = computeConferenceTileGrid(count, side * 1.6, side).tileWidth
                assertTrue(tile >= previous, "count $count side $side: $tile < $previous")
                previous = tile
            }
        }
    }

    @Test
    fun areaHeight_usesWindowValues() {
        assertEquals(500.0, conferenceTileAreaHeight(viewportHeight = 800.0, areaTop = 200.0, barHeight = 56.0, bottomPad = 44.0))
        // below the minimum the minimum applies
        assertEquals(240.0, conferenceTileAreaHeight(viewportHeight = 500.0, areaTop = 400.0, barHeight = 56.0, bottomPad = 12.0))
        assertEquals(300.0, conferenceTileAreaHeight(100.0, 400.0, 56.0, 12.0, minHeight = 300.0))
        assertEquals(240.0, conferenceTileAreaHeight(Double.NaN, 0.0, 0.0, 0.0))
        assertEquals(240.0, conferenceTileAreaHeight(800.0, Double.POSITIVE_INFINITY, 0.0, 0.0))
    }

    @Test
    fun railInset_onlyInFullscreenWithRail_atLeast768() {
        assertEquals(0.0, conferenceRailInset(railVisible = false, fullscreen = true, viewportWidth = 1280.0))
        assertEquals(0.0, conferenceRailInset(railVisible = true, fullscreen = false, viewportWidth = 1280.0))
        assertEquals(0.0, conferenceRailInset(railVisible = true, fullscreen = true, viewportWidth = 600.0))
        assertEquals(0.0, conferenceRailInset(railVisible = true, fullscreen = true, viewportWidth = Double.NaN))
        // 28vw clamped to 280..380
        assertEquals(280.0, conferenceRailInset(railVisible = true, fullscreen = true, viewportWidth = 800.0))
        assertEquals(358.4, conferenceRailInset(railVisible = true, fullscreen = true, viewportWidth = 1280.0), 0.001)
        assertEquals(380.0, conferenceRailInset(railVisible = true, fullscreen = true, viewportWidth = 2560.0))
    }
}
