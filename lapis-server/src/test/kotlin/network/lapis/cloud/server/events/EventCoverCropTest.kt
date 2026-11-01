package network.lapis.cloud.server.events

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeSameInstanceAs
import network.lapis.cloud.server.articles.ArticleCoverPolicy
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO

private val CROPPED = CoverImageLimits.EVENT_COVER_CROPPED

private fun pngOf(
    width: Int,
    height: Int,
    paint: (java.awt.Graphics2D) -> Unit = { g ->
        g.color = Color.GREEN
        g.fillRect(0, 0, width, height)
    },
): ByteArray {
    val image = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
    val g = image.createGraphics()
    paint(g)
    g.dispose()
    return ByteArrayOutputStream().also { ImageIO.write(image, "png", it) }.toByteArray()
}

private fun jpegOf(
    width: Int,
    height: Int,
): ByteArray {
    val image = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
    val g = image.createGraphics()
    g.color = Color.GREEN
    g.fillRect(0, 0, width, height)
    g.dispose()
    return ByteArrayOutputStream().also { ImageIO.write(image, "jpeg", it) }.toByteArray()
}

/** Inserts a minimal hand-built EXIF APP1 segment (Orientation tag) after the SOI marker. */
private fun withExifOrientation(
    jpeg: ByteArray,
    orientation: Int,
): ByteArray {
    val tiffInts =
        intArrayOf(
            'M'.code,
            'M'.code,
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
            orientation,
            0x00,
            0x00,
            0x00,
            0x00,
            0x00,
            0x00,
        )
    val tiff = ByteArray(tiffInts.size) { tiffInts[it].toByte() }
    val exifHeader = byteArrayOf('E'.code.toByte(), 'x'.code.toByte(), 'i'.code.toByte(), 'f'.code.toByte(), 0x00, 0x00)
    val body = exifHeader + tiff
    val length = body.size + 2
    val app1 = byteArrayOf(0xFF.toByte(), 0xE1.toByte(), (length shr 8).toByte(), (length and 0xFF).toByte()) + body
    return jpeg.copyOfRange(0, 2) + app1 + jpeg.copyOfRange(2, jpeg.size)
}

private fun decode(result: CoverProcessingResult): BufferedImage {
    check(result is CoverProcessingResult.Ok) { "Expected Ok, got $result" }
    return ImageIO.read(ByteArrayInputStream(result.bytes))
}

private fun BufferedImage.rgb(
    x: Int,
    y: Int,
): Int = getRGB(x, y) and 0xFFFFFF

private fun BufferedImage.shouldBe4to3(): BufferedImage {
    (width * 3) shouldBe (height * 4)
    return this
}

private suspend fun run(
    bytes: ByteArray,
    format: CoverImageFormat = CoverImageFormat.PNG,
    limits: CoverImageLimits = CROPPED,
) = EventCoverImageProcessor.process(bytes = bytes, format = format, limits = limits)

/** V1.9.252 -- event covers are centre-cropped to exactly 4:3 landscape; every other pipeline is unchanged. */
class EventCoverCropTest :
    FunSpec({
        val green = Color.GREEN.rgb and 0xFFFFFF
        val red = Color.RED.rgb and 0xFFFFFF
        val blue = Color.BLUE.rgb and 0xFFFFFF

        test("centreCropRect table") {
            val aspect = CropAspect(w = 4, h = 3)

            fun expectRect(
                width: Int,
                height: Int,
                expected: CropRect,
            ) {
                centreCropRect(width = width, height = height, aspect = aspect) shouldBe expected
            }
            expectRect(width = 1920, height = 1080, expected = CropRect(x = 240, y = 0, width = 1440, height = 1080))
            expectRect(width = 1200, height = 1600, expected = CropRect(x = 0, y = 350, width = 1200, height = 900))
            expectRect(width = 800, height = 600, expected = CropRect(x = 0, y = 0, width = 800, height = 600))
            expectRect(width = 801, height = 600, expected = CropRect(x = 0, y = 0, width = 800, height = 600))
            expectRect(width = 8000, height = 8000, expected = CropRect(x = 0, y = 1000, width = 8000, height = 6000))
            expectRect(width = 3, height = 2, expected = CropRect(x = 0, y = 0, width = 3, height = 2))
            shouldThrow<IllegalArgumentException> { centreCropRect(width = 0, height = 5, aspect = aspect) }
            shouldThrow<IllegalArgumentException> { centreCropRect(width = 5, height = -1, aspect = aspect) }
        }

        test("limits: only EVENT_COVER_CROPPED crops; EVENT_COVER and every other limit stay uncropped") {
            CoverImageLimits.EVENT_COVER.cropAspect shouldBe null
            CROPPED.cropAspect shouldBe CropAspect(w = 4, h = 3)
            CoverImageLimits.CHAPTER_CREST.cropAspect shouldBe null
            CoverImageLimits.GALLERY_IMAGE.cropAspect shouldBe null
            CoverImageLimits.VIDEO_OVERLAY_LOGO.cropAspect shouldBe null
            CoverImageLimits.VIDEO_OVERLAY_FRAME.cropAspect shouldBe null
            CoverImageLimits.IMPORTED_ARTICLE_COVER.cropAspect shouldBe null
            // Tripwire: articles must keep the uncropped 16:9 limits.
            ArticleCoverPolicy.limitsForArticle(false) shouldBeSameInstanceAs CoverImageLimits.EVENT_COVER
            CROPPED.describeMinimumDe() shouldContain "4:3"
            CoverImageLimits.EVENT_COVER.describeMinimumDe() shouldBe "mindestens 800x600px, je nach Ausrichtung"
        }

        test("16:9 landscape 1920x1080 -> 1440x1080, centre kept, side stripes cut away") {
            val source =
                pngOf(width = 1920, height = 1080) { g ->
                    g.color = Color.RED
                    g.fillRect(0, 0, 240, 1080)
                    g.color = Color.GREEN
                    g.fillRect(240, 0, 1440, 1080)
                    g.color = Color.BLUE
                    g.fillRect(1680, 0, 240, 1080)
                }
            val out = decode(run(bytes = source)).shouldBe4to3()
            out.width shouldBe 1440
            out.height shouldBe 1080
            out.rgb(x = 0, y = 540) shouldBe green
            out.rgb(x = 1439, y = 540) shouldBe green
            (out.rgb(x = 0, y = 0) == red || out.rgb(x = 1439, y = 0) == blue) shouldBe false
        }

        test("large 16:9 3200x1800 -> crop 2400x1800 -> exactly 1600x1200") {
            val out = decode(run(bytes = pngOf(width = 3200, height = 1800))).shouldBe4to3()
            out.width shouldBe 1600
            out.height shouldBe 1200
        }

        test("strongly elongated 8000x2000 -> subsampling follows the cropped region, stored exactly 1600x1200") {
            val out = decode(run(bytes = pngOf(width = 8000, height = 2000))).shouldBe4to3()
            out.width shouldBe 1600
            out.height shouldBe 1200
        }

        test("strongly elongated portrait 3000x8000 -> stored exactly 1600x1200") {
            val out = decode(run(bytes = pngOf(width = 3000, height = 8000))).shouldBe4to3()
            out.width shouldBe 1600
            out.height shouldBe 1200
        }

        test("6400x601 passes the minimum and is stored at 800x600, not below it") {
            val out = decode(run(bytes = pngOf(width = 6400, height = 601))).shouldBe4to3()
            out.width shouldBe 800
            out.height shouldBe 600
        }

        test("portrait 1200x1600 -> 1200x900, top and bottom bands cut away") {
            val source =
                pngOf(width = 1200, height = 1600) { g ->
                    g.color = Color.RED
                    g.fillRect(0, 0, 1200, 350)
                    g.color = Color.GREEN
                    g.fillRect(0, 350, 1200, 900)
                    g.color = Color.BLUE
                    g.fillRect(0, 1250, 1200, 350)
                }
            val out = decode(run(bytes = source)).shouldBe4to3()
            out.width shouldBe 1200
            out.height shouldBe 900
            out.rgb(x = 600, y = 0) shouldBe green
            out.rgb(x = 600, y = 899) shouldBe green
            (out.rgb(x = 600, y = 0) == red || out.rgb(x = 600, y = 899) == blue) shouldBe false
        }

        test("exactly 4:3 stays as it is: 800x600 and 1600x1200") {
            decode(run(bytes = pngOf(width = 800, height = 600))).let {
                it.width shouldBe 800
                it.height shouldBe 600
            }
            decode(run(bytes = pngOf(width = 1600, height = 1200))).let {
                it.width shouldBe 1600
                it.height shouldBe 1200
            }
        }

        test("odd sizes end up exactly 4:3 (no 1-px drift after the downscale)") {
            decode(run(bytes = pngOf(width = 1999, height = 1201))).shouldBe4to3()
            decode(run(bytes = pngOf(width = 2401, height = 1799))).shouldBe4to3()
            decode(run(bytes = pngOf(width = 1601, height = 1199 + 400))).shouldBe4to3()
        }

        test("below the minimum, checked on the original and on the cropped size") {
            run(bytes = pngOf(width = 799, height = 600)) shouldBe CoverProcessingResult.DimensionsTooSmall(width = 799, height = 600)
            // Portrait: passes the long/short rule as it is, but is only 600x450 after the crop.
            run(bytes = pngOf(width = 600, height = 800)) shouldBe CoverProcessingResult.DimensionsTooSmall(width = 600, height = 800)
            // Very wide: the crop is exactly 800x600, still fine.
            decode(run(bytes = pngOf(width = 2000, height = 600))).let {
                it.width shouldBe 800
                it.height shouldBe 600
            }
            run(bytes = pngOf(width = 2000, height = 590)) shouldBe CoverProcessingResult.DimensionsTooSmall(width = 2000, height = 590)
        }

        test("EXIF orientation 6 (shown as portrait) is rotated first, then cropped to landscape 4:3") {
            val source = withExifOrientation(jpeg = jpegOf(width = 1600, height = 1200), orientation = 6)
            val out = decode(run(bytes = source, format = CoverImageFormat.JPEG)).shouldBe4to3()
            out.width shouldBe 1200
            out.height shouldBe 900
        }

        test("EXIF orientation 3 (no axis swap) stays 4:3 unchanged") {
            val out =
                decode(
                    run(
                        bytes = withExifOrientation(jpeg = jpegOf(width = 1600, height = 1200), orientation = 3),
                        format = CoverImageFormat.JPEG,
                    ),
                ).shouldBe4to3()
            out.width shouldBe 1600
            out.height shouldBe 1200
        }

        test("the crop path stays metadata-free: no EXIF segment in the stored JPEG") {
            val result =
                run(
                    bytes = withExifOrientation(jpeg = jpegOf(width = 1600, height = 1200), orientation = 6),
                    format = CoverImageFormat.JPEG,
                )
            check(result is CoverProcessingResult.Ok)
            String(result.bytes, Charsets.ISO_8859_1).contains("Exif") shouldBe false
        }

        test("decompression-bomb guard still runs before the crop") {
            run(bytes = pngOf(width = 8001, height = 100)) shouldBe CoverProcessingResult.DimensionsTooLarge
        }

        test("defaults unchanged: EVENT_COVER keeps 16:9, crest and article cover are not cropped") {
            decode(run(bytes = pngOf(width = 1920, height = 1080), limits = CoverImageLimits.EVENT_COVER)).let {
                it.width shouldBe 1600
                it.height shouldBe 900
            }
            decode(run(bytes = pngOf(width = 1920, height = 1080), limits = ArticleCoverPolicy.limitsForArticle(false))).let {
                it.width shouldBe 1600
                it.height shouldBe 900
            }
            decode(run(bytes = pngOf(width = 100, height = 64), limits = CoverImageLimits.CHAPTER_CREST)).let {
                it.width shouldBe 100
                it.height shouldBe 64
            }
            decode(run(bytes = pngOf(width = 1920, height = 1080), limits = CoverImageLimits.GALLERY_IMAGE)).let {
                (it.width * 9) shouldBe (it.height * 16)
            }
        }
    })
