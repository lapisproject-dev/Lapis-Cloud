package network.lapis.cloud.server.routes

import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.plugins.origin
import io.ktor.server.request.path
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.options
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import network.lapis.cloud.server.articles.ArticleCoverPolicy
import network.lapis.cloud.server.articles.ArticleStore
import network.lapis.cloud.server.db.generated.ArticleTable
import network.lapis.cloud.server.embed.EmbedConfig
import network.lapis.cloud.server.embed.EmbedCorsResult
import network.lapis.cloud.server.embed.applyEmbedCors
import network.lapis.cloud.server.embed.respondEmbedForbiddenOrigin
import network.lapis.cloud.server.embed.respondEmbedPreflight
import network.lapis.cloud.server.federation.FederationInboxRateLimiter
import org.jetbrains.exposed.v1.jdbc.transactions.transaction

private val logger = KotlinLogging.logger {}

private val EMBED_ARTICLES_FEED_JSON = Json { explicitNulls = false }
private val EMBED_ARTICLES_FEED_JSON_CONTENT_TYPE = ContentType.Application.Json.withParameter("charset", "utf-8")

/**
 * Welle V1.4.36 "Nachrichten-/Artikel-Modul, Folgewelle" -- 1:1 structural mirror of
 * [EmbedEventsFeedLimits]/[registerEmbedEventsFeedRoutes] for the article family. [MAX_ARTICLES]/
 * [DEFAULT_ARTICLES] are deliberately their OWN, smaller numbers than the events feed's `MAX_EVENTS
 * = 50` -- a partner website's article widget realistically shows the 5-10 most recent headlines,
 * never a full archive dump (Stolperfalle §9 of the plan: [ArticleStore.listPublishedNewestFirst]
 * itself floors any `limit` argument at `1`, so [parseLimit] must already have normalized into
 * `1..MAX_ARTICLES` before that call, never pass a raw, un-clamped value through).
 */
internal object EmbedArticlesFeedLimits {
    const val MAX_ARTICLES = 20
    const val DEFAULT_ARTICLES = 10

    /** `null`, unparsable, or `<= 0` -> [DEFAULT_ARTICLES]; anything `> MAX_ARTICLES` -> [MAX_ARTICLES]. Never throws. */
    fun parseLimit(raw: String?): Int {
        val parsed = raw?.toIntOrNull()
        if (parsed == null || parsed <= 0) return DEFAULT_ARTICLES
        return parsed.coerceAtMost(MAX_ARTICLES)
    }
}

@Serializable
internal data class EmbedArticleListItemDto(
    val slug: String,
    val title: String,
    val excerpt: String,
    val url: String,
    val coverImageUrl: String? = null,
    val publishedAt: String,
)

@Serializable
internal data class EmbedArticleListResponse(
    val articles: List<EmbedArticleListItemDto>,
)

/**
 * Registers `GET /api/embed/v1/articles` and its `OPTIONS` preflight. Called from
 * [registerEmbedRoutes] INSIDE the `if (!config.enabled) return` gate, directly after
 * [registerEmbedEventsFeedRoutes] -- same registration discipline every other route in that
 * function follows, and same Q5 decision (plan's open question, confirmed default): the embed
 * feed is opt-in behind `LAPIS_EMBED_ENABLED=true` like every other embed-widget endpoint, UNLIKE
 * the public `/aktuelles/{slug}` page and its cover-image route, which stay always-on (see
 * [registerArticlePublicRoutes] KDoc).
 *
 * **Read-only, public-by-design** -- same [EmbedCorsResult.NoOriginHeader] "normal, 200-continuing"
 * posture [registerEmbedEventsFeedRoutes] already establishes (see that function's own KDoc for
 * the full reasoning): this route never reads a cookie, never writes anything, and never echoes
 * anything beyond what `/aktuelles/{slug}` already serves unauthenticated.
 *
 * **Allowlist mapping, not a DTO reuse.** [EmbedArticleListItemDto] deliberately does NOT reuse
 * `network.lapis.cloud.shared.domain.ArticleDto` -- an explicit field-by-field allowlist mapping is
 * the load-bearing property `EmbedArticlesFeedRoutesTest` asserts on (the wire JSON must never
 * contain `body`/`authorId`/`author`/`reviewedBy`/`id`/`rejectionReason`, checked at the raw-STRING
 * level, not merely "the type doesn't have that field" -- see that test's own KDoc).
 */
internal fun Route.registerEmbedArticlesFeedRoutes(
    config: EmbedConfig,
    baseUrl: String,
    feedRateLimiter: FederationInboxRateLimiter,
    preflightRateLimiter: FederationInboxRateLimiter,
) {
    get("/api/embed/v1/articles") {
        call.withEmbedArticlesFeedErrorHandling {
            val cors = call.applyEmbedCors(allowlist = config.allowlist, allowInsecure = config.allowInsecureOrigins)
            if (cors is EmbedCorsResult.Rejected) {
                call.respondEmbedForbiddenOrigin()
                return@withEmbedArticlesFeedErrorHandling
            }

            val rateLimitKey = rateLimitKeyFor(remoteHost = call.request.origin.remoteHost)
            if (!feedRateLimiter.checkAndRecord(rateLimitKey)) {
                val retryAfterSeconds = feedRateLimiter.retryAfterSeconds(rateLimitKey)
                call.response.header(HttpHeaders.RetryAfter, retryAfterSeconds.toString())
                call.respond(HttpStatusCode.TooManyRequests)
                return@withEmbedArticlesFeedErrorHandling
            }

            call.response.header("X-Content-Type-Options", "nosniff")

            val limit = EmbedArticlesFeedLimits.parseLimit(call.request.queryParameters["limit"])
            val items =
                transaction {
                    // Defensive: a PUBLISHED row without `slug`/`publishedAt` cannot happen by
                    // construction (`ArticleService.approveArticle` sets both together), but this
                    // route must never throw on a single such data inconsistency -- one bad row
                    // would otherwise 500 the ENTIRE partner feed. Skip just that row (logged) and
                    // keep serving the rest, same "never throw on a data inconsistency" stance
                    // `ArticlePublicRoutes`' own `/aktuelles/{slug}` route already takes (there it
                    // renders a 404 for the one affected article instead of failing the whole page).
                    ArticleStore.listPublishedNewestFirst(limit).mapNotNull { row ->
                        val slug = row[ArticleTable.slug]
                        val publishedAt = row[ArticleTable.publishedAt]
                        if (slug == null || publishedAt == null) {
                            logger.warn {
                                "Skipping inconsistent PUBLISHED article ${row[ArticleTable.id]} from the embed feed " +
                                    "(slug=$slug, publishedAt=$publishedAt)"
                            }
                            return@mapNotNull null
                        }
                        EmbedArticleListItemDto(
                            slug = slug,
                            title = row[ArticleTable.title],
                            excerpt = row[ArticleTable.excerpt],
                            url = "$baseUrl/aktuelles/$slug",
                            coverImageUrl =
                                ArticleCoverPolicy.publicCoverImageUrl(
                                    baseUrl = baseUrl,
                                    slug = slug,
                                    coverImageId = row[ArticleTable.coverImageId],
                                ),
                            publishedAt = embedFeedUtcFromSystem(publishedAt),
                        )
                    }
                }

            call.respondText(
                text = EMBED_ARTICLES_FEED_JSON.encodeToString(EmbedArticleListResponse.serializer(), EmbedArticleListResponse(items)),
                contentType = EMBED_ARTICLES_FEED_JSON_CONTENT_TYPE,
                status = HttpStatusCode.OK,
            )
        }
    }

    options("/api/embed/v1/articles") {
        if (!preflightRateLimiter.checkAndRecord(rateLimitKeyFor(remoteHost = call.request.origin.remoteHost))) {
            call.response.header(HttpHeaders.CacheControl, "no-store")
            call.respond(HttpStatusCode.TooManyRequests)
            return@options
        }
        val cors = call.applyEmbedCors(allowlist = config.allowlist, allowInsecure = config.allowInsecureOrigins)
        if (cors is EmbedCorsResult.Rejected) {
            call.respondEmbedForbiddenOrigin()
        } else {
            call.respondEmbedPreflight(allowedMethods = "GET, OPTIONS")
        }
    }
}

/** Same guarantee `EmbedEventsFeedRoutes`' own error handler establishes -- plain `try`/`catch`, NOT `runCatching` (would also swallow [CancellationException] on a client disconnect). No PII of any kind ever reaches this endpoint's response or logs. */
private suspend fun ApplicationCall.withEmbedArticlesFeedErrorHandling(handler: suspend () -> Unit) {
    try {
        handler()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        logger.error(e) { "Unhandled exception in the embed articles-feed handler (${request.path()})" }
        runCatching { respond(HttpStatusCode.InternalServerError) }
    }
}
