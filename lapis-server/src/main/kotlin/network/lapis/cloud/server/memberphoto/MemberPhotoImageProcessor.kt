package network.lapis.cloud.server.memberphoto

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import network.lapis.cloud.server.events.CoverImageFormat
import network.lapis.cloud.server.events.EventCoverImageProcessor
import network.lapis.cloud.shared.domain.MemberPhotoRules
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import javax.imageio.ImageIO
import javax.imageio.stream.MemoryCacheImageInputStream

private val logger = KotlinLogging.logger {}

/** Typed outcome of [MemberPhotoImageProcessor.process] -- every expected failure is a value, never an exception. */
internal sealed interface MemberPhotoProcessingResult {
    class Ok(
        val jpeg: ByteArray,
        val edgePx: Int,
    ) : MemberPhotoProcessingResult

    /** Header dimensions exceed [MemberPhotoPolicy.MAX_EDGE_PX]/[MemberPhotoPolicy.MAX_PIXELS] -- rejected before any pixel is decoded. */
    data object DimensionsTooLarge : MemberPhotoProcessingResult

    /** Shorter edge below [MemberPhotoRules.MIN_SHORT_EDGE_PX]. */
    data object TooSmall : MemberPhotoProcessingResult

    data object Undecodable : MemberPhotoProcessingResult
}

/**
 * Welle V1.9.19 "Mitglieder-Foto" -- headless decode -> square crop -> downscale -> metadata-free
 * JPEG re-encode of an untrusted JPEG/PNG upload. Shares the decode primitives and the process-wide
 * decode budget with [EventCoverImageProcessor] (see its KDoc for the decompression-bomb /
 * polyglot reasoning), but unlike the cover pipeline this one CROPS to a square and ALWAYS emits a
 * JPEG.
 *
 * **Pipeline (order matters):**
 * 1. Reader chosen by the SNIFFED format, header dimensions read first.
 * 2. `longEdge > MAX_EDGE_PX || w*h > MAX_PIXELS` -> [MemberPhotoProcessingResult.DimensionsTooLarge], no decode.
 * 3. `min(w,h) < MIN_SHORT_EDGE_PX` -> [MemberPhotoProcessingResult.TooSmall] (EXIF orientation only swaps
 *    axes, so the shorter edge is orientation-independent and the header check suffices).
 * 4. Subsampling so the subsampled shorter edge stays >= TARGET_EDGE_PX.
 * 5. Decode, EXIF orientation (JPEG only) -- BEFORE the crop, so the crop sees the upright image.
 * 6. Square crop of side `s = min(w,h)`: landscape centered, portrait with 25 % of the surplus cut
 *    from the top.
 * 7. Scale to `min(TARGET_EDGE_PX, s)` -- NEVER upscaled.
 * 8. Draw onto a FRESH `TYPE_INT_RGB` image over a white ground: alpha, EXIF, ICC, XMP, GPS and any
 *    polyglot tail are gone, only pixels survive.
 * 9. JPEG encode with `IIOImage(img, null, null)` (no metadata at all).
 */
internal object MemberPhotoImageProcessor {
    init {
        System.setProperty("java.awt.headless", "true")
        ImageIO.setUseCache(false)
    }

    suspend fun process(
        bytes: ByteArray,
        format: CoverImageFormat,
    ): MemberPhotoProcessingResult = withContext(Dispatchers.IO) { processBlocking(bytes = bytes, format = format) }

    /** Synchronous core -- the caller is responsible for holding a decode permit. Internal for tests. */
    internal fun processBlocking(
        bytes: ByteArray,
        format: CoverImageFormat,
    ): MemberPhotoProcessingResult {
        val readers = ImageIO.getImageReadersByFormatName(format.imageIoFormatName)
        if (!readers.hasNext()) return MemberPhotoProcessingResult.Undecodable
        val reader = readers.next()
        try {
            reader.setInput(MemoryCacheImageInputStream(ByteArrayInputStream(bytes)), true, true)

            val width: Int
            val height: Int
            try {
                width = reader.getWidth(0)
                height = reader.getHeight(0)
            } catch (e: Exception) {
                logger.info { "Member photo header unreadable: ${e.message}" }
                return MemberPhotoProcessingResult.Undecodable
            }
            if (width <= 0 || height <= 0) return MemberPhotoProcessingResult.Undecodable

            val longEdge = maxOf(width, height)
            val shortEdge = minOf(width, height)
            if (longEdge > MemberPhotoPolicy.MAX_EDGE_PX || width.toLong() * height.toLong() > MemberPhotoPolicy.MAX_PIXELS) {
                return MemberPhotoProcessingResult.DimensionsTooLarge
            }
            if (shortEdge < MemberPhotoRules.MIN_SHORT_EDGE_PX) return MemberPhotoProcessingResult.TooSmall

            val subsample = (shortEdge / (2 * MemberPhotoRules.TARGET_EDGE_PX)).coerceAtLeast(1)
            val param = reader.defaultReadParam
            if (subsample > 1) param.setSourceSubsampling(subsample, subsample, 0, 0)

            val decoded =
                try {
                    reader.read(0, param)
                } catch (e: Exception) {
                    logger.info { "Member photo undecodable: ${e.message}" }
                    return MemberPhotoProcessingResult.Undecodable
                }

            val orientation = if (format == CoverImageFormat.JPEG) EventCoverImageProcessor.readExifOrientation(bytes) else 1
            val oriented = EventCoverImageProcessor.applyOrientation(image = decoded, orientation = orientation)
            val square = cropSquare(oriented)
            val edge = minOf(MemberPhotoRules.TARGET_EDGE_PX, square.width)
            val resized = EventCoverImageProcessor.downscaleToTarget(source = square, targetLongEdge = edge)
            val flattened = flatten(resized)
            val jpeg = EventCoverImageProcessor.encodeJpeg(image = flattened, quality = MemberPhotoPolicy.JPEG_QUALITY)
            return MemberPhotoProcessingResult.Ok(jpeg = jpeg, edgePx = flattened.width)
        } catch (e: Exception) {
            logger.info { "Member photo processing failed: ${e.message}" }
            return MemberPhotoProcessingResult.Undecodable
        } finally {
            reader.dispose()
        }
    }

    /** Landscape: centered horizontally, `y = 0`. Portrait: `x = 0`, `y = (h - s) * 0.25`. */
    internal fun cropSquare(source: BufferedImage): BufferedImage {
        val w = source.width
        val h = source.height
        val s = minOf(w, h)
        val x: Int
        val y: Int
        if (w >= h) {
            x = (w - s) / 2
            y = 0
        } else {
            x = 0
            y = ((h - s) * MemberPhotoPolicy.PORTRAIT_CROP_TOP_FRACTION).toInt()
        }
        if (x == 0 && y == 0 && w == h) return source
        return source.getSubimage(x, y, s, s)
    }

    private fun flatten(source: BufferedImage): BufferedImage {
        val target = BufferedImage(source.width, source.height, BufferedImage.TYPE_INT_RGB)
        val g = target.createGraphics()
        try {
            g.color = Color.WHITE
            g.fillRect(0, 0, target.width, target.height)
            g.drawImage(source, 0, 0, null)
        } finally {
            g.dispose()
        }
        return target
    }
}
