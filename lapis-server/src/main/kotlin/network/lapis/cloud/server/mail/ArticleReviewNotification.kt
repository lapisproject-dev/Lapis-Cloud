package network.lapis.cloud.server.mail

import kotlin.uuid.Uuid

/** Which of the three board decisions this notification reports -- see `ArticleService`'s own approve/reject/unpublish KDoc for the transitions each corresponds to. */
enum class ArticleReviewOutcome { APPROVED, REJECTED, UNPUBLISHED }

/**
 * Welle V1.4.36 "Nachrichten-/Artikel-Modul, Folgewelle" -- the data an author-facing review
 * notification is built from. Deliberately carries NO reviewer identity (mirrors `ArticleReviewDto`
 * KDoc "keine Namen nach aussen") and NO raw article body -- only what the author already knows
 * (their own article's title) plus the decision itself.
 */
data class ArticleReviewNotification(
    val articleId: Uuid,
    val authorEmail: String,
    val title: String,
    val outcome: ArticleReviewOutcome,
    /** Rejection/unpublish reason, `null` for [ArticleReviewOutcome.APPROVED] and for an omitted rejection reason. */
    val reason: String?,
    /** The public `/aktuelles/{slug}` URL -- non-null only for [ArticleReviewOutcome.APPROVED] (the one outcome that makes the article publicly reachable). */
    val publicUrl: String?,
)

/**
 * Abstraction over "tell the article's author that the board has decided" -- same swap-seam shape
 * [FinTsReauthNotificationMailer]/[AdminPasswordResetNotificationMailer] already establish for
 * unrelated notification needs. **Fire-and-forget, never throws** -- `ArticleService` calls [send]
 * AFTER its own transaction has already committed the status change (see that class' KDoc "Mail
 * vor Commit würde ... eine falsche Mail verschicken"), wrapped in its own `runCatching` on top of
 * this contract's own promise, so a notification failure can never roll back or hide a successful
 * review decision.
 */
interface ArticleReviewNotificationMailer {
    fun send(notification: ArticleReviewNotification)
}
