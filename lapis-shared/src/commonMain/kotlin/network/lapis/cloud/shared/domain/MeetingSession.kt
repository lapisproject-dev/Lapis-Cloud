package network.lapis.cloud.shared.domain

import kotlinx.datetime.LocalDateTime
import kotlinx.serialization.Serializable

/**
 * Welle V1.9.236 "Sitzungsmodus im Konferenzraum" -- the phase of a Sitzung run in its conference room. Derived, never stored:
 * `HELD`/`CANCELLED` are [CLOSED]; a `PLANNED` Sitzung is [NOT_OPENED] until `meeting.opened_at` is set, then [OPEN].
 */
@Serializable
enum class MeetingSessionPhase { NOT_OPENED, OPEN, CLOSED }

/** Welle V1.9.236 -- what the caller is in this Sitzung. [LEAD] runs it, [MINUTES] keeps the attendance, [PARTICIPANT] only follows. */
@Serializable
enum class MeetingSessionRole { LEAD, MINUTES, PARTICIPANT }

/** Welle V1.9.236 -- how the attendance of this kind of committee is kept (the rule is exchangeable per committee type). */
@Serializable
enum class AttendanceRuleKind { NAME_LIST, UNSUPPORTED }

/** Welle V1.9.236 -- the hint "what comes next", only ever sent to the [MeetingSessionRole.LEAD]. */
@Serializable
enum class MeetingSessionNextStep { OPEN_MEETING, ADOPT_CONNECTED, ESTABLISH_QUORUM, NEXT_AGENDA_ITEM, NONE }

/** Welle V1.9.236 -- the quorum as the lead established it: three numbers and whether the calculation said "not met" at that moment. */
@Serializable
data class EstablishedQuorumDto(
    val at: LocalDateTime,
    val present: Int,
    val required: Int,
    val eligible: Int,
    val overridden: Boolean,
)

@Serializable
data class SessionAgendaItemDto(
    val id: String,
    val position: Int,
    val title: String,
)

/** Welle V1.9.236 -- one line of the attendance list. Only LEAD/MINUTES ever receive these. */
@Serializable
data class SessionRosterEntryDto(
    val memberId: String,
    val displayName: String,
    /** `null` = still open (no entry yet). */
    val status: AttendanceStatus?,
    val representedByMemberId: String? = null,
    val representedByDisplayName: String? = null,
    /** Whether this person is connected to the conference room right now. */
    val connected: Boolean = false,
)

@Serializable
data class SessionAttendanceInput(
    val memberId: String,
    /** `null` clears the entry ("open"). */
    val status: AttendanceStatus?,
    val representedByMemberId: String? = null,
)

/**
 * Welle V1.9.236 -- the state of the Sitzung bound to a conference room. A PARTICIPANT receives no names, no roster and no live quorum, only the
 * agenda, the phase and the established-quorum line.
 */
@Serializable
data class ConferenceMeetingStateDto(
    val meetingId: String,
    val meetingTitle: String,
    /** The committee of the Sitzung (an id, no name) -- the client needs it for the guest search of the lead. */
    val committeeId: String,
    val phase: MeetingSessionPhase,
    val openedAt: LocalDateTime?,
    val callerRole: MeetingSessionRole,
    val ruleKind: AttendanceRuleKind,
    /** Live calculation; LEAD/MINUTES only. */
    val liveQuorum: QuorumResultDto? = null,
    val establishedQuorum: EstablishedQuorumDto? = null,
    val agenda: List<SessionAgendaItemDto> = emptyList(),
    val currentAgendaItemId: String? = null,
    val nextStepHint: MeetingSessionNextStep? = null,
    val roster: List<SessionRosterEntryDto> = emptyList(),
    val guests: List<SessionRosterEntryDto> = emptyList(),
    val adoptableCount: Int = 0,
    val extraConnectedCount: Int = 0,
    /** V1.9.238 -- the motions of this Sitzung, per agenda item (all roles; the figures of a secret vote stay withheld). */
    val agendaMotions: List<SessionMotionDto> = emptyList(),
    /** V1.9.238 -- the vote that runs (or waits for its tally) in this Sitzung right now, if any; all roles see the banner. */
    val runningMotionVote: SessionMotionVoteDto? = null,
    /** V1.9.238 -- LEAD that may also record for the committee: the only caller that may attach a motion or open a vote. */
    val canManageMotions: Boolean = false,
    /** V1.9.238 -- motions that can be attached to the current agenda item; only sent when [canManageMotions] (capped). */
    val attachableMotions: List<SessionMotionRefDto> = emptyList(),
)

/** Welle V1.9.238 -- where a vote on a motion of the Sitzung stands. */
@Serializable
enum class SessionMotionVoteStatus { OPEN, CLOSED_AWAITING_TALLY, TALLIED, ABORTED }

/** Welle V1.9.238 -- the resolution written by the tally of a motion vote. [figuresWithheld] = a secret vote with too few ballots. */
@Serializable
data class SessionResolutionDto(
    val id: String,
    val number: String,
    val status: ResolutionStatus,
    val votesYes: Int,
    val votesNo: Int,
    val votesAbstain: Int,
    val figuresWithheld: Boolean,
    val quorumMet: Boolean,
    val decidedAt: LocalDateTime,
)

/**
 * Welle V1.9.238 -- a vote on a motion. The counters ([ballotCount], [eligibleCount]) are only ever set for LEAD/MINUTES, only for an open vote
 * and NEVER for a secret one; the tally counters only for LEAD/MINUTES while the tally waits.
 */
@Serializable
data class SessionMotionVoteDto(
    val electionId: String,
    val motionId: String,
    val motionTitle: String,
    val status: SessionMotionVoteStatus,
    val secret: Boolean,
    val ballotCount: Int? = null,
    val eligibleCount: Int? = null,
    val tallyApprovalCount: Int? = null,
    val tallyThreshold: Int? = null,
    /** Only on the RUNNING vote: the caller may close it (BOARD/ADMIN or a member of the election board) -- the server demands the same. */
    val canClose: Boolean = false,
)

/** Welle V1.9.238 -- one motion of an agenda item, with its vote and resolution (once there are any). */
@Serializable
data class SessionMotionDto(
    val id: String,
    val agendaItemId: String,
    val title: String,
    /** `currentText ?: text` -- the wording the vote and the resolution use. */
    val effectiveText: String,
    val status: MotionStatus,
    val vote: SessionMotionVoteDto? = null,
    val resolution: SessionResolutionDto? = null,
)

/** Welle V1.9.238 -- a motion that can still be attached to the current agenda item. */
@Serializable
data class SessionMotionRefDto(
    val id: String,
    val title: String,
    val status: MotionStatus,
)

/**
 * Welle V1.9.236 -- the answer of `getConferenceMeetingState`: [state] is `null` when there is no Sitzung for the caller in this room.
 * A wrapper because Kilua RPC cannot generate a client call for a nullable return type.
 */
@Serializable
data class ConferenceMeetingStateAnswer(
    val state: ConferenceMeetingStateDto? = null,
)
