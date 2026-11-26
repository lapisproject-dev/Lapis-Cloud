package network.lapis.cloud.server.mail

import io.github.oshai.kotlinlogging.KotlinLogging

private val logger = KotlinLogging.logger {}

/**
 * Thin [TenderPayoutNotificationMailer] adapter over [MailDispatcher]. [smtpConfigured] short-circuits BEFORE [MailDispatcher.enqueue].
 * The recipient is not logged (`logRecipient = false`): an address next to a money event does not belong in one log line.
 */
class SmtpTenderPayoutNotificationMailer(
    private val dispatcher: MailDispatcher,
    private val branding: MailBranding,
    private val smtpConfigured: Boolean,
    private val brandTitle: String,
) : TenderPayoutNotificationMailer {
    override fun sendPaidForWinner(notification: TenderPayoutPaidNotification) {
        if (!smtpConfigured) {
            logger.info { "tender payout receipt skipped (SMTP not configured): tenderId=${notification.tenderId}" }
            return
        }
        val mail = MailTemplates.tenderPayoutPaid(notification = notification, brandTitle = brandTitle, branding = branding)
        dispatcher.enqueue(
            to = notification.winnerEmail,
            subject = mail.subject,
            plainTextBody = mail.plainText,
            htmlBody = mail.html,
            purpose = TENDER_PAYOUT_PAID_PURPOSE,
            logRecipient = false,
        )
    }
}
