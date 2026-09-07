package network.lapis.cloud.client

import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.AccountingExportPreviewDto

/**
 * Welle V1.4.5.3 "lexoffice-Live-Anbindung" -- pure, DOM-free client-side mirror of
 * `network.lapis.cloud.server.rpc.AccountingExportService`'s own `ACCOUNTING_EXPORT_ROLES`
 * (TREASURER/ADMIN, deliberately NO BOARD -- see `IAccountingExportService` KDoc for why this is
 * narrower than [DatevAuthzUi.PREVIEW_ROLES]). Same "UX nicety on top of the server's real
 * authority" caveat every other `*AuthzUi` object in this client carries.
 */
object AccountingExportAuthzUi {
    val MANAGE_ROLES: Set<AccountRole> = setOf(AccountRole.TREASURER, AccountRole.ADMIN)

    fun canManage(role: AccountRole?): Boolean = role in MANAGE_ROLES

    /** The "Übertragen" button is only ever active when BOTH the role permits it AND the server
     * has reported the period as [AccountingExportPreviewDto.exportable] -- a blocked period must
     * never offer a start that would just come back 409. */
    fun canStartRun(
        role: AccountRole?,
        preview: AccountingExportPreviewDto?,
    ): Boolean = canManage(role) && preview?.exportable == true
}
