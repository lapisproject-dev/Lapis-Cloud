package network.lapis.cloud.server.videooverlays

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import network.lapis.cloud.server.db.SourceScan

/**
 * Welle V1.9.129 "Video-Overlays" -- tripwires of the overlay library:
 *
 *  1. `video_overlay_asset` is read and written ONLY through [VideoOverlayStore] (plus its table object): nobody else may touch the
 *     Exposed table, so there is no second writer that could skip the audit entry or the library lock.
 *  2. A log line of the overlay code never names an overlay name, an access token, a hash or a member.
 *  3. The delivery route never sets a CORS header (the client loads the image same-origin with `fetch`; a CORS grant would be exactly what
 *     a non-public organization library must not have) and never serves an SVG.
 *  4. The overlay code never uses the SVG sanitizer or accepts an SVG extension.
 */
class VideoOverlayStoreTripwireTest :
    FunSpec({
        val files = SourceScan.mainFiles()
        val overlayFiles =
            files.filter { it.name.startsWith("VideoOverlay") && !it.name.endsWith("Table.kt") }

        test("the scanner really sees the main sources (guards against a silently empty scan)") {
            (files.size > 100) shouldBe true
            overlayFiles.map { it.name }.toSet() shouldBe
                setOf(
                    "VideoOverlayRoutes.kt",
                    "VideoOverlayService.kt",
                    "VideoOverlayStore.kt",
                    "VideoOverlayStorage.kt",
                    "VideoOverlayImagePolicy.kt",
                    "VideoOverlayInstanceLogo.kt",
                )
        }

        test("only the store and the table object use the Exposed table") {
            val users =
                files
                    .filter { "VideoOverlayAssetTable" in SourceScan.blank(it.readText()) }
                    .map { it.name }
                    .toSet()
            users shouldBe setOf("VideoOverlayStore.kt", "VideoOverlayAssetTable.kt")
        }

        test("log lines of the overlay code never name an overlay name, a token, a hash or a member") {
            val offenders =
                overlayFiles.flatMap { file ->
                    file.readLines().filter { "logger." in it }.filter {
                        Regex("(?i)\\\$\\{?(name|token|sha|member|actor|current)").containsMatchIn(it)
                    }
                }
            offenders.shouldBeEmpty()
        }

        test("the delivery route sets no CORS header and the overlay code knows no SVG") {
            val routes = overlayFiles.single { it.name == "VideoOverlayRoutes.kt" }.readText()
            val code = SourceScan.blank(routes)
            ("Access-Control" in code) shouldBe false
            ("applyEmbedCors" in code) shouldBe false
            overlayFiles.forEach { file ->
                val blanked = SourceScan.blank(file.readText())
                ("SvgCrestSanitizer" in blanked) shouldBe false
                ("image/svg" in blanked) shouldBe false
            }
        }
    })
