package network.lapis.cloud.shared.domain

import kotlinx.datetime.LocalDateTime
import kotlinx.serialization.Serializable

/** Welle V1.4.2 "Interessenten-/Sympathisanten-CRM" -- see `38-crm.kuml.kts` for the full rationale. */
@Serializable
enum class CrmContactType { INTERESSENT, SYMPATHISANT, FOERDERER, EHEMALIGES_MITGLIED, PRESSE }

/** Art. 6(1) DSGVO lawful basis -- mandatory, deliberate choice at creation. See `CrmContactPolicy.validate`. */
@Serializable
enum class CrmLawfulBasis { CONSENT, LEGITIMATE_INTEREST, CONTRACT }

@Serializable
enum class CrmInteractionKind { CALL, MEETING, EMAIL, LETTER, EVENT, NOTE }

/**
 * [mayReceiveEmail] is SERVER-computed (`CrmContactPolicy.mayReceiveEmail`) -- the client never
 * derives it from [email] alone. See that function's own KDoc for why: there is deliberately no CRM
 * mailing path in this wave, and this field is the one place a future one must consult.
 */
@Serializable
data class CrmContactDto(
    val id: String,
    val displayName: String,
    val email: String?,
    val phone: String?,
    val street: String?,
    val postalCode: String?,
    val city: String?,
    val country: String?,
    val contactType: CrmContactType,
    val lawfulBasis: CrmLawfulBasis,
    val consentSource: String?,
    val consentGivenAt: LocalDateTime?,
    val consentWithdrawnAt: LocalDateTime?,
    val externalDonorId: String?,
    val memberId: String?,
    val createdAt: LocalDateTime,
    val createdBy: String,
    val lastInteractionAt: LocalDateTime?,
    val retentionReviewDueAt: LocalDateTime,
    val archivedAt: LocalDateTime?,
    val mayReceiveEmail: Boolean,
)

@Serializable
data class CrmContactInput(
    val displayName: String,
    val email: String?,
    val phone: String?,
    val street: String?,
    val postalCode: String?,
    val city: String?,
    val country: String?,
    val contactType: CrmContactType,
    val lawfulBasis: CrmLawfulBasis,
    val consentSource: String?,
    val consentGivenAt: LocalDateTime?,
    val externalDonorId: String?,
    val memberId: String?,
    /**
     * Explicit "erase this consent evidence" signal for `ICrmService.updateContact` -- see
     * `CrmContactStore.update`'s KDoc "Explicit consent-evidence erasure". Distinct from simply
     * submitting `null`/blank for [consentSource]/[consentGivenAt], which (deliberately) means
     * "keep whatever evidence is already on file", not "delete it" -- see that same KDoc's
     * "Consent evidence is preserved" section for why the two must not share a codepath. Only
     * meaningful together with [lawfulBasis] != [CrmLawfulBasis.CONSENT] (rejected by
     * `CrmContactPolicy.validate` otherwise, since an active CONSENT basis requires exactly the
     * evidence this flag would erase). Defaults to `false` so every existing caller (create, and
     * every update that is not an explicit correction of a wrongly-recorded consent) is
     * unaffected.
     */
    val clearConsentEvidence: Boolean = false,
)

@Serializable
data class CrmInteractionDto(
    val id: String,
    val contactId: String,
    val occurredAt: LocalDateTime,
    val kind: CrmInteractionKind,
    val summary: String,
    val recordedBy: String,
    val recordedByDisplayName: String,
    val recordedAt: LocalDateTime,
)

@Serializable
data class CrmInteractionInput(
    val contactId: String,
    /** `null` defaults to "now" server-side -- see `CrmService.recordInteraction`. */
    val occurredAt: LocalDateTime?,
    val kind: CrmInteractionKind,
    val summary: String,
)

/** Page shape mirrors `WebhookDeliveryPageDto`/`CrmContactsScreen`'s own pagination controls. */
@Serializable
data class CrmContactPageDto(
    val items: List<CrmContactDto>,
    val total: Int,
)
