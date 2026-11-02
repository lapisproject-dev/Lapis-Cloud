package network.lapis.cloud.server.conference.pausefeed

import network.lapis.cloud.server.events.CoverImageFormat
import network.lapis.cloud.server.events.CoverImageLimits
import network.lapis.cloud.server.events.CoverProcessingResult
import network.lapis.cloud.server.events.EventCoverImageProcessor
import network.lapis.cloud.server.gallery.GalleryImageProcessor
import network.lapis.cloud.server.gallery.GalleryInputFormat
import network.lapis.cloud.shared.domain.PauseMediaRules
import java.awt.Color
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import javax.imageio.ImageIO

/** Outcome of [PauseImageProcessor.process] -- typed, never an exception, for every expected state. */
internal sealed interface PauseImageResult {
    /** A fresh 1280x720 PNG without any metadata. */
    class Ok(
        val bytes: ByteArray,
    ) : PauseImageResult

    data object DimensionsTooLarge : PauseImageResult

    data object TooSmall : PauseImageResult

    data object Undecodable : PauseImageResult

    data object Busy : PauseImageResult
}

/**
 * V1.9.255 -- turns one uploaded picture into the stored pause picture: a PNG of exactly 1280x720 with a flat dark margin, never cropped.
 *
 * It reuses [EventCoverImageProcessor.processDecoded] (the header-dimension and decompression-bomb guards BEFORE a single pixel is decoded, the
 * subsampling decode, the EXIF orientation for JPEG, a fresh raster without any metadata -- which also neutralises a polyglot file) and its
 * process-wide decode permit. The client-declared type and the file name are never consulted; [sniff] looks at the first bytes only.
 *
 * Letterbox: the picture is scaled proportionally into the frame (enlarging is allowed -- a 640x360 upload fills the frame), centred on
 * [BACKGROUND], and the result is encoded as a fresh PNG.
 */
internal object PauseImageProcessor {
    /** The flat margin colour of the frame. */
    val BACKGROUND = Color(0x14, 0x17, 0x1C)

    private const val DECODE_WAIT_MILLIS = 10_000L

    fun sniff(head: ByteArray): GalleryInputFormat? = GalleryImageProcessor.sniff(head)

    suspend fun process(
        bytes: ByteArray,
        input: GalleryInputFormat,
    ): PauseImageResult =
        EventCoverImageProcessor.withDecodePermitOrNull(waitMillis = DECODE_WAIT_MILLIS) { processBlocking(bytes = bytes, input = input) }
            ?: PauseImageResult.Busy

    /** The whole pipeline; the caller holds the decode permit. */
    internal fun processBlocking(
        bytes: ByteArray,
        input: GalleryInputFormat,
    ): PauseImageResult {
        val decoded =
            EventCoverImageProcessor.processDecoded(
                bytes = bytes,
                readerFormatName = input.readerFormatName,
                isJpegExif = input == GalleryInputFormat.JPEG,
                limits = CoverImageLimits.PAUSE_IMAGE,
                outputFormatFor = { CoverImageFormat.PNG },
            )
        return when (decoded) {
            CoverProcessingResult.DimensionsTooLarge -> PauseImageResult.DimensionsTooLarge
            is CoverProcessingResult.DimensionsTooSmall -> PauseImageResult.TooSmall
            CoverProcessingResult.Undecodable -> PauseImageResult.Undecodable
            is CoverProcessingResult.Ok -> {
                // Our own PNG, at most 1280 px on the long edge: decoding it again is cheap and safe.
                val raster =
                    runCatching { ImageIO.read(ByteArrayInputStream(decoded.bytes)) }.getOrNull() ?: return PauseImageResult.Undecodable
                PauseImageResult.Ok(EventCoverImageProcessor.encode(image = letterbox(source = raster), format = CoverImageFormat.PNG))
            }
        }
    }

    /** Scales [source] proportionally into the frame and centres it on [BACKGROUND]. Pure; the result is `TYPE_INT_RGB`. */
    internal fun letterbox(
        source: BufferedImage,
        frameWidth: Int = PauseMediaRules.IMAGE_FRAME_WIDTH_PX,
        frameHeight: Int = PauseMediaRules.IMAGE_FRAME_HEIGHT_PX,
    ): BufferedImage {
        val scale = minOf(frameWidth.toDouble() / source.width, frameHeight.toDouble() / source.height)
        val w = (source.width * scale).toInt().coerceIn(1, frameWidth)
        val h = (source.height * scale).toInt().coerceIn(1, frameHeight)
        val frame = BufferedImage(frameWidth, frameHeight, BufferedImage.TYPE_INT_RGB)
        val g = frame.createGraphics()
        try {
            g.color = BACKGROUND
            g.fillRect(0, 0, frameWidth, frameHeight)
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC)
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
            g.drawImage(source, (frameWidth - w) / 2, (frameHeight - h) / 2, w, h, null)
        } finally {
            g.dispose()
        }
        return frame
    }
}
