package network.lapis.cloud.server.testdb

import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.server.db.generated.AccountTable
import network.lapis.cloud.shared.domain.AccountRole
import org.jetbrains.exposed.v1.core.statements.UpdateBuilder

/**
 * V155 test shim: administrator rights are the independent flag `account.is_admin`, never a value of `account.role`. A test that wants an
 * account with the pre-V155 "role" [role] writes it through this helper: ADMIN becomes (MEMBER + is_admin), every other value clears the flag.
 * Use inside `AccountTable.insert { }` / `AccountTable.update(...) { }`.
 */
fun UpdateBuilder<*>.legacyAccountRole(
    role: AccountRole,
    now: LocalDateTime? = null,
) {
    if (role == AccountRole.ADMIN) {
        this[AccountTable.role] = AccountRole.MEMBER
        this[AccountTable.isAdmin] = true
        if (now != null) this[AccountTable.adminChangedAt] = now
    } else {
        this[AccountTable.role] = role
        this[AccountTable.isAdmin] = false
    }
}
