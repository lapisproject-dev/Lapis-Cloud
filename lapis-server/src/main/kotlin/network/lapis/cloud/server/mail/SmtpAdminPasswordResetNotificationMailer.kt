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
) : AdminPasswordResetNotificationMailer {
    override fun send(
        email: String,
        occurredAt: LocalDateTime,
    ): DeliveryStatus {
        val mail = MailTemplates.passwordResetByAdmin(occurredAt = occurredAt, branding = branding)
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
