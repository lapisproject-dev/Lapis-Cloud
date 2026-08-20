package network.lapis.cloud.client

/**
 * V1.2.2 SEPA-Client-UI wave: URL builder for the one raw `GET` route
 * `network.lapis.cloud.server.routes.SepaRoutes` registers. Exact [MailmergeHttp]/`DsgvoRightsScreen.kt`'s
 * `dsgvoExportUrl` idiom -- a plain same-origin GET that streams a `Content-Disposition: attachment`
 * pain.008 XML file, so a `root.link(url = ..., target = "_blank")` is sufficient; no fetch/Blob
 * wrapper is needed.
 *
 * **Access note (load-bearing, mirrors [MailmergeHttp]'s own "Access note")**: this route is gated
 * TREASURER/ADMIN server-side (`SepaRoutes.SEPA_FILE_DOWNLOAD_ROLES`) -- **deliberately without
 * BOARD**, unlike [SepaAuthzUi.READ_ROLES]'s own TREASURER/BOARD/ADMIN tier for the rest of the
 * SEPA read surface (Security Round 1, MAJOR-1: this is the ONE place in the whole system a full,
 * un-redacted IBAN for every collected member ever crosses the wire in clear text). This URL must
 * only ever be rendered where [SepaAuthzUi.canDownloadBatchFile] returned `true` -- never
 * unconditionally next to a batch row.
 */
object SepaHttp {
    fun batchFileUrl(batchId: String): String = "/api/sepa/batches/$batchId/file.xml"
}
