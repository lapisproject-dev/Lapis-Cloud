package network.lapis.cloud.server.routes

import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.plugins.origin
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import network.lapis.cloud.server.db.generated.EventTable
import network.lapis.cloud.server.events.EVENT_GUEST_NAME_SUFFIX
import network.lapis.cloud.server.events.EventCaptureLock
import network.lapis.cloud.server.events.EventCaptureRequirement
import network.lapis.cloud.server.events.EventOnlineJoin
import network.lapis.cloud.server.events.EventOnlineServices
import network.lapis.cloud.server.events.EventOnlineWindow
import network.lapis.cloud.server.events.EventStore
import network.lapis.cloud.server.federation.FederationInboxRateLimiter
import network.lapis.cloud.server.time.OrganizationTimeZone
import network.lapis.cloud.server.time.ServerClock
import network.lapis.cloud.shared.domain.EventCaptureAcknowledgmentInput
import network.lapis.cloud.shared.domain.EventCaptureLiveDto
import network.lapis.cloud.shared.domain.EventGuestJoinRequestDto
import network.lapis.cloud.shared.domain.EventGuestJoinResponseDto
import network.lapis.cloud.shared.domain.EventGuestNotice
import network.lapis.cloud.shared.domain.EventGuestNoticeCapture
import network.lapis.cloud.shared.domain.EventOnlineKind
import network.lapis.cloud.shared.domain.EventOnlineOutcome
import network.lapis.cloud.shared.domain.EventOnlinePhase
import network.lapis.cloud.shared.domain.EventOnlineStateDto
import network.lapis.cloud.shared.domain.EventStatus
import network.lapis.cloud.shared.domain.EventTicketCode
import network.lapis.cloud.shared.domain.EventVisibility
import network.lapis.cloud.shared.rpc.EventCaptureNoticeOutdatedException
import network.lapis.cloud.shared.rpc.EventCaptureNoticeRequiredException
import network.lapis.cloud.shared.rpc.EventCaptureTextMissingException
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.net.URI

// Privacy rule of this file: the logs never name an event, a guest name, a ticket code, a token or an IP.
private val logger = KotlinLogging.logger {}

/** The request body is a name, two notice fields and an optional ticket code -- anything bigger is not a legitimate request. */
internal const val MAX_EVENT_GUEST_BODY_BYTES = 4096

private val GUEST_JSON = Json { ignoreUnknownKeys = true }

/**
 * Welle V1.9.113 -- the two literal routes a person WITHOUT an account uses to take part in the online conference of a public event:
 *
 * - `POST /api/events/{slug}/online-guest` -- JSON body [EventGuestJoinRequestDto], JSON answer [EventGuestJoinResponseDto].
 * - `GET /api/events/{slug}/online-state` -- the token-free state of the window, for the guest screen ([EventOnlineStateDto]).
 *
 * **This is a new attack surface without an account, so every guard is on the request path, in this order**: a per-IP flood limit
 * (10/min, IPv6 reduced to its /64) and a per-event limit (200/hour) BEFORE anything is read; the `Origin` header must equal this
 * server's own origin (CSRF / foreign embedding -- a browser always sends it on a POST); `Content-Length` <= [MAX_EVENT_GUEST_BODY_BYTES]
 * and a bounded read; strict JSON; the notice acknowledgment must match [EventGuestNotice]. Every refusal for an unknown / hidden /
 * unauthorized event is the SAME `200 {"outcome":"FORBIDDEN"}` -- there is no 404 that would tell slugs apart. The name is normalized
 * (NFC, control and bidi characters removed, 1..60 characters) and gets the server-side suffix `" (Gast)"` so it cannot impersonate a
 * member; the LiveKit identity is random (`evg-<uuid>`), never the name. For an event that is NOT released for recording no row is written for the guest (no
 * identity, nothing to keep); for a released one (V1.9.156) a proof WITHOUT a name is written (`event_capture_acknowledgment`: the event, the consent version and
 * hash, a day and the SHA-256 of the identity).
 * Responses are `Cache-Control: no-store`.
 */
internal fun Route.registerEventOnlineGuestRoutes(
    baseUrl: String,
    eventOnline: EventOnlineServices,
    ipRateLimiter: FederationInboxRateLimiter,
    eventRateLimiter: FederationInboxRateLimiter,
    stateRateLimiter: FederationInboxRateLimiter,
) {
    val ownOrigin = originOf(baseUrl)

    get("/api/events/{slug}/online-state") {
        call.response.header(HttpHeaders.CacheControl, "no-store")
        if (!stateRateLimiter.checkAndRecord(rateLimitKeyFor(remoteHost = call.request.origin.remoteHost))) {
            call.respond(HttpStatusCode.TooManyRequests)
            return@get
        }
        val slug = call.parameters["slug"]
        val state = if (slug == null) forbiddenState() else loadState(slug)
        call.respondText(text = GUEST_JSON.encodeToString(state), contentType = ContentType.Application.Json)
    }

    post("/api/events/{slug}/online-guest") {
        call.response.header(HttpHeaders.CacheControl, "no-store")
        val remote = rateLimitKeyFor(remoteHost = call.request.origin.remoteHost)
        // 1. Flood gates -- BEFORE any body byte is read.
        if (!ipRateLimiter.checkAndRecord(remote)) {
            call.respond(HttpStatusCode.TooManyRequests)
            return@post
        }
        val slug = call.parameters["slug"]
        if (slug == null || slug.length > MAX_SLUG_LENGTH) {
            call.respondGuest(EventGuestJoinResponseDto(outcome = EventOnlineOutcome.FORBIDDEN))
            return@post
        }
        // 2. Origin: the page that sends this request must be this server's own page.
        val origin = call.request.headers[HttpHeaders.Origin]
        if (origin == null || originOf(origin) != ownOrigin) {
            call.respond(HttpStatusCode.Forbidden)
            return@post
        }
        // 3. Content type and size, before and while reading.
        if (call.request.headers[HttpHeaders.ContentType]?.startsWith("application/json", ignoreCase = true) != true) {
            call.respond(HttpStatusCode.UnsupportedMediaType)
            return@post
        }
        val declared = call.request.headers[HttpHeaders.ContentLength]?.toLongOrNull()
        if (declared == null || declared > MAX_EVENT_GUEST_BODY_BYTES) {
            call.respond(HttpStatusCode.PayloadTooLarge)
            return@post
        }
        val bytes = readCappedBody(call = call, maxBytes = MAX_EVENT_GUEST_BODY_BYTES)
        if (bytes == null) {
            call.respond(HttpStatusCode.PayloadTooLarge)
            return@post
        }
        val text = bytes.toString(Charsets.UTF_8)
        if (exceedsMaxJsonNestingDepth(text = text, maxDepth = MAX_JSON_DEPTH)) {
            call.respond(HttpStatusCode.BadRequest)
            return@post
        }
        val request =
            try {
                GUEST_JSON.decodeFromString<EventGuestJoinRequestDto>(text)
            } catch (e: Exception) {
                call.respond(HttpStatusCode.BadRequest)
                return@post
            }
        // 4. The notice the guest acknowledged must be one of the two current variants (an old or forged page is told to reload). Which of the two is
        // the right one for THIS event (the capture variant when it may be recorded) is checked by joinAccountless, once the event is known.
        val plainNotice = request.consentVersion == EventGuestNotice.VERSION && request.consentSha256 == EventGuestNotice.SHA256
        val captureNoticeOk =
            request.consentVersion == EventGuestNoticeCapture.VERSION && request.consentSha256 == EventGuestNoticeCapture.SHA256
        if (!plainNotice && !captureNoticeOk) {
            call.respond(HttpStatusCode.Conflict)
            return@post
        }
        val name = EventOnlineJoin.normalizeGuestName(request.name)
        if (name == null) {
            call.respond(HttpStatusCode.BadRequest)
            return@post
        }
        val join = eventOnline.join
        if (join == null) {
            call.respondGuest(EventGuestJoinResponseDto(outcome = EventOnlineOutcome.UNAVAILABLE))
            return@post
        }
        val ticketHash =
            request.ticketCode
                ?.takeIf { it.length <= MAX_TICKET_INPUT_LENGTH }
                ?.let { EventTicketCode.extractAndCanonicalize(it) }
                ?.let { sha256Hex(it.toByteArray(Charsets.US_ASCII)) }
        val eventId = transaction { EventStore.getEventBySlugOrNull(slug)?.get(EventTable.id) }
        if (eventId == null) {
            call.respondGuest(EventGuestJoinResponseDto(outcome = EventOnlineOutcome.FORBIDDEN))
            return@post
        }
        // 5. Per-event budget: counted only for a request that passed Origin/body/notice validation AND names an existing event, so
        // stray or forged traffic against a public slug cannot exhaust the budget for legitimate guests.
        if (!eventRateLimiter.checkAndRecord("event:$slug")) {
            call.respond(HttpStatusCode.TooManyRequests)
            return@post
        }
        val response =
            try {
                join.joinAccountless(
                    eventId = eventId,
                    displayName = name + EVENT_GUEST_NAME_SUFFIX,
                    ticketCodeSha256 = ticketHash,
                    guestNoticeVersion = request.consentVersion,
                    captureConsent = captureConsentOf(request),
                    now = ServerClock.nowInstant(),
                )
            } catch (e: EventCaptureNoticeRequiredException) {
                // V1.9.156: the page showed a different notice than this event needs (a release was granted, a text was published): reload the state.
                call.respond(HttpStatusCode.Conflict)
                return@post
            } catch (e: EventCaptureNoticeOutdatedException) {
                call.respond(HttpStatusCode.Conflict)
                return@post
            } catch (e: EventCaptureTextMissingException) {
                call.respond(HttpStatusCode.Conflict)
                return@post
            } catch (e: Exception) {
                logger.error { "event guest join failed (${e::class.simpleName})" }
                EventGuestJoinResponseDto(outcome = EventOnlineOutcome.UNAVAILABLE)
            }
        call.respondGuest(response)
    }
}

private const val MAX_SLUG_LENGTH = 120
private const val MAX_JSON_DEPTH = 8
private const val MAX_TICKET_INPUT_LENGTH = 400

private suspend fun io.ktor.server.application.ApplicationCall.respondGuest(response: EventGuestJoinResponseDto) {
    respondText(text = GUEST_JSON.encodeToString(response), contentType = ContentType.Application.Json)
}

/** Both fields or none: half a consent is no consent. */
private fun captureConsentOf(request: EventGuestJoinRequestDto): EventCaptureAcknowledgmentInput? {
    val version = request.captureConsentVersion
    val sha = request.captureConsentSha256
    return if (version != null && sha != null) EventCaptureAcknowledgmentInput(consentVersion = version, consentSha256 = sha) else null
}

private fun forbiddenState(): EventOnlineStateDto = EventOnlineStateDto(outcome = EventOnlineOutcome.FORBIDDEN)

/** The token-free state of an event's online window. Anything that is not a public, published Lapis conference answers FORBIDDEN. */
private fun loadState(slug: String): EventOnlineStateDto {
    val orgZone = OrganizationTimeZone.current()
    val row = transaction { EventStore.getEventBySlugOrNull(slug) } ?: return forbiddenState()
    if (row[EventTable.status] != EventStatus.PUBLISHED ||
        row[EventTable.visibility] != EventVisibility.PUBLIC ||
        row[EventTable.onlineKind] != EventOnlineKind.LAPIS_CONFERENCE
    ) {
        return forbiddenState()
    }
    val window = EventOnlineWindow.of(startsAt = row[EventTable.startsAt], endsAt = row[EventTable.endsAt], orgZone = orgZone)
    val outcome =
        when (EventOnlineWindow.phase(window = window, now = ServerClock.nowInstant())) {
            EventOnlinePhase.NOT_YET -> EventOnlineOutcome.NOT_YET
            EventOnlinePhase.ENDED -> EventOnlineOutcome.ENDED
            EventOnlinePhase.OPEN -> EventOnlineOutcome.JOINED
        }
    // V1.9.156: a released event shows the capture consent and the capture variant of the guest notice (never "Es wird nichts aufgezeichnet.").
    val capture = transaction { EventCaptureRequirement.applies(row) }
    val captureNotice = if (capture) EventCaptureRequirement.noticeDto() else null
    val captureLive =
        if (capture && outcome == EventOnlineOutcome.JOINED) {
            transaction {
                EventCaptureLock.roomIdOf(row[EventTable.id])?.let { EventCaptureLock.liveState(it) }
                    ?: EventCaptureLiveDto(recording = false, streaming = false)
            }
        } else {
            null
        }
    return EventOnlineStateDto(
        outcome = outcome,
        title = row[EventTable.title],
        opensAt = window.opensAt.toLocalDateTime(orgZone).toString(),
        closesAt = window.closesAt.toLocalDateTime(orgZone).toString(),
        requiresTicket = row[EventTable.registrationClosesAt] != null,
        noticeVersion = if (capture) EventGuestNoticeCapture.VERSION else EventGuestNotice.VERSION,
        noticeSha256 = if (capture) EventGuestNoticeCapture.SHA256 else EventGuestNotice.SHA256,
        captureNotice = captureNotice,
        captureLive = captureLive,
    )
}

/** `scheme://host[:port]` of [url], lower-cased; an unparsable value yields itself (which then never equals the own origin). */
private fun originOf(url: String): String =
    try {
        val uri = URI(url.trim())
        val scheme = uri.scheme?.lowercase()
        val host = uri.host?.lowercase()
        if (scheme == null || host == null) {
            url
        } else {
            val port = uri.port
            val defaultPort = (scheme == "https" && port == 443) || (scheme == "http" && port == 80)
            if (port == -1 || defaultPort) "$scheme://$host" else "$scheme://$host:$port"
        }
    } catch (e: Exception) {
        url
    }
