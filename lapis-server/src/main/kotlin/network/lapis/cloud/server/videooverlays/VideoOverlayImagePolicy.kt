package network.lapis.cloud.server.videooverlays

import java.io.ByteArrayInputStream
import javax.imageio.ImageIO

/**
 * Welle V1.9.129 -- pure byte-level helpers of the overlay upload pipeline.
 *
 * [pngHasAlpha] answers "does this PNG carry transparency?" by reading ONLY the chunk structure -- no decoder, no allocation proportional to
 * the image. It is run on the UPLOAD bytes (`VideoOverlayRoutes`), before the re-encode: the re-encode may flatten transparency, so only
 * the upload still tells whether the person delivered a transparent picture. Every chunk length is checked against the remaining bytes
 * before it is used: a hostile length can only end the walk (`false`), never overflow or loop.
 */
internal object VideoOverlayImagePolicy {
    private val PNG_SIGNATURE = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
    private const val CHUNK_HEADER = 8 // length (4) + type (4)
    private const val CHUNK_CRC = 4
    private const val COLOR_TYPE_GRAY_ALPHA = 4
    private const val COLOR_TYPE_RGB_ALPHA = 6
    private const val COLOR_TYPE_PALETTE = 3

    fun pngHasAlpha(bytes: ByteArray): Boolean {
        if (bytes.size < PNG_SIGNATURE.size + CHUNK_HEADER + 13 + CHUNK_CRC) return false
        for (i in PNG_SIGNATURE.indices) if (bytes[i] != PNG_SIGNATURE[i]) return false
        var pos = PNG_SIGNATURE.size
        var colorType = -1
        while (pos + CHUNK_HEADER <= bytes.size) {
            val length = readInt(bytes = bytes, offset = pos)
            if (length < 0) return false
            val type = String(bytes, pos + 4, 4, Charsets.ISO_8859_1)
            val dataStart = pos + CHUNK_HEADER
            val remaining = bytes.size - dataStart
            if (length > remaining - CHUNK_CRC) return false
            when (type) {
                "IHDR" -> {
                    if (length < 13) return false
                    colorType = bytes[dataStart + 9].toInt() and 0xFF
                    if (colorType == COLOR_TYPE_GRAY_ALPHA || colorType == COLOR_TYPE_RGB_ALPHA) return true
                }
                // A palette image (and, per spec, grey/RGB images) can carry transparency through a tRNS chunk before the pixel data.
                "tRNS" -> return (colorType == COLOR_TYPE_PALETTE || colorType == 0 || colorType == 2) && length > 0
                "IDAT", "IEND" -> return false
            }
            pos = dataStart + length + CHUNK_CRC
        }
        return false
    }

    private fun readInt(
        bytes: ByteArray,
        offset: Int,
    ): Int =
        ((bytes[offset].toInt() and 0xFF) shl 24) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 8) or
            (bytes[offset + 3].toInt() and 0xFF)

    /**
     * `true` iff the (already re-encoded, size-bounded) PNG [bytes] contain at least one pixel that is not fully opaque. A frame that is
     * fully opaque would hide the whole camera image, so it is refused even when its container declares an alpha channel. The
     * re-encoder ALWAYS writes an ARGB PNG (colour type 6), so [pngHasAlpha] on its output is vacuous -- this pixel scan is the real check.
     * Never throws: an undecodable input is `false`.
     */
    fun hasTransparentPixel(bytes: ByteArray): Boolean =
        runCatching {
            val image = ImageIO.read(ByteArrayInputStream(bytes)) ?: return false
            if (!image.colorModel.hasAlpha()) return false
            for (y in 0 until image.height) {
                for (x in 0 until image.width) {
                    if ((image.getRGB(x, y) ushr 24) < 0xFF) return true
                }
            }
            false
        }.getOrDefault(false)
}
