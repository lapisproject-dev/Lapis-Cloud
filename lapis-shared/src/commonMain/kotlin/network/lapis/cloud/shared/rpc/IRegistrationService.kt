package network.lapis.cloud.shared.rpc

import dev.kilua.rpc.annotations.RpcService
import network.lapis.cloud.shared.domain.AdminCreateMemberInput
import network.lapis.cloud.shared.domain.FriendRegistrationInput
import network.lapis.cloud.shared.domain.FriendTermsDto
import network.lapis.cloud.shared.domain.MemberDto
import network.lapis.cloud.shared.domain.MembershipAgreementDto
import network.lapis.cloud.shared.domain.RegistrationInput

/**
 * V0.7.2 Beitritts-/Registrierungs-Workflow -- the join/exit lifecycle `IAuthService` (V0.7.1)
 * explicitly deferred (see that interface's own "Scope-cuts" KDoc). See
 * `network.lapis.cloud.server.rpc.RegistrationService` KDoc for the concurrency-safety and
 * account-enumeration-hardening details.
 *
 * **Membership is a private-law contract** (Satzung + Beitrittsbedingungen), not a mere account
 * signup -- [registerApplication] therefore requires the registrant to explicitly echo back the
 * CURRENT, versioned+hashed [MembershipAgreementDto] (see [getMembershipAgreement]), same
 * mechanism `IAuctionService.enableAuction`/`AuctionComplianceAcknowledgmentInput` already
 * establish for a different legal-acknowledgment need.
 *
 * **Silence-is-approval does NOT apply to membership admission**, unlike
 * `ICrowdfundingService.submitProject`'s 14-day auto-approval-by-silence clock -- a board
 * admission decision is a more consequential, harder-to-undo act than approving a crowdfunding
 * project, so [approveApplication]/[rejectApplication] always require an explicit board decision,
 * with no auto-approval fallback. See `RegistrationService` KDoc for the full reasoning.
 *
 * **[registerApplication] and [getMembershipAgreement] are deliberately reachable without
 * authentication** -- they must work before any session exists (a prospective member has no
 * account yet), not the "differently-shaped payload" reason login/logout live outside the RPC
 * layer entirely (see `IAuthService` KDoc) -- registration's payload is small and RPC-shaped, so
 * it stays a normal `@RpcService` method, just one that never calls `resolveCurrentMember` first.
 * (Historically `IMemberService.listMembers` was cited alongside these two as sharing this shape;
 * since V1.2.11 that method requires an authenticated caller -- see its own KDoc -- so it is no
 * longer part of this comparison.)
 */
@RpcService
interface IRegistrationService {
    /**
     * Unauthenticated. The CURRENT, versioned+hashed Beitrittsvertrag/Satzungs-text a registrant
     * must be shown before calling [registerApplication] -- same shape as
     * `IAuctionService.getAuctionComplianceDisclaimer`, but reachable with no session at all
     * (registration happens BEFORE any member/account exists).
     */
    suspend fun getMembershipAgreement(): MembershipAgreementDto

    /**
     * Unauthenticated. Creates a `Member(status=APPLICATION)` + `Account` (password set) pair after
     * verifying [RegistrationInput.agreementVersion]/[RegistrationInput.agreementSha256] match
     * the CURRENT [MembershipAgreementDto] (constant-time hash compare) -- throws a conflict if
     * they don't. [RegistrationInput.password] must satisfy
     * `network.lapis.cloud.server.security.PasswordPolicy`.
     *
     * Returns `Unit` unconditionally, including when [RegistrationInput.email] already belongs to
     * an existing member -- no row is created in that case, and the IDENTICAL response is
     * returned either way (account-enumeration hardening, same posture
     * `network.lapis.cloud.server.routes.registerAuthRoutes`'s login endpoint already applies to
     * its own domain; whether a given email is already a member of this organization can itself
     * be sensitive, see `OrganizationSettings.isPoliticalParty`). Rate-limited by IP and by
     * normalized email, same mechanism `network.lapis.cloud.server.security.LoginRateLimiter`
     * already provides for login.
     */
    suspend fun registerApplication(input: RegistrationInput)

    /** Role: BOARD/ADMIN. Every currently-[network.lapis.cloud.shared.domain.MemberStatus.APPLICATION] applicant, oldest first. */
    suspend fun listPendingApplications(): List<MemberDto>

    /**
     * Role: BOARD/ADMIN. `APPLICATION -> ACTIVE`. Row-locked + compare-and-swap -- see
     * `RegistrationService` KDoc for the concurrency contract this shares with
     * `ICrowdfundingService.approveProject`. Throws a conflict if [memberId] is not currently
     * `APPLICATION` (already decided by a concurrent caller, or never was an application).
     */
    suspend fun approveApplication(memberId: String): MemberDto

    /**
     * Role: BOARD/ADMIN. `APPLICATION -> REJECTED` -- EXCEPT for a FRIEND-originated application
     * (one that reached `APPLICATION` via [applyForMembership]), which falls back to `FRIEND`
     * instead of the terminal `REJECTED`, so a declined membership application never destroys the
     * underlying friend account (see [applyForMembership] KDoc). [reason] must be non-blank (a
     * documented rejection reason, retained on the member row -- never a silent/private rejection,
     * same discipline `ICrowdfundingService.rejectProject` already applies). Same locking/failure
     * conditions as [approveApplication].
     */
    suspend fun rejectApplication(
        memberId: String,
        reason: String,
    ): MemberDto

    /**
     * Role: BOARD/ADMIN to create a `role=MEMBER` account; creating an ESCALATED role
     * (BOARD/TREASURER/ADMIN) additionally requires the caller to be ADMIN specifically -- same
     * ADMIN_ONLY-vs-BOARD_ONLY distinction
     * `network.lapis.cloud.server.security.canAccessDocumentAtLevel` already makes for
     * `DocumentAccessLevel.ADMIN_ONLY`, applied here to close an obvious privilege-escalation path
     * (a BOARD account minting a new ADMIN account). Creates `Member(status=ACTIVE)` + `Account`
     * directly, with NO [network.lapis.cloud.shared.domain.MemberStatus.APPLICATION]/approval step --
     * e.g. for members who joined on paper, or as part of a data migration. See
     * [AdminCreateMemberInput] KDoc for the initial-password design decision.
     */
    suspend fun createMemberDirect(input: AdminCreateMemberInput): MemberDto

    /**
     * Role: any authenticated member, self-service only -- the CALLER's own `ACTIVE -> WITHDRAWN`
     * ("Austritt"). No board approval needed for exit, only for entry ("Eintritt und Austritt sind
     * ausschliesslich Willenserklaerungen der Vertragspartner", see [MembershipAgreementDto] /
     * `network.lapis.cloud.shared.domain.MemberStatus` KDoc). Every one of the caller's live
     * sessions is revoked as part of this call (not just OTHER sessions, unlike
     * `IAuthService.changePassword`) -- a WITHDRAWN member must not remain logged in. Throws a
     * conflict if the caller is not currently `ACTIVE` (already left, was never approved, or was
     * rejected).
     */
    suspend fun leaveMembership(): MemberDto

    /**
     * Unauthenticated. The CURRENT, versioned+hashed FRIEND terms of use -- must be echoed back by
     * [registerFriend]. Same shape as [getMembershipAgreement], different document (see
     * [FriendTermsDto] KDoc for why FRIEND does not use [MembershipAgreementDto]).
     */
    suspend fun getFriendTerms(): FriendTermsDto

    /**
     * Unauthenticated. Creates a `Member(status = FRIEND, friendSince = today)` + `Account`
     * (role = MEMBER, password set) pair -- NO board approval step, NO identity verification of
     * [FriendRegistrationInput.displayName]. Returns `Unit` unconditionally, including when the
     * email already belongs to an existing member (account-enumeration hardening, identical
     * response either way -- same posture as [registerApplication]).
     *
     * Rate-limited by IP and by normalized email on its OWN limiter instance, separate from
     * [registerApplication]'s: friend-signup spam must never exhaust the membership-application
     * budget (or vice versa). See `network.lapis.cloud.server.rpc.RegistrationService` KDoc for the
     * concrete limiter wiring.
     */
    suspend fun registerFriend(input: FriendRegistrationInput)

    /**
     * Role: the CALLER's own `FRIEND -> APPLICATION` upgrade -- the regular Beitritts route,
     * entered from an existing friend account instead of from nothing. Requires the same
     * versioned+hashed [MembershipAgreementDto] echo-back [registerApplication] does (a FRIEND has
     * never accepted the Satzung; accepting it is the whole point of this call) and writes the same
     * `membership_agreement_acknowledgment` row.
     *
     * Preserves member id, account, credentials and every live session -- there is deliberately NO
     * second account. `friendSince` is retained, but this does NOT keep conference access alive
     * while the application is pending: `status` flips to [MemberStatus.APPLICATION], which is
     * outside [network.lapis.cloud.shared.domain.MemberStatusSets.CONFERENCE_ELIGIBLE] (that set
     * checks the CURRENT status only), so conference access is lost for the duration of the
     * application exactly like any other applicant's. Retaining `friendSince` has exactly one job
     * here: it is what makes a later [rejectApplication] fall back to `FRIEND` rather than
     * `REJECTED`, so a declined application does not also destroy the underlying friend account.
     *
     * Row-locked + compare-and-swap on `status = FRIEND`, same concurrency contract as
     * [approveApplication]. Throws a conflict if the caller is not currently `FRIEND`.
     */
    suspend fun applyForMembership(
        agreementVersion: String,
        agreementSha256: String,
    ): MemberDto
}
