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
import kotlinx.datetime.TimeZone
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
import network.lapis.cloud.server.events.EventText
import network.lapis.cloud.server.federation.FederationInboxRateLimiter
import network.lapis.cloud.server.time.OrganizationTimeZone
import network.lapis.cloud.shared.domain.EventStatus
import org.jetbrains.exposed.v1.core.ResultRow
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

    /** V1.9.82 archive feed: at most 100 pages of at most 50 entries (5000 reachable entries); OFFSET paging, no total count. */
    const val PAST_MAX_PAGE = 100
    const val PAST_MAX_LIMIT = 50
    const val PAST_DEFAULT_LIMIT = 20
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
    /** V1.9.82 -- plain text (never HTML/Markdown), paragraphs separated by an empty line. Always present in the feed. */
    val description: String? = null,
    /** V1.9.82 -- public teaser, single line. Omitted when not set. */
    val summary: String? = null,
    /** V1.9.82 -- alt text of the cover image. Only present when [coverImageUrl] is. */
    val coverImageAlt: String? = null,
    /** V1.9.82 -- only when the administrator ticked "online link public", the link is https and the event is not cancelled. */
    val onlineUrl: String? = null,
)

/**
 * V1.9.82 archive feed item: the same public fields as [EmbedEventListItemDto], but deliberately WITHOUT `full` (an ended event has
 * no seats to offer) and WITHOUT `onlineUrl` (a past event's meeting link is never published). Its own type so `full` stays a
 * mandatory field of the main feed's wire format.
 */
@Serializable
internal data class EmbedPastEventItemDto(
    val slug: String,
    val title: String,
    val startsAt: String,
    val endsAt: String,
    val locationText: String?,
    val registrationUrl: String,
    val feeAmount: String,
    val feeCurrency: String,
    val coverImageUrl: String?,
    val description: String? = null,
    val summary: String? = null,
    val coverImageAlt: String? = null,
)

@Serializable
internal data class EmbedEventListResponse(
    val events: List<EmbedEventListItemDto>,
)

@Serializable
internal data class EmbedPastEventListResponse(
    val events: List<EmbedPastEventItemDto>,
    val page: Int,
    val limit: Int,
    val hasMore: Boolean,
)

/** The privacy-relevant projection both feed endpoints share -- ONE place decides which event fields leave the server. No person data of any kind. */
internal class EmbedEventCommonFields(
    val slug: String,
    val title: String,
    val startsAt: String,
    val endsAt: String,
    val locationText: String?,
    val registrationUrl: String,
    val feeAmount: String,
    val feeCurrency: String,
    val coverImageUrl: String?,
    val description: String,
    val summary: String?,
    val coverImageAlt: String?,
)

internal fun ResultRow.toEmbedEventCommonFields(
    baseUrl: String,
    orgZone: TimeZone,
): EmbedEventCommonFields {
    val slug = this[EventTable.slug]
    val coverImageUrl =
        EventCoverPolicy.coverImageUrl(baseUrl = baseUrl, slug = slug, coverImageId = this[EventTable.coverImageId])
    return EmbedEventCommonFields(
        slug = slug,
        title = this[EventTable.title],
        startsAt = embedFeedUtc(dt = this[EventTable.startsAt], zone = orgZone),
        endsAt = embedFeedUtc(dt = this[EventTable.endsAt], zone = orgZone),
        locationText = this[EventTable.locationText],
        registrationUrl = "$baseUrl/veranstaltung/$slug",
        // BigDecimal.toPlainString(), NIEMALS bare toString() -- das kann Exponentialnotation ausgeben (derselbe Stolperstein, den
        // EmbedAssets.widgetJs' eigene KDoc für einen anderen BigDecimal-Wert bereits dokumentiert).
        feeAmount = this[EventTable.feeAmount].toPlainString(),
        feeCurrency = this[EventTable.feeCurrency],
        coverImageUrl = coverImageUrl,
        description = EventText.normalizeMultiline(this[EventTable.description]),
        summary = this[EventTable.summary]?.let { EventText.normalizeSingleLine(it) }?.takeIf { it.isNotEmpty() },
        coverImageAlt =
            if (coverImageUrl !=
                null
            ) {
                this[EventTable.coverImageAlt]?.let { EventText.normalizeSingleLine(it) }?.takeIf { it.isNotEmpty() }
            } else {
                null
            },
    )
}

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
    // V1.9.82 -- the archive feed's own budget (see EmbedRoutes' `eventsPastFeedRateLimiter`).
    pastFeedRateLimiter: FederationInboxRateLimiter,
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
            val orgZone = OrganizationTimeZone.current()
            val items =
                transaction {
                    EventIcsFeed
                        .loadUpcomingPublicPublished(
                            wallNow = OrganizationTimeZone.wallNowOf(now),
                            limit = EmbedEventsFeedLimits.MAX_EVENTS,
                        ).map { row ->
                            val eventId = row[EventTable.id]
                            // N+1 by design at MAX_EVENTS=50 -- not batched, see this file's own
                            // KDoc reference and EventStore.countOccupied's own KDoc. If this cap
                            // ever needs to scale up, a batched countOccupied-for-many-events query
                            // is the fix, not raising the cap.
                            val occupied = EventStore.countOccupied(eventId = eventId, now = now)
                            val capacity = row[EventTable.capacity]
                            val common = row.toEmbedEventCommonFields(baseUrl = baseUrl, orgZone = orgZone)
                            val onlineUrl =
                                row[EventTable.onlineUrl]?.trim()?.takeIf {
                                    row[EventTable.onlineUrlPublic] &&
                                        row[EventTable.status] != EventStatus.CANCELLED &&
                                        EventText.isHttpsUrl(it)
                                }
                            EmbedEventListItemDto(
                                slug = common.slug,
                                title = common.title,
                                startsAt = common.startsAt,
                                endsAt = common.endsAt,
                                locationText = common.locationText,
                                registrationUrl = common.registrationUrl,
                                full = capacity != null && occupied >= capacity,
                                feeAmount = common.feeAmount,
                                feeCurrency = common.feeCurrency,
                                coverImageUrl = common.coverImageUrl,
                                description = common.description,
                                summary = common.summary,
                                coverImageAlt = common.coverImageAlt,
                                onlineUrl = onlineUrl,
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

    // V1.9.82 archive feed -- same CORS posture and ordering as the main feed (CORS, rate limit, nosniff), its OWN rate limiter so paging
    // through the archive never uses up the main feed's budget.
    get("/api/embed/v1/events/past") {
        call.withEmbedEventsFeedErrorHandling {
            val cors = call.applyEmbedCors(allowlist = config.allowlist, allowInsecure = config.allowInsecureOrigins)
            if (cors is EmbedCorsResult.Rejected) {
                call.respondEmbedForbiddenOrigin()
                return@withEmbedEventsFeedErrorHandling
            }
            val rateLimitKey = rateLimitKeyFor(remoteHost = call.request.origin.remoteHost)
            if (!pastFeedRateLimiter.checkAndRecord(rateLimitKey)) {
                call.response.header(HttpHeaders.RetryAfter, pastFeedRateLimiter.retryAfterSeconds(rateLimitKey).toString())
                call.respond(HttpStatusCode.TooManyRequests)
                return@withEmbedEventsFeedErrorHandling
            }
            call.response.header("X-Content-Type-Options", "nosniff")

            val page =
                parseBoundedInt(
                    raw = call.request.queryParameters["page"],
                    default = 1,
                    min = 1,
                    max = EmbedEventsFeedLimits.PAST_MAX_PAGE,
                )
            val limit =
                parseBoundedInt(
                    raw = call.request.queryParameters["limit"],
                    default = EmbedEventsFeedLimits.PAST_DEFAULT_LIMIT,
                    min = 1,
                    max = EmbedEventsFeedLimits.PAST_MAX_LIMIT,
                )
            if (page == null || limit == null) {
                call.respondText(
                    text = """{"error":"invalid_parameter"}""",
                    contentType = EMBED_EVENTS_FEED_JSON_CONTENT_TYPE,
                    status = HttpStatusCode.BadRequest,
                )
                return@withEmbedEventsFeedErrorHandling
            }

            val now = DbClock.nowLocalDateTime()
            val orgZone = OrganizationTimeZone.current()
            // One short read-only transaction; the DTO projection happens outside it (no Pool connection held while building strings).
            val rows =
                transaction {
                    EmbedEventsQueries.loadPastPublicPublished(
                        wallNow = OrganizationTimeZone.wallNowOf(now),
                        limit = limit,
                        offset = (page - 1).toLong() * limit,
                    )
                }
            val hasMore = rows.size > limit
            val items =
                rows.take(limit).map { row ->
                    val common = row.toEmbedEventCommonFields(baseUrl = baseUrl, orgZone = orgZone)
                    EmbedPastEventItemDto(
                        slug = common.slug,
                        title = common.title,
                        startsAt = common.startsAt,
                        endsAt = common.endsAt,
                        locationText = common.locationText,
                        registrationUrl = common.registrationUrl,
                        feeAmount = common.feeAmount,
                        feeCurrency = common.feeCurrency,
                        coverImageUrl = common.coverImageUrl,
                        description = common.description,
                        summary = common.summary,
                        coverImageAlt = common.coverImageAlt,
                    )
                }
            call.respondText(
                text =
                    EMBED_EVENTS_FEED_JSON.encodeToString(
                        EmbedPastEventListResponse.serializer(),
                        EmbedPastEventListResponse(events = items, page = page, limit = limit, hasMore = hasMore),
                    ),
                contentType = EMBED_EVENTS_FEED_JSON_CONTENT_TYPE,
                status = HttpStatusCode.OK,
            )
        }
    }

    options("/api/embed/v1/events/past") {
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

/** Strictly decimal digits only: `null` (absent) yields [default], anything non-numeric or outside [min]..[max] yields `null` = invalid. */
private fun parseBoundedInt(
    raw: String?,
    default: Int,
    min: Int,
    max: Int,
): Int? {
    if (raw == null) return default
    if (raw.isEmpty() || raw.length > 9 || !raw.all { it in '0'..'9' }) return null
    return raw.toInt().takeIf { it in min..max }
}
