package network.lapis.cloud.shared.domain

import dev.kilua.rpc.types.Decimal
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.serialization.Serializable

/**
 * [GENERAL_ASSEMBLY] added in V0.2.2 (Motionsverwaltung) to model the general assembly as a
 * singleton [CommitteeDto] rather than inventing a second, parallel "target kind" axis alongside
 * Committee -- see [MotionDto] KDoc and `GovernanceService.computeQuorum`'s branch on this type.
 */
@Serializable
enum class CommitteeType { EXECUTIVE_BOARD, WORKING_GROUP, COMMISSION, OTHER, GENERAL_ASSEMBLY }

/**
 * Role *within* a [CommitteeDto] — distinct from [AccountRole], the system-wide login role. A
 * person can be [AccountRole.MEMBER] system-wide but [CHAIR] of the Working Group IT.
 */
@Serializable
enum class CommitteeRole { CHAIR, DEPUTY_CHAIR, SECRETARY, MEMBER, ASSESSOR }

@Serializable
enum class MeetingFormat { IN_PERSON, ONLINE, HYBRID }

@Serializable
enum class MeetingStatus { PLANNED, HELD, CANCELLED }

@Serializable
enum class AttendanceStatus { PRESENT, EXCUSED, UNEXCUSED, REPRESENTED }

@Serializable
enum class ResolutionStatus { ADOPTED, REJECTED, POSTPONED }

/**
 * Meritokratische Voteen (V0.2.3): distinguishes the pre-existing Committee-Quorum
 * resolution book (V0.2.1/V0.2.2, headcount-driven Ja/Nein/Enthaltung tally) from the new
 * Vickrey-basket-auction path (LTR-weighted, see [VoteDto]). [COMMITTEE_QUORUM] stays the DB
 * default so every pre-existing [ResolutionDto] row and `recordResolution`/`resolveMotion` call site
 * is unaffected by this wave.
 *
 * Demokratische Electionen (V0.2.4) add [DEMOCRATIC]: a one-person-one-vote Election
 * ([network.lapis.cloud.shared.domain.ElectionDto]) resolved by `ElectionService.tally`, tagged
 * alongside [MERITOCRATIC] as a third resolution path into the same resolution book.
 *
 * Systemic Consensus (V0.2.5) adds [SYSTEMIC_CONSENSUS]: a lowest-cumulative-resistance
 * SystemicConsensus ([network.lapis.cloud.shared.domain.SystemicConsensusDto]) resolved by
 * `SystemicConsensusService.evaluate`, written only when
 * [network.lapis.cloud.shared.domain.SystemicConsensusBindingness.BINDING] — a
 * [network.lapis.cloud.shared.domain.SystemicConsensusBindingness.ADVISORY] SystemicConsensus never writes a
 * Resolution at all (purely advisory).
 */
@Serializable
enum class ResolutionMode { COMMITTEE_QUORUM, MERITOCRATIC, DEMOCRATIC, SYSTEMIC_CONSENSUS }

@Serializable
data class CommitteeDto(
    val id: String,
    val name: String,
    val type: CommitteeType,
    val description: String,
    val active: Boolean,
    val quorumPercent: Int,
    val createdAt: LocalDateTime,
)

@Serializable
data class CommitteeInput(
    val name: String,
    val type: CommitteeType,
    val description: String,
    val quorumPercent: Int = 50,
    val active: Boolean = true,
)

@Serializable
data class CommitteeMembershipDto(
    val id: String,
    val committeeId: String,
    val memberId: String,
    val memberDisplayName: String,
    val role: CommitteeRole,
    val since: LocalDate,
    val until: LocalDate?,
)

@Serializable
data class CommitteeMembershipInput(
    val memberId: String,
    val role: CommitteeRole,
    val since: LocalDate,
)

@Serializable
data class MeetingDto(
    val id: String,
    val committeeId: String,
    val committeeName: String,
    val title: String,
    val scheduledAt: LocalDateTime,
    val location: String?,
    val format: MeetingFormat,
    val status: MeetingStatus,
    val calledById: String?,
    val calledByDisplayName: String?,
    val calledAt: LocalDateTime?,
    val chairMemberId: String?,
    val chairDisplayName: String?,
    val minuteTakerMemberId: String?,
    val minuteTakerDisplayName: String?,
    val protocolDocumentId: String?,
    val createdAt: LocalDateTime,
)

@Serializable
data class MeetingInput(
    val committeeId: String,
    val title: String,
    val scheduledAt: LocalDateTime,
    val location: String?,
    val format: MeetingFormat,
    val chairMemberId: String? = null,
    val minuteTakerMemberId: String? = null,
)

@Serializable
data class AgendaItemDto(
    val id: String,
    val meetingId: String,
    val position: Int,
    val title: String,
    val description: String?,
    val presenterMemberId: String?,
    val presenterDisplayName: String?,
)

@Serializable
data class AgendaItemInput(
    val position: Int,
    val title: String,
    val description: String? = null,
    val presenterMemberId: String? = null,
)

@Serializable
data class AttendanceDto(
    val id: String,
    val meetingId: String,
    val memberId: String,
    val memberDisplayName: String,
    val status: AttendanceStatus,
    val representedByMemberId: String?,
    val representedByDisplayName: String?,
    val note: String?,
    val recordedAt: LocalDateTime,
)

@Serializable
data class AttendanceInput(
    val memberId: String,
    val status: AttendanceStatus,
    val representedByMemberId: String? = null,
    val note: String? = null,
)

@Serializable
data class QuorumResultDto(
    val meetingId: String,
    val eligibleMemberCount: Int,
    val presentCount: Int,
    val requiredCount: Int,
    val quorumPercent: Int,
    val met: Boolean,
)

@Serializable
data class ResolutionDto(
    val id: String,
    val meetingId: String,
    val agendaItemId: String?,
    val number: String,
    val title: String,
    val text: String,
    val votesYes: Int,
    val votesNo: Int,
    val votesAbstain: Int,
    val quorumMet: Boolean,
    val status: ResolutionStatus,
    val decidedAt: LocalDateTime,
    val recordedById: String,
    val recordedByDisplayName: String,
    // Meritokratische Voteen (V0.2.3). Defaults keep every existing call site (that builds
    // a ResolutionDto without naming these two params) source-compatible.
    val resolutionMode: ResolutionMode = ResolutionMode.COMMITTEE_QUORUM,
    val voteId: String? = null,
    // Demokratische Electionen (V0.2.4). Same source-compatible default as voteId above.
    val electionId: String? = null,
    // Systemic Consensus (V0.2.5). Same source-compatible default as voteId/electionId
    // above. Always null for a SystemicConsensusBindingness.ADVISORY SystemicConsensus -- see [ResolutionMode
    // .SYSTEMIC_CONSENSUS] KDoc.
    val systemicConsensusId: String? = null,
)

@Serializable
data class ResolutionInput(
    val agendaItemId: String? = null,
    val title: String,
    val text: String,
    val votesYes: Int,
    val votesNo: Int,
    val votesAbstain: Int,
    val status: ResolutionStatus,
)

@Serializable
data class MeetingDetailDto(
    val meeting: MeetingDto,
    val agenda: List<AgendaItemDto>,
    val attendance: List<AttendanceDto>,
    val resolutions: List<ResolutionDto>,
    val quorum: QuorumResultDto,
)

/**
 * Structured template a client (or a later PDF/Serienbrief engine, see roadmap) renders — no
 * markdown/PDF generation happens in this wave, avoiding duplicate work with the planned
 * Serienbrief-/PDF-Engine (V0.4).
 */
@Serializable
data class ProtocolDraftDto(
    val meeting: MeetingDto,
    val attendance: List<AttendanceDto>,
    val agenda: List<AgendaItemDto>,
    val resolutions: List<ResolutionDto>,
    val quorum: QuorumResultDto,
    val generatedAt: LocalDateTime,
)

/**
 * Motionsverwaltung (V0.2.2): pre-meeting motion submission targeting either a specific
 * [CommitteeDto] or the [CommitteeType.GENERAL_ASSEMBLY] singleton Committee. Lifecycle:
 * [MotionStatus.SUBMITTED] -> [MotionStatus.REVIEWED] | [MotionStatus.REJECTED_PRELIMINARY]
 * (`reviewMotion`) -> [MotionStatus.SCHEDULED] (`scheduleMotion`, also reachable again from
 * [MotionStatus.POSTPONED] to support rescheduling) -> [MotionStatus.RESOLVED] |
 * [MotionStatus.REJECTED] | [MotionStatus.POSTPONED] (`resolveMotion`, mapped 1:1 from the
 * resulting [ResolutionStatus]) | [MotionStatus.WITHDRAWN] (`withdrawMotion`, only while
 * [MotionStatus.SUBMITTED] unless performed by Committee leadership/BOARD/ADMIN).
 */
@Serializable
enum class MotionStatus {
    SUBMITTED,
    REVIEWED,
    REJECTED_PRELIMINARY,
    SCHEDULED,
    RESOLVED,
    REJECTED,
    POSTPONED,
    WITHDRAWN,
}

@Serializable
enum class MotionReviewDecision { ACCEPT, REJECT }

/**
 * `text` is the motion text itself and becomes [ResolutionDto.text] verbatim at resolution --
 * deliberately no amendment/"Aenderungsmotion" support in this wave (floor amendments are a
 * distinct Robert's-Rules-style feature with real complexity, out of scope here; see roadmap).
 */
@Serializable
data class MotionDto(
    val id: String,
    val targetCommitteeId: String,
    val targetCommitteeName: String,
    val targetCommitteeType: CommitteeType,
    val title: String,
    val rationale: String,
    val text: String,
    val submitterMemberId: String,
    val submitterDisplayName: String,
    val status: MotionStatus,
    val submittedAt: LocalDateTime,
    val reviewedById: String?,
    val reviewedByDisplayName: String?,
    val reviewedAt: LocalDateTime?,
    val reviewNote: String?,
    val meetingId: String?,
    val agendaItemId: String?,
    val resolutionId: String?,
)

@Serializable
data class MotionInput(
    val targetCommitteeId: String,
    val title: String,
    val rationale: String,
    val text: String,
)

@Serializable
data class MotionResolutionInput(
    val votesYes: Int,
    val votesNo: Int,
    val votesAbstain: Int,
    val status: ResolutionStatus,
)

/**
 * Meritokratische Voteen (V0.2.3): lifecycle of an eBay/Vickrey basket auction opened on a
 * [MotionStatus.SCHEDULED] Motion. [OPEN] -> [CLOSED] (`GovernanceService.closeVote`,
 * runs the Vickrey settlement and creates a [ResolutionDto] with
 * [ResolutionMode.MERITOCRATIC]) | [ABORTED] (`abortVote`, no settlement, no
 * Resolution). Exactly one non-[ABORTED] Vote may exist per Motion at a time — see
 * `GovernanceService.openVote`.
 */
@Serializable
enum class VoteStatus { OPEN, CLOSED, ABORTED }

/**
 * A basket (`vote_option`). [basketTotalLtr] is computed server-side from this option's
 * ballots (never stored, never trusted from a client), see
 * `network.lapis.cloud.server.rpc.GovernanceService`.
 */
@Serializable
data class VoteOptionDto(
    val id: String,
    val voteId: String,
    val label: String,
    val position: Int,
    val basketTotalLtr: Decimal,
)

/**
 * The vote itself. `winnerOptionId`/`secondPriceLtr` are null while [status] is [VoteStatus
 * .OPEN], and both stay null even after close if the top two baskets tied (see
 * `network.lapis.cloud.server.rpc.VoteSettlement` KDoc for the tie rule) — a tied vote
 * produces no winner, no charges, and resolves its Motion to [MotionStatus.POSTPONED].
 */
@Serializable
data class VoteDto(
    val id: String,
    val motionId: String,
    val meetingId: String,
    val title: String,
    val status: VoteStatus,
    val options: List<VoteOptionDto>,
    val winnerOptionId: String?,
    val secondPriceLtr: Decimal?,
    val openedById: String,
    val openedByDisplayName: String,
    val openedAt: LocalDateTime,
    val closedAt: LocalDateTime?,
    val resolutionId: String?,
)

/**
 * The per-member ballot (`vote_ballot`). [settledLtr] is null while the Vote is
 * [VoteStatus.OPEN] and computed once at close — 0 for a losing ballot, never null once
 * settled.
 */
@Serializable
data class VoteBallotDto(
    val id: String,
    val voteId: String,
    val optionId: String,
    val memberId: String,
    val memberDisplayName: String,
    val stakeLtr: Decimal,
    val settledLtr: Decimal?,
    val castAt: LocalDateTime,
)

/** Two options ("YES"/"NO") by default; ordering follows list order, `position` 0-based. */
@Serializable
data class VoteOpenInput(
    val motionId: String,
    val optionLabels: List<String> = listOf("YES", "NO"),
)

@Serializable
data class VoteBallotInput(
    val voteId: String,
    val optionId: String,
    val stakeLtr: Decimal,
)
