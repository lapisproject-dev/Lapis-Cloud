package network.lapis.cloud.shared.domain

import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.serialization.Serializable

/**
 * Welle V1.9.240 "Protokoll fertigstellen" (D2) -- the finalised, immutable versions of the Protokoll of a Sitzung. A version is a snapshot
 * (canonical JSON + SHA-256) of the draft at the moment it was finalised; it is never edited, a correction is a NEW version.
 */
@Serializable
enum class ProtocolVersionStatus { AWAITING_CONFIRMATION, CONFIRMED, SUPERSEDED }

/** How a version is confirmed. Only [APP_CONFIRMATION] (a plain electronic confirmation) exists; the others are hooks, nothing sets them. */
@Serializable
enum class ProtocolConfirmationMode { APP_CONFIRMATION, PLATFORM_SEAL, QES }

@Serializable
enum class ProtocolConfirmationRole { LEAD, MINUTES }

@Serializable
enum class ProtocolApprovalResult { APPROVED, APPROVED_WITH_CORRECTIONS, NOT_APPROVED }

@Serializable
enum class ProtocolFinaliseOutcome { FINALISED, CONFIRMED, STALE, ALREADY_FINALISED, NOT_OPENED, EMPTY, PDF_LIMIT }

@Serializable
enum class ProtocolConfirmOutcome { CONFIRMED_ROLE, FULLY_CONFIRMED, ALREADY_CONFIRMED, NOT_AWAITING, PDF_PENDING, PDF_LIMIT }

const val PROTOCOL_CORRECTION_NOTE_MIN = 20
const val PROTOCOL_CORRECTION_NOTE_MAX = 1000

/** The fixed prefix of the hashed text: `sha256(PROTOCOL_HASH_PREFIX + canonicalJson)`. A format change gets a new prefix. */
const val PROTOCOL_HASH_PREFIX = "lapis-protocol-v1\n"

/** What finalising would do right now. The hash is empty unless the caller is LEAD or MINUTES. */
@Serializable
data class MeetingProtocolPreviewDto(
    val meetingId: String,
    val nextVersionNo: Int,
    val contentSha256: String,
    val itemsWithoutCourse: Int,
    val canFinalise: Boolean,
    val blockedReason: ProtocolFinaliseOutcome?,
    val callerRoles: Set<ProtocolConfirmationRole>,
    val minutesRequired: Boolean,
)

@Serializable
data class ProtocolConfirmationDto(
    val role: ProtocolConfirmationRole,
    val confirmerName: String,
    val confirmedAt: LocalDateTime,
)

@Serializable
data class ProtocolVersionDto(
    val versionId: String,
    val versionNo: Int,
    val status: ProtocolVersionStatus,
    val contentSha256: String,
    val correctionNote: String?,
    val finalisedAt: LocalDateTime,
    val confirmationMode: ProtocolConfirmationMode,
    val minutesRequired: Boolean,
    val confirmations: List<ProtocolConfirmationDto>,
    val missingRoles: Set<ProtocolConfirmationRole>,
    val pdfAvailable: Boolean,
    /** `true` for a CONFIRMED version whose PDF is still missing (archiving failed); LEAD/MINUTES may retry. Not a pending correction. */
    val pdfPending: Boolean = false,
)

@Serializable
data class MeetingProtocolVersionsDto(
    val meetingId: String,
    val callerRoles: Set<ProtocolConfirmationRole>,
    val canConfirm: Boolean,
    val canStartCorrection: Boolean,
    /** Newest first. */
    val versions: List<ProtocolVersionDto>,
)

@Serializable
data class ProtocolFinaliseResultDto(
    val outcome: ProtocolFinaliseOutcome,
    val version: ProtocolVersionDto?,
    /** Set on [ProtocolFinaliseOutcome.STALE]: the hash of the draft as it is now. */
    val currentSha256: String?,
)

@Serializable
data class ProtocolConfirmResultDto(
    val outcome: ProtocolConfirmOutcome,
    val version: ProtocolVersionDto?,
)

/** The "approve the Protokoll of the last Sitzung" item of an agenda. */
@Serializable
data class ProtocolApprovalDto(
    val sourceMeetingId: String,
    val sourceMeetingDate: LocalDate,
    val versionNo: Int?,
    val result: ProtocolApprovalResult?,
    val recordedAt: LocalDateTime?,
    val editable: Boolean,
    /** `true` after "approved with corrections" until a newer version of the source Protokoll exists. */
    val correctionPending: Boolean = false,
)

/** One task of the caller, taken from the newest CONFIRMED version of a Protokoll. [taskKey] is stable across versions. */
@Serializable
data class MyProtocolTaskDto(
    val taskKey: String,
    val meetingId: String,
    val meetingDate: LocalDate,
    val committeeName: String,
    val versionNo: Int,
    val description: String,
    val dueDate: LocalDate?,
    val done: Boolean,
    val doneAt: LocalDateTime?,
    val overdue: Boolean,
)
