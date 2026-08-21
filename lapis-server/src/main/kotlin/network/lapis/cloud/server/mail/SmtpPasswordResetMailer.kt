package network.lapis.cloud.server.mail

import network.lapis.cloud.shared.domain.DeliveryStatus

/**
 * Thin [PasswordResetMailer] adapter over [MailDispatcher] -- see [PasswordResetMailer] KDoc "real
 * SMTP transport, optional" for the full contract this implements.
 */
class SmtpPasswordResetMailer(
    private val dispatcher: MailDispatcher,
    private val branding: MailBranding,
) : PasswordResetMailer {
    override fun send(
        email: String,
        rawToken: String,
    ): DeliveryStatus {
        val mail = MailTemplates.passwordReset(rawToken = rawToken, branding = branding)
        dispatcher.enqueue(
            to = email,
            subject = mail.subject,
            plainTextBody = mail.plainText,
            htmlBody = mail.html,
            purpose = "password-reset",
        )
        return DeliveryStatus.SENT
    }
}
