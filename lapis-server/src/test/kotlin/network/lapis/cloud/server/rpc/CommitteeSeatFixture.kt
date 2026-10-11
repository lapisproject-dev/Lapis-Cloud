package network.lapis.cloud.server.rpc

import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.server.audit.AuditChainVerifier
import network.lapis.cloud.server.db.generated.AccountTable
import network.lapis.cloud.server.db.generated.AuditLogEntryTable
import network.lapis.cloud.server.db.generated.BoardMembershipTable
import network.lapis.cloud.server.db.generated.CommitteeMembershipTable
import network.lapis.cloud.server.db.generated.CommitteeTable
import network.lapis.cloud.server.db.generated.MemberStatusHistoryTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.server.db.generated.TransparenzregisterReminderTable
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.AuditEntityType
import network.lapis.cloud.shared.domain.CommitteeRole
import network.lapis.cloud.shared.domain.CommitteeType
import network.lapis.cloud.shared.domain.MemberStatus
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import kotlin.uuid.Uuid

/**
 * Welle V1.9.102 -- members, committees, seats and audit reads shared by the committee-seat specs. Creates its own rows only (never the dev
 * seed's) and [cleanUp] hard-deletes them. Audit rows the hook writes carry the triggering actor (null for the operator console) and stay (the log is append-only by design).
 */
internal class CommitteeSeatFixture {
    val memberIds = mutableListOf<Uuid>()
    val committeeIds = mutableListOf<Uuid>()

    fun member(
        status: MemberStatus = MemberStatus.ACTIVE,
        role: AccountRole? = AccountRole.MEMBER,
        displayName: String = "Gremium Testperson",
        friendSince: LocalDate? = null,
    ): Uuid {
        val id = Uuid.random()
        transaction {
            MemberTable.insert {
                it[MemberTable.id] = id
                it[MemberTable.displayName] = displayName
                it[email] = "seat-secret-$id@example.org"
                it[MemberTable.status] = status
                it[joinedAt] = LocalDate(2020, 1, 1)
                it[membershipTierId] = null
                if (friendSince != null) it[MemberTable.friendSince] = friendSince
            }
            if (role != null) {
                AccountTable.insert {
                    it[AccountTable.id] = Uuid.random()
                    it[memberId] = id
                    it[AccountTable.role] = role
                }
            }
        }
        memberIds += id
        return id
    }

    fun committee(
        type: CommitteeType = CommitteeType.WORKING_GROUP,
        name: String = "Testgremium ${Uuid.random().toString().take(8)}",
        active: Boolean = true,
    ): Uuid {
        val id = Uuid.random()
        transaction {
            CommitteeTable.insert {
                it[CommitteeTable.id] = id
                it[CommitteeTable.name] = name
                it[CommitteeTable.type] = type
                it[description] = "Testgremium"
                it[CommitteeTable.active] = active
                it[quorumPercent] = 50
                it[createdAt] = LocalDateTime(2026, 1, 1, 0, 0)
            }
        }
        committeeIds += id
        return id
    }

    fun seat(
        committee: Uuid,
        member: Uuid,
        role: CommitteeRole = CommitteeRole.MEMBER,
        since: LocalDate = LocalDate(2026, 1, 1),
        until: LocalDate? = null,
        autoEndReason: MemberStatus? = null,
    ): Uuid {
        val id = Uuid.random()
        transaction {
            CommitteeMembershipTable.insert {
                it[CommitteeMembershipTable.id] = id
                it[committeeId] = committee
                it[memberId] = member
                it[CommitteeMembershipTable.role] = role
                it[CommitteeMembershipTable.since] = since
                it[CommitteeMembershipTable.until] = until
                it[CommitteeMembershipTable.autoEndReason] = autoEndReason
            }
        }
        return id
    }

    fun boardMembership(
        member: Uuid,
        role: CommitteeRole = CommitteeRole.CHAIR,
        startedAt: LocalDate = LocalDate(2026, 1, 1),
    ): Uuid {
        val id = Uuid.random()
        transaction {
            BoardMembershipTable.insert {
                it[BoardMembershipTable.id] = id
                it[memberId] = member
                it[committeeRole] = role
                it[BoardMembershipTable.startedAt] = startedAt
                it[endedAt] = null
            }
        }
        return id
    }

    fun seatRow(id: Uuid): ResultRow =
        transaction { CommitteeMembershipTable.selectAll().where { CommitteeMembershipTable.id eq id }.single() }

    fun boardRow(id: Uuid): ResultRow = transaction { BoardMembershipTable.selectAll().where { BoardMembershipTable.id eq id }.single() }

    fun committeeRow(id: Uuid): ResultRow = transaction { CommitteeTable.selectAll().where { CommitteeTable.id eq id }.single() }

    fun seatsOf(member: Uuid): List<ResultRow> =
        transaction { CommitteeMembershipTable.selectAll().where { CommitteeMembershipTable.memberId eq member }.toList() }

    /** The current highest audit sequence number -- a marker to read "everything written after this point". */
    fun auditMark(): Long =
        transaction {
            AuditLogEntryTable
                .selectAll()
                .orderBy(AuditLogEntryTable.sequenceNumber, SortOrder.DESC)
                .limit(1)
                .map { it[AuditLogEntryTable.sequenceNumber] }
                .firstOrNull() ?: 0L
        }

    fun auditSince(
        mark: Long,
        type: AuditEntityType? = null,
    ): List<ResultRow> =
        transaction {
            AuditLogEntryTable
                .selectAll()
                .where {
                    if (type == null) {
                        AuditLogEntryTable.sequenceNumber greater mark
                    } else {
                        (AuditLogEntryTable.sequenceNumber greater mark) and (AuditLogEntryTable.entityType eq type)
                    }
                }.orderBy(AuditLogEntryTable.sequenceNumber)
                .toList()
        }

    /**
     * Verifies the hash chain over every row written after [mark] (the row before is the anchor); null = valid, else the reason. Other specs
     * hard-delete audit rows of their own members, so the anchor row itself can be missing depending on the spec order -- then the check
     * starts at the second new row (anchored on the first), which still covers every link the hook's own writes created.
     */
    fun chainProblemSince(mark: Long): String? =
        transaction {
            val rows =
                AuditLogEntryTable
                    .selectAll()
                    .where { AuditLogEntryTable.sequenceNumber greater mark }
                    .orderBy(AuditLogEntryTable.sequenceNumber)
                    .toList()
            var result = AuditChainVerifier.verify(rows)
            var checked = rows
            if (!result.valid && result.reason?.startsWith("Predecessor row") == true && rows.size > 1) {
                checked = rows.drop(1)
                result = AuditChainVerifier.verify(checked)
            }
            if (result.valid) null else "mark=$mark rows=${checked.size}: ${result.reason}"
        }

    fun cleanUp() {
        if (memberIds.isEmpty() && committeeIds.isEmpty()) return
        transaction {
            AuditLogEntryTable.update({ AuditLogEntryTable.actorMemberId inList memberIds }) { it[actorMemberId] = null }
            TransparenzregisterReminderTable.deleteWhere { TransparenzregisterReminderTable.memberId inList memberIds }
            BoardMembershipTable.deleteWhere { BoardMembershipTable.memberId inList memberIds }
            CommitteeMembershipTable.deleteWhere {
                (CommitteeMembershipTable.committeeId inList committeeIds) or (CommitteeMembershipTable.memberId inList memberIds)
            }
            CommitteeTable.deleteWhere { CommitteeTable.id inList committeeIds }
            AccountTable.deleteWhere { AccountTable.memberId inList memberIds }
            MemberStatusHistoryTable.deleteWhere { MemberStatusHistoryTable.memberId inList memberIds }
            MemberTable.deleteWhere { MemberTable.id inList memberIds }
        }
    }
}
