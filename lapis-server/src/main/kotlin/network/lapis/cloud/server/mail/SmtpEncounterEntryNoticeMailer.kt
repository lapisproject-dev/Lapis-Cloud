package network.lapis.cloud.server.mail

/**
 * Thin [EncounterEntryNoticeMailer] adapter over [MailDispatcher] (same shape as [SmtpArticleReviewNotificationMailer]).
 *
 * One mail PER recipient (nobody sees the addresses of the other office holders). The dispatcher is told NOT to log the recipient
 * (`logRecipient = false`): even a masked address next to the purpose `encounter-entry-notice` would be a log line saying that a
 * given office holder was told about an entry (Art. 9 GDPR). This class itself logs nothing.
 */
class SmtpEncounterEntryNoticeMailer(
    private val dispatcher: MailDispatcher,
    private val branding: MailBranding,
    private val smtpConfigured: Boolean,
    /** V1.9.334 -- the instance mail language, read at SEND time (a setting change needs no restart). Never throws, see [MailLanguage.instance]. */
    private val mailLanguage: () -> String = { "de" },
) : EncounterEntryNoticeMailer {
    override val enabled: Boolean get() = smtpConfigured

    override fun send(notice: EncounterEntryNotice) {
        if (!smtpConfigured) return
        val mail = MailTemplates.encounterEntryNotice(notice = notice, branding = branding, language = MailLanguage.instance(mailLanguage))
        notice.recipients.forEach { recipient ->
            dispatcher.enqueue(
                to = recipient,
                subject = mail.subject,
                plainTextBody = mail.plainText,
                htmlBody = mail.html,
                purpose = "encounter-entry-notice",
                logRecipient = false,
            )
        }
    }
}
