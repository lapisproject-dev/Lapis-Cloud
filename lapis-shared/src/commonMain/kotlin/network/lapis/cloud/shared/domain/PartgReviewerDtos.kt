package network.lapis.cloud.shared.domain

import kotlinx.datetime.LocalDateTime
import kotlinx.serialization.Serializable

// V1.9.310 (R6b) -- read access of an auditor WITHOUT an account to kept versions of the accountability report. See
// `docs/features/partg-report.adoc` ("Read access for the auditor"). Nothing here carries a token, a code, a hash of either, or an IP.

/** Where an invitation stands. Computed, never stored: REVOKED before LOCKED before EXPIRED before ACTIVE. Literal order is load-bearing. */
@Serializable
enum class PartgReviewerInvitationStatus { ACTIVE, EXPIRED, REVOKED, LOCKED }

/** What the usage list of the board records. Literal order is load-bearing (the SQL CHECK of `partg_reviewer_event.kind` lists the same names). */
@Serializable
enum class PartgReviewerEventKind {
    INVITATION_CREATED,
    INVITATION_REVOKED,
    CODE_SENT,
    CODE_FAILED,
    INVITATION_LOCKED,
    SESSION_OPENED,
    SESSION_ENDED,
    DOWNLOAD,
    DOCUMENT_VIEWED,
    COMPARISON_VIEWED,
}

/** The file formats an auditor can take. The JSON is the stored text, byte for byte. */
@Serializable
enum class PartgReviewerFileFormat { PDF, XLSX, JSON }

/** After-snapshot of an `AuditEntityType.PARTG_REVIEWER_ACCESS` entry. IDS ONLY: no hash, no E-mail address, no code, no IP, no content. */
@Serializable
data class PartgReviewerAuditSnapshot(
    val event: PartgReviewerEventKind,
    val invitationId: String,
    val versionId: String? = null,
    val format: PartgReviewerFileFormat? = null,
    val count: Int? = null,
)

/** One line of the usage list: kind, version number (when the version still exists) and time. No actor, no IP. */
@Serializable
data class PartgReviewerEventDto(
    val kind: PartgReviewerEventKind,
    val occurredAt: LocalDateTime,
    val versionNumber: Int? = null,
    val format: PartgReviewerFileFormat? = null,
)

/** One invitation as the board sees it. No token, no hash. [email] is `null` once it was erased or the retention period ran out. */
@Serializable
data class PartgReviewerInvitationDto(
    val id: String,
    val year: Int,
    val email: String?,
    val versionNumbers: List<Int>,
    val versionIds: List<String>,
    val createdAt: LocalDateTime,
    val expiresAt: LocalDateTime,
    val status: PartgReviewerInvitationStatus,
    val lastUsedAt: LocalDateTime?,
    val events: List<PartgReviewerEventDto>,
)

/** The answer of `createReviewerInvitation`. [link] is shown ONCE, here, and is not stored in plain text anywhere. */
@Serializable
data class PartgReviewerInvitationCreatedDto(
    val invitation: PartgReviewerInvitationDto,
    val link: String,
)

/** What the auditor's page needs to know about its own session. */
@Serializable
data class PartgReviewerSessionInfoDto(
    val year: Int,
    val invitationExpiresAt: LocalDateTime,
    val sessionEndsAt: LocalDateTime,
)

/** One kept version for the auditor: no name of a member, no comment. */
@Serializable
data class PartgReviewerVersionDto(
    val id: String,
    val versionNumber: Int,
    val frozenAt: LocalDateTime,
    val contentHash: String,
    val finalized: Boolean,
)

/** Rules shared by the board dialog and the server. */
object PartgReviewerRules {
    val VALIDITY_DAYS: List<Int> = listOf(7, 14, 30, 90)
    const val DEFAULT_VALIDITY_DAYS = 14
    const val MAX_VERSIONS = 50
    const val MAX_OPEN_INVITATIONS_PER_YEAR = 20
    const val MAX_EMAIL_LENGTH = 254
    const val CODE_DIGITS = 6
    const val CODE_VALID_MINUTES = 10
    const val MAX_CODE_ATTEMPTS = 5
    const val MAX_BURNED_CODES = 3
    const val MAX_CODES_PER_HOUR = 3
    const val MAX_CODES_PER_INVITATION = 10
    const val IDLE_MINUTES = 30
    const val ABSOLUTE_HOURS = 4
    const val EMAIL_RETENTION_DAYS = 90
    const val EVENTS_PER_INVITATION = 50

    private val EMAIL = Regex("""[^\s@<>"',;:\\()\[\]]+@[^\s@<>"',;:\\()\[\]]+\.[^\s@<>"',;:\\()\[\]]{2,}""")

    /** Trimmed, lower case; `null` for anything that is not a plain address (control characters, line breaks, over 254, no `@`). */
    fun normalizeEmail(raw: String?): String? {
        val value = raw?.trim()?.lowercase() ?: return null
        if (value.isEmpty() || value.length > MAX_EMAIL_LENGTH) return null
        if (value.any { it.isISOControl() }) return null
        return value.takeIf { EMAIL.matches(it) }
    }

    /** `482913` or `482 913` -> `482913`; anything else -> `null`. */
    fun normalizeCode(raw: String?): String? {
        val value = raw?.filterNot { it == ' ' } ?: return null
        return value.takeIf { it.length == CODE_DIGITS && it.all { c -> c in '0'..'9' } }
    }

    /** "482913" -> "482 913" (the form in the E-mail and the code field). */
    fun formatCode(code: String): String = if (code.length == CODE_DIGITS) "${code.substring(0, 3)} ${code.substring(3)}" else code
}
