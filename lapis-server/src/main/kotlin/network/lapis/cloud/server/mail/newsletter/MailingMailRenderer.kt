package network.lapis.cloud.server.mail.newsletter

import kotlinx.html.body
import kotlinx.html.div
import kotlinx.html.head
import kotlinx.html.html
import kotlinx.html.img
import kotlinx.html.p
import kotlinx.html.stream.createHTML
import kotlinx.html.title
import kotlinx.html.unsafe
import network.lapis.cloud.server.mail.MailBranding
import network.lapis.cloud.server.mail.MailTemplates
import org.jsoup.Jsoup

/**
 * Welle V1.9.7 "SuperMailer" Teil A -- renders a [network.lapis.cloud.shared.domain.MailingMessageDto]
 * into the actual mail that goes out (or would go out in `LOG` mode), for both the async send
 * ([MailingDeliveryWorker]) and the two preview RPCs (`MailingService.previewMailingMessage`/
 * `.previewMailingHtml`) -- exactly ONE render code path for both, so a preview is never allowed to
 * drift from what actually gets sent.
 *
 * **Tracking (Welle V1.9.15).** The optional [RecipientTracking] parameter carries the per-recipient
 * tracking snapshot [MailingDeliveryWorker] decided on (consent at send time): when
 * [RecipientTracking.clickUrlFor] is set, every `http(s)` `<a href>` inside the message CONTENT that
 * has an entry in [RecipientTracking.linkIndexByUrl] is rewritten to the signed click URL; when
 * [RecipientTracking.pixelUrl] is set, a 1x1 open pixel is appended as the last element before the
 * footer. `mailto:` links and unknown hrefs are never touched, the footer/unsubscribe line lives
 * outside the content and is never rewritten, and the previews pass `tracking = null` so a preview
 * can never contain (or mint) a tracking artifact.
 *
 * **Plain-text NEVER carries HTML markup or a tracking artifact** (S10 in the plan) -- [content]'s
 * [SanitizedMailingHtml.plainText] is derived once, by [MailingHtmlSanitizer], from the UNREWRITTEN
 * sanitized document.
 */
object MailingMailRenderer {
    private const val CONTENT_WIDTH_PX = 600

    /**
     * [clickUrlFor] is `null` unless the recipient's click-tracking snapshot is on; [pixelUrl] is
     * `null` unless the open-tracking snapshot is on. [linkIndexByUrl] comes from the message's
     * `mailing_message_link` rows, never from a fresh sanitize pass.
     */
    class RecipientTracking(
        val clickUrlFor: ((linkIndex: Int) -> String)?,
        val linkIndexByUrl: Map<String, Int>,
        val pixelUrl: String?,
    )

    fun render(
        subject: String,
        content: SanitizedMailingHtml?,
        legacyBodyText: String,
        branding: MailBranding,
        tracking: RecipientTracking? = null,
    ): MailTemplates.RenderedMail {
        val plainBody = content?.plainText ?: legacyBodyText
        val footerLine = footer(branding)
        val plainText = "$plainBody\n\n$footerLine"

        val html =
            createHTML().html {
                head { title { +subject } }
                body {
                    div {
                        attributes["style"] = "max-width:${CONTENT_WIDTH_PX}px;margin:0 auto;font-family:sans-serif;line-height:1.5;"
                        if (content != null) {
                            // Content is ALWAYS SanitizedMailingHtml.html -- output of
                            // MailingHtmlSanitizer.sanitize, never raw author input (type-enforced:
                            // SanitizedMailingHtml's constructor is internal to this package).
                            unsafe { +rewriteLinks(html = content.html, tracking = tracking) }
                        } else {
                            // Legacy/plain-text draft (createDraftMessage): escape via kotlinx.html's
                            // ordinary text-node API, one <p> per blank-line-separated paragraph.
                            legacyBodyText.split(Regex("\n{2,}")).forEach { paragraph ->
                                if (paragraph.isNotBlank()) p { +paragraph }
                            }
                        }
                        if (content != null && tracking?.pixelUrl != null) {
                            img {
                                attributes["src"] = tracking.pixelUrl
                                attributes["width"] = "1"
                                attributes["height"] = "1"
                                attributes["alt"] = ""
                                attributes["style"] = "display:block;border:0;"
                            }
                        }
                        p { +footerLine }
                    }
                }
            }
        return MailTemplates.RenderedMail(subject = subject, plainText = plainText, html = html)
    }

    private fun rewriteLinks(
        html: String,
        tracking: RecipientTracking?,
    ): String {
        val clickUrlFor = tracking?.clickUrlFor ?: return html
        val body = Jsoup.parseBodyFragment(html).body()
        body.ownerDocument()?.outputSettings()?.prettyPrint(false)
        var rewritten = false
        body.select("a[href]").forEach { anchor ->
            val index = tracking.linkIndexByUrl[anchor.attr("href")]
            if (index != null) {
                anchor.attr("href", clickUrlFor(index))
                rewritten = true
            }
        }
        return if (rewritten) body.html() else html
    }

    /** Same footer text/logic as [MailTemplates]' own private `footer` -- kept local since this renderer is not part of that object. */
    private fun footer(branding: MailBranding): String =
        branding.replyTo?.let { "Fragen? Antworten Sie einfach auf diese E-Mail ($it)." }
            ?: "Diese Adresse wird nicht gelesen. Fragen: ${branding.publicBaseUrl}. Abbestellen: ${branding.publicBaseUrl}/app"
}
