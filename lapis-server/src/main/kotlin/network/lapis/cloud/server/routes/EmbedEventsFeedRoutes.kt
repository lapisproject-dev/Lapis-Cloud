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
import network.lapis.cloud.server.db.DbClock
import network.lapis.cloud.server.db.generated.EventTable
import network.lapis.cloud.server.embed.EmbedConfig
import network.lapis.cloud.server.embed.EmbedCorsResult
import network.lapis.cloud.server.embed.applyEmbedCors
import network.lapis.cloud.server.embed.respondEmbedForbiddenOrigin
import network.lapis.cloud.server.embed.respondEmbedPreflight
import network.lapis.cloud.server.events.EventCoverPolicy
import network.lapis.cloud.server.events.EventStore
import network.lapis.cloud.server.federation.FederationInboxRateLimiter
import org.jetbrains.exposed.v1.jdbc.transactions.transaction

private val logger = KotlinLogging.logger {}

private val EMBED_EVENTS_FEED_JSON = Json { explicitNulls = false }
private val EMBED_EVENTS_FEED_JSON_CONTENT_TYPE = ContentType.Application.Json.withParameter("charset", "utf-8")

/**
 * DoS-Guard-Konstante -- BEWUSST eigenständig, NICHT [EventIcsFeed.MAX_EVENTS] (500) wieder-
 * verwendet: ein Partner-Website-Widget zeigt realistisch die nächsten 5-20 Termine, niemals einen
 * vollständigen Kalender-Dump. Der niedrigere Deckel begrenzt zugleich den in [countOccupiedFor]
 * pro Zeile ausgeführten `EventStore.countOccupied`-Fan-out (siehe dessen KDoc "N+1").
 */
internal object EmbedEventsFeedLimits {
    const val MAX_EVENTS = 50
}

@Serializable
internal data class EmbedEventListItemDto(
    val slug: String,
    val title: String,
    val startsAt: String,
    val endsAt: String,
    val locationText: String?,
    val registrationUrl: String,
    val full: Boolean,
    val feeAmount: String,
    val feeCurrency: String,
    val coverImageUrl: String?,
)

@Serializable
internal data class EmbedEventListResponse(
    val events: List<EmbedEventListItemDto>,
)

/**
 * Welle V1.4.33 "Veranstaltungsliste als Embed-Widget" -- registers
 * `GET /api/embed/v1/events` and its `OPTIONS` preflight. Called from [registerEmbedRoutes] INSIDE
 * the `if (!config.enabled) return` gate, directly after [registerEmbedEventRoutes], same
 * registration discipline every other route in that function follows.
 *
 * **Read-only, public-by-design -- the one deliberate CORS-posture DEVIATION in this file, spelled
 * out explicitly so a future "harmonize the embed routes" refactor does not silently flip it:**
 * unlike [registerEmbedEventRoutes]/`registerEmbedDonationRoutes` (both write/money paths, where a
 * missing `Origin` header is ALSO rejected), this endpoint treats [EmbedCorsResult.NoOriginHeader]
 * as a NORMAL, 200-continuing case -- exactly the posture `/api/embed/v1/session`'s own KDoc calls
 * the "normal" case. A plain `curl`/server-to-server fetch with no `Origin` header carries zero
 * session/credential risk here: this route never reads a cookie, never writes anything, and never
 * echoes anything beyond what `/veranstaltung/{slug}` already serves unauthenticated. Only
 * [EmbedCorsResult.Rejected] (an `Origin` present but not allow-listed) 403s.
 *
 * **Scope inherited, not re-implemented**: the underlying query
 * ([EventIcsFeed.loadUpcomingPublicPublished]) already hard-codes
 * `visibility=PUBLIC AND status=PUBLISHED AND endsAt > now` -- the SAME filter this endpoint needs,
 * reused verbatim rather than re-implemented a second time (a second copy of a security-relevant
 * predicate could silently drift from the first).
 *
 * **`Cache-Control` stays `no-store`** -- [applyEmbedCors] itself sets `Cache-Control: no-store`
 * unconditionally, BEFORE this handler ever runs (see that function's own KDoc), and
 * `response.header(...)` APPENDS rather than replaces (the exact footgun every other file in this
 * package's KDoc warns about, e.g. `EmbedEventRoutes.kt`'s own "F-1"). A second, more permissive
 * `Cache-Control` value written here would not REPLACE that `no-store`, it would sit ALONGSIDE it as
 * a second value on the same header -- worse than either value alone. Making this endpoint properly
 * cacheable would require changing [applyEmbedCors] itself (a shared, security-reviewed helper used
 * by every other embed route), which is out of scope for this wave; `no-store` is kept, deferring a
 * dedicated cache-policy change to a follow-up wave.
 */
internal fun Route.registerEmbedEventsFeedRoutes(
    config: EmbedConfig,
    baseUrl: String,
    feedRateLimiter: FederationInboxRateLimiter,
    preflightRateLimiter: FederationInboxRateLimiter,
) {
    get("/api/embed/v1/events") {
        call.withEmbedEventsFeedErrorHandling {
            // 1. CORS -- see this file's own KDoc "Read-only, public-by-design": NoOriginHeader is
            // NOT rejected here, unlike the write-path embed routes.
            val cors = call.applyEmbedCors(allowlist = config.allowlist, allowInsecure = config.allowInsecureOrigins)
            if (cors is EmbedCorsResult.Rejected) {
                call.respondEmbedForbiddenOrigin()
                return@withEmbedEventsFeedErrorHandling
            }

            // 2. Rate limit -- a bare 429 + Retry-After, no JSON body needed on a GET with no
            // request body to reject (mirrors /api/embed/v1/session's own 429 handling).
            val rateLimitKey = rateLimitKeyFor(remoteHost = call.request.origin.remoteHost)
            if (!feedRateLimiter.checkAndRecord(rateLimitKey)) {
                val retryAfterSeconds = feedRateLimiter.retryAfterSeconds(rateLimitKey)
                call.response.header(HttpHeaders.RetryAfter, retryAfterSeconds.toString())
                call.respond(HttpStatusCode.TooManyRequests)
                return@withEmbedEventsFeedErrorHandling
            }

            // 3. nosniff -- set EXACTLY here, exactly once (see EmbedEventRoutes.kt's own "F-1").
            call.response.header("X-Content-Type-Options", "nosniff")

            // 4. Query + per-row projection, ONE short transaction (see EventIcsFeed.kt's own KDoc
            // "Nicht gestreamt" for the same split-outside-the-transaction reasoning).
            val now = DbClock.nowLocalDateTime()
            val items =
                transaction {
                    EventIcsFeed
                        .loadUpcomingPublicPublished(now = now, limit = EmbedEventsFeedLimits.MAX_EVENTS)
                        .map { row ->
                            val slug = row[EventTable.slug]
                            val eventId = row[EventTable.id]
                            // N+1 by design at MAX_EVENTS=50 -- not batched, see this file's own
                            // KDoc reference and EventStore.countOccupied's own KDoc. If this cap
                            // ever needs to scale up, a batched countOccupied-for-many-events query
                            // is the fix, not raising the cap.
                            val occupied = EventStore.countOccupied(eventId = eventId, now = now)
                            val capacity = row[EventTable.capacity]
                            EmbedEventListItemDto(
                                slug = slug,
                                title = row[EventTable.title],
                                startsAt = embedFeedUtc(row[EventTable.startsAt]),
                                endsAt = embedFeedUtc(row[EventTable.endsAt]),
                                locationText = row[EventTable.locationText],
                                registrationUrl = "$baseUrl/veranstaltung/$slug",
                                full = capacity != null && occupied >= capacity,
                                // BigDecimal.toPlainString(), NIEMALS bare toString() -- das kann
                                // Exponentialnotation ausgeben (derselbe Stolperstein, den
                                // EmbedAssets.widgetJs' eigene KDoc für einen anderen BigDecimal-Wert
                                // bereits dokumentiert).
                                feeAmount = row[EventTable.feeAmount].toPlainString(),
                                feeCurrency = row[EventTable.feeCurrency],
                                coverImageUrl =
                                    EventCoverPolicy.coverImageUrl(
                                        baseUrl = baseUrl,
                                        slug = slug,
                                        coverImageId = row[EventTable.coverImageId],
                                    ),
                            )
                        }
                }

            call.respondText(
                text = EMBED_EVENTS_FEED_JSON.encodeToString(EmbedEventListResponse.serializer(), EmbedEventListResponse(items)),
                contentType = EMBED_EVENTS_FEED_JSON_CONTENT_TYPE,
                status = HttpStatusCode.OK,
            )
        }
    }

    // Exact mirror of the other embed widgets' OPTIONS preflight shape, gated by the soft, every-
    // request page-rate-limiter idiom (never a strict per-write budget -- this endpoint has none).
    options("/api/embed/v1/events") {
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

/**
 * Same guarantee every other embed route family's own error handler establishes (see
 * `EmbedEventRoutes.withEmbedEventErrorHandling`'s KDoc) -- plain `try`/`catch`, NOT `runCatching`
 * (which would also swallow [CancellationException] on a client disconnect). No PII of any kind
 * ever reaches this endpoint's response or logs -- nothing to redact -- but a DB hiccup must still
 * never surface as Ktor's bare, header-less default 500.
 */
private suspend fun ApplicationCall.withEmbedEventsFeedErrorHandling(handler: suspend () -> Unit) {
    try {
        handler()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        logger.error(e) { "Unhandled exception in the embed events-feed handler (${request.path()})" }
        runCatching { respond(HttpStatusCode.InternalServerError) }
    }
}
