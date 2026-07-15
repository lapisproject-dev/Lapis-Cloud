package network.lapis.cloud.server.security

import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import network.lapis.cloud.server.db.generated.CommitteeMembershipTable
import network.lapis.cloud.server.db.generated.CommitteeTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.shared.domain.CommitteeRole
import network.lapis.cloud.shared.domain.CommitteeType
import network.lapis.cloud.shared.domain.MemberStatus
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greaterEq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.lessEq
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.time.Clock
import kotlin.uuid.Uuid

/**
 * Authorization helpers for Gremien-/Meetingsverwaltung (V0.2.1). [Committee][network.lapis.cloud
 * .shared.domain.CommitteeDto] create/update stays BOARD/ADMIN-only ([requireRole], existing
 * pattern in `network.lapis.cloud.server.rpc.GovernanceService`) since committee structure itself
 * is an org-wide governance decision. Meeting/Agenda/Attendance/Resolution management uses
 * the functions below, which resolve the Committee behind the resource and check for an *active*
 * (as-of-today) [CommitteeRole] membership entitled to that action, OR global BOARD/ADMIN via
 * [isPrivileged].
 *
 * Each function opens its own `transaction {}`, mirroring [resolveCurrentMember]'s style —
 * Exposed transactions nest without opening a second physical transaction, so calling these from
 * inside an already-open `transaction {}` (as `GovernanceService` typically does) is safe.
 */
fun CurrentMember.canManageCommittee(committeeId: Uuid): Boolean =
    isPrivileged || hasCommitteeRole(committeeId, CommitteeRole.CHAIR, CommitteeRole.DEPUTY_CHAIR)

fun CurrentMember.canRecordForMeeting(committeeId: Uuid): Boolean =
    isPrivileged ||
        hasCommitteeRole(committeeId, CommitteeRole.CHAIR, CommitteeRole.DEPUTY_CHAIR, CommitteeRole.SECRETARY)

/**
 * Motionsverwaltung (V0.2.2) submission rule. Asymmetric on purpose: submitting *to* the
 * General Assembly is a broad participation right (any [MemberStatus.AKTIV] member, mirroring
 * every member's stake in a general assembly), while submitting *to* a specific Committee requires
 * an active membership of that Committee — any [CommitteeRole], not just leadership, so a
 * rank-and-file committee member can propose something to their own committee, but a non-member
 * cannot.
 */
fun CurrentMember.canSubmitMotion(targetCommitteeId: Uuid): Boolean {
    if (isPrivileged) return true
    val committeeType =
        transaction {
            CommitteeTable
                .selectAll()
                .where { CommitteeTable.id eq targetCommitteeId }
                .singleOrNull()
                ?.get(CommitteeTable.type)
        } ?: return false
    return if (committeeType == CommitteeType.GENERAL_ASSEMBLY) {
        transaction {
            MemberTable
                .selectAll()
                .where { (MemberTable.id eq memberId) and (MemberTable.status eq MemberStatus.AKTIV) }
                .count() > 0
        }
    } else {
        hasCommitteeRole(targetCommitteeId, *CommitteeRole.entries.toTypedArray())
    }
}

private fun CurrentMember.hasCommitteeRole(
    committeeId: Uuid,
    vararg roles: CommitteeRole,
): Boolean {
    val today =
        Clock.System
            .now()
            .toLocalDateTime(TimeZone.currentSystemDefault())
            .date
    return transaction {
        CommitteeMembershipTable
            .selectAll()
            .where {
                (CommitteeMembershipTable.committeeId eq committeeId) and
                    (CommitteeMembershipTable.memberId eq memberId) and
                    (CommitteeMembershipTable.role inList roles.toList()) and
                    (CommitteeMembershipTable.since lessEq today) and
                    (
                        CommitteeMembershipTable.until.isNull() or
                            (CommitteeMembershipTable.until greaterEq today)
                    )
            }.count() > 0
    }
}
