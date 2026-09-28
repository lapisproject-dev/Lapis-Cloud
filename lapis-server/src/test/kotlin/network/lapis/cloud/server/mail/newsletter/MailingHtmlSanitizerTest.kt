package network.lapis.cloud.server.mail.newsletter

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import network.lapis.cloud.shared.domain.MailingHtmlPolicy
import network.lapis.cloud.shared.rpc.BadRequestException

/**
 * Welle V1.9.7 "SuperMailer" Teil A. Covers the plan's full test list for
 * [MailingHtmlSanitizer]: the XSS corpus, tag renaming, relative-link stripping, idempotency, and
 * every documented limit.
 */
class MailingHtmlSanitizerTest :
    FunSpec({
        test("XSS corpus: nothing survives, script content never appears in the output") {
            // Every entry keeps some surviving safe text ("Text.") alongside the payload -- an
            // entirely-stripped body would fail MailingPlainText's own empty-text check before the
            // XSS assertions below ever run, which is not what this test is about.
            val corpus =
                listOf(
                    "<p>Text.</p><script>alert('xss')</script>",
                    "<p>Text.<img src=x onerror=alert('xss')></p>",
                    "<p>Text.</p><a href=\"javascript:alert('xss')\">click</a>",
                    "<p>Text.</p><a href=\"jav&#x09;ascript:alert('xss')\">click</a>",
                    "<p>Text.</p><svg onload=alert('xss')>",
                    "<p style=\"background:url(javascript:alert('xss'))\">Text.</p>",
                    "<p>Text.</p><iframe src=\"https://evil.example\"></iframe>",
                    "<p>Text.</p><object data=\"https://evil.example\"></object>",
                    "<p>Text.</p><form action=\"https://evil.example\"><input></form>",
                    "<p>Text.</p><a href=\"data:text/html,<script>alert(1)</script>\">click</a>",
                )
            corpus.forEach { payload ->
                val result = MailingHtmlSanitizer.sanitize(payload)
                result.html shouldNotContain "script"
                result.html shouldNotContain "onerror"
                result.html shouldNotContain "onload"
                result.html shouldNotContain "javascript:"
                result.html shouldNotContain "<iframe"
                result.html shouldNotContain "<object"
                result.html shouldNotContain "<form"
                result.html shouldNotContain "style="
                result.html shouldNotContain "alert"
            }
        }

        test("b/i/div are renamed to strong/em/p before the allowlist runs") {
            val result = MailingHtmlSanitizer.sanitize("<div><b>bold</b> and <i>italic</i></div>")
            result.html shouldContain "<p>"
            result.html shouldContain "<strong>bold</strong>"
            result.html shouldContain "<em>italic</em>"
            result.html shouldNotContain "<div"
            result.html shouldNotContain "<b>"
            result.html shouldNotContain "<i>"
        }

        test("href on a non-a element is stripped (Safelist has no attribute allowance for it)") {
            val result = MailingHtmlSanitizer.sanitize("<p href=\"https://example.org\">text</p>")
            result.html shouldNotContain "href"
        }

        test("relative links are dropped") {
            val result = MailingHtmlSanitizer.sanitize("<p><a href=\"/relative/path\">link</a></p>")
            result.html shouldNotContain "href"
            result.html shouldContain "link"
            result.trackableLinks shouldBe emptyList()
        }

        test("idempotent: sanitizing an already-sanitized result changes nothing") {
            val once = MailingHtmlSanitizer.sanitize("<div><b>Hallo</b> <a href=\"https://example.org\">Welt</a></div>")
            val twice = MailingHtmlSanitizer.sanitize(once.html)
            twice.html shouldBe once.html
            twice.trackableLinks shouldBe once.trackableLinks
        }

        test("more than MAX_HTML_BYTES raw input is rejected before parsing") {
            val huge = "<p>${"x".repeat(MailingHtmlPolicy.MAX_HTML_BYTES + 1)}</p>"
            shouldThrow<BadRequestException> { MailingHtmlSanitizer.sanitize(huge) }
        }

        test("more than MAX_LINKS trackable links is rejected") {
            val links = (0..MailingHtmlPolicy.MAX_LINKS).joinToString("") { "<p><a href=\"https://example.org/$it\">l$it</a></p>" }
            shouldThrow<BadRequestException> { MailingHtmlSanitizer.sanitize(links) }
        }

        test("empty text throws (via MailingPlainText, surfaced through sanitize)") {
            shouldThrow<BadRequestException> { MailingHtmlSanitizer.sanitize("<p></p>") }
        }

        test("text over MAX_TEXT_CHARS throws") {
            val huge = "<p>${"x".repeat(MailingHtmlPolicy.MAX_TEXT_CHARS + 1)}</p>"
            shouldThrow<BadRequestException> { MailingHtmlSanitizer.sanitize(huge) }
        }

        test("trackableLinks preserves document order and excludes mailto") {
            val html =
                "<p><a href=\"https://b.example\">b</a></p>" +
                    "<p><a href=\"mailto:someone@example.org\">mail</a></p>" +
                    "<p><a href=\"http://a.example\">a</a></p>"
            val result = MailingHtmlSanitizer.sanitize(html)
            result.trackableLinks shouldContainExactly listOf("https://b.example", "http://a.example")
        }

        test("an href longer than 2048 characters is unwrapped, keeping the anchor's text") {
            val longHref = "https://example.org/" + "a".repeat(2048)
            val result = MailingHtmlSanitizer.sanitize("<p><a href=\"$longHref\">click here</a></p>")
            result.html shouldNotContain "href"
            result.html shouldContain "click here"
        }

        test(
            "a body whose SANITIZED output exceeds MAX_HTML_BYTES is rejected even though the RAW " +
                "input is under the limit (review finding #1, W-SuperMailer round 1)",
        ) {
            // <i> renames to <em> (7 -> 9 bytes per tag), so N repeats of "<i></i>" grow on
            // sanitize -- this exact N puts the raw input at 199,004 bytes (under MAX_HTML_BYTES,
            // 200,000) but the sanitized <em></em> output at 255,862 bytes (over it). Without the
            // output-side check, this body would be silently accepted and stored, only to blow up
            // the NEXT time it is sanitized (e.g. MailingDeliveryWorker.loadSendPlan re-sanitizing
            // the stored HTML right before send).
            val n = 28_429
            val raw = "x" + "<i></i>".repeat(n)
            raw.toByteArray(Charsets.UTF_8).size shouldBe (MailingHtmlPolicy.MAX_HTML_BYTES - 996)
            shouldThrow<BadRequestException> { MailingHtmlSanitizer.sanitize(raw) }
        }
    })
