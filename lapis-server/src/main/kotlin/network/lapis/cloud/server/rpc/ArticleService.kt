package network.lapis.cloud.server.rpc

import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.server.application.ApplicationCall
import network.lapis.cloud.server.articles.ArticleCoverPolicy
import network.lapis.cloud.server.articles.ArticleMarkdown
import network.lapis.cloud.server.articles.ArticlePolicy
import network.lapis.cloud.server.articles.ArticleStore
import network.lapis.cloud.server.audit.AuditLogRecorder
import network.lapis.cloud.server.db.DbClock
import network.lapis.cloud.server.db.generated.ArticleTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.server.events.EventCoverStorage
import network.lapis.cloud.server.federation.FederationInboxRateLimiter
import network.lapis.cloud.server.mail.ArticleReviewNotification
import network.lapis.cloud.server.mail.ArticleReviewNotificationMailer
import network.lapis.cloud.server.mail.ArticleReviewOutcome
import network.lapis.cloud.server.security.requireRole
import network.lapis.cloud.server.security.resolveCurrentMember
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.ArticleDraftInput
import network.lapis.cloud.shared.domain.ArticleDto
import network.lapis.cloud.shared.domain.ArticleReviewDto
import network.lapis.cloud.shared.domain.ArticleStatus
import network.lapis.cloud.shared.domain.ArticleSummaryDto
import network.lapis.cloud.shared.domain.AuditAction
import network.lapis.cloud.shared.domain.AuditEntityType
import network.lapis.cloud.shared.rpc.BadRequestException
import network.lapis.cloud.shared.rpc.ConflictException
import network.lapis.cloud.shared.rpc.ForbiddenException
import network.lapis.cloud.shared.rpc.IArticleService
import network.lapis.cloud.shared.rpc.NotFoundException
import network.lapis.cloud.shared.rpc.RateLimitedException
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import kotlin.uuid.Uuid

private val logger = KotlinLogging.logger {}

private val ARTICLE_REVIEW_ROLES = arrayOf(AccountRole.BOARD, AccountRole.ADMIN)

/**
 * Welle V1.4.34 "Nachrichten-/Artikel-Modul mit redaktionellem Workflow", erweitert Welle V1.4.36
 * "Nachrichten-/Artikel-Modul, Folgewelle" -- see [IArticleService] KDoc and `55-articles.kuml.kts`
 * file header for the full state machine. [baseUrl] backs [ArticleCoverPolicy.coverImageUrlFor]
 * (same convention `EventService`/`EventCoverPolicy` establish).
 *
 * V1.4.36 wires in the three collaborators V1.4.34 deliberately deferred: [previewRateLimiter] (a
 * per-member budget on [previewArticle], mirrors `EventCoverRoutes`' write-side rate limiter
 * posture), [reviewNotifier] (fire-and-forget author notification on approve/reject/unpublish,
 * sent only AFTER the state-changing transaction has committed -- see this class' own
 * "Mail vor Commit" reasoning below), and [coverStorage] (the article-covers on-disk store, reused
 * 1:1 from `network.lapis.cloud.server.events.EventCoverStorage` with an article-specific root --
 * see `ArticleCoverPolicy` KDoc "kein Neubau").
 *
 * Security fix (post-V1.4.36 review, DoS finding): [saveDraft] was reachable by every active member
 * with NO rate limit and NO cap on the number of drafts per author -- see [draftCreateRateLimiter]/
 * [draftUpdateRateLimiter] KDoc on the `saveDraft` call sites and `ArticleStore.MAX_DRAFTS_PER_AUTHOR`
 * for the three-part fix (member-keyed rate limits, a per-author cap, and a capped/limited
 * `listByAuthor`).
 */
internal class ArticleService(
    private val call: ApplicationCall,
    private val baseUrl: String,
    private val previewRateLimiter: FederationInboxRateLimiter,
    private val draftCreateRateLimiter: FederationInboxRateLimiter,
    private val draftUpdateRateLimiter: FederationInboxRateLimiter,
    private val reviewNotifier: ArticleReviewNotificationMailer,
    private val coverStorage: EventCoverStorage,
) : IArticleService {
    override suspend fun listMyArticles(): List<ArticleDto> {
        val current = resolveCurrentMember(call)
        return transaction {
            requireActiveMembership(memberId = current.memberId)
            ArticleStore.listByAuthor(current.memberId).map { it.toDto() }
        }
    }

    override suspend fun getMyArticle(id: String): ArticleDto {
        val current = resolveCurrentMember(call)
        val articleId = id.toArticleUuid()
        return transaction {
            requireActiveMembership(memberId = current.memberId)
            ArticleStore.requireOwnedRowForUpdate(id = articleId, authorId = current.memberId).toDto()
        }
    }

    /**
     * Security fix (post-V1.4.36 review, DoS finding): the create path (`id == null`) is gated by
     * [draftCreateRateLimiter] (a generous but bounded budget -- creates are rare in normal use) AND
     * by `ArticleStore.MAX_DRAFTS_PER_AUTHOR` (`ConflictException` once reached, mirrors every other
     * "cap reached" `ConflictException` this class already throws). The update path (`id != null`)
     * is gated by [draftUpdateRateLimiter] instead -- a much larger budget, because the editor's
     * auto-save fires on a debounce timer while a member is actively typing, not just on explicit
     * clicks. Both checks happen BEFORE the transaction opens, same "cheap in-memory check first"
     * ordering [previewArticle] already establishes.
     */
    override suspend fun saveDraft(
        id: String?,
        input: ArticleDraftInput,
    ): ArticleDto {
        val current = resolveCurrentMember(call)
        ArticlePolicy.validateDraftLengths(input)
        if (id == null) {
            if (!draftCreateRateLimiter.checkAndRecord("member:${current.memberId}")) throw RateLimitedException()
        } else {
            if (!draftUpdateRateLimiter.checkAndRecord("member:${current.memberId}")) throw RateLimitedException()
        }
        val now = nowLocalDateTime()
        return transaction {
            requireActiveMembership(memberId = current.memberId)
            val articleId =
                if (id == null) {
                    if (ArticleStore.countEditableByAuthor(current.memberId) >= ArticleStore.MAX_DRAFTS_PER_AUTHOR) {
                        throw ConflictException(
                            "Draft/rejected article limit of ${ArticleStore.MAX_DRAFTS_PER_AUTHOR} reached -- " +
                                "submit or delete an existing one first",
                        )
                    }
                    ArticleStore.insertDraft(authorId = current.memberId, input = input, now = now)
                } else {
                    val articleUuid = id.toArticleUuid()
                    val row = ArticleStore.requireOwnedRowForUpdate(id = articleUuid, authorId = current.memberId)
                    val status = row[ArticleTable.status]
                    if (status != ArticleStatus.DRAFT && status != ArticleStatus.REJECTED) {
                        throw ConflictException("Article $articleUuid is not editable in status $status")
                    }
                    ArticleStore.updateDraft(id = articleUuid, input = input, now = now)
                    articleUuid
                }
            ArticleStore.getOrThrow(articleId).toDto()
        }
    }

    override suspend fun submitArticle(id: String): ArticleDto {
        val current = resolveCurrentMember(call)
        val articleId = id.toArticleUuid()
        val now = nowLocalDateTime()
        return transaction {
            requireActiveMembership(memberId = current.memberId)
            val row = ArticleStore.requireOwnedRowForUpdate(id = articleId, authorId = current.memberId)
            val status = row[ArticleTable.status]
            if (status != ArticleStatus.DRAFT && status != ArticleStatus.REJECTED) {
                throw ConflictException("Article $articleId cannot be submitted from status $status")
            }
            ArticlePolicy.validateForSubmit(
                title = row[ArticleTable.title],
                excerpt = row[ArticleTable.excerpt],
                body = row[ArticleTable.body],
            )
            val updated =
                ArticleTable.update({
                    (ArticleTable.id eq articleId) and (ArticleTable.status eq status)
                }) {
                    it[ArticleTable.status] = ArticleStatus.SUBMITTED
                    it[submittedAt] = now
                    it[rejectionReason] = null
                    it[updatedAt] = now
                }
            if (updated == 0) throw ConflictException("Article $articleId was concurrently changed -- retry")
            AuditLogRecorder.record(
                actorMemberId = current.memberId,
                actorRole = current.role,
                entityType = AuditEntityType.ARTICLE,
                entityId = articleId,
                action = AuditAction.UPDATE,
                before = "{\"status\":\"$status\"}",
                after = "{\"status\":\"SUBMITTED\"}",
            )
            ArticleStore.getOrThrow(articleId).toDto()
        }
    }

    override suspend fun withdrawArticle(id: String): ArticleDto {
        val current = resolveCurrentMember(call)
        val articleId = id.toArticleUuid()
        val now = nowLocalDateTime()
        return transaction {
            requireActiveMembership(memberId = current.memberId)
            val row = ArticleStore.requireOwnedRowForUpdate(id = articleId, authorId = current.memberId)
            val status = row[ArticleTable.status]
            if (status != ArticleStatus.SUBMITTED) {
                throw ConflictException("Article $articleId cannot be withdrawn from status $status")
            }
            val updated =
                ArticleTable.update({
                    (ArticleTable.id eq articleId) and (ArticleTable.status eq ArticleStatus.SUBMITTED)
                }) {
                    it[ArticleTable.status] = ArticleStatus.DRAFT
                    it[submittedAt] = null
                    it[updatedAt] = now
                }
            if (updated == 0) throw ConflictException("Article $articleId was concurrently changed -- retry")
            AuditLogRecorder.record(
                actorMemberId = current.memberId,
                actorRole = current.role,
                entityType = AuditEntityType.ARTICLE,
                entityId = articleId,
                action = AuditAction.UPDATE,
                before = "{\"status\":\"SUBMITTED\"}",
                after = "{\"status\":\"DRAFT\"}",
            )
            ArticleStore.getOrThrow(articleId).toDto()
        }
    }

    /**
     * Welle V1.4.36 -- the cover image file (if any) is deleted OUTSIDE the transaction, after the
     * row itself is gone, same "delete the row first, the orphaned file second" ordering
     * `ArticleCoverRoutes`' own upload/remove routes already establish (a crash between the two
     * leaves an orphaned file on disk, never a dangling DB reference to a missing one).
     */
    override suspend fun deleteDraft(id: String) {
        val current = resolveCurrentMember(call)
        val articleId = id.toArticleUuid()
        val coverImageId =
            transaction {
                requireActiveMembership(memberId = current.memberId)
                val row = ArticleStore.requireOwnedRowForUpdate(id = articleId, authorId = current.memberId)
                if (row[ArticleTable.status] != ArticleStatus.DRAFT) {
                    throw ConflictException("Article $articleId can only be deleted while DRAFT (is ${row[ArticleTable.status]})")
                }
                val cover = row[ArticleTable.coverImageId]
                ArticleStore.deleteById(articleId)
                cover
            }
        if (coverImageId != null) coverStorage.delete(coverImageId)
    }

    override suspend fun previewArticle(body: String): String {
        val current = resolveCurrentMember(call)
        transaction { requireActiveMembership(memberId = current.memberId) }
        if (!previewRateLimiter.checkAndRecord("member:${current.memberId}")) throw RateLimitedException()
        if (body.length > ArticlePolicy.BODY_MAX) throw BadRequestException("body exceeds ${ArticlePolicy.BODY_MAX} characters")
        return ArticleMarkdown.render(body)
    }

    // -- Vorstand --

    override suspend fun listSubmittedArticles(): List<ArticleSummaryDto> {
        val current = resolveCurrentMember(call)
        current.requireRole(*ARTICLE_REVIEW_ROLES)
        return transaction {
            ArticleStore.listSubmittedOldestFirst().map { row ->
                ArticleSummaryDto(
                    id = row[ArticleTable.id].toString(),
                    title = row[ArticleTable.title],
                    excerpt = row[ArticleTable.excerpt],
                    submittedAt =
                        row[ArticleTable.submittedAt]
                            ?: error("SUBMITTED article ${row[ArticleTable.id]} has no submittedAt"),
                    authorIsSelf = row[ArticleTable.authorId] == current.memberId,
                )
            }
        }
    }

    /**
     * Welle V1.4.36 -- newest-published-first list for the board's "Veröffentlicht" tab. [ArticleSummaryDto.submittedAt] falls back to [ArticleTable.publishedAt] defensively (a published article's own `submittedAt` is never actually cleared by any transition today, but the DTO field is non-nullable, and this keeps the mapping total rather than throwing on a future edge case).
     */
    override suspend fun listPublishedArticles(): List<ArticleSummaryDto> {
        val current = resolveCurrentMember(call)
        current.requireRole(*ARTICLE_REVIEW_ROLES)
        return transaction {
            ArticleStore.listPublishedNewestFirst(ArticleStore.MAX_PAGE_SIZE).map { row ->
                ArticleSummaryDto(
                    id = row[ArticleTable.id].toString(),
                    title = row[ArticleTable.title],
                    excerpt = row[ArticleTable.excerpt],
                    submittedAt =
                        row[ArticleTable.submittedAt]
                            ?: row[ArticleTable.publishedAt]
                            ?: error("PUBLISHED article ${row[ArticleTable.id]} has neither submittedAt nor publishedAt"),
                    authorIsSelf = row[ArticleTable.authorId] == current.memberId,
                    publishedAt = row[ArticleTable.publishedAt],
                    slug = row[ArticleTable.slug],
                )
            }
        }
    }

    override suspend fun getArticleForReview(id: String): ArticleReviewDto {
        val current = resolveCurrentMember(call)
        current.requireRole(*ARTICLE_REVIEW_ROLES)
        val articleId = id.toArticleUuid()
        return transaction {
            val row = ArticleStore.getOrThrow(articleId)
            if (row[ArticleTable.status] != ArticleStatus.SUBMITTED) {
                throw NotFoundException("Article $articleId not found")
            }
            ArticleReviewDto(
                id = row[ArticleTable.id].toString(),
                title = row[ArticleTable.title],
                excerpt = row[ArticleTable.excerpt],
                renderedBodyHtml = ArticleMarkdown.render(row[ArticleTable.body]),
                coverImageUrl =
                    ArticleCoverPolicy.authenticatedCoverImageUrl(
                        baseUrl = baseUrl,
                        articleId = articleId,
                        coverImageId = row[ArticleTable.coverImageId],
                    ),
                submittedAt = row[ArticleTable.submittedAt],
                authorIsSelf = row[ArticleTable.authorId] == current.memberId,
            )
        }
    }

    /**
     * Vier-Augen-Prinzip: refuses even an ADMIN who is also the article's own author. The
     * comparison MUST happen against the freshly row-locked `authorId` (not a value read before
     * the lock), same "lock first, decide second" ordering
     * [network.lapis.cloud.server.rpc.CrowdfundingService.approveProject]'s own KDoc documents --
     * otherwise a concurrent edit of authorship (not currently possible for `article.author_id`,
     * but the ordering discipline is the load-bearing property, not today's absence of a mutator).
     */
    override suspend fun approveArticle(id: String): ArticleDto {
        val current = resolveCurrentMember(call)
        current.requireRole(*ARTICLE_REVIEW_ROLES)
        val articleId = id.toArticleUuid()
        val now = nowLocalDateTime()
        val (dto, notification) =
            transaction {
                val row = ArticleStore.lockForUpdateOrThrow(articleId)
                requireNotOwnArticle(row = row, current = current)
                if (row[ArticleTable.status] != ArticleStatus.SUBMITTED) {
                    throw ConflictException("Article $articleId is not awaiting review (status ${row[ArticleTable.status]})")
                }
                val slug =
                    row[ArticleTable.slug] ?: ArticlePolicy.slugFor(title = row[ArticleTable.title]) { candidate ->
                        ArticleStore.slugTaken(slug = candidate, excludingId = articleId)
                    }
                val updated =
                    ArticleTable.update({
                        (ArticleTable.id eq articleId) and (ArticleTable.status eq ArticleStatus.SUBMITTED)
                    }) {
                        it[status] = ArticleStatus.PUBLISHED
                        it[ArticleTable.slug] = slug
                        it[reviewedBy] = current.memberId
                        it[reviewedAt] = now
                        it[publishedAt] = now
                        it[updatedAt] = now
                    }
                if (updated == 0) throw ConflictException("Article $articleId was concurrently decided -- retry")
                AuditLogRecorder.record(
                    actorMemberId = current.memberId,
                    actorRole = current.role,
                    entityType = AuditEntityType.ARTICLE,
                    entityId = articleId,
                    action = AuditAction.UPDATE,
                    before = "{\"status\":\"SUBMITTED\"}",
                    after = "{\"status\":\"PUBLISHED\",\"slug\":\"$slug\"}",
                )
                val fresh = ArticleStore.getOrThrow(articleId)
                val authorEmail = authorEmailOf(fresh[ArticleTable.authorId])
                val dto = fresh.toDto()
                val notification =
                    ArticleReviewNotification(
                        articleId = articleId,
                        authorEmail = authorEmail,
                        title = fresh[ArticleTable.title],
                        outcome = ArticleReviewOutcome.APPROVED,
                        reason = null,
                        publicUrl = "$baseUrl/aktuelles/$slug",
                    )
                dto to notification
            }
        notifyAfterCommit(notification)
        return dto
    }

    override suspend fun rejectArticle(
        id: String,
        reason: String?,
    ): ArticleDto {
        val current = resolveCurrentMember(call)
        current.requireRole(*ARTICLE_REVIEW_ROLES)
        ArticlePolicy.validateRejectionReason(reason)
        val articleId = id.toArticleUuid()
        val now = nowLocalDateTime()
        val (dto, notification) =
            transaction {
                val row = ArticleStore.lockForUpdateOrThrow(articleId)
                requireNotOwnArticle(row = row, current = current)
                if (row[ArticleTable.status] != ArticleStatus.SUBMITTED) {
                    throw ConflictException("Article $articleId is not awaiting review (status ${row[ArticleTable.status]})")
                }
                val updated =
                    ArticleTable.update({
                        (ArticleTable.id eq articleId) and (ArticleTable.status eq ArticleStatus.SUBMITTED)
                    }) {
                        it[status] = ArticleStatus.REJECTED
                        it[rejectionReason] = reason
                        it[reviewedBy] = current.memberId
                        it[reviewedAt] = now
                        it[updatedAt] = now
                        // Welle V1.4.36, Q1: clears a stale `publishedAt` from an EARLIER publish-
                        // then-unpublish-then-resubmit cycle -- without this, a subsequently
                        // REJECTED article would still carry a non-null `publishedAt` and the
                        // client's "Depubliziert" (status==REJECTED && publishedAt != null) /
                        // "Abgelehnt" (status==REJECTED && publishedAt == null) derivation
                        // (`ArticleLabels.displayStatus`) would misreport it as "Depubliziert"
                        // rather than "Abgelehnt". A no-op for the common case (a first-time
                        // SUBMITTED article has no `publishedAt` yet). History is unaffected --
                        // the full transition sequence remains in `audit_log_entry`.
                        it[publishedAt] = null
                    }
                if (updated == 0) throw ConflictException("Article $articleId was concurrently decided -- retry")
                AuditLogRecorder.record(
                    actorMemberId = current.memberId,
                    actorRole = current.role,
                    entityType = AuditEntityType.ARTICLE,
                    entityId = articleId,
                    action = AuditAction.UPDATE,
                    before = "{\"status\":\"SUBMITTED\"}",
                    after = "{\"status\":\"REJECTED\"}",
                )
                val fresh = ArticleStore.getOrThrow(articleId)
                val authorEmail = authorEmailOf(fresh[ArticleTable.authorId])
                val dto = fresh.toDto()
                val notification =
                    ArticleReviewNotification(
                        articleId = articleId,
                        authorEmail = authorEmail,
                        title = fresh[ArticleTable.title],
                        outcome = ArticleReviewOutcome.REJECTED,
                        reason = reason,
                        publicUrl = null,
                    )
                dto to notification
            }
        notifyAfterCommit(notification)
        return dto
    }

    /**
     * Welle V1.4.36 -- the cover image file is deliberately KEPT (see the pre-existing comment on
     * `publishedAt`/`slug` below, unchanged this wave): re-publishing later (a fresh
     * `submitArticle`/`approveArticle` cycle) must not force the author to re-upload their cover.
     */
    override suspend fun unpublishArticle(
        id: String,
        reason: String,
    ): ArticleDto {
        val current = resolveCurrentMember(call)
        current.requireRole(*ARTICLE_REVIEW_ROLES)
        ArticlePolicy.validateUnpublishReason(reason)
        val articleId = id.toArticleUuid()
        val now = nowLocalDateTime()
        val (dto, notification) =
            transaction {
                val row = ArticleStore.lockForUpdateOrThrow(articleId)
                requireNotOwnArticle(row = row, current = current)
                if (row[ArticleTable.status] != ArticleStatus.PUBLISHED) {
                    throw ConflictException("Article $articleId is not published (status ${row[ArticleTable.status]})")
                }
                // publishedAt/slug are deliberately KEPT (audit trail) -- the public route/embed feed
                // re-check `status == PUBLISHED` on every request, so a kept slug/publishedAt cannot
                // resurrect public visibility (see implementation plan Stolperfalle §8).
                val updated =
                    ArticleTable.update({
                        (ArticleTable.id eq articleId) and (ArticleTable.status eq ArticleStatus.PUBLISHED)
                    }) {
                        it[status] = ArticleStatus.REJECTED
                        it[rejectionReason] = reason
                        it[reviewedBy] = current.memberId
                        it[reviewedAt] = now
                        it[updatedAt] = now
                    }
                if (updated == 0) throw ConflictException("Article $articleId was concurrently changed -- retry")
                AuditLogRecorder.record(
                    actorMemberId = current.memberId,
                    actorRole = current.role,
                    entityType = AuditEntityType.ARTICLE,
                    entityId = articleId,
                    action = AuditAction.UPDATE,
                    before = "{\"status\":\"PUBLISHED\"}",
                    after = "{\"status\":\"REJECTED\"}",
                )
                val fresh = ArticleStore.getOrThrow(articleId)
                val authorEmail = authorEmailOf(fresh[ArticleTable.authorId])
                val dto = fresh.toDto()
                val notification =
                    ArticleReviewNotification(
                        articleId = articleId,
                        authorEmail = authorEmail,
                        title = fresh[ArticleTable.title],
                        outcome = ArticleReviewOutcome.UNPUBLISHED,
                        reason = reason,
                        publicUrl = null,
                    )
                dto to notification
            }
        notifyAfterCommit(notification)
        return dto
    }

    /**
     * Reads the author's current e-mail INSIDE the caller's already-open transaction -- see class
     * KDoc "Mail vor Commit" for why the notification is only ever SENT after that transaction has
     * returned, but the recipient address itself must be read from the same consistent snapshot the
     * status change itself was written from.
     */
    private fun authorEmailOf(authorId: Uuid): String =
        MemberTable.selectAll().where { MemberTable.id eq authorId }.single()[MemberTable.email]

    /**
     * **Mail vor Commit würde bei einem Rollback eine falsche Mail verschicken** -- deshalb wird
     * [ArticleReviewNotificationMailer.send] ausschließlich HIER aufgerufen, nach dem
     * `transaction { }`-Block bereits committet zurückgekehrt ist, nie von innerhalb der Transaktion
     * selbst. Ein Fehlschlag von [reviewNotifier] darf den bereits erfolgreichen Statuswechsel nie
     * rückgängig machen oder die RPC-Antwort scheitern lassen -- deshalb `runCatching`, nie eine
     * rethrow.
     */
    private fun notifyAfterCommit(notification: ArticleReviewNotification) {
        runCatching { reviewNotifier.send(notification) }
            .onFailure { e ->
                logger.warn(e) { "article review notice failed: articleId=${notification.articleId} kind=${notification.outcome}" }
            }
    }

    private fun requireNotOwnArticle(
        row: ResultRow,
        current: network.lapis.cloud.server.security.CurrentMember,
    ) {
        if (row[ArticleTable.authorId] == current.memberId) {
            throw ForbiddenException("Eigene Artikel gibt ein anderes Vorstandsmitglied frei.")
        }
    }

    private fun ResultRow.toDto(): ArticleDto =
        ArticleDto(
            id = this[ArticleTable.id].toString(),
            slug = this[ArticleTable.slug],
            title = this[ArticleTable.title],
            excerpt = this[ArticleTable.excerpt],
            body = this[ArticleTable.body],
            coverImageUrl = ArticleCoverPolicy.coverImageUrlFor(baseUrl = baseUrl, row = this),
            status = this[ArticleTable.status],
            submittedAt = this[ArticleTable.submittedAt],
            reviewedAt = this[ArticleTable.reviewedAt],
            rejectionReason = this[ArticleTable.rejectionReason],
            publishedAt = this[ArticleTable.publishedAt],
            updatedAt = this[ArticleTable.updatedAt],
        )

    private fun nowLocalDateTime() = DbClock.nowLocalDateTime()

    private fun String.toArticleUuid(): Uuid = runCatching { Uuid.parse(this) }.getOrElse { throw NotFoundException("Invalid id: $this") }
}
