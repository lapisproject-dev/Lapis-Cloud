package network.lapis.cloud.server.messaging

import kotlinx.datetime.LocalDate
import network.lapis.cloud.server.db.generated.CommitteeTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.server.rpc.eligibleMemberIds
import network.lapis.cloud.server.rpc.isCommitteeEligible
import network.lapis.cloud.server.security.CurrentMember
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.CommitteeType
import network.lapis.cloud.shared.domain.MemberStatusSets
import network.lapis.cloud.shared.rpc.ConflictException
import network.lapis.cloud.shared.rpc.DirectMessageGroupUnavailableException
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.neq
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import kotlin.uuid.Uuid

/**
 * V1.9.237 -- which committees a member may write to, and who receives such a message. Must run inside a `transaction {}`.
 *
 * Membership is NEVER rebuilt here: [eligibleMemberIds] / [isCommitteeEligible] cover MANUAL committees (seat rows with `since`/`until`) AND RULE
 * committees (no seat rows, derived from the stored rule). Addressable = active and not the general assembly (its "members" are the whole
 * organisation -- out of scope). BOARD and ADMIN may write to every addressable committee, everybody else only to those he is a member of today.
 */
internal object DirectMessageGroups {
    fun addressable(
        current: CurrentMember,
        today: LocalDate,
    ): List<ResultRow> =
        CommitteeTable
            .selectAll()
            .where { (CommitteeTable.active eq true) and (CommitteeTable.type neq CommitteeType.GENERAL_ASSEMBLY) }
            .orderBy(CommitteeTable.name to SortOrder.ASC, CommitteeTable.id to SortOrder.ASC)
            .toList()
            .filter { mayWrite(current = current, row = it, today = today) }

    private fun mayWrite(
        current: CurrentMember,
        row: ResultRow,
        today: LocalDate,
    ): Boolean {
        if (current.role == AccountRole.BOARD || current.role == AccountRole.ADMIN) return true
        return try {
            isCommitteeEligible(committeeRow = row, scheduledDate = today, memberId = current.memberId)
        } catch (e: ConflictException) {
            false // a broken stored rule hides the committee; it must not break the whole list
        }
    }

    /** Recipients of a message to [row] from [senderId]: current members, ACTIVE and not anonymized, sender excluded, ascending by id. */
    fun recipientsOf(
        row: ResultRow,
        senderId: Uuid,
        today: LocalDate,
    ): List<Uuid> {
        val candidates = eligibleMemberIds(committeeRow = row, scheduledDate = today) - senderId
        if (candidates.isEmpty()) return emptyList()
        return MemberTable
            .select(MemberTable.id)
            .where {
                (MemberTable.id inList candidates) and
                    (MemberTable.status inList MemberStatusSets.ORGANIZATION_MEMBER) and
                    MemberTable.anonymizedAt.isNull()
            }.map { it[MemberTable.id] }
            .sortedBy { it.toString() }
    }

    /**
     * The committee [rawId] if the caller may write to it, otherwise ONE uniform [DirectMessageGroupUnavailableException]: malformed, unknown,
     * inactive, general assembly, foreign or broken-rule committees are indistinguishable (closes the IDOR).
     */
    fun resolveAddressable(
        current: CurrentMember,
        rawId: String,
        today: LocalDate,
    ): ResultRow {
        val id = runCatching { Uuid.parse(rawId) }.getOrElse { throw DirectMessageGroupUnavailableException() }
        val row =
            CommitteeTable
                .selectAll()
                .where {
                    (CommitteeTable.id eq id) and (CommitteeTable.active eq true) and
                        (CommitteeTable.type neq CommitteeType.GENERAL_ASSEMBLY)
                }.singleOrNull()
        if (row == null || !mayWrite(current = current, row = row, today = today)) throw DirectMessageGroupUnavailableException()
        return row
    }
}
