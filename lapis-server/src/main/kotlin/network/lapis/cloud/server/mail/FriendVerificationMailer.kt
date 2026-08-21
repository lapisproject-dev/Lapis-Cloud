package network.lapis.cloud.server.mail

import network.lapis.cloud.shared.domain.DeliveryStatus

/**
 * Abstraction over "send this email-verification link/token to this email" (V0.11.0 FRIEND self-
 * registration). Same swap-seam shape [PasswordResetMailer] already establishes.
 *
 * **V1.2.3: real SMTP transport, optional** -- see [PasswordResetMailer] KDoc "real SMTP
 * transport, optional" for the full story; [SmtpFriendVerificationMailer] is the sole
 * implementation now, delegating to the SAME [MailDispatcher]/[MailTransport] instance
 * [SmtpPasswordResetMailer] uses (one transport, two thin adapters). The token-generation/
 * storage/consumption mechanics themselves have always been fully real -- see
 * [network.lapis.cloud.server.security.FriendEmailVerificationTokenStore]. This is exactly why
 * email-verification ENFORCEMENT still stays behind `LAPIS_FRIEND_REQUIRE_EMAIL_VERIFICATION`
 * (default `false`, see `RegistrationService` KDoc "Email verification"): the client-side deep-link
 * screen a verification mail now points to (`#/verify-email?token=...`) exists as of this wave,
 * but activating hard enforcement is a separate, later operational decision.
 */
interface FriendVerificationMailer {
    /**
     * Hands [rawToken] to [email] off for delivery -- see [PasswordResetMailer.send] KDoc
     * "Fire-and-forget" for the full contract, identical here. **Never logs [rawToken]** -- same
     * discipline [PasswordResetMailer.send] documents, for the same account-takeover-adjacent-
     * oracle reason.
     */
    fun send(
        email: String,
        rawToken: String,
    ): DeliveryStatus
}
