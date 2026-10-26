package network.lapis.cloud.shared.domain

import dev.kilua.rpc.types.Decimal
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.serialization.Serializable

/**
 * Persisted lifecycle state of a `crowdfunding_project` row. See
 * [CrowdfundingProjectDto.effectiveStatus] for the DISTINCT, non-persisted "as of now, has
 * silence-is-approval already kicked in" view -- `status` itself only ever changes via an
 * explicit board `approveProject`/`rejectProject` call, never a background process (this
 * codebase has no scheduler infrastructure, see `17-crowdfunding.kuml.kts` file header).
 */
@Serializable
enum class CrowdfundingProjectStatus { PENDING, APPROVED, REJECTED }

/** One member's Like/Dislike on one project -- the democratic, LTR-unweighted "Verteilungs-Korb" input. See `17-crowdfunding.kuml.kts` file header point 2. */
@Serializable
enum class CrowdfundingReactionValue { LIKE, DISLIKE }

/**
 * A Crowdfunding project. [initialWeightLtr] is the immutable, submission-time LTR stake bound
 * from the submitter's free balance; [currentWeightLtr] is the SAME value decayed 10%/day as of
 * "now" (computed on every read, never persisted -- see
 * `network.lapis.cloud.server.rpc.CrowdfundingWeightDecay`). [status] is the persisted board
 * decision (or [CrowdfundingProjectStatus.PENDING] if none has been made yet);
 * [effectiveStatus]/[isAutoApproved] additionally reflect the 14-day silence-is-approval rule
 * WITHOUT that ever being written back to [status] -- a project can show
 * `status == PENDING, effectiveStatus == APPROVED, isAutoApproved == true` indefinitely, and
 * that combination is the normal, expected shape for a project the board never explicitly acted
 * on. Donations ([likeCount]/[dislikeCount]/[basketTotal]) are only meaningful once
 * [effectiveStatus] is [CrowdfundingProjectStatus.APPROVED] -- see
 * `network.lapis.cloud.shared.rpc.ICrowdfundingService.castReaction` KDoc.
 */
@Serializable
data class CrowdfundingProjectDto(
    val id: String,
    val title: String,
    val description: String,
    val submitterMemberId: String,
    val submitterDisplayName: String,
    val initialWeightLtr: Decimal,
    val currentWeightLtr: Decimal,
    val status: CrowdfundingProjectStatus,
    val effectiveStatus: CrowdfundingProjectStatus,
    val isAutoApproved: Boolean,
    val rejectionReason: String?,
    val reviewedById: String?,
    val reviewedByDisplayName: String?,
    val reviewedAt: LocalDateTime?,
    val submittedAt: LocalDateTime,
    val likeCount: Int,
    val dislikeCount: Int,
    val basketTotal: Int,
    /** V1.9.222: delivery URL of the cover image (gallery position 0), or `null` when the project has no pictures. */
    val coverImageUrl: String? = null,
)

/** [initialWeightLtr] must be >= the current, decayed weight of the top existing project (the entry hurdle) and <= the submitter's free LTR balance. */
@Serializable
data class CrowdfundingProjectInput(
    val title: String,
    val description: String,
    val initialWeightLtr: Decimal,
)

@Serializable
data class CrowdfundingReactionDto(
    val id: String,
    val projectId: String,
    val memberId: String,
    val memberDisplayName: String,
    val value: CrowdfundingReactionValue,
    val castAt: LocalDateTime,
)

/**
 * One monthly proportional EUR-pool allocation to one project -- a decision/audit record only,
 * NOT a payment instruction. See
 * `network.lapis.cloud.shared.rpc.ICrowdfundingService.computeMonthlyDistribution` KDoc for the
 * explicit scope cut (no `JournalEntry`/bank transfer is produced by this wave).
 */
@Serializable
data class CrowdfundingDistributionDto(
    val id: String,
    val projectId: String,
    val projectTitle: String,
    val periodStart: LocalDate,
    val periodEnd: LocalDate,
    val basketTotalAtDistribution: Int,
    val amountEur: Decimal,
    val computedAt: LocalDateTime,
    val triggeredById: String,
    val triggeredByDisplayName: String,
)
