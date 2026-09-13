package network.lapis.cloud.shared.rpc

import dev.kilua.rpc.annotations.RpcService
import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.shared.domain.VolunteerAllowanceCapAcknowledgmentInput
import network.lapis.cloud.shared.domain.VolunteerAllowanceCapDisclaimerDto
import network.lapis.cloud.shared.domain.VolunteerAllowanceCategory
import network.lapis.cloud.shared.domain.VolunteerAllowanceConfigDto
import network.lapis.cloud.shared.domain.VolunteerAllowancePaymentDto
import network.lapis.cloud.shared.domain.VolunteerAllowancePaymentInput
import network.lapis.cloud.shared.domain.VolunteerAllowancePaymentStatus
import network.lapis.cloud.shared.domain.VolunteerAllowanceSelfDeclarationDto
import network.lapis.cloud.shared.domain.VolunteerAllowanceSelfDeclarationInput
import network.lapis.cloud.shared.domain.VolunteerAllowanceYearStatusDto

/**
 * Welle V1.4.12 "Übungsleiter- und Ehrenamtspauschale" (§3 Nr. 26 / 26a EStG). See
 * `network.lapis.cloud.server.rpc.VolunteerAllowanceService` KDoc for the full state machine and
 * `docs/architecture/volunteer-allowance.adoc` for the fachlich rationale.
 */
@RpcService
interface IVolunteerAllowanceService {
    /** Every authenticated member -- the self-service form needs the caps without the TREASURER/BOARD/ADMIN-gated
     *  `getOrganizationSettings()`, same tier `getTravelExpenseRates` already establishes. */
    suspend fun getVolunteerAllowanceConfig(): VolunteerAllowanceConfigDto

    /** The current board cap-acknowledgment disclaimer -- version/text/hash, see `VolunteerAllowanceCapDisclaimer`. */
    suspend fun getCapDisclaimer(): VolunteerAllowanceCapDisclaimerDto

    /**
     * LIVE annual status for [memberId]/[category]/[year]. Self: every member, for themselves.
     * Someone else's: BOARD/ADMIN only (IDOR gate). [VolunteerAllowanceYearStatusDto
     * .remainingInThisOrganization], NEVER a global remaining amount -- see that field's own KDoc.
     */
    suspend fun getYearStatus(
        memberId: String,
        category: VolunteerAllowanceCategory,
        year: Int,
    ): VolunteerAllowanceYearStatusDto

    /**
     * The receiving member's own self-declaration (`source = IN_APP`) -- only ever for themselves,
     * `recordedBy` is server-set to the caller, never accepted from the client (Norman's forgery
     * scenario). One per (member, category, calendarYear), enforced by `uq_vasd_member_category_year`.
     */
    suspend fun declareSelf(
        category: VolunteerAllowanceCategory,
        calendarYear: Int,
    ): VolunteerAllowanceSelfDeclarationDto

    /**
     * Role: BOARD/ADMIN. Records a signed paper declaration (`source = ON_PAPER`) on behalf of a
     * member who declared on paper rather than in the app -- `recordedBy` (the caller) must differ
     * from [VolunteerAllowanceSelfDeclarationInput.memberId] and [VolunteerAllowanceSelfDeclarationInput.signedOn]
     * is mandatory (`chk_vasd_source_shape`). **Never a Disclaimer** -- see [VolunteerAllowanceSelfDeclarationInput]
     * class-wide framing: the board confirms nothing about itself here, the PERSON declares something about themselves.
     */
    suspend fun recordPaperDeclaration(input: VolunteerAllowanceSelfDeclarationInput): VolunteerAllowanceSelfDeclarationDto

    /** Role: BOARD/ADMIN when [memberId] is supplied for someone else; every member may query their own. */
    suspend fun listDeclarations(
        memberId: String? = null,
        calendarYear: Int? = null,
    ): List<VolunteerAllowanceSelfDeclarationDto>

    /**
     * Role: **ADMIN only** (stricter than the BOARD/ADMIN tier the rest of this interface uses --
     * this corrects a mistake in a legally-relevant record another BOARD/ADMIN member recorded,
     * not a routine decision). Security-Fund (INFORMATIONAL: "keine Korrektur-/
     * Widerrufsmoeglichkeit fuer eine falsch oder missbraeuchlich erfasste
     * Papier-Selbstauskunft"). Only ever [VolunteerAllowanceSelfDeclarationDto.source] `ON_PAPER`
     * -- the person's OWN `IN_APP` declaration is never voidable through this endpoint (that would
     * be a DIFFERENT, much more sensitive capability: a board member erasing something the SUBJECT
     * themselves asserted) -> [ConflictException] otherwise. [NotFoundException] if
     * [declarationId] does not exist. HARD-deletes the row (there is no soft-delete/void column --
     * see `AuditAction.VOID`'s own KDoc for why) and always returns `true` on success, same
     * "Boolean return, exceptions carry every failure" shape [IConferenceRecordingService
     * .deleteRecording] already establishes. Frees the subject to submit their own `declareSelf`
     * for the same category/calendarYear afterward (`uq_vasd_member_category_year` no longer
     * blocks it once the erroneous row is gone).
     */
    suspend fun voidPaperDeclaration(declarationId: String): Boolean

    /**
     * Self-service. MEMBER only for themselves (IDOR gate); BOARD/ADMIN also on behalf of another
     * member. Cap: [network.lapis.cloud.shared.domain.VolunteerAllowanceRules.MAX_OPEN_PER_MEMBER]
     * open payments per subject -> [ConflictException].
     */
    suspend fun createDraft(
        subjectMemberId: String,
        input: VolunteerAllowancePaymentInput,
    ): VolunteerAllowancePaymentDto

    /** DRAFT only, subject or requester. [VolunteerAllowancePaymentInput.category] must match the stored one -- UNCHANGEABLE. */
    suspend fun updateDraft(
        paymentId: String,
        input: VolunteerAllowancePaymentInput,
    ): VolunteerAllowancePaymentDto

    /** DRAFT -> REQUESTED. Validates formal shape ONLY -- the annual cap is checked at decision time, not here (Duarte-Ruling). */
    suspend fun submitPayment(paymentId: String): VolunteerAllowancePaymentDto

    /** Subject or requester, NO BOARD/ADMIN bypass. DRAFT/REQUESTED -> WITHDRAWN only. */
    suspend fun withdrawPayment(paymentId: String): VolunteerAllowancePaymentDto

    /** Every authenticated caller, exclusively their own payments (subject OR requester), including DRAFT. */
    suspend fun listMyPayments(): List<VolunteerAllowancePaymentDto>

    /**
     * Role: BOARD/ADMIN -- NOT TREASURER (a discretionary judgment call, not a mechanical accounting
     * fact). DRAFT is NEVER visible here. Keyset pagination via [afterSubmittedAt]/[afterId].
     */
    suspend fun listPayments(
        status: VolunteerAllowancePaymentStatus? = null,
        afterSubmittedAt: LocalDateTime? = null,
        afterId: String? = null,
    ): List<VolunteerAllowancePaymentDto>

    /**
     * Role: BOARD/ADMIN. [note] is REQUIRED for both `approve=true` and `approve=false`. Four-eyes,
     * without exception: `decidedBy` in `{subjectMemberId, requestedBy}` -> [ForbiddenException].
     * [capAcknowledgment] is REQUIRED exactly when the under-lock recheck yields `EXCEEDS_CAP`, and
     * FORBIDDEN otherwise (no pre-emptive sending) -- see `VolunteerAllowanceService.decidePayment` KDoc.
     */
    suspend fun decidePayment(
        paymentId: String,
        approve: Boolean,
        note: String? = null,
        capAcknowledgment: VolunteerAllowanceCapAcknowledgmentInput? = null,
    ): VolunteerAllowancePaymentDto

    /** Role: BOARD/ADMIN. Only from APPROVED with a failed booking. Same four-eyes exclusion as [decidePayment] (the V1.4.11 finding). */
    suspend fun retryPosting(paymentId: String): VolunteerAllowancePaymentDto
}
