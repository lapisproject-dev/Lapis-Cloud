package network.lapis.cloud.client

import network.lapis.cloud.shared.domain.AccountRole

/**
 * Welle V1.4.5.1.1 -- DOM-freier Spiegel der beiden Rollenstufen, die der Server wirklich
 * durchsetzt: `BANK_STATEMENT_READ_ROLES`/`BANK_STATEMENT_WRITE_ROLES` (`BankStatementService.kt`)
 * und `BANK_STATEMENT_UPLOAD_ROLES` (`BankStatementRoutes.kt`, identisch mit WRITE_ROLES).
 *
 * [READ_ROLES] und [WRITE_ROLES] sind bewusst ZWEI getrennte Konstanten, NICHT
 * "[READ_ROLES] minus BOARD" -- exakt die Drift-Falle, vor der vergleichbare Authz-Spiegel in
 * diesem Client (z.B. für DATEV-Export) bereits warnen.
 */
object BankStatementAuthzUi {
    val READ_ROLES: Set<AccountRole> = setOf(AccountRole.TREASURER, AccountRole.BOARD, AccountRole.ADMIN)
    val WRITE_ROLES: Set<AccountRole> = setOf(AccountRole.TREASURER, AccountRole.ADMIN)

    fun canRead(role: AccountRole?): Boolean = role in READ_ROLES

    fun canWrite(role: AccountRole?): Boolean = role in WRITE_ROLES
}
