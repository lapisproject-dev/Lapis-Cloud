package network.lapis.cloud.server.mail

/**
 * Test-only stand-in for [ArticleReviewNotificationMailer] -- records every call (so
 * `ArticleServiceTest` can assert the right outcome/reason/publicUrl reached the mailer) and,
 * via [throwOnSend], can simulate a transport failure so the "mail failure leaves the DB status
 * change intact" test can exercise `ArticleService.notifyAfterCommit`'s own `runCatching`.
 */
class FakeArticleReviewNotificationMailer : ArticleReviewNotificationMailer {
    val calls = mutableListOf<ArticleReviewNotification>()
    var throwOnSend: Boolean = false

    override fun send(notification: ArticleReviewNotification) {
        calls += notification
        if (throwOnSend) throw RuntimeException("simulated mail transport failure")
    }
}
