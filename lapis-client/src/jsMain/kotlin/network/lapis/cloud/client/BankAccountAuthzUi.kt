package network.lapis.cloud.client

import network.lapis.cloud.shared.domain.AccountRole

/**
 * Welle V1.4.14 "Mehrere Bankkonten" + Wave 2 "FinTS/HBCI-Live-Kontoabruf" -- DOM-freier Spiegel der
 * drei Rollenstufen, die der Server wirklich durchsetzt (`BankAccountService.kt`):
 * `BANK_ACCOUNT_READ_ROLES`/`BANK_ACCOUNT_WRITE_ROLES` (Wave 1 CRUD) und -- STRIKT ENGER --
 * ADMIN-only für jede der fünf FinTS-Methoden (Wave 2). [READ_ROLES]/[WRITE_ROLES]/[FINTS_ROLES]
 * sind bewusst DREI getrennte Konstanten, NICHT "[WRITE_ROLES] minus TREASURER" -- exakt die
 * Drift-Falle, vor der [BankStatementAuthzUi] bereits warnt.
 */
object BankAccountAuthzUi {
    val READ_ROLES: Set<AccountRole> = setOf(AccountRole.TREASURER, AccountRole.BOARD, AccountRole.ADMIN)
    val WRITE_ROLES: Set<AccountRole> = setOf(AccountRole.TREASURER, AccountRole.ADMIN)
    val FINTS_ROLES: Set<AccountRole> = setOf(AccountRole.ADMIN)

    fun canRead(role: AccountRole?): Boolean = role in READ_ROLES

    fun canWrite(role: AccountRole?): Boolean = role in WRITE_ROLES

    /** Whether the ADMIN-only FinTS setup/reauth/disable controls should render at all. */
    fun canManageFinTs(role: AccountRole?): Boolean = role in FINTS_ROLES
}
