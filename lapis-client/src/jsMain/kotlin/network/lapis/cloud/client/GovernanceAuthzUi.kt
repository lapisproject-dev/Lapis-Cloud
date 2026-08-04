package network.lapis.cloud.client

import network.lapis.cloud.shared.domain.CommitteeMembershipDto
import network.lapis.cloud.shared.domain.CommitteeRole

/**
 * Governance UI wave -- pure, DOM-free client-side mirror of
 * `network.lapis.cloud.server.security.GovernanceAuthorization.canRecordForMeeting` (see the
 * approved plan §4/§1: "a pure, DOM-free helper mirroring the server's `canRecordForMeeting`/
 * `canSubmitMotion` predicates client-side ... so it's unit-testable"). This is a per-Committee
 * check the UI cannot compute from `SessionInfoDto` alone -- a committee's active roster (as
 * already returned by `IGovernanceService.listCommitteeMembers(committeeId, activeOnly = true)`)
 * must be loaded first.
 *
 * As with every other client-side role gate in this app ([AppState.hasRole], `CommitteesScreen`'s
 * `canManage`), this is a UX nicety on top of the server's real authority, not the actual security
 * boundary -- `guarded()` gracefully surfaces the server's own `ForbiddenException` if a stale
 * client-side computation (e.g. a roster loaded before a just-ended membership propagated) lets a
 * now-unauthorized action through to an RPC call.
 *
 * Shared by `MeetingsScreen.kt` (createMeeting/updateMeetingStatus/addAgendaItem/
 * removeAgendaItem/recordAttendance/recordResolution gating) and `MotionsScreen.kt`
 * (reviewMotion/scheduleMotion/resolveMotion/openVote/closeVote/abortVote gating, plus
 * `withdrawMotion`'s "committee leadership at any status" branch) -- both screens' privileged
 * actions are gated by the exact same server predicate, so this lives in one shared file rather
 * than being duplicated per screen.
 */
object GovernanceAuthzUi {
    private val LEADERSHIP_ROLES = setOf(CommitteeRole.CHAIR, CommitteeRole.DEPUTY_CHAIR, CommitteeRole.SECRETARY)

    /**
     * Mirrors `GovernanceAuthorization.canRecordForMeeting(committeeId): Boolean =
     * isPrivileged || hasCommitteeRole(committeeId, CHAIR, DEPUTY_CHAIR, SECRETARY)`.
     *
     * [activeCommitteeMemberships] must already be scoped to [committeeId] and already
     * date-filtered to "active" -- i.e. straight from
     * `listCommitteeMembers(committeeId, activeOnly = true)` -- this function does not re-check
     * `since`/`until` itself.
     */
    fun canRecordForMeeting(
        isBoardOrAdmin: Boolean,
        currentMemberId: String,
        committeeId: String,
        activeCommitteeMemberships: List<CommitteeMembershipDto>,
    ): Boolean {
        if (isBoardOrAdmin) return true
        return activeCommitteeMemberships.any {
            it.committeeId == committeeId && it.memberId == currentMemberId && it.role in LEADERSHIP_ROLES
        }
    }
}
