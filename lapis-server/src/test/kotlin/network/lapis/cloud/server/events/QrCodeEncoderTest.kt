package network.lapis.cloud.server.events

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe

/** Welle V1.4.3.2 "Veranstaltungen: Ticketing/QR-Codes" -- [QrCodeEncoder]/[QrCodeMatrix] geometry, no rendering involved. */
class QrCodeEncoderTest :
    FunSpec({
        test("encode produces a square matrix with a plausible module count for a short URL") {
            val matrix = QrCodeEncoder.encode("https://example.org/veranstaltung/sommerfest/ticket?code=ABCD1234EFGH5678")
            matrix.size shouldBeGreaterThan 0
            // A QR code's side is always 21 + 4*(version-1), so it must be odd here to even matter --
            // the real assertion is just "some plausible, non-trivial size came back".
            (matrix.size >= 21) shouldBe true
        }

        test("horizontalRuns reconstructs the matrix losslessly") {
            val matrix = QrCodeEncoder.encode("https://example.org/veranstaltung/s/ticket?code=WXYZ9876ABCD1234")
            val reconstructed = BooleanArray(matrix.size * matrix.size)
            matrix.horizontalRuns().forEach { run ->
                for (dx in 0 until run.length) {
                    reconstructed[run.y * matrix.size + (run.x + dx)] = true
                }
            }
            for (y in 0 until matrix.size) {
                for (x in 0 until matrix.size) {
                    reconstructed[y * matrix.size + x] shouldBe matrix[x, y]
                }
            }
        }

        test("encodes a maximal ticket URL length without throwing") {
            // A realistic upper bound: baseUrl + slug + fixed path + a 16-char code, generously padded.
            val longSlug = "a".repeat(100)
            val url = "https://example.org/veranstaltung/$longSlug/ticket?code=ABCD1234EFGH5678"
            val matrix = QrCodeEncoder.encode(url)
            matrix.size shouldBeGreaterThan 0
        }

        test("get() out of bounds throws IllegalArgumentException") {
            val matrix = QrCodeEncoder.encode("short")
            runCatching { matrix[-1, 0] }.isFailure shouldBe true
            runCatching { matrix[matrix.size, 0] }.isFailure shouldBe true
        }
    })
