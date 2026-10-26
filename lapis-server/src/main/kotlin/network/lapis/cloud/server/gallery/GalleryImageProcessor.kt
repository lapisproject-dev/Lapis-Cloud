package network.lapis.cloud.server.gallery

import network.lapis.cloud.server.events.CoverImageFormat
import network.lapis.cloud.server.events.CoverImageLimits
import network.lapis.cloud.server.events.CoverProcessingResult
import network.lapis.cloud.server.events.EventCoverImageProcessor
import network.lapis.cloud.shared.domain.GalleryRules
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import javax.imageio.ImageIO

/** The container formats a gallery upload may arrive in. WebP is an INPUT only: it is stored as JPEG (PNG when it has alpha), never as
 * WebP. */
internal enum class GalleryInputFormat(
    val readerFormatName: String,
) {
    JPEG("jpeg"),
    PNG("png"),
    WEBP("webp"),
}

/** Outcome of [GalleryImageProcessor.process] -- typed, never an exception, for every expected state. */
internal sealed interface GalleryProcessingResult {
    class Ok(
        val bytes: ByteArray,
        val format: CoverImageFormat,
        val width: Int,
        val height: Int,
    ) : GalleryProcessingResult

    /** The declared dimensions exceed the decompression-bomb guard; nothing was decoded. */
    data object DimensionsTooLarge : GalleryProcessingResult

    data object Undecodable : GalleryProcessingResult

    /** The re-encoded picture is still larger than [GalleryRules.MAX_UPLOAD_BYTES] (the backup restore would refuse it). */
    data object EncodedTooLarge : GalleryProcessingResult

    /** No decode slot became free in time. */
    data object Busy : GalleryProcessingResult
}

/**
 * V1.9.222 -- sniffing, decoding and re-encoding of ONE gallery picture. It reuses [EventCoverImageProcessor]'s pipeline (header
 * dimension
 * guard
 * BEFORE any pixel is decoded, subsampling decode, EXIF orientation for JPEG, a FRESH raster re-encoded without any metadata -- which
 * strips
 * EXIF/GPS/ICC/XMP and neutralises polyglots) and its process-wide decode semaphore; only the output choice differs.
 *
 * - The client-declared `Content-Type` and the file name are never consulted: [sniff] looks at the first bytes only.
 * - JPEG stays JPEG, PNG stays PNG, WebP becomes JPEG -- or PNG when the raster really has a transparent pixel.
 * - EXIF orientation applies to JPEG input only; a WebP's orientation metadata is deliberately ignored.
 * - The result is capped at [GalleryRules.MAX_UPLOAD_BYTES]: a 1600 px PNG of a photograph can exceed 5 MiB, and
 * `MAX_RESTORED_ASSET_BYTES`
 *   would make the backup restore refuse that file. A PNG without transparency is therefore re-encoded as JPEG when too large.
 */
internal object GalleryImageProcessor {
    private const val SNIFF_BYTES = 12
    private const val DECODE_WAIT_MILLIS = 10_000L
    private val PNG_SIGNATURE = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)

    /** Magic-byte sniff over the first [SNIFF_BYTES] bytes: JPEG `FF D8 FF`, PNG signature, WebP `RIFF....WEBP`. */
    fun sniff(head: ByteArray): GalleryInputFormat? {
        val h = if (head.size > SNIFF_BYTES) head.copyOfRange(0, SNIFF_BYTES) else head
        return when {
            h.size >= 3 && h[0] == 0xFF.toByte() && h[1] == 0xD8.toByte() && h[2] == 0xFF.toByte() -> GalleryInputFormat.JPEG
            h.size >= 8 && PNG_SIGNATURE.indices.all { h[it] == PNG_SIGNATURE[it] } -> GalleryInputFormat.PNG
            h.size >= 12 &&
                h[0] == 'R'.code.toByte() &&
                h[1] == 'I'.code.toByte() &&
                h[2] == 'F'.code.toByte() &&
                h[3] == 'F'.code.toByte() &&
                h[8] == 'W'.code.toByte() &&
                h[9] == 'E'.code.toByte() &&
                h[10] == 'B'.code.toByte() &&
                h[11] == 'P'.code.toByte() ->
                GalleryInputFormat.WEBP
            else -> null
        }
    }

    suspend fun process(
        bytes: ByteArray,
        input: GalleryInputFormat,
    ): GalleryProcessingResult =
        EventCoverImageProcessor.withDecodePermitOrNull(waitMillis = DECODE_WAIT_MILLIS) { processBlocking(bytes = bytes, input = input) }
            ?: GalleryProcessingResult.Busy

    /** The whole pipeline; the caller holds the decode permit (see [process]). */
    internal fun processBlocking(
        bytes: ByteArray,
        input: GalleryInputFormat,
    ): GalleryProcessingResult {
        val decoded =
            EventCoverImageProcessor.processDecoded(
                bytes = bytes,
                readerFormatName = input.readerFormatName,
                isJpegExif = input == GalleryInputFormat.JPEG,
                limits = CoverImageLimits.GALLERY_IMAGE,
                outputFormatFor = { raster -> outputFormat(input = input, raster = raster) },
            )
        return when (decoded) {
            CoverProcessingResult.DimensionsTooLarge -> GalleryProcessingResult.DimensionsTooLarge
            is CoverProcessingResult.DimensionsTooSmall, CoverProcessingResult.Undecodable -> GalleryProcessingResult.Undecodable
            is CoverProcessingResult.Ok -> capSize(decoded)
        }
    }

    private fun outputFormat(
        input: GalleryInputFormat,
        raster: BufferedImage,
    ): CoverImageFormat =
        when (input) {
            GalleryInputFormat.JPEG -> CoverImageFormat.JPEG
            GalleryInputFormat.PNG -> CoverImageFormat.PNG
            GalleryInputFormat.WEBP -> if (hasTransparentPixel(raster)) CoverImageFormat.PNG else CoverImageFormat.JPEG
        }

    private fun capSize(ok: CoverProcessingResult.Ok): GalleryProcessingResult {
        if (ok.bytes.size <= GalleryRules.MAX_UPLOAD_BYTES) {
            return GalleryProcessingResult.Ok(bytes = ok.bytes, format = ok.format, width = ok.width, height = ok.height)
        }
        if (ok.format != CoverImageFormat.PNG) return GalleryProcessingResult.EncodedTooLarge
        // A big PNG: when it has no transparency it is a photograph and JPEG is the right container. Our own output is decoded again
        // here.
        val raster =
            runCatching { ImageIO.read(ByteArrayInputStream(ok.bytes)) }.getOrNull() ?: return GalleryProcessingResult.EncodedTooLarge
        if (hasTransparentPixel(raster)) return GalleryProcessingResult.EncodedTooLarge
        val jpeg =
            EventCoverImageProcessor.encode(
                image = EventCoverImageProcessor.flatten(source = raster, format = CoverImageFormat.JPEG),
                format = CoverImageFormat.JPEG,
            )
        if (jpeg.size > GalleryRules.MAX_UPLOAD_BYTES) return GalleryProcessingResult.EncodedTooLarge
        return GalleryProcessingResult.Ok(bytes = jpeg, format = CoverImageFormat.JPEG, width = ok.width, height = ok.height)
    }

    /** `true` iff [raster] has an alpha channel AND at least one pixel that is not fully opaque. */
    internal fun hasTransparentPixel(raster: BufferedImage): Boolean {
        if (!raster.colorModel.hasAlpha()) return false
        for (y in 0 until raster.height) {
            for (x in 0 until raster.width) {
                if ((raster.getRGB(x, y) ushr 24) != 0xFF) return true
            }
        }
        return false
    }
}
