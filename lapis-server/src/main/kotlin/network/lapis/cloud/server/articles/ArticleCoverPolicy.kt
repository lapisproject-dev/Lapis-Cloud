package network.lapis.cloud.server.articles

import network.lapis.cloud.server.db.generated.ArticleTable
import network.lapis.cloud.shared.domain.ArticleStatus
import org.jetbrains.exposed.v1.core.ResultRow
import kotlin.uuid.Uuid

/**
 * Pure URL formatting for an article's cover image -- the actual upload/serving routes
 * (`POST/DELETE /api/articles/{id}/cover`, `GET /aktuelles/{slug}/bild`) reuse
 * `network.lapis.cloud.server.events.EventCoverImageProcessor`/`EventCoverStorage` 1:1 (Welle
 * V1.4.36, plan §4.2 "Wiederverwendung 1:1") with an article-specific storage root (`article-
 * covers/`, see `Application.kt` wiring) -- deliberately NO local re-implementation of the size/
 * format constants here: [network.lapis.cloud.server.events.EventCoverPolicy.MAX_UPLOAD_BYTES]/
 * `MIN_*`/`TARGET_LONG_EDGE_PX`/`JPEG_QUALITY` are reused verbatim so the two upload families never
 * silently drift apart on limits.
 *
 * **Two distinct URL shapes, chosen per article status** (see [coverImageUrlFor]):
 * - PUBLISHED with a slug: the public, unauthenticated `/aktuelles/{slug}/bild?v=...` URL (cacheable
 *   by any browser/CDN, same `?v=` cache-busting convention `EventCoverPolicy.coverImageUrl`
 *   establishes -- the first 8 hex characters of the cover image's own id).
 * - Every other status (DRAFT/SUBMITTED/REJECTED, or PUBLISHED-but-somehow-slug-less, which cannot
 *   happen by construction but is handled the same way defensively): the authenticated
 *   `/api/articles/{id}/cover?v=...` URL -- reachable only by the author or a BOARD/ADMIN reviewer
 *   (see `ArticleCoverRoutes`' own GET route KDoc), never publicly cacheable.
 */
internal object ArticleCoverPolicy {
    /** Public, unauthenticated URL -- `null` iff [coverImageId] is `null` (no cover set). Renamed from the pre-V1.4.36 `coverImageUrl` now that an authenticated counterpart exists (see [authenticatedCoverImageUrl]). */
    fun publicCoverImageUrl(
        baseUrl: String,
        slug: String,
        coverImageId: Uuid?,
    ): String? = coverImageId?.let { "$baseUrl/aktuelles/$slug/bild?v=${it.toString().take(8)}" }

    /** Authenticated URL (`ArticleCoverRoutes`' `GET /api/articles/{id}/cover`) -- `null` iff [coverImageId] is `null`. */
    fun authenticatedCoverImageUrl(
        baseUrl: String,
        articleId: Uuid,
        coverImageId: Uuid?,
    ): String? = coverImageId?.let { "$baseUrl/api/articles/$articleId/cover?v=${it.toString().take(8)}" }

    /**
     * Picks the right one of [publicCoverImageUrl]/[authenticatedCoverImageUrl] for an `article`
     * [ResultRow] -- the single call site every DTO-mapping function (`ArticleService.toDto`/
     * `.getArticleForReview`, `EmbedArticlesFeedRoutes`) should go through, so the PUBLISHED-vs-not
     * decision is made in exactly one place.
     */
    fun coverImageUrlFor(
        baseUrl: String,
        row: ResultRow,
    ): String? {
        val coverImageId = row[ArticleTable.coverImageId]
        val slug = row[ArticleTable.slug]
        return if (row[ArticleTable.status] == ArticleStatus.PUBLISHED && slug != null) {
            publicCoverImageUrl(baseUrl = baseUrl, slug = slug, coverImageId = coverImageId)
        } else {
            authenticatedCoverImageUrl(baseUrl = baseUrl, articleId = row[ArticleTable.id], coverImageId = coverImageId)
        }
    }
}
