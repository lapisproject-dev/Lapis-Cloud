package network.lapis.cloud.client

import kotlin.math.abs
import kotlin.math.roundToInt

/*
 * V1.9.71 -- the floating conference window: pure geometry, presentation choice and the storage codec. No DOM, no `window`, no `localStorage`
 * (the one storage access lives in `ConferenceFloatStore.kt`), so every rule here is covered by a plain jsTest.
 *
 * The window is anchored to the nearest corner of the viewport (corner + two distances) instead of absolute pixels, so a viewport that
 * shrinks or grows never leaves it off screen: the absolute rectangle is recomputed from the anchor and clamped on every paint.
 */

/** How the dock presents a running conference right now (derived, never stored). */
internal enum class DockPresentation { BAR, FLOAT, FULL }

/** The stored wish: the floating window or the bar when the view is away. */
internal enum class FloatMode { BAR, FLOAT }

internal enum class FloatSize(
    val widthPx: Int,
) {
    SMALL(256),
    MEDIUM(352),
    LARGE(480),
}

internal const val FLOAT_MIN_VIEWPORT_PX = 768
internal const val FLOAT_MARGIN_PX = 16
internal const val FLOAT_HEADER_PX = 44
internal const val FLOAT_CONTROLS_PX = 52
internal const val FLOAT_STRIP_PX = 64

/** Height of every additional row of consent / status badges beyond the first. */
internal const val FLOAT_BADGE_ROW_PX = 28
internal const val FLOAT_MIN_WIDTH_PX = 256
internal const val FLOAT_MAX_WIDTH_PX = 640
internal const val FLOAT_KEY_STEP_PX = 16
internal const val FLOAT_KEY_RESIZE_PX = 32
internal const val FLOAT_DRAG_THRESHOLD_PX = 4
internal const val FLOAT_EDGE_SNAP_PX = 24
internal const val FLOAT_SIZE_SNAP_PX = 12

/** From this width on the window shows the tile strip under the main picture. */
internal const val FLOAT_STRIP_MIN_WIDTH_PX = 320

internal data class Viewport(
    val width: Int,
    val height: Int,
    val safeTop: Int = 0,
    val safeRight: Int = 0,
    val safeBottom: Int = 0,
    val safeLeft: Int = 0,
)

/** [corner]: 0 = bottom right, 1 = bottom left, 2 = top left, 3 = top right. [dx]/[dy]: distance from the two anchoring edges. */
internal data class FloatGeometry(
    val corner: Int,
    val dx: Int,
    val dy: Int,
    val width: Int,
)

internal data class FloatRect(
    val left: Int,
    val top: Int,
    val width: Int,
    val height: Int,
)

internal data class FloatPreference(
    val mode: FloatMode,
    val geometry: FloatGeometry,
)

internal val DEFAULT_FLOAT_GEOMETRY =
    FloatGeometry(corner = 0, dx = FLOAT_MARGIN_PX, dy = FLOAT_MARGIN_PX, width = FloatSize.MEDIUM.widthPx)
internal val DEFAULT_FLOAT_PREFERENCE = FloatPreference(FloatMode.FLOAT, DEFAULT_FLOAT_GEOMETRY)

/**
 * Pure: how the running conference is presented. The view in the route always wins ([DockPresentation.FULL]); the floating window needs a
 * live call (not a join in flight, not an end state), the stored wish and a wide viewport; everything else is the mini bar.
 */
internal fun dockPresentationOf(
    state: DockState,
    preference: FloatMode,
    wide: Boolean,
): DockPresentation =
    when {
        state is DockState.Idle -> DockPresentation.BAR
        state.isAttached -> DockPresentation.FULL
        (state is DockState.Live || state is DockState.Resolving) && preference == FloatMode.FLOAT && wide -> DockPresentation.FLOAT
        else -> DockPresentation.BAR
    }

/** The 16:9 stage height of a window of [width] px. */
internal fun floatStageHeight(width: Int): Int = (width * 9.0 / 16.0).roundToInt()

/** Total height: header, 16:9 stage, tile strip (from [FLOAT_STRIP_MIN_WIDTH_PX]), controls and every badge row beyond the first. */
internal fun floatHeight(
    width: Int,
    badgeRows: Int,
): Int {
    val strip = if (width >= FLOAT_STRIP_MIN_WIDTH_PX) FLOAT_STRIP_PX else 0
    val extraRows = (badgeRows - 1).coerceAtLeast(0)
    return FLOAT_HEADER_PX + floatStageHeight(width) + strip + FLOAT_CONTROLS_PX + extraRows * FLOAT_BADGE_ROW_PX
}

private fun Viewport.usableWidth(): Int = width - safeLeft - safeRight

private fun Viewport.usableHeight(): Int = height - safeTop - safeBottom

/** The widest the window may be: [FLOAT_MAX_WIDTH_PX], the viewport minus its margins, and a height that still fits. Never below the minimum. */
internal fun floatMaxWidth(
    vp: Viewport,
    badgeRows: Int,
): Int {
    var width = minOf(FLOAT_MAX_WIDTH_PX, vp.usableWidth() - 2 * FLOAT_MARGIN_PX)
    val maxHeight = vp.usableHeight() - 2 * FLOAT_MARGIN_PX
    while (width > FLOAT_MIN_WIDTH_PX && floatHeight(width, badgeRows) > maxHeight) width--
    return width.coerceAtLeast(FLOAT_MIN_WIDTH_PX)
}

internal fun clampWidth(
    width: Int,
    vp: Viewport,
    badgeRows: Int,
): Int = width.coerceIn(FLOAT_MIN_WIDTH_PX, floatMaxWidth(vp, badgeRows))

private fun clampRect(
    rect: FloatRect,
    vp: Viewport,
): FloatRect {
    val minLeft = vp.safeLeft + FLOAT_MARGIN_PX
    val maxLeft = maxOf(minLeft, vp.width - vp.safeRight - FLOAT_MARGIN_PX - rect.width)
    val minTop = vp.safeTop + FLOAT_MARGIN_PX
    val maxTop = maxOf(minTop, vp.height - vp.safeBottom - FLOAT_MARGIN_PX - rect.height)
    return rect.copy(left = rect.left.coerceIn(minLeft, maxLeft), top = rect.top.coerceIn(minTop, maxTop))
}

/** The absolute rectangle of [g] in [vp]: from the anchor, then clamped so the window always lies fully inside the margins. */
internal fun floatRect(
    g: FloatGeometry,
    vp: Viewport,
    badgeRows: Int,
): FloatRect {
    val width = clampWidth(g.width, vp, badgeRows)
    val height = floatHeight(width, badgeRows)
    val right = g.corner == 0 || g.corner == 3
    val bottom = g.corner == 0 || g.corner == 1
    val left = if (right) vp.width - vp.safeRight - g.dx - width else vp.safeLeft + g.dx
    val top = if (bottom) vp.height - vp.safeBottom - g.dy - height else vp.safeTop + g.dy
    return clampRect(FloatRect(left, top, width, height), vp)
}

/** The anchor that describes [rect]: the nearest corner of the viewport plus the distances to its two edges. */
internal fun geometryFromRect(
    rect: FloatRect,
    vp: Viewport,
): FloatGeometry {
    val right = rect.left + rect.width / 2 >= vp.width / 2
    val bottom = rect.top + rect.height / 2 >= vp.height / 2
    val corner =
        when {
            right && bottom -> 0
            !right && bottom -> 1
            !right -> 2
            else -> 3
        }
    val dx = if (right) vp.width - vp.safeRight - (rect.left + rect.width) else rect.left - vp.safeLeft
    val dy = if (bottom) vp.height - vp.safeBottom - (rect.top + rect.height) else rect.top - vp.safeTop
    return FloatGeometry(corner, dx.coerceAtLeast(0), dy.coerceAtLeast(0), rect.width)
}

/** Within [FLOAT_EDGE_SNAP_PX] of an edge the window clings to the margin of that edge (margin plus safe area). */
internal fun snapToEdges(
    rect: FloatRect,
    vp: Viewport,
): FloatRect {
    val minLeft = vp.safeLeft + FLOAT_MARGIN_PX
    val maxLeft = vp.width - vp.safeRight - FLOAT_MARGIN_PX - rect.width
    val minTop = vp.safeTop + FLOAT_MARGIN_PX
    val maxTop = vp.height - vp.safeBottom - FLOAT_MARGIN_PX - rect.height
    val left =
        when {
            rect.left - minLeft < FLOAT_EDGE_SNAP_PX -> minLeft
            maxLeft - rect.left < FLOAT_EDGE_SNAP_PX -> maxLeft
            else -> rect.left
        }
    val top =
        when {
            rect.top - minTop < FLOAT_EDGE_SNAP_PX -> minTop
            maxTop - rect.top < FLOAT_EDGE_SNAP_PX -> maxTop
            else -> rect.top
        }
    return rect.copy(left = left, top = top)
}

/** "In die nächste Ecke": bottom right, bottom left, top left, top right, and round; the distances reset to the margin. */
internal fun nextCorner(g: FloatGeometry): FloatGeometry = g.copy(corner = (g.corner + 1) % 4, dx = FLOAT_MARGIN_PX, dy = FLOAT_MARGIN_PX)

/** The size step that is exactly [width], or `null` for a free width. */
internal fun sizeOf(width: Int): FloatSize? = FloatSize.entries.firstOrNull { it.widthPx == width }

/** Small -> medium -> large -> small, starting from the step nearest to [width]. */
internal fun nextSize(width: Int): FloatSize {
    val nearest = FloatSize.entries.minByOrNull { abs(it.widthPx - width) } ?: FloatSize.MEDIUM
    return FloatSize.entries[(nearest.ordinal + 1) % FloatSize.entries.size]
}

/** A width less than [FLOAT_SIZE_SNAP_PX] beside a size step clicks into it. */
internal fun snapWidth(width: Int): Int = FloatSize.entries.firstOrNull { abs(it.widthPx - width) < FLOAT_SIZE_SNAP_PX }?.widthPx ?: width

/**
 * The corner of [rect] that points towards the middle of the viewport (0 = bottom right, 1 = bottom left, 2 = top left, 3 = top right, as
 * for [FloatGeometry.corner]). The resize grip sits there; the opposite corner stays where it is while the width changes.
 */
internal fun gripCorner(
    rect: FloatRect,
    vp: Viewport,
): Int {
    val windowIsRight = rect.left + rect.width / 2 >= vp.width / 2
    val windowIsBottom = rect.top + rect.height / 2 >= vp.height / 2
    return when {
        windowIsRight && windowIsBottom -> 2
        !windowIsRight && windowIsBottom -> 3
        !windowIsRight -> 0
        else -> 1
    }
}

/** Whether [window] hides more than half of [target] (WCAG 2.4.11: a focused element must not be mostly covered). */
internal fun coversFocus(
    target: FloatRect,
    window: FloatRect,
): Boolean {
    val overlapWidth = minOf(target.left + target.width, window.left + window.width) - maxOf(target.left, window.left)
    val overlapHeight = minOf(target.top + target.height, window.top + window.height) - maxOf(target.top, window.top)
    if (overlapWidth <= 0 || overlapHeight <= 0 || target.width <= 0 || target.height <= 0) return false
    val covered = overlapWidth.toLong() * overlapHeight
    val area = target.width.toLong() * target.height
    return covered * 2 > area
}

internal enum class FloatKey { LEFT, RIGHT, UP, DOWN, HOME }

/**
 * The keyboard alternative to dragging and to the resize grip (WCAG 2.5.7): arrows move by [FLOAT_KEY_STEP_PX] in screen direction; with
 * shift, right and up widen and left and down narrow by [FLOAT_KEY_RESIZE_PX]; Home goes back to the default corner (the width stays).
 */
internal fun applyFloatKey(
    g: FloatGeometry,
    key: FloatKey,
    shift: Boolean,
    vp: Viewport,
    badgeRows: Int,
): FloatGeometry {
    if (key == FloatKey.HOME) {
        return g.copy(corner = DEFAULT_FLOAT_GEOMETRY.corner, dx = DEFAULT_FLOAT_GEOMETRY.dx, dy = DEFAULT_FLOAT_GEOMETRY.dy)
    }
    if (shift) {
        val delta =
            when (key) {
                FloatKey.RIGHT, FloatKey.UP -> FLOAT_KEY_RESIZE_PX
                else -> -FLOAT_KEY_RESIZE_PX
            }
        val width = clampWidth(clampWidth(g.width, vp, badgeRows) + delta, vp, badgeRows)
        return g.copy(width = width)
    }
    val rect = floatRect(g, vp, badgeRows)
    val moved =
        when (key) {
            FloatKey.LEFT -> rect.copy(left = rect.left - FLOAT_KEY_STEP_PX)
            FloatKey.RIGHT -> rect.copy(left = rect.left + FLOAT_KEY_STEP_PX)
            FloatKey.UP -> rect.copy(top = rect.top - FLOAT_KEY_STEP_PX)
            else -> rect.copy(top = rect.top + FLOAT_KEY_STEP_PX)
        }
    return geometryFromRect(clampRect(moved, vp), vp)
}

// --- storage codec (pure) -----------------------------------------------------------------------------------------------------------

internal const val FLOAT_STORAGE_KEY = "lapis.conferenceFloat.v1"

/** The only shape ever written and the only shape ever read back: ASCII digits only (JS `\d`), three-digit width. */
internal val FLOAT_STORAGE_PATTERN = Regex("""^v1\|(bar|float)\|[0-3]\|\d{1,4}\|\d{1,4}\|\d{3}$""")

private const val FLOAT_STORAGE_MAX_DISTANCE_PX = 4000

internal fun encodeFloatPreference(p: FloatPreference): String {
    val g = p.geometry
    val mode = if (p.mode == FloatMode.FLOAT) "float" else "bar"
    return "v1|$mode|${g.corner.coerceIn(0, 3)}|${g.dx.coerceIn(0, FLOAT_STORAGE_MAX_DISTANCE_PX)}|" +
        "${g.dy.coerceIn(0, FLOAT_STORAGE_MAX_DISTANCE_PX)}|${g.width.coerceIn(FLOAT_MIN_WIDTH_PX, FLOAT_MAX_WIDTH_PX)}"
}

internal sealed class FloatDecode {
    data class Ok(
        val pref: FloatPreference,
    ) : FloatDecode()

    data object Absent : FloatDecode()

    data object Invalid : FloatDecode()
}

/** Whitelist parser: anything that is not exactly the encoded shape within its bounds is [FloatDecode.Invalid]. */
internal fun decodeFloatPreference(raw: String?): FloatDecode {
    if (raw.isNullOrEmpty()) return FloatDecode.Absent
    if (!FLOAT_STORAGE_PATTERN.matches(raw)) return FloatDecode.Invalid
    val parts = raw.split('|')
    if (parts.size != 6) return FloatDecode.Invalid
    val mode = if (parts[1] == "float") FloatMode.FLOAT else FloatMode.BAR
    val corner = parts[2].toIntOrNull() ?: return FloatDecode.Invalid
    val dx = parts[3].toIntOrNull() ?: return FloatDecode.Invalid
    val dy = parts[4].toIntOrNull() ?: return FloatDecode.Invalid
    val width = parts[5].toIntOrNull() ?: return FloatDecode.Invalid
    if (corner !in 0..3) return FloatDecode.Invalid
    if (dx !in 0..FLOAT_STORAGE_MAX_DISTANCE_PX || dy !in 0..FLOAT_STORAGE_MAX_DISTANCE_PX) return FloatDecode.Invalid
    if (width !in FLOAT_MIN_WIDTH_PX..FLOAT_MAX_WIDTH_PX) return FloatDecode.Invalid
    return FloatDecode.Ok(FloatPreference(mode, FloatGeometry(corner, dx, dy, width)))
}
