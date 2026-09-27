package network.lapis.cloud.server.conference

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.longs.shouldBeLessThanOrEqual
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import network.lapis.cloud.shared.domain.ConferenceBackgroundRules
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import javax.imageio.ImageIO
import javax.imageio.ImageReadParam
import javax.imageio.ImageReader

private fun fixture(name: String): ByteArray =
    requireNotNull(
        Thread.currentThread().contextClassLoader.getResourceAsStream("conference-backgrounds/$name"),
    ) { "Fixture not found on classpath: conference-backgrounds/$name -- see that directory's README.adoc" }
        .use { it.readBytes() }

/**
 * Welle V1.9.4 "private Hintergrundbild-Uploads für Videokonferenzen". See
 * [ConferenceBackgroundImageProcessor] class KDoc for the numbered security rationale each test
 * below is named after, and `src/test/resources/conference-backgrounds/README.adoc` for how every
 * fixture was generated (no `exiftool` in this environment -- Python 3 + Pillow/ImageMagick
 * instead).
 */
class ConferenceBackgroundImageProcessorTest :
    FunSpec({

        test("ordinary landscape JPEG is downscaled to at most MAX_OUTPUT_LONG_EDGE_PX and never upscaled") {
            val processor = ConferenceBackgroundImageProcessor()
            val outcome = processor.process(bytes = fixture("landscape.jpg"), sniffedMime = "image/jpeg")
            outcome.shouldBeOk { ok ->
                ok.width shouldBe 1920 // 2400x1600 -> long edge clamped to 1920
                ok.height shouldBe 1280
                maxOf(ok.width, ok.height) shouldBe ConferenceBackgroundRules.MAX_OUTPUT_LONG_EDGE_PX
            }
        }

        test("ordinary portrait PNG within bounds is not upscaled") {
            val processor = ConferenceBackgroundImageProcessor()
            val outcome = processor.process(bytes = fixture("portrait.png"), sniffedMime = "image/png")
            outcome.shouldBeOk { ok ->
                ok.width shouldBe 600
                ok.height shouldBe 1200
            }
        }

        test("thumbnail is always exactly THUMB_WIDTH_PX x THUMB_HEIGHT_PX") {
            val processor = ConferenceBackgroundImageProcessor()
            val outcome = processor.process(bytes = fixture("landscape.jpg"), sniffedMime = "image/jpeg")
            outcome.shouldBeOk { ok ->
                val thumbImage = ImageIO.read(ByteArrayInputStream(ok.thumb))
                thumbImage.width shouldBe ConferenceBackgroundRules.THUMB_WIDTH_PX
                thumbImage.height shouldBe ConferenceBackgroundRules.THUMB_HEIGHT_PX
            }
        }

        test("too-small image (both sides below MIN_SIDE_PX) is rejected as DimensionsOutOfRange") {
            val processor = ConferenceBackgroundImageProcessor()
            processor.process(bytes = fixture("too-small.png"), sniffedMime = "image/png") shouldBe
                BackgroundImageOutcome.DimensionsOutOfRange
        }

        test(
            "decompression-bomb PNG (IHDR 20000x20000, tiny truncated IDAT) is rejected on dimensions " +
                "ALONE -- decode() is never called",
        ) {
            var decodeCalls = 0
            val processor =
                ConferenceBackgroundImageProcessor(
                    decode = { reader: ImageReader, param: ImageReadParam ->
                        decodeCalls++
                        reader.read(0, param)
                    },
                )
            val outcome = processor.process(bytes = fixture("bomb.png"), sniffedMime = "image/png")
            outcome shouldBe BackgroundImageOutcome.DimensionsOutOfRange
            decodeCalls shouldBe 0
        }

        test(
            "progressive-JPEG scan-bomb (legit 4096x4096 progressive JPEG with its smallest scan " +
                "duplicated 40x) is rejected on the SOS-count gate ALONE -- decode() is never called",
        ) {
            var decodeCalls = 0
            val processor =
                ConferenceBackgroundImageProcessor(
                    decode = { reader: ImageReader, param: ImageReadParam ->
                        decodeCalls++
                        reader.read(0, param)
                    },
                )
            val outcome = processor.process(bytes = fixture("scan-bomb.jpg"), sniffedMime = "image/jpeg")
            outcome shouldBe BackgroundImageOutcome.Undecodable
            decodeCalls shouldBe 0
        }

        test(
            "Security-Audit regression (2026-09-27): a single extraneous 0x00 byte spliced in right " +
                "before the scan-bomb's first SOS marker must NOT bypass the SOS-count gate -- decode() " +
                "is still never called",
        ) {
            var decodeCalls = 0
            val processor =
                ConferenceBackgroundImageProcessor(
                    decode = { reader: ImageReader, param: ImageReadParam ->
                        decodeCalls++
                        reader.read(0, param)
                    },
                )
            val outcome = processor.process(bytes = fixture("scan-bomb-extraneous-byte.jpg"), sniffedMime = "image/jpeg")
            outcome shouldBe BackgroundImageOutcome.Undecodable
            decodeCalls shouldBe 0
        }

        context("Security-Audit regression (2026-09-27, round 3): scan-bomb gate bypass via a bogus 2-byte length -- process()-level") {
            // process()-level counterpart to the jpegScanCountExceeds(...) pure-function assertions
            // below -- proves the gate rejects each bypass fixture BEFORE decode() is ever invoked,
            // the same decodeCalls == 0 proof used for the round-1 and round-2 scan-bomb regressions.
            fun assertRejectedBeforeDecode(fixtureName: String) {
                var decodeCalls = 0
                val processor =
                    ConferenceBackgroundImageProcessor(
                        decode = { reader: ImageReader, param: ImageReadParam ->
                            decodeCalls++
                            reader.read(0, param)
                        },
                    )
                val outcome = processor.process(bytes = fixture(fixtureName), sniffedMime = "image/jpeg")
                outcome shouldBe BackgroundImageOutcome.Undecodable
                decodeCalls shouldBe 0
            }

            test("COM marker (0xFFFE) with a bogus 0 length must not bypass the SOS-count gate") {
                assertRejectedBeforeDecode("scan-bomb-bogus-length-com.jpg")
            }

            test("APP1 marker (0xFFE1) with a bogus 1 length must not bypass the SOS-count gate") {
                assertRejectedBeforeDecode("scan-bomb-bogus-length-app1.jpg")
            }

            test("0xFF 0x00 at a marker position, followed by a bogus 0 length, must not bypass the SOS-count gate") {
                assertRejectedBeforeDecode("scan-bomb-bogus-length-ff00.jpg")
            }
        }

        test("garbage bytes after valid JPEG magic bytes are Undecodable") {
            val processor = ConferenceBackgroundImageProcessor()
            processor.process(bytes = fixture("garbage-after-magic.jpg"), sniffedMime = "image/jpeg") shouldBe
                BackgroundImageOutcome.Undecodable
        }

        test("random bytes with no valid magic bytes are Undecodable") {
            val processor = ConferenceBackgroundImageProcessor()
            processor.process(bytes = fixture("random-bytes.bin"), sniffedMime = "image/jpeg") shouldBe BackgroundImageOutcome.Undecodable
            processor.process(bytes = fixture("random-bytes.bin"), sniffedMime = "image/png") shouldBe BackgroundImageOutcome.Undecodable
        }

        test("PNG with a fully-transparent RGBA fill is flattened onto the #202020 canvas, not left as garbage/pink") {
            val processor = ConferenceBackgroundImageProcessor()
            val outcome = processor.process(bytes = fixture("alpha.png"), sniffedMime = "image/png")
            outcome.shouldBeOk { ok ->
                val decoded: BufferedImage = ImageIO.read(ByteArrayInputStream(ok.main))
                val centerPixel = decoded.getRGB(decoded.width / 2, decoded.height / 2) and 0xFFFFFF
                centerPixel shouldBe 0x202020
            }
        }

        context("EXIF/XMP/ICC stripping") {
            test(
                "fixture exif-gps-xmp.jpg genuinely carries EXIF, GPS-shaped data, XMP and an ICC profile (sanity check on the fixture itself)",
            ) {
                val source = fixture("exif-gps-xmp.jpg")
                val sourceText = source.toString(Charsets.ISO_8859_1)
                sourceText shouldContainMarker "Exif\u0000\u0000"
                sourceText shouldContainMarker "ns.adobe.com/xap"
                sourceText shouldContainMarker "ICC_PROFILE"
            }

            test("processed JPEG output carries no EXIF/GPS/XMP/ICC segment") {
                val processor = ConferenceBackgroundImageProcessor()
                val outcome = processor.process(bytes = fixture("exif-gps-xmp.jpg"), sniffedMime = "image/jpeg")
                outcome.shouldBeOk { ok ->
                    val outputText = ok.main.toString(Charsets.ISO_8859_1)
                    outputText.shouldNotContain("Exif\u0000\u0000")
                    outputText.shouldNotContain("ns.adobe.com/xap")
                    outputText.shouldNotContain("ICC_PROFILE")
                    // No APP1 (0xFFE1) or APP2 (0xFFE2) marker anywhere in the re-encoded stream.
                    ok.main.containsMarker(0xFF, 0xE1) shouldBe false
                    ok.main.containsMarker(0xFF, 0xE2) shouldBe false
                }
            }

            test("fixture exif-png.png genuinely carries an eXIf chunk and an iTXt XMP chunk (sanity check on the fixture itself)") {
                val source = fixture("exif-png.png")
                val sourceText = source.toString(Charsets.ISO_8859_1)
                sourceText shouldContainMarker "eXIf"
                sourceText shouldContainMarker "iTXt"
                sourceText shouldContainMarker "ns.adobe.com/xap"
            }

            test("PNG source's EXIF/XMP does not survive re-encoding either (output is always JPEG)") {
                val processor = ConferenceBackgroundImageProcessor()
                val outcome = processor.process(bytes = fixture("exif-png.png"), sniffedMime = "image/png")
                outcome.shouldBeOk { ok ->
                    val outputText = ok.main.toString(Charsets.ISO_8859_1)
                    outputText.shouldNotContain("ns.adobe.com/xap")
                    // Output is JPEG regardless of the PNG input -- JPEG SOI magic bytes.
                    (ok.main[0].toInt() and 0xFF) shouldBe 0xFF
                    (ok.main[1].toInt() and 0xFF) shouldBe 0xD8
                }
            }
        }

        test("polyglot JPEG (HTML + fake ZIP local-file-header appended after EOI) decodes, and neither payload survives re-encoding") {
            val processor = ConferenceBackgroundImageProcessor()
            val outcome = processor.process(bytes = fixture("polyglot.jpg"), sniffedMime = "image/jpeg")
            outcome.shouldBeOk { ok ->
                val outputText = ok.main.toString(Charsets.ISO_8859_1)
                outputText.shouldNotContain("<html")
                outputText.shouldNotContain("PK\u0003\u0004")
            }
        }

        test("checksum is computed over the RE-ENCODED main image bytes, not the original upload") {
            val processor = ConferenceBackgroundImageProcessor()
            val outcome = processor.process(bytes = fixture("landscape.jpg"), sniffedMime = "image/jpeg")
            outcome.shouldBeOk { ok ->
                val digest = java.security.MessageDigest.getInstance("SHA-256")
                val expected = digest.digest(ok.main).joinToString("") { "%02x".format(it.toInt() and 0xFF) }
                ok.sha256Hex shouldBe expected
                ok.sha256Hex.length shouldBe 64
            }
        }

        test("output byte size respects MAX_UPLOAD_BYTES-scale expectations for a solid-fill test image") {
            val processor = ConferenceBackgroundImageProcessor()
            val outcome = processor.process(bytes = fixture("landscape.jpg"), sniffedMime = "image/jpeg")
            outcome.shouldBeOk { ok ->
                ok.main.size.toLong() shouldBeLessThanOrEqual ConferenceBackgroundRules.MAX_UPLOAD_BYTES
            }
        }

        context("jpegScanCountExceeds (pure)") {
            // Mirrors the private MAX_JPEG_SCANS in ConferenceBackgroundImageProcessor's companion
            // object -- kept as a literal here since that constant is private to the production class.
            val productionLimit = 32

            test("an ordinary baseline JPEG (single scan) never exceeds any realistic limit") {
                jpegScanCountExceeds(bytes = fixture("landscape.jpg"), limit = productionLimit) shouldBe false
                // Sanity check: it DOES find the one real scan.
                jpegScanCountExceeds(bytes = fixture("landscape.jpg"), limit = 0) shouldBe true
            }

            test("the scan-bomb fixture (50 SOS markers) exceeds the production limit (32)") {
                jpegScanCountExceeds(bytes = fixture("scan-bomb.jpg"), limit = productionLimit) shouldBe true
            }

            test(
                "Security-Audit regression (2026-09-27): a single extraneous 0x00 byte before the first " +
                    "SOS marker must not make the walk bail out and under-report the true scan count",
            ) {
                // The exact bypass fixture: scan-bomb.jpg (50 SOS markers) with one 0x00 spliced in
                // right before its first FF DA. Before the fix, this made jpegScanCountExceeds return
                // `false` for ANY limit -- the walk hit the stray byte at the very first marker boundary
                // after SOI and bailed out before counting a single scan.
                jpegScanCountExceeds(bytes = fixture("scan-bomb-extraneous-byte.jpg"), limit = productionLimit) shouldBe true
                jpegScanCountExceeds(bytes = fixture("scan-bomb-extraneous-byte.jpg"), limit = 0) shouldBe true
            }

            test("an extraneous non-0xFF byte anywhere between markers is skipped, not treated as fatal") {
                // Minimal synthetic case, independent of the fixture: SOI, then a stray 0x00 byte, then
                // two bare SOS markers (each with a 2-byte "no extra header" length field and no entropy
                // data), then EOI. The stray byte must be skipped so both SOS markers are still counted.
                val bytes =
                    byteArrayOf(
                        // SOI
                        0xFF.toByte(),
                        0xD8.toByte(),
                        // extraneous byte -- must be skipped, not treated as end-of-walk
                        0x00,
                        // SOS #1, header length 2 (no params)
                        0xFF.toByte(),
                        0xDA.toByte(),
                        0x00,
                        0x02,
                        // SOS #2, header length 2 (no params)
                        0xFF.toByte(),
                        0xDA.toByte(),
                        0x00,
                        0x02,
                        // EOI
                        0xFF.toByte(),
                        0xD9.toByte(),
                    )
                jpegScanCountExceeds(bytes = bytes, limit = 1) shouldBe true // finds both SOS markers -> 2 > 1
                jpegScanCountExceeds(bytes = bytes, limit = 2) shouldBe false // exactly 2, not exceeding
            }

            test("bytes without a valid SOI (or too short to have one) never exceed -- left for the real decoder to judge") {
                jpegScanCountExceeds(bytes = fixture("random-bytes.bin"), limit = 1) shouldBe false
                jpegScanCountExceeds(bytes = ByteArray(0), limit = 1) shouldBe false
                jpegScanCountExceeds(bytes = byteArrayOf(0xFF.toByte(), 0xD8.toByte()), limit = 1) shouldBe false
            }

            test(
                "Security-Audit regression (2026-09-27, round 3): malformed marker structure AFTER a " +
                    "valid SOI now fails CLOSED (exceeds) rather than falling back to \"let decode() " +
                    "judge it\" -- garbage-after-magic.jpg's APP0 segment has a bogus 0-length that the " +
                    "OLD implementation used as an excuse to stop the walk and return false",
            ) {
                jpegScanCountExceeds(bytes = fixture("garbage-after-magic.jpg"), limit = 1) shouldBe true
                jpegScanCountExceeds(bytes = fixture("garbage-after-magic.jpg"), limit = Int.MAX_VALUE) shouldBe true
            }

            context("Security-Audit regression (2026-09-27, round 3): scan-bomb gate bypass via a bogus 2-byte length") {
                // Each fixture is scan-bomb.jpg (50 SOS markers) with one of the three bypass sequences
                // spliced in right before its first SOS, followed by 500 more copies of its smallest
                // scan (550 scans total) -- see README.adoc "scan-bomb-bogus-length-*.jpg" and the
                // ConferenceBackgroundImageProcessor.jpegScanCountExceeds KDoc "scan-bomb gate still
                // bypassable via a bogus 2-byte length" for the exact reproduction and root cause. The
                // OLD implementation returned `false` (not exceeding) for all three, regardless of
                // `limit`, because it gave up on the marker walk the instant it hit the bogus length.

                test("COM marker (0xFFFE) with a bogus 0 length") {
                    jpegScanCountExceeds(bytes = fixture("scan-bomb-bogus-length-com.jpg"), limit = productionLimit) shouldBe true
                }

                test("APP1 marker (0xFFE1) with a bogus 1 length") {
                    jpegScanCountExceeds(bytes = fixture("scan-bomb-bogus-length-app1.jpg"), limit = productionLimit) shouldBe true
                }

                test("0xFF 0x00 (byte-stuffing syntax) at a marker position, followed by a bogus 0 length") {
                    jpegScanCountExceeds(bytes = fixture("scan-bomb-bogus-length-ff00.jpg"), limit = productionLimit) shouldBe true
                }
            }

            test("a polyglot JPEG's trailing HTML/ZIP bytes after EOI are never walked -- the walk stops at EOI") {
                jpegScanCountExceeds(bytes = fixture("polyglot.jpg"), limit = 0) shouldBe true // still finds its one real scan...
                jpegScanCountExceeds(bytes = fixture("polyglot.jpg"), limit = 1) shouldBe false // ...but never more than that one.
            }
        }

        context("backgroundTargetSize (pure)") {
            test("shrinks the long edge to maxLongEdge, preserving aspect ratio") {
                backgroundTargetSize(width = 4000, height = 2000, maxLongEdge = 1920) shouldBe (1920 to 960)
                backgroundTargetSize(width = 2000, height = 4000, maxLongEdge = 1920) shouldBe (960 to 1920)
            }

            test("never upscales an image already within bounds") {
                backgroundTargetSize(width = 800, height = 600, maxLongEdge = 1920) shouldBe (800 to 600)
                backgroundTargetSize(width = 1920, height = 1080, maxLongEdge = 1920) shouldBe (1920 to 1080)
            }
        }
    })

private infix fun String.shouldContainMarker(marker: String) {
    require(this.contains(marker)) { "Fixture sanity check failed: expected to find '$marker' in the fixture bytes, but it was not there." }
}

private fun ByteArray.containsMarker(vararg bytes: Int): Boolean {
    if (bytes.isEmpty() || size < bytes.size) return false
    outer@ for (start in 0..(size - bytes.size)) {
        for (i in bytes.indices) {
            if ((this[start + i].toInt() and 0xFF) != bytes[i]) continue@outer
        }
        return true
    }
    return false
}

private inline fun BackgroundImageOutcome.shouldBeOk(block: (BackgroundImageOutcome.Ok) -> Unit) {
    val ok = this as? BackgroundImageOutcome.Ok ?: error("Expected BackgroundImageOutcome.Ok but was $this")
    block(ok)
}
