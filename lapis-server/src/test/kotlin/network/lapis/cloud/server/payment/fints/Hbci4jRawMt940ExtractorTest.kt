package network.lapis.cloud.server.payment.fints

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.kapott.hbci.GV_Result.GVRKUms

/**
 * Resolves the plan's blocking open question OQ-1 with a REAL (unmodified, no fake, no mock)
 * `org.kapott.hbci.GV_Result.GVRKUms` instance -- see [Hbci4jRawMt940Extractor] KDoc for the full
 * live-bytecode verification this class's design rests on. If a future `hbci4j-core` version
 * renames/retypes the private `bufferMT940` field, THIS test fails loudly instead of a production
 * account silently receiving empty statement files.
 */
class Hbci4jRawMt940ExtractorTest :
    FunSpec({
        test("extract() returns exactly the bytes appended via appendMT940Data, UTF-8 encoded") {
            val result = GVRKUms()
            val raw = ":20:STARTUMS\r\n:25:12345678/1234567890\r\n:28C:1/1\r\n-\r\n"
            result.appendMT940Data(raw)

            Hbci4jRawMt940Extractor.extract(result) shouldBe raw.toByteArray(Charsets.UTF_8)
        }

        test("extract() is stable across multiple appendMT940Data calls (accumulates, does not overwrite)") {
            val result = GVRKUms()
            result.appendMT940Data(":20:PART1\r\n")
            result.appendMT940Data(":20:PART2\r\n")

            Hbci4jRawMt940Extractor.extract(result) shouldBe ":20:PART1\r\n:20:PART2\r\n".toByteArray(Charsets.UTF_8)
        }

        test("extract() on a fresh (never appended-to) GVRKUms returns an empty byte array, not null/throw") {
            val result = GVRKUms()
            Hbci4jRawMt940Extractor.extract(result) shouldBe ByteArray(0)
        }

        test("extract() preserves German umlauts (UTF-8 round-trip, no double-escaping)") {
            val result = GVRKUms()
            val raw = "Übertrag: Müller GmbH, Straße 5"
            result.appendMT940Data(raw)

            Hbci4jRawMt940Extractor.extract(result) shouldBe raw.toByteArray(Charsets.UTF_8)
        }
    })
