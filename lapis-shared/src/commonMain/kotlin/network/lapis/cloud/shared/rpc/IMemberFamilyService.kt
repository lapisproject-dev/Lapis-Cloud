package network.lapis.cloud.shared.rpc

import dev.kilua.rpc.annotations.RpcService
import network.lapis.cloud.shared.domain.AnniversaryCalendar
import network.lapis.cloud.shared.domain.MemberFamilyDetailDto
import network.lapis.cloud.shared.domain.MemberFamilyLimits
import network.lapis.cloud.shared.domain.MemberFamilyPageDto
import network.lapis.cloud.shared.domain.UpcomingMajorityOverviewDto

/**
 * Welle V1.4.4.4 "Mitgliederlebenszyklus: Familienmitgliedschaften" -- BOARD/ADMIN read/write
 * management surface for `member_family`/`member_family_link`, backed directly by
 * `network.lapis.cloud.server.rpc.MemberFamilyService` (no separate Store/Policy pair -- same
 * "single, simple table, no concurrency problem" reasoning [IMemberHonorService] KDoc already
 * gives for that entity's shape).
 *
 * **Rollen-Asymmetrie, bewusst**: every method here is BOARD/ADMIN; [deleteFamily] alone is
 * ADMIN-only -- same posture [IMemberHonorService.deleteHonor] already establishes.
 *
 * **This is NOT a payment/tier-assignment interface.** [addFamilyMember] nulls the new dependent's
 * tier as a documented SIDE EFFECT (billing-relevant fact must not simply vanish), but the only
 * way to ASSIGN a tier is `network.lapis.cloud.shared.rpc.IMemberService.updateMemberMembershipTier`
 * -- see that method's own KDoc for its own, stricter TREASURER/ADMIN-vs-BOARD role split (setting
 * a paying tier creates a payment obligation; this interface never does that).
 */
@RpcService
interface IMemberFamilyService {
    /** Role: BOARD/ADMIN. [limit] server-capped at [MemberFamilyLimits.MAX_LIMIT]. Payerless families sort first, then name, then id. */
    suspend fun listFamilies(
        search: String? = null,
        limit: Int = MemberFamilyLimits.DEFAULT_LIMIT,
        offset: Int = 0,
    ): MemberFamilyPageDto

    /** Role: BOARD/ADMIN. Throws [NotFoundException] if `id` does not resolve. */
    suspend fun getFamily(id: String): MemberFamilyDetailDto

    /**
     * Role: BOARD/ADMIN. Creates a family with exactly one PAYER link for [payerMemberId] -- the
     * payer keeps whatever tier they already have (never nulled). Throws [BadRequestException] if
     * [payerMemberId] does not resolve to an existing, non-anonymized member, [ConflictException]
     * if that member already belongs to a family.
     */
    suspend fun createFamily(
        name: String,
        payerMemberId: String,
    ): MemberFamilyDetailDto

    /** Role: BOARD/ADMIN. A pure name correction -- see interface KDoc "Plan-Ergänzung: renameFamily". */
    suspend fun renameFamily(
        id: String,
        name: String,
    ): MemberFamilyDetailDto

    /**
     * Role: BOARD/ADMIN. Adds [memberId] as a DEPENDENT to [familyId] and, in the SAME
     * transaction, NULLS the member's `membership_tier_id`
     * (`network.lapis.cloud.server.rpc.MembershipTierAssignment.apply`) -- a dependent is billed
     * through the family's payer, not directly. Throws [BadRequestException] if the target does
     * not resolve or is anonymized, [ConflictException] if the target already belongs to a family.
     */
    suspend fun addFamilyMember(
        familyId: String,
        memberId: String,
    ): MemberFamilyDetailDto

    /**
     * Role: BOARD/ADMIN. Removes the link identified by [linkId] and deliberately assigns NO
     * tier -- a removed dependent is left tier-less until a treasurer/admin makes a SEPARATE,
     * deliberate call to
     * `network.lapis.cloud.shared.rpc.IMemberService.updateMemberMembershipTier`. The family row
     * itself is never deleted or auto-cleaned here, even if this was its last link or its payer --
     * a resulting payerless/empty family stays visible in [listFamilies], never silently hidden.
     */
    suspend fun removeFamilyMember(linkId: String): MemberFamilyDetailDto

    /**
     * Role: BOARD/ADMIN. Reassigns the PAYER role within [familyId] to [newPayerMemberId] (which
     * must already be a link of this family, else [BadRequestException]). The OLD payer's link
     * becomes DEPENDENT (`payer_family_id` nulled) and their tier is nulled via
     * [network.lapis.cloud.server.rpc.MembershipTierAssignment]; the NEW payer's link becomes
     * PAYER but their tier is left untouched (whatever it already is) -- assigning one is a
     * separate, deliberate act via `IMemberService.updateMemberMembershipTier`. The old-payer
     * demotion is always applied BEFORE the new-payer promotion within the same transaction --
     * `uq_member_family_link_payer` (a plain, non-deferrable unique index) would otherwise reject
     * the insert/update of a second PAYER row before the first is cleared.
     */
    suspend fun changePayer(
        familyId: String,
        newPayerMemberId: String,
    ): MemberFamilyDetailDto

    /** Role: **ADMIN**. Hard-deletes the family and all its links (order: links, then family). Never touches any member's tier. */
    suspend fun deleteFamily(id: String)

    /**
     * Role: BOARD/ADMIN. Every DEPENDENT link whose member turns
     * [network.lapis.cloud.shared.domain.FamilyMembershipRules.MAJORITY_AGE_YEARS] within
     * [windowDays] of today -- PLUS every one that is ALREADY past that age, regardless of
     * [windowDays] (see [network.lapis.cloud.shared.domain.UpcomingMajorityEntryDto.alreadyMajor]
     * KDoc for why an overdue entry must never be able to silently age out of this list). Throws
     * [BadRequestException] if [windowDays] is outside `1..`[AnniversaryCalendar.MAX_WINDOW_DAYS].
     */
    suspend fun listUpcomingMajorities(windowDays: Int = AnniversaryCalendar.MAX_WINDOW_DAYS): UpcomingMajorityOverviewDto
}
