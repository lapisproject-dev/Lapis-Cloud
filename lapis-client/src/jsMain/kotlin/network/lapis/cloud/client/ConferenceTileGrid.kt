package network.lapis.cloud.client

import kotlin.math.max
import kotlin.math.min

/*
 * V1.9.92 -- the geometry helpers of the video conference's tile area: pure arithmetic, no DOM access (a tripwire guards that).
 *
 * V1.9.109: the best-fit grid itself (`computeConferenceTileGrid`, `ConferenceTileGrid`, the 16:9 aspect and the hysteresis factor)
 * moved to `lapis-shared` (`network.lapis.cloud.shared.conference`), because the recording composition on the server lays its pictures out
 * with the very same function. This file keeps the live view's own parameters -- the 8 px gap and the 160 px tile floor, which the call
 * site passes explicitly -- and the helpers that only make sense in the browser's tile area.
 */

internal const val CONFERENCE_TILE_GAP_PX = 8.0
internal const val CONFERENCE_TILE_MIN_WIDTH_PX = 160.0

/** Below this tile width the speaker badge shows only its symbol and the name shrinks (class `lapis-conference-tile--small`). */
internal const val CONFERENCE_TILE_SMALL_WIDTH_PX = 220

/** Width of the side rail (roster / chat / voting) in full screen, as in `theme.css`: `clamp(280px, 28vw, 380px)`. */
internal const val CONFERENCE_RAIL_MIN_WIDTH_PX = 280.0
internal const val CONFERENCE_RAIL_MAX_WIDTH_PX = 380.0
internal const val CONFERENCE_RAIL_VIEWPORT_SHARE = 0.28

/** Same breakpoint as `CONFERENCE_NARROW_VIEWPORT_MEDIA_MAX_WIDTH` (below it the panels are bottom sheets and no rail exists). */
internal const val CONFERENCE_RAIL_MIN_VIEWPORT_PX = 768.0

internal const val CONFERENCE_TILE_AREA_MIN_HEIGHT_PX = 240.0

/**
 * The height of the tile area, from window values only -- never from the area's own size (it grows when `ConferenceTileGrid.scrolls`,
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

/** The fixed thumbnail of the filmstrip (16:9). */
internal const val CONFERENCE_FILMSTRIP_TILE_WIDTH_PX = 146
internal const val CONFERENCE_FILMSTRIP_TILE_HEIGHT_PX = 82

/** The number of a CSS length such as `"16px"`; anything else (`""`, `"auto"`, `"normal"`, NaN) is 0. */
internal fun conferenceCssPx(value: String): Double {
    val number = value.trim().removeSuffix("px").toDoubleOrNull() ?: return 0.0
    return if (number.isFinite()) number else 0.0
}
