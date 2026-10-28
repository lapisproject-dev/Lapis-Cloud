package network.lapis.cloud.shared.domain

import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.serialization.Serializable

/**
 * Welle V1.9.239 "Protokollentwurf in der Sitzung" (D1) -- the phase of the draft. [LOCKED] is the hook for the finalisation of a later wave:
 * D1 never sets it, but every write refuses once it is set.
 */
@Serializable
enum class ProtocolDraftPhase { DRAFT, LOCKED }

/** Maximum plain-text length of the text of ONE agenda item (UTF-16 units, counted after sanitising: the bound that fits H2 and PostgreSQL). */
const val PROTOCOL_BLOCK_MAX_LENGTH = 8_000

/** Maximum length of the description of ONE task. */
const val PROTOCOL_TASK_MAX_LENGTH = 500

/** Maximum number of tasks of ONE draft. */
const val PROTOCOL_MAX_TASKS = 50

/**
 * Welle V1.9.239 -- the Protokoll draft of a Sitzung as the caller may see it. Everything the system fills in (header, attendance, quorum,
 * resolutions) is derived when the draft is read; the free texts and the tasks are what people wrote. Named `MeetingProtocol*` because
 * [ProtocolDraftDto] (the older, unrelated "generate a draft" result) already exists.
 *
 * **No ballot data, ever**: a resolution carries its counts only; no field links a person to a vote.
 */
@Serializable
data class MeetingProtocolDraftDto(
    val meetingId: String,
    val phase: ProtocolDraftPhase,
    /** Whether THIS caller may write right now (role LEAD or MINUTES, draft not locked, Sitzung opened and not cancelled). */
    val editable: Boolean,
    val role: MeetingSessionRole,
    val header: ProtocolHeaderDto,
    val attendance: ProtocolAttendanceDto,
    val quorum: ProtocolQuorumDto?,
    val items: List<ProtocolAgendaItemDto>,
    /** Resolutions of the Sitzung that belong to no agenda item. */
    val unassignedResolutions: List<ProtocolResolutionDto> = emptyList(),
    /** Who can be given a task; empty unless [editable]. */
    val assignableMembers: List<ProtocolMemberRefDto> = emptyList(),
    val taskCount: Int,
    val serverNow: LocalDateTime,
    /** V1.9.240 -- the newest finalised version of the Protokoll (`null` while none was finalised yet). */
    val latestVersionNo: Int? = null,
    val latestStatus: ProtocolVersionStatus? = null,
    /** V1.9.240 -- set while this draft is a correction of that version. */
    val correctionOfVersionNo: Int? = null,
    /** V1.9.240 -- every role the caller has in the Sitzung (a person can be chair and minute taker at once). */
    val roles: Set<MeetingSessionRole> = emptySet(),
)

@Serializable
data class ProtocolHeaderDto(
    val committeeName: String,
    val committeeType: CommitteeType,
    val title: String,
    val scheduledAt: LocalDateTime,
    val format: MeetingFormat,
    val location: String?,
    val openedAt: LocalDateTime?,
    /** Not recorded anywhere yet (a later wave adds the moment the Sitzung was closed); always `null` in D1. */
    val endedAt: LocalDateTime?,
    val chairName: String?,
    val minuteTakerName: String?,
)

/** The attendance as it was CONFIRMED in the attendance table; raw "connected" suggestions are never part of it. */
@Serializable
data class ProtocolAttendanceDto(
    val present: List<ProtocolPersonDto> = emptyList(),
    val excused: List<ProtocolPersonDto> = emptyList(),
    val absent: List<ProtocolPersonDto> = emptyList(),
    val represented: List<ProtocolRepresentationDto> = emptyList(),
    val guests: List<ProtocolPersonDto> = emptyList(),
)

/** A name only: the member id deliberately does not leave the server (a participant gets the same view). */
@Serializable
data class ProtocolPersonDto(
    val displayName: String,
)

@Serializable
data class ProtocolRepresentationDto(
    val representedName: String,
    val representativeName: String?,
)

@Serializable
data class ProtocolQuorumDto(
    val establishedAt: LocalDateTime,
    val present: Int,
    val required: Int,
    val eligible: Int,
    val overridden: Boolean,
)

@Serializable
data class ProtocolAgendaItemDto(
    val agendaItemId: String,
    val position: Int,
    val title: String,
    /** The text written so far; empty while nobody wrote (version 0). */
    val body: String,
    /** The version the caller must name when saving; 0 = no text saved yet. */
    val version: Long,
    val updatedAt: LocalDateTime?,
    val resolutions: List<ProtocolResolutionDto> = emptyList(),
    val tasks: List<ProtocolTaskDto> = emptyList(),
    /** V1.9.240 -- only on the item that approves the Protokoll of the previous Sitzung. */
    val approval: ProtocolApprovalDto? = null,
)

/** A resolution with its counts only -- never a ballot, never a voter. [secretBallot] marks a resolution decided in a secret election. */
@Serializable
data class ProtocolResolutionDto(
    val resolutionId: String,
    val number: String,
    val title: String,
    val text: String,
    val yes: Int,
    val no: Int,
    val abstain: Int,
    val status: ResolutionStatus,
    val quorumMet: Boolean,
    val decidedAt: LocalDateTime,
    val secretBallot: Boolean,
    /** `true` when a secret vote had too few ballots: [yes]/[no]/[abstain] are then 0 and must not be shown. */
    val figuresWithheld: Boolean = false,
)

@Serializable
data class ProtocolTaskDto(
    val taskId: String,
    val agendaItemId: String,
    val assigneeName: String,
    val description: String,
    val dueDate: LocalDate?,
    val createdAt: LocalDateTime,
)

@Serializable
data class ProtocolMemberRefDto(
    val memberId: String,
    val displayName: String,
)

/** The answer to a save: a lost race is an ordinary answer, not an exception ([saved] = false carries the current text). */
@Serializable
data class ProtocolBlockSaveResultDto(
    val saved: Boolean,
    val version: Long,
    val currentBody: String?,
    val currentUpdatedAt: LocalDateTime?,
)

@Serializable
data class ProtocolTaskInput(
    val agendaItemId: String,
    val assigneeMemberId: String,
    val description: String,
    val dueDate: LocalDate?,
)
