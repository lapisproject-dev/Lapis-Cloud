package network.lapis.cloud.client

import network.lapis.cloud.shared.domain.VideoOverlayItemDto
import network.lapis.cloud.shared.domain.VideoOverlayKind
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

private val ELLIPSIS = Char(0x2026).toString()

private fun codePoint(value: Int): String = js("String.fromCodePoint(value)").unsafeCast<String>()

/** V1.9.129 -- the geometry of the four corners, the drawing order and the name pill, as pure functions. */
class ConferenceVideoOverlayGeometryTest {
    private fun rects(
        w: Int,
        h: Int,
        vararg items: Pair<OverlayCorner, OverlayItemGeometry>,
    ) = videoOverlayCornerRects(w, h, items.toMap())

    @Test
    fun oneElementPerSize_1280x720_exactPixels() {
        // short edge 720: margin 28.8, heights 72 / 108 / 158.4, aspect 1
        val small = rects(1280, 720, OverlayCorner.TOP_LEFT to OverlayItemGeometry(OverlaySize.SMALL, 1.0)).getValue(OverlayCorner.TOP_LEFT)
        assertEquals(OverlayRect(x = 28, y = 28, width = 72, height = 72), small)
        val medium =
            rects(
                1280,
                720,
                OverlayCorner.TOP_LEFT to OverlayItemGeometry(OverlaySize.MEDIUM, 1.0),
            ).getValue(OverlayCorner.TOP_LEFT)
        assertEquals(OverlayRect(28, 28, 108, 108), medium)
        val large = rects(1280, 720, OverlayCorner.TOP_LEFT to OverlayItemGeometry(OverlaySize.LARGE, 1.0)).getValue(OverlayCorner.TOP_LEFT)
        assertEquals(OverlayRect(28, 28, 158, 158), large)
    }

    @Test
    fun theFourCornersSitInTheFourCorners() {
        val all =
            OverlayCorner.entries.associateWith { OverlayItemGeometry(OverlaySize.MEDIUM, 2.0) }
        val r = videoOverlayCornerRects(1280, 720, all)
        assertEquals(28, r.getValue(OverlayCorner.TOP_LEFT).x)
        assertEquals(28, r.getValue(OverlayCorner.TOP_LEFT).y)
        val tr = r.getValue(OverlayCorner.TOP_RIGHT)
        assertTrue(abs(1280 - 28 - (tr.x + tr.width)) <= 1)
        assertEquals(28, tr.y)
        val bl = r.getValue(OverlayCorner.BOTTOM_LEFT)
        assertEquals(28, bl.x)
        assertTrue(abs(720 - 28 - (bl.y + bl.height)) <= 1)
        val br = r.getValue(OverlayCorner.BOTTOM_RIGHT)
        assertTrue(abs(1280 - 28 - (br.x + br.width)) <= 1)
        assertTrue(abs(720 - 28 - (br.y + br.height)) <= 1)
    }

    @Test
    fun portraitAndSquareFramesUseTheShortEdge() {
        val portrait =
            rects(
                720,
                1280,
                OverlayCorner.TOP_LEFT to OverlayItemGeometry(OverlaySize.MEDIUM, 1.0),
            ).getValue(OverlayCorner.TOP_LEFT)
        assertEquals(OverlayRect(28, 28, 108, 108), portrait) // short edge 720 in both orientations
        val square = rects(480, 480, OverlayCorner.TOP_LEFT to OverlayItemGeometry(OverlaySize.LARGE, 1.0)).getValue(OverlayCorner.TOP_LEFT)
        assertEquals(OverlayRect(19, 19, 106, 106), square)
        val vga = rects(640, 480, OverlayCorner.TOP_LEFT to OverlayItemGeometry(OverlaySize.SMALL, 1.0)).getValue(OverlayCorner.TOP_LEFT)
        assertEquals(OverlayRect(19, 19, 48, 48), vga)
    }

    @Test
    fun aVeryWideLogoIsCappedAt40PercentOfTheFrameWidth() {
        val wide = rects(1280, 720, OverlayCorner.TOP_LEFT to OverlayItemGeometry(OverlaySize.LARGE, 8.0)).getValue(OverlayCorner.TOP_LEFT)
        assertEquals(512, wide.width)
        assertTrue(abs(wide.height - 64) <= 1) // the height shrinks with it: the shape is kept
        assertTrue(abs(wide.width.toDouble() / wide.height - 8.0) < 0.2)
    }

    @Test
    fun twoWideElementsOfARowAreScaledToFit() {
        val r =
            rects(
                400,
                300,
                OverlayCorner.TOP_LEFT to OverlayItemGeometry(OverlaySize.LARGE, 6.0),
                OverlayCorner.TOP_RIGHT to OverlayItemGeometry(OverlaySize.LARGE, 6.0),
            )
        val left = r.getValue(OverlayCorner.TOP_LEFT)
        val right = r.getValue(OverlayCorner.TOP_RIGHT)
        assertTrue(left.x + left.width < right.x, "no overlap in the row: $left $right")
        assertTrue(right.x + right.width <= 400)
    }

    @Test
    fun twoTallElementsOfAColumnAreScaledToFit() {
        val r =
            rects(
                1280,
                200,
                OverlayCorner.TOP_RIGHT to OverlayItemGeometry(OverlaySize.LARGE, 0.2),
                OverlayCorner.BOTTOM_RIGHT to OverlayItemGeometry(OverlaySize.LARGE, 0.2),
            )
        val top = r.getValue(OverlayCorner.TOP_RIGHT)
        val bottom = r.getValue(OverlayCorner.BOTTOM_RIGHT)
        assertTrue(top.y + top.height < bottom.y, "no overlap in the column: $top $bottom")
        assertTrue(bottom.y + bottom.height <= 200)
    }

    @Test
    fun everyCombinationStaysInsideTheFrameAndNeverOverlaps() {
        val frames = listOf(1920 to 1080, 1280 to 720, 640 to 480, 480 to 480, 720 to 1280, 320 to 180, 1080 to 1920, 3840 to 2160)
        val aspects = listOf(0.2, 0.5, 1.0, 1.7, 3.0, 5.0, 8.0)
        var checked = 0
        for ((w, h) in frames) {
            for (aspect in aspects) {
                for (size in OverlaySize.entries) {
                    for (second in OverlaySize.entries) {
                        val items =
                            mapOf(
                                OverlayCorner.TOP_LEFT to OverlayItemGeometry(size, aspect),
                                OverlayCorner.TOP_RIGHT to OverlayItemGeometry(second, aspect),
                                OverlayCorner.BOTTOM_LEFT to OverlayItemGeometry(second, 1.0 / aspect.coerceAtLeast(0.3)),
                                OverlayCorner.BOTTOM_RIGHT to OverlayItemGeometry(size, aspect),
                            )
                        val result = videoOverlayCornerRects(w, h, items)
                        assertEquals(4, result.size)
                        for ((corner, rect) in result) {
                            assertTrue(rect.x >= 0 && rect.y >= 0, "origin $corner $rect in ${w}x$h")
                            assertTrue(rect.width >= 1 && rect.height >= 1, "size $corner $rect")
                            assertTrue(rect.x + rect.width <= w, "right edge $corner $rect in ${w}x$h aspect $aspect")
                            assertTrue(rect.y + rect.height <= h, "bottom edge $corner $rect in ${w}x$h aspect $aspect")
                        }
                        val list = result.entries.toList()
                        for (i in list.indices) {
                            for (j in i + 1 until list.size) {
                                val a = list[i].value
                                val b = list[j].value
                                val apart = a.x + a.width <= b.x || b.x + b.width <= a.x || a.y + a.height <= b.y || b.y + b.height <= a.y
                                assertTrue(apart, "overlap ${list[i].key} $a and ${list[j].key} $b in ${w}x$h aspect $aspect")
                            }
                        }
                        checked++
                    }
                }
            }
        }
        assertTrue(checked > 300)
    }

    @Test
    fun emptyInputsGiveNothing() {
        assertTrue(videoOverlayCornerRects(0, 720, mapOf(OverlayCorner.TOP_LEFT to OverlayItemGeometry(OverlaySize.SMALL, 1.0))).isEmpty())
        assertTrue(videoOverlayCornerRects(1280, 720, emptyMap()).isEmpty())
    }

    // ── draw plan ────────────────────────────────────────────────────────────────

    private fun item(id: String) = VideoOverlayItemDto(id, VideoOverlayKind.LOGO, id, 100, 100, "/api/video-overlays/${"C".repeat(43)}")

    @Test
    fun drawPlan_frameFirstThenTlTrBlBr_theFrameFillsTheImage() {
        val frame = SceneFrame(item("22222222-2222-4222-8222-222222222222").copy(kind = VideoOverlayKind.FRAME), OverlayOpacity.STRONG)
        val scene =
            VideoOverlayScene(
                corners =
                    mapOf(
                        OverlayCorner.BOTTOM_RIGHT to SceneCorner.Name("Erika", OverlaySize.SMALL, OverlayOpacity.FULL),
                        OverlayCorner.TOP_RIGHT to
                            SceneCorner.Image(item("11111111-1111-4111-8111-111111111111"), OverlaySize.MEDIUM, OverlayOpacity.LIGHT),
                        OverlayCorner.BOTTOM_LEFT to
                            SceneCorner.Image(item("33333333-3333-4333-8333-333333333333"), OverlaySize.SMALL, OverlayOpacity.FULL),
                        OverlayCorner.TOP_LEFT to
                            SceneCorner.Image(item("44444444-4444-4444-8444-444444444444"), OverlaySize.LARGE, OverlayOpacity.FULL),
                    ),
                frame = frame,
            )
        val ops = videoOverlayDrawPlan(scene, 1280, 720) { 1.0 }
        assertEquals(5, ops.size)
        assertTrue(ops[0] is OverlayDrawOp.Frame)
        assertEquals(0.7, ops[0].alpha)
        assertEquals(
            listOf(OverlayCorner.TOP_LEFT, OverlayCorner.TOP_RIGHT, OverlayCorner.BOTTOM_LEFT, OverlayCorner.BOTTOM_RIGHT),
            ops.drop(1).map { (it as? OverlayDrawOp.Image)?.corner ?: (it as OverlayDrawOp.Name).corner },
        )
        assertEquals(0.4, (ops[2] as OverlayDrawOp.Image).alpha)
        assertEquals("Erika", (ops[4] as OverlayDrawOp.Name).text)
    }

    @Test
    fun drawPlan_withoutAFrameOrCornersIsEmpty() {
        val scene = VideoOverlayScene(corners = emptyMap(), frame = null)
        assertTrue(videoOverlayDrawPlan(scene, 1280, 720) { 1.0 }.isEmpty())
    }

    // ── name pill ────────────────────────────────────────────────────────────────

    @Test
    fun namePillMetrics_followTheBoxHeightAndTheFrameWidth() {
        val m = namePillMetrics(boxHeightPx = 108.0, frameWidthPx = 1280)
        assertEquals(48.6, m.fontPx, 0.001)
        assertEquals(17.01, m.padPx, 0.001)
        assertEquals(512 - 2 * 17.01, m.maxTextWidthPx, 0.001)
        assertTrue(namePillMetrics(1.0, 1).maxTextWidthPx >= 1.0)
    }

    private val graphemes: (String) -> List<String> = { text -> text.map { it.toString() } }
    private val measure: (String) -> Double = { it.length * 10.0 }

    @Test
    fun truncate_blankIsNull_shortIsUnchanged_longGetsAnEllipsisAndFits() {
        assertNull(truncateDisplayName("   ", 100.0, measure, graphemes))
        assertNull(truncateDisplayName("", 100.0, measure, graphemes))
        assertEquals("Erika", truncateDisplayName("  Erika  ", 100.0, measure, graphemes))
        val cut = truncateDisplayName("Maximiliane Mustermann", 100.0, measure, graphemes)!!
        assertTrue(cut.endsWith(ELLIPSIS))
        assertTrue(measure(cut) <= 100.0)
        // the longest prefix that still fits together with the ellipsis: 9 characters + the ellipsis
        assertEquals("Maximilia$ELLIPSIS", cut)
    }

    @Test
    fun truncate_neverCutsAGraphemeInHalf() {
        // a family emoji is ONE grapheme (man, ZWJ, woman, ZWJ, girl); "e" + a combining diaeresis is ONE grapheme.
        val zwj = Char(0x200D)
        val family = "${codePoint(0x1F468)}$zwj${codePoint(0x1F469)}$zwj${codePoint(0x1F467)}"
        val diaeresis = Char(0x0308)
        val splitter: (String) -> List<String> = { text ->
            val out = mutableListOf<String>()
            var i = 0
            while (i < text.length) {
                val rest = text.substring(i)
                val unit =
                    when {
                        rest.startsWith(family) -> family
                        rest.length > 1 && rest[1] == diaeresis -> rest.substring(0, 2)
                        else -> rest.substring(0, 1)
                    }
                out += unit
                i += unit.length
            }
            out
        }
        val perGrapheme: (String) -> Double = { text -> splitter(text).size * 10.0 }
        val cut = truncateDisplayName("Ab${family}Ze${diaeresis}xyz", 45.0, perGrapheme, splitter)!!
        // 45 px = 4 graphemes in total including the ellipsis: "A", "b" and the family are ONE each.
        assertEquals("Ab$family$ELLIPSIS", cut)
        val combining = truncateDisplayName("Zoe${diaeresis}nigma", 40.0, perGrapheme, splitter)!!
        assertEquals("Zoe$diaeresis$ELLIPSIS", combining) // nothing was cut between the "e" and its diaeresis
    }

    // ── corner navigation ────────────────────────────────────────────────────────

    @Test
    fun cornerArrows_moveByRowAndColumn_andStopAtTheEdge() {
        assertEquals(OverlayCorner.TOP_RIGHT, overlayCornerArrowTarget(OverlayCorner.TOP_LEFT, "ArrowRight"))
        assertEquals(OverlayCorner.BOTTOM_LEFT, overlayCornerArrowTarget(OverlayCorner.TOP_LEFT, "ArrowDown"))
        assertEquals(OverlayCorner.TOP_LEFT, overlayCornerArrowTarget(OverlayCorner.TOP_RIGHT, "ArrowLeft"))
        assertEquals(OverlayCorner.BOTTOM_RIGHT, overlayCornerArrowTarget(OverlayCorner.TOP_RIGHT, "ArrowDown"))
        assertEquals(OverlayCorner.TOP_LEFT, overlayCornerArrowTarget(OverlayCorner.BOTTOM_LEFT, "ArrowUp"))
        assertEquals(OverlayCorner.BOTTOM_RIGHT, overlayCornerArrowTarget(OverlayCorner.BOTTOM_LEFT, "ArrowRight"))
        assertEquals(OverlayCorner.TOP_RIGHT, overlayCornerArrowTarget(OverlayCorner.BOTTOM_RIGHT, "ArrowUp"))
        assertEquals(OverlayCorner.BOTTOM_LEFT, overlayCornerArrowTarget(OverlayCorner.BOTTOM_RIGHT, "ArrowLeft"))
        assertNull(overlayCornerArrowTarget(OverlayCorner.TOP_LEFT, "ArrowLeft"))
        assertNull(overlayCornerArrowTarget(OverlayCorner.TOP_LEFT, "ArrowUp"))
        assertNull(overlayCornerArrowTarget(OverlayCorner.BOTTOM_RIGHT, "ArrowDown"))
        assertNull(overlayCornerArrowTarget(OverlayCorner.TOP_LEFT, "Enter"))
    }

    @Test
    fun radioTarget_wrapsAndSupportsHomeAndEnd() {
        assertEquals(1, overlayRadioTarget(0, "ArrowRight", 3))
        assertEquals(0, overlayRadioTarget(2, "ArrowDown", 3))
        assertEquals(2, overlayRadioTarget(0, "ArrowLeft", 3))
        assertEquals(0, overlayRadioTarget(2, "Home", 3))
        assertEquals(2, overlayRadioTarget(0, "End", 3))
        assertNull(overlayRadioTarget(0, "a", 3))
        assertNull(overlayRadioTarget(5, "ArrowRight", 3))
        assertNull(overlayRadioTarget(0, "ArrowRight", 0))
    }
}
