package network.lapis.cloud.client

import kotlinx.datetime.LocalDate

/**
 * Welle V1.4.5.2 "DATEV-Format-Export": URL builder for the one raw `GET` route
 * `network.lapis.cloud.server.routes.registerDatevRoutes` registers. Exact [SepaHttp]/
 * [MailmergeHttp] idiom -- a plain same-origin GET that streams a
 * `Content-Disposition: attachment` CSV file, so a `root.link(url = ..., target = "_blank")` is
 * sufficient; no fetch/Blob wrapper is needed.
 *
 * **Access note (load-bearing, mirrors [SepaHttp]'s own "Access note")**: this route is gated
 * TREASURER/ADMIN server-side (`DatevRoutes.DATEV_FILE_DOWNLOAD_ROLES`) -- **deliberately without
 * BOARD**. This URL must only ever be rendered where [DatevAuthzUi.canDownloadNow] returned `true`
 * -- never unconditionally next to the preview.
 */
object DatevHttp {
    fun buchungsstapelUrl(
        from: LocalDate,
        to: LocalDate,
    ): String = "/api/accounting/datev/buchungsstapel.csv?from=$from&to=$to"
}
