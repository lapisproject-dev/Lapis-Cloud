package network.lapis.cloud.server.mail

import network.lapis.cloud.shared.domain.DeliveryStatus

/**
 * Abstraction over "send this password-reset link/token to this email" (V0.7.2 Beitritts-/
 * Registrierungs-Workflow). Same swap-seam shape
 * [network.lapis.cloud.server.postal.PostalMailProvider] already establishes for a different
 * outbound-delivery need.
 *
 * **V1.2.3: real SMTP transport, optional.** [SmtpPasswordResetMailer] is the only implementation
 * in production use now -- it delegates to [MailDispatcher]/[MailTransport], which itself falls
 * back to [NoOpMailTransport] (honest, disclosed non-delivery, logged only) whenever
 * `LAPIS_SMTP_*` is unset. See [SmtpConfig] KDoc for the full opt-in story. The token-generation/
 * storage/consumption mechanics themselves have always been fully real -- see
 * [network.lapis.cloud.server.security.PasswordResetTokenStore] -- only the email TRANSPORT used
 * to be a disclosed stub unconditionally; now it is real whenever SMTP is configured. An operator
 * locked out for real, on a deployment without SMTP configured, must still fall back to
 * `network.lapis.cloud.server.bootstrap.AdminBootstrap --force`.
 */
interface PasswordResetMailer {
    /**
     * Hands [rawToken] to [email] off for delivery. **Fire-and-forget**: returns as soon as the
     * message is accepted by [MailDispatcher], NOT once it is actually delivered --
     * [DeliveryStatus.SENT] means "accepted for delivery", not "delivered". A delivery failure
     * surfaces as an ERROR log line (see [MailDispatcher] KDoc), never as an exception back to the
     * caller -- deliberate, see [MailDispatcher] KDoc "Fire-and-forget by design" for the
     * timing-side-channel reasoning this depends on. **Never logs [rawToken]** -- logging the raw,
     * bearer-usable reset token would defeat the entire hash-only-persisted security model
     * [network.lapis.cloud.server.security.PasswordResetTokenStore] establishes.
     */
    fun send(
        email: String,
        rawToken: String,
    ): DeliveryStatus
}
