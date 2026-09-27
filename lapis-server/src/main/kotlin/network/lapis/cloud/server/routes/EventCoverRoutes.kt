package network.lapis.cloud.server.routes

import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.PartData
import io.ktor.http.content.forEachPart
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.http.content.LocalFileContent
import io.ktor.server.plugins.origin
import io.ktor.server.request.formFieldLimit
import io.ktor.server.request.receiveMultipart
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readAvailable
import network.lapis.cloud.server.db.generated.EventTable
import network.lapis.cloud.server.events.CoverProcessingResult
import network.lapis.cloud.server.events.EventCoverImageProcessor
import network.lapis.cloud.server.events.EventCoverPolicy
import network.lapis.cloud.server.events.EventCoverStorage
import network.lapis.cloud.server.events.EventStore
import network.lapis.cloud.server.federation.FederationInboxRateLimiter
import network.lapis.cloud.server.security.isPrivileged
import network.lapis.cloud.server.security.requireRole
import network.lapis.cloud.server.security.resolveCurrentMember
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.EventCoverResultDto
import network.lapis.cloud.shared.domain.EventStatus
import network.lapis.cloud.shared.domain.EventVisibility
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.io.ByteArrayOutputStream
import java.net.URI
import kotlin.uuid.Uuid

private val logger = KotlinLogging.logger {}

private const val MAX_UPLOAD_WITH_MULTIPART_OVERHEAD_BYTES = EventCoverPolicy.MAX_UPLOAD_BYTES + 64 * 1024
private const val SNIFF_BYTES = 8
private val SLUG_PATTERN = Regex("^[a-z0-9-]{1,120}$")

/**
 * Welle "Veranstaltungs-Titelbild" (Event Cover Image) -- upload/remove/serve routes for a public
 * event's cover image. Registered UNCONDITIONALLY, alongside [registerEventPublicRoutes] (both
 * literal routes, "literal beats catch-all", same reasoning that file's own registration comment
 * gives) -- deliberately OUTSIDE [registerEmbedRoutes], because that function early-returns to
 * "admin status only" when `EmbedConfig.enabled` is `false`, which would otherwise 404 this route
 * on any instance that has not opted into the (unrelated) embed-widget feature.
 *
 * **NOT anonymous, despite the `/api/embed/v1/...` path prefix it shares with other embed-widget
 * endpoints purely for URL-namespace consistency**: upload/remove require a BOARD/ADMIN session,
 * same-origin, from the SPA -- deliberately NO [network.lapis.cloud.server.embed.applyEmbedCors]
 * and NO `OPTIONS` handler (unlike [registerEmbedAdminStatusRoute]), because `multipart/form-data`
 * is a "simple request" per the Fetch spec and needs no CORS preflight -- so the Origin/
 * `Sec-Fetch-Site` check below is the ONLY thing standing between this route and a cross-site form
 * submission, not merely a defense-in-depth extra.
 *
 * Security checklist (see the wave plan's own table for the full rationale):
 * - **CSRF**: `Origin`/`Sec-Fetch-Site` checked on every write, in addition to the session cookie's
 *   own `SameSite=Strict`.
 * - **DoS**: an early `Content-Length` rejection BEFORE any byte is read, [formFieldLimit], and the
 *   image processor's own decompression-bomb guard (dimensions checked before decode).
 * - **Path traversal**: the stored file name is always `<server-generated Uuid>.<jpg|png>` -- see
 *   [EventCoverStorage] KDoc.
 * - **Content-Type/XSS**: magic-byte sniffing only, full re-encode strips every byte of the
 *   original file (metadata AND any polyglot payload), never `image/svg+xml`.
 * - **Existence oracle**: the GET route below returns the IDENTICAL 404 for "unknown slug", "event
 *   not publicly visible", and "no cover image set" -- see that route's own comment.
 */
internal fun Route.registerEventCoverRoutes(
    storage: EventCoverStorage,
    baseUrl: String,
    writeRateLimiter: FederationInboxRateLimiter,
    readRateLimiter: FederationInboxRateLimiter,
) {
    val canonicalOrigin = runCatching { URI(baseUrl) }.getOrNull()

    post("/api/embed/v1/event/{slug}/cover") {
        if (!isSameOriginRequest(headers = call.request.headers, canonicalOrigin = canonicalOrigin)) {
            call.respond(HttpStatusCode.Forbidden, "Invalid origin")
            return@post
        }
        val current = resolveCurrentMember(call)
        current.requireRole(AccountRole.BOARD, AccountRole.ADMIN)

        if (!writeRateLimiter.checkAndRecord("member:${current.memberId}")) {
            call.response.header(HttpHeaders.RetryAfter, writeRateLimiter.retryAfterSeconds("member:${current.memberId}").toString())
            call.respond(HttpStatusCode.TooManyRequests, "Zu viele Anfragen -- bitte spaeter erneut versuchen.")
            return@post
        }

        val slug = call.parameters["slug"]
        if (slug == null || !SLUG_PATTERN.matches(slug)) {
            call.respond(HttpStatusCode.NotFound)
            return@post
        }
        val eventId =
            transaction { EventStore.getEventBySlugOrNull(slug)?.get(EventTable.id) }
                ?: run {
                    call.respond(HttpStatusCode.NotFound)
                    return@post
                }

        val contentLength = call.request.headers[HttpHeaders.ContentLength]?.toLongOrNull()
        if (contentLength != null && contentLength > MAX_UPLOAD_WITH_MULTIPART_OVERHEAD_BYTES) {
            call.respond(HttpStatusCode.PayloadTooLarge, "Max upload size is ${EventCoverPolicy.MAX_UPLOAD_BYTES} bytes")
            return@post
        }
        call.formFieldLimit = MAX_UPLOAD_WITH_MULTIPART_OVERHEAD_BYTES

        var uploadBytes: ByteArray? = null
        var tooLarge = false
        var fileItemCount = 0
        try {
            call.receiveMultipart().forEachPart { part ->
                when (part) {
                    is PartData.FileItem -> {
                        fileItemCount++
                        if (fileItemCount == 1) {
                            val buffer = ByteArrayOutputStream()
                            val channel: ByteReadChannel = part.provider()
                            val chunk = ByteArray(8192)
                            var total = 0L
                            while (true) {
                                val read = channel.readAvailable(chunk)
                                if (read == -1) break
                                total += read
                                if (total > EventCoverPolicy.MAX_UPLOAD_BYTES) {
                                    tooLarge = true
                                    break
                                }
                                buffer.write(chunk, 0, read)
                            }
                            if (!tooLarge) uploadBytes = buffer.toByteArray()
                        }
                        part.release()
                    }
                    else -> part.release()
                }
            }
        } catch (e: Exception) {
            logger.info(e) { "Event cover upload stream failed for event $eventId" }
            throw e
        }

        if (tooLarge) {
            call.respond(HttpStatusCode.PayloadTooLarge, "Max upload size is ${EventCoverPolicy.MAX_UPLOAD_BYTES} bytes")
            return@post
        }
        if (fileItemCount > 1) {
            call.respond(HttpStatusCode.BadRequest, "Request must contain exactly one file part")
            return@post
        }
        val bytes = uploadBytes
        if (bytes == null || bytes.isEmpty()) {
            call.respond(HttpStatusCode.BadRequest, "No file part in request")
            return@post
        }

        val format = EventCoverImageProcessor.sniff(bytes.copyOfRange(0, minOf(SNIFF_BYTES, bytes.size)))
        if (format == null) {
            call.respond(HttpStatusCode.UnsupportedMediaType, "Nur JPEG oder PNG werden unterstuetzt.")
            return@post
        }

        val result = EventCoverImageProcessor.process(bytes = bytes, format = format)
        when (result) {
            CoverProcessingResult.DimensionsTooLarge -> {
                call.respond(
                    HttpStatusCode.UnprocessableEntity,
                    "Das Bild ist zu gross (maximal ${EventCoverPolicy.MAX_EDGE_PX}px Kantenlaenge).",
                )
                return@post
            }
            CoverProcessingResult.DimensionsTooSmall -> {
                call.respond(
                    HttpStatusCode.UnprocessableEntity,
                    "Das Bild ist zu klein (mindestens ${EventCoverPolicy.MIN_LONG_EDGE_PX}x${EventCoverPolicy.MIN_SHORT_EDGE_PX}px, je nach Ausrichtung).",
                )
                return@post
            }
            CoverProcessingResult.Undecodable -> {
                call.respond(HttpStatusCode.UnprocessableEntity, "Die Bilddatei konnte nicht gelesen werden.")
                return@post
            }
            is CoverProcessingResult.Ok -> Unit
        }
        val ok = result as CoverProcessingResult.Ok

        val newId = Uuid.random()
        storage.write(id = newId, format = ok.format, bytes = ok.bytes)

        // Wraps the previous cover id in an Optional-shaped sealed result so "event not found" and
        // "found, previous cover was null" (both otherwise represented as a bare Kotlin `null`) stay
        // distinguishable -- see below.
        val setOutcome: SetCoverOutcome =
            try {
                transaction {
                    val locked = EventStore.lockEventForUpdate(eventId)
                    if (locked == null) {
                        SetCoverOutcome.EventNotFound
                    } else {
                        SetCoverOutcome.Set(EventStore.setCoverImageId(id = eventId, coverImageId = newId))
                    }
                }
            } catch (e: Exception) {
                storage.delete(newId)
                throw e
            }
        val previousId =
            when (setOutcome) {
                SetCoverOutcome.EventNotFound -> {
                    storage.delete(newId)
                    call.respond(HttpStatusCode.NotFound)
                    return@post
                }
                is SetCoverOutcome.Set -> setOutcome.previousCoverImageId
            }
        // May be `null` (no previous cover) -- `storage.delete` on a `null` id is not called at all.
        if (previousId != null) storage.delete(previousId)

        logger.info { "Event cover set: eventId=$eventId memberId=${current.memberId} action=cover.set bytes=${ok.bytes.size}" }

        val slugFresh = transaction { EventStore.getEventOrThrow(eventId)[EventTable.slug] }
        call.response.header(HttpHeaders.CacheControl, "no-store")
        call.respond(
            HttpStatusCode.OK,
            EventCoverResultDto(coverImageUrl = EventCoverPolicy.coverImageUrl(baseUrl = baseUrl, slug = slugFresh, coverImageId = newId)),
        )
    }

    delete("/api/embed/v1/event/{slug}/cover") {
        if (!isSameOriginRequest(headers = call.request.headers, canonicalOrigin = canonicalOrigin)) {
            call.respond(HttpStatusCode.Forbidden, "Invalid origin")
            return@delete
        }
        val current = resolveCurrentMember(call)
        current.requireRole(AccountRole.BOARD, AccountRole.ADMIN)

        if (!writeRateLimiter.checkAndRecord("member:${current.memberId}")) {
            call.response.header(HttpHeaders.RetryAfter, writeRateLimiter.retryAfterSeconds("member:${current.memberId}").toString())
            call.respond(HttpStatusCode.TooManyRequests, "Zu viele Anfragen -- bitte spaeter erneut versuchen.")
            return@delete
        }

        val slug = call.parameters["slug"]
        if (slug == null || !SLUG_PATTERN.matches(slug)) {
            call.respond(HttpStatusCode.NotFound)
            return@delete
        }
        val eventId =
            transaction { EventStore.getEventBySlugOrNull(slug)?.get(EventTable.id) }
                ?: run {
                    call.respond(HttpStatusCode.NotFound)
                    return@delete
                }

        val previousId =
            transaction {
                EventStore.lockEventForUpdate(eventId) ?: return@transaction null
                EventStore.setCoverImageId(id = eventId, coverImageId = null)
            }
        if (previousId != null) storage.delete(previousId)
        logger.info { "Event cover removed: eventId=$eventId memberId=${current.memberId} action=cover.remove" }

        call.response.header(HttpHeaders.CacheControl, "no-store")
        call.respond(HttpStatusCode.OK, EventCoverResultDto(coverImageUrl = null))
    }

    /**
     * Publicly reachable image bytes -- deliberately NOT under `/api/embed/v1/...`, but under the
     * same `/veranstaltung/{slug}/...` family [registerEventPublicRoutes] owns, so it is exactly
     * the URL an unauthenticated visitor's browser loads for `<img src>`/`og:image` (see
     * `EventPublicHtml`/`EventPublicRoutes`).
     *
     * **Existence-oracle discipline**: an unknown slug, a slug whose event is not PUBLIC+PUBLISHED
     * (checked anonymously), and a PUBLIC+PUBLISHED event with no cover set all return the exact
     * same bare 404 -- no response header or body may differ between them. A non-anonymous BOARD/
     * ADMIN caller (session resolved WITHOUT letting a resolution failure throw, so an anonymous
     * caller on a non-public event still gets the same 404, not a 401) may additionally view ANY
     * event's cover image, privately.
     */
    get("/veranstaltung/{slug}/bild") {
        if (!readRateLimiter.checkAndRecord(rateLimitKeyFor(remoteHost = call.request.origin.remoteHost))) {
            call.respond(HttpStatusCode.TooManyRequests)
            return@get
        }
        val slug = call.parameters["slug"]
        if (slug == null || !SLUG_PATTERN.matches(slug)) {
            call.respond(HttpStatusCode.NotFound)
            return@get
        }
        val row = transaction { EventStore.getEventBySlugOrNull(slug) }
        if (row == null) {
            call.respond(HttpStatusCode.NotFound)
            return@get
        }
        val isPublic = row[EventTable.visibility] == EventVisibility.PUBLIC && row[EventTable.status] == EventStatus.PUBLISHED
        val coverImageId = row[EventTable.coverImageId]

        if (isPublic) {
            val requestedVersion = call.request.queryParameters["v"]
            val currentVersion = coverImageId?.toString()?.take(8)
            if (coverImageId == null) {
                call.respond(HttpStatusCode.NotFound)
                return@get
            }
            // Known limitation (review finding, minor, not a blocker): the `immutable`/`max-age=
            // 31536000` branch only guards against the COVER IMAGE ITSELF changing while the URL's
            // `?v=` tag stays the same (impossible -- a changed cover always gets a new
            // `coverImageId`, hence a new `v`). It does NOT guard against the EVENT's visibility
            // later flipping from PUBLIC+PUBLISHED to MEMBERS_ONLY/DRAFT, or the cover being removed
            // entirely: a browser or shared cache (proxy/CDN) that already stored a versioned URL
            // under this header keeps serving those bytes for up to a year, even though this
            // handler would now correctly 404 a fresh request. The "nur für öffentliche
            // Veranstaltungen öffentlich abrufbar" guarantee above therefore only holds SERVER-SIDE
            // going forward, not retroactively for URLs already cached by a third party. Accepted
            // trade-off for this wave (cover images are not expected to carry sensitive content, and
            // the versioned-immutable pattern is what makes `og:image` previews on social platforms
            // reliable); if this needs tightening later, drop `public` (shared-cache-only exposure)
            // and/or shorten `max-age` instead of `immutable`.
            call.response.header(
                HttpHeaders.CacheControl,
                if (requestedVersion != null &&
                    requestedVersion == currentVersion
                ) {
                    "public, max-age=31536000, immutable"
                } else {
                    "public, max-age=300"
                },
            )
            call.respondCoverFile(storage = storage, coverImageId = coverImageId)
            return@get
        }

        // Not publicly visible: resolve a session WITHOUT letting a resolution failure throw --
        // an anonymous or non-privileged caller must see the identical 404 an unknown/private-
        // without-cover event gets, never a 401/403 (see method KDoc "Existence-oracle discipline").
        val current = runCatching { resolveCurrentMember(call) }.getOrNull()
        if (current == null || !current.isPrivileged || coverImageId == null) {
            call.respond(HttpStatusCode.NotFound)
            return@get
        }
        call.response.header(HttpHeaders.CacheControl, "private, no-store")
        call.respondCoverFile(storage = storage, coverImageId = coverImageId)
    }
}

private suspend fun ApplicationCall.respondCoverFile(
    storage: EventCoverStorage,
    coverImageId: Uuid,
) {
    val resolved = storage.resolve(coverImageId)
    if (resolved == null) {
        logger.warn { "Event cover file missing on disk for id=$coverImageId" }
        respond(HttpStatusCode.NotFound)
        return
    }
    val (file, format) = resolved
    response.header("X-Content-Type-Options", "nosniff")
    response.header("Content-Security-Policy", "default-src 'none'")
    response.header("Cross-Origin-Resource-Policy", "cross-origin")
    respond(LocalFileContent(file, format.contentType))
}

/**
 * `true` iff [headers] carry no evidence of a cross-origin request. A "simple request" (which
 * `multipart/form-data` is) needs no CORS preflight, so this check -- not CORS -- is the only CSRF
 * defense for this route family (see class KDoc). Deliberately conservative: a MISSING `Origin`
 * header is treated as same-origin (a same-origin `fetch`/form submit from a modern browser always
 * sends one; a same-origin plain navigation from a very old browser might not -- rejecting that
 * outright would be a usability regression with no attacker-relevant upside, since `SameSite=Strict`
 * on the session cookie already blocks the cross-site case even when `Origin` is absent). An
 * `Origin: null` (present but the literal string "null", as a sandboxed iframe or a local `file://`
 * page sends) and a `Sec-Fetch-Site` other than `same-origin` are both rejected outright.
 */
private fun isSameOriginRequest(
    headers: Headers,
    canonicalOrigin: URI?,
): Boolean {
    val secFetchSite = headers["Sec-Fetch-Site"]
    if (secFetchSite != null && secFetchSite != "same-origin") return false

    val origin = headers[HttpHeaders.Origin] ?: return true
    if (origin == "null") return false
    if (canonicalOrigin == null) return false
    val parsedOrigin = runCatching { URI(origin) }.getOrNull() ?: return false
    return parsedOrigin.scheme == canonicalOrigin.scheme &&
        parsedOrigin.host == canonicalOrigin.host &&
        effectivePort(parsedOrigin) == effectivePort(canonicalOrigin)
}

/** See the upload route's own comment for why this exists instead of a bare `Uuid?`. */
private sealed interface SetCoverOutcome {
    data object EventNotFound : SetCoverOutcome

    data class Set(
        val previousCoverImageId: Uuid?,
    ) : SetCoverOutcome
}

private fun effectivePort(uri: URI): Int =
    if (uri.port != -1) {
        uri.port
    } else {
        when (uri.scheme) {
            "https" -> 443
            "http" -> 80
            else -> -1
        }
    }
