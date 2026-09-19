package network.lapis.cloud.client

import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.OpenItemDirection
import network.lapis.cloud.shared.domain.OpenItemDto
import network.lapis.cloud.shared.domain.OpenItemStatusSets
import network.lapis.cloud.shared.domain.OpenItemSummaryDto
import network.lapis.cloud.shared.domain.ReceivableDunningNoticeStatus
import network.lapis.cloud.shared.domain.ReceivableDunningSettingsDto

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

    /**
     * `ICrmService.listContacts` ist BOARD/ADMIN -- **nicht** [WRITE_ROLES] (TREASURER/ADMIN). Ein
     * TREASURER darf offene Posten anlegen, aber keine CRM-Kontaktliste laden; ein CRM-Kontakt-Select
     * im Formular wäre für ihn ein stiller 403-Toast. Eigene Konstante, nicht aus [WRITE_ROLES]
     * abgeleitet -- gleiche Drift-Warnung wie `DunningAuthzUi.FILE_ACCESS_ROLES`.
     */
    val CRM_LINK_ROLES: Set<AccountRole> = setOf(AccountRole.BOARD, AccountRole.ADMIN)

    fun canRead(role: AccountRole?): Boolean = role in READ_ROLES

    fun canWrite(role: AccountRole?): Boolean = role in WRITE_ROLES

    /** Mahnstufen-CRUD (createReceivableDunningLevel/update/deactivate, enable/disableReceivableDunning). */
    fun canManageDunningLevels(role: AccountRole?): Boolean = role in ADMIN_ROLES

    // Audit-Fund N16: `canNet(role, candidate)` ist entfernt. Es wurde von keinem Aufrufer je
    // benutzt (nur von seinem eigenen Test), sein `candidate`-Parameter war per
    // `@Suppress("UNUSED_PARAMETER")` stillgelegt, und die einzige echte Netting-Schranke ist
    // [canWrite] am Verrechnen-Knopf plus die server-seitige Prüfung in `previewNetting`/
    // `executeNetting` (Gegenseitigkeit, Status, Betrag). Eine Funktion, die nur ihr eigener Test
    // aufruft, sichert nichts ab und behauptet eine Regel, die es nicht gibt.

    fun canSettle(
        role: AccountRole?,
        item: OpenItemDto,
    ): Boolean = role in WRITE_ROLES && item.status in OpenItemStatusSets.SETTLEABLE && item.creationJournalEntryId != null

    /**
     * Nachbuchen der Entstehungsbuchung -- nie für einen abgeschlossenen Posten. Ein stornierter Posten
     * mit `creationPostingError` hat nie eine Entstehungsbuchung bekommen und darf sie auch nicht
     * nachträglich erhalten: die Gegenbuchung (`cancelOpenItem`) kann nicht mehr laufen (Status
     * ist bereits CANCELLED), es entstünde eine Buchung ohne Storno-Gegenstück.
     */
    fun canRetryPosting(
        role: AccountRole?,
        item: OpenItemDto,
    ): Boolean = role in WRITE_ROLES && item.creationPostingError != null && item.status !in OpenItemStatusSets.CLOSED

    fun showAccountsNotConfiguredBand(summary: OpenItemSummaryDto?): Boolean = summary != null && !summary.accountsConfigured

    /** Welle V1.4.21: ein Mahnhinweis lässt sich nur für einen offenen Debitor-Posten ausstellen. */
    fun canIssueDunningNotice(
        role: AccountRole?,
        item: OpenItemDto,
    ): Boolean = role in WRITE_ROLES && item.direction == OpenItemDirection.RECEIVABLE && item.status in OpenItemStatusSets.SETTLEABLE

    /** Wie `canIssueDunningNotice` für Überspringen der nächsten Stufe (gleiche Server-Rollen, gleicher Posten-Zustand). */
    fun canSkipDunningLevel(
        role: AccountRole?,
        item: OpenItemDto,
    ): Boolean = canIssueDunningNotice(role, item) && item.nextDunningLevelNumber != null

    /** `cancelReceivableDunningNotice` -- nur ein ausgestellter Mahnhinweis ist stornierbar. */
    fun canCancelDunningNotice(
        role: AccountRole?,
        status: ReceivableDunningNoticeStatus,
    ): Boolean = role in WRITE_ROLES && status == ReceivableDunningNoticeStatus.ISSUED

    /** Warnband: aktiviert, aber keine aktive Stufe -- es wird nichts gemahnt. */
    fun showNoActiveReceivableLevelWarning(settings: ReceivableDunningSettingsDto): Boolean =
        settings.receivableDunningEnabled && settings.activeLevelCount == 0

    /** Hinweis: aktiviert, aber der automatische Poller ist per ENV aus -- nur manuelles Mahnen. */
    fun showPollerDisabledWarning(settings: ReceivableDunningSettingsDto): Boolean =
        settings.receivableDunningEnabled && !settings.pollerEnabled

    fun canLinkCrmContact(role: AccountRole?): Boolean = role in CRM_LINK_ROLES
}
