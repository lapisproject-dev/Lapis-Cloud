package network.lapis.cloud.shared.domain

import kotlinx.serialization.Serializable

/**
 * The current, versioned+hashed Beitrittsvertrag/Satzungs-text a registrant must echo back
 * (unmodified) to [network.lapis.cloud.shared.rpc.IRegistrationService.registerApplication] --
 * same shape/mechanism as `AuctionComplianceDisclaimerDto`
 * ([network.lapis.cloud.shared.rpc.IAuctionService.getAuctionComplianceDisclaimer]). Membership in
 * this codebase's own concept is a private-law contract (Satzung + Beitrittsbedingungen), not a
 * mere account signup -- see `network.lapis.cloud.server.rpc.MembershipAgreementDisclaimer` KDoc
 * for the full legal-verification-disclaimer framing.
 */
@Serializable
data class MembershipAgreementDto(
    val version: String,
    val text: String,
    val sha256: String,
)

/**
 * Self-registration input for [network.lapis.cloud.shared.rpc.IRegistrationService.registerApplication].
 * [agreementVersion]/[agreementSha256] must match [MembershipAgreementDto.version]/
 * [MembershipAgreementDto.sha256] exactly (server re-verifies both, constant-time hash
 * comparison) -- same acknowledgment-proof shape as `AuctionComplianceAcknowledgmentInput`.
 *
 * Deliberately omits postal-address fields -- [network.lapis.cloud.shared.rpc.IMemberService.updateMemberAddress]
 * is already the one production write path for those and is reachable once the applicant is
 * created, avoiding a second, duplicate address-write path here.
 */
@Serializable
data class RegistrationInput(
    val displayName: String,
    val email: String,
    val password: String,
    val agreementVersion: String,
    val agreementSha256: String,
)

/**
 * Input for [network.lapis.cloud.shared.rpc.IRegistrationService.createMemberDirect] -- a
 * BOARD/ADMIN-created member that starts at [MemberStatus.ACTIVE] immediately (no
 * [MemberStatus.APPLICATION]/approval step), e.g. for members who joined on paper, or as part of a
 * data migration. [temporaryPassword] is set directly by the creating BOARD/ADMIN account and
 * must satisfy `network.lapis.cloud.server.security.PasswordPolicy` -- the new member can change
 * it themselves afterward via `IAuthService.changePassword`; this wave does not add a
 * forced-change-on-first-login mechanism (no such flag exists in this codebase yet, see
 * `RegistrationService` KDoc "Known limitations").
 */
@Serializable
data class AdminCreateMemberInput(
    val displayName: String,
    val email: String,
    val role: AccountRole,
    val temporaryPassword: String,
)

/**
 * V0.11.0 -- the FRIEND terms of use, echoed back (unmodified) to
 * [network.lapis.cloud.shared.rpc.IRegistrationService.registerFriend]. Deliberately NOT
 * [MembershipAgreementDto]: a FRIEND does not enter the Satzung/Beitritts contract -- it is a
 * self-registered, non-membership account scoped to video-conference access only (see
 * [MemberStatus] KDoc). Same versioned+hashed echo-back mechanism as [MembershipAgreementDto].
 */
@Serializable
data class FriendTermsDto(
    val version: String,
    val text: String,
    val sha256: String,
)

/**
 * Self-registration input for [network.lapis.cloud.shared.rpc.IRegistrationService.registerFriend].
 * [termsVersion]/[termsSha256] must match [FriendTermsDto.version]/[FriendTermsDto.sha256] exactly
 * (server re-verifies both, constant-time hash comparison). [displayName] is deliberately
 * UNVERIFIED -- no identity check is performed, see [MemberStatus.FRIEND] KDoc.
 */
@Serializable
data class FriendRegistrationInput(
    val displayName: String,
    val email: String,
    val password: String,
    val termsVersion: String,
    val termsSha256: String,
)
