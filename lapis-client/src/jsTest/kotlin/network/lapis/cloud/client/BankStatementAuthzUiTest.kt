package network.lapis.cloud.client

import network.lapis.cloud.shared.domain.AccountRole
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Welle V1.4.5.1.1 -- pins [BankStatementAuthzUi] against the server's real role sets
 * (`BankStatementService.kt`'s `BANK_STATEMENT_READ_ROLES`/`BANK_STATEMENT_WRITE_ROLES`). The
 * BOARD case is the load-bearing one: BOARD can read but must never see a write affordance.
 */
class BankStatementAuthzUiTest {
    @Test
    fun canRead_treasurerBoardAdmin_areTrue() {
        assertTrue(BankStatementAuthzUi.canRead(AccountRole.TREASURER))
        assertTrue(BankStatementAuthzUi.canRead(AccountRole.BOARD))
        assertTrue(BankStatementAuthzUi.canRead(AccountRole.ADMIN))
    }

    @Test
    fun canRead_memberOrNull_isFalse() {
        assertFalse(BankStatementAuthzUi.canRead(AccountRole.MEMBER))
        assertFalse(BankStatementAuthzUi.canRead(null))
    }

    @Test
    fun canWrite_treasurerAdmin_areTrue() {
        assertTrue(BankStatementAuthzUi.canWrite(AccountRole.TREASURER))
        assertTrue(BankStatementAuthzUi.canWrite(AccountRole.ADMIN))
    }

    @Test
    fun canWrite_board_isFalse() {
        // Der Kernfall: BOARD darf lesen, aber nie einen Schreib-Affordanz sehen.
        assertFalse(BankStatementAuthzUi.canWrite(AccountRole.BOARD))
    }

    @Test
    fun canWrite_memberOrNull_isFalse() {
        assertFalse(BankStatementAuthzUi.canWrite(AccountRole.MEMBER))
        assertFalse(BankStatementAuthzUi.canWrite(null))
    }

    @Test
    fun writeRoles_isSubsetOfReadRoles_withoutBeingDerivedFromIt() {
        assertTrue(BankStatementAuthzUi.READ_ROLES.containsAll(BankStatementAuthzUi.WRITE_ROLES))
    }
}
