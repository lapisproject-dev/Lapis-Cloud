package network.lapis.cloud.client

import kotlinx.browser.document
import kotlinx.browser.window
import kotlinx.coroutines.await
import kotlinx.coroutines.withTimeoutOrNull
import network.lapis.cloud.shared.domain.VideoOverlayItemDto
import org.w3c.fetch.DEFAULT
import org.w3c.fetch.RequestCache
import org.w3c.fetch.RequestCredentials
import org.w3c.fetch.RequestInit
import org.w3c.fetch.SAME_ORIGIN
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.min
import kotlin.math.roundToInt

/*
 * V1.9.129 "Video-Overlays" -- the DRAWING STEP in the camera pipeline: the person's own camera frames get their overlays before they are sent.
 * Everything is local in the browser; the images are fetched same-origin, authenticated, as a BLOB and decoded to an `ImageBitmap` -- never
 * through an `<img>`, an object URL or `crossOrigin` (a canvas drawn with a cross-origin image is tainted and `new VideoFrame(canvas)` then
 * throws a `SecurityError`; `VideoOverlayImageSourceTripwireTest` forbids those APIs in this family of files).
 *
 * **Pipeline** (see docs/architecture/video-background-effects.adoc, "Overlay step"): the `@livekit/track-processors` transformer builds its
 * own output frame; [installTransformHook] puts [VideoOverlayCompositor.process] between that frame and `controller.enqueue`. Without a
 * scene the original frame is enqueued untouched (zero cost). With a scene the frame is drawn onto ONE reused 2D `OffscreenCanvas`, the
 * overlays are drawn over it in the order of [videoOverlayDrawPlan], and a new `VideoFrame` replaces the original.
 *
 * **Never freeze the video.** An exception inside a `TransformStream` transform would stop the stream. [VideoOverlayCompositor.process]
 * therefore catches EVERYTHING: on any failure the ORIGINAL frame is enqueued, the drawing step is suspended until the next scene and the
 * failure is reported once. Every `VideoFrame` is closed or enqueued exactly once.
 *
 * **Memory**: at most one bitmap per element (four corners, the frame, the name pills -- all inside the same set), every superseded bitmap is
 * `close()`d at once, nothing is decoded per frame. Logging: only counts and exception classes, never a path, a name or a token.
 */

/** `console.warn` with a fixed sentence and an exception CLASS name only -- never a path, a name, a token or a message text. */
private fun overlayLog(line: String) {
    runCatching { console.warn(line) }
}

/** A decoded image in the form the drawing step needs. [raw] is the `ImageBitmap`. */
internal interface OverlayBitmap {
    val width: Int
    val height: Int

    fun close()
}

/** One camera frame. [raw] is the `VideoFrame`; `close()` must be called exactly once unless the frame is enqueued. */
internal interface OverlayFrame {
    val width: Int
    val height: Int
    val timestamp: Double

    fun close()
}

/** The 2D surface the compositor draws on (production: one reused `OffscreenCanvas`). */
internal interface OverlayDrawSurface {
    fun begin(
        width: Int,
        height: Int,
    )

    fun drawFrame(frame: OverlayFrame)

    fun drawBitmap(
        bitmap: OverlayBitmap,
        rect: OverlayRect,
        alpha: Double,
    )

    /** The drawn result as a new frame (the surface keeps no reference to it). */
    fun toFrame(timestamp: Double): OverlayFrame
}

/** Test seam: how bitmaps come into being (production: [BrowserOverlayBitmapLoader]). Both throw on failure. */
internal interface OverlayBitmapLoader {
    /** [targetWidth]/[targetHeight] `0` = natural size; otherwise the bitmap is decoded at (at most) that size, never upscaled. */
    suspend fun loadImage(
        path: String,
        targetWidth: Int,
        targetHeight: Int,
    ): OverlayBitmap

    /** The name pill of [text] for a box of [boxHeightPx] in a frame [frameWidth] wide, or `null` for a blank name. */
    suspend fun renderName(
        text: String,
        boxHeightPx: Double,
        frameWidth: Int,
    ): OverlayBitmap?
}

/** Identifies one decoded bitmap: what it shows and at which size. */
internal data class OverlayBitmapKey(
    val source: String,
    val width: Int,
    val height: Int,
)

/** One element of a prepared scene: its bitmap (owned by the [PreparedOverlayScene]) and where it goes. */
internal class PreparedOverlayOp(
    val kind: Kind,
    val corner: OverlayCorner?,
    val bitmap: OverlayBitmap,
    val rect: OverlayRect,
    val alpha: Double,
    val size: OverlaySize?,
) {
    enum class Kind { FRAME, CORNER }
}

/**
 * A scene whose bitmaps are all loaded, prepared for a [frameWidth] x [frameHeight] camera image. Swapped into the drawing step atomically
 * ([VideoOverlayCompositor.setScene]); owns its [bitmaps].
 */
internal class PreparedOverlayScene(
    val frameWidth: Int,
    val frameHeight: Int,
    val ops: List<PreparedOverlayOp>,
    val bitmaps: Map<OverlayBitmapKey, OverlayBitmap>,
    /** How many elements could not be loaded (they are simply left out). */
    val failedElements: Int,
)

internal class VideoOverlayCompositor(
    private val loader: OverlayBitmapLoader,
    private val surface: OverlayDrawSurface,
    /** Called at most once per [setScene] when drawing failed (the original frame is passed on from then on). */
    private val onDrawFailure: () -> Unit = {},
    /** Called once per newly seen camera size that differs from the prepared one: the owner should [prepare] again. */
    private val onFrameSizeMismatch: (Int, Int) -> Unit = { _, _ -> },
) {
    private var scene: PreparedOverlayScene? = null
    private var suspended = false
    private var closed = false
    private var lastReportedMismatch: Pair<Int, Int>? = null

    /** `true` while there is something to draw (the processor kind decision looks at this). */
    val hasScene: Boolean get() = scene != null

    /** `true` while the scene really reaches the video: there is one, and no draw failure suspended it (nor [close]). */
    val isDrawing: Boolean
        get() {
            val current = scene
            if (current == null || suspended || closed) return false
            // A scene of a frame only is not drawn on a camera that is not 16:9 (see [videoOverlayFrameFits]): nothing reaches the video.
            val hasCornerElement = current.ops.any { it.kind != PreparedOverlayOp.Kind.FRAME }
            return hasCornerElement || videoOverlayFrameFits(current.frameWidth, current.frameHeight)
        }

    val preparedWidth: Int? get() = scene?.frameWidth
    val preparedHeight: Int? get() = scene?.frameHeight

    /**
     * Loads every bitmap [scene] needs for a [frameWidth] x [frameHeight] camera image. A bitmap that is already in [current] under the
     * same key is REUSED (no second download, no second decode). An element that fails to load is left out and counted, the others stay.
     * The result is not drawn until [setScene].
     */
    suspend fun prepare(
        scene: VideoOverlayScene,
        frameWidth: Int,
        frameHeight: Int,
        current: PreparedOverlayScene? = this.scene,
    ): PreparedOverlayScene {
        val short = min(frameWidth, frameHeight).toDouble()
        val keep = mutableMapOf<OverlayBitmapKey, OverlayBitmap>()
        var failed = 0

        suspend fun image(
            item: VideoOverlayItemDto,
            targetWidth: Int,
            targetHeight: Int,
        ): OverlayBitmap? {
            val path = videoOverlayAssetPath(item) ?: return null
            val key = OverlayBitmapKey(source = path, width = targetWidth, height = targetHeight)
            (keep[key] ?: current?.bitmaps?.get(key))?.let {
                keep[key] = it
                return it
            }
            return try {
                loader.loadImage(path = path, targetWidth = targetWidth, targetHeight = targetHeight).also { keep[key] = it }
            } catch (e: Throwable) {
                overlayLog("video overlay: an image could not be loaded (${e::class.simpleName})")
                null
            }
        }

        suspend fun name(
            text: String,
            boxHeight: Double,
        ): OverlayBitmap? {
            val key = OverlayBitmapKey(source = "name:$text", width = boxHeight.roundToInt(), height = frameWidth)
            (keep[key] ?: current?.bitmaps?.get(key))?.let {
                keep[key] = it
                return it
            }
            return try {
                loader.renderName(text = text, boxHeightPx = boxHeight, frameWidth = frameWidth)?.also { keep[key] = it }
            } catch (e: Throwable) {
                overlayLog("video overlay: a name could not be rendered (${e::class.simpleName})")
                null
            }
        }

        // Step 1: the aspect ratio of every corner element (a pill is measured by rendering it at its nominal height).
        val nominal = mutableMapOf<OverlayCorner, OverlayBitmap>()
        val geometry = mutableMapOf<OverlayCorner, OverlayItemGeometry>()
        for ((corner, element) in scene.corners) {
            val aspect: Double? =
                when (element) {
                    is SceneCorner.Name -> {
                        val pill = name(text = element.text, boxHeight = element.size.heightFraction * short)
                        pill?.also { nominal[corner] = it }?.let { it.width.toDouble() / it.height }
                    }
                    is SceneCorner.Image ->
                        if (element.item.width > 0 && element.item.height > 0) {
                            element.item.width.toDouble() / element.item.height
                        } else {
                            // Unknown size (the instance logo as WebP): decode it once at natural size to learn the shape.
                            image(item = element.item, targetWidth = 0, targetHeight = 0)?.also { nominal[corner] = it }?.let {
                                it.width.toDouble() /
                                    it.height
                            }
                        }
                }
            if (aspect == null) failed++ else geometry[corner] = OverlayItemGeometry(size = element.size, aspect = aspect)
        }

        // Step 2: where everything goes, then the bitmaps at (at most) that size.
        val rects = videoOverlayCornerRects(frameWidth = frameWidth, frameHeight = frameHeight, items = geometry)
        val ops = mutableListOf<PreparedOverlayOp>()
        scene.frame?.let { frame ->
            // Aspect-preserving decode (a frame is 16:9 by contract, the camera may not be: the stretch is never drawn, see
            // [videoOverlayFrameFits], so the bitmap must not be distorted by the decode either).
            val itemWidth = frame.item.width.takeIf { it > 0 } ?: frameWidth
            val itemHeight = frame.item.height.takeIf { it > 0 } ?: frameHeight
            val scale = min(1.0, min(frameWidth.toDouble() / itemWidth, frameHeight.toDouble() / itemHeight))
            val targetWidth = (itemWidth * scale).roundToInt().coerceAtLeast(1)
            val targetHeight = (itemHeight * scale).roundToInt().coerceAtLeast(1)
            val bitmap = image(item = frame.item, targetWidth = targetWidth, targetHeight = targetHeight)
            if (bitmap == null) {
                failed++
            } else {
                ops +=
                    PreparedOverlayOp(
                        PreparedOverlayOp.Kind.FRAME,
                        null,
                        bitmap,
                        OverlayRect(0, 0, frameWidth, frameHeight),
                        frame.opacity.alpha,
                        null,
                    )
            }
        }
        for (corner in OverlayCorner.entries) {
            val element = scene.corners[corner] ?: continue
            val rect = rects[corner] ?: continue
            val bitmap: OverlayBitmap? =
                when (element) {
                    is SceneCorner.Name -> nominal[corner]
                    is SceneCorner.Image -> {
                        val natural = nominal[corner]
                        val targetWidth = min(rect.width, element.item.width.takeIf { it > 0 } ?: Int.MAX_VALUE)
                        val targetHeight = min(rect.height, element.item.height.takeIf { it > 0 } ?: Int.MAX_VALUE)
                        if (natural != null && natural.width <= targetWidth) {
                            natural
                        } else {
                            image(item = element.item, targetWidth = targetWidth, targetHeight = targetHeight) ?: natural
                        }
                    }
                }
            if (bitmap == null) {
                failed++
            } else {
                ops += PreparedOverlayOp(PreparedOverlayOp.Kind.CORNER, corner, bitmap, rect, element.opacity.alpha, element.size)
            }
        }

        // Step 3: a natural-size probe that was replaced by a resized bitmap (and is not shared with the running scene) must not leak.
        val used = ops.map { it.bitmap }.toSet()
        for ((key, bitmap) in keep.entries.toList()) {
            if (bitmap in used) continue
            if (current?.bitmaps?.get(key) !== bitmap) runCatching { bitmap.close() }
            keep.remove(key)
        }
        return PreparedOverlayScene(frameWidth = frameWidth, frameHeight = frameHeight, ops = ops, bitmaps = keep, failedElements = failed)
    }

    /**
     * Swaps the drawing step to [prepared] (or to "nothing" for `null`) atomically and closes every bitmap of the previous scene that the
     * new one does not keep. Re-arms the step after a draw failure.
     */
    fun setScene(prepared: PreparedOverlayScene?) {
        val previous = scene
        scene = if (closed) null else prepared
        suspended = false
        lastReportedMismatch = null
        if (closed && prepared != null) prepared.bitmaps.values.forEach { runCatching { it.close() } }
        val kept =
            scene
                ?.bitmaps
                ?.values
                ?.toSet()
                .orEmpty()
        previous
            ?.bitmaps
            ?.values
            ?.filter { it !in kept }
            ?.forEach { runCatching { it.close() } }
    }

    /**
     * Closes the bitmaps of a [prepared] scene that was never handed to [setScene] (overtaken by a newer request, or a throw-away preview),
     * except those the running scene still shows.
     */
    fun discard(prepared: PreparedOverlayScene) {
        val running =
            scene
                ?.bitmaps
                ?.values
                ?.toSet()
                .orEmpty()
        prepared.bitmaps.values
            .filter { it !in running }
            .forEach { runCatching { it.close() } }
    }

    /**
     * The core of the transform hook. Takes ownership of [frame]: it is either closed after the overlays were drawn (and the NEW frame is
     * passed to [enqueue]) or passed on untouched. Never throws.
     */
    fun process(
        frame: OverlayFrame,
        enqueue: (OverlayFrame) -> Unit,
    ) {
        val current = scene
        if (current == null || suspended || closed) {
            enqueue(frame)
            return
        }
        var drawn: OverlayFrame? = null
        var originalClosed = false
        try {
            val width = frame.width
            val height = frame.height
            surface.begin(width = width, height = height)
            surface.drawFrame(frame)
            val matches = width == current.frameWidth && height == current.frameHeight
            val frameFits = videoOverlayFrameFits(width, height)
            for (op in if (matches) current.ops else rescaled(current, width, height)) {
                // A frame is a 16:9 picture: on any other camera shape it would be stretched, so it is not drawn there.
                if (op.kind == PreparedOverlayOp.Kind.FRAME && !frameFits) continue
                surface.drawBitmap(bitmap = op.bitmap, rect = op.rect, alpha = op.alpha)
            }
            if (!matches) reportMismatch(width, height)
            drawn = surface.toFrame(frame.timestamp)
            frame.close()
            originalClosed = true
            enqueue(drawn)
        } catch (e: Throwable) {
            overlayLog("video overlay: drawing failed (${e::class.simpleName}) -- the original frames pass on")
            suspended = true
            if (!originalClosed) {
                runCatching { enqueue(frame) }
            } else {
                runCatching { drawn?.close() }
            }
            runCatching { onDrawFailure() }
        }
    }

    private fun rescaled(
        prepared: PreparedOverlayScene,
        width: Int,
        height: Int,
    ): List<PreparedOverlayOp> {
        val corners = prepared.ops.filter { it.kind == PreparedOverlayOp.Kind.CORNER }
        val geometry =
            corners.associate {
                it.corner!! to
                    OverlayItemGeometry(size = it.size!!, aspect = it.bitmap.width.toDouble() / it.bitmap.height)
            }
        val rects = videoOverlayCornerRects(frameWidth = width, frameHeight = height, items = geometry)
        return prepared.ops.map { op ->
            when (op.kind) {
                PreparedOverlayOp.Kind.FRAME ->
                    PreparedOverlayOp(
                        op.kind,
                        null,
                        op.bitmap,
                        OverlayRect(0, 0, width, height),
                        op.alpha,
                        null,
                    )
                PreparedOverlayOp.Kind.CORNER ->
                    PreparedOverlayOp(
                        op.kind,
                        op.corner,
                        op.bitmap,
                        rects[op.corner] ?: op.rect,
                        op.alpha,
                        op.size,
                    )
            }
        }
    }

    private fun reportMismatch(
        width: Int,
        height: Int,
    ) {
        if (lastReportedMismatch == width to height) return
        lastReportedMismatch = width to height
        runCatching { onFrameSizeMismatch(width, height) }
    }

    /** Ends the drawing step for good and closes every bitmap. Idempotent. */
    fun close() {
        if (closed) return
        closed = true
        setScene(null)
    }
}

/** A colour of the theme (`theme.css`), read once per rendered pill; [fallback] is a plain colour name. */
internal fun overlayCssColor(
    variable: String,
    fallback: String,
): String =
    runCatching {
        window
            .getComputedStyle(document.documentElement!!)
            .getPropertyValue(variable)
            .trim()
            .ifEmpty { fallback }
    }.getOrDefault(fallback)

// ── Production implementations ────────────────────────────────────────────────────────

internal class JsOverlayBitmap(
    val raw: dynamic,
) : OverlayBitmap {
    override val width: Int get() = (raw.width as Number).toInt()
    override val height: Int get() = (raw.height as Number).toInt()

    override fun close() {
        runCatching { raw.close() }
    }
}

internal class JsOverlayFrame(
    val raw: dynamic,
) : OverlayFrame {
    override val width: Int get() = ((raw.displayWidth ?: raw.codedWidth) as Number).toInt()
    override val height: Int get() = ((raw.displayHeight ?: raw.codedHeight) as Number).toInt()
    override val timestamp: Double get() = (raw.timestamp as Number).toDouble()

    override fun close() {
        raw.close()
    }
}

/** One reused 2D `OffscreenCanvas`; resized only when the camera size changes. */
internal class CanvasOverlaySurface : OverlayDrawSurface {
    private var canvas: dynamic = null
    private var context: dynamic = null

    override fun begin(
        width: Int,
        height: Int,
    ) {
        if (canvas == null) {
            canvas = newOffscreenCanvas(width, height)
            val options = js("({ alpha: false, desynchronized: true })")
            context = canvas.getContext("2d", options)
        }
        if ((canvas.width as Number).toInt() != width || (canvas.height as Number).toInt() != height) {
            canvas.width = width
            canvas.height = height
        }
        context.imageSmoothingEnabled = true
        context.imageSmoothingQuality = "high"
        context.globalAlpha = 1.0
    }

    override fun drawFrame(frame: OverlayFrame) {
        context.globalAlpha = 1.0
        context.drawImage((frame as JsOverlayFrame).raw, 0, 0, canvas.width, canvas.height)
    }

    override fun drawBitmap(
        bitmap: OverlayBitmap,
        rect: OverlayRect,
        alpha: Double,
    ) {
        context.globalAlpha = alpha
        context.drawImage((bitmap as JsOverlayBitmap).raw, rect.x, rect.y, rect.width, rect.height)
        context.globalAlpha = 1.0
    }

    override fun toFrame(timestamp: Double): OverlayFrame = JsOverlayFrame(newVideoFrameFrom(canvas, timestamp))
}

private const val IMAGE_LOAD_TIMEOUT_MS = 10_000L
private const val FONT_LOAD_TIMEOUT_MS = 1_500L
private val ALLOWED_IMAGE_TYPES = listOf("image/png", "image/jpeg", "image/webp")

/**
 * Loads the image bytes same-origin and authenticated (`credentials: same-origin`), checks the status and the content type, decodes the
 * BLOB to an `ImageBitmap`. No `<img>`, no `crossOrigin`, no object URL: the canvas must never become tainted.
 */
internal class BrowserOverlayBitmapLoader : OverlayBitmapLoader {
    override suspend fun loadImage(
        path: String,
        targetWidth: Int,
        targetHeight: Int,
    ): OverlayBitmap {
        val response =
            withTimeoutOrNull(IMAGE_LOAD_TIMEOUT_MS) {
                window.fetch(path, RequestInit(credentials = RequestCredentials.SAME_ORIGIN, cache = RequestCache.DEFAULT)).await()
            } ?: error("video overlay image: timeout")
        check(response.ok) { "video overlay image: status ${response.status}" }
        val type =
            response.headers
                .get("Content-Type")
                ?.substringBefore(';')
                ?.trim()
                ?.lowercase()
        check(type in ALLOWED_IMAGE_TYPES) { "video overlay image: unexpected content type" }
        val blob = response.blob().await()
        val bitmap =
            createImageBitmapOf(
                source = blob,
                resizeWidth = targetWidth,
                resizeHeight = targetHeight,
            ).unsafeCast<kotlin.js.Promise<dynamic>>().await()
        return JsOverlayBitmap(bitmap)
    }

    override suspend fun renderName(
        text: String,
        boxHeightPx: Double,
        frameWidth: Int,
    ): OverlayBitmap? {
        val metrics = namePillMetrics(boxHeightPx = boxHeightPx, frameWidthPx = frameWidth)
        val fontSpec = "600 ${metrics.fontPx}px 'Inter Variable', sans-serif"
        // Inter is only reliably available in the canvas AFTER it was loaded; otherwise the pill would be measured with the fallback font.
        withTimeoutOrNull(FONT_LOAD_TIMEOUT_MS) {
            runCatching {
                document
                    .asDynamic()
                    .fonts
                    .load(fontSpec)
                    .unsafeCast<kotlin.js.Promise<dynamic>>()
                    .await()
            }
        }
        val measuring = newOffscreenCanvas(1, 1).getContext("2d")
        measuring.font = fontSpec
        val shown =
            truncateDisplayName(text, metrics.maxTextWidthPx, { (measuring.measureText(it).width as Number).toDouble() }, ::splitGraphemes)
                ?: return null
        val textWidth = (measuring.measureText(shown).width as Number).toDouble()
        val width = ceil(textWidth + 2 * metrics.padPx).toInt().coerceAtLeast(1)
        val height = ceil(boxHeightPx).toInt().coerceAtLeast(1)
        val canvas = newOffscreenCanvas(width, height)
        val ctx = canvas.getContext("2d")
        val radius = height / 2.0
        ctx.beginPath()
        ctx.moveTo(radius, 0)
        ctx.lineTo(width - radius, 0)
        ctx.arc(width - radius, radius, radius, -kotlin.math.PI / 2, kotlin.math.PI / 2)
        ctx.lineTo(radius, height)
        ctx.arc(radius, radius, radius, kotlin.math.PI / 2, 3 * kotlin.math.PI / 2)
        ctx.closePath()
        ctx.fillStyle = overlayCssColor("--lapis-overlay-pill-bg", "black")
        ctx.globalAlpha = 0.55
        ctx.fill()
        ctx.globalAlpha = 1.0
        ctx.font = fontSpec
        ctx.textBaseline = "middle"
        ctx.fillStyle = overlayCssColor("--lapis-overlay-pill-text", "white")
        ctx.shadowColor = overlayCssColor("--lapis-overlay-pill-shadow", "black")
        ctx.shadowBlur = 2
        ctx.fillText(shown, metrics.padPx, height / 2.0 + 1)
        return JsOverlayBitmap(canvas.transferToImageBitmap())
    }
}

/**
 * The hook of one transformer: every frame the transformer wants to enqueue goes through [compositor]. Installed on the segmenting
 * processor BEFORE `setProcessor` (which initialises it) and used by the overlay-only transformer.
 */
internal fun overlayFrameProcessor(compositor: VideoOverlayCompositor): (dynamic, dynamic) -> Unit =
    { rawFrame, enqueue ->
        compositor.process(frame = JsOverlayFrame(rawFrame)) { out ->
            enqueue(if (out is JsOverlayFrame) out.raw else out)
        }
    }

/** Draws the ops of a [prepared] scene (not the camera picture) onto a 2D context; used by the panel's static preview. Never throws. */
internal fun drawPreparedSceneOnto(
    context: dynamic,
    prepared: PreparedOverlayScene,
) {
    runCatching {
        for (op in prepared.ops) {
            val bitmap = op.bitmap as? JsOverlayBitmap ?: continue
            context.globalAlpha = op.alpha
            context.drawImage(bitmap.raw, op.rect.x, op.rect.y, op.rect.width, op.rect.height)
        }
        context.globalAlpha = 1.0
    }
}

/** The aspect ratio a frame (border) picture is designed for; the server only accepts 16:9 frames. */
private const val OVERLAY_FRAME_ASPECT = 16.0 / 9.0

/** Relative tolerance around 16:9 (1366x768 is 0.1 % off, 1280x800 is 10 % off). */
private const val OVERLAY_FRAME_ASPECT_TOLERANCE = 0.02

/**
 * Whether a frame picture may be drawn over a camera image of [width] x [height] without distortion: only on (almost) 16:9. Portrait phones
 * (Android Chrome) and 4:3 webcams therefore show corner elements only -- documented under "Limits" in `docs/features/video-overlays.adoc`.
 */
internal fun videoOverlayFrameFits(
    width: Int,
    height: Int,
): Boolean {
    if (width <= 0 || height <= 0) return false
    return abs(width.toDouble() / height / OVERLAY_FRAME_ASPECT - 1.0) <= OVERLAY_FRAME_ASPECT_TOLERANCE
}
