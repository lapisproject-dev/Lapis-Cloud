package network.lapis.cloud.server.mail.newsletter

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import network.lapis.cloud.server.mail.MailBranding

/**
 * Welle V1.9.7 "SuperMailer" Teil A -- [MailingMailRenderer] is the ONE render path both the async
 * send ([MailingDeliveryWorker]) and both preview RPCs go through. Pins sanitized-HTML vs.
 * legacy-plain-text rendering and, since V1.9.15, the optional per-recipient tracking rewrite.
 */
class MailingMailRendererTest :
    FunSpec({
        val branding = MailBranding(fromDisplayName = "Testverein", replyTo = "kontakt@example.org")

        test("HTML content is embedded verbatim (unsafe) into the rendered HTML") {
            val sanitized = MailingHtmlSanitizer.sanitize("<p>Hallo <strong>Welt</strong></p>")
            val rendered =
                MailingMailRenderer.render(
                    subject = "Betreff",
                    content = sanitized,
                    legacyBodyText = "unused",
                    branding = branding,
                )
            rendered.html shouldContain "<strong>Welt</strong>"
        }

        test("plain text is derived from the sanitized content, never from legacyBodyText, when content is present") {
            val sanitized = MailingHtmlSanitizer.sanitize("<p>Sanitized-Text.</p>")
            val rendered =
                MailingMailRenderer.render(subject = "Betreff", content = sanitized, legacyBodyText = "Legacy-Text.", branding = branding)
            rendered.plainText shouldContain "Sanitized-Text."
            rendered.plainText shouldNotContain "Legacy-Text."
        }

        test("legacy (content == null) drafts are HTML-escaped, never raw-embedded") {
            val rendered =
                MailingMailRenderer.render(
                    subject = "Betreff",
                    content = null,
                    legacyBodyText = "Ein <script>alert(1)</script> Text.",
                    branding = branding,
                )
            rendered.html shouldNotContain "<script>"
            rendered.html shouldContain "&lt;script&gt;"
            rendered.plainText shouldContain "Ein <script>alert(1)</script> Text."
        }

        test("never emits a mailing-tracking-route URL, in either plain text or HTML") {
            val sanitized = MailingHtmlSanitizer.sanitize("<p><a href=\"https://example.org\">Link</a></p>")
            val rendered =
                MailingMailRenderer.render(
                    subject = "Betreff",
                    content = sanitized,
                    legacyBodyText = "unused",
                    branding = branding,
                )
            rendered.html shouldNotContain "/api/mailing/"
            rendered.plainText shouldNotContain "/api/mailing/"
        }

        test("footer mentions replyTo when configured") {
            val rendered = MailingMailRenderer.render(subject = "Betreff", content = null, legacyBodyText = "Text.", branding = branding)
            rendered.plainText shouldContain "kontakt@example.org"
        }

        test("footer falls back to publicBaseUrl when replyTo is absent") {
            val noReply = MailBranding(fromDisplayName = "Testverein", replyTo = null, publicBaseUrl = "https://example.org")
            val rendered = MailingMailRenderer.render(subject = "Betreff", content = null, legacyBodyText = "Text.", branding = noReply)
            rendered.plainText shouldContain "https://example.org"
        }

        // ── Welle V1.9.15 tracking ──────────────────────────────────────────────────────────────

        val trackedHtml =
            "<p>Hallo <a href=\"https://example.org/a\">A</a> <a href=\"mailto:x@example.org\">M</a> " +
                "<a href=\"https://example.org/a\">A2</a> <a href=\"https://example.org/b\">B</a></p>"

        fun tracking(
            click: Boolean,
            pixel: Boolean,
        ) = MailingMailRenderer.RecipientTracking(
            clickUrlFor = if (click) { i -> "https://test.example/m/c/T$i" } else null,
            linkIndexByUrl = mapOf("https://example.org/a" to 0, "https://example.org/b" to 1),
            pixelUrl = if (pixel) "https://test.example/m/o/P.gif" else null,
        )

        test("click tracking rewrites http(s) links (same URL -> same index), leaves mailto alone") {
            val sanitized = MailingHtmlSanitizer.sanitize(trackedHtml)
            val html =
                MailingMailRenderer
                    .render(
                        subject = "S",
                        content = sanitized,
                        legacyBodyText = "unused",
                        branding = branding,
                        tracking = tracking(click = true, pixel = false),
                    ).html
            html shouldContain "href=\"https://test.example/m/c/T0\""
            html shouldContain "href=\"https://test.example/m/c/T1\""
            html shouldContain "href=\"mailto:x@example.org\""
            html shouldNotContain "https://example.org/a"
            html shouldNotContain "/m/o/"
        }

        test("links are NOT rewritten when only the open pixel is on") {
            val sanitized = MailingHtmlSanitizer.sanitize(trackedHtml)
            val html =
                MailingMailRenderer
                    .render(
                        subject = "S",
                        content = sanitized,
                        legacyBodyText = "unused",
                        branding = branding,
                        tracking = tracking(click = false, pixel = true),
                    ).html
            html shouldContain "href=\"https://example.org/a\""
            html shouldNotContain "/m/c/"
            html shouldContain "src=\"https://test.example/m/o/P.gif\""
        }

        test("the pixel is the last element before the footer paragraph") {
            val sanitized = MailingHtmlSanitizer.sanitize("<p>Text</p>")
            val html =
                MailingMailRenderer
                    .render(
                        subject = "S",
                        content = sanitized,
                        legacyBodyText = "unused",
                        branding = branding,
                        tracking = tracking(click = false, pixel = true),
                    ).html
            val pixelAt = html.indexOf("<img")
            val footerAt = html.indexOf("kontakt@example.org")
            (pixelAt in 1 until footerAt) shouldBe true
            html.substring(pixelAt).substringBefore("<p>") shouldContain "/m/o/P.gif"
        }

        test("plain text is byte-identical with and without tracking") {
            val sanitized = MailingHtmlSanitizer.sanitize(trackedHtml)
            val plain =
                MailingMailRenderer
                    .render(
                        subject = "S",
                        content = sanitized,
                        legacyBodyText = "unused",
                        branding = branding,
                        tracking = null,
                    ).plainText
            val tracked =
                MailingMailRenderer
                    .render(
                        subject = "S",
                        content = sanitized,
                        legacyBodyText = "unused",
                        branding = branding,
                        tracking = tracking(click = true, pixel = true),
                    ).plainText
            tracked shouldBe plain
            tracked shouldNotContain "/m/c/"
            tracked shouldNotContain "/m/o/"
        }

        test("footer text is untouched by tracking") {
            val sanitized = MailingHtmlSanitizer.sanitize("<p>Text</p>")
            val plain =
                MailingMailRenderer
                    .render(
                        subject = "S",
                        content = sanitized,
                        legacyBodyText = "unused",
                        branding = branding,
                        tracking = null,
                    ).html
            val tracked =
                MailingMailRenderer
                    .render(
                        subject = "S",
                        content = sanitized,
                        legacyBodyText = "unused",
                        branding = branding,
                        tracking = tracking(click = true, pixel = false),
                    ).html
            tracked shouldBe plain
        }

        test("tracking = null (previews) never produces a tracking URL") {
            val sanitized = MailingHtmlSanitizer.sanitize(trackedHtml)
            val rendered =
                MailingMailRenderer.render(
                    subject = "S",
                    content = sanitized,
                    legacyBodyText = "unused",
                    branding = branding,
                    tracking = null,
                )
            rendered.html shouldNotContain "/m/c/"
            rendered.html shouldNotContain "/m/o/"
        }

        test("a legacy plain-text draft never gets a pixel") {
            val html =
                MailingMailRenderer
                    .render(
                        subject = "S",
                        content = null,
                        legacyBodyText = "Text",
                        branding = branding,
                        tracking = tracking(click = true, pixel = true),
                    ).html
            html shouldNotContain "<img"
        }
    })
