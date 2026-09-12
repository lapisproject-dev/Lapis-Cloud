package network.lapis.cloud.shared.domain

import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.serialization.Serializable

/**
 * Welle V1.4.10 "Beitragsvergünstigungen" -- the three request kinds a member may ask the board
 * for. Literal order load-bearing (`ContributionReliefSchemaDriftTest` pins it against
 * `44-contribution-relief.kuml.kts`'s `contributionReliefKind` enum) -- append only, never
 * reorder.
 */
@Serializable
enum class ContributionReliefKind { DEFERRAL, EXEMPTION, REDUCTION }

/**
 * Welle V1.4.10. Literal order load-bearing, same reason as [ContributionReliefKind]. See
 * [ContributionReliefStatusSets] for the one place a "which literals may do X" question about
 * these five is answered.
 */
@Serializable
enum class ContributionReliefStatus { REQUESTED, APPROVED, REJECTED, EXECUTED, WITHDRAWN }

/**
 * Welle V1.4.10. Satzungs-Tatbestände, KEINE Diagnosen -- Art.-9-Minimierung, siehe
 * `ContributionReliefService` KDoc "Art. 9 DSGVO". Literal order load-bearing, same reason as
 * [ContributionReliefKind].
 */
@Serializable
enum class ContributionReliefReason { FINANCIAL_HARDSHIP, UNEMPLOYMENT, STUDENT_TRAINEE, ILLNESS_DISABILITY, PARENTAL_CARE, OTHER }

/**
 * Welle V1.4.10. One `contribution_relief_request` row, wire DTO.
 *
 * **Flat, not a sealed `ReliefPayload` union**: this codebase has no precedent for a polymorphic
 * `@Serializable sealed interface` crossing the kilua-rpc wire (see
 * `network.lapis.cloud.shared.domain.DsgvoSubjectKind` KDoc -- the one sealed-interface-shaped
 * concept in this domain package is deliberately represented on the wire as a plain enum
 * discriminator, with the polymorphic type staying server-side only). Introducing one here would
 * be new, unverified surface for this wave to also carry. Instead this DTO mirrors
 * `chk_crr_payload_shape`'s own database-level shape: exactly the columns for [kind] are non-null,
 * all others `null` -- same discriminated-flat-row idiom every payload-shape CHECK constraint in
 * this codebase already encodes structurally.
 *
 * [reasonText] may be Art. 9 DSGVO special-category data -- see `ContributionReliefService` KDoc.
 * Server-side field-level redaction: `null` for any caller who is neither BOARD/ADMIN nor the
 * request's own subject, regardless of endpoint-level role gating.
 */
@Serializable
data class ContributionReliefRequestDto(
    val id: String,
    val subjectMemberId: String,
    val subjectDisplayName: String,
    val kind: ContributionReliefKind,
    val status: ContributionReliefStatus,
    val reasonCategory: ContributionReliefReason,
    val reasonText: String?,
    val reasonRedactedAt: LocalDateTime? = null,
    // DEFERRAL payload -- both set iff kind == DEFERRAL.
    val deferralContributionId: String? = null,
    val deferralNewDueDate: LocalDate? = null,
    /** Set only once [status] reaches [ContributionReliefStatus.EXECUTED] for a DEFERRAL. */
    val deferralPreviousDueDate: LocalDate? = null,
    // EXEMPTION payload -- exemptionFrom set iff kind == EXEMPTION; exemptionUntil optional (open-ended).
    val exemptionFrom: LocalDate? = null,
    val exemptionUntil: LocalDate? = null,
    // REDUCTION payload -- both set iff kind == REDUCTION.
    val reductionTargetTierId: String? = null,
    val reductionTargetTierName: String? = null,
    /** EXEMPTION/REDUCTION only -- optional Wiedervorlage-Datum, no automatic effect (see CHANGELOG "bewusste Grenze"). */
    val reviewDueOn: LocalDate? = null,
    val requestedAt: LocalDateTime,
    val requestedBy: String,
    val requestedByDisplayName: String,
    val decidedBy: String? = null,
    val decidedByDisplayName: String? = null,
    val decidedAt: LocalDateTime? = null,
    val decisionNote: String? = null,
    val executedAt: LocalDateTime? = null,
    /** Non-null iff [status] == [ContributionReliefStatus.APPROVED] and execution failed its under-lock state recheck. */
    val executionError: String? = null,
    /** Human-readable "what this request does" line, computed server-side -- never part of the state machine. */
    val effectDescription: String? = null,
)

/**
 * Welle V1.4.10. Input bundle for [network.lapis.cloud.shared.rpc.IContributionReliefService
 * .requestRelief] -- kilua-rpc's generated `bind` overloads only go up to 6 reified type
 * parameters (see `network.lapis.cloud.shared.domain.AuditLogListQuery` KDoc for the precedent),
 * and unpacking [ContributionReliefKind] + [ContributionReliefReason] + optional text + three
 * mutually-exclusive payload shapes as positional parameters would both exceed that ceiling and
 * read far worse at the call site than this one bundle. Exactly the columns for [kind] must be
 * non-null -- validated server-side, see `ContributionReliefService.requestRelief` KDoc.
 */
@Serializable
data class ContributionReliefRequestInput(
    val kind: ContributionReliefKind,
    val reasonCategory: ContributionReliefReason,
    val reasonText: String? = null,
    val deferralContributionId: String? = null,
    val deferralNewDueDate: LocalDate? = null,
    val exemptionFrom: LocalDate? = null,
    val exemptionUntil: LocalDate? = null,
    val reductionTargetTierId: String? = null,
    val reviewDueOn: LocalDate? = null,
)

/** Welle V1.4.10. Read-only projection of a member's current exemption state (`member.contribution_exempt_*`). */
@Serializable
data class ContributionExemptionStateDto(
    val memberId: String,
    val exemptFrom: LocalDate?,
    val exemptUntil: LocalDate?,
    val sourceRequestId: String?,
)

/**
 * Welle V1.4.10 -- the ONE place a "which [ContributionReliefStatus] literals may do X" question
 * about the *request* is answered, mirroring [ContributionStatusSets]'s own KDoc rationale
 * exactly. A separate object from [ContributionStatusSets] on purpose: that one partitions
 * [ContributionStatus] (the *contribution line*), this one partitions a structurally different
 * enum (the *relief request*) -- see the plan's K-5 finding for why these must not be merged.
 */
object ContributionReliefStatusSets {
    /** Endzustände -- kein Übergang führt heraus. */
    val TERMINAL: Set<ContributionReliefStatus> =
        setOf(ContributionReliefStatus.REJECTED, ContributionReliefStatus.EXECUTED, ContributionReliefStatus.WITHDRAWN)

    /** Blockiert einen zweiten Antrag derselben Art fuer dasselbe Mitglied -- siehe `active_request_key` (K-1). */
    val BLOCKS_NEW_REQUEST: Set<ContributionReliefStatus> =
        setOf(ContributionReliefStatus.REQUESTED, ContributionReliefStatus.APPROVED)
}

/**
 * Welle V1.4.10 -- the ONE place "is this member exempt from a contribution charge for this
 * period?" is answered, `MemberStatusSets`-Idiom: no boolean anywhere, no 17 call-site null-checks.
 * "Ganz-oder-gar-nicht": a period is exempt only if the exemption covers it FULLY (`from` on or
 * before [periodStart] AND (`until` is open-ended OR on/after [periodEnd])) -- no anteilige
 * (partial-period) exemption in this wave, see CHANGELOG "bewusste Grenze".
 */
object ContributionExemptionRules {
    fun isExemptForPeriod(
        from: LocalDate?,
        until: LocalDate?,
        periodStart: LocalDate,
        periodEnd: LocalDate,
    ): Boolean = from != null && from <= periodStart && (until == null || until >= periodEnd)
}

/**
 * Structured payload for a [AuditEntityType.CONTRIBUTION_RELIEF_REQUEST] audit entry (Welle
 * V1.4.10). **Never carries [ContributionReliefRequestDto.reasonText] or
 * [ContributionReliefRequestDto.decisionNote]** -- same PII-minimization discipline every other
 * snapshot in `AuditLog.kt` establishes for an append-only, hash-chained table: [reasonCategory]
 * is a satzungs-tatbestand classification (not free text), safe to retain unconditionally.
 *
 * [executionError] IS carried (Review fix, unlike [reasonText]/[decisionNote] above): it is a
 * short technical code (e.g. `contribution_not_deferrable:PAID`, see
 * `network.lapis.cloud.server.rpc.ContributionReliefExecution`'s `ReliefExecutionOutcome.Failed
 * .reason` call sites) never derived from member-authored free text -- carrying it is what makes a
 * repeated, unsuccessful [network.lapis.cloud.server.rpc.IContributionReliefService
 * .retryReliefExecution] audit entry distinguishable from a no-op: without it, `before`/`after`
 * were byte-identical (APPROVED -> APPROVED, this field the only thing that ever actually changes)
 * and a Kassenprüfer could not tell one retry attempt from another.
 */
@Serializable
data class ContributionReliefSnapshot(
    val requestId: String,
    val subjectMemberId: String,
    val kind: ContributionReliefKind,
    val status: ContributionReliefStatus,
    val reasonCategory: ContributionReliefReason,
    val previousDueDate: LocalDate? = null,
    val newDueDate: LocalDate? = null,
    val exemptFrom: LocalDate? = null,
    val exemptUntil: LocalDate? = null,
    val targetTierId: String? = null,
    val executionError: String? = null,
)
