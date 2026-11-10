package network.lapis.cloud.server.bootstrap

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.serialization.json.Json
import network.lapis.cloud.server.audit.AuditLogRecorder
import network.lapis.cloud.server.db.DbClock
import network.lapis.cloud.server.db.forMemberUpdate
import network.lapis.cloud.server.db.generated.AccountTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.server.security.ESCALATED_ROLES
import network.lapis.cloud.server.security.SessionStore
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.AuditAction
import network.lapis.cloud.shared.domain.AuditEntityType
import network.lapis.cloud.shared.domain.MemberChangeSnapshot
import network.lapis.cloud.shared.domain.MemberStatus
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update

private val logger = KotlinLogging.logger {}

/**
 * Welle V1.9.298 (review fix) -- one-time, idempotent startup repair of legacy data. Before donors could sign in, an administrator could set a
 * BOARD/TREASURER/ADMIN member to [MemberStatus.DONOR] while the account row kept its escalated role; that was harmless because a donor was
 * login-blocked. Since donors may sign in, such an account would come back with its old administrative rights (and would count as a remaining
 * administrator / peer approver). This job downgrades every account of a [MemberStatus.DONOR] member whose role is in [ESCALATED_ROLES] to
 * [AccountRole.MEMBER], writes one audit entry per account (no actor, before/after snapshot) and ends the account's open sessions.
 *
 * It deliberately does NOT run the last-admin protection: a donor must not count as an administrator, so that check would be wrong here.
 * Runs once per start on the production entry point (never in `module()`); a second run finds nothing to do.
 */
object DonorRoleDowngrade {
    /** Returns the number of downgraded accounts. */
    fun run(): Int {
        val memberIds =
            transaction {
                (AccountTable innerJoin MemberTable)
                    .selectAll()
                    .where {
                        (MemberTable.status eq MemberStatus.DONOR) and (AccountTable.role inList ESCALATED_ROLES)
                    }.map { it[AccountTable.memberId] }
            }
        if (memberIds.isEmpty()) return 0
        var count = 0
        for (memberId in memberIds) {
            transaction {
                val row =
                    (AccountTable innerJoin MemberTable)
                        .selectAll()
                        .where {
                            (MemberTable.id eq memberId) and (MemberTable.status eq MemberStatus.DONOR) and
                                (AccountTable.role inList ESCALATED_ROLES)
                        }.forMemberUpdate()
                        .singleOrNull() ?: return@transaction
                val oldRole = row[AccountTable.role]
                val now = DbClock.nowLocalDateTime()
                AccountTable.update({ AccountTable.memberId eq memberId }) {
                    it[role] = AccountRole.MEMBER
                    it[roleChangedAt] = now
                }
                val before =
                    MemberChangeSnapshot(
                        displayNameChanged = false,
                        emailChanged = false,
                        status = MemberStatus.DONOR,
                        role = oldRole,
                    )
                AuditLogRecorder.record(
                    actorMemberId = null,
                    actorRole = null,
                    entityType = AuditEntityType.MEMBER,
                    entityId = memberId,
                    action = AuditAction.UPDATE,
                    before = Json.encodeToString(MemberChangeSnapshot.serializer(), before),
                    after = Json.encodeToString(MemberChangeSnapshot.serializer(), before.copy(role = AccountRole.MEMBER)),
                    occurredAt = now,
                )
                count++
            }
            SessionStore.revokeAllForMember(memberId = memberId)
        }
        if (count > 0) logger.warn { "Downgraded $count donor account(s) with an administrative role to MEMBER (V1.9.298 legacy repair)." }
        return count
    }
}
