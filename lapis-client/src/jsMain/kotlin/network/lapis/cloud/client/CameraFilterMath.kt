package network.lapis.cloud.client

import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min

/*
 * V1.9.142 -- the Kotlin REFERENCE of the camera filter shader. [ConferenceCameraFilterStage] runs the same arithmetic on the GPU; this
 * object runs it on plain float arrays so the behaviour of every slider can be asserted on synthetic pictures in unit tests (there is no
 * pixel readback from a GPU test). A change in one place must be made in the other.
 *
 * Pictures are `FloatArray`s of interleaved RGB in 0..1, row by row, top row first. Two deliberate differences from the GPU path, both
 * documented: the reference blurs at the full resolution of its input (the GPU blurs a half- or quarter-resolution copy and scales it up),
 * and it uses the exact kernel below (the GPU evaluates the same kernel separably, horizontal then vertical, like [bilateralBlur] does).
 */
internal object CameraFilterMath {
    /** Taps on each side of the centre of the separable bilateral kernel. */
    const val BILATERAL_RADIUS = 4

    /** Spatial sigma of the kernel in texels. */
    const val SIGMA_S = 2.0f

    /** Range sigma of the kernel (colour distance in 0..1 units): edges taller than this are NOT blurred across. */
    const val SIGMA_R = 0.1f

    private fun clamp01(v: Float): Float = max(0f, min(1f, v))

    /** Rec. 709 luma of a colour in 0..1. */
    fun luma(
        r: Float,
        g: Float,
        b: Float,
    ): Float = 0.2126f * r + 0.7152f * g + 0.0722f * b

    /**
     * The light and colour pass of ONE pixel, in the fixed order: warmth, brightness, contrast, backlight; each step clamped to 0..1.
     * [rgb] is read at [offset] and written back there.
     */
    fun lightAndColor(
        rgb: FloatArray,
        offset: Int,
        p: CameraFilterParams,
    ) {
        var r = clamp01(rgb[offset] * p.warmR)
        var g = clamp01(rgb[offset + 1])
        var b = clamp01(rgb[offset + 2] * p.warmB)
        r = clamp01(r * p.gain)
        g = clamp01(g * p.gain)
        b = clamp01(b * p.gain)
        r = clamp01((r - 0.5f) * p.contrastK + 0.5f)
        g = clamp01((g - 0.5f) * p.contrastK + 0.5f)
        b = clamp01((b - 0.5f) * p.contrastK + 0.5f)
        val l = luma(r, g, b)
        val lift = p.backlightLift * (1f - l) * (1f - l)
        rgb[offset] = clamp01(r + lift)
        rgb[offset + 1] = clamp01(g + lift)
        rgb[offset + 2] = clamp01(b + lift)
    }

    /** [lightAndColor] over a whole picture; returns a new array. */
    fun applyLightAndColor(
        src: FloatArray,
        p: CameraFilterParams,
    ): FloatArray {
        val out = src.copyOf()
        var i = 0
        while (i < out.size) {
            lightAndColor(out, i, p)
            i += 3
        }
        return out
    }

    /** One separable pass of the edge-preserving blur ([horizontal] or vertical). Twin of the fragment shader `BH`/`BV`. */
    private fun bilateralPass(
        src: FloatArray,
        width: Int,
        height: Int,
        horizontal: Boolean,
    ): FloatArray {
        val out = FloatArray(src.size)
        val twoSs = 2f * SIGMA_S * SIGMA_S
        val twoSr = 2f * SIGMA_R * SIGMA_R
        for (y in 0 until height) {
            for (x in 0 until width) {
                val c = (y * width + x) * 3
                var accR = 0f
                var accG = 0f
                var accB = 0f
                var sumW = 0f
                for (i in -BILATERAL_RADIUS..BILATERAL_RADIUS) {
                    val sx = if (horizontal) max(0, min(width - 1, x + i)) else x
                    val sy = if (horizontal) y else max(0, min(height - 1, y + i))
                    val s = (sy * width + sx) * 3
                    val dr = src[s] - src[c]
                    val dg = src[s + 1] - src[c + 1]
                    val db = src[s + 2] - src[c + 2]
                    val w = exp(-(i * i) / twoSs - (dr * dr + dg * dg + db * db) / twoSr)
                    accR += src[s] * w
                    accG += src[s + 1] * w
                    accB += src[s + 2] * w
                    sumW += w
                }
                out[c] = accR / sumW
                out[c + 1] = accG / sumW
                out[c + 2] = accB / sumW
            }
        }
        return out
    }

    /** The edge-preserving blur: horizontal pass, then vertical pass. */
    fun bilateralBlur(
        src: FloatArray,
        width: Int,
        height: Int,
    ): FloatArray = bilateralPass(bilateralPass(src, width, height, horizontal = true), width, height, horizontal = false)

    /**
     * The skin smoothing: `mix(original, blurred, strength * mask)` per pixel. A pixel whose mask is 0 (or a strength of 0) is returned
     * UNCHANGED, bit for bit. [mask] is the weight at pixel (x, y) of the picture.
     */
    fun smooth(
        src: FloatArray,
        width: Int,
        height: Int,
        strength: Float,
        mask: (Int, Int) -> Float,
    ): FloatArray {
        if (strength <= 0f) return src.copyOf()
        val blurred = bilateralBlur(src, width, height)
        val out = src.copyOf()
        for (y in 0 until height) {
            for (x in 0 until width) {
                val m = clamp01(strength * mask(x, y))
                if (m <= 0f) continue
                val i = (y * width + x) * 3
                for (k in 0..2) out[i + k] = src[i + k] + (blurred[i + k] - src[i + k]) * m
            }
        }
        return out
    }
}
