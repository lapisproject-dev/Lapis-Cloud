package network.lapis.cloud.server.mail

import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.shared.domain.DeliveryStatus

/**
 * Thin [FinTsReauthNotificationMailer] adapter over [MailDispatcher] -- exact mirror of
 * [SmtpAdminPasswordResetNotificationMailer]. The recipient CAP is the caller's responsibility
 * (`network.lapis.cloud.server.payment.fints.FinTsPoller` bounds [recipients] to
 * `network.lapis.cloud.server.webhook.WEBHOOK_NOTIFICATION_MAX_RECIPIENTS` before calling [send] --
 * same "board-wide notification, generous but bounded" posture
 * [network.lapis.cloud.server.webhook.WebhookDeactivationNotifier] already establishes), this class
 * simply enqueues one mail per address it is given.
 */
class SmtpFinTsReauthNotificationMailer(
    private val dispatcher: MailDispatcher,
    private val branding: MailBranding,
) : FinTsReauthNotificationMailer {
    override fun send(
        recipients: List<String>,
        accountLabel: String,
        ibanMasked: String,
        occurredAt: LocalDateTime,
    ): DeliveryStatus {
        val mail =
            MailTemplates.finTsReauthRequired(
                accountLabel = accountLabel,
                ibanMasked = ibanMasked,
                occurredAt = occurredAt,
                branding = branding,
            )
        recipients.forEach { email ->
            dispatcher.enqueue(
                to = email,
                subject = mail.subject,
                plainTextBody = mail.plainText,
                htmlBody = mail.html,
                purpose = "fints-reauth-required",
            )
        }
        return DeliveryStatus.SENT
    }
}
