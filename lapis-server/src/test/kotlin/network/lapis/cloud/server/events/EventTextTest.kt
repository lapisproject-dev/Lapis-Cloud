package network.lapis.cloud.server.events

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/** Welle V1.9.82 -- pure plain-text normalization shared by the feed, the detail page and the import. */
class EventTextTest :
    FunSpec({
        test("CRLF and CR become LF") {
            EventText.normalizeMultiline("a\r\nb\rc") shouldBe "a\nb\nc"
        }

        test("tab becomes a space, NUL, ESC and bidi override are removed") {
            EventText.normalizeMultiline("a\tb") shouldBe "a b"
            EventText.normalizeMultiline("a\u0000b\u001Bc") shouldBe "abc"
            EventText.normalizeMultiline("abc‮def") shouldBe "abcdef"
        }

        test("all bidi controls are removed, but ZWJ, ZWNJ and soft hyphen are kept") {
            EventText.normalizeMultiline("a\u200Eb\u200Fc\u202Ad\u202Ee\u2066f\u2069g\u061Ch") shouldBe "abcdefgh"
            val family = "\uD83D\uDC68\u200D\uD83D\uDC69\u200D\uD83D\uDC67"
            EventText.normalizeMultiline(family) shouldBe family
            EventText.normalizeSingleLine("می\u200Cخواهم") shouldBe "می\u200Cخواهم"
            EventText.normalizeMultiline("co\u00ADoperate") shouldBe "co\u00ADoperate"
        }

        test("U+2028 and U+2029 become LF") {
            EventText.normalizeMultiline("a b c") shouldBe "a\nb\nc"
        }

        test("three or more line breaks collapse to exactly one empty line") {
            EventText.normalizeMultiline("a\n\n\n\nb") shouldBe "a\n\nb"
            EventText.normalizeMultiline("a\n\nb") shouldBe "a\n\nb"
            EventText.normalizeMultiline("a\nb") shouldBe "a\nb"
        }

        test("trailing whitespace per line and around the text is trimmed") {
            EventText.normalizeMultiline("  a   \n b \n\n  ") shouldBe "a\n b"
        }

        test("NFC: decomposed umlaut is composed") {
            EventText.normalizeMultiline("ü") shouldBe "ü"
        }

        test("blank input stays empty") {
            EventText.normalizeMultiline("  \n \r\n ") shouldBe ""
        }

        test("normalizeSingleLine turns every line break run into one space and collapses spaces") {
            EventText.normalizeSingleLine("a\n\n b  c\r\nd") shouldBe "a b c d"
            EventText.normalizeSingleLine("  x  ") shouldBe "x"
        }

        test("paragraphs split at empty lines, lines at single breaks") {
            EventText.paragraphs("a\nb\n\nc") shouldBe listOf(listOf("a", "b"), listOf("c"))
            EventText.paragraphs("") shouldBe emptyList()
        }

        test("isHttpsUrl accepts https with a host, case-insensitively") {
            EventText.isHttpsUrl("https://example.org/x?y=1") shouldBe true
            EventText.isHttpsUrl("HTTPS://example.org") shouldBe true
        }

        test("isHttpsUrl rejects http, javascript:, userinfo, empty host, blank and null") {
            EventText.isHttpsUrl("http://example.org") shouldBe false
            EventText.isHttpsUrl("javascript:alert(1)") shouldBe false
            EventText.isHttpsUrl("https://user@example.org") shouldBe false
            EventText.isHttpsUrl("https://") shouldBe false
            EventText.isHttpsUrl("https:///path") shouldBe false
            EventText.isHttpsUrl("  ") shouldBe false
            EventText.isHttpsUrl(null) shouldBe false
            EventText.isHttpsUrl("https://exa mple.org") shouldBe false
        }

        test("containsHtmlLikeMarkup spots tags but not a plain less-than sign") {
            EventText.containsHtmlLikeMarkup("a <b>x</b>") shouldBe true
            EventText.containsHtmlLikeMarkup("<script>") shouldBe true
            EventText.containsHtmlLikeMarkup("3 < 5 und 7 > 2") shouldBe false
        }
    })
