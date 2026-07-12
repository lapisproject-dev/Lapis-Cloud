package network.lapis.cloud.shared.rpc

import dev.kilua.rpc.annotations.RpcService
import dev.kilua.rpc.types.Decimal
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.shared.domain.ContributionDto
import network.lapis.cloud.shared.domain.ContributionStatus
import network.lapis.cloud.shared.domain.MemberContributionSummaryDto
import network.lapis.cloud.shared.domain.MembershipTierDto
import network.lapis.cloud.shared.domain.MembershipTierInput

@RpcService
interface IContributionService {
    suspend fun listMembershipTiers(): List<MembershipTierDto>

    /** Role: Schatzmeister/Admin. */
    suspend fun createMembershipTier(input: MembershipTierInput): MembershipTierDto

    /** Role: Schatzmeister/Admin. */
    suspend fun updateMembershipTier(
        id: String,
        input: MembershipTierInput,
    ): MembershipTierDto

    /**
     * Generates OPEN [ContributionDto] rows for every active member of the given tier for the
     * given period. Idempotent: a member+period combination that already has a contribution row
     * is skipped rather than duplicated. Role: Schatzmeister/Admin. Returns the number of rows
     * newly created (not the number of members considered).
     */
    suspend fun generateContributionsForPeriod(
        membershipTierId: String,
        periodStart: LocalDate,
        periodEnd: LocalDate,
    ): Int

    /**
     * Members may only ever see their own contributions — callers other than
     * Schatzmeister/Admin/Board get [memberId] silently forced to their own id server-side.
     */
    suspend fun listContributions(
        memberId: String? = null,
        status: ContributionStatus? = null,
        periodFrom: LocalDate? = null,
        periodTo: LocalDate? = null,
    ): List<ContributionDto>

    /** Role: Schatzmeister/Admin. */
    suspend fun markContributionPaid(
        contributionId: String,
        paidAt: LocalDateTime,
        paidAmount: Decimal,
        note: String? = null,
    ): ContributionDto

    /** Role: Board/Admin. */
    suspend fun markContributionWaived(
        contributionId: String,
        note: String? = null,
    ): ContributionDto

    /** Members may only request their own summary unless Schatzmeister/Admin/Board. */
    suspend fun getMemberContributionSummary(memberId: String): MemberContributionSummaryDto
}
