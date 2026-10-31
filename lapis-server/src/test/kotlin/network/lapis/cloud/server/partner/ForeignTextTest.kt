package network.lapis.cloud.server.partner

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class ForeignTextTest :
    FunSpec({
        test("null and blank become null") {
            ForeignText.clean(raw = null, maxChars = 10) shouldBe null
            ForeignText.clean(raw = "   ", maxChars = 10) shouldBe null
        }

        test("bidi overrides, isolates and control characters are removed") {
            ForeignText.clean(raw = "a‮b⁦c\u0007d", maxChars = 50) shouldBe "abcd"
        }

        test("line breaks and blank runs collapse") {
            ForeignText.clean(raw = "one\r\n\r\n  two\tthree", maxChars = 50) shouldBe "one two three"
        }

        test("decomposed characters are composed (NFC)") {
            ForeignText.clean(raw = "é", maxChars = 5) shouldBe "é"
        }

        test("the cut is by code points and never splits a surrogate pair") {
            val text = "ab" + "😀".repeat(3)
            ForeignText.clean(raw = text, maxChars = 3) shouldBe "ab😀"
            ForeignText.clean(raw = text, maxChars = 4)!!.codePointCount(0, ForeignText.clean(raw = text, maxChars = 4)!!.length) shouldBe 4
        }

        test("HTML is never interpreted: it stays the literal text") {
            ForeignText.clean(raw = "<script>alert(1)</script> <b>x</b>", maxChars = 100) shouldBe "<script>alert(1)</script> <b>x</b>"
        }
    })
