package network.lapis.cloud.shared.domain

import kotlinx.datetime.LocalDateTime
import kotlinx.serialization.Serializable

/**
 * Welle V1.4.34 "Nachrichten-/Artikel-Modul mit redaktionellem Workflow" -- see
 * `55-articles.kuml.kts` file header for the full schema-scope rationale and the state machine
 * this enum backs. Literal order load-bearing (mirrors `article.status VARCHAR(10)`,
 * `V55__article.sql`) -- append-only, never reorder.
 *
 * State machine (see `ArticleService` for the transition implementations):
 * `DRAFT`/`REJECTED` --submitArticle--> `SUBMITTED` --withdrawArticle--> `DRAFT`
 * `SUBMITTED` --approveArticle--> `PUBLISHED` --unpublishArticle--> `REJECTED`
 * `SUBMITTED` --rejectArticle--> `REJECTED`
 */
@Serializable
enum class ArticleStatus { DRAFT, SUBMITTED, PUBLISHED, REJECTED }

/**
 * Welle V1.9.105 -- what the author line ([ArticleDto.byline]) of an article means. Literal order load-bearing (mirrors
 * `article.byline_kind VARCHAR(12)`, `V85__article_byline_and_import.sql`) -- append-only, never reorder.
 *
 * - [PERSONAL]: the author's own name, shown ONLY because the author consented (GDPR Art. 6(1)(a)); revocable at any time.
 * - [ORGANIZATION]: an organization/editorial label set by the board; deliberately never a personal name.
 * - [IMPORTED]: taken over unchanged from the legacy website by the admin import; no consent is held in Lapis.
 */
@Serializable
enum class ArticleBylineKind { PERSONAL, ORGANIZATION, IMPORTED }

/** Welle V1.9.105 -- board decision for [network.lapis.cloud.shared.rpc.IArticleService.setBylineAsBoard]: no author line, or an organization label. A personal name cannot be set by the board. */
@Serializable
enum class BoardBylineMode { NONE, ORGANIZATION }

/** Welle V1.9.105 -- input of [network.lapis.cloud.shared.rpc.IArticleService.setBylineAsBoard]. [text] is required for [BoardBylineMode.ORGANIZATION] and ignored for [BoardBylineMode.NONE]. */
@Serializable
data class BoardBylineInput(
    val mode: BoardBylineMode,
    val text: String? = null,
)

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
    /**
     * Welle V1.9.100 -- `null` for a manually created article, otherwise the (untrusted, member-chosen)
     * label of the MCP connection that created the draft. Never part of any public feed.
     */
    val mcpAgentLabel: String? = null,
    /** Welle V1.9.105 -- the author line (see [ArticleBylineKind]); `null` = no author is named. */
    val byline: String? = null,
    val bylineKind: ArticleBylineKind? = null,
    /** Moment of the author's consent, only set for [ArticleBylineKind.PERSONAL]. */
    val bylineConsentAt: LocalDateTime? = null,
    /** TRUE for an article taken over by the admin import (no human author in Lapis). */
    val imported: Boolean = false,
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
 * .listSubmittedArticles]) OR "Veröffentlicht"-list
 * ([network.lapis.cloud.shared.rpc.IArticleService.listPublishedArticles]). [authorIsSelf] steers
 * the client's disabled review-/depublish-button + hint text (server-side enforcement is the real
 * gate -- see `ArticleService.requireNotOwnArticle`) -- a client-side convenience only, never
 * trusted for authorization.
 *
 * [publishedAt]/[slug] are `null` for a row coming from [network.lapis.cloud.shared.rpc
 * .IArticleService.listSubmittedArticles] (a SUBMITTED article has neither yet) and non-null for
 * [network.lapis.cloud.shared.rpc.IArticleService.listPublishedArticles]'s rows -- deliberately the
 * SAME wire type for both lists (not two near-identical DTOs) rather than duplicated, since every
 * other field is already shared 1:1; defaulted to `null` so this stays source-compatible with the
 * pre-V1.4.36 wire shape.
 */
@Serializable
data class ArticleSummaryDto(
    val id: String,
    val title: String,
    val excerpt: String,
    val submittedAt: LocalDateTime,
    val authorIsSelf: Boolean,
    val publishedAt: LocalDateTime? = null,
    val slug: String? = null,
    /** Welle V1.9.100 -- see [ArticleDto.mcpAgentLabel]. */
    val mcpAgentLabel: String? = null,
    /** Welle V1.9.105 -- see [ArticleDto.byline]. */
    val byline: String? = null,
    val bylineKind: ArticleBylineKind? = null,
    val imported: Boolean = false,
    /** Welle V1.9.105 -- `false` if no title image is stored (the board sees which imported articles still need one). */
    val hasCover: Boolean = false,
    /**
     * Welle V1.9.106 -- `true` iff the article is imported AND the calling member ran that import (`authorIsSelf` is always `false` for imports).
     * The bulk approval needs it: a member cannot approve what they imported themselves (four-eyes). Response-only, never read from a request.
     */
    val importedBySelf: Boolean = false,
)

/**
 * Wire shape of `POST`/`DELETE /api/articles/{id}/cover` ([network.lapis.cloud.server.routes
 * .ArticleCoverRoutes]) -- deliberate mirror of `network.lapis.cloud.shared.domain
 * .EventCoverResultDto`, kept as its own type rather than a reuse: the two upload families are
 * unrelated RPC/route surfaces (article vs. event), and a shared type would create an accidental
 * coupling between them for no benefit (see `ArticleCoverPolicy` KDoc "kein Neubau" for the same
 * "reuse the logic, not the wire type" split already applied to the underlying image
 * processing/storage classes).
 */
@Serializable
data class ArticleCoverResultDto(
    val coverImageUrl: String?,
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
    /** Welle V1.9.100 -- see [ArticleDto.mcpAgentLabel]. */
    val mcpAgentLabel: String? = null,
    /** Welle V1.9.105 -- see [ArticleDto.byline]. */
    val byline: String? = null,
    val bylineKind: ArticleBylineKind? = null,
    val imported: Boolean = false,
)
