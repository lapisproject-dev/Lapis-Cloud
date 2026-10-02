package network.lapis.cloud.server.security

import network.lapis.cloud.server.db.generated.CommitteeMembershipTable
import network.lapis.cloud.server.db.generated.CommitteeTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.server.time.OrganizationTimeZone
import network.lapis.cloud.shared.domain.COMMITTEE_RECORDING_ROLES
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.rpc.ForbiddenException
import org.jetbrains.exposed.v1.core.JoinType
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greaterEq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.lessEq
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.jdbc.selectAll
import kotlin.uuid.Uuid

// Welle V1.9.30 "Umfragen auf LTR-Basis" -- authorization helpers of `PollService`.
//
// **All of these MUST run inside the caller's already-open `transaction {}`** and open no
// transaction of their own (unlike [canRecordForMeeting]'s `hasCommitteeRole`): the poll service
// calls them while it already holds row locks, and a self-contained read here keeps the lock order
// obvious. Polls are bound to no motion and no meeting -- they are committee-independent -- so
// `MotionDecisionLock` is deliberately not involved.

/**
 * `true` iff the caller is currently an ACTIVE member AND holds a [COMMITTEE_RECORDING_ROLES] seat
 * (CHAIR, DEPUTY_CHAIR, GENERAL_SECRETARY, SECRETARY, MANAGING_DIRECTOR -- the same set
 * [canRecordForMeeting] uses) in ANY active committee as of today (`since <= today`,
 * `until IS NULL OR until >= today`). One query.
 */
fun CurrentMember.isCommitteeLeaderAnywhere(): Boolean {
    val today = OrganizationTimeZone.today()
    if (!isActiveMemberNow()) return false
    return CommitteeMembershipTable
        .join(CommitteeTable, JoinType.INNER, CommitteeMembershipTable.committeeId, CommitteeTable.id)
        .selectAll()
        .where {
            (CommitteeMembershipTable.memberId eq memberId) and
                (CommitteeTable.active eq true) and
                (CommitteeMembershipTable.role inList COMMITTEE_RECORDING_ROLES.toList()) and
                (CommitteeMembershipTable.since lessEq today) and
                (CommitteeMembershipTable.until.isNull() or (CommitteeMembershipTable.until greaterEq today))
        }.limit(1)
        .any()
}

/** ADMIN/BOARD, or a committee leader (see [isCommitteeLeaderAnywhere]) may create polls. */
fun CurrentMember.canCreatePolls(): Boolean = isPrivileged || isCommitteeLeaderAnywhere()

/**
 * Close/abort: privileged members, or the creator while still [canCreatePolls]. The chair of ANOTHER
 * committee may NOT close somebody else's poll.
 */
fun CurrentMember.canManagePoll(createdBy: Uuid): Boolean = isPrivileged || (createdBy == memberId && canCreatePolls())

/**
 * Read gate: an ACTIVE member, or a creator-capable caller. GUEST/FRIEND/federated guests/
 * APPLICATION/WITHDRAWN/REJECTED get a [ForbiddenException] -- evaluated BEFORE any poll lookup so
 * the response never reveals whether a poll exists.
 */
fun CurrentMember.requirePollReader() {
    // The database is authoritative, not the (possibly stale) status cached in the session.
    if (!isActiveMemberNow() && !canCreatePolls()) throw ForbiddenException()
}

/** The caller's CURRENT status read from the database is ACTIVE (the cached session status is not trusted). */
fun CurrentMember.isActiveMemberNow(): Boolean =
    MemberTable
        .selectAll()
        .where { (MemberTable.id eq memberId) and (MemberTable.status eq MemberStatus.ACTIVE) }
        .limit(1)
        .any()
