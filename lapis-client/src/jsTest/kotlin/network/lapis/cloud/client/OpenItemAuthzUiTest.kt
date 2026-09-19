package network.lapis.cloud.client

import dev.kilua.rpc.types.toDecimal
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.GemeinnuetzigkeitSphere
import network.lapis.cloud.shared.domain.OpenItemDirection
import network.lapis.cloud.shared.domain.OpenItemDto
import network.lapis.cloud.shared.domain.OpenItemStatus
import network.lapis.cloud.shared.domain.OpenItemStatusSets
import network.lapis.cloud.shared.domain.ReceivableDunningNoticeStatus
import network.lapis.cloud.shared.domain.ReceivableDunningSettingsDto
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Welle V1.4.15 "Kreditoren-/Debitorenbuchhaltung" -- pins [OpenItemAuthzUi] against the server's
 * real role sets (`OpenItemService.kt`'s `OPEN_ITEM_READ_ROLES`/`OPEN_ITEM_WRITE_ROLES` +
 * `ReceivableDunningService.kt`'s ADMIN-only level CRUD). Mirrors [BankAccountAuthzUiTest]'s shape.
 */
class OpenItemAuthzUiTest {
    private fun item(
        status: OpenItemStatus = OpenItemStatus.OPEN,
        creationJournalEntryId: String? = "je-1",
        creationPostingError: String? = null,
        direction: OpenItemDirection = OpenItemDirection.PAYABLE,
        nextDunningLevelNumber: Int? = null,
        highestDunningLevelNumber: Int? = null,
        nextDunningLevelDueOn: LocalDate? = null,
    ) = OpenItemDto(
        id = "oi-1",
        direction = direction,
        counterpartyName = "Muster GmbH",
        counterpartyKey = "muster gmbh",
        itemDate = LocalDate(2026, 1, 1),
        dueDate = LocalDate(2026, 2, 1),
        amount = 100.0.toDecimal(),
        openAmount = 100.0.toDecimal(),
        contraAccountId = "acc-1",
        contraAccountNumber = "50000",
        contraAccountName = "Wareneinsatz",
        sphere = GemeinnuetzigkeitSphere.IDEELLER_BEREICH,
        status = status,
        daysOverdue = 0,
        asOf = LocalDate(2026, 1, 15),
        creationJournalEntryId = creationJournalEntryId,
        creationPostingError = creationPostingError,
        createdByMemberId = "m-1",
        createdAt = LocalDateTime(2026, 1, 1, 10, 0),
        highestDunningLevelNumber = highestDunningLevelNumber,
        nextDunningLevelNumber = nextDunningLevelNumber,
        nextDunningLevelDueOn = nextDunningLevelDueOn,
    )

    @Test
    fun canRead_treasurerBoardAdmin_areTrue() {
        assertTrue(OpenItemAuthzUi.canRead(AccountRole.TREASURER))
        assertTrue(OpenItemAuthzUi.canRead(AccountRole.BOARD))
        assertTrue(OpenItemAuthzUi.canRead(AccountRole.ADMIN))
    }

    @Test
    fun canRead_memberOrNull_isFalse() {
        assertFalse(OpenItemAuthzUi.canRead(AccountRole.MEMBER))
        assertFalse(OpenItemAuthzUi.canRead(null))
    }

    @Test
    fun canWrite_treasurerAdmin_areTrue_board_isFalse() {
        assertTrue(OpenItemAuthzUi.canWrite(AccountRole.TREASURER))
        assertTrue(OpenItemAuthzUi.canWrite(AccountRole.ADMIN))
        assertFalse(OpenItemAuthzUi.canWrite(AccountRole.BOARD))
    }

    @Test
    fun canManageDunningLevels_adminOnly() {
        assertTrue(OpenItemAuthzUi.canManageDunningLevels(AccountRole.ADMIN))
        // Der Kernfall: TREASURER darf offene Posten schreiben, aber nie Mahnstufen verwalten.
        assertFalse(OpenItemAuthzUi.canManageDunningLevels(AccountRole.TREASURER))
        assertFalse(OpenItemAuthzUi.canManageDunningLevels(AccountRole.BOARD))
    }

    @Test
    fun canSettle_requiresWriteRole_settleableStatus_andBookedItem() {
        assertTrue(OpenItemAuthzUi.canSettle(AccountRole.TREASURER, item()))
        assertFalse(OpenItemAuthzUi.canSettle(AccountRole.BOARD, item()))
        assertFalse(OpenItemAuthzUi.canSettle(AccountRole.TREASURER, item(status = OpenItemStatus.SETTLED)))
        assertFalse(OpenItemAuthzUi.canSettle(AccountRole.TREASURER, item(creationJournalEntryId = null)))
    }

    @Test
    fun canRetryPosting_requiresWriteRole_andPendingError() {
        assertTrue(
            OpenItemAuthzUi.canRetryPosting(
                AccountRole.TREASURER,
                item(creationJournalEntryId = null, creationPostingError = "receivables_account_not_configured"),
            ),
        )
        assertFalse(OpenItemAuthzUi.canRetryPosting(AccountRole.TREASURER, item()))
        assertFalse(OpenItemAuthzUi.canRetryPosting(AccountRole.BOARD, item(creationJournalEntryId = null, creationPostingError = "x")))
    }

    @Test
    fun canRetryPosting_neverForClosedItems() {
        // Ein stornierter Posten mit offenem Buchungsfehler darf nie nachträglich gebucht werden.
        OpenItemStatusSets.CLOSED.forEach { status ->
            assertFalse(
                OpenItemAuthzUi.canRetryPosting(
                    AccountRole.TREASURER,
                    item(status = status, creationJournalEntryId = null, creationPostingError = "payables_account_not_configured"),
                ),
                "status $status",
            )
        }
        OpenItemStatusSets.SETTLEABLE.forEach { status ->
            assertTrue(
                OpenItemAuthzUi.canRetryPosting(
                    AccountRole.ADMIN,
                    item(status = status, creationJournalEntryId = null, creationPostingError = "payables_account_not_configured"),
                ),
            )
        }
    }

    @Test
    fun readRoles_isSupersetOfWriteRoles_withoutBeingDerivedFromIt() {
        assertTrue(OpenItemAuthzUi.READ_ROLES.containsAll(OpenItemAuthzUi.WRITE_ROLES))
    }

    @Test
    fun writeRoles_isSupersetOfAdminRoles_withoutBeingDerivedFromIt() {
        assertTrue(OpenItemAuthzUi.WRITE_ROLES.containsAll(OpenItemAuthzUi.ADMIN_ROLES))
    }

    // ── Welle V1.4.21 ────────────────────────────────────────────────────────────────────────────

    @Test
    fun canIssueDunningNotice_onlyTreasurerAdmin_onOpenReceivables() {
        val receivable = item(direction = OpenItemDirection.RECEIVABLE)
        assertTrue(OpenItemAuthzUi.canIssueDunningNotice(AccountRole.TREASURER, receivable))
        assertTrue(OpenItemAuthzUi.canIssueDunningNotice(AccountRole.ADMIN, receivable))
        assertFalse(OpenItemAuthzUi.canIssueDunningNotice(AccountRole.BOARD, receivable))
        assertFalse(OpenItemAuthzUi.canIssueDunningNotice(AccountRole.TREASURER, item(direction = OpenItemDirection.PAYABLE)))
        assertFalse(
            OpenItemAuthzUi.canIssueDunningNotice(
                AccountRole.TREASURER,
                item(direction = OpenItemDirection.RECEIVABLE, status = OpenItemStatus.SETTLED),
            ),
        )
    }

    @Test
    fun canSkipDunningLevel_needsANextLevel() {
        assertTrue(
            OpenItemAuthzUi.canSkipDunningLevel(
                AccountRole.TREASURER,
                item(direction = OpenItemDirection.RECEIVABLE, nextDunningLevelNumber = 2),
            ),
        )
        assertFalse(OpenItemAuthzUi.canSkipDunningLevel(AccountRole.TREASURER, item(direction = OpenItemDirection.RECEIVABLE)))
        assertFalse(
            OpenItemAuthzUi.canSkipDunningLevel(
                AccountRole.BOARD,
                item(direction = OpenItemDirection.RECEIVABLE, nextDunningLevelNumber = 2),
            ),
        )
    }

    @Test
    fun canCancelDunningNotice_onlyIssuedNotices_onlyWriteRoles() {
        assertTrue(OpenItemAuthzUi.canCancelDunningNotice(AccountRole.TREASURER, ReceivableDunningNoticeStatus.ISSUED))
        assertTrue(OpenItemAuthzUi.canCancelDunningNotice(AccountRole.ADMIN, ReceivableDunningNoticeStatus.ISSUED))
        assertFalse(OpenItemAuthzUi.canCancelDunningNotice(AccountRole.BOARD, ReceivableDunningNoticeStatus.ISSUED))
        assertFalse(OpenItemAuthzUi.canCancelDunningNotice(AccountRole.TREASURER, ReceivableDunningNoticeStatus.CANCELLED))
        assertFalse(OpenItemAuthzUi.canCancelDunningNotice(AccountRole.TREASURER, ReceivableDunningNoticeStatus.SKIPPED))
    }

    @Test
    fun showNoActiveReceivableLevelWarning_onlyWhenEnabledWithZeroActiveLevels() {
        assertTrue(
            OpenItemAuthzUi.showNoActiveReceivableLevelWarning(
                ReceivableDunningSettingsDto(receivableDunningEnabled = true, activeLevelCount = 0),
            ),
        )
        assertFalse(
            OpenItemAuthzUi.showNoActiveReceivableLevelWarning(
                ReceivableDunningSettingsDto(receivableDunningEnabled = true, activeLevelCount = 2),
            ),
        )
        assertFalse(
            OpenItemAuthzUi.showNoActiveReceivableLevelWarning(
                ReceivableDunningSettingsDto(receivableDunningEnabled = false, activeLevelCount = 0),
            ),
        )
    }

    @Test
    fun showPollerDisabledWarning_onlyWhenEnabledAndPollerOff() {
        assertTrue(
            OpenItemAuthzUi.showPollerDisabledWarning(ReceivableDunningSettingsDto(receivableDunningEnabled = true, pollerEnabled = false)),
        )
        assertFalse(
            OpenItemAuthzUi.showPollerDisabledWarning(ReceivableDunningSettingsDto(receivableDunningEnabled = true, pollerEnabled = true)),
        )
        assertFalse(
            OpenItemAuthzUi.showPollerDisabledWarning(
                ReceivableDunningSettingsDto(receivableDunningEnabled = false, pollerEnabled = false),
            ),
        )
    }

    // K3-Verwandter: `ICrmService.listContacts` ist BOARD/ADMIN, `WRITE_ROLES` TREASURER/ADMIN. Ein
    // TREASURER darf offene Posten anlegen, aber KEINE CRM-Kontaktliste laden.
    @Test
    fun canLinkCrmContact_boardAndAdmin_notTreasurer() {
        assertTrue(OpenItemAuthzUi.canLinkCrmContact(AccountRole.BOARD))
        assertTrue(OpenItemAuthzUi.canLinkCrmContact(AccountRole.ADMIN))
        assertFalse(OpenItemAuthzUi.canLinkCrmContact(AccountRole.TREASURER))
        assertFalse(OpenItemAuthzUi.canLinkCrmContact(null))
    }

    @Test
    fun crmLinkRoles_isNotDerivedFromWriteRoles() {
        assertNotEquals(OpenItemAuthzUi.WRITE_ROLES, OpenItemAuthzUi.CRM_LINK_ROLES)
    }

    // ── Audit-Fund B1: die drei Mahnfelder, wie der Server sie wirklich liefert ──────────────────

    /**
     * Genau die Wertekombinationen, die `OpenItemDunningProgressTest` (lapis-server) end-to-end aus
     * echten Fixtures erzeugt -- vorher setzte der Server alle drei Felder NIE, und weil dieser Test
     * seine DTOs selbst baut, konnte er das gar nicht sehen. Der Server-Round-Trip-Test ist die
     * Absicherung, dass die Werte wirklich ankommen; dieser hier ist die Absicherung, dass die
     * Bedienoberfläche mit genau diesen Werten das Richtige freigibt.
     */
    @Test
    fun dunningFields_asTheServerProducesThem_driveTheDunningActions() {
        // Frischer Debitor-Posten: nichts ausgestellt, Stufe 1 angekündigt (dueDate + graceDays).
        val fresh =
            item(
                direction = OpenItemDirection.RECEIVABLE,
                highestDunningLevelNumber = null,
                nextDunningLevelNumber = 1,
                nextDunningLevelDueOn = LocalDate(2026, 2, 8),
            )
        assertTrue(OpenItemAuthzUi.canIssueDunningNotice(AccountRole.TREASURER, fresh))
        assertTrue(OpenItemAuthzUi.canSkipDunningLevel(AccountRole.TREASURER, fresh))
        assertEquals("–", receivableDunningLevelLabel(fresh.highestDunningLevelNumber))

        // Nach dem Ausstellen von Stufe 1: Spalte zeigt Stufe 1, angekündigt ist Stufe 2.
        val afterFirst =
            fresh.copy(
                highestDunningLevelNumber = 1,
                nextDunningLevelNumber = 2,
                nextDunningLevelDueOn = LocalDate(2026, 2, 22),
            )
        assertTrue(OpenItemAuthzUi.canSkipDunningLevel(AccountRole.TREASURER, afterFirst))
        assertNotEquals("–", receivableDunningLevelLabel(afterFirst.highestDunningLevelNumber))

        // Letzte aktive Stufe ausgestellt: Überspringen ist aus, die Spalte bleibt aussagekräftig.
        val exhausted = fresh.copy(highestDunningLevelNumber = 2, nextDunningLevelNumber = null, nextDunningLevelDueOn = null)
        assertTrue(OpenItemAuthzUi.canIssueDunningNotice(AccountRole.TREASURER, exhausted))
        assertFalse(OpenItemAuthzUi.canSkipDunningLevel(AccountRole.TREASURER, exhausted))
        assertNotEquals("–", receivableDunningLevelLabel(exhausted.highestDunningLevelNumber))
    }
}
