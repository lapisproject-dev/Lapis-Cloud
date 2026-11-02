package network.lapis.cloud.server.conference.pausefeed

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import network.lapis.cloud.server.gallery.GalleryInputFormat
import network.lapis.cloud.server.memberphoto.MemberPhotoTestImages
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import javax.imageio.ImageIO

private val BG = Color(0x14, 0x17, 0x1C).rgb and 0xFFFFFF

private fun marked(
    width: Int,
    height: Int,
): BufferedImage =
    MemberPhotoTestImages.image(width = width, height = height) { g ->
        g.color = Color.WHITE
        g.fillRect(0, 0, width, height)
        g.color = Color.RED
        g.fillRect(0, 0, 40, 40) // top-left marker
        g.color = Color.BLUE
        g.fillRect(width - 40, height - 40, 40, 40) // bottom-right marker
    }

private fun PauseImageResult.ok(): BufferedImage {
    this.shouldBeInstanceOf<PauseImageResult.Ok>()
    return ImageIO.read(ByteArrayInputStream(this.bytes))
}

private fun rgb(
    img: BufferedImage,
    x: Int,
    y: Int,
) = img.getRGB(x, y) and 0xFFFFFF

class PauseImageProcessorTest :
    FunSpec({
        test("the stored picture is always 1280x720; a 4:3 picture gets side margins and is not cropped") {
            val img =
                PauseImageProcessor
                    .processBlocking(
                        bytes = MemberPhotoTestImages.png(marked(width = 1024, height = 768)),
                        input = GalleryInputFormat.PNG,
                    ).ok()
            img.width shouldBe 1280
            img.height shouldBe 720
            rgb(img = img, x = 2, y = 360) shouldBe BG // left margin
            rgb(img = img, x = 1277, y = 360) shouldBe BG // right margin
            // both corner markers survive: nothing is cut off
            val markerTopLeft = rgb(img = img, x = 160 + 5, y = 5)
            ((markerTopLeft shr 16) and 0xFF > 200 && (markerTopLeft and 0xFF) < 80) shouldBe true
            val markerBottomRight = rgb(img = img, x = 1280 - 160 - 5, y = 720 - 5)
            ((markerBottomRight and 0xFF) > 200 && (markerBottomRight shr 16 and 0xFF) < 80) shouldBe true
        }

        test("a tall 1:3 picture is scaled into the frame with wide margins") {
            val img =
                PauseImageProcessor
                    .processBlocking(
                        bytes = MemberPhotoTestImages.png(marked(width = 640, height = 1920)),
                        input = GalleryInputFormat.PNG,
                    ).ok()
            (img.width to img.height) shouldBe (1280 to 720)
            rgb(img = img, x = 100, y = 360) shouldBe BG
            rgb(img = img, x = 1180, y = 360) shouldBe BG
            rgb(img = img, x = 640, y = 360) shouldBe 0xFFFFFF // the white middle of the picture
        }

        test("a 16:9 picture fills the frame and a small one (640x360) is enlarged to it") {
            val full =
                PauseImageProcessor
                    .processBlocking(
                        bytes = MemberPhotoTestImages.png(marked(width = 1600, height = 900)),
                        input = GalleryInputFormat.PNG,
                    ).ok()
            rgb(img = full, x = 0, y = 360) shouldNotBe BG
            val small =
                PauseImageProcessor
                    .processBlocking(
                        bytes = MemberPhotoTestImages.png(marked(width = 640, height = 360)),
                        input = GalleryInputFormat.PNG,
                    ).ok()
            (small.width to small.height) shouldBe (1280 to 720)
            rgb(img = small, x = 640, y = 360) shouldBe 0xFFFFFF
        }

        test("below 640x360 and beyond the bomb limits are refused, the latter WITHOUT decoding") {
            PauseImageProcessor.processBlocking(
                bytes = MemberPhotoTestImages.png(marked(width = 639, height = 360)),
                input = GalleryInputFormat.PNG,
            ) shouldBe
                PauseImageResult.TooSmall
            PauseImageProcessor.processBlocking(
                bytes = MemberPhotoTestImages.png(marked(width = 640, height = 359)),
                input = GalleryInputFormat.PNG,
            ) shouldBe
                PauseImageResult.TooSmall
            // header-only files: not decodable at all, so a TooLarge answer proves the header guard ran first
            PauseImageProcessor.processBlocking(
                bytes = MemberPhotoTestImages.pngHeaderOnly(width = 9000, height = 9000),
                input = GalleryInputFormat.PNG,
            ) shouldBe
                PauseImageResult.DimensionsTooLarge
            PauseImageProcessor.processBlocking(
                bytes = MemberPhotoTestImages.pngHeaderOnly(width = 7000, height = 7000),
                input = GalleryInputFormat.PNG,
            ) shouldBe
                PauseImageResult.DimensionsTooLarge
        }

        test("a polyglot PNG+ZIP leaves no ZIP bytes behind, and metadata chunks are gone") {
            val png =
                MemberPhotoTestImages.pngWithMetadata(
                    png = MemberPhotoTestImages.png(marked(width = 800, height = 450)),
                    secret = "GPS-52.26-SECRET",
                )
            val zipTail = byteArrayOf(0x50, 0x4B, 0x03, 0x04) + "payload.txt".toByteArray() + ByteArray(30)
            val out =
                (
                    PauseImageProcessor.processBlocking(
                        bytes = png + zipTail,
                        input = GalleryInputFormat.PNG,
                    ) as PauseImageResult.Ok
                ).bytes
            String(out, Charsets.ISO_8859_1).contains("PK\u0003\u0004") shouldBe false
            String(out, Charsets.ISO_8859_1).contains("SECRET") shouldBe false
            String(out, Charsets.ISO_8859_1).contains("tEXt") shouldBe false
            String(out, Charsets.ISO_8859_1).contains("eXIf") shouldBe false
            // a fresh PNG: it starts with the signature and decodes
            out[0] shouldBe 0x89.toByte()
            ImageIO.read(ByteArrayInputStream(out)).width shouldBe 1280
        }

        test("JPEG with an EXIF rotation is turned upright, WebP and garbage are handled") {
            val upright =
                PauseImageProcessor.processBlocking(
                    bytes =
                        MemberPhotoTestImages.jpegWithExif(
                            jpeg = MemberPhotoTestImages.jpeg(marked(width = 900, height = 600)),
                            orientation = 6,
                        ),
                    input = GalleryInputFormat.JPEG,
                )
            // orientation 6 swaps the dimensions: 600x900 -> aspect 2:3, still accepted (>= 640 wide? 600 < 640 -> too small)
            upright shouldBe PauseImageResult.TooSmall
            val rotated =
                PauseImageProcessor
                    .processBlocking(
                        bytes =
                            MemberPhotoTestImages.jpegWithExif(
                                jpeg = MemberPhotoTestImages.jpeg(marked(width = 1200, height = 800)),
                                orientation = 6,
                            ),
                        input = GalleryInputFormat.JPEG,
                    ).ok()
            (rotated.width to rotated.height) shouldBe (1280 to 720)
            val webp = PauseImageProcessorTest::class.java.getResourceAsStream("/gallery/lossy.webp")!!.readBytes()
            PauseImageProcessor.sniff(webp) shouldBe GalleryInputFormat.WEBP
            PauseImageProcessor.processBlocking(bytes = ByteArray(64) { 7 }, input = GalleryInputFormat.PNG) shouldBe
                PauseImageResult.Undecodable
        }

        test("sniffing ignores everything but the first bytes") {
            PauseImageProcessor.sniff("<svg xmlns='http://www.w3.org/2000/svg'/>".toByteArray()) shouldBe null
            PauseImageProcessor.sniff("GIF89a....".toByteArray()) shouldBe null
        }
    })

private infix fun Int.shouldNotBe(other: Int) {
    (this != other) shouldBe true
}
