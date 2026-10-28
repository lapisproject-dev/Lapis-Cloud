package network.lapis.cloud.server.governance.session

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/** V1.9.239 -- the sanitiser of every free text of the Protokoll draft: plain text in, plain text out, and a character is a code point. */
class MeetingProtocolTextTest :
    FunSpec({
        test("line breaks are normalised, tab and newline survive") {
            sanitizeProtocolText("a\r\nb\rc\nd\te") shouldBe "a\nb\nc\nd\te"
        }

        test("C0 and C1 controls, bidi controls, zero-width characters and separators are removed") {
            sanitizeProtocolText("a\u0000b\u0007c\u007Fd\u0085e\u009Ff") shouldBe "abcdef"
            sanitizeProtocolText("x‪y‮z⁦w⁩") shouldBe "xyzw"
            sanitizeProtocolText("p​q‌r‍s﻿t⁠u") shouldBe "pqrstu"
            sanitizeProtocolText("l‎m‏n؜o p q") shouldBe "lmnopq"
        }

        test("markup and wiki syntax are NOT interpreted and NOT escaped -- they stay as typed") {
            val raw = "<b onclick=\"x()\">fett</b> & [[Beschluss:B-1]] {{x}} \${y} `z`"
            sanitizeProtocolText(raw) shouldBe raw
        }

        test("text outside the BMP is kept; an unpaired surrogate is removed") {
            val smile = String(Character.toChars(0x1F600))
            sanitizeProtocolText("a${smile}b") shouldBe "a${smile}b"
            sanitizeProtocolText("a\uD83Db") shouldBe "ab"
            sanitizeProtocolText("a\uDE00b") shouldBe "ab"
        }

        test("length is counted in UTF-16 units -- the bound that fits H2 (units) and PostgreSQL (code points)") {
            val smile = String(Character.toChars(0x1F600))
            protocolCharCount(smile.repeat(10)) shouldBe 20
            protocolCharCount("äöü") shouldBe 3
        }
    })
