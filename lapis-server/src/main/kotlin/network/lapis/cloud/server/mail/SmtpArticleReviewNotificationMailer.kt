package network.lapis.cloud.server.mail

import io.github.oshai.kotlinlogging.KotlinLogging

private val logger = KotlinLogging.logger {}

/**
 * Thin [ArticleReviewNotificationMailer] adapter over [MailDispatcher] -- same "one mail, enqueue
 * and return" shape [SmtpFinTsReauthNotificationMailer] already establishes for a single-recipient
 * notification.
 *
 * **[smtpConfigured] short-circuits BEFORE [MailDispatcher.enqueue] is ever called** (rather than
 * relying on [MailDispatcher]'s own [NoOpMailTransport] fallback to silently swallow the send) --
 * deliberate, testable behavior: `ArticleReviewMailTemplatesTest`/`ArticleServiceTest` assert that
 * an unconfigured SMTP instance never reaches [MailDispatcher] at all for this notification family,
 * not merely that nothing ends up in an inbox.
 */
class SmtpArticleReviewNotificationMailer(
    private val dispatcher: MailDispatcher,
    private val branding: MailBranding,
    private val smtpConfigured: Boolean,
    private val brandTitle: String,
) : ArticleReviewNotificationMailer {
    override fun send(notification: ArticleReviewNotification) {
        if (!smtpConfigured) {
            logger.info {
                "article review notice skipped (SMTP not configured): articleId=${notification.articleId} outcome=${notification.outcome}"
            }
            return
        }
        val mail = MailTemplates.articleReview(notification = notification, brandTitle = brandTitle, branding = branding)
        dispatcher.enqueue(
            to = notification.authorEmail,
            subject = mail.subject,
            plainTextBody = mail.plainText,
            htmlBody = mail.html,
            purpose = "article-review-${notification.outcome.name.lowercase()}",
        )
    }
}
