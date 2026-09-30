package network.lapis.cloud.server.events

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import network.lapis.cloud.server.images.SvgCrestSanitizer

/**
 * V1.9.21 tripwire: SVG is accepted ONLY for the regional chapter crest. The shared raster pipeline (event
 * covers, article covers, member photos) must stay raster-only.
 */
class CoverImageFormatTripwireTest :
    FunSpec({
        test("CoverImageFormat stays JPEG/PNG and the raster sniffer does not recognise an SVG") {
            CoverImageFormat.entries shouldBe listOf(CoverImageFormat.JPEG, CoverImageFormat.PNG)
            val svg = "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 1 1\"/>".toByteArray()
            EventCoverImageProcessor.sniff(svg) shouldBe null
            SvgCrestSanitizer.isCandidate(svg) shouldBe true
        }
    })
