package network.lapis.cloud.client

import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.promise
import network.lapis.cloud.shared.domain.VideoOverlayItemDto
import network.lapis.cloud.shared.domain.VideoOverlayKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * V1.9.129 -- the drawing step through its seams, without a camera: bitmaps are closed when they are superseded, a drawing failure passes
 * the ORIGINAL frame on exactly once and reports once, no scene means no copy, every frame is closed or enqueued exactly once.
 */
class ConferenceVideoOverlayCompositorTest {
    private class FakeBitmap(
        override val width: Int,
        override val height: Int,
        val label: String,
    ) : OverlayBitmap {
        var closed = 0

        override fun close() {
            closed++
        }
    }

    private class FakeFrame(
        override val width: Int = 1280,
        override val height: Int = 720,
        override val timestamp: Double = 1.0,
    ) : OverlayFrame {
        var closed = 0

        override fun close() {
            closed++
        }
    }

    private class FakeSurface : OverlayDrawSurface {
        val calls = mutableListOf<String>()
        val produced = mutableListOf<FakeFrame>()
        var failOnDraw = false
        var failOnToFrame = false

        override fun begin(
            width: Int,
            height: Int,
        ) {
            calls += "begin ${width}x$height"
        }

        override fun drawFrame(frame: OverlayFrame) {
            calls += "frame"
        }

        override fun drawBitmap(
            bitmap: OverlayBitmap,
            rect: OverlayRect,
            alpha: Double,
        ) {
            if (failOnDraw) error("draw failed")
            calls += "bitmap ${(bitmap as FakeBitmap).label} ${rect.x},${rect.y} ${rect.width}x${rect.height} a=$alpha"
        }

        override fun toFrame(timestamp: Double): OverlayFrame {
            if (failOnToFrame) error("toFrame failed")
            return FakeFrame(timestamp = timestamp).also { produced += it }
        }
    }

    private class FakeLoader : OverlayBitmapLoader {
        val images = mutableListOf<Triple<String, Int, Int>>()
        val names = mutableListOf<String>()
        val bitmaps = mutableListOf<FakeBitmap>()
        var failPaths = emptySet<String>()

        override suspend fun loadImage(
            path: String,
            targetWidth: Int,
            targetHeight: Int,
        ): OverlayBitmap {
            images += Triple(path, targetWidth, targetHeight)
            if (path in failPaths) error("load failed")
            val w = if (targetWidth > 0) targetWidth else 600
            val h = if (targetHeight > 0) targetHeight else 300
            return FakeBitmap(w, h, "img:${path.takeLast(4)}").also { bitmaps += it }
        }

        override suspend fun renderName(
            text: String,
            boxHeightPx: Double,
            frameWidth: Int,
        ): OverlayBitmap? {
            names += text
            return FakeBitmap((boxHeightPx * 4).toInt(), boxHeightPx.toInt(), "name:$text").also { bitmaps += it }
        }
    }

    private fun item(
        id: String,
        kind: VideoOverlayKind = VideoOverlayKind.LOGO,
        token: Char = 'A',
        width: Int = 600,
        height: Int = 300,
    ) = VideoOverlayItemDto(id, kind, id, width, height, "/api/video-overlays/${token.toString().repeat(43)}")

    private val logoA = item("11111111-1111-4111-8111-111111111111")
    private val frameB = item("22222222-2222-4222-8222-222222222222", VideoOverlayKind.FRAME, 'B', 1920, 1080)

    private fun scene(
        vararg corners: Pair<OverlayCorner, SceneCorner>,
        withFrame: Boolean = false,
    ) = VideoOverlayScene(corners.toMap(), if (withFrame) SceneFrame(frameB, OverlayOpacity.STRONG) else null)

    private fun compositor(
        loader: FakeLoader = FakeLoader(),
        surface: FakeSurface = FakeSurface(),
        onFailure: () -> Unit = {},
        onMismatch: (Int, Int) -> Unit = { _, _ -> },
    ) = VideoOverlayCompositor(loader = loader, surface = surface, onDrawFailure = onFailure, onFrameSizeMismatch = onMismatch)

    private fun async(block: suspend () -> Unit) = GlobalScope.promise { block() }

    private fun image(
        size: OverlaySize = OverlaySize.MEDIUM,
        opacity: OverlayOpacity = OverlayOpacity.FULL,
    ) = SceneCorner.Image(logoA, size, opacity)

    @Test
    fun noScene_theFrameIsPassedOnUntouched_noSurfaceIsTouched() {
        val surface = FakeSurface()
        val c = compositor(surface = surface)
        val frame = FakeFrame()
        var enqueued: OverlayFrame? = null
        c.process(frame) { enqueued = it }
        assertSame(frame, enqueued)
        assertEquals(0, frame.closed)
        assertTrue(surface.calls.isEmpty())
    }

    @Test
    fun prepare_loadsEveryBitmapAtItsTargetSize_andDrawingFollowsThePlanOrder() =
        async {
            val loader = FakeLoader()
            val surface = FakeSurface()
            val c = compositor(loader, surface)
            val prepared =
                c.prepare(
                    scene(
                        OverlayCorner.TOP_RIGHT to image(),
                        OverlayCorner.BOTTOM_LEFT to SceneCorner.Name("Erika", OverlaySize.SMALL, OverlayOpacity.LIGHT),
                        withFrame = true,
                    ),
                    1280,
                    720,
                )
            assertEquals(0, prepared.failedElements)
            assertEquals(3, prepared.ops.size)
            c.setScene(prepared)
            val frame = FakeFrame()
            var enqueued: OverlayFrame? = null
            c.process(frame) { enqueued = it }
            assertEquals(1, frame.closed, "the original is closed after drawing")
            assertNotNull(enqueued)
            assertTrue(enqueued !== frame)
            assertEquals("begin 1280x720", surface.calls[0])
            assertEquals("frame", surface.calls[1])
            // the overlay frame first, over the full image
            assertTrue(surface.calls[2].startsWith("bitmap img:BBBB 0,0 1280x720 a=0.7"), surface.calls[2])
            assertTrue(surface.calls[3].startsWith("bitmap img:AAAA"), surface.calls[3]) // then top-right
            assertTrue(surface.calls[4].startsWith("bitmap name:Erika"), surface.calls[4]) // then bottom-left
            assertEquals(1, surface.produced.size)
            assertEquals(0, surface.produced[0].closed, "the new frame goes on, it is not closed here")
            // The logo has a known size 600x300 (aspect 2), medium on a 720 px short edge: 108 px high -> 216 x 108.
            assertTrue(
                loader.images.any { it.first.endsWith("A".repeat(43)) && it.second == 216 && it.third == 108 },
                loader.images.toString(),
            )
        }

    @Test
    fun videoOverlayFrameFits_onlyOnAlmostSixteenByNine() {
        assertTrue(videoOverlayFrameFits(1280, 720))
        assertTrue(videoOverlayFrameFits(1920, 1080))
        assertTrue(videoOverlayFrameFits(1366, 768))
        assertFalse(videoOverlayFrameFits(720, 1280), "portrait phone")
        assertFalse(videoOverlayFrameFits(640, 480), "4:3 webcam")
        assertFalse(videoOverlayFrameFits(1280, 800), "16:10")
        assertFalse(videoOverlayFrameFits(0, 0))
    }

    @Test
    fun aFrameIsNeverStretchedOverANonSixteenByNineCamera_cornersStillDraw() =
        async {
            val loader = FakeLoader()
            val surface = FakeSurface()
            val c = compositor(loader, surface)
            // Android Chrome portrait: 720x1280, 1920x1080 frame. The decode keeps the frame's own shape ...
            val prepared = c.prepare(scene(OverlayCorner.TOP_RIGHT to image(), withFrame = true), 720, 1280)
            assertTrue(
                loader.images.any { it.first.endsWith("B".repeat(43)) && it.second * 9 == it.third * 16 },
                loader.images.toString(),
            )
            c.setScene(prepared)
            c.process(FakeFrame(width = 720, height = 1280)) {}
            // ... and it is not drawn at all; the logo still is.
            assertTrue(surface.calls.none { it.contains("img:BBBB") }, surface.calls.toString())
            assertTrue(surface.calls.any { it.startsWith("bitmap img:AAAA") }, surface.calls.toString())
            surface.calls.clear()
            // A 640x480 webcam (size mismatch -> rescaled path) behaves the same.
            c.process(FakeFrame(width = 640, height = 480)) {}
            assertTrue(surface.calls.none { it.contains("img:BBBB") }, surface.calls.toString())
            surface.calls.clear()
            // On a 16:9 camera of another size the frame is drawn full-size.
            c.process(FakeFrame(width = 1280, height = 720)) {}
            assertTrue(surface.calls.any { it.startsWith("bitmap img:BBBB 0,0 1280x720") }, surface.calls.toString())
        }

    @Test
    fun setScene_closesTheSupersededBitmapsButKeepsTheOnesTheNewSceneReuses() =
        async {
            val loader = FakeLoader()
            val c = compositor(loader)
            val first = c.prepare(scene(OverlayCorner.TOP_LEFT to image(), withFrame = true), 1280, 720)
            c.setScene(first)
            val frameBitmap = first.ops.first { it.kind == PreparedOverlayOp.Kind.FRAME }.bitmap as FakeBitmap
            val logoBitmap = first.ops.first { it.kind == PreparedOverlayOp.Kind.CORNER }.bitmap as FakeBitmap
            // The new scene keeps the frame (same key -> reused, not loaded again) and drops the logo.
            val loadsBefore = loader.images.size
            val second = c.prepare(scene(withFrame = true), 1280, 720)
            assertEquals(loadsBefore, loader.images.size, "an unchanged bitmap is not downloaded or decoded again")
            c.setScene(second)
            assertEquals(0, frameBitmap.closed, "the reused bitmap stays open")
            assertEquals(1, logoBitmap.closed, "the superseded bitmap is closed at once")
            c.setScene(null)
            assertEquals(1, frameBitmap.closed)
            assertEquals(1, logoBitmap.closed, "closed once, never twice")
        }

    @Test
    fun close_closesEverythingExactlyOnce_andAFurtherSetSceneClosesWhatItGets() =
        async {
            val loader = FakeLoader()
            val c = compositor(loader)
            val prepared = c.prepare(scene(OverlayCorner.TOP_LEFT to image(OverlaySize.SMALL)), 1280, 720)
            c.setScene(prepared)
            c.close()
            c.close()
            assertTrue(loader.bitmaps.all { it.closed == 1 }, loader.bitmaps.map { it.closed }.toString())
            val late = c.prepare(scene(OverlayCorner.TOP_LEFT to image(OverlaySize.SMALL)), 1280, 720, current = null)
            c.setScene(late)
            assertFalse(c.hasScene)
            assertTrue(late.bitmaps.values.all { (it as FakeBitmap).closed == 1 })
            val frame = FakeFrame()
            var enqueued: OverlayFrame? = null
            c.process(frame) { enqueued = it }
            assertSame(frame, enqueued, "after close the frames pass on")
        }

    @Test
    fun aFailingElementIsLeftOutAndCounted_theOthersStay() =
        async {
            val loader = FakeLoader()
            loader.failPaths = setOf("/api/video-overlays/${"A".repeat(43)}")
            val c = compositor(loader)
            val prepared =
                c.prepare(
                    scene(
                        OverlayCorner.TOP_LEFT to image(),
                        OverlayCorner.TOP_RIGHT to SceneCorner.Name("Erika", OverlaySize.MEDIUM, OverlayOpacity.FULL),
                    ),
                    1280,
                    720,
                )
            assertEquals(1, prepared.failedElements)
            assertEquals(1, prepared.ops.size)
            assertEquals(OverlayCorner.TOP_RIGHT, prepared.ops.single().corner)
        }

    @Test
    fun anImageWithAnUnsafePathIsNeverRequested() =
        async {
            val loader = FakeLoader()
            val c = compositor(loader)
            val evil = logoA.copy(imagePath = "https://evil.example/x.png")
            val prepared =
                c.prepare(
                    scene(OverlayCorner.TOP_LEFT to SceneCorner.Image(evil, OverlaySize.SMALL, OverlayOpacity.FULL)),
                    1280,
                    720,
                )
            assertTrue(loader.images.isEmpty())
            assertTrue(prepared.ops.isEmpty())
        }

    @Test
    fun aDrawingFailure_passesTheOriginalFrameOn_once_andIsReportedOnce_thenSuspends() =
        async {
            val surface = FakeSurface()
            var failures = 0
            val c = compositor(surface = surface, onFailure = { failures++ })
            c.setScene(c.prepare(scene(OverlayCorner.TOP_LEFT to image(OverlaySize.SMALL)), 1280, 720))
            surface.failOnDraw = true
            val frame = FakeFrame()
            val out = mutableListOf<OverlayFrame>()
            c.process(frame) { out += it }
            assertEquals(listOf<OverlayFrame>(frame), out, "the ORIGINAL frame, exactly once")
            assertEquals(0, frame.closed, "and not closed: it travels on")
            assertEquals(1, failures)
            // From now on the step is suspended: later frames pass on without touching the surface and without a second report.
            val callsBefore = surface.calls.size
            val second = FakeFrame()
            c.process(second) { out += it }
            assertEquals(listOf<OverlayFrame>(frame, second), out)
            assertEquals(callsBefore, surface.calls.size)
            assertEquals(1, failures)
            assertFalse(c.isDrawing, "a suspended scene draws nothing")
            // A new scene re-arms the step.
            surface.failOnDraw = false
            c.setScene(c.prepare(scene(OverlayCorner.TOP_LEFT to image(OverlaySize.SMALL)), 1280, 720))
            val third = FakeFrame()
            c.process(third) { out += it }
            assertEquals(1, third.closed)
            assertTrue(out.last() !== third)
        }

    @Test
    fun aFrameOnlyScene_onANon169Camera_isNotDrawing_on169ItIs() =
        async {
            val c = compositor()
            c.setScene(c.prepare(scene(withFrame = true), 720, 1280))
            assertFalse(c.isDrawing, "the frame is skipped on a portrait camera: nothing reaches the video")
            c.setScene(c.prepare(scene(withFrame = true), 1280, 720))
            assertTrue(c.isDrawing)
            // A corner element next to the frame still draws on a portrait camera.
            c.setScene(c.prepare(scene(OverlayCorner.TOP_LEFT to image(OverlaySize.SMALL), withFrame = true), 720, 1280))
            assertTrue(c.isDrawing)
        }

    @Test
    fun aFailureWhileMakingTheNewFrame_alsoPassesTheOriginalOn() =
        async {
            val surface = FakeSurface()
            var failures = 0
            val c = compositor(surface = surface, onFailure = { failures++ })
            c.setScene(c.prepare(scene(OverlayCorner.TOP_LEFT to image(OverlaySize.SMALL)), 1280, 720))
            surface.failOnToFrame = true
            val frame = FakeFrame()
            val out = mutableListOf<OverlayFrame>()
            c.process(frame) { out += it }
            assertEquals(listOf<OverlayFrame>(frame), out)
            assertEquals(0, frame.closed)
            assertEquals(1, failures)
        }

    @Test
    fun aDifferentCameraSize_isDrawnWithRescaledRectangles_andReportedOncePerSize() =
        async {
            val mismatches = mutableListOf<Pair<Int, Int>>()
            val surface = FakeSurface()
            val c = compositor(surface = surface, onMismatch = { w, h -> mismatches += w to h })
            c.setScene(c.prepare(scene(OverlayCorner.TOP_LEFT to image(), withFrame = true), 1280, 720))
            c.process(FakeFrame(width = 640, height = 480)) {}
            c.process(FakeFrame(width = 640, height = 480)) {}
            assertEquals(listOf(640 to 480), mismatches, "once per newly seen size")
            // 4:3 is not 16:9: the frame is NOT stretched over it; the logo is positioned for it (480 short edge: margin 19, 72 px high -> 144 x 72).
            assertTrue(surface.calls.none { it.contains("img:BBBB") }, surface.calls.toString())
            assertTrue(surface.calls.any { it.contains("img:AAAA 19,19 144x72") }, surface.calls.toString())
        }

    @Test
    fun discard_closesAPreparedSceneThatWasNeverShown_butNotWhatTheRunningSceneStillUses() =
        async {
            val c = compositor()
            val running = c.prepare(scene(withFrame = true), 1280, 720)
            c.setScene(running)
            val overtaken = c.prepare(scene(OverlayCorner.TOP_LEFT to image(OverlaySize.SMALL), withFrame = true), 1280, 720)
            c.discard(overtaken)
            val frameBitmap = running.ops.single().bitmap as FakeBitmap
            assertEquals(0, frameBitmap.closed, "shared with the running scene: stays open")
            val logoBitmap = overtaken.ops.first { it.kind == PreparedOverlayOp.Kind.CORNER }.bitmap as FakeBitmap
            assertEquals(1, logoBitmap.closed)
        }
}
