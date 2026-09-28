package network.lapis.cloud.server.routes

import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.plugins.origin
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import network.lapis.cloud.server.db.generated.EventTable
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
import java.net.URI
import kotlin.uuid.Uuid

private val logger = KotlinLogging.logger {}

/** Welle V1.4.36 -- alias so this file's own read below stays readable after `SLUG_PATTERN` moved to [COVER_SLUG_PATTERN] in `CoverUploadSupport.kt`. */
private val SLUG_PATTERN = COVER_SLUG_PATTERN

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
 * - **DoS**: an early `Content-Length` rejection BEFORE any byte is read, a form-field byte-size
 *   limit (see `CoverUploadSupport.receiveSingleCoverUpload`), and the image processor's own
 *   decompression-bomb guard (dimensions checked before decode).
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
        if (contentLength != null && contentLength > COVER_UPLOAD_MAX_BYTES_WITH_MULTIPART_OVERHEAD) {
            call.respond(HttpStatusCode.PayloadTooLarge, "Max upload size is ${EventCoverPolicy.MAX_UPLOAD_BYTES} bytes")
            return@post
        }

        // Welle V1.4.36 -- multipart-receive/sniff/process extracted (byte-identical behavior) into
        // CoverUploadSupport.kt so ArticleCoverRoutes can reuse it; see that file's own KDoc.
        val ok = call.receiveSingleCoverUpload() ?: return@post

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

/** See the upload route's own comment for why this exists instead of a bare `Uuid?`. */
private sealed interface SetCoverOutcome {
    data object EventNotFound : SetCoverOutcome

    data class Set(
        val previousCoverImageId: Uuid?,
    ) : SetCoverOutcome
}
