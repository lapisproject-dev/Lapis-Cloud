package network.lapis.cloud.server.security

import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.server.db.generated.AccountTable
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.MemberStatus
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.core.statements.UpdateBuilder
import org.jetbrains.exposed.v1.jdbc.update
import kotlin.uuid.Uuid

/**
 * V155 -- THE one place that touches `account.role`, `account.is_admin` and `account.admin_changed_at` for authorization purposes.
 *
 * Administrator rights are a FLAG independent of the stored base role (MEMBER, BOARD, TREASURER; never ADMIN). The EFFECTIVE role of an
 * account is ADMIN when the flag is set, else the base role -- every pre-V155 gate (`CurrentMember.role`, DTO `role`, `ESCALATED_ROLES`)
 * keeps reading the effective role. Reading the raw column anywhere else silently loses administrators, which
 * `AccountAdminFlagTripwireTest` forbids.
 */
internal object AccountRights {
    /** SQL: accounts holding the administrator right. */
    fun adminAccountsPredicate(): Op<Boolean> = AccountTable.isAdmin eq true

    /** SQL for a LEFT-joined account: no account at all, or an account without the administrator right (NULL-safe). */
    fun notAdminOrNoAccountPredicate(): Op<Boolean> = AccountTable.role.isNull() or (AccountTable.isAdmin eq false)

    /** SQL: the EFFECTIVE role is one of [roles] (ADMIN in [roles] additionally matches every account with the flag). */
    fun effectiveRoleIn(roles: Collection<AccountRole>): Op<Boolean> {
        val base = roles.filter { it != AccountRole.ADMIN }
        val withAdmin = AccountRole.ADMIN in roles
        return when {
            withAdmin && base.isEmpty() -> adminAccountsPredicate()
            withAdmin -> (AccountTable.role inList base) or adminAccountsPredicate()
            else -> (AccountTable.role inList base)
        }
    }

    fun isAdmin(row: ResultRow): Boolean = row[AccountTable.isAdmin]

    /** Tenure clock of the administrator right (UTC); `null` = pre-existing admin, tenured. */
    fun adminChangedAt(row: ResultRow): LocalDateTime? = row[AccountTable.adminChangedAt]

    fun baseRole(row: ResultRow): AccountRole = row[AccountTable.role]

    fun effectiveRole(row: ResultRow): AccountRole = if (isAdmin(row)) AccountRole.ADMIN else baseRole(row)

    /** For `leftJoin`/`getOrNull` paths: `null` when the row carries no account. */
    fun effectiveRoleOrNull(row: ResultRow): AccountRole? {
        val base = row.getOrNull(AccountTable.role) ?: return null
        return if (row.getOrNull(AccountTable.isAdmin) == true) AccountRole.ADMIN else base
    }

    fun baseRoleOrNull(row: ResultRow): AccountRole? = row.getOrNull(AccountTable.role)

    fun isAdminOrFalse(row: ResultRow): Boolean = row.getOrNull(AccountTable.isAdmin) == true

    /** Role FACTS for rule evaluation: the base role plus ADMIN when the right is held (a BOARD administrator matches both rules). */
    fun roleFacts(row: ResultRow): Set<AccountRole> = if (isAdmin(row)) setOf(baseRole(row), AccountRole.ADMIN) else setOf(baseRole(row))

    fun currentMember(
        row: ResultRow,
        status: MemberStatus,
    ): CurrentMember =
        CurrentMember(
            memberId = row[AccountTable.memberId],
            baseRole = baseRole(row),
            isAdmin = isAdmin(row),
            status = status,
        )

    /** The only writer of the role columns at INSERT time: the base role can never be ADMIN, the admin stamp is set with the flag. */
    fun writeInitial(
        stmt: UpdateBuilder<*>,
        baseRole: AccountRole,
        isAdmin: Boolean,
        now: LocalDateTime?,
    ) {
        require(baseRole != AccountRole.ADMIN) { "ADMIN is a right (is_admin), not a base role" }
        stmt[AccountTable.role] = baseRole
        stmt[AccountTable.isAdmin] = isAdmin
        if (isAdmin && now != null) stmt[AccountTable.adminChangedAt] = now
    }

    /** Maps a legacy "role incl. ADMIN" value to (base role, flag) for callers that still hold an effective role. */
    fun splitEffective(role: AccountRole): Pair<AccountRole, Boolean> =
        if (role == AccountRole.ADMIN) AccountRole.MEMBER to true else role to false

    /** The only writer of `is_admin`/`admin_changed_at` after INSERT -- call only under the union lock. */
    fun setAdminFlagLocked(
        targetId: Uuid,
        enabled: Boolean,
        now: LocalDateTime,
    ) {
        AccountTable.update({ AccountTable.memberId eq targetId }) {
            it[isAdmin] = enabled
            it[adminChangedAt] = now
        }
    }

    /** The only writer of a changed BASE role -- call only under the union lock. Stamps `role_changed_at`. */
    fun setBaseRoleLocked(
        targetId: Uuid,
        newRole: AccountRole,
        now: LocalDateTime,
    ) {
        require(newRole != AccountRole.ADMIN) { "ADMIN is a right (is_admin), not a base role" }
        AccountTable.update({ AccountTable.memberId eq targetId }) {
            it[role] = newRole
            it[roleChangedAt] = now
        }
    }
}
