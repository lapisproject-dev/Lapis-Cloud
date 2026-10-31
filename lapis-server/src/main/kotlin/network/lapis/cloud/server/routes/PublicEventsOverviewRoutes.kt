package network.lapis.cloud.server.routes

import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.server.application.call
import io.ktor.server.plugins.origin
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import network.lapis.cloud.server.branding.ResolvedBranding
import network.lapis.cloud.server.db.DbClock
import network.lapis.cloud.server.db.generated.EventTable
import network.lapis.cloud.server.events.series.PublicSeriesSummary
import network.lapis.cloud.server.events.series.SeriesOccurrenceRef
import network.lapis.cloud.server.federation.FederationConfig
import network.lapis.cloud.server.federation.FederationInboxRateLimiter
import network.lapis.cloud.server.partner.PartnerDisplayEpoch
import network.lapis.cloud.server.partner.PartnerEventDisplay
import network.lapis.cloud.server.partner.PartnerEventView
import network.lapis.cloud.server.time.OrganizationTimeZone
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlin.uuid.Uuid

private val logger = KotlinLogging.logger {}

/** Hard cap on how many events `GET /veranstaltungen` ever lists -- no pagination in this wave, see [PublicOverviewHtml] class KDoc. */
private const val MAX_EVENTS = 50

/** Same short, memoized-render TTL as [PublicLandingRoutes] -- see that file's own KDoc "Body memoization" for the full rationale. */
private val EVENTS_OVERVIEW_CACHE_TTL = 30.seconds

/** The memo key: the language, the source filter and the display epoch (bumped by every block / activation change, V1.9.245). */
private data class EventsOverviewKey(
    val lang: PublicLanguage,
    val source: PublicOverviewHtml.EventSource,
    val epoch: Long,
)

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
    val cachedBody = ConcurrentHashMap<EventsOverviewKey, CachedEventsOverviewBody>()

    get("/veranstaltungen") {
        call.withPublicErrorHandling(baseUrl = baseUrl, branding = branding) {
            if (!readRateLimiter.checkAndRecord(rateLimitKeyFor(remoteHost = call.request.origin.remoteHost))) {
                call.respondPublicTooManyRequests(baseUrl = baseUrl, branding = branding, lang = call.resolvePublicLanguage())
                return@withPublicErrorHandling
            }
            // V1.9.245: `?quelle=alle|eigene`. An unknown or repeated value -- and the redundant `alle` -- redirects to the URL WITHOUT `quelle` ("all"), so every content has one URL.
            val sourceParam = call.request.queryParameters.getAll("quelle")
            val source =
                when (sourceParam?.singleOrNull()) {
                    "eigene" -> PublicOverviewHtml.EventSource.OWN
                    else -> PublicOverviewHtml.EventSource.ALL
                }
            val sourceValid = sourceParam == null || (sourceParam.size == 1 && sourceParam[0] == "eigene")
            if (!call.hasOnlyAllowedQueryParams(allowed = setOf("lang", "quelle")) ||
                call.publicLangNeedsCanonicalization() ||
                !sourceValid
            ) {
                val path =
                    if (sourceValid &&
                        source == PublicOverviewHtml.EventSource.OWN
                    ) {
                        "/veranstaltungen?quelle=eigene"
                    } else {
                        "/veranstaltungen"
                    }
                call.respondPublicCanonicalRedirect(
                    canonicalUrl = PublicChrome.languageUrl(baseUrl = baseUrl, currentPath = path, lang = call.resolvePublicLanguage()),
                )
                return@withPublicErrorHandling
            }
            val lang = call.resolvePublicLanguage()
            val body =
                renderCachedEventsBody(
                    cachedBody = cachedBody,
                    source = source,
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
    cachedBody: ConcurrentHashMap<EventsOverviewKey, CachedEventsOverviewBody>,
    source: PublicOverviewHtml.EventSource,
    lang: PublicLanguage,
    baseUrl: String,
    branding: ResolvedBranding,
    navAvailability: PublicNavAvailabilityProvider,
): String {
    val now = Clock.System.now()
    val epoch = PartnerDisplayEpoch.current()
    val key = EventsOverviewKey(lang = lang, source = source, epoch = epoch)
    cachedBody[key]?.let { cached -> if (cached.expiresAt > now) return cached.body }
    val nav = navAvailability.current()
    var partnerEvents: List<PartnerEventView> = emptyList()
    val items =
        transaction {
            val dbNow = DbClock.nowLocalDateTime()
            val wallNow = OrganizationTimeZone.wallNowOf(dbNow)
            val rows = PublicSeriesQueries.loadOverviewRepresentatives(wallNow = wallNow, limit = MAX_EVENTS)
            val stats = PublicSeriesQueries.loadSeriesStats(seriesIds = rows.mapNotNull { it[EventTable.seriesId] }, wallNow = wallNow)
            if (source == PublicOverviewHtml.EventSource.ALL) {
                partnerEvents = PartnerEventDisplay.loadVisible(nowUtc = dbNow, orgZone = OrganizationTimeZone.current())
            }
            rows.mapNotNull { row -> row.toEventItem(stats) }
        }
    val body =
        PublicOverviewHtml.eventsPage(
            items = items,
            baseUrl = baseUrl,
            branding = branding,
            lang = lang,
            nav = nav,
            partnerEvents = partnerEvents,
            source = source,
            // The filter only appears when it has something to filter: foreign events exist, or the visitor already chose.
            showFilter = partnerEvents.isNotEmpty() || source == PublicOverviewHtml.EventSource.OWN,
        )
    // Entries of an older epoch can never be hit again: drop them so the map stays small.
    cachedBody.keys.removeIf { it.epoch != epoch }
    cachedBody[key] = CachedEventsOverviewBody(body = body, expiresAt = now + EVENTS_OVERVIEW_CACHE_TTL)
    return body
}

private fun ResultRow.toEventItem(stats: Map<Uuid, PublicSeriesQueries.SeriesStats>): PublicOverviewHtml.EventItem? {
    val slug = this[EventTable.slug]
    if (!COVER_SLUG_PATTERN.matches(slug)) {
        logger.warn { "Skipping event ${this[EventTable.id]} on /veranstaltungen: slug does not match the expected pattern" }
        return null
    }
    val seriesStats = this[EventTable.seriesId]?.let { stats[it] }
    val teaser =
        seriesStats?.let {
            PublicSeriesSummary.teaser(
                rule = PublicSeriesSummary.parseOrNull(it.rrule),
                next =
                    SeriesOccurrenceRef(
                        eventId = this[EventTable.id],
                        slug = slug,
                        title = this[EventTable.title],
                        startsAt = this[EventTable.startsAt],
                    ),
                upcomingCount = it.upcomingCount,
                lastStartsAt = it.lastStartsAt,
            )
        }
    return PublicOverviewHtml.EventItem(title = this[EventTable.title], slug = slug, startsAt = this[EventTable.startsAt], series = teaser)
}
