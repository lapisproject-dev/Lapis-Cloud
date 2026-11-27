package network.lapis.cloud.server.mail

import network.lapis.cloud.shared.domain.DeliveryStatus

/**
 * Thin [PasswordResetMailer] adapter over [MailDispatcher] -- see [PasswordResetMailer] KDoc "real
 * SMTP transport, optional" for the full contract this implements.
 */
class SmtpPasswordResetMailer(
    private val dispatcher: MailDispatcher,
    private val branding: MailBranding,
    /** V1.9.334 -- the instance mail language, read at SEND time (a setting change needs no restart). Never throws, see [MailLanguage.instance]. */
    private val mailLanguage: () -> String = { "de" },
) : PasswordResetMailer {
    override fun send(
        email: String,
        rawToken: String,
    ): DeliveryStatus {
        val mail = MailTemplates.passwordReset(rawToken = rawToken, branding = branding, language = MailLanguage.instance(mailLanguage))
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
