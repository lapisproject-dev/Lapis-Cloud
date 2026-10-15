package network.lapis.cloud.client

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * V1.9.142 -- the frame stage against REAL browser objects (headless Chrome): the reading of a face detection, the ownership rule of the
 * frame (same frame back when inactive or on any error; a new frame with the original closed otherwise) and, where the browser has WebGL2,
 * the real shader on real pixels (brightness, no vertical flip). Without WebGL2 in the test browser the GPU tests return early -- they
 * are a smoke test of the glue, not a quality check; the arithmetic itself is covered by [ConferenceCameraFilterMathTest].
 */
class ConferenceCameraFilterStageTest {
    private fun apisPresent(): Boolean = cameraFilterStageSupportedInThisBrowser()

    private class Reports {
        var failures = 0
        val perf = mutableListOf<PerfAction>()
        var unavailable = 0
    }

    private fun stage(
        reports: Reports = Reports(),
        useDetector: Boolean = false,
    ) = WebGlCameraFilterStage(
        onFailure = { reports.failures++ },
        onPerfAction = { reports.perf += it },
        onSmoothingUnavailable = { reports.unavailable++ },
        useDetector = useDetector,
    )

    /** A frame whose upper half is [top] and lower half is [bottom] (CSS colours). */
    private fun halvesFrame(
        top: String,
        bottom: String,
    ): dynamic {
        val canvas = newOffscreenCanvas(32, 32)
        val ctx = canvas.getContext("2d")
        ctx.fillStyle = top
        ctx.fillRect(0, 0, 32, 16)
        ctx.fillStyle = bottom
        ctx.fillRect(0, 16, 32, 16)
        return newVideoFrameFrom(canvas, 777)
    }

    private fun pixel(
        frame: dynamic,
        x: Int,
        y: Int,
    ): IntArray {
        val canvas = newOffscreenCanvas(32, 32)
        val ctx = canvas.getContext("2d")
        ctx.drawImage(frame, 0, 0)
        val data = ctx.getImageData(x, y, 1, 1).data
        return intArrayOf((data[0] as Number).toInt(), (data[1] as Number).toInt(), (data[2] as Number).toInt())
    }

    private fun isClosed(frame: dynamic): Boolean = (frame.codedWidth as Number).toInt() == 0

    // --- face reading -----------------------------------------------------------------------------------------------------------------

    private fun detection(): dynamic =
        js(
            """({
              boundingBox: { originX: 20, originY: 10, width: 60, height: 80, angle: 0 },
              keypoints: [{x: 0.65, y: 0.35}, {x: 0.35, y: 0.35}, {x: 0.5, y: 0.5}, {x: 0.5, y: 0.7}, {x: 0.2, y: 0.5}, {x: 0.8, y: 0.5}],
              categories: [{ score: 0.9 }]
            })""",
        )

    @Test
    fun faceFromDetection_readsTheBoxAndScalesTheKeypointsToPixels() {
        val face = assertNotNull(faceFromDetection(detection(), 100, 100))
        assertEquals(Rect(20f, 10f, 60f, 80f), face.box)
        assertEquals(Pt(65f, 35f), face.rightEye, "keypoint 0 is the (image-)right eye")
        assertEquals(Pt(35f, 35f), face.leftEye)
        assertEquals(Pt(50f, 50f), face.nose)
        assertEquals(Pt(50f, 70f), face.mouth)
    }

    @Test
    fun faceFromDetection_rejectsAnythingIncomplete() {
        assertNull(faceFromDetection(null, 100, 100))
        assertNull(faceFromDetection(js("({})"), 100, 100))
        assertNull(
            faceFromDetection(
                js("({ boundingBox: {originX: 0, originY: 0, width: 10, height: 10}, keypoints: [{x: 0, y: 0}] })"),
                100,
                100,
            ),
        )
        assertNull(
            faceFromDetection(js("({ boundingBox: {originX: 0, originY: 0, width: 0, height: 10}, keypoints: [1,2,3,4] })"), 100, 100),
        )
        val nan =
            js(
                """({ boundingBox: {originX: NaN, originY: 0, width: 10, height: 10},
                    keypoints: [{x:0,y:0},{x:0,y:0},{x:0,y:0},{x:0,y:0}] })""",
            )
        assertNull(faceFromDetection(nan, 100, 100))
    }

    // --- ownership ----------------------------------------------------------------------------------------------------------------------

    @Test
    fun anInactiveStage_returnsTheSameFrame_untouched() {
        if (!apisPresent()) return
        val reports = Reports()
        val s = stage(reports)
        val frame = halvesFrame("red", "blue")
        assertSame(frame, s.process(frame))
        assertTrue(!isClosed(frame))
        s.configure(CameraFilterSettings(enabled = true), smoothingPaused = false) // all neutral: still inactive
        assertSame(frame, s.process(frame))
        frame.close()
        assertEquals(0, reports.failures)
    }

    @Test
    fun anActiveStage_givesANewFrame_andClosesTheOriginalExactlyOnce() {
        if (!apisPresent()) return
        val reports = Reports()
        val s = stage(reports)
        s.configure(CameraFilterSettings(enabled = true, brightness = 10), smoothingPaused = false)
        assertTrue(s.active)
        val frame = halvesFrame("rgb(100,100,100)", "rgb(100,100,100)")
        val out = s.process(frame)
        assertTrue(out !== frame)
        assertTrue(isClosed(frame), "the original is closed once the new frame exists")
        assertTrue(!isClosed(out))
        assertEquals(777.0, (out.timestamp as Number).toDouble(), "the timestamp survives")
        assertEquals(32, (out.displayWidth as Number).toInt())
        out.close()
        s.release()
        assertEquals(0, reports.failures)
    }

    @Test
    fun anObjectThatIsNotAFrame_passesThroughAndIsNotAFailure() {
        if (!apisPresent()) return
        val reports = Reports()
        val s = stage(reports)
        s.configure(CameraFilterSettings(enabled = true, brightness = 10), smoothingPaused = false)
        val notAFrame = js("({ displayWidth: 'x' })")
        assertSame(notAFrame, s.process(notAFrame))
        s.release()
        assertEquals(0, reports.failures)
    }

    // --- real pixels ---------------------------------------------------------------------------------------------------------------------

    @Test
    fun brightnessPlus20_changesThePixelsAsTheMathSays() {
        if (!apisPresent()) return
        val s = stage()
        s.configure(CameraFilterSettings(enabled = true, brightness = 20), smoothingPaused = false)
        val frame = halvesFrame("rgb(100,100,100)", "rgb(100,100,100)")
        val out = s.process(frame)
        val p = pixel(out, 16, 16)
        // 100 / 255 * 1.2 = 0.4706 -> 120
        for (channel in p) assertTrue(abs(channel - 120) <= 4, "channel $channel")
        out.close()
        s.release()
    }

    @Test
    fun thePictureIsNotFlipped() {
        if (!apisPresent()) return
        val s = stage()
        s.configure(CameraFilterSettings(enabled = true, brightness = 1), smoothingPaused = false)
        val frame = halvesFrame("rgb(220,20,20)", "rgb(20,20,220)")
        val out = s.process(frame)
        val top = pixel(out, 16, 4)
        val bottom = pixel(out, 16, 28)
        assertTrue(top[0] > 150 && top[2] < 80, "the upper half is still red: ${top.toList()}")
        assertTrue(bottom[2] > 150 && bottom[0] < 80, "the lower half is still blue: ${bottom.toList()}")
        out.close()
        s.release()
    }

    @Test
    fun switchingTheStageOff_releasesAndPassesFramesAgain() {
        if (!apisPresent()) return
        val s = stage()
        s.configure(CameraFilterSettings(enabled = true, brightness = 10), smoothingPaused = false)
        val first = s.process(halvesFrame("gray", "gray"))
        first.close()
        s.configure(CameraFilterSettings(enabled = false, brightness = 10), smoothingPaused = false)
        assertTrue(!s.active)
        val frame = halvesFrame("gray", "gray")
        assertSame(frame, s.process(frame))
        frame.close()
        s.release()
        assertTrue(!s.active)
    }

    // --- skin smoothing on the GPU ----------------------------------------------------------------------------------------------------

    /** A 64 x 64 grey picture with a deterministic +-10 level noise (small against the range sigma of the blur, so it is meant to be smoothed). */
    private fun noiseFrame(): dynamic {
        val canvas = newOffscreenCanvas(64, 64)
        val ctx = canvas.getContext("2d")
        val image = ctx.createImageData(64, 64)
        var seed = 987654
        for (i in 0 until 64 * 64) {
            seed = (seed * 1103515245 + 12345) and 0x7fffffff
            val level = 128 + (seed % 21) - 10
            image.data[i * 4] = level
            image.data[i * 4 + 1] = level
            image.data[i * 4 + 2] = level
            image.data[i * 4 + 3] = 255
        }
        ctx.putImageData(image, 0, 0)
        return newVideoFrameFrom(canvas, 5)
    }

    private fun levels(frame: dynamic): IntArray {
        val canvas = newOffscreenCanvas(64, 64)
        val ctx = canvas.getContext("2d")
        ctx.drawImage(frame, 0, 0)
        val data = ctx.getImageData(0, 0, 64, 64).data
        return IntArray(64 * 64) { (data[it * 4] as Number).toInt() }
    }

    private fun std(values: List<Int>): Double {
        val mean = values.sum().toDouble() / values.size
        return kotlin.math.sqrt(values.sumOf { (it - mean) * (it - mean) } / values.size)
    }

    @Test
    fun theSkinSmoothing_lowersTheNoiseInsideTheFace_andLeavesEverythingOutsideAlone() {
        if (!apisPresent()) return
        val face =
            FaceKeypoints(
                Rect(16f, 12f, 32f, 40f),
                leftEye = Pt(26f, 26f),
                rightEye = Pt(38f, 26f),
                nose = Pt(32f, 34f),
                mouth = Pt(32f, 42f),
            )
        val geometry = faceMaskGeometry(face)
        val s = stage()
        s.configure(CameraFilterSettings(enabled = true, smoothing = 100), smoothingPaused = false)
        s.injectFaceForTest(face)
        val frame = noiseFrame()
        val before = levels(frame)
        val out = s.process(frame)
        assertTrue(out !== frame)
        val after = levels(out)
        val skin = (0 until 64 * 64).filter { faceMaskValue(geometry, it % 64 + 0.5f, it / 64 + 0.5f) > 0.99f }
        val outside = (0 until 64 * 64).filter { faceMaskValue(geometry, it % 64 + 0.5f, it / 64 + 0.5f) <= 0f }
        assertTrue(skin.size > 200 && outside.size > 200, "the test picture has skin (${skin.size}) and background (${outside.size})")
        val stdBefore = std(skin.map { before[it] })
        val stdAfter = std(skin.map { after[it] })
        assertTrue(stdAfter < stdBefore * 0.75, "noise on the skin: $stdBefore -> $stdAfter")
        val changedOutside = outside.count { abs(after[it] - before[it]) > 1 }
        assertEquals(0, changedOutside, "pixels outside the face mask are not touched")
        val eye = 26 * 64 + 26
        assertTrue(abs(after[eye] - before[eye]) <= 1, "the eye stays sharp")
        out.close()
        s.release()
    }

    @Test
    fun withoutAFace_theSmoothingChangesNothing_evenWithTheSliderAtTheEnd() {
        if (!apisPresent()) return
        val s = stage()
        s.configure(CameraFilterSettings(enabled = true, smoothing = 100), smoothingPaused = false)
        val frame = noiseFrame()
        val before = levels(frame)
        val out = s.process(frame)
        val after = levels(out)
        assertEquals(0, (0 until 64 * 64).count { abs(after[it] - before[it]) > 1 })
        out.close()
        s.release()
    }

    @Test
    fun aPausedSmoothing_changesNothing() {
        if (!apisPresent()) return
        val s = stage()
        s.configure(CameraFilterSettings(enabled = true, smoothing = 100, brightness = 0), smoothingPaused = true)
        assertTrue(!s.active, "paused smoothing alone leaves nothing to do")
        val frame = noiseFrame()
        assertSame(frame, s.process(frame))
        frame.close()
        s.release()
    }
}
