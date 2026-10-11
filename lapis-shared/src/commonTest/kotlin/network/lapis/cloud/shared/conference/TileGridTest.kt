package network.lapis.cloud.shared.conference

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * V1.9.109 -- the shared best-fit grid ([computeConferenceTileGrid], moved from the live client in this wave), the recording helpers on top of it
 * ([bestFitGrid], [gridCellRects], [assignSlots]) and the live view's parameter set (8 px gap, 160 px floor), pinned here so that moving the
 * function did not change a single live answer.
 */
class TileGridTest {
    private val liveGap = 8.0
    private val liveMinTile = 160.0

    private fun live(
        count: Int,
        width: Double,
        height: Double,
        aspect: Double = CONFERENCE_TILE_ASPECT,
        previousColumns: Int? = null,
    ): ConferenceTileGrid =
        computeConferenceTileGrid(
            count = count,
            width = width,
            height = height,
            aspect = aspect,
            gap = liveGap,
            previousColumns = previousColumns,
            minTileWidth = liveMinTile,
        )

    private val empty = ConferenceTileGrid(columns = 0, rows = 0, tileWidth = 0, tileHeight = 0, scrolls = false)

    private fun assertFits(
        grid: ConferenceTileGrid,
        count: Int,
        width: Double,
        height: Double,
    ) {
        assertTrue(grid.columns * grid.rows >= count, "all tiles have a cell: $grid for $count")
        val usedWidth = grid.tileWidth * grid.columns + liveGap * (grid.columns - 1)
        assertTrue(usedWidth <= width + 0.001, "width $usedWidth <= $width: $grid")
        if (!grid.scrolls) {
            val usedHeight = grid.tileHeight * grid.rows + liveGap * (grid.rows - 1)
            assertTrue(usedHeight <= height + 0.001, "height $usedHeight <= $height: $grid")
        }
        assertTrue(abs(grid.tileWidth / CONFERENCE_TILE_ASPECT - grid.tileHeight) <= 1.0, "16:9 within one pixel: $grid")
    }

    @Test
    fun everyParticipantCount_fitsTheArea_in16To9() {
        for (count in listOf(1, 2, 3, 5, 7, 25)) {
            val grid = live(count = count, width = 1280.0, height = 720.0)
            assertFits(grid = grid, count = count, width = 1280.0, height = 720.0)
        }
    }

    @Test
    fun onePerson_fillsTheHeight_notTheWidth() {
        val grid = live(count = 1, width = 1280.0, height = 720.0)
        assertEquals(1, grid.columns)
        assertEquals(1, grid.rows)
        assertEquals(1280, grid.tileWidth)
        assertEquals(720, grid.tileHeight)
        val wide = live(count = 1, width = 3840.0, height = 400.0)
        assertEquals(711, wide.tileWidth)
        assertEquals(399, wide.tileHeight)
    }

    @Test
    fun veryWideArea_isOneRow() {
        val grid = live(count = 3, width = 3840.0, height = 400.0)
        assertEquals(1, grid.rows)
        assertEquals(3, grid.columns)
        assertFits(grid = grid, count = 3, width = 3840.0, height = 400.0)
    }

    @Test
    fun veryTallArea_isOneColumn() {
        val grid = live(count = 3, width = 400.0, height = 2000.0)
        assertEquals(1, grid.columns)
        assertEquals(3, grid.rows)
        assertFits(grid = grid, count = 3, width = 400.0, height = 2000.0)
    }

    @Test
    fun phonePortrait_matchesTheDesignValues() {
        val three = live(count = 3, width = 344.0, height = 520.0)
        assertEquals(1, three.columns)
        assertEquals(298, three.tileWidth)
        assertTrue(three.tileHeight in 167..168, "height ${three.tileHeight}")
        val seven = live(count = 7, width = 344.0, height = 520.0)
        assertEquals(2, seven.columns)
        assertTrue(seven.tileWidth >= 160)
        assertFits(grid = seven, count = 7, width = 344.0, height = 520.0)
        assertFits(grid = live(count = 1, width = 344.0, height = 520.0), count = 1, width = 344.0, height = 520.0)
        assertFits(grid = live(count = 2, width = 344.0, height = 520.0), count = 2, width = 344.0, height = 520.0)
    }

    @Test
    fun phoneLandscape_fitsTheLowArea() {
        val grid = live(count = 2, width = 624.0, height = 240.0)
        assertFits(grid = grid, count = 2, width = 624.0, height = 240.0)
        assertEquals(2, grid.columns)
    }

    @Test
    fun degenerateInput_answersAnEmptyGrid_neverThrows() {
        assertEquals(empty, live(count = 0, width = 100.0, height = 100.0))
        assertEquals(empty, live(count = -3, width = 100.0, height = 100.0))
        assertEquals(empty, live(count = 3, width = 0.0, height = 100.0))
        assertEquals(empty, live(count = 3, width = 100.0, height = 0.0))
        assertEquals(empty, live(count = 3, width = -5.0, height = 100.0))
        assertEquals(empty, live(count = 3, width = 100.0, height = -5.0))
        assertEquals(empty, live(count = 3, width = Double.NaN, height = 100.0))
        assertEquals(empty, live(count = 3, width = 100.0, height = Double.NaN))
        assertEquals(empty, live(count = 3, width = Double.POSITIVE_INFINITY, height = 100.0))
        assertEquals(empty, live(count = 3, width = 100.0, height = Double.NEGATIVE_INFINITY))
        assertEquals(empty, live(count = 3, width = 100.0, height = 100.0, aspect = 0.0))
        assertEquals(empty, live(count = 3, width = 100.0, height = 100.0, aspect = Double.NaN))
    }

    @Test
    fun hysteresis_gainBelowSixPercentStays_aboveChanges() {
        // 4 tiles in 1000 px: 4 columns give a tile of 244 px (width bound), 2 columns give min(496, (H - 8) / 2 * 16/9) (height bound).
        // H = 295: 2 columns reach 255.1, a gain of 4.5 percent over the previous 4 columns -> the previous layout stays
        val stays = live(count = 4, width = 1000.0, height = 295.0, previousColumns = 4)
        assertEquals(4, stays.columns)
        assertEquals(244, stays.tileWidth)
        // without a previous layout the better one is taken at once
        assertEquals(2, live(count = 4, width = 1000.0, height = 295.0).columns)
        // H = 310: 268.4, a gain of 10 percent -> the new layout wins
        assertEquals(2, live(count = 4, width = 1000.0, height = 310.0, previousColumns = 4).columns)
        // exactly the boundary region: 5.5 percent stays (H = 298 -> 257.8), 6.4 percent changes (H = 300 -> 259.6)
        assertEquals(4, live(count = 4, width = 1000.0, height = 298.0, previousColumns = 4).columns)
        assertEquals(2, live(count = 4, width = 1000.0, height = 300.0, previousColumns = 4).columns)
    }

    @Test
    fun hysteresis_invalidPreviousColumns_isIgnored() {
        val plain = live(count = 5, width = 1280.0, height = 720.0)
        assertEquals(plain, live(count = 5, width = 1280.0, height = 720.0, previousColumns = 0))
        assertEquals(plain, live(count = 5, width = 1280.0, height = 720.0, previousColumns = 9))
        assertEquals(plain, live(count = 5, width = 1280.0, height = 720.0, previousColumns = -1))
    }

    @Test
    fun floor_liftsTheTile_andTheAreaScrolls() {
        val grid = live(count = 25, width = 800.0, height = 300.0)
        assertTrue(grid.scrolls)
        assertEquals(160, grid.tileWidth)
        assertEquals(((800.0 + 8.0) / (160.0 + 8.0)).toInt(), grid.columns)
        assertTrue(grid.columns * grid.rows >= 25)
        assertFits(grid = grid, count = 25, width = 800.0, height = 300.0)
    }

    @Test
    fun floor_neverMoreColumnsThanTiles() {
        val grid = live(count = 2, width = 2000.0, height = 60.0)
        assertTrue(grid.scrolls)
        assertEquals(2, grid.columns)
        assertEquals(1, grid.rows)
    }

    @Test
    fun floor_areaNarrowerThanOneTile_isOneColumn() {
        val grid = live(count = 4, width = 120.0, height = 400.0)
        assertTrue(grid.scrolls)
        assertEquals(1, grid.columns)
        // the width clamp: a tile is never wider than the area
        assertTrue(grid.tileWidth <= 120)
    }

    @Test
    fun tie_prefersFewerEmptyCells_thenMoreColumns() {
        // 4 tiles in a square area: 2x2 beats every other layout, 1 tile has only one layout
        val grid = live(count = 4, width = 1000.0, height = 1000.0)
        assertEquals(2, grid.columns)
        assertEquals(2, grid.rows)
        // 1 tile: only one column count exists
        assertEquals(1, live(count = 1, width = 500.0, height = 500.0).columns)
    }

    @Test
    fun monotone_aLargerWindowNeverShrinksTheTile() {
        for (count in listOf(1, 2, 3, 4, 6, 7, 9, 12)) {
            var previous = 0
            for (side in listOf(400.0, 600.0, 800.0, 1000.0, 1400.0, 2000.0)) {
                val tile = live(count = count, width = side * 1.6, height = side).tileWidth
                assertTrue(tile >= previous, "count $count side $side: $tile < $previous")
                previous = tile
            }
        }
    }

    // ── recording parameters: gap 0, no floor, no hysteresis ────────────────────────────────────────────────────────────────────

    @Test
    fun recording_table_at1920x1080() {
        val expected =
            mapOf(
                1 to GridShape(cols = 1, rows = 1),
                2 to GridShape(cols = 2, rows = 1),
                3 to GridShape(cols = 2, rows = 2),
                4 to GridShape(cols = 2, rows = 2),
                5 to GridShape(cols = 3, rows = 2),
                6 to GridShape(cols = 3, rows = 2),
                7 to GridShape(cols = 3, rows = 3),
                8 to GridShape(cols = 3, rows = 3),
                9 to GridShape(cols = 3, rows = 3),
                10 to GridShape(cols = 4, rows = 3),
                11 to GridShape(cols = 4, rows = 3),
                12 to GridShape(cols = 4, rows = 3),
                13 to GridShape(cols = 4, rows = 4),
                16 to GridShape(cols = 4, rows = 4),
            )
        for ((count, shape) in expected) {
            assertEquals(shape, bestFitGrid(count = count, canvasW = 1920, canvasH = 1080), "count $count")
        }
    }

    @Test
    fun recording_sixPeople_areThreeByTwo_notTwoByThree() {
        assertEquals(GridShape(cols = 3, rows = 2), bestFitGrid(count = 6, canvasW = 1280, canvasH = 720))
        assertEquals(GridShape(cols = 3, rows = 2), bestFitGrid(count = 6, canvasW = 640, canvasH = 360))
    }

    @Test
    fun recording_zeroOrNegativeCount_isOneByOne() {
        assertEquals(GridShape(cols = 1, rows = 1), bestFitGrid(count = 0, canvasW = 1280, canvasH = 720))
        assertEquals(GridShape(cols = 1, rows = 1), bestFitGrid(count = -4, canvasW = 1280, canvasH = 720))
    }

    @Test
    fun recording_defaultsAreZeroGapAndNoFloor() {
        val plain = computeConferenceTileGrid(count = 6, width = 1920.0, height = 1080.0)
        assertEquals(640, plain.tileWidth)
        assertEquals(360, plain.tileHeight)
        assertTrue(!plain.scrolls)
    }

    @Test
    fun liveView_withTheGap_isNotAffectedByTheTieRule() {
        // 6 people on 1280x720 with the live gap: 3x2, as before; 4 in a square: 2x2
        assertEquals(3, live(count = 6, width = 1280.0, height = 720.0).columns)
        assertEquals(2, live(count = 4, width = 1000.0, height = 1000.0).columns)
        assertEquals(2, live(count = 2, width = 1280.0, height = 720.0).columns)
    }

    @Test
    fun cellRects_areEvenInsideTheCanvas_16To9_andCentred() {
        for (count in 1..16) {
            for ((w, h) in listOf(1280 to 720, 640 to 360, 1920 to 1080)) {
                val shape = bestFitGrid(count = count, canvasW = w, canvasH = h)
                val rects = gridCellRects(count = count, canvasW = w, canvasH = h, shape = shape)
                assertEquals(count, rects.size)
                for (r in rects) {
                    assertTrue(r.x % 2 == 0 && r.y % 2 == 0 && r.width % 2 == 0 && r.height % 2 == 0, "even: $r")
                    assertTrue(r.x >= 0 && r.y >= 0 && r.x + r.width <= w && r.y + r.height <= h, "inside the canvas: $r count $count")
                    assertTrue(abs(r.width * 9.0 / 16.0 - r.height) <= 1.01, "16:9 within a pixel: $r")
                }
                assertTrue(rects.map { it.width }.distinct().size == 1, "one cell size")
                // no overlap
                for (i in rects.indices) {
                    for (j in i + 1 until rects.size) {
                        val a = rects[i]
                        val b = rects[j]
                        val apart = a.x + a.width <= b.x || b.x + b.width <= a.x || a.y + a.height <= b.y || b.y + b.height <= a.y
                        assertTrue(apart, "cells $i and $j overlap: $a $b")
                    }
                }
                // centred: left and right margin differ by at most one cell step of 2 px
                val left = rects.minOf { it.x }
                val right = w - rects.maxOf { it.x + it.width }
                assertTrue(abs(left - right) <= 2, "horizontally centred: $left vs $right count $count")
                val top = rects.minOf { it.y }
                val bottom = h - rects.maxOf { it.y + it.height }
                assertTrue(abs(top - bottom) <= 2, "vertically centred: $top vs $bottom count $count")
            }
        }
    }

    @Test
    fun cellRects_incompleteLastRowIsCentredOnItsOwn() {
        // 5 people in 3x2: the second row holds two cells and sits in the middle
        val rects = gridCellRects(count = 5, canvasW = 1920, canvasH = 1080, shape = GridShape(cols = 3, rows = 2))
        val firstRowLeft = rects[0].x
        val secondRowLeft = rects[3].x
        assertEquals(rects[0].width / 2, secondRowLeft - firstRowLeft, "half a cell indented")
        assertEquals(rects[3].y, rects[4].y)
        assertEquals(rects[3].width, rects[4].x - rects[3].x)
    }

    @Test
    fun cellRects_oneCell_fillsTheCanvas() {
        val rect = gridCellRects(count = 1, canvasW = 1280, canvasH = 720, shape = GridShape(cols = 1, rows = 1)).single()
        assertEquals(CellRect(x = 0, y = 0, width = 1280, height = 720), rect)
    }

    @Test
    fun cellRects_emptyForNoPictures() {
        assertEquals(emptyList(), gridCellRects(count = 0, canvasW = 1280, canvasH = 720, shape = GridShape(cols = 1, rows = 1)))
    }

    @Test
    fun singleRow_isOneRow() {
        assertEquals(GridShape(cols = 4, rows = 1), singleRowGrid(4))
        assertEquals(GridShape(cols = 1, rows = 1), singleRowGrid(0))
    }

    // ── slot assignment ─────────────────────────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun slots_noPreviousLayout_followFirstSeenOrder() {
        val slots =
            assignSlots(
                previous = emptyMap(),
                prevShape = null,
                visibleInFirstSeenOrder = listOf("a", "b", "c"),
                shape = GridShape(cols = 2, rows = 2),
            )
        assertEquals(mapOf("a" to 0, "b" to 1, "c" to 2), slots)
    }

    @Test
    fun slots_sameShape_existingKeepTheirSlot_newTakeLowestFree() {
        val shape = GridShape(cols = 2, rows = 2)
        val first = assignSlots(previous = emptyMap(), prevShape = null, visibleInFirstSeenOrder = listOf("a", "b", "c"), shape = shape)
        // d joins, same shape: a b c unchanged, d takes slot 3
        val second = assignSlots(previous = first, prevShape = shape, visibleInFirstSeenOrder = listOf("a", "b", "c", "d"), shape = shape)
        assertEquals(mapOf("a" to 0, "b" to 1, "c" to 2, "d" to 3), second)
        // b leaves, e joins: the others stay, e takes the freed slot 1
        val third = assignSlots(previous = second, prevShape = shape, visibleInFirstSeenOrder = listOf("a", "c", "d", "e"), shape = shape)
        assertEquals(mapOf("a" to 0, "c" to 2, "d" to 3, "e" to 1), third)
    }

    @Test
    fun slots_sameShape_butFewerPictures_staysContiguous() {
        val shape = GridShape(cols = 2, rows = 2)
        val full = mapOf("a" to 0, "b" to 1, "c" to 2, "d" to 3)
        val result = assignSlots(previous = full, prevShape = shape, visibleInFirstSeenOrder = listOf("a", "c", "d"), shape = shape)
        assertEquals(setOf(0, 1, 2), result.values.toSet())
        assertEquals(0, result["a"])
        assertEquals(2, result["c"])
    }

    @Test
    fun slots_anotherShape_isRedistributedInFirstSeenOrder() {
        val before = mapOf("a" to 0, "b" to 1, "c" to 2, "d" to 3)
        val after =
            assignSlots(
                previous = before,
                prevShape = GridShape(cols = 2, rows = 2),
                visibleInFirstSeenOrder = listOf("a", "b", "c", "d", "e"),
                shape = GridShape(cols = 3, rows = 2),
            )
        assertEquals(mapOf("a" to 0, "b" to 1, "c" to 2, "d" to 3, "e" to 4), after)
    }
}
