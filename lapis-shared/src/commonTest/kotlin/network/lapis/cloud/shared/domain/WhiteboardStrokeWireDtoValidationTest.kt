package network.lapis.cloud.shared.domain

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Security-audit fix (V1.0 Videokonferenzen Wave 7 "Whiteboard") -- covers
 * [WhiteboardStrokeWireDto.isStructurallyValid], the shared bounds-check function that gates BOTH the
 * server's `ConferenceWhiteboardService.commitStroke` RPC path AND the client's
 * `LiveKitRoomSession.dataReceived` LiveKit-data-channel receive path (see that class KDoc "Security-
 * audit fix" for why the latter is the ONLY enforcement point on that transport -- the server never
 * observes it at all). Lives in `commonTest` (not `lapis-server`'s JVM-only test suite) precisely
 * because the function itself lives in `commonMain` and must behave identically on both platforms.
 *
 * Tamper cases below mirror the exact DoS payload shapes the audit finding described: an oversized
 * `points` array, out-of-canvas coordinates, a non-finite coordinate, an out-of-range `strokeWidth`,
 * and an off-palette `color` -- each is a value a raw script publishing directly onto the LiveKit data
 * channel (bypassing the UI entirely) could send.
 */
class WhiteboardStrokeWireDtoValidationTest {
    private fun validStroke(
        strokeId: String = "member-1-1700000000000-abc",
        tool: WhiteboardTool = WhiteboardTool.PEN,
        color: String = WHITEBOARD_COLORS.first(),
        strokeWidth: Double = 4.0,
        points: List<WhiteboardPointDto> = listOf(WhiteboardPointDto(10.0, 10.0), WhiteboardPointDto(20.0, 20.0)),
    ) = WhiteboardStrokeWireDto(strokeId, tool, color, strokeWidth, points)

    // ── happy path ──────────────────────────────────────────────────────────

    @Test
    fun wellFormedPenStroke_isValid() {
        assertTrue(validStroke().isStructurallyValid())
    }

    @Test
    fun wellFormedEraserStroke_withAnyColor_isValid() {
        assertTrue(validStroke(tool = WhiteboardTool.ERASER, color = "#abcdef").isStructurallyValid())
    }

    @Test
    fun singlePointStroke_isValid() {
        assertTrue(validStroke(points = listOf(WhiteboardPointDto(0.0, 0.0))).isStructurallyValid())
    }

    @Test
    fun boundaryCoordinatesAtCanvasEdges_areValid() {
        val corners =
            listOf(
                WhiteboardPointDto(0.0, 0.0),
                WhiteboardPointDto(WHITEBOARD_CANVAS_WIDTH.toDouble(), WHITEBOARD_CANVAS_HEIGHT.toDouble()),
            )
        assertTrue(validStroke(points = corners).isStructurallyValid())
    }

    @Test
    fun strokeWidthAtBounds_isValid() {
        assertTrue(validStroke(strokeWidth = WHITEBOARD_MIN_STROKE_WIDTH).isStructurallyValid())
        assertTrue(validStroke(strokeWidth = WHITEBOARD_MAX_STROKE_WIDTH).isStructurallyValid())
    }

    @Test
    fun pointCountAtCap_isValid() {
        val points = (1..WHITEBOARD_MAX_POINTS_PER_STROKE).map { WhiteboardPointDto(1.0, 1.0) }
        assertTrue(validStroke(points = points).isStructurallyValid())
    }

    // ── tamper cases: the audit's own DoS payload shapes ───────────────────

    @Test
    fun pointCountAboveCap_isRejected_theCoreAuditFindingsPayloadShape() {
        val tooMany = (1..(WHITEBOARD_MAX_POINTS_PER_STROKE + 1)).map { WhiteboardPointDto(1.0, 1.0) }
        assertFalse(validStroke(points = tooMany).isStructurallyValid())
    }

    @Test
    fun emptyPoints_isRejected() {
        assertFalse(validStroke(points = emptyList()).isStructurallyValid())
    }

    @Test
    fun outOfBoundsCoordinate_isRejected() {
        assertFalse(validStroke(points = listOf(WhiteboardPointDto(99_999.0, 1.0))).isStructurallyValid())
        assertFalse(validStroke(points = listOf(WhiteboardPointDto(1.0, 99_999.0))).isStructurallyValid())
        assertFalse(validStroke(points = listOf(WhiteboardPointDto(-1.0, 1.0))).isStructurallyValid())
    }

    @Test
    fun nonFiniteCoordinate_isRejected() {
        assertFalse(validStroke(points = listOf(WhiteboardPointDto(Double.NaN, 1.0))).isStructurallyValid())
        assertFalse(validStroke(points = listOf(WhiteboardPointDto(Double.POSITIVE_INFINITY, 1.0))).isStructurallyValid())
    }

    @Test
    fun strokeWidthOutsideBounds_isRejected() {
        assertFalse(validStroke(strokeWidth = WHITEBOARD_MIN_STROKE_WIDTH - 0.1).isStructurallyValid())
        assertFalse(validStroke(strokeWidth = WHITEBOARD_MAX_STROKE_WIDTH + 0.1).isStructurallyValid())
        assertFalse(validStroke(strokeWidth = Double.NaN).isStructurallyValid())
    }

    @Test
    fun offPaletteColor_onPenTool_isRejected() {
        assertFalse(validStroke(color = "#abcdef").isStructurallyValid())
    }

    @Test
    fun blankOrOversizedStrokeId_isRejected() {
        assertFalse(validStroke(strokeId = "").isStructurallyValid())
        assertFalse(validStroke(strokeId = "   ").isStructurallyValid())
        assertFalse(validStroke(strokeId = "x".repeat(WHITEBOARD_MAX_STROKE_ID_LENGTH + 1)).isStructurallyValid())
    }

    @Test
    fun strokeIdAtLengthCap_isValid() {
        assertTrue(validStroke(strokeId = "x".repeat(WHITEBOARD_MAX_STROKE_ID_LENGTH)).isStructurallyValid())
    }
}
