package network.lapis.cloud.server.routes

import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.server.application.call
import io.ktor.server.plugins.origin
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import network.lapis.cloud.server.branding.ResolvedBranding
import network.lapis.cloud.server.db.DbClock
import network.lapis.cloud.server.db.generated.EventTable
import network.lapis.cloud.server.federation.FederationConfig
import network.lapis.cloud.server.federation.FederationInboxRateLimiter
import network.lapis.cloud.server.time.OrganizationTimeZone
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

private val logger = KotlinLogging.logger {}

/** Hard cap on how many events `GET /veranstaltungen` ever lists -- no pagination in this wave, see [PublicOverviewHtml] class KDoc. */
private const val MAX_EVENTS = 50

/** Same short, memoized-render TTL as [PublicLandingRoutes] -- see that file's own KDoc "Body memoization" for the full rationale. */
private val EVENTS_OVERVIEW_CACHE_TTL = 30.seconds

private data class CachedEventsOverviewBody(
    val body: String,
    val expiresAt: Instant,
)

/**
 * Welle V1.9.11 "Öffentliche Icon-Navigation" -- `GET /veranstaltungen`, a public, `index,follow`
 * overview of every upcoming `visibility=PUBLIC AND status=PUBLISHED` event, titled/dated only (see
 * [PublicOverviewHtml] class KDoc "Datensparsamkeit"). Reuses the SAME shared plumbing
 * [registerPublicArticlesOverviewRoutes] does -- see that file's own class KDoc "Ablauf" for the full
 * shape, identical here except the data source ([EventIcsFeed.loadUpcomingPublicPublished], the SAME
 * query `/veranstaltung.ics` already uses -- see that object's own KDoc for the visibility rule).
 *
 * `/veranstaltung.ics` and `/veranstaltung/{slug}` are UNTOUCHED by this wave -- this route only adds
 * a THIRD, human-readable entry point onto the exact same underlying data.
 */
fun Route.registerPublicEventsOverviewRoutes(
    readRateLimiter: FederationInboxRateLimiter,
    branding: ResolvedBranding,
    navAvailability: PublicNavAvailabilityProvider,
) {
    val baseUrl = FederationConfig.publicBaseUrl.trimEnd('/')
    val cachedBody = ConcurrentHashMap<PublicLanguage, CachedEventsOverviewBody>()

    get("/veranstaltungen") {
        call.withPublicErrorHandling(baseUrl = baseUrl, branding = branding) {
            if (!readRateLimiter.checkAndRecord(rateLimitKeyFor(remoteHost = call.request.origin.remoteHost))) {
                call.respondPublicTooManyRequests(baseUrl = baseUrl, branding = branding, lang = call.resolvePublicLanguage())
                return@withPublicErrorHandling
            }
            if (!call.hasOnlyAllowedQueryParams(allowed = setOf("lang")) || call.publicLangNeedsCanonicalization()) {
                call.respondPublicCanonicalRedirect(
                    canonicalUrl =
                        PublicChrome.languageUrl(baseUrl = baseUrl, currentPath = "/veranstaltungen", lang = call.resolvePublicLanguage()),
                )
                return@withPublicErrorHandling
            }
            val lang = call.resolvePublicLanguage()
            val body =
                renderCachedEventsBody(
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

private fun renderCachedEventsBody(
    cachedBody: ConcurrentHashMap<PublicLanguage, CachedEventsOverviewBody>,
    lang: PublicLanguage,
    baseUrl: String,
    branding: ResolvedBranding,
    navAvailability: PublicNavAvailabilityProvider,
): String {
    val now = Clock.System.now()
    cachedBody[lang]?.let { cached -> if (cached.expiresAt > now) return cached.body }
    val nav = navAvailability.current()
    val items =
        transaction {
            val dbNow = DbClock.nowLocalDateTime()
            EventIcsFeed
                .loadUpcomingPublicPublished(wallNow = OrganizationTimeZone.wallNowOf(dbNow), limit = MAX_EVENTS)
                .mapNotNull { row -> row.toEventItem() }
        }
    val body = PublicOverviewHtml.eventsPage(items = items, baseUrl = baseUrl, branding = branding, lang = lang, nav = nav)
    cachedBody[lang] = CachedEventsOverviewBody(body = body, expiresAt = now + EVENTS_OVERVIEW_CACHE_TTL)
    return body
}

private fun ResultRow.toEventItem(): PublicOverviewHtml.EventItem? {
    val slug = this[EventTable.slug]
    if (!COVER_SLUG_PATTERN.matches(slug)) {
        logger.warn { "Skipping event ${this[EventTable.id]} on /veranstaltungen: slug does not match the expected pattern" }
        return null
    }
    return PublicOverviewHtml.EventItem(title = this[EventTable.title], slug = slug, startsAt = this[EventTable.startsAt])
}
