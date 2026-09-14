package network.lapis.cloud.client

import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.NettingCandidateDto
import network.lapis.cloud.shared.domain.OpenItemDto
import network.lapis.cloud.shared.domain.OpenItemStatusSets
import network.lapis.cloud.shared.domain.OpenItemSummaryDto

/**
 * Welle V1.4.15 "Kreditoren-/Debitorenbuchhaltung" -- DOM-freier Spiegel der Rollenstufen, die der
 * Server wirklich durchsetzt (`OpenItemService`/`ReceivableDunningService`):
 * `OPEN_ITEM_READ_ROLES`/`OPEN_ITEM_WRITE_ROLES` plus ADMIN-only für die Mahnstufen-CRUD. Drei
 * getrennte Konstanten, NICHT "[WRITE_ROLES] minus TREASURER" -- gleiche Drift-Falle, vor der
 * [BankAccountAuthzUi] bereits warnt.
 */
object OpenItemAuthzUi {
    val READ_ROLES: Set<AccountRole> = setOf(AccountRole.TREASURER, AccountRole.BOARD, AccountRole.ADMIN)
    val WRITE_ROLES: Set<AccountRole> = setOf(AccountRole.TREASURER, AccountRole.ADMIN)
    val ADMIN_ROLES: Set<AccountRole> = setOf(AccountRole.ADMIN)

    fun canRead(role: AccountRole?): Boolean = role in READ_ROLES

    fun canWrite(role: AccountRole?): Boolean = role in WRITE_ROLES

    /** Mahnstufen-CRUD (createReceivableDunningLevel/update/deactivate, enable/disableReceivableDunning). */
    fun canManageDunningLevels(role: AccountRole?): Boolean = role in ADMIN_ROLES

    /** [candidate] is accepted for call-site symmetry with [canSettle] even though every
     *  candidate is equally nettable today -- see `previewNetting`'s own server-side
     *  re-validation for the actual gate. */
    fun canNet(
        role: AccountRole?,
        @Suppress("UNUSED_PARAMETER") candidate: NettingCandidateDto,
    ): Boolean = role in WRITE_ROLES

    fun canSettle(
        role: AccountRole?,
        item: OpenItemDto,
    ): Boolean = role in WRITE_ROLES && item.status in OpenItemStatusSets.SETTLEABLE && item.creationJournalEntryId != null

    fun canRetryPosting(
        role: AccountRole?,
        item: OpenItemDto,
    ): Boolean = role in WRITE_ROLES && item.creationPostingError != null

    fun showAccountsNotConfiguredBand(summary: OpenItemSummaryDto?): Boolean = summary != null && !summary.accountsConfigured
}
