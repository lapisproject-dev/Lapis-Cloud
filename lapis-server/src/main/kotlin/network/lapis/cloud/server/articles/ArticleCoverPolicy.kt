package network.lapis.cloud.server.articles

import kotlin.uuid.Uuid

/**
 * Pure URL formatting for an article's public cover image -- the actual upload/serving routes
 * (`POST/DELETE /api/articles/{id}/cover`, `GET /aktuelles/{slug}/bild`) reuse
 * `network.lapis.cloud.server.events.EventCoverImageProcessor`/`EventCoverStorage` 1:1 (see the
 * implementation plan §4.2 "Wiederverwendung 1:1") with an article-specific storage root --
 * deferred to a follow-up wave, not part of this pass. This object exists already so
 * [network.lapis.cloud.shared.domain.ArticleDto.coverImageUrl] has a stable shape to build
 * against.
 */
internal object ArticleCoverPolicy {
    fun coverImageUrl(
        baseUrl: String,
        slug: String,
        coverImageId: Uuid?,
    ): String? = coverImageId?.let { "$baseUrl/aktuelles/$slug/bild?v=${it.toString().take(8)}" }
}
