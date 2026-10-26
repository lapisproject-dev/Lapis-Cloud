package network.lapis.cloud.server.gallery

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.ints.shouldBeLessThanOrEqual
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import network.lapis.cloud.server.events.CoverImageFormat
import network.lapis.cloud.shared.domain.GalleryRules
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.Random
import java.util.zip.CRC32
import javax.imageio.ImageIO

private fun resource(name: String): ByteArray =
    requireNotNull(
        GalleryImageProcessorTest::class.java.getResourceAsStream("/gallery/$name"),
    ) { "missing test resource $name" }.readBytes()

private fun jpeg(
    width: Int,
    height: Int,
): ByteArray {
    val image = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
    val g = image.createGraphics()
    g.color = Color.ORANGE
    g.fillRect(0, 0, width, height)
    g.color = Color.BLUE
    g.fillRect(width / 4, height / 4, width / 2, height / 2)
    g.dispose()
    return ByteArrayOutputStream().also { ImageIO.write(image, "jpeg", it) }.toByteArray()
}

private fun png(
    width: Int,
    height: Int,
    noise: Boolean = false,
): ByteArray {
    val image = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
    if (noise) {
        val rnd = Random(42)
        for (y in 0 until height) for (x in 0 until width) image.setRGB(x, y, rnd.nextInt(0xFFFFFF))
    } else {
        val g = image.createGraphics()
        g.color = Color.GREEN
        g.fillRect(0, 0, width, height)
        g.dispose()
    }
    return ByteArrayOutputStream().also { ImageIO.write(image, "png", it) }.toByteArray()
}

/** A JPEG whose APP1 segment carries an EXIF block with a GPS-looking payload right after the SOI marker. */
private fun jpegWithExif(): ByteArray {
    val base = jpeg(width = 200, height = 120)
    val payload = "Exif\u0000\u0000MM\u0000*GPSLatitude=52.26;GPSLongitude=10.52;Make=SecretCamera".toByteArray(Charsets.ISO_8859_1)
    val length = payload.size + 2
    return base.copyOfRange(0, 2) + byteArrayOf(0xFF.toByte(), 0xE1.toByte(), (length shr 8).toByte(), length.toByte()) + payload +
        base.copyOfRange(2, base.size)
}

/** A PNG whose IHDR claims [width] x [height] (CRC fixed up) while the pixel data stays tiny: the decompression-bomb shape. */
private fun pngClaiming(
    width: Int,
    height: Int,
): ByteArray {
    val bytes = png(width = 8, height = 8)
    val ihdrDataStart = 8 + 8

    fun putInt(
        offset: Int,
        value: Int,
    ) {
        bytes[offset] = (value ushr 24).toByte()
        bytes[offset + 1] = (value ushr 16).toByte()
        bytes[offset + 2] = (value ushr 8).toByte()
        bytes[offset + 3] = value.toByte()
    }
    putInt(ihdrDataStart, width)
    putInt(ihdrDataStart + 4, height)
    val crc = CRC32().apply { update(bytes, ihdrDataStart - 4, 4 + 13) }.value
    putInt(ihdrDataStart + 13, crc.toInt())
    return bytes
}

private fun GalleryProcessingResult.ok(): GalleryProcessingResult.Ok = shouldBeInstanceOf<GalleryProcessingResult.Ok>()

/** V1.9.222 -- the decoder pipeline of the gallery: sniffing, the formats, the metadata strip, the guards. */
class GalleryImageProcessorTest :
    FunSpec({
        fun run(
            bytes: ByteArray,
            format: GalleryInputFormat? = GalleryImageProcessor.sniff(bytes),
        ) = GalleryImageProcessor.processBlocking(bytes = bytes, input = requireNotNull(format))

        test("sniffing recognises JPEG, PNG and WebP by their first bytes and nothing else") {
            GalleryImageProcessor.sniff(jpeg(width = 10, height = 10)) shouldBe GalleryInputFormat.JPEG
            GalleryImageProcessor.sniff(png(width = 10, height = 10)) shouldBe GalleryInputFormat.PNG
            GalleryImageProcessor.sniff(resource("lossy.webp")) shouldBe GalleryInputFormat.WEBP
            GalleryImageProcessor.sniff("GIF89a".toByteArray() + ByteArray(20)).shouldBeNull()
            GalleryImageProcessor.sniff("<svg xmlns=\"http://www.w3.org/2000/svg\"/>".toByteArray()).shouldBeNull()
            GalleryImageProcessor.sniff("<html><script>alert(1)</script></html>".toByteArray()).shouldBeNull()
            GalleryImageProcessor.sniff("RIFF\u0000\u0000\u0000\u0000WAVEfmt ".toByteArray(Charsets.ISO_8859_1)).shouldBeNull()
            GalleryImageProcessor.sniff(ByteArray(0)).shouldBeNull()
        }

        test("a polyglot that starts like HTML but is named .jpg is not an image") {
            GalleryImageProcessor.sniff("<!DOCTYPE html><img src=x onerror=alert(1)>".toByteArray()).shouldBeNull()
        }

        test("a JPEG keeps its format and loses EXIF/GPS: the output has no APP1 and no trace of the payload") {
            val input = jpegWithExif()
            String(input, Charsets.ISO_8859_1).contains("SecretCamera") shouldBe true
            val out = run(input).ok()
            out.format shouldBe CoverImageFormat.JPEG
            val text = String(out.bytes, Charsets.ISO_8859_1)
            text.contains("Exif") shouldBe false
            text.contains("SecretCamera") shouldBe false
            text.contains("GPSLatitude") shouldBe false
            ImageIO.read(ByteArrayInputStream(out.bytes)).width shouldBe 200
        }

        test("a PNG stays a PNG") {
            val out = run(png(width = 120, height = 90)).ok()
            out.format shouldBe CoverImageFormat.PNG
            (out.width to out.height) shouldBe (120 to 90)
        }

        test("a large picture is scaled down to at most 1600 px on the long edge, a small one is never enlarged") {
            val big = run(jpeg(width = 4000, height = 3000)).ok()
            maxOf(big.width, big.height) shouldBe 1600
            val small = run(jpeg(width = 40, height = 30)).ok()
            (small.width to small.height) shouldBe (40 to 30)
        }

        test("a lossy WebP is decoded and stored as JPEG, a WebP with transparency as PNG; WebP itself is never stored") {
            val lossy = run(resource("lossy.webp")).ok()
            lossy.format shouldBe CoverImageFormat.JPEG
            (lossy.width to lossy.height) shouldBe (64 to 48)
            GalleryImageProcessor.sniff(lossy.bytes) shouldBe GalleryInputFormat.JPEG
            val alpha = run(resource("alpha.webp")).ok()
            alpha.format shouldBe CoverImageFormat.PNG
            GalleryImageProcessor.sniff(alpha.bytes) shouldBe GalleryInputFormat.PNG
        }

        test("a declared size above 8000 px or 40 megapixels is refused from the header, before a pixel is decoded") {
            run(pngClaiming(width = 8001, height = 100), GalleryInputFormat.PNG) shouldBe GalleryProcessingResult.DimensionsTooLarge
            run(pngClaiming(width = 7000, height = 7000), GalleryInputFormat.PNG) shouldBe GalleryProcessingResult.DimensionsTooLarge
            run(pngClaiming(width = 40_000, height = 40_000), GalleryInputFormat.PNG) shouldBe GalleryProcessingResult.DimensionsTooLarge
        }

        test("a truncated or garbage file is undecodable, never an exception") {
            // (the JDK JPEG decoder is lenient about a missing tail and pads it, so the JPEG is cut INSIDE its header)
            run(jpeg(width = 300, height = 200).copyOfRange(0, 20), GalleryInputFormat.JPEG) shouldBe GalleryProcessingResult.Undecodable
            val wholePng = png(width = 300, height = 200, noise = true)
            run(wholePng.copyOfRange(0, wholePng.size / 2), GalleryInputFormat.PNG) shouldBe GalleryProcessingResult.Undecodable
            // a bare PNG signature with garbage behind it: refused (as undecodable, or from its nonsense header as too large), never
            // accepted
            (
                run(
                    byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A) + ByteArray(40),
                    GalleryInputFormat.PNG,
                ) is GalleryProcessingResult.Ok
            ) shouldBe
                false
            (run(resource("lossy.webp").copyOfRange(0, 30), GalleryInputFormat.WEBP) is GalleryProcessingResult.Ok) shouldBe false
        }

        test("a PNG photo that exceeds 5 MiB after re-encoding is stored as JPEG instead (the backup restore would refuse the big PNG)") {
            val source = png(width = 1600, height = 1200, noise = true)
            val out = run(source).ok()
            out.format shouldBe CoverImageFormat.JPEG
            out.bytes.size shouldBeLessThanOrEqual GalleryRules.MAX_UPLOAD_BYTES.toInt()
        }

        test("the output of every format fits the 5 MiB upload cap") {
            listOf(jpeg(width = 1600, height = 1200), png(width = 300, height = 300), resource("lossy.webp")).forEach {
                run(it).ok().bytes.size shouldBeLessThanOrEqual GalleryRules.MAX_UPLOAD_BYTES.toInt()
            }
        }
    })
