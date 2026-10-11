package network.lapis.cloud.server.articles

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import network.lapis.cloud.shared.rpc.BadRequestException

/** Welle V1.9.105 -- pure rules for the author line: normalization first, then the length check on the stored form. */
class ArticleBylinePolicyTest :
    FunSpec({
        test("normalize: NFC, one line, control and bidi characters removed, whitespace collapsed and trimmed") {
            ArticleBylinePolicy.normalize("  Max‮   Muster\n\t ") shouldBe "Max Muster"
            ArticleBylinePolicy.normalize("Zeile eins\r\nZeile zwei") shouldBe "Zeile eins Zeile zwei"
            ArticleBylinePolicy.normalize("Müller") shouldBe "Müller"
            ArticleBylinePolicy.normalize("\u0000\u0007 ‎") shouldBe ""
        }

        test("requireValid: 1..120 characters of the normalized text") {
            ArticleBylinePolicy.requireValid("M")
            ArticleBylinePolicy.requireValid("x".repeat(120))
            shouldThrow<BadRequestException> { ArticleBylinePolicy.requireValid("") }
            shouldThrow<BadRequestException> { ArticleBylinePolicy.requireValid("x".repeat(121)) }
        }

        test("the limit applies to the stored form: padding that normalization removes does not count") {
            val padded = " ".repeat(50) + "x".repeat(120) + " ".repeat(50)
            ArticleBylinePolicy.requireValid(ArticleBylinePolicy.normalize(padded))
        }

        test("HTML-looking input is kept as text, never interpreted (the pages escape it)") {
            ArticleBylinePolicy.normalize("<script>alert(1)</script>") shouldBe "<script>alert(1)</script>"
        }
    })
