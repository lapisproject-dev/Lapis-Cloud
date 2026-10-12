package network.lapis.cloud.shared.conference

import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * V1.9.92 / V1.9.109 -- the best-fit tile grid of the video conference, shared by the LIVE view (`lapis-client`) and the recording
 * composition (`lapis-server`): pure arithmetic, no DOM, no I/O (a tripwire guards that for the client's own file; this file is
 * dependency-free on purpose).
 *
 * The algorithm (every column count from 1 to `count` is tried, the largest 16:9 tile wins) is the one the live call has used since
 * V1.9.92. V1.9.109 moved it here so the recorded video lays its pictures out with the very same function the participants see on
 * screen; the recording calls it with `gap = 0`, no hysteresis and no minimum tile width, the live view passes its 8 px gap, its
 * 160 px floor and the previous column count explicitly.
 *
 * @property scrolls the floor `minTileWidth` took effect: the area has to grow beyond the height it was given, the page scrolls
 */
data class ConferenceTileGrid(
    val columns: Int,
    val rows: Int,
    val tileWidth: Int,
    val tileHeight: Int,
    val scrolls: Boolean,
)

const val CONFERENCE_TILE_ASPECT = 16.0 / 9.0

/** A layout with another column count only wins when its tile is at least 6 % larger than the one of the previous layout (no flicker at the edge). */
const val CONFERENCE_TILE_GRID_HYSTERESIS = 1.06

private val NO_TILE_GRID = ConferenceTileGrid(columns = 0, rows = 0, tileWidth = 0, tileHeight = 0, scrolls = false)

private const val TIE_EPSILON = 1e-9
private const val PIXEL_EPSILON = 1e-6

private fun Double.usable(): Boolean = isFinite() && this > 0.0

/** The tile width a layout with [columns] columns reaches in the area, or 0 when it does not fit at all. */
private fun fittedWidth(
    count: Int,
    columns: Int,
    width: Double,
    height: Double,
    aspect: Double,
    gap: Double,
): Double {
    val rows = ceil(count.toDouble() / columns).toInt()
    val byWidth = (width - gap * (columns - 1)) / columns
    val byHeight = ((height - gap * (rows - 1)) / rows) * aspect
    val w = min(byWidth, byHeight)
    return if (w.isFinite() && w > 0.0) w else 0.0
}

/**
 * The best-fit grid for [count] 16:9 tiles in an area of [width] x [height] pixels.
 *
 * Every column count from 1 to [count] is tried; the one with the largest tile wins. A tie goes to the layout with fewer empty cells,
 * then to MORE columns (V1.9.109: with a gap of 0 and a 16:9 area, 2x1 / 1x2, 3x2 / 2x3 or 4x3 / 3x4 are equally large; the wider layout
 * is the natural reading order and what the recording shows -- with the live view's 8 px gap such ties do not occur in practice).
 * [previousColumns] (the answer of the last call) keeps its column count unless the best layout is at least
 * [CONFERENCE_TILE_GRID_HYSTERESIS] times larger. A tile narrower than [minTileWidth] is lifted to it: the columns that fit the width
 * stay, the area becomes taller than [height] and [ConferenceTileGrid.scrolls] says so.
 *
 * Total: a zero or negative count, a zero/negative/NaN/infinite area or aspect answer an empty grid, never an exception.
 */
fun computeConferenceTileGrid(
    count: Int,
    width: Double,
    height: Double,
    aspect: Double = CONFERENCE_TILE_ASPECT,
    gap: Double = 0.0,
    previousColumns: Int? = null,
    minTileWidth: Double = 0.0,
): ConferenceTileGrid {
    if (count <= 0 || !width.usable() || !height.usable() || !aspect.usable()) return NO_TILE_GRID
    val safeGap = if (gap.isFinite() && gap >= 0.0) gap else 0.0

    var bestColumns = 1
    var bestWidth = 0.0
    for (columns in 1..count) {
        val w = fittedWidth(count = count, columns = columns, width = width, height = height, aspect = aspect, gap = safeGap)
        if (w <= 0.0) continue
        val better =
            when {
                w > bestWidth + TIE_EPSILON -> true
                w < bestWidth - TIE_EPSILON -> false
                else -> {
                    val emptyHere = ceil(count.toDouble() / columns).toInt() * columns - count
                    val emptyBest = ceil(count.toDouble() / bestColumns).toInt() * bestColumns - count
                    // equal cells: the later (wider) layout wins
                    emptyHere <= emptyBest
                }
            }
        if (better) {
            bestColumns = columns
            bestWidth = w
        }
    }

    var columns = bestColumns
    var tile = bestWidth
    if (previousColumns != null && previousColumns in 1..count && previousColumns != bestColumns) {
        val previousWidth =
            fittedWidth(count = count, columns = previousColumns, width = width, height = height, aspect = aspect, gap = safeGap)
        if (previousWidth > 0.0 && bestWidth < previousWidth * CONFERENCE_TILE_GRID_HYSTERESIS) {
            columns = previousColumns
            tile = previousWidth
        }
    }

    var scrolls = false
    if (tile < minTileWidth) {
        tile = minTileWidth
        columns = max(1, floor((width + safeGap) / (minTileWidth + safeGap)).toInt())
        columns = min(columns, count)
        scrolls = true
    }
    val rows = ceil(count.toDouble() / columns).toInt()
    // the epsilon keeps 1280 / (16/9) from landing on 719.9999999 and losing a whole pixel to the floor
    val tileWidth = floor(min(tile, floor(width)) + PIXEL_EPSILON).toInt()
    val tileHeight = floor(tileWidth / aspect + PIXEL_EPSILON).toInt()
    return ConferenceTileGrid(columns = columns, rows = rows, tileWidth = tileWidth, tileHeight = tileHeight, scrolls = scrolls)
}

// ── Recording-side helpers (V1.9.109) ───────────────────────────────────────────────────────────────────────────────────────────

/** Columns x rows of a grid; both at least 1. */
data class GridShape(
    val cols: Int,
    val rows: Int,
)

/** A pixel rectangle on the output canvas. */
data class CellRect(
    val x: Int,
    val y: Int,
    val width: Int,
    val height: Int,
)

/**
 * The best-fit grid for [count] pictures on a [canvasW] x [canvasH] canvas with no gap -- exactly [computeConferenceTileGrid] with its
 * recording parameters. A [count] of 0 or less answers 1x1 (an empty canvas still has a shape).
 */
fun bestFitGrid(
    count: Int,
    canvasW: Int,
    canvasH: Int,
    cellAspectW: Int = 16,
    cellAspectH: Int = 9,
): GridShape {
    if (count <= 0) return GridShape(cols = 1, rows = 1)
    val grid =
        computeConferenceTileGrid(
            count = count,
            width = canvasW.toDouble(),
            height = canvasH.toDouble(),
            aspect = cellAspectW.toDouble() / cellAspectH.toDouble(),
        )
    return if (grid.columns <= 0) GridShape(cols = 1, rows = 1) else GridShape(cols = grid.columns, rows = grid.rows)
}

/** One row of [count] pictures -- the camera strip below a screen share. */
fun singleRowGrid(count: Int): GridShape = GridShape(cols = count.coerceAtLeast(1), rows = 1)

private fun Int.floorToEven(): Int = this - (this and 1)

/**
 * The cell rectangles of [count] pictures in [shape], one per SLOT (index 0 until [count], row-major).
 *
 * Gap 0; the cell size is the largest 16:9 rectangle that fits ([canvasW] / cols wide, [canvasH] / rows high), reduced to even numbers
 * (yuv420p needs even sizes); the whole block is centred horizontally and vertically, and an incomplete last row is centred on its own.
 * All coordinates are even.
 */
fun gridCellRects(
    count: Int,
    canvasW: Int,
    canvasH: Int,
    shape: GridShape,
): List<CellRect> {
    if (count <= 0 || canvasW <= 0 || canvasH <= 0) return emptyList()
    val cols = shape.cols.coerceAtLeast(1)
    val rows = max(shape.rows.coerceAtLeast(1), ceil(count.toDouble() / cols).toInt())
    val byWidth = canvasW.toDouble() / cols
    val byHeight = canvasH.toDouble() / rows * CONFERENCE_TILE_ASPECT
    var cellW = floor(min(byWidth, byHeight) + PIXEL_EPSILON).toInt().floorToEven().coerceAtLeast(2)
    var cellH = (((cellW * 9.0 / 16.0) / 2.0).roundToInt() * 2).coerceAtLeast(2)
    if (cellH * rows > canvasH) cellH = (canvasH / rows).floorToEven().coerceAtLeast(2)
    if (cellW * cols > canvasW) cellW = (canvasW / cols).floorToEven().coerceAtLeast(2)
    val blockH = cellH * rows
    val top = ((canvasH - blockH) / 2).floorToEven()
    return (0 until count).map { slot ->
        val row = slot / cols
        val col = slot % cols
        val inRow = if (row == rows - 1) count - row * cols else cols
        val rowWidth = inRow * cellW
        val left = ((canvasW - rowWidth) / 2).floorToEven()
        CellRect(x = left + col * cellW, y = top + row * cellH, width = cellW, height = cellH)
    }
}

/**
 * Slot assignment that does not make pictures jump around.
 *
 * [previous] is the slot of every picture of the last layout, [prevShape] its shape. [visibleInFirstSeenOrder] are the pictures of
 * the new layout, oldest first.
 * - Same shape: a picture that was visible keeps its slot (as long as the slot is still inside `0 until n`), every other picture takes
 *   the lowest free slot.
 * - Another shape (or no previous layout): the pictures take slots `0 until n` in first-seen order.
 *
 * The result always uses exactly the slots `0 until n`, each once.
 */
fun <T> assignSlots(
    previous: Map<T, Int>,
    prevShape: GridShape?,
    visibleInFirstSeenOrder: List<T>,
    shape: GridShape,
): Map<T, Int> {
    val n = visibleInFirstSeenOrder.size
    if (prevShape == null || prevShape != shape) {
        return visibleInFirstSeenOrder.mapIndexed { index, item -> item to index }.toMap()
    }
    val result = LinkedHashMap<T, Int>()
    val taken = BooleanArray(n)
    for (item in visibleInFirstSeenOrder) {
        val slot = previous[item] ?: continue
        if (slot in 0 until n && !taken[slot]) {
            result[item] = slot
            taken[slot] = true
        }
    }
    var cursor = 0
    for (item in visibleInFirstSeenOrder) {
        if (item in result) continue
        while (taken[cursor]) cursor++
        result[item] = cursor
        taken[cursor] = true
    }
    return result
}

// -- V1.9.115: the "speaker large" layout of the recording ---------------------------------------------------------------------------

/** The large tile takes this fraction of the canvas height; the rest is the strip below. At 1280x720: 576 + 144. */
const val SPEAKER_MAIN_FRACTION = 0.8

/**
 * The rectangles of the "speaker large" layout on a [canvasW] x [canvasH] canvas: one large 16:9 tile on top (80 % of the height,
 * centred horizontally) and [stripCount] smaller 16:9 tiles in a row below it (centred, as large as the strip band allows). At 1280x720
 * that is a 1024x576 tile at x = 128 and tiles of 256x144 in the strip. With [stripCount] 0 the large tile fills the canvas as a
 * single grid cell would. All coordinates and sizes are even (yuv420p). Total for any input.
 */
fun speakerLayoutRects(
    canvasW: Int,
    canvasH: Int,
    stripCount: Int,
): Pair<CellRect, List<CellRect>> {
    if (canvasW <= 0 || canvasH <= 0) return CellRect(x = 0, y = 0, width = 2, height = 2) to emptyList()
    if (stripCount <= 0) {
        return (
            gridCellRects(count = 1, canvasW = canvasW, canvasH = canvasH, shape = GridShape(cols = 1, rows = 1)).firstOrNull()
                ?: CellRect(x = 0, y = 0, width = 2, height = 2)
        ) to
            emptyList()
    }
    var mainH = ((canvasH * SPEAKER_MAIN_FRACTION).roundToInt()).floorToEven().coerceAtLeast(2)
    var mainW = (mainH * 16 / 9).floorToEven().coerceAtLeast(2)
    if (mainW > canvasW) {
        mainW = canvasW.floorToEven().coerceAtLeast(2)
        mainH = (mainW * 9 / 16).floorToEven().coerceAtLeast(2)
    }
    val stripBand = (canvasH - mainH).floorToEven().coerceAtLeast(2)
    var tileW = (stripBand * 16 / 9).floorToEven().coerceAtLeast(2)
    if (tileW * stripCount > canvasW) tileW = (canvasW / stripCount).floorToEven().coerceAtLeast(2)
    val tileH = (tileW * 9 / 16).floorToEven().coerceAtLeast(2).coerceAtMost(stripBand)
    val main = CellRect(x = ((canvasW - mainW) / 2).floorToEven(), y = 0, width = mainW, height = mainH)
    val rowLeft = ((canvasW - tileW * stripCount) / 2).floorToEven()
    val rowTop = (mainH + (stripBand - tileH) / 2).floorToEven()
    val strip = (0 until stripCount).map { CellRect(x = rowLeft + it * tileW, y = rowTop, width = tileW, height = tileH) }
    return main to strip
}
