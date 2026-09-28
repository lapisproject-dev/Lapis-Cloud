package network.lapis.cloud.server.mail.newsletter

import kotlinx.html.body
import kotlinx.html.div
import kotlinx.html.head
import kotlinx.html.html
import kotlinx.html.p
import kotlinx.html.stream.createHTML
import kotlinx.html.title
import kotlinx.html.unsafe
import network.lapis.cloud.server.mail.MailBranding
import network.lapis.cloud.server.mail.MailTemplates

/**
 * Welle V1.9.7 "SuperMailer" Teil A -- renders a [network.lapis.cloud.shared.domain.MailingMessageDto]
 * into the actual mail that goes out (or would go out in `LOG` mode), for both the async send
 * ([MailingDeliveryWorker]) and the two preview RPCs (`MailingService.previewMailingMessage`/
 * `.previewMailingHtml`) -- exactly ONE render code path for both, so a preview is never allowed to
 * drift from what actually gets sent.
 *
 * **No tracking parameter in this wave.** The design plan's [content]/[legacyBodyText] split (HTML
 * vs. plain-text drafts) is fully implemented; the plan's additional `tracking: RecipientTracking?`
 * parameter (open-pixel insertion, click-link rewriting) is deliberately NOT built here -- the
 * routes that would actually SERVE `/api/mailing/o/{token}`/`/api/mailing/c/{token}/{index}`, the
 * token generation (`MailingTrackingToken`) and the consent RPCs that would ever produce a non-null
 * tracking state are all out of scope for this wave (Teil B "Klick-Zählung"/Teil C
 * "Öffnungs-Zählung"), see CHANGELOG. Shipping a renderer branch that emits links to routes that do
 * not exist would be worse than shipping nothing -- every recipient would see a dead link/broken
 * image. The follow-up wave adds the tracking parameter, the token plumbing and the routes
 * together, in one coherent change.
 *
 * **Plain-text NEVER carries HTML markup or a tracking artifact** (S10 in the plan) -- [content]'s
 * [SanitizedMailingHtml.plainText] is derived once, by [MailingHtmlSanitizer], from the UNREWRITTEN
 * sanitized document.
 */
object MailingMailRenderer {
    private const val CONTENT_WIDTH_PX = 600

    fun render(
        subject: String,
        content: SanitizedMailingHtml?,
        legacyBodyText: String,
        branding: MailBranding,
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
                            unsafe { +content.html }
                        } else {
                            // Legacy/plain-text draft (createDraftMessage): escape via kotlinx.html's
                            // ordinary text-node API, one <p> per blank-line-separated paragraph.
                            legacyBodyText.split(Regex("\n{2,}")).forEach { paragraph ->
                                if (paragraph.isNotBlank()) p { +paragraph }
                            }
                        }
                        p { +footerLine }
                    }
                }
            }
        return MailTemplates.RenderedMail(subject = subject, plainText = plainText, html = html)
    }

    /** Same footer text/logic as [MailTemplates]' own private `footer` -- kept local since this renderer is not part of that object. */
    private fun footer(branding: MailBranding): String =
        branding.replyTo?.let { "Fragen? Antworten Sie einfach auf diese E-Mail ($it)." }
            ?: "Diese Adresse wird nicht gelesen. Fragen: ${branding.publicBaseUrl}. Abbestellen: ${branding.publicBaseUrl}/app"
}
