package network.lapis.cloud.client

import network.lapis.cloud.shared.domain.AccountRole
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Welle V1.4.14 "Mehrere Bankkonten" + Wave 2 "FinTS/HBCI-Live-Kontoabruf" -- pins
 * [BankAccountAuthzUi] against the server's real role sets (`BankAccountService.kt`'s
 * `BANK_ACCOUNT_READ_ROLES`/`BANK_ACCOUNT_WRITE_ROLES` + the five ADMIN-only FinTS methods). The
 * TREASURER case is the load-bearing one for [BankAccountAuthzUi.canManageFinTs]: TREASURER can
 * read AND write ordinary account data but must never see a FinTS control -- strictly narrower
 * than [BankAccountAuthzUi.WRITE_ROLES], same load-bearing distinction
 * [BankStatementAuthzUiTest]'s own BOARD case establishes for read-vs-write.
 */
class BankAccountAuthzUiTest {
    @Test
    fun canRead_treasurerBoardAdmin_areTrue() {
        assertTrue(BankAccountAuthzUi.canRead(AccountRole.TREASURER))
        assertTrue(BankAccountAuthzUi.canRead(AccountRole.BOARD))
        assertTrue(BankAccountAuthzUi.canRead(AccountRole.ADMIN))
    }

    @Test
    fun canRead_memberOrNull_isFalse() {
        assertFalse(BankAccountAuthzUi.canRead(AccountRole.MEMBER))
        assertFalse(BankAccountAuthzUi.canRead(null))
    }

    @Test
    fun canWrite_treasurerAdmin_areTrue() {
        assertTrue(BankAccountAuthzUi.canWrite(AccountRole.TREASURER))
        assertTrue(BankAccountAuthzUi.canWrite(AccountRole.ADMIN))
    }

    @Test
    fun canWrite_board_isFalse() {
        assertFalse(BankAccountAuthzUi.canWrite(AccountRole.BOARD))
    }

    @Test
    fun canManageFinTs_adminOnly() {
        assertTrue(BankAccountAuthzUi.canManageFinTs(AccountRole.ADMIN))
    }

    @Test
    fun canManageFinTs_treasurerBoardMemberOrNull_areFalse() {
        // Der Kernfall: TREASURER darf Konten schreiben, aber nie eine FinTS-Bedienung sehen.
        assertFalse(BankAccountAuthzUi.canManageFinTs(AccountRole.TREASURER))
        assertFalse(BankAccountAuthzUi.canManageFinTs(AccountRole.BOARD))
        assertFalse(BankAccountAuthzUi.canManageFinTs(AccountRole.MEMBER))
        assertFalse(BankAccountAuthzUi.canManageFinTs(null))
    }

    @Test
    fun writeRoles_isSubsetOfReadRoles_withoutBeingDerivedFromIt() {
        assertTrue(BankAccountAuthzUi.READ_ROLES.containsAll(BankAccountAuthzUi.WRITE_ROLES))
    }

    @Test
    fun finTsRoles_isSubsetOfWriteRoles_withoutBeingDerivedFromIt() {
        assertTrue(BankAccountAuthzUi.WRITE_ROLES.containsAll(BankAccountAuthzUi.FINTS_ROLES))
    }
}
