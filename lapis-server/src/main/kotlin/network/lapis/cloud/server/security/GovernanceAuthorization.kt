package network.lapis.cloud.server.security

import network.lapis.cloud.server.db.generated.CommitteeMembershipTable
import network.lapis.cloud.server.db.generated.CommitteeTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.server.rpc.isCommitteeEligible
import network.lapis.cloud.server.rpc.isRuleCommittee
import network.lapis.cloud.server.time.OrganizationTimeZone
import network.lapis.cloud.shared.domain.COMMITTEE_LEADERSHIP_ROLES
import network.lapis.cloud.shared.domain.COMMITTEE_RECORDING_ROLES
import network.lapis.cloud.shared.domain.CommitteeRole
import network.lapis.cloud.shared.domain.CommitteeType
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.rpc.ConflictException
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greaterEq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.lessEq
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
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
    isPrivileged || hasCommitteeRole(committeeId = committeeId, *COMMITTEE_LEADERSHIP_ROLES.toTypedArray())

fun CurrentMember.canRecordForMeeting(committeeId: Uuid): Boolean =
    isPrivileged || hasCommitteeRole(committeeId = committeeId, *COMMITTEE_RECORDING_ROLES.toTypedArray())

/**
 * Security-audit-round-2 L1 fix -- "is this member currently seated on this Committee, in ANY role"
 * (privileged callers excluded on purpose, unlike [canManageCommittee]/[canRecordForMeeting] -- see
 * [ConferenceService.setRoomMeeting] KDoc, which already short-circuits on
 * [CurrentMember.isPrivileged] itself before ever reaching this check). Thin public wrapper around
 * the same [hasCommitteeRole] every other membership-role gate in this file already uses (`since <=
 * today AND (until IS NULL OR until >= today)`, i.e. a genuinely ACTIVE-as-of-today row, not merely
 * "was ever seated") -- exists so callers outside this file (`ConferenceService.setRoomMeeting`'s
 * "hin-binden" gate) can reuse the exact same semantics [canSubmitMotion]'s own non-GENERAL_ASSEMBLY
 * branch already establishes, instead of hand-rolling an incomplete `until IS NULL`-only copy that
 * wrongly rejects a member with a time-limited (but still current) term -- the normal case for an
 * elected Vorstand seat.
 */
fun CurrentMember.isActiveCommitteeMember(committeeId: Uuid): Boolean =
    hasCommitteeRole(committeeId = committeeId, *CommitteeRole.entries.toTypedArray()) || isRuleCommitteeMember(committeeId)

/**
 * V1.9.112 -- membership of a rule-based committee is derived, not seated: true when [committeeId] is a rule-based committee whose rule
 * selects this member TODAY (live, no snapshot -- this is an access check, not a decision). A stored rule that no longer parses grants
 * nothing (false), it never throws out of an authorization predicate. Consumers: [isActiveCommitteeMember] (conference binding) and
 * [canSubmitMotion]. Leadership checks ([canManageCommittee], [canRecordForMeeting]) deliberately stay seat/role based: a rule-based
 * committee has no offices, so only BOARD/ADMIN lead its meetings.
 */
private fun CurrentMember.isRuleCommitteeMember(committeeId: Uuid): Boolean =
    transaction {
        val row = CommitteeTable.selectAll().where { CommitteeTable.id eq committeeId }.singleOrNull()
        if (row == null || !isRuleCommittee(row)) {
            false
        } else {
            try {
                isCommitteeEligible(committeeRow = row, scheduledDate = OrganizationTimeZone.today(), memberId = memberId)
            } catch (e: ConflictException) {
                false
            }
        }
    }

/**
 * Motionsverwaltung (V0.2.2) submission rule. Asymmetric on purpose: submitting *to* the
 * General Assembly is a broad participation right (any [MemberStatus.ACTIVE] member, mirroring
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
                .where { (MemberTable.id eq memberId) and (MemberTable.status eq MemberStatus.ACTIVE) }
                .count() > 0
        }
    } else {
        hasCommitteeRole(committeeId = targetCommitteeId, *CommitteeRole.entries.toTypedArray()) ||
            isRuleCommitteeMember(targetCommitteeId)
    }
}

private fun CurrentMember.hasCommitteeRole(
    committeeId: Uuid,
    vararg roles: CommitteeRole,
): Boolean = activeRoles(committeeId = committeeId, roles = roles.toList()).isNotEmpty()

private fun CurrentMember.activeRoles(
    committeeId: Uuid,
    roles: List<CommitteeRole>,
): List<CommitteeRole> {
    val today = OrganizationTimeZone.today()
    return transaction {
        CommitteeMembershipTable
            .selectAll()
            .where {
                (CommitteeMembershipTable.committeeId eq committeeId) and
                    (CommitteeMembershipTable.memberId eq memberId) and
                    (CommitteeMembershipTable.role inList roles) and
                    (CommitteeMembershipTable.since lessEq today) and
                    (
                        CommitteeMembershipTable.until.isNull() or
                            (CommitteeMembershipTable.until greaterEq today)
                    )
            }.map { it[CommitteeMembershipTable.role] }
    }
}

/**
 * V1.9.229 -- the recording office (Vorsitz, stellvertretender Vorsitz, Schriftfuehrung, Geschaeftsfuehrung) this member holds on [committeeId]
 * TODAY, or `null`. Seat based ONLY: unlike [canRecordForMeeting] there is no BOARD/ADMIN branch, so the result says what an officer's own seat
 * grants. With several offices the most senior one (lowest [CommitteeRole] ordinal) wins -- sorted here, not in SQL, so it is deterministic.
 */
fun CurrentMember.activeRecordingRole(committeeId: Uuid): CommitteeRole? =
    activeRoles(committeeId = committeeId, roles = COMMITTEE_RECORDING_ROLES.toList()).minByOrNull { it.ordinal }

/** V1.9.229 -- what the caller may do with one event; see [meetingEventGrant]. */
sealed interface MeetingEventGrant {
    /** BOARD/ADMIN: everything, as before. */
    data object Full : MeetingEventGrant

    /** An officer of the committee whose Sitzung [meetingId] the event announces: the limited officer rights. */
    data class Officer(
        val meetingId: Uuid,
        val committeeId: Uuid,
        val officerRole: CommitteeRole,
    ) : MeetingEventGrant

    data object None : MeetingEventGrant
}

/**
 * V1.9.229 -- THE rule: BOARD/ADMIN manage every event (same set as `EVENT_MANAGE_ROLES`; deliberately NOT TREASURER); otherwise only an officer
 * seat on the committee of the Sitzung the event is linked to grants anything. A pure decision over already-read ids -- callers on a write path
 * read them from LOCKED rows, never from client input. No lock, no cache.
 */
fun CurrentMember.meetingEventGrant(
    eventMeetingId: Uuid?,
    meetingCommitteeId: Uuid?,
): MeetingEventGrant =
    when {
        isPrivileged -> MeetingEventGrant.Full
        eventMeetingId == null || meetingCommitteeId == null -> MeetingEventGrant.None
        else ->
            activeRecordingRole(meetingCommitteeId)?.let {
                MeetingEventGrant.Officer(meetingId = eventMeetingId, committeeId = meetingCommitteeId, officerRole = it)
            } ?: MeetingEventGrant.None
    }
