package network.lapis.cloud.server.memberphoto

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import network.lapis.cloud.server.events.CoverImageFormat
import network.lapis.cloud.server.events.EventCoverImageProcessor
import java.awt.Color

private fun process(
    bytes: ByteArray,
    format: CoverImageFormat,
): MemberPhotoProcessingResult = MemberPhotoImageProcessor.processBlocking(bytes = bytes, format = format)

private fun MemberPhotoProcessingResult.ok(): MemberPhotoProcessingResult.Ok {
    shouldBeInstanceOf<MemberPhotoProcessingResult.Ok>()
    return this as MemberPhotoProcessingResult.Ok
}

private fun Triple<Int, Int, Int>.isBlueish() = third > 150 && first < 100 && second < 100

private fun Triple<Int, Int, Int>.isReddish() = first > 150 && second < 100 && third < 100

/** Welle V1.9.19 "Mitglieder-Foto" -- the decode/crop/scale/re-encode pipeline. */
class MemberPhotoImageProcessorTest :
    FunSpec({
        test("sniff agrees with the formats the pipeline is fed (JPEG and PNG)") {
            EventCoverImageProcessor.sniff(MemberPhotoTestImages.jpeg(MemberPhotoTestImages.solid(width = 10, height = 10))) shouldBe
                CoverImageFormat.JPEG
            EventCoverImageProcessor.sniff(MemberPhotoTestImages.png(MemberPhotoTestImages.solid(width = 10, height = 10))) shouldBe
                CoverImageFormat.PNG
        }

        test("a large JPEG and a large PNG both become an 800x800 JPEG (FFD8 magic)") {
            val source = MemberPhotoTestImages.solid(width = 1600, height = 1000)
            listOf(
                MemberPhotoTestImages.jpeg(source) to CoverImageFormat.JPEG,
                MemberPhotoTestImages.png(source) to CoverImageFormat.PNG,
            ).forEach { (bytes, format) ->
                val ok = process(bytes = bytes, format = format).ok()
                ok.edgePx shouldBe 800
                ok.jpeg[0] shouldBe 0xFF.toByte()
                ok.jpeg[1] shouldBe 0xD8.toByte()
                MemberPhotoTestImages.dimensions(ok.jpeg) shouldBe (800 to 800)
            }
        }

        test("portrait 800x1600: the crop starts at source row 200 (25 percent of the surplus is cut from the TOP)") {
            val source =
                MemberPhotoTestImages.image(width = 800, height = 1600) { g ->
                    g.color = Color.BLUE
                    g.fillRect(0, 0, 800, 1600)
                    g.color = Color.RED
                    g.fillRect(0, 0, 800, 200) // rows 0..199 -- cut away by the crop
                    g.color = Color.GREEN
                    g.fillRect(0, 1000, 800, 600) // rows 1000.. -- below the crop window (200..999)
                }
            val ok = process(bytes = MemberPhotoTestImages.png(source), format = CoverImageFormat.PNG).ok()
            MemberPhotoTestImages.rgbAt(jpeg = ok.jpeg, x = 400, y = 5).isBlueish() shouldBe true
            MemberPhotoTestImages.rgbAt(jpeg = ok.jpeg, x = 400, y = 795).isBlueish() shouldBe true
        }

        test("landscape 1600x800 is cropped centered: side bands are cut away") {
            val source =
                MemberPhotoTestImages.image(width = 1600, height = 800) { g ->
                    g.color = Color.BLUE
                    g.fillRect(0, 0, 1600, 800)
                    g.color = Color.RED
                    g.fillRect(0, 0, 400, 800)
                    g.color = Color.GREEN
                    g.fillRect(1200, 0, 400, 800)
                }
            val ok = process(bytes = MemberPhotoTestImages.png(source), format = CoverImageFormat.PNG).ok()
            MemberPhotoTestImages.rgbAt(jpeg = ok.jpeg, x = 5, y = 400).isBlueish() shouldBe true
            MemberPhotoTestImages.rgbAt(jpeg = ok.jpeg, x = 795, y = 400).isBlueish() shouldBe true
        }

        test("EXIF orientation 6 is applied BEFORE the crop (axes swap)") {
            // 1000x500, left half red / right half blue. Orientation 6 rotates 90 degrees clockwise: red ends up on TOP.
            val source =
                MemberPhotoTestImages.image(width = 1000, height = 500) { g ->
                    g.color = Color.BLUE
                    g.fillRect(0, 0, 1000, 500)
                    g.color = Color.RED
                    g.fillRect(0, 0, 500, 500)
                }
            val jpeg = MemberPhotoTestImages.jpegWithExif(jpeg = MemberPhotoTestImages.jpeg(source), orientation = 6)
            val ok = process(bytes = jpeg, format = CoverImageFormat.JPEG).ok()
            ok.edgePx shouldBe 500
            MemberPhotoTestImages.rgbAt(jpeg = ok.jpeg, x = 250, y = 10).isReddish() shouldBe true
            MemberPhotoTestImages.rgbAt(jpeg = ok.jpeg, x = 250, y = 490).isBlueish() shouldBe true
        }

        test("shorter edge 399 is TooSmall, 400 passes at 400x400 -- never upscaled") {
            process(
                bytes = MemberPhotoTestImages.png(MemberPhotoTestImages.solid(width = 399, height = 800)),
                format = CoverImageFormat.PNG,
            ) shouldBe
                MemberPhotoProcessingResult.TooSmall
            val ok =
                process(
                    bytes = MemberPhotoTestImages.png(MemberPhotoTestImages.solid(width = 400, height = 400)),
                    format = CoverImageFormat.PNG,
                ).ok()
            ok.edgePx shouldBe 400
            MemberPhotoTestImages.dimensions(ok.jpeg) shouldBe (400 to 400)
            // 600x600 is also kept at its own size (< 800 target)
            process(
                bytes = MemberPhotoTestImages.png(MemberPhotoTestImages.solid(width = 600, height = 900)),
                format = CoverImageFormat.PNG,
            ).ok().edgePx shouldBe
                600
        }

        test("a PNG header claiming 9000x9000 is rejected from the header alone (DimensionsTooLarge), without decoding") {
            val started = System.nanoTime()
            process(bytes = MemberPhotoTestImages.pngHeaderOnly(width = 9000, height = 9000), format = CoverImageFormat.PNG) shouldBe
                MemberPhotoProcessingResult.DimensionsTooLarge
            ((System.nanoTime() - started) / 1_000_000 < 2000) shouldBe true
        }

        test("more than 40 megapixels (7000x7000) is DimensionsTooLarge even though each edge is below 8000") {
            process(bytes = MemberPhotoTestImages.pngHeaderOnly(width = 7000, height = 7000), format = CoverImageFormat.PNG) shouldBe
                MemberPhotoProcessingResult.DimensionsTooLarge
        }

        test("JPEG with EXIF (GPS-like secret) in APP1: no APP1..APP15 segment and no secret bytes in the output") {
            val secret = "GPSLATITUDE-SECRET-4711"
            val jpeg =
                MemberPhotoTestImages.jpegWithExif(
                    jpeg = MemberPhotoTestImages.jpeg(MemberPhotoTestImages.solid(width = 900, height = 900)),
                    orientation = 1,
                    secret = secret,
                )
            MemberPhotoTestImages.contains(haystack = jpeg, needle = secret) shouldBe true // the INPUT does carry it
            val ok = process(bytes = jpeg, format = CoverImageFormat.JPEG).ok()
            MemberPhotoTestImages.contains(haystack = ok.jpeg, needle = secret) shouldBe false
            MemberPhotoTestImages.jpegMarkersBeforeScan(ok.jpeg).none { it in 0xE1..0xEF } shouldBe true
        }

        test("PNG with tEXt/eXIf chunks becomes a JPEG without that content") {
            val secret = "PNGSECRET-0815"
            val png =
                MemberPhotoTestImages.pngWithMetadata(
                    png = MemberPhotoTestImages.png(MemberPhotoTestImages.solid(width = 900, height = 900)),
                    secret = secret,
                )
            MemberPhotoTestImages.contains(haystack = png, needle = secret) shouldBe true
            val ok = process(bytes = png, format = CoverImageFormat.PNG).ok()
            MemberPhotoTestImages.contains(haystack = ok.jpeg, needle = secret) shouldBe false
            MemberPhotoTestImages.jpegMarkersBeforeScan(ok.jpeg).none { it in 0xE1..0xEF } shouldBe true
        }

        test("a PNG with alpha gets a white ground") {
            val source =
                MemberPhotoTestImages.image(width = 500, height = 500, type = java.awt.image.BufferedImage.TYPE_INT_ARGB) { g ->
                    g.composite = java.awt.AlphaComposite.Clear
                    g.fillRect(0, 0, 500, 500)
                }
            val ok = process(bytes = MemberPhotoTestImages.png(source), format = CoverImageFormat.PNG).ok()
            val (r, g, b) = MemberPhotoTestImages.rgbAt(jpeg = ok.jpeg, x = 250, y = 250)
            (r > 240 && g > 240 && b > 240) shouldBe true
        }

        test("a truncated PNG and a JPEG-magic blob of garbage are Undecodable") {
            val png = MemberPhotoTestImages.png(MemberPhotoTestImages.solid(width = 900, height = 900))
            process(bytes = png.copyOfRange(0, png.size / 2), format = CoverImageFormat.PNG) shouldBe
                MemberPhotoProcessingResult.Undecodable
            val garbage = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte()) + ByteArray(200) { (it * 7).toByte() }
            process(bytes = garbage, format = CoverImageFormat.JPEG) shouldBe MemberPhotoProcessingResult.Undecodable
        }
    })
