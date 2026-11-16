package network.lapis.cloud.shared.domain

import kotlinx.datetime.LocalDate
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Welle V1.9.305 "Gruendungsmitglied" -- visibility of the founding-member label. The label itself is set by BOARD/ADMIN only
 * (`member.founding_member`); it is [PRIVATE] unless the member gave a versioned consent ([FoundingMemberRules.CONSENT_TEXT_VERSION]).
 * A separate enum on purpose: [MemberPhotoVisibility] has photo semantics.
 */
@Serializable
enum class FoundingMemberVisibility {
    PRIVATE,
    PUBLIC,
}

/** Why a "founding member since" date is refused. DOM-free, used by server and client. */
@Serializable
enum class FoundingMemberSinceViolation { IN_FUTURE, }

/** Shared constants and rules of the founding-member marker. The server is the authoritative enforcer. */
object FoundingMemberRules {
    /** Version tag of the consent wording. A change of [CONSENT_TEXT] REQUIRES a new version (pinned by a test). */
    const val CONSENT_TEXT_VERSION = "1"

    /** The label shown wherever the marker is displayed. */
    const val LABEL = "Gründungsmitglied"

    /** The consent wording shown to the member; names truthfully every place where the label appears. */
    const val CONSENT_TEXT =
        "Ich bin damit einverstanden, dass die Kennzeichnung „Gründungsmitglied“ öffentlich neben meinem Namen angezeigt wird: " +
            "auf den öffentlichen Seiten Vorstand und Politiker (nur, wenn ich dort geführt werde), in den einbettbaren Verzeichnissen " +
            "und auf der Prüfseite meines Mitgliedsausweises, die der QR-Code aufruft. " +
            "Ich kann diese Einwilligung jederzeit widerrufen. Sie erlischt, wenn der Vorstand die Kennzeichnung entfernt " +
            "oder ich nicht mehr aktives Mitglied bin."

    /** `null` = admissible (also `since == null`, "not known"). Only a date in the future is refused; no comparison with the join date. */
    fun validateSince(
        since: LocalDate?,
        today: LocalDate,
    ): FoundingMemberSinceViolation? = if (since != null && since > today) FoundingMemberSinceViolation.IN_FUTURE else null
}

/** The caller's OWN founding-member state. Carries no date: the member does not need it. */
@Serializable
data class OwnFoundingMemberDto(
    val foundingMember: Boolean,
    val visibility: FoundingMemberVisibility,
    /** The member consented, but to an older wording: the consent is not effective any more. */
    val consentOutdated: Boolean,
)

/** What a founding-member audit entry records. Fixed keys, never a name or free text. */
@Serializable
enum class FoundingMemberAuditEvent {
    @SerialName("member.founding_member.set")
    SET,

    @SerialName("member.founding_member.cleared")
    CLEARED,

    @SerialName("member.founding_member.since_changed")
    SINCE_CHANGED,

    @SerialName("member.founding_member.publication_revoked")
    PUBLICATION_REVOKED,

    @SerialName("member.founding_member.published")
    PUBLISHED,

    @SerialName("member.founding_member.unpublished")
    UNPUBLISHED,

    @SerialName("member.founding_member.publication_revoked_by_status_change")
    PUBLICATION_REVOKED_BY_STATUS_CHANGE,
}

/** Audit facts of one founding-member event. The dates are low-sensitivity; the consent text itself is never stored. */
@Serializable
data class FoundingMemberAuditFacts(
    val event: FoundingMemberAuditEvent,
    val sinceBefore: LocalDate? = null,
    val sinceAfter: LocalDate? = null,
    val consentVersion: String? = null,
)
