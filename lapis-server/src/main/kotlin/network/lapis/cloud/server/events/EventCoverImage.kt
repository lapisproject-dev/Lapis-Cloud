package network.lapis.cloud.server.events

import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.http.ContentType
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageWriteParam
import javax.imageio.stream.MemoryCacheImageInputStream

private val logger = KotlinLogging.logger {}

/**
 * Welle "Veranstaltungs-Titelbild" (Event Cover Image) -- the two container formats this feature
 * accepts. Deliberately NOT `image/svg+xml` (an XSS vector, same reasoning
 * [network.lapis.cloud.server.routes.TravelExpenseReceiptRoutes] class KDoc gives for its own MIME
 * allowlist) and deliberately NOT `image/gif`/`image/webp` -- no decoder for either is exercised
 * anywhere else in this codebase, and this wave's scope is intentionally narrow (see the wave
 * plan's "Nicht in dieser Welle" section).
 */
enum class CoverImageFormat(
    val extension: String,
    val contentType: ContentType,
    internal val imageIoFormatName: String,
) {
    JPEG("jpg", ContentType.Image.JPEG, "jpeg"),
    PNG("png", ContentType.Image.PNG, "png"),
}

/**
 * Welle V1.9.20 "Öffentliche Seiten" -- the size thresholds [EventCoverImageProcessor.process]
 * applies: the minimum edges below which an image is rejected and the long edge it is downscaled
 * to. Everything ELSE of the pipeline (sniffing, header-dimension and decompression-bomb guards, the
 * decode-with-subsampling, EXIF orientation, the fresh metadata-free re-encode) is identical for
 * every caller. [EVENT_COVER] is the default and reproduces the pre-V1.9.20 behaviour exactly;
 * [CHAPTER_CREST] admits small logos (a crest is legitimately tiny) and stores up to 1024 px.
 */
internal data class CoverImageLimits(
    val minLongEdgePx: Int,
    val minShortEdgePx: Int,
    val targetLongEdgePx: Int,
) {
    companion object {
        val EVENT_COVER =
            CoverImageLimits(
                minLongEdgePx = EventCoverPolicy.MIN_LONG_EDGE_PX,
                minShortEdgePx = EventCoverPolicy.MIN_SHORT_EDGE_PX,
                targetLongEdgePx = EventCoverPolicy.TARGET_LONG_EDGE_PX,
            )
        val CHAPTER_CREST = CoverImageLimits(minLongEdgePx = 64, minShortEdgePx = 64, targetLongEdgePx = 1024)
    }
}

/**
 * Outcome of [EventCoverImageProcessor.process] -- a typed result, never an exception, for every
 * FACHLICH-expected outcome (same "typed result over exception for expected states" posture
 * `network.lapis.cloud.shared.domain.EventCheckInOutcome` KDoc documents), because the route caller
 * turns each of these into a specific, calm 422 message rather than a generic 500.
 */
sealed interface CoverProcessingResult {
    data class Ok(
        val bytes: ByteArray,
        val format: CoverImageFormat,
        val width: Int,
        val height: Int,
    ) : CoverProcessingResult

    /** The source image's declared dimensions exceed [EventCoverPolicy.MAX_EDGE_PX]/[EventCoverPolicy.MAX_PIXELS] -- rejected BEFORE a single pixel is decoded (decompression-bomb guard). */
    data object DimensionsTooLarge : CoverProcessingResult

    /** `max(w,h) < MIN_LONG_EDGE_PX || min(w,h) < MIN_SHORT_EDGE_PX` -- see `EventCoverPolicy` KDoc "F4", orientation-independent. */
    data object DimensionsTooSmall : CoverProcessingResult

    /** No `ImageReader` accepted the bytes, or decoding threw (truncated file, CMYK JPEG ImageIO's bundled decoder cannot read, ...). */
    data object Undecodable : CoverProcessingResult
}

/**
 * Welle "Veranstaltungs-Titelbild" (Event Cover Image) -- headless, server-side image
 * sniffing/validation/downscaling/re-encoding. Same headless-AWT discipline
 * [network.lapis.cloud.server.conference.WhiteboardRasterizer] class KDoc documents (`java.awt
 * .headless=true`), though for the opposite reason: THAT class only ever DRAWS vector shapes and
 * never touches a decoder; this one exclusively DECODES untrusted, attacker-controlled bytes, which
 * is the actually risky direction for a headless-container image stack.
 *
 * **Pipeline (mirrors the wave plan's "Pflicht-Reihenfolge" exactly):**
 * 1. Select the [javax.imageio.ImageReader] by the SNIFFED format name (never ImageIO's own
 *    auto-detection) and read the header dimensions via [javax.imageio.ImageReader.getWidth]/
 *    [javax.imageio.ImageReader.getHeight] -- BEFORE `read(0, ...)` is ever called.
 * 2. Reject on [EventCoverPolicy.MAX_EDGE_PX]/[EventCoverPolicy.MAX_PIXELS] (decompression-bomb
 *    guard) or on [EventCoverPolicy.MIN_LONG_EDGE_PX]/[EventCoverPolicy.MIN_SHORT_EDGE_PX] --
 *    neither ever decodes a pixel.
 * 3. Subsample while decoding (`ImageReadParam.setSourceSubsampling`) so the in-memory decode
 *    buffer is never much larger than the eventual [EventCoverPolicy.TARGET_LONG_EDGE_PX] target --
 *    without this, a 40-megapixel PNG can require on the order of 150-160 MB of heap just to
 *    decode, a real risk given this Dockerfile's own recent OOM history (`27b177c4`).
 * 4. Halve repeatedly with bilinear interpolation down to the target long edge, proportionally,
 *    NEVER cropped.
 * 5. Apply the EXIF orientation tag (JPEG only) BEFORE the final re-encode, so a portrait phone
 *    photo does not come out rotated 90 degrees once EXIF is stripped.
 * 6. Draw into a FRESH [BufferedImage] (`TYPE_INT_RGB` for JPEG -- the bundled JPEG writer cannot
 *    encode an alpha channel; `TYPE_INT_ARGB` for PNG) -- this step alone guarantees every stored
 *    cover image carries NO metadata (EXIF/GPS/XMP/ICC all gone) and neutralizes any polyglot
 *    payload (a byte sequence that is simultaneously a valid JPEG and, say, valid HTML/ZIP): only
 *    the decoded PIXELS survive into the new image, nothing else from the original byte stream.
 * 7. Re-encode: JPEG via an explicit-quality `ImageWriter`, PNG via `ImageIO.write`.
 *
 * [concurrency] bounds how many decodes run at once process-wide -- a cheap, coarse defense against
 * several large uploads landing at the same instant and spiking heap together (the per-request
 * subsampling above already bounds any SINGLE decode's peak memory).
 */
internal object EventCoverImageProcessor {
    init {
        System.setProperty("java.awt.headless", "true")
        ImageIO.setUseCache(false)
    }

    private val concurrency = Semaphore(2)

    /**
     * Process-wide decode budget shared by every image pipeline (event/article covers and member
     * photos) so several simultaneous large uploads cannot spike the heap together.
     */
    internal suspend fun <T> withDecodePermit(block: suspend () -> T): T = concurrency.withPermit { block() }

    /** Like [withDecodePermit], but gives up after [waitMillis] without running [block] and returns `null` ("server busy"). */
    internal suspend fun <T : Any> withDecodePermitOrNull(
        waitMillis: Long,
        block: suspend () -> T,
    ): T? {
        val acquired = kotlinx.coroutines.withTimeoutOrNull(waitMillis) { concurrency.acquire() } != null
        if (!acquired) return null
        try {
            return block()
        } finally {
            concurrency.release()
        }
    }

    /** Explicit-quality JPEG encode of [image] WITHOUT any metadata (`IIOImage(img, null, null)`). */
    internal fun encodeJpeg(
        image: BufferedImage,
        quality: Float,
    ): ByteArray {
        val out = ByteArrayOutputStream()
        val writer = ImageIO.getImageWritersByFormatName("jpeg").next()
        try {
            val ios = javax.imageio.stream.MemoryCacheImageOutputStream(out)
            writer.output = ios
            val param =
                writer.defaultWriteParam.apply {
                    compressionMode = ImageWriteParam.MODE_EXPLICIT
                    compressionQuality = quality
                }
            writer.write(null, IIOImage(image, null, null), param)
            ios.flush()
        } finally {
            writer.dispose()
        }
        return out.toByteArray()
    }

    /** Magic-byte sniff, same posture as `TravelExpenseReceiptRoutes.sniffMimeType` -- the client-declared `Content-Type` is never consulted anywhere in this pipeline. */
    fun sniff(head: ByteArray): CoverImageFormat? =
        when {
            head.size >= 3 && head[0] == 0xFF.toByte() && head[1] == 0xD8.toByte() && head[2] == 0xFF.toByte() -> CoverImageFormat.JPEG
            head.size >= 8 &&
                head[0] == 0x89.toByte() &&
                head[1] == 0x50.toByte() &&
                head[2] == 0x4E.toByte() &&
                head[3] == 0x47.toByte() &&
                head[4] == 0x0D.toByte() &&
                head[5] == 0x0A.toByte() &&
                head[6] == 0x1A.toByte() &&
                head[7] == 0x0A.toByte()
            -> CoverImageFormat.PNG
            else -> null
        }

    suspend fun process(
        bytes: ByteArray,
        format: CoverImageFormat,
        limits: CoverImageLimits = CoverImageLimits.EVENT_COVER,
    ): CoverProcessingResult =
        concurrency.withPermit {
            val readers = ImageIO.getImageReadersByFormatName(format.imageIoFormatName)
            if (!readers.hasNext()) return@withPermit CoverProcessingResult.Undecodable
            val reader = readers.next()
            try {
                val iis = MemoryCacheImageInputStream(ByteArrayInputStream(bytes))
                reader.setInput(iis, true, true)

                val width: Int
                val height: Int
                try {
                    width = reader.getWidth(0)
                    height = reader.getHeight(0)
                } catch (e: Exception) {
                    logger.info { "Event cover image header unreadable: ${e.message}" }
                    return@withPermit CoverProcessingResult.Undecodable
                }

                if (width <= 0 || height <= 0) return@withPermit CoverProcessingResult.Undecodable
                val longEdge = maxOf(width, height)
                val shortEdge = minOf(width, height)
                if (longEdge > EventCoverPolicy.MAX_EDGE_PX || width.toLong() * height.toLong() > EventCoverPolicy.MAX_PIXELS) {
                    return@withPermit CoverProcessingResult.DimensionsTooLarge
                }
                if (longEdge < limits.minLongEdgePx || shortEdge < limits.minShortEdgePx) {
                    return@withPermit CoverProcessingResult.DimensionsTooSmall
                }

                val subsample = (longEdge / (2 * limits.targetLongEdgePx)).coerceAtLeast(1)
                val param = reader.defaultReadParam
                if (subsample > 1) param.setSourceSubsampling(subsample, subsample, 0, 0)

                val decoded =
                    try {
                        reader.read(0, param)
                    } catch (e: Exception) {
                        logger.info { "Event cover image undecodable: ${e.message}" }
                        return@withPermit CoverProcessingResult.Undecodable
                    }

                val orientation = if (format == CoverImageFormat.JPEG) readExifOrientation(bytes) else 1
                val oriented = applyOrientation(image = decoded, orientation = orientation)
                val resized = downscaleToTarget(source = oriented, targetLongEdge = limits.targetLongEdgePx)

                val flattened = flatten(source = resized, format = format)
                val encoded = encode(image = flattened, format = format)
                CoverProcessingResult.Ok(bytes = encoded, format = format, width = flattened.width, height = flattened.height)
            } catch (e: Exception) {
                logger.info { "Event cover image processing failed: ${e.message}" }
                CoverProcessingResult.Undecodable
            } finally {
                reader.dispose()
            }
        }

    /**
     * Halves repeatedly with bilinear interpolation WHILE the long edge is still more than double
     * [targetLongEdge] (each halving step stays a high-quality box-like downsample), then does one
     * final bilinear step to land EXACTLY on [targetLongEdge] -- never upscales, never crops.
     *
     * The naive "halve while > target" loop this replaced overshoots: since each step only ever
     * halves, it lands wherever a power-of-two division happens to fall (anywhere from just above
     * target down to just above target/2), never on the target edge itself. E.g. a 4032px source
     * with target=1600 would halve to 2016 then 1008 -- well under 1600 -- instead of the intended
     * 1600. Stopping the halving loop at `> 2 * targetLongEdge` and finishing with one exact resize
     * guarantees the output's long edge is always precisely [targetLongEdge] (unless the source was
     * already smaller, per the `<=` guard below).
     */
    internal fun downscaleToTarget(
        source: BufferedImage,
        targetLongEdge: Int,
    ): BufferedImage {
        var current = source
        while (maxOf(current.width, current.height) > 2 * targetLongEdge) {
            val nextLongEdge = maxOf(current.width, current.height) / 2
            val scale = nextLongEdge.toDouble() / maxOf(current.width, current.height)
            val newWidth = (current.width * scale).toInt().coerceAtLeast(1)
            val newHeight = (current.height * scale).toInt().coerceAtLeast(1)
            current = bilinearResize(source = current, newWidth = newWidth, newHeight = newHeight)
        }
        if (maxOf(current.width, current.height) <= targetLongEdge) return current
        val scale = targetLongEdge.toDouble() / maxOf(current.width, current.height)
        val newWidth = (current.width * scale).toInt().coerceAtLeast(1)
        val newHeight = (current.height * scale).toInt().coerceAtLeast(1)
        return bilinearResize(source = current, newWidth = newWidth, newHeight = newHeight)
    }

    internal fun bilinearResize(
        source: BufferedImage,
        newWidth: Int,
        newHeight: Int,
    ): BufferedImage {
        val scaled = BufferedImage(newWidth, newHeight, BufferedImage.TYPE_INT_ARGB)
        val g = scaled.createGraphics()
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
            g.drawImage(source, 0, 0, newWidth, newHeight, null)
        } finally {
            g.dispose()
        }
        return scaled
    }

    /** Draws into a FRESH image with no residual metadata -- `TYPE_INT_RGB` for JPEG (no alpha support), `TYPE_INT_ARGB` for PNG (alpha preserved). See class KDoc step 6. */
    private fun flatten(
        source: BufferedImage,
        format: CoverImageFormat,
    ): BufferedImage {
        val type = if (format == CoverImageFormat.JPEG) BufferedImage.TYPE_INT_RGB else BufferedImage.TYPE_INT_ARGB
        val target = BufferedImage(source.width, source.height, type)
        val g: Graphics2D = target.createGraphics()
        try {
            if (format == CoverImageFormat.JPEG) {
                // No alpha channel in the target -- painting white first avoids a black background
                // wherever the source had transparency (should not normally occur for a JPEG source,
                // but harmless and cheap insurance).
                g.color = java.awt.Color.WHITE
                g.fillRect(0, 0, target.width, target.height)
            }
            g.drawImage(source, 0, 0, null)
        } finally {
            g.dispose()
        }
        return target
    }

    private fun encode(
        image: BufferedImage,
        format: CoverImageFormat,
    ): ByteArray {
        val out = ByteArrayOutputStream()
        if (format == CoverImageFormat.PNG) {
            ImageIO.write(image, "png", out)
            return out.toByteArray()
        }
        val writers = ImageIO.getImageWritersByFormatName("jpeg")
        val writer = writers.next()
        try {
            val ios = javax.imageio.stream.MemoryCacheImageOutputStream(out)
            writer.output = ios
            val param =
                writer.defaultWriteParam.apply {
                    compressionMode = ImageWriteParam.MODE_EXPLICIT
                    compressionQuality = EventCoverPolicy.JPEG_QUALITY
                }
            writer.write(null, IIOImage(image, null, null), param)
            ios.flush()
        } finally {
            writer.dispose()
        }
        return out.toByteArray()
    }

    internal fun applyOrientation(
        image: BufferedImage,
        orientation: Int,
    ): BufferedImage {
        if (orientation <= 1 || orientation > 8) return image
        val swapDims = orientation in 5..8
        val newWidth = if (swapDims) image.height else image.width
        val newHeight = if (swapDims) image.width else image.height
        val target = BufferedImage(newWidth, newHeight, BufferedImage.TYPE_INT_ARGB)
        val g = target.createGraphics()
        try {
            val transform = java.awt.geom.AffineTransform()
            when (orientation) {
                2 -> {
                    transform.translate(newWidth.toDouble(), 0.0)
                    transform.scale(-1.0, 1.0)
                }
                3 -> {
                    transform.translate(newWidth.toDouble(), newHeight.toDouble())
                    transform.rotate(Math.PI)
                }
                4 -> {
                    transform.translate(0.0, newHeight.toDouble())
                    transform.scale(1.0, -1.0)
                }
                5 -> {
                    transform.rotate(Math.PI / 2)
                    transform.scale(1.0, -1.0)
                }
                6 -> {
                    transform.translate(newWidth.toDouble(), 0.0)
                    transform.rotate(Math.PI / 2)
                }
                7 -> {
                    transform.translate(newWidth.toDouble(), newHeight.toDouble())
                    transform.rotate(Math.PI / 2)
                    transform.scale(-1.0, 1.0)
                }
                8 -> {
                    transform.translate(0.0, newHeight.toDouble())
                    transform.rotate(-Math.PI / 2)
                }
            }
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
            g.drawImage(image, transform, null)
        } finally {
            g.dispose()
        }
        return target
    }

    /**
     * Reads EXIF tag `0x0112` (Orientation) from a JPEG's APP1 segment, if present -- a small,
     * deliberately hand-rolled and heavily bounds-checked parser rather than a new dependency (see
     * wave plan "F5" for the `com.drewnoakes:metadata-extractor` alternative that was rejected).
     * NEVER throws -- any malformed/truncated/adversarial input falls through to the default `1`
     * ("normal", no rotation needed), the same as a JPEG that simply carries no EXIF data at all.
     * Package-`internal`, not `private`, so [EventCoverImageProcessorTest] can fuzz it directly.
     */
    internal fun readExifOrientation(jpeg: ByteArray): Int =
        runCatching {
            var pos = 2 // skip SOI (FF D8)
            while (pos + 4 <= jpeg.size) {
                if (jpeg[pos] != 0xFF.toByte()) return 1
                val marker = jpeg[pos + 1].toInt() and 0xFF
                if (marker == 0xD8 || marker == 0x01 || (marker in 0xD0..0xD7)) {
                    pos += 2
                    continue
                }
                if (marker == 0xDA) return 1 // Start of Scan -- no more markers to inspect
                val segmentLength = ((jpeg[pos + 2].toInt() and 0xFF) shl 8) or (jpeg[pos + 3].toInt() and 0xFF)
                if (segmentLength < 2 || pos + 2 + segmentLength > jpeg.size) return 1
                if (marker == 0xE1) {
                    val orientation = parseExifApp1(data = jpeg, start = pos + 4, end = pos + 2 + segmentLength)
                    if (orientation != null) return orientation
                }
                pos += 2 + segmentLength
            }
            1
        }.getOrDefault(1)

    /** Parses one APP1 segment's body (already bounds-checked by the caller) for the TIFF Orientation tag. Returns `null` if this segment is not an Exif APP1, or the tag is absent/out of range. */
    private fun parseExifApp1(
        data: ByteArray,
        start: Int,
        end: Int,
    ): Int? {
        if (end - start < 10) return null
        if (!(
                data[start] == 'E'.code.toByte() &&
                    data[start + 1] == 'x'.code.toByte() &&
                    data[start + 2] == 'i'.code.toByte() &&
                    data[start + 3] == 'f'.code.toByte()
            )
        ) {
            return null
        }
        val tiffStart = start + 6 // "Exif\0\0"
        if (tiffStart + 8 > end) return null
        val bigEndian =
            when {
                data[tiffStart] == 'M'.code.toByte() && data[tiffStart + 1] == 'M'.code.toByte() -> true
                data[tiffStart] == 'I'.code.toByte() && data[tiffStart + 1] == 'I'.code.toByte() -> false
                else -> return null
            }

        fun u16(offset: Int): Int {
            if (offset + 2 > end) throw IllegalStateException("truncated")
            val b0 = data[offset].toInt() and 0xFF
            val b1 = data[offset + 1].toInt() and 0xFF
            return if (bigEndian) (b0 shl 8) or b1 else (b1 shl 8) or b0
        }

        fun u32(offset: Int): Long {
            if (offset + 4 > end) throw IllegalStateException("truncated")
            val b0 = data[offset].toInt() and 0xFF
            val b1 = data[offset + 1].toInt() and 0xFF
            val b2 = data[offset + 2].toInt() and 0xFF
            val b3 = data[offset + 3].toInt() and 0xFF
            return if (bigEndian) {
                (b0.toLong() shl 24) or (b1.toLong() shl 16) or (b2.toLong() shl 8) or b3.toLong()
            } else {
                (b3.toLong() shl 24) or (b2.toLong() shl 16) or (b1.toLong() shl 8) or b0.toLong()
            }
        }
        return runCatching {
            val ifdOffset = tiffStart + u32(tiffStart + 4).toInt()
            if (ifdOffset < tiffStart + 8 || ifdOffset + 2 > end) return@runCatching null
            val entryCount = u16(ifdOffset)
            // Bounded loop -- entryCount is attacker-controlled (a raw 16-bit field), so cap it at a
            // sane maximum rather than trusting it: a real EXIF IFD0 never has more than a few dozen
            // entries.
            val maxEntries = minOf(entryCount, 200)
            for (i in 0 until maxEntries) {
                val entryOffset = ifdOffset + 2 + i * 12
                if (entryOffset + 12 > end) return@runCatching null
                val tag = u16(entryOffset)
                if (tag == 0x0112) {
                    val valueType = u16(entryOffset + 2)
                    if (valueType != 3) return@runCatching null // SHORT expected
                    val value = u16(entryOffset + 8)
                    return@runCatching if (value in 1..8) value else null
                }
            }
            null
        }.getOrNull()
    }
}
