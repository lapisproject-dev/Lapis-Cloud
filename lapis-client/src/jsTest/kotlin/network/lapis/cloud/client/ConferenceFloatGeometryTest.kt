package network.lapis.cloud.client

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** V1.9.71 -- the pure geometry of the floating conference window: anchoring, clamping, snapping, the keyboard alternative. */
class ConferenceFloatGeometryTest {
    private val big = Viewport(1920, 1080)
    private val small = Viewport(768, 600)
    private val flat = Viewport(800, 400)

    private fun geometry(
        corner: Int,
        dx: Int = 16,
        dy: Int = 16,
        width: Int = 352,
    ) = FloatGeometry(corner, dx, dy, width)

    // ── height and width limits ───────────────────────────────────────────────

    @Test
    fun height_isHeaderStageStripAndControls_andEveryExtraBadgeRowAdds28() {
        assertEquals(44 + 198 + 64 + 52, floatHeight(352, 1))
        assertEquals(44 + 144 + 52, floatHeight(256, 1), "no strip below 320 px")
        assertEquals(floatHeight(352, 1) + 28, floatHeight(352, 2), "the second badge row raises the window")
        assertEquals(floatHeight(352, 1), floatHeight(352, 0), "no badges is the same as one row")
    }

    @Test
    fun maxWidth_isCappedByTheViewportAndByTheHeight_neverBelowTheMinimum() {
        assertEquals(640, floatMaxWidth(big, 1))
        assertEquals(640, floatMaxWidth(small, 1))
        val limited = floatMaxWidth(flat, 1)
        assertTrue(limited in 256..640, "limited by the height: $limited")
        assertTrue(limited < 640)
        assertTrue(floatHeight(limited, 1) <= flat.height - 32, "the window fits the short viewport")
        assertEquals(256, floatMaxWidth(Viewport(768, 200), 1), "never below the minimum")
        assertEquals(500 - 32, floatMaxWidth(Viewport(500, 1080), 1), "the viewport minus both margins")
    }

    @Test
    fun clampWidth_keepsTheWidthInsideTheLimits() {
        assertEquals(256, clampWidth(100, big, 1))
        assertEquals(640, clampWidth(2000, big, 1))
        assertEquals(352, clampWidth(352, big, 1))
    }

    // ── anchoring ─────────────────────────────────────────────────────────────

    @Test
    fun floatRect_anchorsToAllFourCorners() {
        assertEquals(FloatRect(1552, 706, 352, 358), floatRect(geometry(0), big, 1))
        assertEquals(FloatRect(16, 706, 352, 358), floatRect(geometry(1), big, 1))
        assertEquals(FloatRect(16, 16, 352, 358), floatRect(geometry(2), big, 1))
        assertEquals(FloatRect(1552, 16, 352, 358), floatRect(geometry(3), big, 1))
    }

    @Test
    fun floatRect_isClampedInsideTheMargins_andFollowsASmallerViewport() {
        val rect = floatRect(geometry(0, dx = 9000, dy = 9000), big, 1)
        assertEquals(16, rect.left)
        assertEquals(16, rect.top)
        val tight = floatRect(geometry(0, width = 640), small, 1)
        assertTrue(tight.left >= 16 && tight.left + tight.width <= small.width - 16, "inside horizontally: $tight")
        assertTrue(tight.top >= 16 && tight.top + tight.height <= small.height - 16, "inside vertically: $tight")
        // the same anchor on a shrunk viewport still lies inside it
        val shrunk = floatRect(geometry(0, dx = 400, dy = 300), Viewport(800, 600), 1)
        assertTrue(shrunk.left >= 16 && shrunk.top >= 16)
    }

    @Test
    fun floatRect_respectsTheSafeArea() {
        val vp = Viewport(1920, 1080, safeTop = 20, safeRight = 30, safeBottom = 40, safeLeft = 10)
        val rect = floatRect(geometry(0), vp, 1)
        assertEquals(1920 - 30 - 16 - 352, rect.left)
        assertEquals(1080 - 40 - 16 - 358, rect.top)
        val topLeft = floatRect(geometry(2), vp, 1)
        assertEquals(10 + 16, topLeft.left)
        assertEquals(20 + 16, topLeft.top)
    }

    @Test
    fun geometryFromRect_picksTheNearestCorner_andRoundTrips() {
        for (corner in 0..3) {
            val rect = floatRect(geometry(corner), big, 1)
            assertEquals(geometry(corner), geometryFromRect(rect, big), "corner $corner")
        }
    }

    // ── corners, sizes, snapping ──────────────────────────────────────────────

    @Test
    fun nextCorner_cyclesInFourStepsBackToTheStart_andResetsTheDistances() {
        var g = geometry(0, dx = 100, dy = 50)
        val seen = mutableListOf<Int>()
        repeat(4) {
            g = nextCorner(g)
            seen += g.corner
            assertEquals(16, g.dx)
            assertEquals(16, g.dy)
            assertEquals(352, g.width)
        }
        assertEquals(listOf(1, 2, 3, 0), seen)
    }

    @Test
    fun nextSize_cyclesSmallMediumLarge_fromTheNearestStep() {
        assertEquals(FloatSize.MEDIUM, nextSize(256))
        assertEquals(FloatSize.LARGE, nextSize(352))
        assertEquals(FloatSize.SMALL, nextSize(480))
        assertEquals(FloatSize.MEDIUM, nextSize(300), "300 is nearest to small")
        assertEquals(FloatSize.MEDIUM, nextSize(FloatSize.SMALL.widthPx))
        assertEquals(FloatSize.MEDIUM, sizeOf(352))
        assertNull(sizeOf(353))
    }

    @Test
    fun snapWidth_clicksIntoAStepWithin11px_butNotAt12() {
        assertEquals(256, snapWidth(256 + 11))
        assertEquals(268, snapWidth(256 + 12))
        assertEquals(352, snapWidth(352 - 11))
        assertEquals(339, snapWidth(352 - 13))
        assertEquals(480, snapWidth(480))
    }

    @Test
    fun snapToEdges_clingsWithin23px_butNotAt25() {
        val base = FloatRect(16, 300, 352, 358)
        assertEquals(16, snapToEdges(base.copy(left = 16 + 23), big).left)
        assertEquals(16 + 25, snapToEdges(base.copy(left = 16 + 25), big).left)
        val maxLeft = 1920 - 16 - 352
        assertEquals(maxLeft, snapToEdges(base.copy(left = maxLeft - 23), big).left)
        assertEquals(maxLeft - 25, snapToEdges(base.copy(left = maxLeft - 25), big).left)
        assertEquals(16, snapToEdges(base.copy(top = 16 + 23), big).top)
        assertEquals(16 + 25, snapToEdges(base.copy(top = 16 + 25), big).top)
    }

    @Test
    fun gripCorner_pointsTowardsTheMiddleOfTheViewport_inAllFourQuadrants() {
        assertEquals(2, gripCorner(floatRect(geometry(0), big, 1), big), "window bottom right: grip top left")
        assertEquals(3, gripCorner(floatRect(geometry(1), big, 1), big), "window bottom left: grip top right")
        assertEquals(0, gripCorner(floatRect(geometry(2), big, 1), big), "window top left: grip bottom right")
        assertEquals(1, gripCorner(floatRect(geometry(3), big, 1), big), "window top right: grip bottom left")
    }

    @Test
    fun coversFocus_isTrueAboveHalf_andFalseBelow() {
        val target = FloatRect(0, 0, 100, 100)
        assertFalse(coversFocus(target, FloatRect(0, 0, 49, 100)), "49 %")
        assertFalse(coversFocus(target, FloatRect(0, 0, 50, 100)), "exactly half is not 'more than half'")
        assertTrue(coversFocus(target, FloatRect(0, 0, 51, 100)), "51 %")
        assertFalse(coversFocus(target, FloatRect(200, 200, 50, 50)), "no overlap")
        assertFalse(coversFocus(FloatRect(0, 0, 0, 0), FloatRect(0, 0, 50, 50)), "an empty target")
    }

    // ── keyboard ──────────────────────────────────────────────────────────────

    @Test
    fun applyFloatKey_arrowsMoveBy16InScreenDirection() {
        val g = geometry(0, dx = 100, dy = 100)
        assertEquals(geometry(0, dx = 116, dy = 100), applyFloatKey(g, FloatKey.LEFT, false, big, 1))
        assertEquals(geometry(0, dx = 84, dy = 100), applyFloatKey(g, FloatKey.RIGHT, false, big, 1))
        assertEquals(geometry(0, dx = 100, dy = 116), applyFloatKey(g, FloatKey.UP, false, big, 1))
        assertEquals(geometry(0, dx = 100, dy = 84), applyFloatKey(g, FloatKey.DOWN, false, big, 1))
    }

    @Test
    fun applyFloatKey_staysInsideAtTheEdges() {
        val g = geometry(0)
        assertEquals(g, applyFloatKey(g, FloatKey.RIGHT, false, big, 1))
        assertEquals(g, applyFloatKey(g, FloatKey.DOWN, false, big, 1))
        val topLeft = geometry(2)
        assertEquals(topLeft, applyFloatKey(topLeft, FloatKey.LEFT, false, big, 1))
        assertEquals(topLeft, applyFloatKey(topLeft, FloatKey.UP, false, big, 1))
    }

    @Test
    fun applyFloatKey_shiftResizesBy32_andHomeGoesToTheDefaultCorner() {
        val g = geometry(1, dx = 40, dy = 40)
        assertEquals(384, applyFloatKey(g, FloatKey.RIGHT, true, big, 1).width)
        assertEquals(384, applyFloatKey(g, FloatKey.UP, true, big, 1).width)
        assertEquals(320, applyFloatKey(g, FloatKey.LEFT, true, big, 1).width)
        assertEquals(320, applyFloatKey(g, FloatKey.DOWN, true, big, 1).width)
        assertEquals(256, applyFloatKey(geometry(0, width = 256), FloatKey.LEFT, true, big, 1).width, "never below 256")
        assertEquals(640, applyFloatKey(geometry(0, width = 640), FloatKey.RIGHT, true, big, 1).width, "never above 640")
        val home = applyFloatKey(g, FloatKey.HOME, false, big, 1)
        assertEquals(DEFAULT_FLOAT_GEOMETRY.corner, home.corner)
        assertEquals(DEFAULT_FLOAT_GEOMETRY.dx, home.dx)
        assertEquals(DEFAULT_FLOAT_GEOMETRY.dy, home.dy)
        assertEquals(352, home.width, "the width stays")
    }

    @Test
    fun theWidthIsNeverOutsideTheLimits_whateverTheKeysDo() {
        var g = geometry(0)
        repeat(30) { g = applyFloatKey(g, FloatKey.RIGHT, true, flat, 1) }
        assertTrue(g.width <= floatMaxWidth(flat, 1))
        repeat(30) { g = applyFloatKey(g, FloatKey.LEFT, true, flat, 1) }
        assertEquals(256, g.width)
    }

    // ── presentation ──────────────────────────────────────────────────────────

    @Test
    fun floatSizeBucket_hasNoStripBelow320() {
        assertEquals(FloatSize.SMALL, floatSizeBucket(256))
        assertEquals(FloatSize.SMALL, floatSizeBucket(319))
        assertEquals(FloatSize.MEDIUM, floatSizeBucket(320))
        assertEquals(FloatSize.MEDIUM, floatSizeBucket(352))
        assertEquals(FloatSize.LARGE, floatSizeBucket(480))
    }
}
