package network.lapis.cloud.shared.rpc

import dev.kilua.rpc.annotations.RpcService
import network.lapis.cloud.shared.domain.ArticleDraftInput
import network.lapis.cloud.shared.domain.ArticleDto
import network.lapis.cloud.shared.domain.ArticleReviewDto
import network.lapis.cloud.shared.domain.ArticleSummaryDto

/**
 * Welle V1.4.34 "Nachrichten-/Artikel-Modul mit redaktionellem Workflow" -- the AUTHENTICATED RPC
 * surface. The unauthenticated public reading path (`/aktuelles/{slug}`,
 * `network.lapis.cloud.server.routes.registerArticlePublicRoutes`) does not go through this
 * interface -- same split `IEventService`/`registerEventPublicRoutes` already establish.
 *
 * Author-facing functions ([saveDraft]/[submitArticle]/[withdrawArticle]/[deleteDraft]/
 * [previewArticle]) require an ORGANIZATION_MEMBER-status caller (`requireActiveMembership`) --
 * FRIEND/GUEST/APPLICATION/WITHDRAWN/REJECTED members are refused. Board-facing functions
 * ([listSubmittedArticles]/[getArticleForReview]/[approveArticle]/[rejectArticle]/
 * [unpublishArticle]) require BOARD or ADMIN role, AND additionally refuse the caller acting on
 * their own article (Vier-Augen-Prinzip -- see `ArticleService.requireNotOwnArticle` KDoc, applies
 * even to an ADMIN who is also the author).
 */
@RpcService
interface IArticleService {
    /** Role: any authenticated ORGANIZATION_MEMBER. Own articles only, all statuses. */
    suspend fun listMyArticles(): List<ArticleDto>

    /** Role: any authenticated ORGANIZATION_MEMBER. Own article only, any status. */
    suspend fun getMyArticle(id: String): ArticleDto

    /**
     * Create-or-update. `id == null` creates a new `DRAFT`. An existing article may only be saved
     * while it is `DRAFT` or `REJECTED` -- `SUBMITTED`/`PUBLISHED` are read-only from this
     * function's perspective (`ConflictException` otherwise). Only length ceilings are enforced
     * here (drafts may otherwise be incomplete) -- see `ArticlePolicy.validateDraftLengths`.
     */
    suspend fun saveDraft(
        id: String?,
        input: ArticleDraftInput,
    ): ArticleDto

    /** `DRAFT`/`REJECTED` -> `SUBMITTED`. Server validates title/excerpt/body against `ArticlePolicy.validateForSubmit` regardless of any client-side pre-check. */
    suspend fun submitArticle(id: String): ArticleDto

    /** `SUBMITTED` -> `DRAFT`. Own article only. */
    suspend fun withdrawArticle(id: String): ArticleDto

    /** `DRAFT` only -- `ConflictException` for any other status. Own article only. */
    suspend fun deleteDraft(id: String)

    /** Server-rendered preview HTML for the editor's "Vorschau" tab -- rate-limited, see `Application.kt` wiring. */
    suspend fun previewArticle(body: String): String

    // -- Vorstand (BOARD/ADMIN) --

    /** Oldest-submitted-first queue. */
    suspend fun listSubmittedArticles(): List<ArticleSummaryDto>

    /** `SUBMITTED` only -- `NotFoundException` otherwise. Never leaks the raw Markdown body -- see [ArticleReviewDto] KDoc. */
    suspend fun getArticleForReview(id: String): ArticleReviewDto

    /** `SUBMITTED` -> `PUBLISHED`, assigns the article's permanent slug. Refuses the caller's own article. */
    suspend fun approveArticle(id: String): ArticleDto

    /** `SUBMITTED` -> `REJECTED`. [reason] optional. Refuses the caller's own article. */
    suspend fun rejectArticle(
        id: String,
        reason: String?,
    ): ArticleDto

    /** `PUBLISHED` -> `REJECTED`. [reason] mandatory (10..1000 chars). Slug and `publishedAt` are kept (audit trail), the public route then 404s. Refuses the caller's own article. */
    suspend fun unpublishArticle(
        id: String,
        reason: String,
    ): ArticleDto
}
