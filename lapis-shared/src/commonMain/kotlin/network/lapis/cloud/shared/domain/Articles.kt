package network.lapis.cloud.shared.domain

import kotlinx.datetime.LocalDateTime
import kotlinx.serialization.Serializable

/**
 * Welle V1.4.34 "Nachrichten-/Artikel-Modul mit redaktionellem Workflow" -- see
 * `55-articles.kuml.kts` file header for the full schema-scope rationale and the state machine
 * this enum backs. Literal order load-bearing (mirrors `article.status VARCHAR(10)`,
 * `V53__article.sql`) -- append-only, never reorder.
 *
 * State machine (see `ArticleService` for the transition implementations):
 * `DRAFT`/`REJECTED` --submitArticle--> `SUBMITTED` --withdrawArticle--> `DRAFT`
 * `SUBMITTED` --approveArticle--> `PUBLISHED` --unpublishArticle--> `REJECTED`
 * `SUBMITTED` --rejectArticle--> `REJECTED`
 */
@Serializable
enum class ArticleStatus { DRAFT, SUBMITTED, PUBLISHED, REJECTED }

/**
 * The author-facing / board-review wire shape. Deliberately carries NO `authorId`/`reviewedBy` --
 * author identity stays server-internal (Design-Team decision "keine Namen nach aussen", see
 * `55-articles.kuml.kts` file header) -- a client only ever learns whether it may act on its OWN
 * article via the RPC surface's own role/ownership checks, never by comparing ids client-side.
 */
@Serializable
data class ArticleDto(
    val id: String,
    val slug: String?,
    val title: String,
    val excerpt: String,
    val body: String,
    val coverImageUrl: String?,
    val status: ArticleStatus,
    val submittedAt: LocalDateTime?,
    val reviewedAt: LocalDateTime?,
    val rejectionReason: String?,
    val publishedAt: LocalDateTime?,
    val updatedAt: LocalDateTime,
)

/** Create-or-update input for [network.lapis.cloud.shared.rpc.IArticleService.saveDraft]. */
@Serializable
data class ArticleDraftInput(
    val title: String,
    val excerpt: String,
    val body: String,
)

/**
 * One row of the board's "Freigabe"-queue ([network.lapis.cloud.shared.rpc.IArticleService
 * .listSubmittedArticles]). [authorIsSelf] steers the client's disabled review-button + hint text
 * (server-side enforcement is the real gate -- see `ArticleService.requireNotOwnArticle`) --
 * a client-side convenience only, never trusted for authorization.
 */
@Serializable
data class ArticleSummaryDto(
    val id: String,
    val title: String,
    val excerpt: String,
    val submittedAt: LocalDateTime,
    val authorIsSelf: Boolean,
)

/**
 * [network.lapis.cloud.shared.rpc.IArticleService.getArticleForReview]'s wire shape -- deliberately
 * a DIFFERENT type from [ArticleDto], not a reuse with one field ignored: it carries
 * [renderedBodyHtml] (server-rendered via `ArticleMarkdown.render`, same pipeline the public
 * `/aktuelles/{slug}` page uses) instead of the raw Markdown [ArticleDto.body] -- a reviewing
 * board member never receives the author's raw Markdown source, only what the public page would
 * show. See `ArticleService.getArticleForReview` KDoc.
 */
@Serializable
data class ArticleReviewDto(
    val id: String,
    val title: String,
    val excerpt: String,
    val renderedBodyHtml: String,
    val coverImageUrl: String?,
    val submittedAt: LocalDateTime?,
    val authorIsSelf: Boolean,
)
