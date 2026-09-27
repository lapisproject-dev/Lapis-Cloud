package network.lapis.cloud.server.events

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import kotlin.random.Random

private fun renderPng(
    width: Int,
    height: Int,
    markerColor: Color = Color.RED,
): ByteArray {
    val image = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
    val g = image.createGraphics()
    g.color = Color.WHITE
    g.fillRect(0, 0, width, height)
    // A small marker block in the top-left corner -- used by the orientation test to verify WHERE
    // that corner ends up after a rotation.
    g.color = markerColor
    g.fillRect(0, 0, maxOf(1, width / 10), maxOf(1, height / 10))
    g.dispose()
    val out = ByteArrayOutputStream()
    ImageIO.write(image, "png", out)
    return out.toByteArray()
}

private fun renderJpeg(
    width: Int,
    height: Int,
): ByteArray {
    val image = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
    val g = image.createGraphics()
    g.color = Color.WHITE
    g.fillRect(0, 0, width, height)
    g.color = Color.RED
    g.fillRect(0, 0, maxOf(1, width / 10), maxOf(1, height / 10))
    g.dispose()
    val out = ByteArrayOutputStream()
    ImageIO.write(image, "jpeg", out)
    return out.toByteArray()
}

/** Inserts a minimal, hand-built EXIF APP1 segment (with an Orientation tag) right after the JPEG's SOI marker. */
private fun withExifOrientation(
    jpeg: ByteArray,
    orientation: Int,
): ByteArray {
    require(jpeg.size >= 2 && jpeg[0] == 0xFF.toByte() && jpeg[1] == 0xD8.toByte())
    // TIFF header (big-endian "MM") + IFD0 with exactly one entry (tag 0x0112, type SHORT, count 1,
    // value in the first 2 bytes of the 4-byte value field) + next-IFD offset 0.
    val tiff =
        byteArrayOf(
            'M'.code.toByte(),
            'M'.code.toByte(),
            0x00,
            0x2A,
            0x00,
            0x00,
            0x00,
            0x08, // IFD0 offset = 8
            0x00,
            0x01, // 1 entry
            0x01,
            0x12, // tag 0x0112 Orientation
            0x00,
            0x03, // type SHORT
            0x00,
            0x00,
            0x00,
            0x01, // count 1
            0x00,
            orientation.toByte(),
            0x00,
            0x00, // value (SHORT, big-endian) + padding
            0x00,
            0x00,
            0x00,
            0x00, // next IFD offset
        )
    val exifHeader = byteArrayOf('E'.code.toByte(), 'x'.code.toByte(), 'i'.code.toByte(), 'f'.code.toByte(), 0x00, 0x00)
    val app1Body = exifHeader + tiff
    val app1Length = app1Body.size + 2
    val app1Marker =
        byteArrayOf(0xFF.toByte(), 0xE1.toByte(), (app1Length shr 8).toByte(), (app1Length and 0xFF).toByte()) + app1Body
    return jpeg.copyOfRange(0, 2) + app1Marker + jpeg.copyOfRange(2, jpeg.size)
}

/**
 * Welle "Veranstaltungs-Titelbild" (Event Cover Image) -- [EventCoverImageProcessor] is pure image
 * processing (no DB, no transaction), same "unit-testable in isolation" posture `EventPolicyTest`
 * documents for its own policy object.
 */
class EventCoverImageProcessorTest :
    FunSpec({
        test("sniff: empty array -> null") {
            EventCoverImageProcessor.sniff(ByteArray(0)) shouldBe null
        }

        test("sniff: JPEG magic bytes recognized") {
            EventCoverImageProcessor.sniff(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0x00)) shouldBe CoverImageFormat.JPEG
        }

        test("sniff: PNG magic bytes recognized") {
            val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
            EventCoverImageProcessor.sniff(png) shouldBe CoverImageFormat.PNG
        }

        test("sniff: GIF/other magic bytes -> null (no GIF/WebP/SVG support)") {
            val gif = "GIF89a".toByteArray()
            EventCoverImageProcessor.sniff(gif) shouldBe null
        }

        test("process: undecodable (truncated) JPEG -> Undecodable, never throws") {
            val truncated = renderJpeg(width = 1000, height = 800).copyOfRange(0, 20)
            val result = EventCoverImageProcessor.process(bytes = truncated, format = CoverImageFormat.JPEG)
            result shouldBe CoverProcessingResult.Undecodable
        }

        test("process: 799x600 -> DimensionsTooSmall") {
            val bytes = renderPng(width = 799, height = 600)
            EventCoverImageProcessor.process(bytes = bytes, format = CoverImageFormat.PNG) shouldBe CoverProcessingResult.DimensionsTooSmall
        }

        test("process: 600x800 portrait (long edge >= 800, short edge >= 600) -> Ok, orientation-independent per design decision F4") {
            val bytes = renderPng(width = 600, height = 800)
            val result = EventCoverImageProcessor.process(bytes = bytes, format = CoverImageFormat.PNG)
            result.shouldBeOk()
        }

        test("process: 800x600 exactly at the threshold -> Ok") {
            val bytes = renderPng(width = 800, height = 600)
            EventCoverImageProcessor.process(bytes = bytes, format = CoverImageFormat.PNG).shouldBeOk()
        }

        test("process: an 8001px edge is rejected as DimensionsTooLarge WITHOUT ever decoding pixels") {
            // A real 8001x8001 raster would need gigabytes; instead we build a PNG whose IHDR chunk
            // CLAIMS 9000x9000 but carries no real pixel data after it -- if the processor decoded
            // before checking dimensions, this would throw/hang instead of returning cleanly and
            // quickly.
            val fakeLargePng = buildFakePngWithClaimedDimensions(width = 9000, height = 9000)
            val result = EventCoverImageProcessor.process(bytes = fakeLargePng, format = CoverImageFormat.PNG)
            result shouldBe CoverProcessingResult.DimensionsTooLarge
        }

        test("process: PNG with alpha stays PNG with alpha") {
            val image = BufferedImage(900, 700, BufferedImage.TYPE_INT_ARGB)
            val g = image.createGraphics()
            g.color = Color(255, 0, 0, 128)
            g.fillRect(0, 0, 900, 700)
            g.dispose()
            val out = ByteArrayOutputStream()
            ImageIO.write(image, "png", out)
            val result = EventCoverImageProcessor.process(bytes = out.toByteArray(), format = CoverImageFormat.PNG)
            val ok = result.shouldBeOk()
            val decoded = ImageIO.read(ByteArrayInputStream(ok.bytes))
            decoded.colorModel.hasAlpha() shouldBe true
        }

        test("process: a large JPEG is downscaled to EXACTLY the target long edge, aspect ratio preserved") {
            val bytes = renderJpeg(width = 4000, height = 3000)
            val result = EventCoverImageProcessor.process(bytes = bytes, format = CoverImageFormat.JPEG)
            val ok = result.shouldBeOk()
            // Regression guard: downscaleToTarget used to only ever halve, which overshoots past the
            // target (4000x3000 used to land at 1000x750, not 1600x1200) -- it must now land exactly
            // on TARGET_LONG_EDGE_PX, not merely "at or under" it.
            ok.width shouldBe EventCoverPolicy.TARGET_LONG_EDGE_PX
            ok.height shouldBe 1200
        }

        test("process: a source just above 2x the target (1700x1275) is downscaled to EXACTLY the target long edge") {
            // Exercises the boundary where the halving loop (`> 2 * target`) already stops and the
            // final exact-resize step must do the rest -- also an extreme-aspect-ratio-adjacent case
            // where the naive "halve, then stop once <= target" loop used to undershoot below the
            // policy's own MIN_LONG_EDGE_PX/MIN_SHORT_EDGE_PX for some inputs.
            val bytes = renderJpeg(width = 1700, height = 1275)
            val result = EventCoverImageProcessor.process(bytes = bytes, format = CoverImageFormat.JPEG)
            val ok = result.shouldBeOk()
            ok.width shouldBe EventCoverPolicy.TARGET_LONG_EDGE_PX
            ok.height shouldBe 1200
        }

        test("process: re-encoded JPEG bytes carry no Exif/GPS APP1 marker") {
            val withExif = withExifOrientation(jpeg = renderJpeg(width = 2000, height = 1500), orientation = 1)
            val result = EventCoverImageProcessor.process(bytes = withExif, format = CoverImageFormat.JPEG)
            val ok = result.shouldBeOk()
            val asText = ok.bytes.toString(Charsets.ISO_8859_1)
            (asText.contains("Exif") || asText.contains("GPS")) shouldBe false
        }

        test("readExifOrientation: no EXIF present -> default 1") {
            EventCoverImageProcessor.readExifOrientation(renderJpeg(width = 500, height = 500)) shouldBe 1
        }

        test("readExifOrientation: orientation tag present and in range is read correctly") {
            for (o in 1..8) {
                EventCoverImageProcessor.readExifOrientation(
                    withExifOrientation(jpeg = renderJpeg(width = 500, height = 500), orientation = o),
                ) shouldBe
                    o
            }
        }

        test("readExifOrientation: out-of-range value (0) falls back to 1") {
            EventCoverImageProcessor.readExifOrientation(
                withExifOrientation(jpeg = renderJpeg(width = 500, height = 500), orientation = 0),
            ) shouldBe
                1
        }

        test("readExifOrientation: never throws on random/truncated bytes (fuzz)") {
            val random = Random(42)
            repeat(200) {
                val garbage = ByteArray(random.nextInt(0, 400))
                random.nextBytes(garbage)
                // No exception is the assertion itself -- a thrown exception fails the test.
                val orientation = EventCoverImageProcessor.readExifOrientation(garbage)
                (orientation in 1..8) shouldBe true
            }
        }

        test("readExifOrientation: never throws on a JPEG with a truncated APP1 segment") {
            val full = withExifOrientation(jpeg = renderJpeg(width = 500, height = 500), orientation = 6)
            for (cut in full.indices step 7) {
                val truncated = full.copyOfRange(0, cut)
                EventCoverImageProcessor.readExifOrientation(truncated) shouldNotBe null
            }
        }

        test("process: every EXIF orientation value 1..8 is applied without throwing and yields a decodable result") {
            for (o in 1..8) {
                val bytes = withExifOrientation(jpeg = renderJpeg(width = 1200, height = 900), orientation = o)
                val result = EventCoverImageProcessor.process(bytes = bytes, format = CoverImageFormat.JPEG)
                val ok = result.shouldBeOk()
                ImageIO.read(ByteArrayInputStream(ok.bytes)) shouldNotBe null
            }
        }

        test("process: EXIF orientation 6 (rotate 90 CW) swaps width/height before downscaling") {
            // Source is portrait 600x900 (long edge 900 < TARGET, so no further downscale happens
            // beyond the orientation-driven swap) -- orientation 6 means the CAMERA was rotated 90
            // degrees, so the correctly-oriented image is landscape: width/height swapped relative to
            // the raw source.
            val sourceBytes = renderJpeg(width = 600, height = 900)
            val rotated = withExifOrientation(jpeg = sourceBytes, orientation = 6)
            val result = EventCoverImageProcessor.process(bytes = rotated, format = CoverImageFormat.JPEG)
            val ok = result.shouldBeOk()
            ok.width shouldBe 900
            ok.height shouldBe 600
        }
    })

private fun CoverProcessingResult.shouldBeOk(): CoverProcessingResult.Ok {
    check(this is CoverProcessingResult.Ok) { "Expected Ok, got $this" }
    return this
}

/**
 * Builds a syntactically valid PNG (correct signature, a real IHDR chunk with a valid CRC claiming
 * [width]x[height], and a minimal IEND) but with NO real IDAT pixel data -- exactly the shape a
 * decompression-bomb probe would use: `ImageReader.getWidth`/`getHeight` must reject it from the
 * header alone, long before any attempt to decode actual pixels (which would fail/hang here anyway,
 * since there is no valid compressed pixel stream).
 */
private fun buildFakePngWithClaimedDimensions(
    width: Int,
    height: Int,
): ByteArray {
    val signature = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
    val ihdrData =
        ByteArray(13).also { data ->
            data[0] = (width shr 24).toByte()
            data[1] = (width shr 16).toByte()
            data[2] = (width shr 8).toByte()
            data[3] = width.toByte()
            data[4] = (height shr 24).toByte()
            data[5] = (height shr 16).toByte()
            data[6] = (height shr 8).toByte()
            data[7] = height.toByte()
            data[8] = 8 // bit depth
            data[9] = 2 // color type: truecolor
            data[10] = 0 // compression
            data[11] = 0 // filter
            data[12] = 0 // interlace
        }
    val ihdrChunk = pngChunk(type = "IHDR", data = ihdrData)
    val iendChunk = pngChunk(type = "IEND", data = ByteArray(0))
    return signature + ihdrChunk + iendChunk
}

private fun pngChunk(
    type: String,
    data: ByteArray,
): ByteArray {
    val typeBytes = type.toByteArray(Charsets.US_ASCII)
    val length = data.size
    val lengthBytes = byteArrayOf((length shr 24).toByte(), (length shr 16).toByte(), (length shr 8).toByte(), length.toByte())
    val crc = java.util.zip.CRC32()
    crc.update(typeBytes)
    crc.update(data)
    val crcValue = crc.value
    val crcBytes =
        byteArrayOf(
            (crcValue shr 24).toByte(),
            (crcValue shr 16).toByte(),
            (crcValue shr 8).toByte(),
            crcValue.toByte(),
        )
    return lengthBytes + typeBytes + data + crcBytes
}
