package network.lapis.cloud.server.mail

import io.github.oshai.kotlinlogging.KotlinLogging
import network.lapis.cloud.shared.domain.DeliveryStatus

private val logger = KotlinLogging.logger {}

/**
 * Abstraction over "send this email-verification link/token to this email" (V0.11.0 FRIEND self-
 * registration). Same swap-seam shape [PasswordResetMailer] already establishes -- a real SMTP-
 * backed implementation can later replace [NoOpFriendVerificationMailer] without touching
 * `RegistrationService.registerFriend`'s call site.
 *
 * **Honest, disclosed non-delivery, same established precedent as [PasswordResetMailer]/
 * [network.lapis.cloud.server.rpc.MailingService.sendMailingMessage]**: this codebase has NO real
 * SMTP/email-transport integration anywhere. [NoOpFriendVerificationMailer] follows the exact same
 * convention rather than silently claiming a working delivery. The token-generation/storage/
 * consumption mechanics themselves ARE fully real -- see
 * [network.lapis.cloud.server.security.FriendEmailVerificationTokenStore] -- only the email
 * TRANSPORT is a disclosed stub. This is exactly why email-verification ENFORCEMENT stays behind
 * `LAPIS_FRIEND_REQUIRE_EMAIL_VERIFICATION` (default `false`, see `RegistrationService` KDoc "Email
 * verification"): hard-requiring verification while no real delivery exists would make FRIEND
 * unusable and would violate this project's own explicit no-overclaiming norm.
 */
interface FriendVerificationMailer {
    /**
     * Attempts to deliver [rawToken] to [email] as an email-verification link/token. Synchronous:
     * returns once the attempt has completed (or been simulated).
     */
    fun send(
        email: String,
        rawToken: String,
    ): DeliveryStatus
}

/**
 * See [FriendVerificationMailer] KDoc "Honest, disclosed non-delivery" for the full rationale.
 * **Never logs [rawToken]** -- logging the raw, bearer-usable verification token would defeat the
 * entire hash-only-persisted security model
 * [network.lapis.cloud.server.security.FriendEmailVerificationTokenStore] establishes (a leaked/
 * misconfigured log sink would become an account-takeover-adjacent oracle).
 */
class NoOpFriendVerificationMailer : FriendVerificationMailer {
    override fun send(
        email: String,
        rawToken: String,
    ): DeliveryStatus {
        logger.info {
            "Friend email-verification message would be sent to $email (no real SMTP transport configured -- see FriendVerificationMailer KDoc)"
        }
        return DeliveryStatus.SENT
    }
}
