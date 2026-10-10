package network.lapis.cloud.client

import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * V1.9.92 -- the best-fit tile grid of the video conference: pure arithmetic, no DOM access (a tripwire guards that).
 *
 * The call view hands this file the free area (width, height) and the number of pictures; it answers with the column/row count and the
 * largest 16:9 tile that fits all of them without scrolling. The caller writes only two CSS variables from the answer
 * (`--lapis-tile-w` / `--lapis-tile-h`), so a resize never re-parents a tile (V1.4.19: a `<video>` that leaves the document pauses).
 *
 * @property scrolls the floor [CONFERENCE_TILE_MIN_WIDTH_PX] took effect: the area has to grow beyond the height it was given, the page scrolls
 */
internal data class ConferenceTileGrid(
    val columns: Int,
    val rows: Int,
    val tileWidth: Int,
    val tileHeight: Int,
    val scrolls: Boolean,
)

internal const val CONFERENCE_TILE_ASPECT = 16.0 / 9.0
internal const val CONFERENCE_TILE_GAP_PX = 8.0
internal const val CONFERENCE_TILE_MIN_WIDTH_PX = 160.0

/** A layout with another column count only wins when its tile is at least 6 % larger than the one of the previous layout (no flicker at the edge). */
internal const val CONFERENCE_TILE_GRID_HYSTERESIS = 1.06

/** Below this tile width the speaker badge shows only its symbol and the name shrinks (class `lapis-conference-tile--small`). */
internal const val CONFERENCE_TILE_SMALL_WIDTH_PX = 220

/** Width of the side rail (roster / chat / voting) in full screen, as in `theme.css`: `clamp(280px, 28vw, 380px)`. */
internal const val CONFERENCE_RAIL_MIN_WIDTH_PX = 280.0
internal const val CONFERENCE_RAIL_MAX_WIDTH_PX = 380.0
internal const val CONFERENCE_RAIL_VIEWPORT_SHARE = 0.28

/** Same breakpoint as `CONFERENCE_NARROW_VIEWPORT_MEDIA_MAX_WIDTH` (below it the panels are bottom sheets and no rail exists). */
internal const val CONFERENCE_RAIL_MIN_VIEWPORT_PX = 768.0

internal const val CONFERENCE_TILE_AREA_MIN_HEIGHT_PX = 240.0

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
 * Every column count from 1 to [count] is tried; the one with the largest tile wins, a tie goes to the layout with fewer empty cells,
 * then to fewer columns. [previousColumns] (the answer of the last call) keeps its column count unless the best layout is at least
 * [CONFERENCE_TILE_GRID_HYSTERESIS] times larger. A tile narrower than [minTileWidth] is lifted to it: the columns that fit the width
 * stay, the area becomes taller than [height] and [ConferenceTileGrid.scrolls] says so.
 *
 * Total: a zero or negative count, a zero/negative/NaN/infinite area or aspect answer an empty grid, never an exception.
 */
internal fun computeConferenceTileGrid(
    count: Int,
    width: Double,
    height: Double,
    aspect: Double = CONFERENCE_TILE_ASPECT,
    gap: Double = CONFERENCE_TILE_GAP_PX,
    previousColumns: Int? = null,
    minTileWidth: Double = CONFERENCE_TILE_MIN_WIDTH_PX,
): ConferenceTileGrid {
    if (count <= 0 || !width.usable() || !height.usable() || !aspect.usable()) return NO_TILE_GRID
    val safeGap = if (gap.isFinite() && gap >= 0.0) gap else 0.0

    var bestColumns = 1
    var bestWidth = 0.0
    for (columns in 1..count) {
        val w = fittedWidth(count, columns, width, height, aspect, safeGap)
        if (w <= 0.0) continue
        val better =
            when {
                w > bestWidth + TIE_EPSILON -> true
                w < bestWidth - TIE_EPSILON -> false
                else -> {
                    val emptyHere = ceil(count.toDouble() / columns).toInt() * columns - count
                    val emptyBest = ceil(count.toDouble() / bestColumns).toInt() * bestColumns - count
                    emptyHere < emptyBest
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
        val previousWidth = fittedWidth(count, previousColumns, width, height, aspect, safeGap)
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

/**
 * The height of the tile area, from window values only -- never from the area's own size (it grows when [ConferenceTileGrid.scrolls],
 * which would feed back into itself). [areaTop] is the distance of the area from the top of the scrolled content (scroll offset included).
 */
internal fun conferenceTileAreaHeight(
    viewportHeight: Double,
    areaTop: Double,
    barHeight: Double,
    bottomPad: Double,
    minHeight: Double = CONFERENCE_TILE_AREA_MIN_HEIGHT_PX,
): Double {
    if (!viewportHeight.isFinite() || !areaTop.isFinite() || !barHeight.isFinite() || !bottomPad.isFinite()) return minHeight
    return max(minHeight, viewportHeight - areaTop - barHeight - bottomPad)
}

/**
 * The width the full-screen side rail takes from the tile area. 0 without a visible panel, outside full screen (in the normal view the
 * panels sit below the tiles) and below 768 px (there they are bottom sheets over the tiles).
 */
internal fun conferenceRailInset(
    railVisible: Boolean,
    fullscreen: Boolean,
    viewportWidth: Double,
): Double {
    if (!railVisible || !fullscreen || !viewportWidth.isFinite() || viewportWidth < CONFERENCE_RAIL_MIN_VIEWPORT_PX) return 0.0
    return min(CONFERENCE_RAIL_MAX_WIDTH_PX, max(CONFERENCE_RAIL_MIN_WIDTH_PX, viewportWidth * CONFERENCE_RAIL_VIEWPORT_SHARE))
}

/** Class of a tile narrower than [CONFERENCE_TILE_SMALL_WIDTH_PX]: only the symbol of the speaker badge, a shorter name. */
internal const val CONFERENCE_TILE_SMALL_CLASS = "lapis-conference-tile--small"

/** Marker on the call panel while a call is on screen (the page column is full width then, see `theme.css`). */
internal const val CONFERENCE_CALL_ACTIVE_CLASS = "lapis-conference-call-active"

/** The fixed thumbnail of the filmstrip (16:9). */
internal const val CONFERENCE_FILMSTRIP_TILE_WIDTH_PX = 146
internal const val CONFERENCE_FILMSTRIP_TILE_HEIGHT_PX = 82

/** The number of a CSS length such as `"16px"`; anything else (`""`, `"auto"`, `"normal"`, NaN) is 0. */
internal fun conferenceCssPx(value: String): Double {
    val number = value.trim().removeSuffix("px").toDoubleOrNull() ?: return 0.0
    return if (number.isFinite()) number else 0.0
}
