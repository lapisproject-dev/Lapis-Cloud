package network.lapis.cloud.client

import kotlinx.browser.document
import kotlinx.datetime.LocalDateTime
import org.w3c.dom.HTMLFormElement
import org.w3c.dom.HTMLInputElement

/**
 * V0.4.1 Serienbrief/PDF-Engine: URL builders for the two `GET` PDF routes registered by
 * `network.lapis.cloud.server.routes.registerMailmergeRoutes`. Exact `dsgvoExportUrl`/
 * `BackupHttp.EXPORT_URL` idiom (see `DsgvoRightsScreen.kt`/`BackupScreen.kt`) -- both routes are
 * plain same-origin GETs that stream a `Content-Disposition: attachment` PDF, so a
 * `root.link(url = ..., target = "_blank")` is sufficient; no fetch/Blob wrapper is needed
 * (design decision D3).
 *
 * **Access note (load-bearing design finding, D3)**: both routes are gated
 * TREASURER/BOARD/ADMIN server-side (`FINANCIAL_DOC_ROLES` in `MailmergeRoutes.kt`) --
 * deliberately more conservative than `IContributionService`'s own "member can see their own
 * data" carve-out (`MailmergeRoutes.kt` KDoc, confirmed by `MailmergeRoutesTest.kt`: 403 for
 * MEMBER). A member cannot self-serve their own invoice/receipt this wave, so these URLs must
 * only ever be rendered on staff-facing views (`ContributionsScreen.renderOrgWideContributions`,
 * `LedgerScreen.renderJournalEntryDetail`) -- never on `ContributionsScreen.renderOwnSummary`.
 */
object MailmergeHttp {
    fun invoiceUrl(contributionId: String): String = "/api/mailmerge/contributions/$contributionId/invoice.pdf"

    fun receiptUrl(journalEntryId: String): String = "/api/mailmerge/donations/$journalEntryId/receipt.pdf"

    /**
     * Design decision D4 -- first POST-triggered-file-download idiom in this client (every existing
     * download -- [DocumentHttp]'s `downloadUrl`, `BackupHttp.EXPORT_URL`, `dsgvoExportUrl` -- is a
     * plain GET link; `POST /api/mailmerge/invitations` is a multipart POST, so a plain `<a href>`
     * cannot trigger it). Builds a hidden, same-origin `<form method="post"
     * enctype="multipart/form-data" target="_blank">`, submits it programmatically, then removes it
     * -- the browser handles the download exactly like a GET link would (native
     * `Content-Disposition` handling, session cookie travels automatically as a normal navigation),
     * with no `fetch`/`Blob`/`createObjectURL` machinery. Field names match
     * `MailmergeRoutes.kt`'s `registerMailmergeRoutes` multipart parser exactly (`title`,
     * `eventDateTime`, `location`, `bodyText`, one `recipientMemberId` part per recipient).
     */
    fun submitEinladungPdfDownload(
        title: String,
        eventDateTime: LocalDateTime,
        location: String,
        bodyText: String,
        recipientMemberIds: List<String>,
    ) {
        val form = document.createElement("form") as HTMLFormElement
        form.method = "post"
        form.action = "/api/mailmerge/invitations"
        form.enctype = "multipart/form-data"
        form.target = "_blank"

        fun hidden(
            name: String,
            value: String,
        ) {
            val input = document.createElement("input") as HTMLInputElement
            input.type = "hidden"
            input.name = name
            input.value = value
            form.appendChild(input)
        }

        hidden("title", title)
        hidden("eventDateTime", eventDateTime.toString())
        hidden("location", location)
        hidden("bodyText", bodyText)
        recipientMemberIds.forEach { memberId -> hidden("recipientMemberId", memberId) }

        document.body?.appendChild(form)
        form.submit()
        form.remove()
    }
}
