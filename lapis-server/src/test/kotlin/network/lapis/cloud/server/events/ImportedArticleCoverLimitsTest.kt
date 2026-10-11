package network.lapis.cloud.server.events

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO

private fun pngOf(
    width: Int,
    height: Int,
): ByteArray {
    val image = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
    val g = image.createGraphics()
    g.color = Color.BLUE
    g.fillRect(0, 0, width, height)
    g.dispose()
    return ByteArrayOutputStream().also { ImageIO.write(image, "png", it) }.toByteArray()
}

private fun jpegOf(
    width: Int,
    height: Int,
): ByteArray {
    val image = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
    val g = image.createGraphics()
    g.color = Color.WHITE
    g.fillRect(0, 0, width, height)
    g.dispose()
    return ByteArrayOutputStream().also { ImageIO.write(image, "jpeg", it) }.toByteArray()
}

private fun withOrientation(
    jpeg: ByteArray,
    orientation: Int,
): ByteArray {
    val tiff =
        byteArrayOf(
            'M'.code.toByte(),
            'M'.code.toByte(),
            0x00,
            0x2A,
            0x00,
            0x00,
            0x00,
            0x08,
            0x00,
            0x01,
            0x01,
            0x12,
            0x00,
            0x03,
            0x00,
            0x00,
            0x00,
            0x01,
            0x00,
            orientation.toByte(),
            0x00,
            0x00,
            0x00,
            0x00,
            0x00,
            0x00,
        )
    val exifHeader = byteArrayOf('E'.code.toByte(), 'x'.code.toByte(), 'i'.code.toByte(), 'f'.code.toByte(), 0x00, 0x00)
    val body = exifHeader + tiff
    val len = body.size + 2
    val app1 = byteArrayOf(0xFF.toByte(), 0xE1.toByte(), (len shr 8).toByte(), (len and 0xFF).toByte()) + body
    return jpeg.copyOfRange(0, 2) + app1 + jpeg.copyOfRange(2, jpeg.size)
}

/**
 * Welle V1.9.106 -- [CoverImageLimits.IMPORTED_ARTICLE_COVER] on the processor level: width >= 300, height >= 150, aspect 1:2..4:1 measured AFTER the
 * EXIF orientation, never upscaled, and the event limits stay exactly as they were.
 */
class ImportedArticleCoverLimitsTest :
    FunSpec({
        val imported = CoverImageLimits.IMPORTED_ARTICLE_COVER

        suspend fun run(
            bytes: ByteArray,
            format: CoverImageFormat = CoverImageFormat.PNG,
            limits: CoverImageLimits = imported,
        ) = EventCoverImageProcessor.process(bytes = bytes, format = format, limits = limits)

        test("EVENT_COVER and CHAPTER_CREST keep their long/short-edge rule bit for bit") {
            CoverImageLimits.EVENT_COVER.rule shouldBe CoverDimensionRule.LongShortEdge(minLongEdgePx = 800, minShortEdgePx = 600)
            CoverImageLimits.CHAPTER_CREST.rule shouldBe CoverDimensionRule.LongShortEdge(minLongEdgePx = 64, minShortEdgePx = 64)
            CoverImageLimits.EVENT_COVER.describeMinimumDe() shouldBe "mindestens 800x600px, je nach Ausrichtung"
        }

        test("300 x 200 is accepted and stored as 300 x 200: small images are never upscaled") {
            val ok = run(pngOf(width = 300, height = 200)) as CoverProcessingResult.Ok
            ok.width shouldBe 300
            ok.height shouldBe 200
            val decoded = ImageIO.read(ByteArrayInputStream(ok.bytes))
            decoded.width shouldBe 300
            decoded.height shouldBe 200
        }

        test("the same 300 x 200 image is rejected under the event limits") {
            run(pngOf(width = 300, height = 200), limits = CoverImageLimits.EVENT_COVER) shouldBe
                CoverProcessingResult.DimensionsTooSmall(width = 300, height = 200)
        }

        test("299 x 200 is rejected and reports its dimensions") {
            run(pngOf(width = 299, height = 200)) shouldBe CoverProcessingResult.DimensionsTooSmall(width = 299, height = 200)
        }

        test("a height below 150 is rejected") {
            run(pngOf(width = 400, height = 149)) shouldBe CoverProcessingResult.DimensionsTooSmall(width = 400, height = 149)
            (run(pngOf(width = 300, height = 150)) is CoverProcessingResult.Ok) shouldBe true
        }

        test("aspect ratio limits: 4:1 passes, wider than 4:1 is rejected, 1:2 passes, narrower is rejected") {
            (run(pngOf(width = 1200, height = 300)) is CoverProcessingResult.Ok) shouldBe true
            run(pngOf(width = 1201, height = 300)) shouldBe CoverProcessingResult.DimensionsTooSmall(width = 1201, height = 300)
            (run(pngOf(width = 300, height = 600)) is CoverProcessingResult.Ok) shouldBe true
            run(pngOf(width = 300, height = 601)) shouldBe CoverProcessingResult.DimensionsTooSmall(width = 300, height = 601)
        }

        test(
            "EXIF orientation 6 turns a 200 x 400 header into a 400 x 200 image, which is accepted (and reported oriented when rejected)",
        ) {
            val rotated = withOrientation(jpeg = jpegOf(width = 200, height = 400), orientation = 6)
            val ok = run(rotated, CoverImageFormat.JPEG) as CoverProcessingResult.Ok
            ok.width shouldBe 400
            ok.height shouldBe 200
            val tooNarrow = withOrientation(jpeg = jpegOf(width = 400, height = 250), orientation = 6)
            // oriented 250 x 400: width < 300 -> rejected, with the ORIENTED dimensions
            run(tooNarrow, CoverImageFormat.JPEG) shouldBe CoverProcessingResult.DimensionsTooSmall(width = 250, height = 400)
        }

        test("the decompression-bomb guard still applies to the imported limits") {
            run(pngOf(width = 8001, height = 400)) shouldBe CoverProcessingResult.DimensionsTooLarge
        }
    })
