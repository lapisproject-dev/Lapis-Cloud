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
import network.lapis.cloud.shared.domain.MailingNotice
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
 * **Mandatory notice (V1.9.111).** With [MailingNotice.MANDATORY] the mail is visibly a mandatory notice of the association: the subject
 * gets the [MANDATORY_SUBJECT_PREFIX], a framed banner (`role="note"`, first line of the plain text too) opens the content, and the
 * footer explains why there is no unsubscribe link -- and contains none. The prefix is added HERE (never stored), so the previews and the
 * delivery worker show exactly the same subject. A dynamic, non-mandatory list ([dynamicList]) gets an explicit "Abmelden" hint that
 * points at the self-service page (there is no one-click token).
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

    /** Subject prefix of a mandatory notice. German only, like every mailing text (no per-member language yet). */
    const val MANDATORY_SUBJECT_PREFIX = "[Pflichtmitteilung] "

    const val MANDATORY_BANNER =
        "Pflichtmitteilung des Vereins: Diese Nachricht geht an alle Mitglieder, die die Regel der Liste erfüllen, und kann nicht abbestellt werden."

    fun render(
        subject: String,
        content: SanitizedMailingHtml?,
        legacyBodyText: String,
        branding: MailBranding,
        tracking: RecipientTracking? = null,
        notice: MailingNotice = MailingNotice.NONE,
        dynamicList: Boolean = false,
    ): MailTemplates.RenderedMail {
        val mandatory = notice == MailingNotice.MANDATORY
        val renderedSubject = if (mandatory) MANDATORY_SUBJECT_PREFIX + subject else subject
        val plainBody = content?.plainText ?: legacyBodyText
        val footerLine = footer(branding = branding, mandatory = mandatory, dynamicList = dynamicList)
        val plainText = (if (mandatory) "$MANDATORY_BANNER\n\n" else "") + "$plainBody\n\n$footerLine"

        val html =
            createHTML().html {
                head { title { +renderedSubject } }
                body {
                    div {
                        attributes["style"] = "max-width:${CONTENT_WIDTH_PX}px;margin:0 auto;font-family:sans-serif;line-height:1.5;"
                        if (mandatory) {
                            div {
                                attributes["role"] = "note"
                                attributes["style"] = "border:2px solid #444;padding:8px 12px;margin:0 0 16px 0;font-weight:bold;"
                                +MANDATORY_BANNER
                            }
                        }
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
        return MailTemplates.RenderedMail(subject = renderedSubject, plainText = plainText, html = html)
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

    /**
     * Same footer text/logic as [MailTemplates]' own private `footer` -- kept local since this renderer is not part of that object.
     * Mandatory: contact line + the reason there is no unsubscribe link, never a link. Dynamic list: contact line + "Abmelden" hint.
     */
    private fun footer(
        branding: MailBranding,
        mandatory: Boolean,
        dynamicList: Boolean,
    ): String {
        val contact =
            branding.replyTo?.let { "Fragen? Antworten Sie einfach auf diese E-Mail ($it)." }
                ?: "Diese Adresse wird nicht gelesen. Fragen: ${branding.publicBaseUrl}."
        return when {
            mandatory -> "$contact Dies ist eine Pflichtmitteilung des Vereins und kann nicht abbestellt werden."
            dynamicList -> "$contact Abmelden: ${branding.publicBaseUrl}/app"
            else ->
                branding.replyTo?.let { "Fragen? Antworten Sie einfach auf diese E-Mail ($it)." }
                    ?: "Diese Adresse wird nicht gelesen. Fragen: ${branding.publicBaseUrl}. Abbestellen: ${branding.publicBaseUrl}/app"
        }
    }
}
