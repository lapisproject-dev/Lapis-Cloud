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
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.time.Duration.Companion.minutes

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

    /** V1.9.105 archive feed bounds -- strict (`parseBoundedInt`), unlike the lenient [parseLimit] of the main feed. */
    const val ARCHIVE_MAX_PAGE = 100
    const val ARCHIVE_MAX_LIMIT = 20
    const val ARCHIVE_DEFAULT_LIMIT = 10
}

@Serializable
internal data class EmbedArticleListItemDto(
    val slug: String,
    val title: String,
    val excerpt: String,
    val url: String,
    val coverImageUrl: String? = null,
    val publishedAt: String,
    /** V1.9.105 -- the author line, only present if one is set (consented personal name, organization label or imported line). Never its kind, consent or origin. */
    val byline: String? = null,
    /** V1.9.105 -- the article's full text as the author wrote it: raw Markdown, NOT rendered HTML. Consumers MUST render it with a safe Markdown renderer that does not pass raw HTML through. */
    val body: String? = null,
)

@Serializable
internal data class EmbedArticleListResponse(
    val articles: List<EmbedArticleListItemDto>,
)

/** V1.9.105 -- one page of the article archive. */
@Serializable
internal data class EmbedArticleArchiveResponse(
    val articles: List<EmbedArticleListItemDto>,
    val page: Int,
    val limit: Int,
    val hasMore: Boolean,
)

/**
 * V1.9.105 -- the ONE place that maps a PUBLISHED article row to the public feed item, shared by the main feed and the archive so both have
 * exactly one field format. `null` (logged) for a row without `slug`/`publishedAt`, which cannot happen by construction but must never 500 a feed.
 */
internal fun ResultRow.toEmbedArticleItem(baseUrl: String): EmbedArticleListItemDto? {
    val slug = this[ArticleTable.slug]
    val publishedAt = this[ArticleTable.publishedAt]
    if (slug == null || publishedAt == null) {
        logger.warn {
            "Skipping inconsistent PUBLISHED article ${this[ArticleTable.id]} from the embed feed (slug=$slug, publishedAt=$publishedAt)"
        }
        return null
    }
    return EmbedArticleListItemDto(
        slug = slug,
        title = this[ArticleTable.title],
        excerpt = this[ArticleTable.excerpt],
        url = "$baseUrl/aktuelles/$slug",
        coverImageUrl =
            ArticleCoverPolicy.publicCoverImageUrl(
                baseUrl = baseUrl,
                slug = slug,
                coverImageId = this[ArticleTable.coverImageId],
            ),
        publishedAt = embedFeedUtcFromSystem(publishedAt),
        byline = this[ArticleTable.byline],
        body = this[ArticleTable.body],
    )
}

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
 * contain `authorId`/`author`/`reviewedBy`/`id`/`rejectionReason`/`bylineKind`/`imported`, checked at the raw-STRING
 * level, not merely "the type doesn't have that field" -- see that test's own KDoc).
 */
internal fun Route.registerEmbedArticlesFeedRoutes(
    config: EmbedConfig,
    baseUrl: String,
    feedRateLimiter: FederationInboxRateLimiter,
    preflightRateLimiter: FederationInboxRateLimiter,
    // V1.9.105 -- own budget for the archive feed (see registerEmbedRoutes); default keeps older call sites compiling.
    archiveRateLimiter: FederationInboxRateLimiter =
        FederationInboxRateLimiter(maxRequests = 60, window = 1.minutes, maxTrackedKeys = 50_000),
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
                    // Defensive: see toEmbedArticleItem -- one inconsistent row is skipped (logged), the rest is still served.
                    ArticleStore.listPublishedNewestFirst(limit).mapNotNull { row -> row.toEmbedArticleItem(baseUrl) }
                }

            call.respondText(
                text = EMBED_ARTICLES_FEED_JSON.encodeToString(EmbedArticleListResponse.serializer(), EmbedArticleListResponse(items)),
                contentType = EMBED_ARTICLES_FEED_JSON_CONTENT_TYPE,
                status = HttpStatusCode.OK,
            )
        }
    }

    // V1.9.105 archive feed -- same CORS posture and ordering as the main feed (CORS, rate limit, nosniff), its OWN rate limiter, strict
    // parameters (invalid -> 400, never silently clamped), `hasMore` from a `limit + 1` read.
    get("/api/embed/v1/articles/archive") {
        call.withEmbedArticlesFeedErrorHandling {
            val cors = call.applyEmbedCors(allowlist = config.allowlist, allowInsecure = config.allowInsecureOrigins)
            if (cors is EmbedCorsResult.Rejected) {
                call.respondEmbedForbiddenOrigin()
                return@withEmbedArticlesFeedErrorHandling
            }
            val rateLimitKey = rateLimitKeyFor(remoteHost = call.request.origin.remoteHost)
            if (!archiveRateLimiter.checkAndRecord(rateLimitKey)) {
                call.response.header(HttpHeaders.RetryAfter, archiveRateLimiter.retryAfterSeconds(rateLimitKey).toString())
                call.respond(HttpStatusCode.TooManyRequests)
                return@withEmbedArticlesFeedErrorHandling
            }
            call.response.header("X-Content-Type-Options", "nosniff")

            val page =
                parseBoundedInt(
                    raw = call.request.queryParameters["page"],
                    default = 1,
                    min = 1,
                    max = EmbedArticlesFeedLimits.ARCHIVE_MAX_PAGE,
                )
            val limit =
                parseBoundedInt(
                    raw = call.request.queryParameters["limit"],
                    default = EmbedArticlesFeedLimits.ARCHIVE_DEFAULT_LIMIT,
                    min = 1,
                    max = EmbedArticlesFeedLimits.ARCHIVE_MAX_LIMIT,
                )
            if (page == null || limit == null) {
                call.respondText(
                    text = """{"error":"invalid_parameter"}""",
                    contentType = EMBED_ARTICLES_FEED_JSON_CONTENT_TYPE,
                    status = HttpStatusCode.BadRequest,
                )
                return@withEmbedArticlesFeedErrorHandling
            }

            // One short read-only transaction; the DTO projection happens outside it (no pool connection held while building strings).
            val rows = transaction { ArticleStore.listPublishedArchivePage(limit = limit, offset = (page - 1).toLong() * limit) }
            val hasMore = rows.size > limit
            val items = rows.take(limit).mapNotNull { row -> row.toEmbedArticleItem(baseUrl) }
            call.respondText(
                text =
                    EMBED_ARTICLES_FEED_JSON.encodeToString(
                        EmbedArticleArchiveResponse.serializer(),
                        EmbedArticleArchiveResponse(articles = items, page = page, limit = limit, hasMore = hasMore),
                    ),
                contentType = EMBED_ARTICLES_FEED_JSON_CONTENT_TYPE,
                status = HttpStatusCode.OK,
            )
        }
    }

    options("/api/embed/v1/articles/archive") {
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
