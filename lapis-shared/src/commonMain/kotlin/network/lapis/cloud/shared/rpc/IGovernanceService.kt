package network.lapis.cloud.shared.rpc

import dev.kilua.rpc.annotations.RpcService
import kotlinx.datetime.LocalDate
import network.lapis.cloud.shared.domain.AgendaItemDto
import network.lapis.cloud.shared.domain.AgendaItemInput
import network.lapis.cloud.shared.domain.AttendanceDto
import network.lapis.cloud.shared.domain.AttendanceInput
import network.lapis.cloud.shared.domain.CommitteeDto
import network.lapis.cloud.shared.domain.CommitteeInput
import network.lapis.cloud.shared.domain.CommitteeMembershipDto
import network.lapis.cloud.shared.domain.CommitteeMembershipInput
import network.lapis.cloud.shared.domain.MeetingDetailDto
import network.lapis.cloud.shared.domain.MeetingDto
import network.lapis.cloud.shared.domain.MeetingInput
import network.lapis.cloud.shared.domain.MeetingStatus
import network.lapis.cloud.shared.domain.MotionDto
import network.lapis.cloud.shared.domain.MotionInput
import network.lapis.cloud.shared.domain.MotionResolutionInput
import network.lapis.cloud.shared.domain.MotionReviewDecision
import network.lapis.cloud.shared.domain.MotionStatus
import network.lapis.cloud.shared.domain.ProtocolDraftDto
import network.lapis.cloud.shared.domain.QuorumResultDto
import network.lapis.cloud.shared.domain.ResolutionDto
import network.lapis.cloud.shared.domain.ResolutionInput
import network.lapis.cloud.shared.domain.VoteBallotDto
import network.lapis.cloud.shared.domain.VoteBallotInput
import network.lapis.cloud.shared.domain.VoteDto
import network.lapis.cloud.shared.domain.VoteOpenInput

/**
 * Committee and meeting management (V0.2.1): Committees/working groups, memberships within them,
 * meetings with agenda/attendance/quorum, and a resolution book. Reads
 * (listCommittees/getMeetingDetail/listResolutions/etc.) are open to any authenticated member —
 * a deliberate simplification versus [network.lapis.cloud.shared.domain.DocumentAccessLevel]'s
 * three-tier model, to keep this wave's scope bounded; worth revisiting if some Committees need
 * confidentiality. Write operations that manage a specific Committee's meetings/agenda/attendance/
 * resolutions require that Committee's leadership role (CHAIR/DEPUTY_CHAIR/SECRETARY) or
 * global BOARD/ADMIN — see `network.lapis.cloud.server.security.GovernanceAuthorization`.
 *
 * Motion management (V0.2.2) extends this same interface rather than fragmenting into a parallel
 * `IMotionService` — an Motion's lifecycle is tightly coupled to Meeting/AgendaItem/
 * Resolution, which this interface already spans. See [MotionDto] KDoc for the full lifecycle and
 * `GovernanceAuthorization.canSubmitMotion` for submission rules (broad participation right for
 * the General Assembly, any-role Committee membership for a specific Committee).
 *
 * Meritokratische Voteen (V0.2.3) extends this same interface once more: an eBay/Vickrey
 * basket auction opened on a [MotionStatus.SCHEDULED] Motion (`openVote`), per-member LTR
 * staking into one of the auction's baskets (`castVoteBallot`), and a settlement close
 * (`closeVote`) that runs the Vickrey settlement and writes into the *same* resolution book as
 * [recordResolution]/[resolveMotion] via [network.lapis.cloud.shared.domain.ResolutionMode
 * .MERITOCRATIC] — see [network.lapis.cloud.shared.domain.VoteDto] KDoc for the full
 * lifecycle and `network.lapis.cloud.server.rpc.VoteSettlement` for the settlement algorithm
 * itself. This is a parallel *resolution* path for an already-[MotionStatus.SCHEDULED] Motion,
 * not a parallel submission/review/scheduling pipeline — those steps are unchanged.
 *
 * Explicitly out of scope for this wave (see roadmap's separate bullets): Demokratische Electionen,
 * Systemic Consensus, floor amendments to an Motion's text. The [recordResolution]/
 * [resolveMotion] Committee-Quorum path remains a straightforward decision log with a
 * Ja/Nein/Enthaltung tally for every Motion that does not go through a meritocratic Vote.
 */
@RpcService
interface IGovernanceService {
    suspend fun listCommittees(activeOnly: Boolean = true): List<CommitteeDto>

    /** Role: BOARD/ADMIN — committee structure itself is an org-wide governance decision. */
    suspend fun createCommittee(input: CommitteeInput): CommitteeDto

    /** Role: BOARD/ADMIN. */
    suspend fun updateCommittee(
        id: String,
        input: CommitteeInput,
    ): CommitteeDto

    suspend fun listCommitteeMembers(
        committeeId: String,
        activeOnly: Boolean = true,
    ): List<CommitteeMembershipDto>

    /** Role: BOARD/ADMIN. */
    suspend fun addCommitteeMember(
        committeeId: String,
        input: CommitteeMembershipInput,
    ): CommitteeMembershipDto

    /** Role: BOARD/ADMIN. */
    suspend fun endCommitteeMembership(
        membershipId: String,
        until: LocalDate,
    ): CommitteeMembershipDto

    suspend fun listMeetings(
        committeeId: String? = null,
        status: MeetingStatus? = null,
    ): List<MeetingDto>

    suspend fun getMeetingDetail(meetingId: String): MeetingDetailDto

    /** Role: Committee-Vorsitz/-Stellv./Schriftfuehrung/Board/Admin (see class KDoc). */
    suspend fun createMeeting(input: MeetingInput): MeetingDto

    /** Role: Committee-Vorsitz/-Stellv./Schriftfuehrung/Board/Admin. */
    suspend fun updateMeetingStatus(
        meetingId: String,
        status: MeetingStatus,
    ): MeetingDto

    /** Role: Committee-Vorsitz/-Stellv./Schriftfuehrung/Board/Admin. */
    suspend fun addAgendaItem(
        meetingId: String,
        input: AgendaItemInput,
    ): AgendaItemDto

    /** Role: Committee-Vorsitz/-Stellv./Schriftfuehrung/Board/Admin. */
    suspend fun removeAgendaItem(id: String)

    /** Role: Committee-Vorsitz/-Stellv./Schriftfuehrung/Board/Admin. */
    suspend fun recordAttendance(
        meetingId: String,
        input: AttendanceInput,
    ): AttendanceDto

    suspend fun getAttendance(meetingId: String): List<AttendanceDto>

    suspend fun checkQuorum(meetingId: String): QuorumResultDto

    /** Role: Committee-Vorsitz/-Stellv./Schriftfuehrung/Board/Admin. */
    suspend fun recordResolution(
        meetingId: String,
        input: ResolutionInput,
    ): ResolutionDto

    suspend fun listResolutions(
        committeeId: String? = null,
        meetingId: String? = null,
    ): List<ResolutionDto>

    suspend fun generateProtocolDraft(meetingId: String): ProtocolDraftDto

    /**
     * Role: any member with [network.lapis.cloud.shared.domain.MemberStatus.AKTIV] when the
     * target is the General Assembly; any active [CommitteeMembershipDto] (any
     * [network.lapis.cloud.shared.domain.CommitteeRole]) of the target Committee otherwise; or
     * BOARD/ADMIN. See `GovernanceAuthorization.canSubmitMotion`.
     */
    suspend fun submitMotion(input: MotionInput): MotionDto

    suspend fun listMotions(
        targetCommitteeId: String? = null,
        status: MotionStatus? = null,
    ): List<MotionDto>

    suspend fun getMotion(id: String): MotionDto

    /**
     * Role: the submitter themself while [MotionStatus.SUBMITTED], or that Committee's leadership/
     * BOARD/ADMIN at any status.
     */
    suspend fun withdrawMotion(id: String): MotionDto

    /** Role: target Committee leadership (CHAIR/DEPUTY_CHAIR/SECRETARY) or BOARD/ADMIN. */
    suspend fun reviewMotion(
        id: String,
        decision: MotionReviewDecision,
        note: String? = null,
    ): MotionDto

    /** Role: target Committee leadership or BOARD/ADMIN. Requires [MotionStatus.REVIEWED] or [MotionStatus.POSTPONED]. */
    suspend fun scheduleMotion(
        id: String,
        meetingId: String,
        position: Int,
    ): MotionDto

    /** Role: target Committee leadership or BOARD/ADMIN. Requires [MotionStatus.SCHEDULED]. */
    suspend fun resolveMotion(
        id: String,
        input: MotionResolutionInput,
    ): MotionDto

    /**
     * Role: target Committee leadership or BOARD/ADMIN. Requires [MotionStatus.SCHEDULED] and no
     * already-open/-closed Vote for this Motion.
     */
    suspend fun openVote(input: VoteOpenInput): VoteDto

    /**
     * Role: any member eligible for the Vote's underlying Meeting/Committee (same
     * eligibility set `checkQuorum` uses for that Meeting) — see
     * `network.lapis.cloud.server.rpc.GovernanceService.castVoteBallot` KDoc. Requires
     * [network.lapis.cloud.shared.domain.VoteStatus.OPEN]. Upserts one ballot per member;
     * a second call overwrites the member's own prior stake/option, it does not add a second
     * ballot.
     */
    suspend fun castVoteBallot(input: VoteBallotInput): VoteBallotDto

    /**
     * Role: target Committee leadership or BOARD/ADMIN. Requires
     * [network.lapis.cloud.shared.domain.VoteStatus.OPEN]; runs the Vickrey settlement,
     * writes the resulting Resolution, and transitions the underlying Motion.
     */
    suspend fun closeVote(voteId: String): VoteDto

    /**
     * Role: target Committee leadership or BOARD/ADMIN. Requires
     * [network.lapis.cloud.shared.domain.VoteStatus.OPEN]; no settlement runs, no Resolution
     * is created, the underlying Motion stays [MotionStatus.SCHEDULED].
     */
    suspend fun abortVote(voteId: String): VoteDto

    suspend fun getVote(voteId: String): VoteDto

    /**
     * Transparency read of every ballot cast so far, including staked/settled amounts — open to
     * any authenticated member, not just the Vote's own participants. Pseudonymization of
     * ballots is future scope (see the implementation plan's open decision points); `memberId`/
     * `memberDisplayName` are exposed like every other DTO in this interface for now.
     */
    suspend fun listVoteBallots(voteId: String): List<VoteBallotDto>
}
