package network.lapis.cloud.server.events

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO

private fun png(
    width: Int,
    height: Int,
    type: Int = BufferedImage.TYPE_INT_RGB,
    paint: (java.awt.Graphics2D) -> Unit = { g ->
        g.color = Color.BLUE
        g.fillRect(0, 0, width, height)
    },
): ByteArray {
    val image = BufferedImage(width, height, type)
    val g = image.createGraphics()
    paint(g)
    g.dispose()
    return ByteArrayOutputStream().also { ImageIO.write(image, "png", it) }.toByteArray()
}

private fun decode(result: CoverProcessingResult): BufferedImage {
    val ok = result as CoverProcessingResult.Ok
    return ImageIO.read(ByteArrayInputStream(ok.bytes))
}

/**
 * Welle V1.9.20 "Öffentliche Seiten" -- [CoverImageLimits]: the event/article cover pipeline now takes
 * its size thresholds as a parameter. The default must be the pre-V1.9.20 behaviour EXACTLY (the cover
 * routes did not change), and [CoverImageLimits.CHAPTER_CREST] admits small logos and stores up to 1024 px.
 */
class CoverImageLimitsTest :
    FunSpec({
        test("the default limits ARE the event cover limits (800 x 600 minimum, 1600 px target)") {
            CoverImageLimits.EVENT_COVER.minLongEdgePx shouldBe 800
            CoverImageLimits.EVENT_COVER.minShortEdgePx shouldBe 600
            CoverImageLimits.EVENT_COVER.targetLongEdgePx shouldBe 1600
        }

        test("a call without limits produces byte-identical output to an explicit EVENT_COVER call, and still rejects a small image") {
            val source = png(width = 1000, height = 800)
            val implicit = EventCoverImageProcessor.process(bytes = source, format = CoverImageFormat.PNG)
            val explicit =
                EventCoverImageProcessor.process(bytes = source, format = CoverImageFormat.PNG, limits = CoverImageLimits.EVENT_COVER)
            (implicit as CoverProcessingResult.Ok).bytes.toList() shouldBe (explicit as CoverProcessingResult.Ok).bytes.toList()
            EventCoverImageProcessor.process(bytes = png(width = 100, height = 100), format = CoverImageFormat.PNG) shouldBe
                CoverProcessingResult.DimensionsTooSmall
            // The event cover still downsizes to 1600 px.
            val big = decode(EventCoverImageProcessor.process(bytes = png(width = 3200, height = 1600), format = CoverImageFormat.PNG))
            big.width shouldBe 1600
        }

        test("crest limits: 64 x 64 and a wide 100 x 64 logo pass, 63 px on either edge is DimensionsTooSmall") {
            val crest = CoverImageLimits.CHAPTER_CREST
            EventCoverImageProcessor.process(bytes = png(width = 64, height = 64), format = CoverImageFormat.PNG, limits = crest).let {
                (it is CoverProcessingResult.Ok) shouldBe true
            }
            EventCoverImageProcessor.process(bytes = png(width = 100, height = 64), format = CoverImageFormat.PNG, limits = crest).let {
                (it is CoverProcessingResult.Ok) shouldBe true
            }
            EventCoverImageProcessor.process(bytes = png(width = 63, height = 200), format = CoverImageFormat.PNG, limits = crest) shouldBe
                CoverProcessingResult.DimensionsTooSmall
            EventCoverImageProcessor.process(bytes = png(width = 200, height = 63), format = CoverImageFormat.PNG, limits = crest) shouldBe
                CoverProcessingResult.DimensionsTooSmall
            // The decompression-bomb guard is shared and independent of the limits.
            EventCoverImageProcessor.process(bytes = png(width = 8001, height = 70), format = CoverImageFormat.PNG, limits = crest) shouldBe
                CoverProcessingResult.DimensionsTooLarge
        }

        test("crest limits: downscaled to 1024 px proportionally, never upscaled, alpha kept for a PNG") {
            val crest = CoverImageLimits.CHAPTER_CREST
            val large =
                decode(
                    EventCoverImageProcessor.process(
                        bytes = png(width = 3000, height = 1500),
                        format = CoverImageFormat.PNG,
                        limits = crest,
                    ),
                )
            large.width shouldBe 1024
            large.height shouldBe 512
            val small =
                decode(
                    EventCoverImageProcessor.process(bytes = png(width = 100, height = 100), format = CoverImageFormat.PNG, limits = crest),
                )
            small.width shouldBe 100
            val alpha =
                decode(
                    EventCoverImageProcessor.process(
                        bytes =
                            png(width = 128, height = 128, type = BufferedImage.TYPE_INT_ARGB) { g ->
                                g.color = Color(0, 255, 0, 100)
                                g.fillRect(64, 0, 64, 128)
                            },
                        format = CoverImageFormat.PNG,
                        limits = crest,
                    ),
                )
            alpha.colorModel.hasAlpha() shouldBe true
            (alpha.getRGB(10, 10) ushr 24) shouldBe 0
            (alpha.getRGB(100, 10) ushr 24) shouldBe 100
        }
    })
