package network.lapis.cloud.client

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * V1.9.142 -- the pure part of the camera filters: slider -> parameter mapping exactly as designed, the persisted value (a damaged one never
 * switches a filter on), the face mask geometry, the face tracker and the performance governor with an injected clock.
 */
class ConferenceCameraFiltersTest {
    private fun assertClose(
        expected: Float,
        actual: Float,
        eps: Float = 1e-5f,
        message: String = "",
    ) = assertTrue(abs(expected - actual) <= eps, "$message expected $expected but was $actual")

    @Test
    fun neutralSettings_giveTheIdentityParameters() {
        val p = cameraFilterParams(CameraFilterSettings(enabled = true), smoothingPaused = false)
        assertEquals(1f, p.gain)
        assertEquals(1f, p.contrastK)
        assertEquals(1f, p.warmR)
        assertEquals(1f, p.warmB)
        assertEquals(0f, p.backlightLift)
        assertEquals(0f, p.smoothStrength)
        assertTrue(p.lightIsNeutral)
        assertTrue(CameraFilterSettings(enabled = true).isNeutral())
    }

    @Test
    fun theMappingFollowsTheDesignTable_atTheEnds() {
        val high = cameraFilterParams(CameraFilterSettings(true, 40, 40, 50, 100, 100), smoothingPaused = false)
        assertClose(1.4f, high.gain)
        assertClose(1.4f, high.contrastK)
        assertClose(1.10f, high.warmR)
        assertClose(0.90f, high.warmB)
        assertClose(0.35f, high.backlightLift)
        assertClose(0.70f, high.smoothStrength, message = "100 % smoothing is a 70 % blend (CameraFilterLimits.MAX_SMOOTHING)")
        val low = cameraFilterParams(CameraFilterSettings(true, -40, -40, -50, 0, 0), smoothingPaused = false)
        assertClose(0.6f, low.gain)
        assertClose(0.6f, low.contrastK)
        assertClose(0.90f, low.warmR)
        assertClose(1.10f, low.warmB)
        assertFalse(low.lightIsNeutral)
    }

    @Test
    fun theMappingIsLinearInTheMiddle() {
        val p = cameraFilterParams(CameraFilterSettings(true, 20, -10, 25, 50, 50), smoothingPaused = false)
        assertClose(1.2f, p.gain)
        assertClose(0.9f, p.contrastK)
        assertClose(1.05f, p.warmR)
        assertClose(0.95f, p.warmB)
        assertClose(0.175f, p.backlightLift)
        assertClose(0.35f, p.smoothStrength)
    }

    @Test
    fun aPausedSmoothing_hasNoStrength_butKeepsTheOtherParameters() {
        val settings = CameraFilterSettings(true, 10, 0, 0, 0, 80)
        val paused = cameraFilterParams(settings, smoothingPaused = true)
        assertEquals(0f, paused.smoothStrength)
        assertClose(1.1f, paused.gain)
    }

    @Test
    fun clampingKeepsEverySliderInsideItsRange() {
        val wild = CameraFilterSettings(true, 500, -500, 9_999, -3, 1_000).clamped()
        assertEquals(CameraFilterSettings(true, 40, -40, 50, 0, 100), wild)
        // parameters of an out-of-range value are those of the clamped value
        assertClose(1.4f, cameraFilterParams(CameraFilterSettings(true, 500), smoothingPaused = false).gain)
    }

    @Test
    fun theFilterIsWantedOnlyWithTheSwitchOn_aSliderAwayFromNeutral_andASupportingBrowser() {
        val on = CameraFilterSettings(true, brightness = 5)
        assertTrue(cameraFilterWanted(on, supported = true))
        assertFalse(cameraFilterWanted(on, supported = false))
        assertFalse(
            cameraFilterWanted(on.copy(enabled = false), supported = true),
            "switch off: the sliders keep their values, nothing is applied",
        )
        assertFalse(cameraFilterWanted(CameraFilterSettings(true), supported = true), "all neutral: nothing to apply")
    }

    @Test
    fun encodeDecode_roundTrips() {
        val settings = CameraFilterSettings(true, 12, -7, 30, 55, 80)
        assertEquals(settings, decodeCameraFilterSettings(encodeCameraFilterSettings(settings)))
        assertEquals(CameraFilterSettings(), decodeCameraFilterSettings(encodeCameraFilterSettings(CameraFilterSettings())))
    }

    @Test
    fun decode_ofDamagedValues_isNeutralAndSwitchedOff() {
        val neutral = CameraFilterSettings()
        for (raw in listOf(null, "", "   ", "{", "[]", "null", "5", "\"x\"", "x".repeat(401))) {
            assertEquals(neutral, decodeCameraFilterSettings(raw), "raw=$raw")
        }
    }

    @Test
    fun decode_clampsOutOfRangeAndIgnoresNonNumbersAndInfinity() {
        val decoded =
            decodeCameraFilterSettings(
                "{\"enabled\":true,\"brightness\":999,\"contrast\":\"high\",\"warmth\":-999,\"backlight\":1e999,\"smoothing\":12.9}",
            )
        assertEquals(CameraFilterSettings(true, 40, 0, -50, 0, 12), decoded)
        // "enabled" must be the boolean true, nothing else switches the filter on
        assertFalse(decodeCameraFilterSettings("{\"enabled\":\"true\",\"brightness\":5}").enabled)
        assertFalse(decodeCameraFilterSettings("{\"enabled\":1,\"brightness\":5}").enabled)
    }

    // --- mask -------------------------------------------------------------------------------------------------------------------------

    private val face =
        FaceKeypoints(
            box = Rect(20f, 10f, 60f, 80f),
            leftEye = Pt(35f, 35f),
            rightEye = Pt(65f, 35f),
            nose = Pt(50f, 50f),
            mouth = Pt(50f, 70f),
        )

    @Test
    fun theMask_isOnSkin_offOnEyesBrowsAndMouth_softAtTheRim_andZeroOutside() {
        val g = faceMaskGeometry(face)
        assertClose(1f, faceMaskValue(g, 40f, 62f), message = "cheek")
        assertClose(0f, faceMaskValue(g, 35f, 35f), message = "left eye")
        assertClose(0f, faceMaskValue(g, 65f, 35f), message = "right eye")
        assertClose(0f, faceMaskValue(g, 50f, 70f), message = "mouth")
        assertTrue(faceMaskValue(g, 35f, 35f - 30f * 0.30f) < 0.1f, "brow above the eye")
        assertClose(0f, faceMaskValue(g, 0f, 0f), message = "outside the face")
        val rim = faceMaskValue(g, 50f + 0.94f * g.face.rx, 50f)
        assertTrue(rim > 0.05f && rim < 0.95f, "the rim is soft: $rim")
        // skin is the face ellipse scaled 1.15 over the box
        assertClose(34.5f, g.face.rx)
        assertClose(46f, g.face.ry)
        assertEquals(6, faceMaskExclusions(g).size)
    }

    @Test
    fun theMaskGeometry_survivesDegenerateKeypoints() {
        val collapsed = face.copy(leftEye = Pt(50f, 35f), rightEye = Pt(50f, 35f))
        val g = faceMaskGeometry(collapsed)
        assertTrue(g.eyes.all { it.rx >= 1f && it.ry >= 1f })
        assertTrue(faceMaskValue(g, 50f, 50f).isFinite())
    }

    // --- tracker ----------------------------------------------------------------------------------------------------------------------

    @Test
    fun theTracker_averagesDetections_andForgetsTheFaceAfterTenFrames() {
        val tracker = FaceTracker()
        assertNull(tracker.current())
        tracker.observe(face)
        assertEquals(face, tracker.current(), "the first detection is taken as it is")
        val moved = face.copy(box = Rect(40f, 10f, 60f, 80f))
        tracker.observe(moved)
        assertClose(30f, assertNotNull(tracker.current()).box.x, message = "alpha 0.5 between 20 and 40")
        repeat(FACE_TRACK_MISS_FRAMES) { tracker.advance() }
        assertNotNull(tracker.current(), "ten frames without a detection: still held")
        tracker.advance()
        assertNull(tracker.current(), "the eleventh drops the face")
        assertFalse(tracker.present)
    }

    @Test
    fun theTracker_ignoresANullDetection_andAFreshDetectionRestartsTheCount() {
        val tracker = FaceTracker()
        tracker.observe(face)
        repeat(8) { tracker.advance() }
        tracker.observe(null)
        repeat(3) { tracker.advance() }
        assertNull(tracker.current(), "null detections do not refresh the face")
        tracker.observe(face)
        repeat(5) { tracker.advance() }
        assertNotNull(tracker.current())
    }

    @Test
    fun largestFace_picksTheBiggestBox() {
        val small = face.copy(box = Rect(0f, 0f, 10f, 10f))
        assertEquals(face, largestFace(listOf(small, face)))
        assertNull(largestFace(emptyList()))
    }

    // --- governor ---------------------------------------------------------------------------------------------------------------------

    private class Clock {
        var ms = 0.0
    }

    @Test
    fun theGovernor_stepsDownAfterThreeSeconds_thenPausesAfterThreeMore_eachActionOnce() {
        val clock = Clock()
        val governor = CameraFilterPerfGovernor { clock.ms }
        assertEquals(PerfAction.NONE, governor.recordFrame(20.0))
        clock.ms = 2_900.0
        assertEquals(PerfAction.NONE, governor.recordFrame(20.0))
        clock.ms = 3_000.0
        assertEquals(PerfAction.REDUCED, governor.recordFrame(20.0))
        clock.ms = 5_000.0
        assertEquals(PerfAction.NONE, governor.recordFrame(20.0))
        clock.ms = 6_000.0
        assertEquals(PerfAction.PAUSED, governor.recordFrame(20.0))
        clock.ms = 20_000.0
        assertEquals(PerfAction.NONE, governor.recordFrame(20.0), "after the pause there is nothing left to take away")
    }

    @Test
    fun theGovernor_aFastFrameStreakResetsTheTimer() {
        val clock = Clock()
        val governor = CameraFilterPerfGovernor { clock.ms }
        governor.recordFrame(20.0)
        clock.ms = 2_000.0
        repeat(40) { governor.recordFrame(2.0) } // the EMA falls below the threshold
        clock.ms = 4_000.0
        assertEquals(PerfAction.NONE, governor.recordFrame(2.0))
        clock.ms = 5_000.0
        assertEquals(PerfAction.NONE, governor.recordFrame(20.0), "a new slow streak starts counting from here")
        clock.ms = 5_500.0
        assertEquals(PerfAction.NONE, governor.recordFrame(20.0))
    }

    @Test
    fun theGovernor_resetStartsAgainAtFullQuality() {
        val clock = Clock()
        val governor = CameraFilterPerfGovernor { clock.ms }
        governor.recordFrame(30.0)
        clock.ms = 3_000.0
        assertEquals(PerfAction.REDUCED, governor.recordFrame(30.0))
        clock.ms = 6_000.0
        assertEquals(PerfAction.PAUSED, governor.recordFrame(30.0))
        governor.reset()
        clock.ms = 6_100.0
        assertEquals(PerfAction.NONE, governor.recordFrame(30.0))
        clock.ms = 9_100.0
        assertEquals(PerfAction.REDUCED, governor.recordFrame(30.0))
    }
}
