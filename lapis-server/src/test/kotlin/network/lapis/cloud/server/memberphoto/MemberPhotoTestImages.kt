package network.lapis.cloud.server.memberphoto

import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.util.zip.CRC32
import javax.imageio.ImageIO

/** Synthetic image bytes for the member photo tests -- no binary fixtures, every byte is built here. */
internal object MemberPhotoTestImages {
    fun image(
        width: Int,
        height: Int,
        type: Int = BufferedImage.TYPE_INT_RGB,
        paint: (java.awt.Graphics2D) -> Unit,
    ): BufferedImage {
        val img = BufferedImage(width, height, type)
        val g = img.createGraphics()
        try {
            paint(g)
        } finally {
            g.dispose()
        }
        return img
    }

    fun solid(
        width: Int,
        height: Int,
        color: Color = Color.BLUE,
    ): BufferedImage =
        image(width = width, height = height) { g ->
            g.color = color
            g.fillRect(0, 0, width, height)
        }

    fun jpeg(img: BufferedImage): ByteArray {
        val out = ByteArrayOutputStream()
        check(ImageIO.write(img, "jpeg", out)) { "no jpeg writer" }
        return out.toByteArray()
    }

    fun png(img: BufferedImage): ByteArray {
        val out = ByteArrayOutputStream()
        check(ImageIO.write(img, "png", out)) { "no png writer" }
        return out.toByteArray()
    }

    /** A JPEG with an EXIF APP1 segment (orientation tag, plus optional extra ASCII [secret] payload) spliced in after the JFIF APP0. */
    fun jpegWithExif(
        jpeg: ByteArray,
        orientation: Int,
        secret: String? = null,
    ): ByteArray {
        val tiff = ByteArrayOutputStream()
        tiff.write(byteArrayOf('M'.code.toByte(), 'M'.code.toByte(), 0, 0x2A, 0, 0, 0, 8))
        tiff.write(byteArrayOf(0, 1)) // one IFD entry
        tiff.write(byteArrayOf(0x01, 0x12, 0, 3, 0, 0, 0, 1, 0, orientation.toByte(), 0, 0)) // Orientation, SHORT
        tiff.write(byteArrayOf(0, 0, 0, 0)) // next IFD offset
        val payload = ByteArrayOutputStream()
        payload.write("Exif".toByteArray(Charsets.US_ASCII))
        payload.write(byteArrayOf(0, 0))
        payload.write(tiff.toByteArray())
        if (secret != null) payload.write(secret.toByteArray(Charsets.US_ASCII))
        val body = payload.toByteArray()
        val length = body.size + 2
        val segment = byteArrayOf(0xFF.toByte(), 0xE1.toByte(), (length shr 8).toByte(), length.toByte()) + body
        // Insert after SOI + APP0 (JFIF), i.e. after the first segment following FFD8.
        require(jpeg[0] == 0xFF.toByte() && jpeg[1] == 0xD8.toByte())
        var insertAt = 2
        if (jpeg[2] == 0xFF.toByte() && jpeg[3] == 0xE0.toByte()) {
            insertAt = 2 + 2 + (((jpeg[4].toInt() and 0xFF) shl 8) or (jpeg[5].toInt() and 0xFF))
        }
        return jpeg.copyOfRange(0, insertAt) + segment + jpeg.copyOfRange(insertAt, jpeg.size)
    }

    private fun chunk(
        type: String,
        data: ByteArray,
    ): ByteArray {
        val typeBytes = type.toByteArray(Charsets.US_ASCII)
        val crc =
            CRC32()
                .apply {
                    update(typeBytes)
                    update(data)
                }.value
        val out = ByteArrayOutputStream()

        fun int(v: Long) = out.write(byteArrayOf((v shr 24).toByte(), (v shr 16).toByte(), (v shr 8).toByte(), v.toByte()))
        int(data.size.toLong())
        out.write(typeBytes)
        out.write(data)
        int(crc)
        return out.toByteArray()
    }

    /** [png] with extra ancillary chunks (`tEXt`, `eXIf`) carrying [secret], spliced in before IEND. */
    fun pngWithMetadata(
        png: ByteArray,
        secret: String,
    ): ByteArray {
        val iendStart = png.size - 12
        val text = chunk(type = "tEXt", data = "Comment".toByteArray() + byteArrayOf(0) + secret.toByteArray())
        val exif = chunk(type = "eXIf", data = "MM".toByteArray() + secret.toByteArray())
        return png.copyOfRange(0, iendStart) + text + exif + png.copyOfRange(iendStart, png.size)
    }

    /** A structurally valid PNG header claiming [width] x [height] with a bogus one-byte IDAT -- enough for header reads, never decodable. */
    fun pngHeaderOnly(
        width: Int,
        height: Int,
    ): ByteArray {
        val signature = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
        val ihdr = ByteArrayOutputStream()

        fun int(v: Int) = ihdr.write(byteArrayOf((v shr 24).toByte(), (v shr 16).toByte(), (v shr 8).toByte(), v.toByte()))
        int(width)
        int(height)
        ihdr.write(byteArrayOf(8, 2, 0, 0, 0)) // 8-bit RGB
        return signature + chunk(type = "IHDR", data = ihdr.toByteArray()) + chunk(type = "IDAT", data = byteArrayOf(0)) +
            chunk(type = "IEND", data = ByteArray(0))
    }

    fun rgbAt(
        jpeg: ByteArray,
        x: Int,
        y: Int,
    ): Triple<Int, Int, Int> {
        val img = ImageIO.read(jpeg.inputStream())
        val rgb = img.getRGB(x, y)
        return Triple((rgb shr 16) and 0xFF, (rgb shr 8) and 0xFF, rgb and 0xFF)
    }

    fun dimensions(jpeg: ByteArray): Pair<Int, Int> {
        val img = ImageIO.read(jpeg.inputStream())
        return img.width to img.height
    }

    /** The marker bytes (second byte after 0xFF) of every segment before the start of scan. */
    fun jpegMarkersBeforeScan(jpeg: ByteArray): List<Int> {
        val markers = mutableListOf<Int>()
        var pos = 2
        while (pos + 4 <= jpeg.size) {
            check(jpeg[pos] == 0xFF.toByte()) { "not a marker at $pos" }
            val marker = jpeg[pos + 1].toInt() and 0xFF
            markers += marker
            if (marker == 0xDA) break
            val length = ((jpeg[pos + 2].toInt() and 0xFF) shl 8) or (jpeg[pos + 3].toInt() and 0xFF)
            pos += 2 + length
        }
        return markers
    }

    fun contains(
        haystack: ByteArray,
        needle: String,
    ): Boolean = String(haystack, Charsets.ISO_8859_1).contains(needle)
}
