package network.lapis.cloud.server.ai.safety

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain

class PiiRedactorTest :
    FunSpec({
        test("an e-mail address is redacted") {
            val result = PiiRedactor.redact("Bitte an max.muster@example.org senden")
            result.text shouldBe "Bitte an [EMAIL] senden"
            result.redactionCount shouldBe 1
        }

        test("an IBAN, with and without spaces, is redacted") {
            PiiRedactor.redact("Konto DE89370400440532013000 bitte").text shouldContain "[IBAN]"
            PiiRedactor.redact("Konto DE89 3704 0044 0532 0130 00 bitte").text shouldContain "[IBAN]"
            PiiRedactor.redact("Konto DE89370400440532013000 bitte").text shouldNotContain "3704"
        }

        test("telephone numbers are redacted") {
            PiiRedactor.redact("Rufen Sie +49 531 1234567 an").text shouldContain "[PHONE]"
            PiiRedactor.redact("Tel. 0531/1234567").text shouldContain "[PHONE]"
        }

        test("a long digit run is redacted") {
            PiiRedactor.redact("Mitgliedsnummer 1234567890123").text shouldContain "[NUMBER]"
        }

        test("statute references and other short numbers are NOT redacted") {
            listOf(
                "Was regelt § 12 Abs. 3 der Satzung?",
                "Was steht in Art. 5?",
                "Gilt das seit 2026?",
                "Ab Version v2 der Satzung",
                "Nach § 7 Absatz 2 Satz 1",
                "Frist von 14 Tagen und 30 Tagen",
                "Art. 12 Abs. 4 und § 123",
                "Was hat sich in der Satzungsfassung 2024-2025 beim Beitrag geändert?",
                "Beitrag 2026 2027",
                "Geschäftsjahr 2024/2025",
                "Was steht in der Beitragsordnung (2024-2025) zur Fälligkeit?",
                "Beitrag (2026 2027)",
                "siehe (2024/2025)",
            ).forEach { question ->
                val result = PiiRedactor.redact(question)
                result.text shouldBe question
                result.redactionCount shouldBe 0
            }
        }

        test("several patterns in one text are all counted") {
            val result = PiiRedactor.redact("a@b.de und c@d.org sowie DE89370400440532013000")
            result.redactionCount shouldBe 3
        }

        test("text without personal identifiers passes through unchanged") {
            PiiRedactor.redact("Wie hoch ist der Mitgliedsbeitrag für Fördermitglieder?").redactionCount shouldBe 0
        }
    })
