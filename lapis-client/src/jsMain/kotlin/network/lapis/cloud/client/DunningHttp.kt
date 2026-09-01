package network.lapis.cloud.client

import kotlinx.browser.document
import org.w3c.dom.HTMLFormElement

/**
 * Client-UI wave for GitHub Issue #5 -- URL builder/download trigger for the two raw Ktor routes
 * registered by `network.lapis.cloud.server.routes.registerDunningRoutes`
 * (`DunningRoutes.kt`), both gated `DUNNING_FILE_DOWNLOAD_ROLES` (TREASURER/ADMIN, never BOARD)
 * server-side -- see [DunningAuthzUi.FILE_ACCESS_ROLES].
 */
object DunningHttp {
    /** `GET /api/dunning/notices/{noticeId}/notice.pdf` -- plain same-origin GET, streams a
     * `Content-Disposition: attachment` PDF. Same `SepaHttp.batchFileUrl`/`MailmergeHttp.invoiceUrl`
     * idiom: a `root.link(url = ..., target = "_blank")` is sufficient, no fetch/Blob wrapper. */
    fun noticePdfUrl(noticeId: String): String = "/api/dunning/notices/$noticeId/notice.pdf"

    /**
     * `POST /api/dunning/contributions/{contributionId}/preview.pdf` -- a dry run of the NEXT
     * escalation step's letter, no request body. Exact `MailmergeHttp.submitEinladungPdfDownload`
     * idiom, minus `enctype`: this route reads no multipart parts at all (`DunningRoutes.kt:127`
     * takes `contributionId` from the path, nothing from the body), so a plain
     * `application/x-www-form-urlencoded` POST with no fields suffices. Builds a hidden,
     * same-origin `<form method="post" target="_blank">`, submits it programmatically, then removes
     * it -- the browser handles the download exactly like a GET link would (native
     * `Content-Disposition` handling, session cookie travels automatically as a normal navigation),
     * with no `fetch`/`Blob`/`createObjectURL` machinery.
     */
    fun submitNoticePreviewPdf(contributionId: String) {
        val form = document.createElement("form") as HTMLFormElement
        form.method = "post"
        form.action = "/api/dunning/contributions/$contributionId/preview.pdf"
        form.target = "_blank"

        document.body?.appendChild(form)
        form.submit()
        form.remove()
    }
}
