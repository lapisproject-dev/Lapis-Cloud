package network.lapis.cloud.shared.domain

import dev.kilua.rpc.types.Decimal
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.serialization.Serializable

// SEPA-Lastschriftmandate domain, Welle V1.2.2 (vault plan "sepa_v1.2.2_plan.md"). Payments.kt
// stays untouched (it carries the V1.2.1 gate DTOs) -- same split Conference.kt/
// ConferenceStream.kt/ConferenceRecording.kt already live within the conference domain.
//
// Literal order is load-bearing. PaymentsSchemaDriftTest pins every dev.kuml.erm.model.ErmDataType.Enum
// against <Enum>.entries.map { it.name } in EXACTLY this order. Never reorder, only append.

/** Mandate state. See `SepaService` KDoc "mandate state machine". Longest literal REVOKED/EXPIRED (7). */
@Serializable
enum class SepaMandateStatus { ACTIVE, REVOKED, EXPIRED }

/**
 * SEPA sequence type of the next submission. All literals exactly 4 characters (ISO-20022 `SeqTp`).
 * `OOFF`/`FNAL` are never written in V1.2.2 -- they exist because the pain.008 schema knows them and
 * [network.lapis.cloud.server.payment.sepa.SepaPain008Writer] must be able to serialize them without a
 * later wave having to widen this enum (and thus the CHECK constraint + column width) itself.
 */
@Serializable
enum class SepaSequenceType { FRST, RCUR, OOFF, FNAL }

/**
 * Batch lifecycle. DRAFT -> NOTIFIED -> GENERATED -> SUBMITTED -> SETTLED, CANCELLED reachable from
 * any non-terminal state before SUBMITTED. Longest literal GENERATED/SUBMITTED/CANCELLED (9).
 */
@Serializable
enum class SepaDebitBatchStatus { DRAFT, NOTIFIED, GENERATED, SUBMITTED, SETTLED, CANCELLED }

/**
 * Item lifecycle. SETTLEABLE is the intermediate state [network.lapis.cloud.server.payment.sepa.SepaBatchPoller]
 * sets after the 8-week return window elapses without a return: "no return arrived, may now be
 * posted" -- the posting itself is triggered by a human via `ISepaService.settleBatch` (plan D-6).
 * Longest literal SETTLEABLE (10).
 */
@Serializable
enum class SepaDebitItemStatus { PENDING, SETTLEABLE, SETTLED, RETURNED, CANCELLED }

/**
 * ISO-20022 R-transaction codes this application knows. Deliberately a closed list rather than a
 * free `VARCHAR(4)`: the UI shows a German plain-text label for each code, and an unknown code would
 * have none there. `OTHER` is the catch-all -- the bank's own plain text then lives in `reason_text`.
 *
 * `MD01` (no valid mandate) and `MD06` (payer's objection) are the two codes that FORCE the mandate's
 * revocation -- see [SepaReturnReasonSets.FORCES_MANDATE_REVOCATION]. Longest literal OTHER (5).
 */
@Serializable
enum class SepaReturnReason { AC01, AC04, AC06, AC13, AG01, AM04, MD01, MD06, MD07, MS02, MS03, SL01, OTHER }

/**
 * The ONE place "which R-codes do X" is answered -- exactly the [ContributionStatusSets]/
 * [MemberStatusSets] pattern.
 */
object SepaReturnReasonSets {
    /**
     * A mandate the payer's bank rejects (MD01 "no mandate") or the payer objects to (MD06 "refund
     * requested by end customer") must not be reused for the next run. MD07 ("end customer deceased")
     * likewise -- a mandate with no living mandate-giver has lapsed.
     *
     * To a human reviewer: the MD07 -> revocation mapping is this plan's own factual interpretation,
     * not legal advice -- flagged as such in KDoc, same disclosure discipline as
     * `PartyDonationComplianceCalculator`.
     */
    val FORCES_MANDATE_REVOCATION: Set<SepaReturnReason> = setOf(SepaReturnReason.MD01, SepaReturnReason.MD06, SepaReturnReason.MD07)
}

/**
 * A mandate from the client's point of view. **Never carries the full IBAN** -- only
 * [debtorIbanLast4]. The IBAN leaves the database exclusively toward a pain.008 file.
 *
 * [createdByMemberId] != [memberId] marks a mandate entered on the member's behalf (decision point
 * E-12) -- the client shows this visibly, see [createdBySelf].
 */
@Serializable
data class SepaMandateDto(
    val id: String,
    val memberId: String,
    val memberDisplayName: String,
    val mandateReference: String,
    val debtorName: String,
    val debtorIbanLast4: String,
    val debtorBic: String?,
    val signatureDate: LocalDate,
    val sequenceType: SepaSequenceType,
    val status: SepaMandateStatus,
    val grantedAt: LocalDateTime,
    val revokedAt: LocalDateTime?,
    val revocationReason: String?,
    val lastUsedAt: LocalDate?,
    val lastDebitedAmount: Decimal?,
    /** `granted_at`/`last_used_at` + 36 months -- the date the poller sets this mandate to EXPIRED. */
    val expiresAt: LocalDate,
    val createdByMemberId: String,
    val createdByDisplayName: String,
    /** `false` -> entered on the member's behalf (E-12). Server-computed, never client-set. */
    val createdBySelf: Boolean,
)

/**
 * [debtorIban] is the ONLY place in the whole system a full IBAN ever crosses the wire -- inbound,
 * never outbound. Sealed with [network.lapis.cloud.server.crypto.SecretBox] immediately server-side,
 * never returned in plaintext again.
 *
 * [memberId] is `null` for "I am granting my own mandate" and settable only by TREASURER/BOARD/ADMIN
 * (E-12); a MEMBER who sets a foreign id here gets `ForbiddenException`.
 *
 * [mandateTextAcknowledged] must be `true` -- the mandatory checkbox under the mandate text in the
 * form. Checked server-side, not only client-side.
 */
@Serializable
data class SepaMandateInput(
    val memberId: String? = null,
    val debtorName: String,
    val debtorIban: String,
    val debtorBic: String? = null,
    val signatureDate: LocalDate,
    val mandateTextAcknowledged: Boolean,
)

@Serializable
data class SepaDebitBatchDto(
    val id: String,
    val messageId: String,
    val paymentInfoId: String,
    val requestedCollectionDate: LocalDate,
    val sequenceType: SepaSequenceType,
    val status: SepaDebitBatchStatus,
    val itemCount: Int,
    val totalAmount: Decimal,
    val createdByDisplayName: String,
    val createdAt: LocalDateTime,
    val notifiedAt: LocalDateTime?,
    /**
     * Notice period in calendar days, fixed at `notifyBatch` time. Normally
     * `organization_settings.sepa_prenotification_days`; the full period if at least one item has an
     * amount increase (E-7). `null` while status == DRAFT.
     */
    val requiredNoticeDays: Int?,
    /**
     * `notified_at.date + required_notice_days`. NOT a calendar-time gate -- `generateBatchFile`
     * (`SepaService.prepareBatchFileGeneration`) requires this batch's OWN [requestedCollectionDate]
     * to be on or after this date, not the calendar date the check happens to run on. Since
     * [requestedCollectionDate] is fixed once the batch is NOTIFIED, whether generation is possible
     * is a permanent, timeless property of the batch -- it never "becomes" possible by waiting for a
     * clock to advance past this date (Review Round 2, 2026-08-20, MAJOR -- see
     * `SepaAuthzUi.nextBatchAction` KDoc for the client-side bug this earlier, imprecise phrasing
     * caused).
     */
    val fileGenerationAllowedFrom: LocalDate?,
    val generatedAt: LocalDateTime?,
    val generatedDocumentId: String?,
    val prenotificationDocumentId: String?,
    val submittedAt: LocalDateTime?,
    val submittedNote: String?,
    val settledAt: LocalDateTime?,
    /** `submitted_at.date + 56` -- from this day the poller marks unreturned items SETTLEABLE (E-9). */
    val settlementEligibleFrom: LocalDate?,
    val cancelledAt: LocalDateTime?,
    val cancellationReason: String?,
)

@Serializable
data class SepaDebitBatchDetailDto(
    val batch: SepaDebitBatchDto,
    val items: List<SepaDebitItemDto>,
    /**
     * M-4 (Review Round 1, 2026-08-19, MAJOR): ids of items whose posting FAILED during the most
     * recent `ISepaService.settleBatch` call on this batch (`requireBalanced`/`CashRegisterGuard`
     * rejected the posting) -- empty for `getBatch`/`listBatches` and for a `settleBatch` call where
     * every settleable item posted successfully. Before this field existed, a failed item's posting
     * was logged (after this same round's fix) but otherwise invisible to the calling treasurer --
     * the response looked identical whether every item settled or some silently did not. A failed
     * item stays in [SepaDebitItemStatus.SETTLEABLE] (see [SepaDebitItemDto.status] on the
     * corresponding entry in [items]) and is retried the next time `settleBatch` is called for this
     * batch.
     */
    val failedItemIds: List<String> = emptyList(),
)

@Serializable
data class SepaDebitItemDto(
    val id: String,
    val batchId: String,
    val contributionId: String,
    val memberDisplayName: String,
    val mandateId: String,
    val mandateReference: String,
    val debtorIbanLast4: String,
    val endToEndId: String,
    val amount: Decimal,
    val remittanceInformation: String,
    val status: SepaDebitItemStatus,
    val settleableAt: LocalDate?,
    val journalEntryId: String?,
    val returnReason: SepaReturnReason?,
)

/**
 * Preview BEFORE a run is created -- "which contributions would be collected, for what total".
 * Purely read-only, changes nothing. Prevents the class of error where a treasurer creates a run and
 * only afterward sees it has 400 instead of 40 items.
 */
@Serializable
data class SepaDebitBatchPreviewDto(
    val itemCount: Int,
    val totalAmount: Decimal,
    val items: List<SepaDebitBatchPreviewItemDto>,
    /** Contributions that, despite being due, would NOT be included, with a reason. */
    val excluded: List<SepaDebitBatchExclusionDto>,
)

@Serializable
data class SepaDebitBatchPreviewItemDto(
    val contributionId: String,
    val memberDisplayName: String,
    val amount: Decimal,
    val mandateReference: String,
    val debtorIbanLast4: String,
    /** `true` -> amount increase versus this mandate's last collection -> full notice period (E-7). */
    val amountIncreased: Boolean,
)

@Serializable
data class SepaDebitBatchExclusionDto(
    val contributionId: String,
    val memberDisplayName: String,
    val reason: SepaDebitExclusionReason,
)

/**
 * Longest literal ALREADY_IN_FLIGHT (18).
 *
 * Review Round 2 (2026-08-20, MINOR): added MANDATE_EXPIRED -- Round 1's fix added the mandate
 * expiry re-check (36 months without use, see
 * `network.lapis.cloud.server.payment.sepa.SepaConfig.mandateExpiryDate`) to `createDebitBatch`
 * only, never mirrored it into `buildPreview` (the preview-only path also used by
 * `previewDebitBatch`), so a treasurer previewing N members could create a batch and silently get
 * fewer than N with no indication which member/why for this specific case. See
 * `SepaService.buildPreview`'s `else` branch.
 */
@Serializable
enum class SepaDebitExclusionReason {
    NO_ACTIVE_MANDATE,
    ALREADY_IN_FLIGHT,
    MEMBER_NOT_ACTIVE,
    AMOUNT_NOT_POSITIVE,
    MANDATE_EXPIRED,
}

@Serializable
data class SepaReturnDto(
    val id: String,
    val debitItemId: String,
    val batchId: String,
    val contributionId: String,
    val memberDisplayName: String,
    val returnedAt: LocalDate,
    val reasonCode: SepaReturnReason,
    val reasonText: String?,
    val returnFee: Decimal?,
    val recordedByDisplayName: String,
    val recordedAt: LocalDateTime,
    /**
     * `true` iff the mandate this return's item was collected against is (now) `REVOKED`. Since
     * Review Round 1 (2026-08-19, M-6), `SepaService.recordReturn` revokes/excludes the mandate for
     * EVERY return reason code, not only `MD01`/`MD06`/`MD07` -- a non-mandate-problem code
     * (`AC01`/`AC04`/`AC06`/etc.) gets a different `revocationReason` text but the same resulting
     * `REVOKED` status, so this field is `true` for both classes; see that method's own KDoc for the
     * full rationale.
     */
    val mandateRevoked: Boolean,
)

@Serializable
data class SepaReturnInput(
    val debitItemId: String,
    val returnedAt: LocalDate,
    val reasonCode: SepaReturnReason,
    val reasonText: String? = null,
    val returnFee: Decimal? = null,
)

@Serializable
data class SepaDebitBatchInput(
    val requestedCollectionDate: LocalDate,
    /** Only contributions with `due_date <= dueOnOrBefore` are included. */
    val dueOnOrBefore: LocalDate,
    /** Optional: only this membership tier. `null` -> all. */
    val membershipTierId: String? = null,
)

/**
 * What a member must see about an upcoming direct debit -- the four legally required items of a
 * pre-notification (mandate reference, creditor id, amount, due date) plus context. Shown on the
 * member's own contribution screen.
 */
@Serializable
data class SepaPrenotificationDto(
    val batchId: String,
    val contributionId: String,
    val mandateReference: String,
    val creditorId: String,
    val creditorName: String,
    val amount: Decimal,
    val requestedCollectionDate: LocalDate,
    val notifiedAt: LocalDateTime,
    val debtorIbanLast4: String,
)

/** ADMIN-only. Adds the three new configuration values (plan D-4) to [SepaSettingsDto]. */
@Serializable
data class SepaCreditorSettingsDto(
    val sepaCreditorId: String?,
    val sepaCreditorName: String?,
    val sepaPrenotificationDays: Int,
    /** `true` iff both creditorId AND creditorName are set -> `generateBatchFile` can run at all. */
    val readyForFileGeneration: Boolean,
)

@Serializable
data class SepaCreditorSettingsInput(
    val sepaCreditorId: String?,
    val sepaCreditorName: String?,
    val sepaPrenotificationDays: Int,
)
