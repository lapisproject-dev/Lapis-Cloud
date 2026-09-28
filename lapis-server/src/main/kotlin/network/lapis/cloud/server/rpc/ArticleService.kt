package network.lapis.cloud.server.rpc

import io.ktor.server.application.ApplicationCall
import network.lapis.cloud.server.articles.ArticleCoverPolicy
import network.lapis.cloud.server.articles.ArticleMarkdown
import network.lapis.cloud.server.articles.ArticlePolicy
import network.lapis.cloud.server.articles.ArticleStore
import network.lapis.cloud.server.audit.AuditLogRecorder
import network.lapis.cloud.server.db.DbClock
import network.lapis.cloud.server.db.generated.ArticleTable
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
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import kotlin.uuid.Uuid

private val ARTICLE_REVIEW_ROLES = arrayOf(AccountRole.BOARD, AccountRole.ADMIN)

/**
 * Welle V1.4.34 "Nachrichten-/Artikel-Modul mit redaktionellem Workflow" -- see [IArticleService]
 * KDoc and `55-articles.kuml.kts` file header for the full state machine. [baseUrl] backs
 * [ArticleCoverPolicy.coverImageUrl] (same convention `EventService`/`EventCoverPolicy` establish).
 *
 * Mail notifications (approve/reject/unpublish) are deliberately NOT wired in this pass -- see
 * the implementation plan §5 for the intended `SmtpArticleReviewNotificationMailer`; this wave
 * ships the transition/authorization/audit core first. `Application.kt` wiring (routes, rate
 * limiters, mail) is a follow-up wave, not part of this commit.
 */
class ArticleService(
    private val call: ApplicationCall,
    private val baseUrl: String,
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

    override suspend fun saveDraft(
        id: String?,
        input: ArticleDraftInput,
    ): ArticleDto {
        val current = resolveCurrentMember(call)
        ArticlePolicy.validateDraftLengths(input)
        val now = nowLocalDateTime()
        return transaction {
            requireActiveMembership(memberId = current.memberId)
            val articleId =
                if (id == null) {
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

    override suspend fun deleteDraft(id: String) {
        val current = resolveCurrentMember(call)
        val articleId = id.toArticleUuid()
        transaction {
            requireActiveMembership(memberId = current.memberId)
            val row = ArticleStore.requireOwnedRowForUpdate(id = articleId, authorId = current.memberId)
            if (row[ArticleTable.status] != ArticleStatus.DRAFT) {
                throw ConflictException("Article $articleId can only be deleted while DRAFT (is ${row[ArticleTable.status]})")
            }
            ArticleStore.deleteById(articleId)
        }
    }

    override suspend fun previewArticle(body: String): String {
        val current = resolveCurrentMember(call)
        transaction { requireActiveMembership(memberId = current.memberId) }
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
                coverImageUrl = null,
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
        return transaction {
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
            ArticleStore.getOrThrow(articleId).toDto()
        }
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
        return transaction {
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
            ArticleStore.getOrThrow(articleId).toDto()
        }
    }

    override suspend fun unpublishArticle(
        id: String,
        reason: String,
    ): ArticleDto {
        val current = resolveCurrentMember(call)
        current.requireRole(*ARTICLE_REVIEW_ROLES)
        ArticlePolicy.validateUnpublishReason(reason)
        val articleId = id.toArticleUuid()
        val now = nowLocalDateTime()
        return transaction {
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
            ArticleStore.getOrThrow(articleId).toDto()
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

    private fun ResultRow.toDto(): ArticleDto {
        val slug = this[ArticleTable.slug]
        return ArticleDto(
            id = this[ArticleTable.id].toString(),
            slug = slug,
            title = this[ArticleTable.title],
            excerpt = this[ArticleTable.excerpt],
            body = this[ArticleTable.body],
            coverImageUrl =
                slug?.let {
                    ArticleCoverPolicy.coverImageUrl(baseUrl = baseUrl, slug = it, coverImageId = this[ArticleTable.coverImageId])
                },
            status = this[ArticleTable.status],
            submittedAt = this[ArticleTable.submittedAt],
            reviewedAt = this[ArticleTable.reviewedAt],
            rejectionReason = this[ArticleTable.rejectionReason],
            publishedAt = this[ArticleTable.publishedAt],
            updatedAt = this[ArticleTable.updatedAt],
        )
    }

    private fun nowLocalDateTime() = DbClock.nowLocalDateTime()

    private fun String.toArticleUuid(): Uuid = runCatching { Uuid.parse(this) }.getOrElse { throw NotFoundException("Invalid id: $this") }
}
