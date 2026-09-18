package network.lapis.cloud.client

import kotlinx.browser.document
import org.w3c.dom.HTMLFormElement

/**
 * Welle "Digitaler Mitgliedsausweis (PDF)" -- triggers the card download at
 * `POST /api/members/{memberId}/card.pdf`.
 *
 * **POST, not a `<a href>` link** -- unlike [MailmergeHttp.invoiceUrl]/[DocumentHttp]/
 * `BackupHttp.EXPORT_URL`, this download is not a read: the server mints a fresh card code on every
 * call and invalidates the previous card (see `MemberCardRoutes` KDoc). A plain link would let a
 * browser prefetch, a double click or a restored tab silently invalidate the member's card, so the
 * same hidden-form-POST idiom [MailmergeHttp.submitEinladungPdfDownload] already established for
 * `POST /api/mailmerge/invitations` is used here: the browser handles the response exactly like a
 * GET download (native `Content-Disposition` handling, the session cookie travels as a normal
 * navigation), with no `fetch`/`Blob`/`createObjectURL` machinery.
 *
 * **CSRF**: the session cookie is `SameSite=Strict` (`AuthRoutes`), so a cross-site form POST never
 * carries it -- which is precisely why this state-changing download may rely on cookie auth at all.
 * A `GET` variant would NOT have been safe here even with that cookie attribute, because same-site
 * prefetching and link previews are not cross-site.
 */
object MemberCardHttp {
    fun cardPdfUrl(memberId: String): String = "/api/members/$memberId/card.pdf"

    /** Submits a throwaway, same-origin `<form method="post" target="_blank">` and removes it again. */
    fun submitCardPdfDownload(memberId: String) {
        val form = document.createElement("form") as HTMLFormElement
        form.method = "post"
        form.action = cardPdfUrl(memberId)
        form.target = "_blank"
        document.body?.appendChild(form)
        form.submit()
        document.body?.removeChild(form)
    }
}
