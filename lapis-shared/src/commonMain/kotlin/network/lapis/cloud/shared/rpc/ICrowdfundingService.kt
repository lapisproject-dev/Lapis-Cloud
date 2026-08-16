package network.lapis.cloud.shared.rpc

import dev.kilua.rpc.annotations.RpcService
import kotlinx.datetime.LocalDate
import network.lapis.cloud.shared.domain.CrowdfundingDistributionDto
import network.lapis.cloud.shared.domain.CrowdfundingProjectDto
import network.lapis.cloud.shared.domain.CrowdfundingProjectInput
import network.lapis.cloud.shared.domain.CrowdfundingProjectStatus
import network.lapis.cloud.shared.domain.CrowdfundingReactionDto
import network.lapis.cloud.shared.domain.CrowdfundingReactionValue

/**
 * Internes Crowdfunding (V0.6.1) -- see `17-crowdfunding.kuml.kts` file header for the full
 * fachlich model (Sichtbarkeits-Gewicht vs. Verteilungs-Korb, silence-is-approval,
 * submission-gate locking).
 */
@RpcService
interface ICrowdfundingService {
    /**
     * Role: MEMBER+ (any authenticated member, submitting for themselves). Binds
     * [CrowdfundingProjectInput.initialWeightLtr] from the caller's free LTR balance as a
     * [network.lapis.cloud.shared.domain.LtrLedgerEntryType.PROJECT_STAKE] ledger debit. Throws
     * a conflict if the stake exceeds the caller's free balance, or is below the current,
     * decayed weight of the top existing (non-[CrowdfundingProjectStatus.REJECTED]) project (the
     * entry hurdle). The new project is immediately visible (status
     * [CrowdfundingProjectStatus.PENDING]) but not yet open to donations -- see [castReaction].
     */
    suspend fun submitProject(input: CrowdfundingProjectInput): CrowdfundingProjectDto

    /** Any authenticated member. [statusFilter] filters on the PERSISTED `status`, not `effectiveStatus` -- pass null for every project regardless of board decision. */
    suspend fun listProjects(statusFilter: CrowdfundingProjectStatus? = null): List<CrowdfundingProjectDto>

    /** Any authenticated member. */
    suspend fun getProject(id: String): CrowdfundingProjectDto

    /** Role: BOARD/ADMIN. Fails if the project is no longer [CrowdfundingProjectStatus.PENDING], or if silence-is-approval already took effect (`effectiveStatus == APPROVED`). */
    suspend fun approveProject(id: String): CrowdfundingProjectDto

    /** Role: BOARD/ADMIN. [reason] must be non-blank (public, visible-on-the-project rejection reason -- no silent/private rejection). Same failure conditions as [approveProject]. */
    suspend fun rejectProject(
        id: String,
        reason: String,
    ): CrowdfundingProjectDto

    /**
     * Role: MEMBER+, caller must be [network.lapis.cloud.shared.domain.MemberStatus.ACTIVE] (for
     * themselves only) -- see `CrowdfundingService.castReaction` KDoc, ANTRAG membership-gate
     * audit round 1 (2026-07-30). One Like OR Dislike per member per project -- calling again with
     * a different [value] changes the existing reaction, it does not add a second one. Requires
     * the project's `effectiveStatus` to be [CrowdfundingProjectStatus.APPROVED] (donations are
     * not yet open on a still-PENDING or REJECTED project).
     */
    suspend fun castReaction(
        projectId: String,
        value: CrowdfundingReactionValue,
    ): CrowdfundingReactionDto

    /** Role: MEMBER+, caller must be ACTIVE (for themselves only) -- same gate as [castReaction]. No-op if the caller has no reaction on this project. */
    suspend fun retractReaction(projectId: String)

    /**
     * Any authenticated member. Returns an empty list if the caller has not reacted to this
     * project, otherwise a single-element list -- NOT a nullable `CrowdfundingReactionDto?`
     * return type. Verified empirically: the kilua-rpc JS client codegen cannot infer a type for
     * an RPC method returning a nullable custom serializable class (nullable *parameters* are
     * fine throughout this codebase, e.g. [listDistributions]'s `projectId`; only a nullable
     * custom-DTO *return* type triggers the JS compile failure), so this 0-or-1-element list is
     * the RPC-safe way to express "optional lookup" here.
     */
    suspend fun getMyReaction(projectId: String): List<CrowdfundingReactionDto>

    /**
     * Role: TREASURER/BOARD/ADMIN. Computes the EUR donation pool for [periodStart]..[periodEnd]
     * (sum of `PAID` contributions in that window, minus a fixed per-payer platform minimum) and
     * splits it proportionally across every [CrowdfundingProjectStatus.APPROVED] (by
     * `effectiveStatus`) project's basket total (`max(0, likeCount - dislikeCount)`) via
     * largest-remainder apportionment. Idempotent: re-running with the identical period does not
     * duplicate rows (unique on project+period). Produces only an auditable decision/allocation
     * record -- no `JournalEntry`/bank transfer is created, see [CrowdfundingDistributionDto]
     * KDoc for the explicit scope cut.
     */
    suspend fun computeMonthlyDistribution(
        periodStart: LocalDate,
        periodEnd: LocalDate,
    ): List<CrowdfundingDistributionDto>

    /** Any authenticated member. [projectId] null lists every distribution across every project. */
    suspend fun listDistributions(projectId: String? = null): List<CrowdfundingDistributionDto>
}
