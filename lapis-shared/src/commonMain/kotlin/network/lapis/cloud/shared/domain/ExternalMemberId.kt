package network.lapis.cloud.shared.domain

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Welle V1.9.307 "Externe Mitglieds-ID" -- shared, DOM-free rules for the identifier a member carries in an external system (for example the
 * Wabeo/LupaBB id of an association). The server is the authoritative enforcer; the client uses the same rules for inline feedback.
 */
object ExternalMemberIdRules {
    const val ID_MAX_LENGTH = 64
    const val SOURCE_MAX_LENGTH = 32

    /** Letters, digits and `. _ : / -`; must start with a letter or digit. Case is significant and never normalised. */
    val ID_REGEX = Regex("^[A-Za-z0-9][A-Za-z0-9._:/-]{0,63}$")
    val SOURCE_REGEX = Regex("^[A-Za-z0-9][A-Za-z0-9 ._-]{0,31}$")

    /** Trims surrounding whitespace only. `null` if the result is empty or invalid. The length is checked BEFORE the regex (DoS guard). */
    fun normalizeId(raw: String): String? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty() || trimmed.length > ID_MAX_LENGTH) return null
        return trimmed.takeIf { ID_REGEX.matches(it) }
    }

    fun isValidSource(raw: String): Boolean = raw.length <= SOURCE_MAX_LENGTH && SOURCE_REGEX.matches(raw)
}

/** What an external-member-id audit entry records. Fixed keys, never the id value. */
@Serializable
enum class ExternalMemberIdAuditEvent {
    @SerialName("member.external_id.set_by_claim")
    SET_BY_CLAIM,

    @SerialName("member.external_id.claim_mismatch")
    CLAIM_MISMATCH,

    @SerialName("member.external_id.claim_duplicate")
    CLAIM_DUPLICATE,

    @SerialName("member.external_id.set_manually")
    SET_MANUALLY,

    @SerialName("member.external_id.replaced_manually")
    REPLACED_MANUALLY,

    @SerialName("member.external_id.cleared_manually")
    CLEARED_MANUALLY,
}

/** Audit facts of one external-member-id event: the event and the source label. Deliberately NO id value (the audit log is hash-chained and not erasable). */
@Serializable
data class ExternalMemberIdAuditFacts(
    val event: ExternalMemberIdAuditEvent,
    val source: String? = null,
)

/** The instance-wide external-id settings the client needs to show the card, the badge and the confirmation wording. */
@Serializable
data class ExternalMemberIdSettingsDto(
    /** The configured source label; `null` while the feature is off (the card stays invisible). */
    val source: String? = null,
    /** `LAPIS_REQUIRE_EXTERNAL_ID_FOR_VOTING`: a member without an external id has no voting right. */
    val requiredForVoting: Boolean = false,
    /** The caller's OWN stored external id ("Meine Daten"); `null` when none. Never another member's id. */
    val ownExternalMemberId: String? = null,
    /** The source label stored with [ownExternalMemberId]. */
    val ownExternalMemberIdSource: String? = null,
)
