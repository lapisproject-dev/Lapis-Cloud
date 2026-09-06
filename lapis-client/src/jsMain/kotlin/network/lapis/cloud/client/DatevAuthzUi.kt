package network.lapis.cloud.client

import network.lapis.cloud.shared.domain.AccountRole

/**
 * Welle V1.4.5.2 "DATEV-Format-Export" -- pure, DOM-free client-side mirror of the two role tiers
 * the server actually enforces (`AccountingService.previewDatevExport`'s `ACCOUNTING_READ_ROLES`
 * and `DatevRoutes.DATEV_FILE_DOWNLOAD_ROLES`), same "UX nicety on top of the server's real
 * authority" caveat every other `*AuthzUi` object in this client carries (see [SepaAuthzUi] KDoc).
 *
 * [PREVIEW_ROLES] and [FILE_DOWNLOAD_ROLES] are deliberately TWO SEPARATE constants, NOT
 * [FILE_DOWNLOAD_ROLES] computed as "[PREVIEW_ROLES] minus BOARD" -- reusing one for the other
 * would be exactly the silent-drift risk [SepaAuthzUi]'s own KDoc warns about for its
 * `FILE_DOWNLOAD_ROLES`/`READ_ROLES` split. A BOARD member may see THAT a period is exportable
 * (via [PREVIEW_ROLES]) without ever reaching the full plaintext Buchungstext of every posting in
 * it (gated to [FILE_DOWNLOAD_ROLES]).
 */
object DatevAuthzUi {
    val PREVIEW_ROLES: Set<AccountRole> = setOf(AccountRole.TREASURER, AccountRole.BOARD, AccountRole.ADMIN)

    /** Bewusst eigene Konstante, NICHT aus [PREVIEW_ROLES] abgeleitet -- spiegelt
     * `DatevRoutes.DATEV_FILE_DOWNLOAD_ROLES`. */
    val FILE_DOWNLOAD_ROLES: Set<AccountRole> = setOf(AccountRole.TREASURER, AccountRole.ADMIN)

    fun canPreview(role: AccountRole?): Boolean = role in PREVIEW_ROLES

    fun canDownload(role: AccountRole?): Boolean = role in FILE_DOWNLOAD_ROLES

    /** The download button/link is only ever rendered active when BOTH the role permits it AND the
     * server has reported the period as [exportable] (`DatevExportPreviewDto.exportable`) -- a
     * blocked period must never offer a download that would just come back 409. */
    fun canDownloadNow(
        role: AccountRole?,
        exportable: Boolean,
    ): Boolean = canDownload(role) && exportable
}
