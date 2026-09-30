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
import network.lapis.cloud.shared.domain.MembershipTierOverviewDto

@RpcService
interface IContributionService {
    /**
     * All tiers, for every authenticated caller (member self-service pickers -- the relief request
     * form's target tier -- read it). Carries no member counts; see [listMembershipTierOverview].
     */
    suspend fun listMembershipTiers(): List<MembershipTierDto>

    /**
     * Welle V1.9.18. The tier administration's read model: every tier plus the number of ACTIVE
     * members per tier and the number of ACTIVE members without any tier. Role: Schatzmeister/Admin
     * (the counts reveal membership figures, so unlike [listMembershipTiers] this is NOT open to
     * plain members or BOARD). One aggregate query, never one query per tier.
     */
    suspend fun listMembershipTierOverview(): MembershipTierOverviewDto

    /**
     * Role: Schatzmeister/Admin. Writes one `MEMBERSHIP_TIER`/`CREATE` audit entry.
     *
     * Validation (server-authoritative, `MembershipTierRules`): the name is trimmed and whitespace-collapsed,
     * 1..100 characters, unique case-insensitively ([network.lapis.cloud.shared.rpc.MembershipTierNameTakenException]);
     * description at most 1000 characters; amount 0..100000.00 with at most two decimals; payment term 0..365 days.
     * Anything else invalid is a [network.lapis.cloud.shared.rpc.BadRequestException].
     */
    suspend fun createMembershipTier(input: MembershipTierInput): MembershipTierDto

    /**
     * Role: Schatzmeister/Admin. Same validation as [createMembershipTier]. Writes one
     * `MEMBERSHIP_TIER`/`UPDATE` audit entry with a before/after snapshot (no entry when nothing
     * changed). The billing interval cannot be changed while any member is assigned to the tier
     * ([network.lapis.cloud.shared.rpc.MembershipTierIntervalLockedException]). Changing the amount
     * never touches an already-generated contribution.
     */
    suspend fun updateMembershipTier(
        id: String,
        input: MembershipTierInput,
    ): MembershipTierDto

    /**
     * Generates OPEN [ContributionDto] rows for every active member of the given tier for the
     * given period. Idempotent: a member+period combination that already has a contribution row
     * is skipped rather than duplicated. Role: Schatzmeister/Admin. Returns the number of rows
     * newly created (not the number of members considered). A tier with a contribution amount of 0
     * generates nothing (a free tier is never invoiced); a closed tier (`active = false`) keeps
     * generating -- closing only stops NEW assignments. `periodStart` after `periodEnd` is a
     * [network.lapis.cloud.shared.rpc.BadRequestException].
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
