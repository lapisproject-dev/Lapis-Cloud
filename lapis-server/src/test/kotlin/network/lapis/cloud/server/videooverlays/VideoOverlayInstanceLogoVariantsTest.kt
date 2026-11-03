package network.lapis.cloud.server.videooverlays

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import network.lapis.cloud.server.branding.LogoVariant
import network.lapis.cloud.server.branding.ResolvedBranding
import network.lapis.cloud.server.branding.ResolvedLogoFile
import java.awt.image.BufferedImage
import java.io.File
import java.nio.file.Files
import javax.imageio.ImageIO

/** V1.9.265 -- an instance that configured only logo variants still offers a raster logo to the video overlays (never an SVG). */
class VideoOverlayInstanceLogoVariantsTest :
    FunSpec({
        val dir = Files.createTempDirectory("overlay-logo-variants").toFile().also { it.deleteOnExit() }

        fun onlySignetLight(file: File) =
            ResolvedBranding(
                title = "Mein Verein",
                logoAvailable = false,
                logoPath = null,
                variants = mapOf(LogoVariant.SIGNET_LIGHT to ResolvedLogoFile(path = file.path, extension = file.extension.lowercase())),
            )

        test("only SIGNET_LIGHT as PNG -> offered, served at the legacy logo route, with the PNG size") {
            val png = File(dir, "s.png")
            ImageIO.write(BufferedImage(120, 40, BufferedImage.TYPE_INT_ARGB), "png", png)
            val logo = VideoOverlayInstanceLogo.resolve(onlySignetLight(png))
            logo.reason shouldBe null
            logo.item!!.imagePath shouldBe "/api/branding/logo"
            logo.item!!.width shouldBe 120
            logo.item!!.height shouldBe 40
            logo.item!!.name shouldBe "Mein Verein"
        }

        test("only SIGNET_LIGHT as SVG -> not offered, reason svg") {
            val svg = File(dir, "s.svg").also { it.writeText("<svg xmlns=\"http://www.w3.org/2000/svg\"/>") }
            val logo = VideoOverlayInstanceLogo.resolve(onlySignetLight(svg))
            logo.item shouldBe null
            logo.reason shouldBe VideoOverlayInstanceLogo.REASON_SVG
        }

        test("only a dark wordmark (WEBP) -> offered through the fallback chain, size unknown (0x0)") {
            val webp = File(dir, "w.webp").also { it.writeBytes(ByteArray(40)) }
            val brand =
                ResolvedBranding(
                    title = "T",
                    logoAvailable = false,
                    logoPath = null,
                    variants = mapOf(LogoVariant.WORDMARK_DARK to ResolvedLogoFile(path = webp.path, extension = "webp")),
                )
            VideoOverlayInstanceLogo.resolve(brand).item!!.width shouldBe 0
        }

        test("nothing configured -> none") {
            VideoOverlayInstanceLogo.resolve(ResolvedBranding(title = "T", logoAvailable = false, logoPath = null)).reason shouldBe
                VideoOverlayInstanceLogo.REASON_NONE
        }
    })
