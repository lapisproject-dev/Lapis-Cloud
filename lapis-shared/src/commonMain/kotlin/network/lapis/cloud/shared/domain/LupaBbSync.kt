package network.lapis.cloud.shared.domain

import kotlinx.datetime.LocalDateTime
import kotlinx.serialization.Serializable

/*
 * Welle V1.9.226 "LupaBB-Stammdatenabgleich" -- the wire types between the client and `ILupaBbSyncService`. See docs/features/lupabb-sync.adoc.
 * Every text that a person reads is an enum CODE here; the client translates it (the server never sends a German free text), and nothing in
 * these types carries a credential. New values go to the END of every enum (the codes are stored in nothing, but the order is a habit).
 */

/** The class a record of the comparison falls into. */
@Serializable
enum class LupaBbCategory { NEW, CHANGED, UNCHANGED, AMBIGUOUS, SKIPPED, ERROR, MISSING }

/** The filter of a preview page. [ACTION_NEEDED] = [LupaBbCategory.AMBIGUOUS] + [LupaBbCategory.ERROR]. */
@Serializable
enum class LupaBbPreviewFilter { ACTION_NEEDED, NEW, CHANGED, UNCHANGED, AMBIGUOUS, SKIPPED, ERROR, MISSING, ALL }

/**
 * The fields the sync compares and may write. The e-mail address is deliberately NOT here: it is the sign-in and password-reset identity
 * since V1.9.56 and is never written by the sync (a difference only shows as the notice [LupaBbRowReasonCode.EMAIL_DIFFERS]).
 */
@Serializable
enum class LupaBbField {
    PERSON_NUMBER,
    DISPLAY_NAME,
    GIVEN_NAME,
    FAMILY_NAME,
    TITLE,
    NAME_AFFIX,
    COMPANY,
    PERSON_TYPE,
    STREET,
    ADDRESS_SUPPLEMENT,
    PO_BOX,
    POSTAL_CODE,
    CITY,
    COUNTRY,
    DATE_OF_BIRTH,
    PHONE,
    MOBILE,
    REGIONAL_CHAPTER,
    STATUS,
    MISSING_FLAG,
}

/** Why a record is in the class it is in (an error, a skip, an ambiguity) or a notice about it. */
@Serializable
enum class LupaBbRowReasonCode {
    NO_EMAIL,
    INVALID_EMAIL,
    INVALID_DATE,
    FIELD_TOO_LONG,
    CONTROL_CHARACTER,
    INVALID_PERSON_NUMBER,
    DUPLICATE_PERSON_NUMBER,
    NO_NAME,
    UNKNOWN_STATUS,
    CANCELLED_STATUS,
    ANONYMIZED,
    ERASED_IN_LAPIS,
    ADMIN_ROLE_MANUAL,
    STATUS_TRANSITION_NOT_ALLOWED,
    CHAPTER_REQUIRED,
    EMAIL_DIFFERS,
    EMAIL_BELONGS_TO_OTHER,
    NAME_MISMATCH,
    MULTIPLE_CANDIDATES,
    COUNTRY_AMBIGUOUS,
    CHAPTER_NOT_FOUND,
    EMAIL_TAKEN,
    PERSON_NUMBER_TAKEN,
    CHANGED_SINCE_PREVIEW,
    WRITE_FAILED,
    LINKED_WAITING,
    MISSING_IN_LUPABB,
    REAPPEARED,
    LEAVES_DECEASED,
}

/** A remark about the whole preview. */
@Serializable
enum class LupaBbNoticeCode {
    /** The records carry no regional chapter, so the field stays untouched. */
    CHAPTER_NOT_SUPPLIED,
}

/** The state of the stored credentials and of the last applied run. Never a credential. */
@Serializable
data class LupaBbSyncStatusDto(
    val credentialsSet: Boolean,
    val setAt: LocalDateTime? = null,
    val setByName: String? = null,
    /** `false` when the server has no `LAPIS_SECRET_ENCRYPTION_KEY`: credentials cannot be stored. */
    val encryptionAvailable: Boolean = true,
    val lastRun: LupaBbLastRunDto? = null,
)

/** The counters of the last applied run. */
@Serializable
data class LupaBbLastRunDto(
    val finishedAt: LocalDateTime,
    val created: Int,
    val updated: Int,
    val unchanged: Int,
    val skipped: Int,
    val ambiguous: Int,
    val errors: Int,
    val missing: Int,
)

/** The credentials of the LupaBB API: HTTP Digest user and password plus the API key. Never printed. */
@Serializable
class LupaBbCredentialsInput(
    val username: String,
    val password: String,
    val apiKey: String,
) {
    override fun toString(): String = "LupaBbCredentialsInput(***)"
}

/** What the loaded comparison contains; the rows come page by page through `getPreviewPage`. */
@Serializable
data class LupaBbPreviewSummaryDto(
    val token: String,
    val expiresAt: LocalDateTime,
    val counts: Map<LupaBbCategory, Int>,
    val newCount: Int,
    val changedCount: Int,
    val statusChangeCount: Int,
    /** Status changes that end a membership or record a death. */
    val severeStatusChangeCount: Int,
    val notices: List<LupaBbNoticeCode> = emptyList(),
)

@Serializable
data class LupaBbFieldDiffDto(
    val field: LupaBbField,
    val before: String? = null,
    val after: String? = null,
)

@Serializable
data class LupaBbReasonDto(
    val code: LupaBbRowReasonCode,
    val param: String? = null,
)

@Serializable
data class LupaBbCandidateDto(
    val memberId: String,
    val name: String,
    val email: String,
)

@Serializable
data class LupaBbPreviewRowDto(
    val personNumber: Long,
    val name: String,
    val email: String?,
    val category: LupaBbCategory,
    val diffs: List<LupaBbFieldDiffDto> = emptyList(),
    val reasons: List<LupaBbReasonDto> = emptyList(),
    val candidates: List<LupaBbCandidateDto> = emptyList(),
)

@Serializable
data class LupaBbPreviewPageDto(
    val total: Int,
    val offset: Int,
    val rows: List<LupaBbPreviewRowDto>,
    /** The counters of the WHOLE comparison at the time of the call (they change after a manual link). */
    val counts: Map<LupaBbCategory, Int> = emptyMap(),
)

/** A record the apply step could not write: the person number and a fixed code, never a message. */
@Serializable
data class LupaBbApplyFailureDto(
    val personNumber: Long,
    val code: LupaBbRowReasonCode,
)

@Serializable
data class LupaBbApplyResultDto(
    val runId: String,
    val created: Int,
    val updated: Int,
    val unchanged: Int,
    val skipped: Int,
    val ambiguous: Int,
    val errors: Int,
    val missing: Int,
    val failures: List<LupaBbApplyFailureDto> = emptyList(),
)
