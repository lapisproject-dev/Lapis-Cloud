package network.lapis.cloud.server.mail.newsletter

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import network.lapis.cloud.server.mail.MailBranding

/**
 * Welle V1.9.7 "SuperMailer" Teil A -- [MailingMailRenderer] is the ONE render path both the async
 * send ([MailingDeliveryWorker]) and both preview RPCs go through. No tracking parameter exists in
 * this wave (see that object's own KDoc for why) -- these tests pin what IS built: sanitized-HTML
 * vs. legacy-plain-text rendering, and that neither path ever emits a `/api/mailing/` URL (S10 in
 * the plan, kept meaningful for this wave even without a tracking parameter to test against).
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
    })
