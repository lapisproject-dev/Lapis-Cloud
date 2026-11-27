package network.lapis.cloud.server.mail

import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.shared.domain.DeliveryStatus

/**
 * Thin [AdminPasswordResetNotificationMailer] adapter over [MailDispatcher] -- exact mirror of
 * [SmtpPasswordResetMailer].
 */
class SmtpAdminPasswordResetNotificationMailer(
    private val dispatcher: MailDispatcher,
    private val branding: MailBranding,
    /** V1.9.334 -- the instance mail language, read at SEND time (a setting change needs no restart). Never throws, see [MailLanguage.instance]. */
    private val mailLanguage: () -> String = { "de" },
) : AdminPasswordResetNotificationMailer {
    override fun send(
        email: String,
        occurredAt: LocalDateTime,
    ): DeliveryStatus {
        val mail =
            MailTemplates.passwordResetByAdmin(
                occurredAt = occurredAt,
                branding = branding,
                language = MailLanguage.instance(mailLanguage),
            )
        dispatcher.enqueue(
            to = email,
            subject = mail.subject,
            plainTextBody = mail.plainText,
            htmlBody = mail.html,
            purpose = "admin-password-reset-notice",
        )
        return DeliveryStatus.SENT
    }
}
