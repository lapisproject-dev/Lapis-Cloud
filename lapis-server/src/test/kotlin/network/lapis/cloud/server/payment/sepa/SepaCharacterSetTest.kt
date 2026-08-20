package network.lapis.cloud.server.payment.sepa

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/** Pure tests of [SepaCharacterSet] -- no DB access anywhere in this file. */
class SepaCharacterSetTest :
    FunSpec({
        test("German umlauts and sharp-s are transliterated per German banking convention") {
            SepaCharacterSet.transliterate("Müller") shouldBe "Mueller"
            SepaCharacterSet.transliterate("Straße") shouldBe "Strasse"
            SepaCharacterSet.transliterate("Björn") shouldBe "Bjoern"
            SepaCharacterSet.transliterate("Ärger") shouldBe "Aerger"
        }

        test("accented Latin characters are stripped to their base letter") {
            SepaCharacterSet.transliterate("Café") shouldBe "Cafe"
            SepaCharacterSet.transliterate("Øystein") shouldBe ".ystein"
        }

        test("an unmappable character becomes a single dot") {
            SepaCharacterSet.transliterate("A€B") shouldBe "A.B"
        }

        test("sanitize collapses repeated whitespace, trims, and truncates") {
            SepaCharacterSet.sanitize(raw = "  Hello   World  ", maxLength = 100) shouldBe "Hello World"
            SepaCharacterSet.sanitize(raw = "Hello World", maxLength = 5) shouldBe "Hello"
        }

        test("transliteration happens before truncation so growth from umlaut expansion is respected") {
            // "ü" -> "ue" grows the string by one character per umlaut ("üüüüü" -> "ueueueueue", 10 chars).
            SepaCharacterSet.sanitize(raw = "üüüüü", maxLength = 6) shouldBe "ueueue"
        }

        test("isSepaSafe recognizes only the SEPA basic character set") {
            SepaCharacterSet.isSepaSafe("Hello World 123 / - ? : ( ) . , ' +") shouldBe true
            SepaCharacterSet.isSepaSafe("Müller") shouldBe false
            SepaCharacterSet.isSepaSafe("A€B") shouldBe false
        }
    })
