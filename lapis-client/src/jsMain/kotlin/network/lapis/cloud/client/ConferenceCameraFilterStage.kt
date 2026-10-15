package network.lapis.cloud.client

import io.kvision.utils.obj
import kotlinx.browser.window
import kotlinx.coroutines.await
import kotlinx.coroutines.launch
import network.lapis.cloud.client.livekit.FaceDetector
import network.lapis.cloud.client.livekit.FaceDetectorBaseOptions
import network.lapis.cloud.client.livekit.FaceDetectorHandle
import network.lapis.cloud.client.livekit.FaceDetectorOptions
import network.lapis.cloud.client.livekit.FilesetResolver
import org.khronos.webgl.Float32Array
import kotlin.math.max

/*
 * V1.9.142 "Kamera-Filter" -- the frame stage: light and colour, then skin smoothing, on the person's own camera frames. It sits in the
 * lightweight processor and (as the INPUT hook) in the segmenting processor, BEFORE the background effect, so the filter corrects the
 * sensor and not the chosen background picture; overlays are drawn after it.
 *
 * Everything runs in this browser tab. The face detector (MediaPipe BlazeFace short range) and its WASM come from this server only
 * ([ConferenceBackgroundAssets.TASKS_VISION_FILE_SET], [CameraFilterAssets.FACE_MODEL_PATH]); no frame, box or keypoint ever leaves the tab,
 * is stored, logged or exported.
 *
 * **Ownership of a VideoFrame.** [process] takes the frame it is given. It returns either that same frame (nothing done, or anything went
 * wrong -- the original frames pass on, the camera never freezes) or a NEW frame, in which case the original has been closed exactly once,
 * AFTER the new one was created. [process] never throws.
 *
 * **GPU work** (WebGL2 on an `OffscreenCanvas`, created lazily and torn down when the stage is not wanted): pass L (light/colour, full
 * resolution) -> [CameraFilterMath] twin of it; with smoothing and a face additionally DS (half or quarter resolution copy), BH + BV
 * (separable edge-preserving blur) and F (`mix(original, blurred, strength * mask)`, mask computed analytically from the face ellipses --
 * no mask texture). Nothing is allocated per frame except the output `VideoFrame`.
 */

/** Same-origin paths of the face model (the WASM comes from [ConferenceBackgroundAssets]). Never a CDN. */
internal object CameraFilterAssets {
    const val FACE_MODEL_PATH = "/assets/mediapipe/blaze_face_short_range.tflite"
}

/** What the controller needs from a frame stage; the production one is [WebGlCameraFilterStage], tests use fakes. */
internal interface CameraFilterStage {
    /** `true` while the stage changes frames (main switch on, a slider away from neutral, not failed). Drives the processor on the track. */
    val active: Boolean

    /** The person's sliders (and the session-only pause of the smoothing). Cheap; allowed from any thread of the page. */
    fun configure(
        settings: CameraFilterSettings,
        smoothingPaused: Boolean,
    )

    /** One frame in, one frame out (see the ownership rule above). Never throws. */
    fun process(frame: dynamic): dynamic

    /** "Erneut versuchen": full quality again, and the face detector may start again. */
    fun retrySmoothing()

    /** Leaving the call: GPU resources and the detector are released. */
    fun release()
}

private const val DETECT_EVERY_FRAMES = 3
private const val DETECT_EVERY_FRAMES_REDUCED = 6
private const val BLUR_DIVISOR = 2
private const val BLUR_DIVISOR_REDUCED = 4
private const val MAX_DETECTOR_ERRORS = 5
private const val MIN_DETECTION_CONFIDENCE = 0.5

private const val VERTEX_SHADER =
    """#version 300 es
out vec2 vUv;
void main() {
    vec2 p = vec2(float((gl_VertexID << 1) & 2), float(gl_VertexID & 2));
    vUv = p;
    gl_Position = vec4(p * 2.0 - 1.0, 0.0, 1.0);
}
"""

// Pass L -- twin of CameraFilterMath.lightAndColor. The input is sampled upside down: the texture rows run top-down, the GL framebuffer
// bottom-up; every later texture and the canvas are in GL orientation (the canvas is shown the right way up).
private const val LIGHT_SHADER =
    """#version 300 es
precision highp float;
in vec2 vUv;
out vec4 o;
uniform sampler2D u_src;
uniform vec4 u_a;
uniform float u_lift;
void main() {
    vec3 c = texture(u_src, vec2(vUv.x, 1.0 - vUv.y)).rgb;
    c = clamp(vec3(c.r * u_a.z, c.g, c.b * u_a.w), 0.0, 1.0);
    c = clamp(c * u_a.x, 0.0, 1.0);
    c = clamp((c - 0.5) * u_a.y + 0.5, 0.0, 1.0);
    float l = dot(c, vec3(0.2126, 0.7152, 0.0722));
    float w = (1.0 - l) * (1.0 - l);
    c = clamp(c + u_lift * w, 0.0, 1.0);
    o = vec4(c, 1.0);
}
"""

private const val COPY_SHADER =
    """#version 300 es
precision highp float;
in vec2 vUv;
out vec4 o;
uniform sampler2D u_src;
void main() {
    o = vec4(texture(u_src, vUv).rgb, 1.0);
}
"""

// Passes BH / BV -- twin of CameraFilterMath.bilateralBlur (radius 4, sigmaS 2.0, sigmaR 0.1).
private const val BLUR_SHADER =
    """#version 300 es
precision highp float;
in vec2 vUv;
out vec4 o;
uniform sampler2D u_src;
uniform vec2 u_step;
void main() {
    vec3 c0 = texture(u_src, vUv).rgb;
    vec3 acc = vec3(0.0);
    float sum = 0.0;
    for (int i = -4; i <= 4; i++) {
        vec3 c = texture(u_src, vUv + u_step * float(i)).rgb;
        vec3 d = c - c0;
        float w = exp(-float(i * i) / 8.0 - dot(d, d) / 0.02);
        acc += c * w;
        sum += w;
    }
    o = vec4(acc / sum, 1.0);
}
"""

// Pass F -- twin of faceMaskValue and CameraFilterMath.smooth. u_face = (cx, cy, rx, ry) in frame pixels, u_ex = six excluded ellipses.
private const val MIX_SHADER =
    """#version 300 es
precision highp float;
in vec2 vUv;
out vec4 o;
uniform sampler2D u_orig;
uniform sampler2D u_blur;
uniform float u_strength;
uniform vec2 u_size;
uniform vec4 u_face;
uniform float u_feather;
uniform float u_exFeather;
uniform vec4 u_ex[6];
float softEll(vec4 e, float f, vec2 p) {
    vec2 q = (p - e.xy) / e.zw;
    return 1.0 - smoothstep(1.0 - f, 1.0, length(q));
}
void main() {
    vec2 p = vec2(vUv.x * u_size.x, (1.0 - vUv.y) * u_size.y);
    float m = softEll(u_face, u_feather, p);
    float ex = 0.0;
    for (int i = 0; i < 6; i++) {
        ex = max(ex, softEll(u_ex[i], u_exFeather, p));
    }
    m *= 1.0 - ex;
    vec3 a = texture(u_orig, vUv).rgb;
    vec3 b = texture(u_blur, vUv).rgb;
    o = vec4(mix(a, b, clamp(u_strength * m, 0.0, 1.0)), 1.0);
}
"""

/** Reads one `Detection` of the MediaPipe result into a [FaceKeypoints] in frame pixels; `null` if it has no box or fewer than 4 keypoints. */
internal fun faceFromDetection(
    detection: dynamic,
    frameWidth: Int,
    frameHeight: Int,
): FaceKeypoints? {
    if (detection == null) return null
    val box: dynamic = detection.boundingBox
    val points: dynamic = detection.keypoints
    if (box == null || points == null) return null
    if (jsTypeOf(points.length) != "number" || (points.length as Number).toInt() < 4) return null

    fun num(v: dynamic): Float? {
        if (jsTypeOf(v) != "number") return null
        val d = (v as Number).toDouble()
        return if (d.isNaN() || d.isInfinite()) null else d.toFloat()
    }
    val bx = num(box.originX) ?: return null
    val by = num(box.originY) ?: return null
    val bw = num(box.width) ?: return null
    val bh = num(box.height) ?: return null
    if (bw <= 0f || bh <= 0f) return null

    fun point(index: Int): Pt? {
        val p: dynamic = points[index]
        val px = num(p.x) ?: return null
        val py = num(p.y) ?: return null
        return Pt(px * frameWidth, py * frameHeight)
    }
    // MediaPipe's order: right eye, left eye, nose tip, mouth centre (then the two ear points, not used).
    val rightEye = point(0) ?: return null
    val leftEye = point(1) ?: return null
    val nose = point(2) ?: return null
    val mouth = point(3) ?: return null
    return FaceKeypoints(Rect(bx, by, bw, bh), leftEye, rightEye, nose, mouth)
}

private class GlResources(
    val canvas: dynamic,
    val gl: dynamic,
) {
    var light: dynamic = null
    var copy: dynamic = null
    var blur: dynamic = null
    var mix: dynamic = null
    var srcTex: dynamic = null
    var texA: dynamic = null
    var texH1: dynamic = null
    var texH2: dynamic = null
    var fboA: dynamic = null
    var fboH1: dynamic = null
    var fboH2: dynamic = null
    var width = 0
    var height = 0
    var divisor = 0
    var smallW = 0
    var smallH = 0
}

internal class WebGlCameraFilterStage(
    private val onFailure: () -> Unit,
    private val onPerfAction: (PerfAction) -> Unit,
    private val onSmoothingUnavailable: () -> Unit,
    private val now: () -> Double = { window.performance.now() },
    /** Test seam: `false` => the face detector is never started (a test feeds the face through [injectFaceForTest]). */
    private val useDetector: Boolean = true,
) : CameraFilterStage {
    private var params = cameraFilterParams(CameraFilterSettings(), smoothingPaused = false)
    private var wanted = false
    private var failed = false
    private var released = false
    private val governor = CameraFilterPerfGovernor(now)
    private var detectEvery = DETECT_EVERY_FRAMES
    private var blurDivisor = BLUR_DIVISOR
    private val tracker = FaceTracker()
    private var maskUniforms: Float32Array? = null
    private var gl: GlResources? = null
    private var frameCounter = 0
    private var lastDetectTimestampMs = 0.0
    private var detector: FaceDetectorHandle? = null
    private var detectorStarting = false
    private var smoothingDead = false
    private var detectorErrors = 0
    private val exUniform = Float32Array(24)
    private val faceUniform = Float32Array(4)

    override val active: Boolean get() = wanted && !failed && !released

    override fun configure(
        settings: CameraFilterSettings,
        smoothingPaused: Boolean,
    ) {
        if (released) return
        val newParams = cameraFilterParams(settings, smoothingPaused)
        val effective = !(newParams.lightIsNeutral && newParams.smoothStrength == 0f)
        val nowWanted = settings.enabled && effective
        if (nowWanted && !wanted) failed = false // a fresh switch-on is a fresh start after a failure
        params = newParams
        wanted = nowWanted
        if (newParams.smoothStrength <= 0f) {
            closeDetector()
            tracker.clear()
            maskUniforms = null
        }
        if (!nowWanted) releaseGl()
    }

    /** Test seam: the face the detector would have found (the tracker holds it for [FACE_TRACK_MISS_FRAMES] frames). */
    internal fun injectFaceForTest(face: FaceKeypoints) {
        tracker.observe(face)
        rebuildMaskUniforms()
    }

    override fun retrySmoothing() {
        governor.reset()
        detectEvery = DETECT_EVERY_FRAMES
        blurDivisor = BLUR_DIVISOR
        smoothingDead = false
        detectorErrors = 0
    }

    override fun release() {
        released = true
        wanted = false
        closeDetector()
        releaseGl()
    }

    override fun process(frame: dynamic): dynamic {
        if (!active) return frame
        if (jsTypeOf(frame) != "object" || frame == null || jsTypeOf(frame.displayWidth) != "number") return frame
        val started = now()
        return try {
            val out = render(frame)
            if (params.smoothStrength > 0f) {
                when (val action = governor.recordFrame(now() - started)) {
                    PerfAction.NONE -> Unit
                    PerfAction.REDUCED -> {
                        detectEvery = DETECT_EVERY_FRAMES_REDUCED
                        blurDivisor = BLUR_DIVISOR_REDUCED
                        onPerfAction(action)
                    }
                    PerfAction.PAUSED -> onPerfAction(action)
                }
            }
            out
        } catch (e: Throwable) {
            // The original frame passes on (it was not closed: render closes it only AFTER the new frame exists).
            fail()
            frame
        }
    }

    private fun fail() {
        if (failed) return
        failed = true
        closeDetector()
        releaseGl()
        onFailure()
    }

    // --- rendering --------------------------------------------------------------------------------------------------------------------

    private fun render(frame: dynamic): dynamic {
        val width = (frame.displayWidth as Number).toInt()
        val height = (frame.displayHeight as Number).toInt()
        if (width <= 0 || height <= 0) return frame
        val res = ensureGl(width, height)
        val g = res.gl
        if (g.isContextLost() == true) throw IllegalStateException("WebGL context lost")

        val smoothingWanted = params.smoothStrength > 0f && !smoothingDead
        if (smoothingWanted) trackFace(frame, width, height)
        val smoothing = smoothingWanted && maskUniforms != null

        g.activeTexture(g.TEXTURE0)
        g.bindTexture(g.TEXTURE_2D, res.srcTex)
        g.texImage2D(g.TEXTURE_2D, 0, g.RGBA, g.RGBA, g.UNSIGNED_BYTE, frame)

        // pass L
        g.useProgram(res.light)
        g.uniform1i(g.getUniformLocation(res.light, "u_src"), 0)
        g.uniform4f(g.getUniformLocation(res.light, "u_a"), params.gain, params.contrastK, params.warmR, params.warmB)
        g.uniform1f(g.getUniformLocation(res.light, "u_lift"), params.backlightLift)
        if (!smoothing) {
            drawTo(res, null, width, height)
        } else {
            drawTo(res, res.fboA, width, height)
            // DS: copy into the small texture (linear filtering averages the texels)
            g.useProgram(res.copy)
            bindTexture(g, 0, res.texA)
            g.uniform1i(g.getUniformLocation(res.copy, "u_src"), 0)
            drawTo(res, res.fboH1, res.smallW, res.smallH)
            // BH, BV
            g.useProgram(res.blur)
            g.uniform1i(g.getUniformLocation(res.blur, "u_src"), 0)
            bindTexture(g, 0, res.texH1)
            g.uniform2f(g.getUniformLocation(res.blur, "u_step"), 1.0 / res.smallW, 0.0)
            drawTo(res, res.fboH2, res.smallW, res.smallH)
            bindTexture(g, 0, res.texH2)
            g.uniform2f(g.getUniformLocation(res.blur, "u_step"), 0.0, 1.0 / res.smallH)
            drawTo(res, res.fboH1, res.smallW, res.smallH)
            // F
            g.useProgram(res.mix)
            bindTexture(g, 0, res.texA)
            bindTexture(g, 1, res.texH1)
            g.uniform1i(g.getUniformLocation(res.mix, "u_orig"), 0)
            g.uniform1i(g.getUniformLocation(res.mix, "u_blur"), 1)
            g.uniform1f(g.getUniformLocation(res.mix, "u_strength"), params.smoothStrength)
            g.uniform2f(g.getUniformLocation(res.mix, "u_size"), width.toFloat(), height.toFloat())
            g.uniform4fv(g.getUniformLocation(res.mix, "u_face"), faceUniform)
            g.uniform1f(g.getUniformLocation(res.mix, "u_feather"), FACE_MASK_FEATHER)
            g.uniform1f(g.getUniformLocation(res.mix, "u_exFeather"), FACE_MASK_EXCLUDE_FEATHER)
            g.uniform4fv(g.getUniformLocation(res.mix, "u_ex"), exUniform)
            drawTo(res, null, width, height)
        }

        // The new frame is made right after the draw, in the same task (the canvas content is gone after the next composite).
        val out = newVideoFrameFrom(res.canvas, frame.timestamp)
        try {
            frame.close()
        } catch (e: Throwable) {
            // already closed by someone else -- nothing to do, the new frame is valid
        }
        return out
    }

    private fun bindTexture(
        g: dynamic,
        unit: Int,
        texture: dynamic,
    ) {
        g.activeTexture(g.TEXTURE0 + unit)
        g.bindTexture(g.TEXTURE_2D, texture)
    }

    private fun drawTo(
        res: GlResources,
        framebuffer: dynamic,
        width: Int,
        height: Int,
    ) {
        val g = res.gl
        g.bindFramebuffer(g.FRAMEBUFFER, framebuffer)
        g.viewport(0, 0, width, height)
        g.drawArrays(g.TRIANGLES, 0, 3)
    }

    // --- face ---------------------------------------------------------------------------------------------------------------------------

    private fun trackFace(
        frame: dynamic,
        width: Int,
        height: Int,
    ) {
        if (useDetector) ensureDetector()
        val d = detector
        frameCounter++
        if (d != null && frameCounter % detectEvery == 0) {
            try {
                val timestamp = max(lastDetectTimestampMs + 1.0, (frame.timestamp as Number).toDouble() / 1000.0)
                lastDetectTimestampMs = timestamp
                val result: dynamic = d.detectForVideo(frame, timestamp)
                val faces = mutableListOf<FaceKeypoints>()
                val detections: dynamic = result?.detections
                if (detections != null) {
                    for (i in 0 until (detections.length as Number).toInt()) {
                        faceFromDetection(detections[i], width, height)?.let { faces += it }
                    }
                }
                tracker.observe(largestFace(faces))
                detectorErrors = 0
                rebuildMaskUniforms()
            } catch (e: Throwable) {
                detectorErrors++
                if (detectorErrors >= MAX_DETECTOR_ERRORS) {
                    smoothingDead = true
                    closeDetector()
                    onSmoothingUnavailable()
                }
            }
        }
        val hadFace = tracker.present
        tracker.advance()
        if (hadFace && !tracker.present) maskUniforms = null
    }

    private fun rebuildMaskUniforms() {
        val face = tracker.current()
        if (face == null) {
            maskUniforms = null
            return
        }
        val geometry = faceMaskGeometry(face)
        faceUniform.asDynamic()[0] = geometry.face.cx
        faceUniform.asDynamic()[1] = geometry.face.cy
        faceUniform.asDynamic()[2] = geometry.face.rx
        faceUniform.asDynamic()[3] = geometry.face.ry
        faceMaskExclusions(geometry).forEachIndexed { index, e ->
            exUniform.asDynamic()[index * 4] = e.cx
            exUniform.asDynamic()[index * 4 + 1] = e.cy
            exUniform.asDynamic()[index * 4 + 2] = e.rx
            exUniform.asDynamic()[index * 4 + 3] = e.ry
        }
        maskUniforms = faceUniform
    }

    private fun ensureDetector() {
        if (detector != null || detectorStarting || smoothingDead || released) return
        detectorStarting = true
        AppScope.launch {
            try {
                val fileset = FilesetResolver.forVisionTasks(ConferenceBackgroundAssets.TASKS_VISION_FILE_SET).await()
                val options =
                    obj<FaceDetectorOptions> {
                        baseOptions =
                            obj<FaceDetectorBaseOptions> {
                                modelAssetPath = CameraFilterAssets.FACE_MODEL_PATH
                                delegate = "CPU"
                            }
                        runningMode = "VIDEO"
                        minDetectionConfidence = MIN_DETECTION_CONFIDENCE
                    }
                val created = FaceDetector.createFromOptions(fileset, options).await()
                if (released || params.smoothStrength <= 0f || smoothingDead) {
                    runCatching { created.close() }
                } else {
                    detector = created
                }
            } catch (e: Throwable) {
                smoothingDead = true
                onSmoothingUnavailable()
            } finally {
                detectorStarting = false
            }
        }
    }

    private fun closeDetector() {
        val d = detector
        detector = null
        if (d != null) runCatching { d.close() }
    }

    // --- GPU resources ------------------------------------------------------------------------------------------------------------------

    private fun ensureGl(
        width: Int,
        height: Int,
    ): GlResources {
        var res = gl
        if (res == null) {
            val canvas = newOffscreenCanvas(width, height)
            val contextOptions =
                obj<dynamic> {
                    antialias = false
                    alpha = false
                    depth = false
                    stencil = false
                }
            val context: dynamic = canvas.getContext("webgl2", contextOptions)
            if (context == null) throw IllegalStateException("WebGL2 is not available")
            res = GlResources(canvas, context)
            // Only a loss of the CURRENT context is a failure: releaseGl() itself loses the context on purpose, and by then `gl` is cleared.
            canvas.addEventListener("webglcontextlost", { _: dynamic -> if (gl?.canvas === canvas) fail() })
            res.light = program(context, LIGHT_SHADER)
            res.copy = program(context, COPY_SHADER)
            res.blur = program(context, BLUR_SHADER)
            res.mix = program(context, MIX_SHADER)
            res.srcTex = newTexture(context, 0, 0, linear = true)
            gl = res
        }
        if (res.width != width || res.height != height || res.divisor != blurDivisor) {
            res.canvas.width = width
            res.canvas.height = height
            val g = res.gl
            deleteTargets(res)
            res.smallW = max(1, width / blurDivisor)
            res.smallH = max(1, height / blurDivisor)
            res.texA = newTexture(g, width, height, linear = true)
            res.fboA = framebufferOf(g, res.texA)
            res.texH1 = newTexture(g, res.smallW, res.smallH, linear = true)
            res.fboH1 = framebufferOf(g, res.texH1)
            res.texH2 = newTexture(g, res.smallW, res.smallH, linear = true)
            res.fboH2 = framebufferOf(g, res.texH2)
            res.width = width
            res.height = height
            res.divisor = blurDivisor
        }
        return res
    }

    private fun program(
        g: dynamic,
        fragment: String,
    ): dynamic {
        val vs = shader(g, g.VERTEX_SHADER, VERTEX_SHADER)
        val fs = shader(g, g.FRAGMENT_SHADER, fragment)
        val p = g.createProgram()
        g.attachShader(p, vs)
        g.attachShader(p, fs)
        g.linkProgram(p)
        g.deleteShader(vs)
        g.deleteShader(fs)
        if (g.getProgramParameter(p, g.LINK_STATUS) != true) {
            g.deleteProgram(p)
            throw IllegalStateException("WebGL program did not link")
        }
        return p
    }

    private fun shader(
        g: dynamic,
        type: dynamic,
        source: String,
    ): dynamic {
        val s = g.createShader(type)
        g.shaderSource(s, source)
        g.compileShader(s)
        if (g.getShaderParameter(s, g.COMPILE_STATUS) != true) {
            g.deleteShader(s)
            throw IllegalStateException("WebGL shader did not compile")
        }
        return s
    }

    private fun newTexture(
        g: dynamic,
        width: Int,
        height: Int,
        linear: Boolean,
    ): dynamic {
        val t = g.createTexture()
        g.bindTexture(g.TEXTURE_2D, t)
        val filter = if (linear) g.LINEAR else g.NEAREST
        g.texParameteri(g.TEXTURE_2D, g.TEXTURE_MIN_FILTER, filter)
        g.texParameteri(g.TEXTURE_2D, g.TEXTURE_MAG_FILTER, filter)
        g.texParameteri(g.TEXTURE_2D, g.TEXTURE_WRAP_S, g.CLAMP_TO_EDGE)
        g.texParameteri(g.TEXTURE_2D, g.TEXTURE_WRAP_T, g.CLAMP_TO_EDGE)
        if (width > 0 && height > 0) {
            g.texImage2D(g.TEXTURE_2D, 0, g.RGBA, width, height, 0, g.RGBA, g.UNSIGNED_BYTE, null)
        }
        return t
    }

    private fun framebufferOf(
        g: dynamic,
        texture: dynamic,
    ): dynamic {
        val f = g.createFramebuffer()
        g.bindFramebuffer(g.FRAMEBUFFER, f)
        g.framebufferTexture2D(g.FRAMEBUFFER, g.COLOR_ATTACHMENT0, g.TEXTURE_2D, texture, 0)
        if (g.checkFramebufferStatus(g.FRAMEBUFFER) != g.FRAMEBUFFER_COMPLETE) throw IllegalStateException("WebGL framebuffer incomplete")
        g.bindFramebuffer(g.FRAMEBUFFER, null)
        return f
    }

    private fun deleteTargets(res: GlResources) {
        val g = res.gl
        for (t in listOf(res.texA, res.texH1, res.texH2)) if (t != null) runCatching { g.deleteTexture(t) }
        for (f in listOf(res.fboA, res.fboH1, res.fboH2)) if (f != null) runCatching { g.deleteFramebuffer(f) }
        res.texA = null
        res.texH1 = null
        res.texH2 = null
        res.fboA = null
        res.fboH1 = null
        res.fboH2 = null
    }

    private fun releaseGl() {
        val res = gl ?: return
        gl = null
        val g = res.gl
        runCatching {
            deleteTargets(res)
            if (res.srcTex != null) g.deleteTexture(res.srcTex)
            for (p in listOf(res.light, res.copy, res.blur, res.mix)) if (p != null) g.deleteProgram(p)
            g.getExtension("WEBGL_lose_context")?.loseContext()
        }
        runCatching {
            res.canvas.width = 0
            res.canvas.height = 0
        }
    }
}

/**
 * Cheap probe, no frame needed: the browser has `VideoFrame`, `OffscreenCanvas`, `TransformStream` and can create a WebGL2 context. The probe
 * context is released at once. (Firefox and Safari fail here -- the panel then says "nur Chromium".)
 */
internal fun cameraFilterStageSupportedInThisBrowser(): Boolean =
    runCatching {
        val apis: Boolean =
            js("typeof VideoFrame !== 'undefined' && typeof OffscreenCanvas !== 'undefined' && typeof TransformStream !== 'undefined'")
                .unsafeCast<Boolean>()
        if (!apis) return@runCatching false
        val canvas = newOffscreenCanvas(1, 1)
        val context: dynamic = canvas.getContext("webgl2")
        val available = context != null
        runCatching { context?.getExtension("WEBGL_lose_context")?.loseContext() }
        available
    }.getOrDefault(false)
