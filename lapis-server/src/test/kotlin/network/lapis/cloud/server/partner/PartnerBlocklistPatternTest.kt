package network.lapis.cloud.server.partner

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class PartnerBlocklistPatternTest :
    FunSpec({
        test("an exact host matches only itself") {
            PartnerBlocklistPattern.matches(pattern = "beispiel.de", host = "beispiel.de") shouldBe true
            PartnerBlocklistPattern.matches(pattern = "beispiel.de", host = "a.beispiel.de") shouldBe false
        }

        test("*.host matches a real sub-domain, never the host itself and never a longer look-alike") {
            PartnerBlocklistPattern.matches(pattern = "*.beispiel.de", host = "a.beispiel.de") shouldBe true
            PartnerBlocklistPattern.matches(pattern = "*.beispiel.de", host = "a.b.beispiel.de") shouldBe true
            PartnerBlocklistPattern.matches(pattern = "*.beispiel.de", host = "beispiel.de") shouldBe false
            PartnerBlocklistPattern.matches(pattern = "*.beispiel.de", host = "xbeispiel.de") shouldBe false
        }

        test("parse canonicalises and refuses other uses of the asterisk") {
            PartnerBlocklistPattern.parse(" Spam.Beispiel.de ") shouldBe "spam.beispiel.de"
            PartnerBlocklistPattern.parse("*.Beispiel.de") shouldBe "*.beispiel.de"
            PartnerBlocklistPattern.parse("https://spam.beispiel.de/x") shouldBe "spam.beispiel.de"
            PartnerBlocklistPattern.parse("a*.de") shouldBe null
            PartnerBlocklistPattern.parse("*") shouldBe null
            PartnerBlocklistPattern.parse("*.*.de") shouldBe null
            PartnerBlocklistPattern.parse("") shouldBe null
            PartnerBlocklistPattern.parse("127.0.0.1") shouldBe null
        }

        test("isBlocked looks at all patterns") {
            PartnerBlocklistPattern.isBlocked(host = "a.x.de", patterns = listOf("y.de", "*.x.de")) shouldBe true
            PartnerBlocklistPattern.isBlocked(host = "a.z.de", patterns = listOf("y.de", "*.x.de")) shouldBe false
        }
    })
