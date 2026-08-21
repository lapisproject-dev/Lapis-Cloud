package network.lapis.cloud.server.mail

import network.lapis.cloud.shared.domain.DeliveryStatus

/**
 * Thin [FriendVerificationMailer] adapter over [MailDispatcher] -- see [FriendVerificationMailer]
 * KDoc "real SMTP transport, optional" for the full contract this implements. Uses the SAME
 * [MailDispatcher]/[MailTransport] instance [SmtpPasswordResetMailer] uses (one transport, two
 * thin adapters -- see `Application.kt` wiring).
 */
class SmtpFriendVerificationMailer(
    private val dispatcher: MailDispatcher,
    private val branding: MailBranding,
) : FriendVerificationMailer {
    override fun send(
        email: String,
        rawToken: String,
    ): DeliveryStatus {
        val mail = MailTemplates.friendVerification(rawToken = rawToken, branding = branding)
        dispatcher.enqueue(
            to = email,
            subject = mail.subject,
            plainTextBody = mail.plainText,
            htmlBody = mail.html,
            purpose = "friend-email-verification",
        )
        return DeliveryStatus.SENT
    }
}
