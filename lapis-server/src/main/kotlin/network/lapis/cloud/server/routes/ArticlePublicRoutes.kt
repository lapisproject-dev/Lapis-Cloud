package network.lapis.cloud.server.routes

import io.github.oshai.kotlinlogging.KotlinLogging
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
import kotlinx.coroutines.CancellationException
import kotlinx.datetime.toInstant
import network.lapis.cloud.server.articles.ArticleCoverPolicy
import network.lapis.cloud.server.articles.ArticleMarkdown
import network.lapis.cloud.server.articles.ArticlePolicy
import network.lapis.cloud.server.articles.ArticleStore
import network.lapis.cloud.server.articles.EmbedLinkHost
import network.lapis.cloud.server.branding.ResolvedBranding
import network.lapis.cloud.server.db.generated.ArticleTable
import network.lapis.cloud.server.embed.EmbedConfig
import network.lapis.cloud.server.events.EventCoverStorage
import network.lapis.cloud.server.federation.FederationInboxRateLimiter
import network.lapis.cloud.server.time.OrganizationTimeZone
import network.lapis.cloud.server.time.ServerClock
import org.jetbrains.exposed.v1.jdbc.transactions.transaction

private val logger = KotlinLogging.logger {}

private val ARTICLE_SLUG_PATTERN = ArticlePolicy.PUBLIC_SLUG_PATTERN

/**
 * Welle V1.4.36 "Nachrichten-/Artikel-Modul, Folgewelle" -- the unauthenticated public reading
 * surface: `GET /aktuelles/{slug}` (the article page itself) and `GET /aktuelles/{slug}/bild`
 * (its cover image, always registered together, same "one family, one route function"
 * convention [registerEventPublicRoutes]/[registerEventCoverRoutes] establish for events even
 * though those two ARE split across two files there for a different, unrelated reason -- see
 * that file's own KDoc "registered UNCONDITIONALLY, alongside").
 *
 * **Always-on, NOT gated behind `LAPIS_EMBED_ENABLED`** (unlike [registerEmbedArticlesFeedRoutes],
 * see that function's own KDoc "Q5") -- this is this server's own primary public reading surface
 * for a published article, not an opt-in partner-website integration.
 *
 * **Existence-oracle discipline, both routes**: an unknown slug, a slug whose article is not
 * `PUBLISHED` (`DRAFT`/`SUBMITTED`/`REJECTED`, the latter covering both "genuinely rejected" and
 * "depubliziert"), and a malformed slug that fails [ARTICLE_SLUG_PATTERN] all render the IDENTICAL
 * 404 -- same status, same body, same headers. `Cache-Control: no-store` on the page route (never
 * `Cache-Control: no-store` would otherwise let a depublished article's PAGE stay browser-cached;
 * the cover-image route's OWN cache policy mirrors [registerEventCoverRoutes]' versioned-immutable
 * pattern instead, see below).
 *
 * **Slowloris rule**: every transaction below is a single short read; `ArticleMarkdown.render` was
 * already run and stored at approve-time (`ArticleTable.body` is raw Markdown, but the RENDERED
 * HTML this page serves is produced once here, outside the transaction, from that already-committed
 * row -- same "read row, close transaction, do the slow work after" split [registerEventPublicRoutes]
 * establishes).
 */
internal fun Route.registerArticlePublicRoutes(
    baseUrl: String,
    branding: ResolvedBranding,
    navAvailability: PublicNavAvailabilityProvider,
    embedConfig: EmbedConfig,
    coverStorage: EventCoverStorage,
    pageRateLimiter: FederationInboxRateLimiter,
    coverReadRateLimiter: FederationInboxRateLimiter,
) {
    val frameAncestors = embedFrameAncestors(embedConfig)

    /** The per-request context -- always the FIRST thing a handler builds, never inside a transaction (see [PublicNavAvailabilityProvider.current]). */
    fun ApplicationCall.pageContext(): ArticlePageContext =
        articlePageContext(baseUrl = baseUrl, branding = branding, navAvailability = navAvailability, frameAncestors = frameAncestors)

    get("/aktuelles/{slug}") {
        val ctx = call.pageContext()
        call.withArticlePublicErrorHandling(ctx = ctx) {
            if (!pageRateLimiter.checkAndRecord(rateLimitKeyFor(remoteHost = call.request.origin.remoteHost))) {
                call.respondArticleTooManyRequests(ctx)
                return@withArticlePublicErrorHandling
            }
            val slug = call.parameters["slug"]
            if (slug == null || !ARTICLE_SLUG_PATTERN.matches(slug)) {
                call.respondArticleNotFound(ctx)
                return@withArticlePublicErrorHandling
            }
            val row = transaction { ArticleStore.getPublishedBySlugOrNull(slug) }
            if (row == null) {
                call.respondArticleNotFound(ctx)
                return@withArticlePublicErrorHandling
            }
            val publishedAt =
                row[ArticleTable.publishedAt] ?: run {
                    // Defensive: a PUBLISHED row without `publishedAt` cannot happen by construction
                    // (`ArticleService.approveArticle` always sets it in the same UPDATE that flips the
                    // status), but this route must never throw on a data inconsistency -- 404, same as
                    // every other "this shouldn't happen" branch in this file.
                    call.respondArticleNotFound(ctx)
                    return@withArticlePublicErrorHandling
                }
            val view =
                ArticlePublicHtml.View(
                    title = row[ArticleTable.title],
                    slug = slug,
                    excerpt = row[ArticleTable.excerpt],
                    renderedBodyHtml =
                        ArticleMarkdown.render(
                            markdown = row[ArticleTable.body],
                            embedHost = if (ctx.isEmbed) EmbedLinkHost.fromBaseUrl(baseUrl) else null,
                        ),
                    coverImageUrl =
                        ArticleCoverPolicy.publicCoverImageUrl(
                            baseUrl = baseUrl,
                            slug = slug,
                            coverImageId = row[ArticleTable.coverImageId],
                        ),
                    // class-A stamp shown as a date: in the organization zone (V1.9.38); the ISO form below stays the UTC instant
                    publishedAt = ServerClock.systemToWall(utc = publishedAt, orgZone = OrganizationTimeZone.current()),
                    publishedAtIso = publishedAt.toInstant(ServerClock.zone).toString(),
                    byline = row[ArticleTable.byline],
                )
            call.response.header(HttpHeaders.CacheControl, "no-store")
            call.applyPageModeHeaders(isEmbed = ctx.isEmbed, frameAncestors = ctx.frameAncestors)
            call.respondText(
                text = ArticlePublicHtml.articlePage(ctx = ctx, view = view),
                contentType = HTML_CONTENT_TYPE,
            )
        }
    }

    /**
     * See class KDoc "Existence-oracle discipline". No privileged/authenticated bypass here at all
     * (unlike [registerEventCoverRoutes]' own public cover route) -- a BOARD/ADMIN reviewer uses the
     * AUTHENTICATED `/api/articles/{id}/cover` route from [registerArticleCoverRoutes] instead, this
     * one is exclusively the public, unauthenticated path.
     */
    get("/aktuelles/{slug}/bild") {
        if (!coverReadRateLimiter.checkAndRecord(rateLimitKeyFor(remoteHost = call.request.origin.remoteHost))) {
            call.respond(HttpStatusCode.TooManyRequests)
            return@get
        }
        val slug = call.parameters["slug"]
        if (slug == null || !ARTICLE_SLUG_PATTERN.matches(slug)) {
            call.respond(HttpStatusCode.NotFound)
            return@get
        }
        val coverImageId = transaction { ArticleStore.getCoverImageIdForPublishedSlug(slug) }
        if (coverImageId == null) {
            call.respond(HttpStatusCode.NotFound)
            return@get
        }
        val requestedVersion = call.request.queryParameters["v"]
        val currentVersion = coverImageId.toString().take(8)
        call.response.header(
            HttpHeaders.CacheControl,
            if (requestedVersion != null && requestedVersion == currentVersion) {
                "public, max-age=31536000, immutable"
            } else {
                "public, max-age=300"
            },
        )
        call.respondCoverFile(storage = coverStorage, coverImageId = coverImageId)
    }
}

/** Every HTML response (page, 404, 429, 500) is `no-store` and carries the mode-dependent security headers of [applyPageModeHeaders]. */
private suspend fun ApplicationCall.respondArticleHtml(
    ctx: ArticlePageContext,
    text: String,
    status: HttpStatusCode,
) {
    response.header(HttpHeaders.CacheControl, "no-store")
    applyPageModeHeaders(isEmbed = ctx.isEmbed, frameAncestors = ctx.frameAncestors)
    respondText(text = text, contentType = HTML_CONTENT_TYPE, status = status)
}

private suspend fun ApplicationCall.respondArticleNotFound(ctx: ArticlePageContext) =
    respondArticleHtml(ctx = ctx, text = ArticlePublicHtml.notFoundPage(ctx), status = HttpStatusCode.NotFound)

private suspend fun ApplicationCall.respondArticleTooManyRequests(ctx: ArticlePageContext) =
    respondArticleHtml(ctx = ctx, text = ArticlePublicHtml.tooManyRequestsPage(ctx), status = HttpStatusCode.TooManyRequests)

private suspend fun ApplicationCall.respondArticleServerError(ctx: ArticlePageContext) =
    respondArticleHtml(ctx = ctx, text = ArticlePublicHtml.serverErrorPage(ctx), status = HttpStatusCode.InternalServerError)

/** Same guarantee `EventPublicRoutes`' own error handler establishes -- plain `try`/`catch`, NOT `runCatching` (would also swallow [CancellationException] on a client disconnect); logs the PATH only, never the full URI/query string. */
internal suspend fun ApplicationCall.withArticlePublicErrorHandling(
    ctx: ArticlePageContext,
    handler: suspend () -> Unit,
) {
    try {
        handler()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        logger.error(e) { "Unhandled exception in a public article handler (${request.path()})" }
        runCatching { respondArticleServerError(ctx) }
    }
}
