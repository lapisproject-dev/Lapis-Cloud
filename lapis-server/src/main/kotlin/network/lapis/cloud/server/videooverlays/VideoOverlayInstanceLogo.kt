package network.lapis.cloud.server.videooverlays

import io.github.oshai.kotlinlogging.KotlinLogging
import network.lapis.cloud.server.branding.ResolvedBranding
import network.lapis.cloud.shared.domain.VideoOverlayItemDto
import network.lapis.cloud.shared.domain.VideoOverlayKind
import network.lapis.cloud.shared.domain.VideoOverlayRules
import java.io.File
import javax.imageio.ImageIO

private val logger = KotlinLogging.logger {}

/**
 * Welle V1.9.129 -- whether the operator's branding logo (`LAPIS_BRAND_LOGO_PATH`, served at `/api/branding/logo`) is offered as the first
 * element of the member overlay library. Only a raster logo qualifies: **PNG or WebP**. An SVG is never offered (it is not drawn onto a
 * canvas -- same ruling as for the library itself), and JPEG cannot be a branding logo at all (`BrandConfig` allows svg, png and webp).
 *
 * The decision and the pixel size are made ONCE, at startup, from the already-validated [ResolvedBranding]; the file is read only for its
 * PNG header. A WebP has no ImageIO reader in the JDK, so its size is reported as `0x0` and the client uses the decoded bitmap's own size.
 */
internal class VideoOverlayInstanceLogo private constructor(
    /** The synthetic library element, or `null` when the logo is not offered. */
    val item: VideoOverlayItemDto?,
    /** `"svg"` or `"none"` when [item] is `null`, otherwise `null`. */
    val reason: String?,
) {
    companion object {
        const val REASON_SVG = "svg"
        const val REASON_NONE = "none"
        private const val LOGO_ROUTE = "/api/branding/logo"

        fun none(): VideoOverlayInstanceLogo = VideoOverlayInstanceLogo(item = null, reason = REASON_NONE)

        fun resolve(branding: ResolvedBranding): VideoOverlayInstanceLogo {
            val path = branding.logoPath
            if (!branding.logoAvailable || path == null) return none()
            return when (path.substringAfterLast('.', missingDelimiterValue = "").lowercase()) {
                "svg" -> VideoOverlayInstanceLogo(item = null, reason = REASON_SVG)
                "png" -> offered(branding = branding, size = pngSize(File(path)))
                "webp" -> offered(branding = branding, size = 0 to 0)
                else -> none()
            }
        }

        private fun offered(
            branding: ResolvedBranding,
            size: Pair<Int, Int>,
        ): VideoOverlayInstanceLogo {
            val name =
                branding.title
                    .trim()
                    .take(VideoOverlayRules.NAME_MAX)
                    .ifBlank { "Logo" }
            return VideoOverlayInstanceLogo(
                item =
                    VideoOverlayItemDto(
                        id = VideoOverlayRules.INSTANCE_LOGO_ID,
                        kind = VideoOverlayKind.LOGO,
                        name = name,
                        width = size.first,
                        height = size.second,
                        imagePath = LOGO_ROUTE,
                        instanceLogo = true,
                    ),
                reason = null,
            )
        }

        /** Header-only read (no pixel is decoded); any failure is `0x0`. */
        private fun pngSize(file: File): Pair<Int, Int> =
            runCatching {
                ImageIO.createImageInputStream(file).use { input ->
                    val readers = ImageIO.getImageReaders(input)
                    if (!readers.hasNext()) return@use 0 to 0
                    val reader = readers.next()
                    try {
                        reader.setInput(input, true, true)
                        reader.getWidth(0) to reader.getHeight(0)
                    } finally {
                        reader.dispose()
                    }
                }
            }.getOrElse { e ->
                logger.info { "Instance logo size not readable: ${e::class.simpleName}" }
                0 to 0
            }
    }
}
