package network.lapis.cloud.client

import dev.kilua.rpc.types.toDecimal
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.GemeinnuetzigkeitSphere
import network.lapis.cloud.shared.domain.OpenItemDirection
import network.lapis.cloud.shared.domain.OpenItemDto
import network.lapis.cloud.shared.domain.OpenItemStatus
import kotlin.test.Test
import kotlin.test.assertFalse
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
    ) = OpenItemDto(
        id = "oi-1",
        direction = OpenItemDirection.PAYABLE,
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
    fun readRoles_isSupersetOfWriteRoles_withoutBeingDerivedFromIt() {
        assertTrue(OpenItemAuthzUi.READ_ROLES.containsAll(OpenItemAuthzUi.WRITE_ROLES))
    }

    @Test
    fun writeRoles_isSupersetOfAdminRoles_withoutBeingDerivedFromIt() {
        assertTrue(OpenItemAuthzUi.WRITE_ROLES.containsAll(OpenItemAuthzUi.ADMIN_ROLES))
    }
}
