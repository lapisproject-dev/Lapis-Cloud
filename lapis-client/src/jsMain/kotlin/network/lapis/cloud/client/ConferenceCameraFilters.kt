package network.lapis.cloud.client

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/*
 * V1.9.142 "Kamera-Filter" -- the PURE part of the camera filters: the five sliders and how they map to shader parameters, the persisted
 * value, the face mask geometry, the temporal smoothing of the detected face, and the performance governor. No DOM, no JS, no WebGL -- all of
 * it is unit-tested; the shader in [ConferenceCameraFilterStage] is a line-by-line twin of [CameraFilterMath].
 *
 * The filters run ONLY in the browser, on the person's own camera frames, before the frames are published (so everybody sees them, also in a
 * recording or a livestream -- the panel says so). Nothing here is sent anywhere, stored or logged except the five slider values.
 */

/** The remembered slider positions of the person. `enabled` is the main switch; with it off the sliders keep their values. */
internal data class CameraFilterSettings(
    val enabled: Boolean = false,
    val brightness: Int = 0,
    val contrast: Int = 0,
    val warmth: Int = 0,
    val backlight: Int = 0,
    val smoothing: Int = 0,
)

internal object CameraFilterLimits {
    const val BRIGHTNESS_MIN = -40
    const val BRIGHTNESS_MAX = 40
    const val CONTRAST_MIN = -40
    const val CONTRAST_MAX = 40
    const val WARMTH_MIN = -50
    const val WARMTH_MAX = 50
    const val BACKLIGHT_MIN = 0
    const val BACKLIGHT_MAX = 100
    const val SMOOTHING_MIN = 0
    const val SMOOTHING_MAX = 100

    /** The skin smoothing never reaches a full blend: at 100 % the filtered picture contributes 70 %, so skin keeps its texture. */
    const val MAX_SMOOTHING = 0.70f

    /** Largest colour shift of the warmth slider (red up / blue down at +50, the opposite at -50). */
    const val MAX_WARMTH_SHIFT = 0.10f

    /** Largest lift of the backlight slider (added to dark pixels, weighted by `(1 - luma)^2`). */
    const val MAX_BACKLIGHT_LIFT = 0.35f
}

private fun Int.clampedTo(
    low: Int,
    high: Int,
): Int = max(low, min(high, this))

/** Every slider inside its range. (A decoded value is already an Int; NaN and non-numbers are removed in [decodeCameraFilterSettings].) */
internal fun CameraFilterSettings.clamped(): CameraFilterSettings =
    copy(
        brightness = brightness.clampedTo(CameraFilterLimits.BRIGHTNESS_MIN, CameraFilterLimits.BRIGHTNESS_MAX),
        contrast = contrast.clampedTo(CameraFilterLimits.CONTRAST_MIN, CameraFilterLimits.CONTRAST_MAX),
        warmth = warmth.clampedTo(CameraFilterLimits.WARMTH_MIN, CameraFilterLimits.WARMTH_MAX),
        backlight = backlight.clampedTo(CameraFilterLimits.BACKLIGHT_MIN, CameraFilterLimits.BACKLIGHT_MAX),
        smoothing = smoothing.clampedTo(CameraFilterLimits.SMOOTHING_MIN, CameraFilterLimits.SMOOTHING_MAX),
    )

/** All five sliders at their neutral position (the main switch is not looked at). */
internal fun CameraFilterSettings.isNeutral(): Boolean = brightness == 0 && contrast == 0 && warmth == 0 && backlight == 0 && smoothing == 0

/** What the shader gets. Neutral values are exactly `1`/`0`, so a neutral filter is the identity. */
internal data class CameraFilterParams(
    val gain: Float,
    val contrastK: Float,
    val warmR: Float,
    val warmB: Float,
    val backlightLift: Float,
    val smoothStrength: Float,
) {
    /** `true` when the light and colour pass changes nothing. */
    val lightIsNeutral: Boolean
        get() = gain == 1f && contrastK == 1f && warmR == 1f && warmB == 1f && backlightLift == 0f
}

/** The exact mapping of the design table: slider position -> shader parameter. [smoothingPaused] (session only) zeroes the smoothing. */
internal fun cameraFilterParams(
    settings: CameraFilterSettings,
    smoothingPaused: Boolean,
): CameraFilterParams {
    val s = settings.clamped()
    val warmShift = CameraFilterLimits.MAX_WARMTH_SHIFT * s.warmth / CameraFilterLimits.WARMTH_MAX
    return CameraFilterParams(
        gain = 1f + s.brightness / 100f,
        contrastK = 1f + s.contrast / 100f,
        warmR = 1f + warmShift,
        warmB = 1f - warmShift,
        backlightLift = CameraFilterLimits.MAX_BACKLIGHT_LIFT * s.backlight / CameraFilterLimits.BACKLIGHT_MAX,
        smoothStrength = if (smoothingPaused) 0f else CameraFilterLimits.MAX_SMOOTHING * s.smoothing / CameraFilterLimits.SMOOTHING_MAX,
    )
}

/** `true` iff the filter stage has to touch frames: main switch on, browser able, at least one slider away from neutral. */
internal fun cameraFilterWanted(
    settings: CameraFilterSettings,
    supported: Boolean,
): Boolean = supported && settings.enabled && !settings.isNeutral()

/**
 * Whether the camera filter panel is offered at all: a person with a MEMBER session (not a guest or friend of another server, and not an
 * event guest without an account -- there is no session then). The filter itself runs locally and needs nothing from the server; this is the
 * same "the least needed for guests" line the overlays draw.
 */
internal fun cameraFilterOfferedFor(session: network.lapis.cloud.shared.domain.SessionInfoDto?): Boolean =
    session != null && !isConferenceNonMember(session)

internal const val CAMERA_FILTER_STORAGE_KEY = "lapis.conference.cameraFilters.v1"

/** `{"enabled":true,"brightness":5,...}` -- only the five numbers and one boolean, no free text. */
internal fun encodeCameraFilterSettings(settings: CameraFilterSettings): String {
    val s = settings.clamped()
    return "{\"enabled\":${s.enabled},\"brightness\":${s.brightness},\"contrast\":${s.contrast}," +
        "\"warmth\":${s.warmth},\"backlight\":${s.backlight},\"smoothing\":${s.smoothing}}"
}

private fun numberField(
    parsed: dynamic,
    name: String,
): Int {
    val value: dynamic = parsed[name]
    if (jsTypeOf(value) != "number") return 0
    val number = (value as Number).toDouble()
    if (number.isNaN() || number.isInfinite()) return 0
    return number.toInt()
}

/**
 * Reads a remembered value. Anything wrong (no value, not JSON, not an object, a wrong type, NaN) gives the neutral settings with the main
 * switch OFF -- a damaged value never switches a filter on. Every number is clamped; no unchecked value reaches a shader uniform.
 */
internal fun decodeCameraFilterSettings(raw: String?): CameraFilterSettings {
    if (raw.isNullOrBlank() || raw.length > 400) return CameraFilterSettings()
    return try {
        val parsed: dynamic = JSON.parse<dynamic>(raw)
        if (parsed == null || jsTypeOf(parsed) != "object") return CameraFilterSettings()
        CameraFilterSettings(
            // `===`: on a `dynamic` value `==` is the loose JS equality, under which the number 1 would equal `true`
            enabled = parsed["enabled"] === true,
            brightness = numberField(parsed, "brightness"),
            contrast = numberField(parsed, "contrast"),
            warmth = numberField(parsed, "warmth"),
            backlight = numberField(parsed, "backlight"),
            smoothing = numberField(parsed, "smoothing"),
        ).clamped()
    } catch (e: Throwable) {
        CameraFilterSettings()
    }
}

// ---------------------------------------------------------------------------------------------------------------------------------------
// Face mask geometry (pure). All lengths in pixels of the camera frame, origin top-left.
// ---------------------------------------------------------------------------------------------------------------------------------------

internal data class Pt(
    val x: Float,
    val y: Float,
)

internal data class Rect(
    val x: Float,
    val y: Float,
    val width: Float,
    val height: Float,
)

internal data class Ellipse(
    val cx: Float,
    val cy: Float,
    val rx: Float,
    val ry: Float,
)

/** What the detector delivers for the one face that is smoothed: its box and four keypoints (the ears are not needed). */
internal data class FaceKeypoints(
    val box: Rect,
    val leftEye: Pt,
    val rightEye: Pt,
    val nose: Pt,
    val mouth: Pt,
)

internal data class FaceMaskGeometry(
    val face: Ellipse,
    val eyes: List<Ellipse>,
    val brows: List<Ellipse>,
    val mouth: Ellipse,
    val feather: Float,
)

/** The face ellipse is the box scaled up by this much (a detector box is tight around the face, the skin reaches further). */
internal const val FACE_MASK_SCALE = 1.15f

/** The soft edge of the face ellipse as a fraction of its radius. */
internal const val FACE_MASK_FEATHER = 0.12f

/** The soft edge of the excluded eye, brow and mouth ellipses (they fade in more gently than the face fades out). */
internal const val FACE_MASK_EXCLUDE_FEATHER = 0.35f

private const val EYE_RADIUS = 0.22f
private const val BROW_OFFSET = 0.30f
private const val BROW_RX = 0.30f
private const val BROW_RY = 0.12f
private const val MOUTH_RX = 0.45f
private const val MOUTH_RY = 0.25f
private const val MIN_RADIUS_PX = 1f

private fun ellipseOf(
    cx: Float,
    cy: Float,
    rx: Float,
    ry: Float,
): Ellipse = Ellipse(cx, cy, max(rx, MIN_RADIUS_PX), max(ry, MIN_RADIUS_PX))

/**
 * Skin = the face ellipse (box x 1.15, feather 12 %) MINUS the eyes (radius 0.22 x eye distance), the brows (0.30 x eye distance above the
 * eyes) and the mouth (0.45 x 0.25 x eye distance). Eyes, brows and lips keep their sharpness. Hair line and beard are NOT excluded -- only
 * the edge preservation of the filter protects them (documented limitation).
 */
internal fun faceMaskGeometry(k: FaceKeypoints): FaceMaskGeometry {
    var eyeDistance =
        sqrt(
            (k.rightEye.x - k.leftEye.x) * (k.rightEye.x - k.leftEye.x) + (k.rightEye.y - k.leftEye.y) * (k.rightEye.y - k.leftEye.y),
        )
    if (!(eyeDistance > 1f)) eyeDistance = k.box.width * 0.4f
    val face =
        ellipseOf(
            cx = k.box.x + k.box.width / 2f,
            cy = k.box.y + k.box.height / 2f,
            rx = k.box.width / 2f * FACE_MASK_SCALE,
            ry = k.box.height / 2f * FACE_MASK_SCALE,
        )
    val eyePoints = listOf(k.leftEye, k.rightEye)
    return FaceMaskGeometry(
        face = face,
        eyes = eyePoints.map { ellipseOf(it.x, it.y, eyeDistance * EYE_RADIUS, eyeDistance * EYE_RADIUS) },
        brows = eyePoints.map { ellipseOf(it.x, it.y - eyeDistance * BROW_OFFSET, eyeDistance * BROW_RX, eyeDistance * BROW_RY) },
        mouth = ellipseOf(k.mouth.x, k.mouth.y, eyeDistance * MOUTH_RX, eyeDistance * MOUTH_RY),
        feather = FACE_MASK_FEATHER,
    )
}

private fun smoothstep(
    edge0: Float,
    edge1: Float,
    x: Float,
): Float {
    val t = max(0f, min(1f, (x - edge0) / (edge1 - edge0)))
    return t * t * (3f - 2f * t)
}

/** 1 inside the ellipse, 0 outside, a smoothstep ramp over the outer [feather] of the radius. Twin of the shader's `softEll`. */
internal fun softEllipse(
    e: Ellipse,
    feather: Float,
    x: Float,
    y: Float,
): Float {
    val qx = (x - e.cx) / e.rx
    val qy = (y - e.cy) / e.ry
    val d = sqrt(qx * qx + qy * qy)
    return 1f - smoothstep(1f - feather, 1f, d)
}

/** The mask weight at a pixel: 1 on skin, 0 on eyes / brows / mouth and outside the face, soft in between. Twin of the shader's `main`. */
internal fun faceMaskValue(
    g: FaceMaskGeometry,
    x: Float,
    y: Float,
): Float {
    val base = softEllipse(g.face, g.feather, x, y)
    if (base <= 0f) return 0f
    var excluded = 0f
    for (e in g.eyes) excluded = max(excluded, softEllipse(e, FACE_MASK_EXCLUDE_FEATHER, x, y))
    for (e in g.brows) excluded = max(excluded, softEllipse(e, FACE_MASK_EXCLUDE_FEATHER, x, y))
    excluded = max(excluded, softEllipse(g.mouth, FACE_MASK_EXCLUDE_FEATHER, x, y))
    return base * (1f - excluded)
}

/** The six excluded ellipses in the fixed order of the shader's `u_ex[6]`: eyes, brows, mouth, and one spare (a copy of the mouth). */
internal fun faceMaskExclusions(g: FaceMaskGeometry): List<Ellipse> = g.eyes + g.brows + g.mouth + g.mouth

// ---------------------------------------------------------------------------------------------------------------------------------------
// Face tracking over frames (pure): an exponential mean of the box and the keypoints between the detector runs.
// ---------------------------------------------------------------------------------------------------------------------------------------

internal const val FACE_TRACK_ALPHA = 0.5f

/** Without a detection for this many frames the face is gone and the mask decays. */
internal const val FACE_TRACK_MISS_FRAMES = 10

/**
 * Keeps the smoothed face. [observe] is called on the frames where the detector ran (with the LARGEST face or `null`), [advance] on every
 * frame. Mutable on purpose: the values live in one reused array, nothing is allocated per frame. Never stored, logged or exported.
 */
internal class FaceTracker(
    private val alpha: Float = FACE_TRACK_ALPHA,
    private val missFrames: Int = FACE_TRACK_MISS_FRAMES,
) {
    // box x, y, w, h, left eye x, y, right eye x, y, nose x, y, mouth x, y
    private val values = FloatArray(VALUE_COUNT)
    private var hasFace = false
    private var framesSinceSeen = Int.MAX_VALUE

    fun observe(face: FaceKeypoints?) {
        if (face == null) return
        val next = flatten(face)
        if (!hasFace) {
            next.copyInto(values)
        } else {
            for (i in values.indices) values[i] = values[i] + alpha * (next[i] - values[i])
        }
        hasFace = true
        framesSinceSeen = 0
    }

    /** One frame passed; the face is dropped once [missFrames] frames went by without a detection. */
    fun advance() {
        if (!hasFace) return
        if (framesSinceSeen < Int.MAX_VALUE) framesSinceSeen++
        if (framesSinceSeen > missFrames) {
            hasFace = false
        }
    }

    /** `true` while a face is held (no allocation, unlike [current]). */
    val present: Boolean get() = hasFace

    fun current(): FaceKeypoints? =
        if (hasFace) {
            FaceKeypoints(
                box = Rect(values[0], values[1], values[2], values[3]),
                leftEye = Pt(values[4], values[5]),
                rightEye = Pt(values[6], values[7]),
                nose = Pt(values[8], values[9]),
                mouth = Pt(values[10], values[11]),
            )
        } else {
            null
        }

    fun clear() {
        hasFace = false
        framesSinceSeen = Int.MAX_VALUE
    }

    private fun flatten(k: FaceKeypoints) =
        floatArrayOf(
            k.box.x,
            k.box.y,
            k.box.width,
            k.box.height,
            k.leftEye.x,
            k.leftEye.y,
            k.rightEye.x,
            k.rightEye.y,
            k.nose.x,
            k.nose.y,
            k.mouth.x,
            k.mouth.y,
        )

    private companion object {
        const val VALUE_COUNT = 12
    }
}

/** The largest of several detections (the person's own camera shows one user; a second face in the background is ignored). */
internal fun largestFace(faces: List<FaceKeypoints>): FaceKeypoints? = faces.maxByOrNull { abs(it.box.width * it.box.height) }

// ---------------------------------------------------------------------------------------------------------------------------------------
// Performance governor (pure, injected clock).
// ---------------------------------------------------------------------------------------------------------------------------------------

internal enum class PerfAction { NONE, REDUCED, PAUSED }

/** Frame time (EMA) above which the stage counts as too slow. This is a stage cost, not an FPS promise. */
internal const val CAMERA_FILTER_SLOW_FRAME_MS = 12.0

/** How long the stage must stay slow before it steps down (reduced quality first, then the pause). */
internal const val CAMERA_FILTER_SLOW_WINDOW_MS = 3_000.0

private const val PERF_EMA_ALPHA = 0.1

/**
 * Stage 1 -- more than [CAMERA_FILTER_SLOW_FRAME_MS] (EMA, alpha 0.1) for [CAMERA_FILTER_SLOW_WINDOW_MS]: [PerfAction.REDUCED] (the detector
 * runs every 6th frame and the blur works at a quarter of the resolution). Stage 2 -- the same again, a further window later:
 * [PerfAction.PAUSED] (the skin smoothing stops; the slider keeps its value). Each action is returned exactly once, at the step.
 * [reset] ("Erneut versuchen") starts again at the full quality.
 */
internal class CameraFilterPerfGovernor(
    private val nowMs: () -> Double,
) {
    private var ema: Double? = null
    private var slowSince: Double? = null
    private var level = 0

    fun recordFrame(durationMs: Double): PerfAction {
        val previous = ema
        val current = if (previous == null) durationMs else previous + PERF_EMA_ALPHA * (durationMs - previous)
        ema = current
        if (level >= 2) return PerfAction.NONE
        val now = nowMs()
        if (current <= CAMERA_FILTER_SLOW_FRAME_MS) {
            slowSince = null
            return PerfAction.NONE
        }
        val since = slowSince
        if (since == null) {
            slowSince = now
            return PerfAction.NONE
        }
        if (now - since < CAMERA_FILTER_SLOW_WINDOW_MS) return PerfAction.NONE
        level++
        slowSince = now
        return if (level == 1) PerfAction.REDUCED else PerfAction.PAUSED
    }

    fun reset() {
        ema = null
        slowSince = null
        level = 0
    }
}
