package network.lapis.cloud.client

import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * V1.9.142 -- every slider on synthetic pictures, through [CameraFilterMath] (the Kotlin twin of the shader): brightness, contrast, backlight
 * and warmth change what they say they change; the skin smoothing lowers the noise INSIDE the mask, leaves every pixel outside it bit for bit
 * unchanged, and keeps a hard edge.
 */
class ConferenceCameraFilterMathTest {
    private val neutral = cameraFilterParams(CameraFilterSettings(enabled = true), smoothingPaused = false)

    private fun params(settings: CameraFilterSettings) = cameraFilterParams(settings.copy(enabled = true), smoothingPaused = false)

    private fun solid(
        width: Int,
        height: Int,
        r: Float,
        g: Float,
        b: Float,
    ): FloatArray {
        val out = FloatArray(width * height * 3)
        for (i in 0 until width * height) {
            out[i * 3] = r
            out[i * 3 + 1] = g
            out[i * 3 + 2] = b
        }
        return out
    }

    private fun mean(pixels: FloatArray): Double = pixels.fold(0.0) { acc, v -> acc + v } / pixels.size

    private fun std(pixels: FloatArray): Double {
        val m = mean(pixels)
        return sqrt(pixels.fold(0.0) { acc, v -> acc + (v - m) * (v - m) } / pixels.size)
    }

    private fun assertClose(
        expected: Double,
        actual: Double,
        eps: Double,
        message: String = "",
    ) = assertTrue(abs(expected - actual) <= eps, "$message expected $expected but was $actual")

    @Test
    fun aNeutralFilter_isTheIdentity() {
        val src = FloatArray(48) { (it % 7) / 7f }
        assertTrue(src.contentEquals(CameraFilterMath.applyLightAndColor(src, neutral)))
    }

    @Test
    fun brightnessPlus20_raisesTheMeanByAboutTwentyPercent() {
        val src = solid(8, 8, 0.4f, 0.4f, 0.4f)
        val out = CameraFilterMath.applyLightAndColor(src, params(CameraFilterSettings(brightness = 20)))
        assertClose(mean(src) * 1.2, mean(out), 0.005)
        val darker = CameraFilterMath.applyLightAndColor(src, params(CameraFilterSettings(brightness = -20)))
        assertClose(mean(src) * 0.8, mean(darker), 0.005)
    }

    @Test
    fun brightness_neverLeavesTheZeroToOneRange() {
        val src = solid(4, 4, 0.9f, 0.95f, 1f)
        val out = CameraFilterMath.applyLightAndColor(src, params(CameraFilterSettings(brightness = 40, contrast = 40, backlight = 100)))
        assertTrue(out.all { it in 0f..1f })
    }

    @Test
    fun contrastPlus20_widensTheSpreadAroundTheMidpoint() {
        // half 0.3, half 0.7: the midpoint is 0.5
        val src = FloatArray(8 * 8 * 3) { index -> if ((index / 3) % 8 < 4) 0.3f else 0.7f }
        val out = CameraFilterMath.applyLightAndColor(src, params(CameraFilterSettings(contrast = 20)))
        assertClose(std(src) * 1.2, std(out), 0.005)
        assertClose(mean(src), mean(out), 0.005, "the mean stays at the midpoint")
        val flatter = CameraFilterMath.applyLightAndColor(src, params(CameraFilterSettings(contrast = -20)))
        assertClose(std(src) * 0.8, std(flatter), 0.005)
    }

    @Test
    fun backlight_liftsDarkPixelsFarMoreThanBrightOnes() {
        val dark = solid(1, 1, 0.1f, 0.1f, 0.1f)
        val bright = solid(1, 1, 0.9f, 0.9f, 0.9f)
        val p = params(CameraFilterSettings(backlight = 100))
        val darkLift = CameraFilterMath.applyLightAndColor(dark, p)[0] - 0.1f
        val brightLift = CameraFilterMath.applyLightAndColor(bright, p)[0] - 0.9f
        assertClose(0.35 * 0.9 * 0.9, darkLift.toDouble(), 0.001, "(1 - 0.1)^2 weighting")
        assertTrue(darkLift > 10 * brightLift, "dark $darkLift vs bright $brightLift")
        assertTrue(brightLift >= 0f)
    }

    @Test
    fun warmth_movesRedAndBlueAgainstEachOther() {
        val gray = solid(1, 1, 0.5f, 0.5f, 0.5f)
        val warm = CameraFilterMath.applyLightAndColor(gray, params(CameraFilterSettings(warmth = 50)))
        assertClose(0.55, warm[0].toDouble(), 0.001)
        assertClose(0.50, warm[1].toDouble(), 0.001)
        assertClose(0.45, warm[2].toDouble(), 0.001)
        val cool = CameraFilterMath.applyLightAndColor(gray, params(CameraFilterSettings(warmth = -50)))
        assertClose(0.45, cool[0].toDouble(), 0.001)
        assertClose(0.55, cool[2].toDouble(), 0.001)
    }

    @Test
    fun theOrder_isWarmthThenBrightnessThenContrastThenBacklight() {
        // brightness first: 0.4 * 1.2 = 0.48, then contrast: (0.48 - 0.5) * 1.2 + 0.5 = 0.476 (the other order would give 0.456)
        val gray = solid(1, 1, 0.4f, 0.4f, 0.4f)
        val out = CameraFilterMath.applyLightAndColor(gray, params(CameraFilterSettings(brightness = 20, contrast = 20)))
        assertClose(0.476, out[1].toDouble(), 0.0005)
        // warmth before brightness, with a clamp in between: 0.8 * 1.1 = 0.88, * 1.2 = 1.056 -> 1.0
        val warmRed = solid(1, 1, 0.8f, 0.5f, 0.5f)
        assertEquals(1f, CameraFilterMath.applyLightAndColor(warmRed, params(CameraFilterSettings(warmth = 50, brightness = 20)))[0])
    }

    // --- smoothing --------------------------------------------------------------------------------------------------------------------

    /** Deterministic pseudo noise around 0.5 (+-0.03), the same picture every run. */
    private fun noisy(
        width: Int,
        height: Int,
    ): FloatArray {
        var seed = 12345
        return FloatArray(width * height * 3) {
            seed = (seed * 1103515245 + 12345) and 0x7fffffff
            0.5f + ((seed % 1000) / 1000f - 0.5f) * 0.06f
        }
    }

    private fun insideCircle(
        cx: Int,
        cy: Int,
        r: Int,
    ): (Int, Int) -> Float = { x, y -> if ((x - cx) * (x - cx) + (y - cy) * (y - cy) <= r * r) 1f else 0f }

    private fun region(
        pixels: FloatArray,
        width: Int,
        mask: (Int, Int) -> Float,
        inside: Boolean,
    ): FloatArray {
        val list = ArrayList<Float>()
        for (i in 0 until pixels.size / 3) {
            val m = mask(i % width, i / width)
            if ((m > 0f) == inside) for (k in 0..2) list += pixels[i * 3 + k]
        }
        return list.toFloatArray()
    }

    @Test
    fun smoothing_lowersTheNoiseInsideTheMask_andLeavesEverythingOutsideBitForBit() {
        val width = 40
        val height = 40
        val src = noisy(width, height)
        val mask = insideCircle(20, 20, 10)
        val out = CameraFilterMath.smooth(src, width, height, strength = 0.7f, mask = mask)
        val before = std(region(src, width, mask, inside = true))
        val after = std(region(out, width, mask, inside = true))
        assertTrue(after < before * 0.6, "variance inside: $before -> $after")
        assertTrue(
            region(src, width, mask, inside = false).contentEquals(region(out, width, mask, inside = false)),
            "outside the mask: identical",
        )
    }

    @Test
    fun smoothing_withoutAMask_orWithoutStrength_changesNothing() {
        val src = noisy(24, 24)
        assertTrue(src.contentEquals(CameraFilterMath.smooth(src, 24, 24, 0.7f) { _, _ -> 0f }))
        assertTrue(src.contentEquals(CameraFilterMath.smooth(src, 24, 24, 0f) { _, _ -> 1f }))
    }

    @Test
    fun smoothing_keepsAHardEdge() {
        val width = 16
        val height = 16
        // left half 0.2, right half 0.8: an edge of 0.6, far above the range sigma
        val src = FloatArray(width * height * 3) { index -> if ((index / 3) % width < width / 2) 0.2f else 0.8f }
        val out = CameraFilterMath.smooth(src, width, height, strength = 0.7f) { _, _ -> 1f }
        val row = 8
        val left = out[(row * width + width / 2 - 1) * 3]
        val right = out[(row * width + width / 2) * 3]
        assertTrue(right - left >= 0.8f * 0.6f, "the edge keeps at least 80 % of its step: ${right - left}")
    }

    @Test
    fun smoothing_withTheRealFaceMask_leavesTheEyesSharp() {
        val width = 100
        val height = 100
        val face =
            FaceKeypoints(
                Rect(20f, 10f, 60f, 80f),
                leftEye = Pt(35f, 35f),
                rightEye = Pt(65f, 35f),
                nose = Pt(50f, 50f),
                mouth = Pt(50f, 70f),
            )
        val geometry = faceMaskGeometry(face)
        val src = noisy(width, height)
        val out = CameraFilterMath.smooth(src, width, height, 0.7f) { x, y -> faceMaskValue(geometry, x + 0.5f, y + 0.5f) }
        val eye = (35 * width + 35) * 3
        assertEquals(src[eye], out[eye], "the pupil is not smoothed")
        val skin: (Int, Int) -> Float = { x, y -> if (faceMaskValue(geometry, x + 0.5f, y + 0.5f) > 0.99f) 1f else 0f }
        assertTrue(
            std(region(out, width, skin, inside = true)) < std(region(src, width, skin, inside = true)) * 0.7,
            "the skin is smoother",
        )
    }
}
