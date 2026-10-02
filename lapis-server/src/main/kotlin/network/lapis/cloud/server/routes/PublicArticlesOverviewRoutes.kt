package network.lapis.cloud.server.routes

import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.server.application.call
import io.ktor.server.plugins.origin
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import network.lapis.cloud.server.articles.ArticleStore
import network.lapis.cloud.server.branding.ResolvedBranding
import network.lapis.cloud.server.db.generated.ArticleTable
import network.lapis.cloud.server.federation.FederationConfig
import network.lapis.cloud.server.federation.FederationInboxRateLimiter
import network.lapis.cloud.server.time.OrganizationTimeZone
import network.lapis.cloud.server.time.ServerClock
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

private val logger = KotlinLogging.logger {}

/** Hard cap on how many articles `GET /aktuelles` ever lists -- no pagination in this wave, see [PublicOverviewHtml] class KDoc. */
private const val MAX_ARTICLES = 20

/** Same short, memoized-render TTL as [PublicLandingRoutes] -- see that file's own KDoc "Body memoization" for the full rationale. */
private val OVERVIEW_CACHE_TTL = 30.seconds

private data class CachedOverviewBody(
    val body: String,
    val expiresAt: Instant,
)

/**
 * Welle V1.9.11 "Öffentliche Icon-Navigation" -- `GET /aktuelles`, a public, `index,follow` overview
 * of every `PUBLISHED` article, titled/dated only (see [PublicOverviewHtml] class KDoc
 * "Datensparsamkeit"). Reuses the SAME shared plumbing every other public-HTML route family in this
 * package does ([withPublicErrorHandling]/[respondPublicCacheable]/[respondPublicTooManyRequests]/
 * [respondPublicCanonicalRedirect]/[hasOnlyAllowedQueryParams]/[HTML_CONTENT_TYPE]/[rateLimitKeyFor]),
 * never a second, driftable copy.
 *
 * **Ablauf** (same shape as [registerPublicLandingRoutes]'s own "Ablauf" KDoc): the whole handler body
 * runs inside [withPublicErrorHandling] -> an OWN, dedicated IP rate limiter ([readRateLimiter]) -> a
 * canonical-URL guard (`lang` is the only allowed query parameter) -> a short, per-[PublicLanguage]
 * TTL-memoized body (mirrors [PublicLandingRoutes]' own memoization, same rationale) -> on a cache
 * miss, ONE short `transaction {}` via [ArticleStore.listPublishedNewestFirst] -> transaction CLOSED
 * -> render OUTSIDE of it ([PublicOverviewHtml.articlesPage]) -> ETag/304.
 *
 * A row whose `slug`/`publishedAt` is `null`, or whose `slug` does not match [COVER_SLUG_PATTERN], is
 * SKIPPED (logged at `warn`, id only -- never title/slug, see [applyPublicPageHeaders] logging
 * discipline) rather than rendered -- defense in depth: these columns are nullable at the DB layer,
 * but a `PUBLISHED` article's application-level invariant is that both are always set.
 */
fun Route.registerPublicArticlesOverviewRoutes(
    readRateLimiter: FederationInboxRateLimiter,
    branding: ResolvedBranding,
    navAvailability: PublicNavAvailabilityProvider,
) {
    val baseUrl = FederationConfig.publicBaseUrl.trimEnd('/')
    // See PublicLandingRoutes class KDoc "Body memoization" -- a fresh holder PER registration call,
    // never a file-level singleton, keyed exclusively by the already-validated PublicLanguage enum.
    val cachedBody = ConcurrentHashMap<PublicLanguage, CachedOverviewBody>()

    get("/aktuelles") {
        call.withPublicErrorHandling(baseUrl = baseUrl, branding = branding) {
            if (!readRateLimiter.checkAndRecord(rateLimitKeyFor(remoteHost = call.request.origin.remoteHost))) {
                call.respondPublicTooManyRequests(baseUrl = baseUrl, branding = branding, lang = call.resolvePublicLanguage())
                return@withPublicErrorHandling
            }
            if (!call.hasOnlyAllowedQueryParams(allowed = setOf("lang")) || call.publicLangNeedsCanonicalization()) {
                call.respondPublicCanonicalRedirect(
                    canonicalUrl =
                        PublicChrome.languageUrl(baseUrl = baseUrl, currentPath = "/aktuelles", lang = call.resolvePublicLanguage()),
                )
                return@withPublicErrorHandling
            }
            val lang = call.resolvePublicLanguage()
            val body =
                renderCachedArticlesBody(
                    cachedBody = cachedBody,
                    lang = lang,
                    baseUrl = baseUrl,
                    branding = branding,
                    navAvailability = navAvailability,
                )
            call.respondPublicCacheable(
                body = body,
                contentType = HTML_CONTENT_TYPE,
                cacheControl = "public, max-age=300",
                imgSrcSelf = branding.logoAvailable,
            )
        }
    }
}

private fun renderCachedArticlesBody(
    cachedBody: ConcurrentHashMap<PublicLanguage, CachedOverviewBody>,
    lang: PublicLanguage,
    baseUrl: String,
    branding: ResolvedBranding,
    navAvailability: PublicNavAvailabilityProvider,
): String {
    val now = Clock.System.now()
    cachedBody[lang]?.let { cached -> if (cached.expiresAt > now) return cached.body }
    // Sampled BEFORE the transaction, never from inside it -- see PublicNavAvailabilityProvider.current KDoc "S2".
    val nav = navAvailability.current()
    val items =
        transaction {
            ArticleStore
                .listPublishedNewestFirst(limit = MAX_ARTICLES)
                .mapNotNull { row -> row.toArticleItem() }
        }
    val body = PublicOverviewHtml.articlesPage(items = items, baseUrl = baseUrl, branding = branding, lang = lang, nav = nav)
    cachedBody[lang] = CachedOverviewBody(body = body, expiresAt = now + OVERVIEW_CACHE_TTL)
    return body
}

private fun ResultRow.toArticleItem(): PublicOverviewHtml.ArticleItem? {
    val slug = this[ArticleTable.slug]
    val publishedAt = this[ArticleTable.publishedAt]
    if (slug == null || publishedAt == null || !COVER_SLUG_PATTERN.matches(slug)) {
        logger.warn { "Skipping article ${this[ArticleTable.id]} on /aktuelles: missing/invalid slug or publishedAt" }
        return null
    }
    // class-A stamp shown as a date: in the organization zone (V1.9.38)
    return PublicOverviewHtml.ArticleItem(
        title = this[ArticleTable.title],
        slug = slug,
        publishedAt = ServerClock.systemToWall(utc = publishedAt, orgZone = OrganizationTimeZone.current()),
    )
}
