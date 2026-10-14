package network.lapis.cloud.server.routes

import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.http.content.LocalFileContent
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import network.lapis.cloud.server.db.DbClock
import network.lapis.cloud.server.events.CoverImageFormat
import network.lapis.cloud.server.events.CoverImageLimits
import network.lapis.cloud.server.events.CoverProcessingResult
import network.lapis.cloud.server.events.EventCoverImageProcessor
import network.lapis.cloud.server.federation.FederationInboxRateLimiter
import network.lapis.cloud.server.rpc.VideoOverlaysOfferedStore
import network.lapis.cloud.server.rpc.requireConferenceEligibleMembership
import network.lapis.cloud.server.security.CurrentMember
import network.lapis.cloud.server.security.resolveCurrentMember
import network.lapis.cloud.server.videooverlays.VideoOverlayFormat
import network.lapis.cloud.server.videooverlays.VideoOverlayImagePolicy
import network.lapis.cloud.server.videooverlays.VideoOverlayStorage
import network.lapis.cloud.server.videooverlays.VideoOverlayStore
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.AuditAction
import network.lapis.cloud.shared.domain.VideoOverlayKind
import network.lapis.cloud.shared.domain.VideoOverlayRules
import network.lapis.cloud.shared.domain.VideoOverlayUploadError
import network.lapis.cloud.shared.domain.VideoOverlayUploadResultDto
import network.lapis.cloud.shared.rpc.ForbiddenException
import network.lapis.cloud.shared.rpc.UnauthenticatedException
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.net.URI
import java.security.MessageDigest
import kotlin.uuid.Uuid

private val logger = KotlinLogging.logger {}

private const val OVERLAY_SNIFF_BYTES = 8

/** Headroom the 413 pre-check allows on top of the file cap for the multipart framing -- see [multipartCap]. */
private fun uploadCapFor(kind: VideoOverlayKind): Long =
    when (kind) {
        VideoOverlayKind.LOGO -> VideoOverlayRules.LOGO_MAX_UPLOAD_BYTES
        VideoOverlayKind.FRAME -> VideoOverlayRules.FRAME_MAX_UPLOAD_BYTES
    }

/**
 * Welle V1.9.129 "Video-Overlays" -- the byte-carrying routes of the overlay library. Registered ALWAYS and before `staticFiles`.
 *
 * 1. `POST /api/video-overlays/{kind}?name=<urlencoded>` (`kind` = `LOGO` or `FRAME`) -- ADMIN upload. Order of checks: same-origin (the
 *    only CSRF defense -- multipart is a CORS "simple request") -> authenticated member -> role ADMIN -> per-actor rate limit -> kind and
 *    name -> `Content-Length` present and within the cap -> streamed single-file receive with the same cap -> magic-byte sniff (JPEG or
 *    PNG only; a frame must be PNG; **SVG, GIF, WebP and everything else are 415 -- `SvgCrestSanitizer` is deliberately NOT used**) ->
 *    [EventCoverImageProcessor.process] (header-dimension and decompression-bomb guards BEFORE decoding, a fresh metadata-free re-encode that
 *    also neutralizes polyglot payloads, PNG keeps its alpha) -> for a frame: the PNG must really contain transparent pixels -> write the
 *    file, then ONE transaction (library lock, 50-element limit, insert, audit) -> a failed commit deletes the file. Every answer is JSON
 *    with an enum code only, never free text.
 * 2. `GET /api/video-overlays/{token}` -- authenticated delivery (NOT public: the library is organization-internal, and an authenticated
 *    same-origin `fetch` needs no CORS). ADMIN always sees an element (preparation while the option is off); everyone else only while the
 *    option is on AND the member may enter conferences. Existence-oracle discipline: unknown or malformed token, deleted element, missing
 *    file, option off and a not-eligible member are ALL the same bare 404 with the same headers. No `Access-Control-*` header on purpose.
 */
internal fun Route.registerVideoOverlayRoutes(
    storage: VideoOverlayStorage,
    baseUrl: String,
    uploadRateLimiter: FederationInboxRateLimiter,
    readRateLimiter: FederationInboxRateLimiter,
) {
    val canonicalOrigin = runCatching { URI(baseUrl) }.getOrNull()

    post("/api/video-overlays/{kind}") {
        if (!isSameOriginRequest(headers = call.request.headers, canonicalOrigin = canonicalOrigin)) {
            call.respondOverlayError(status = HttpStatusCode.Forbidden, error = VideoOverlayUploadError.INVALID_REQUEST)
            return@post
        }
        val current: CurrentMember =
            try {
                resolveCurrentMember(call)
            } catch (_: UnauthenticatedException) {
                call.respond(HttpStatusCode.Unauthorized)
                return@post
            }
        if (current.role != AccountRole.ADMIN) {
            call.respondOverlayError(status = HttpStatusCode.Forbidden, error = VideoOverlayUploadError.FORBIDDEN)
            return@post
        }
        val rateKey = "actor:${current.memberId}"
        if (!uploadRateLimiter.checkAndRecord(rateKey)) {
            call.response.header(HttpHeaders.RetryAfter, uploadRateLimiter.retryAfterSeconds(rateKey).toString())
            call.respondOverlayError(status = HttpStatusCode.TooManyRequests, error = VideoOverlayUploadError.RATE_LIMITED)
            return@post
        }
        val kind = runCatching { VideoOverlayKind.valueOf(call.parameters["kind"].orEmpty()) }.getOrNull()
        if (kind == null) {
            call.respondOverlayError(status = HttpStatusCode.BadRequest, error = VideoOverlayUploadError.INVALID_REQUEST)
            return@post
        }
        val name = VideoOverlayRules.normalizeName(call.request.queryParameters["name"].orEmpty())
        if (name == null) {
            call.respondOverlayError(status = HttpStatusCode.BadRequest, error = VideoOverlayUploadError.INVALID_NAME)
            return@post
        }
        val cap = uploadCapFor(kind)
        val declared = call.request.headers[HttpHeaders.ContentLength]?.toLongOrNull()
        if (declared == null || declared > multipartCap(cap)) {
            call.respondOverlayError(status = HttpStatusCode.PayloadTooLarge, error = VideoOverlayUploadError.FILE_TOO_LARGE)
            return@post
        }
        val bytes =
            when (val received = call.receiveSingleUploadBytes(cap)) {
                SingleUploadReceive.TooLarge -> {
                    call.respondOverlayError(status = HttpStatusCode.PayloadTooLarge, error = VideoOverlayUploadError.FILE_TOO_LARGE)
                    return@post
                }
                SingleUploadReceive.MultipleParts, SingleUploadReceive.NoFile -> {
                    call.respondOverlayError(status = HttpStatusCode.BadRequest, error = VideoOverlayUploadError.INVALID_REQUEST)
                    return@post
                }
                is SingleUploadReceive.Ok -> received.bytes
            }
        val sniffed = EventCoverImageProcessor.sniff(bytes.copyOfRange(0, minOf(OVERLAY_SNIFF_BYTES, bytes.size)))
        if (sniffed == null) {
            call.respondOverlayError(status = HttpStatusCode.UnsupportedMediaType, error = VideoOverlayUploadError.UNSUPPORTED_FORMAT)
            return@post
        }
        if (kind == VideoOverlayKind.FRAME && sniffed != CoverImageFormat.PNG) {
            call.respondOverlayError(status = HttpStatusCode.UnsupportedMediaType, error = VideoOverlayUploadError.FRAME_NOT_PNG)
            return@post
        }
        if (kind == VideoOverlayKind.FRAME && !VideoOverlayImagePolicy.pngHasAlpha(bytes)) {
            // Structural pre-check on the UPLOAD: an RGB PNG cannot be a frame. (The pixel scan below catches an alpha channel that is opaque.)
            call.respondOverlayError(status = HttpStatusCode.UnprocessableEntity, error = VideoOverlayUploadError.FRAME_NEEDS_ALPHA)
            return@post
        }
        val limits = if (kind == VideoOverlayKind.LOGO) CoverImageLimits.VIDEO_OVERLAY_LOGO else CoverImageLimits.VIDEO_OVERLAY_FRAME
        val processed =
            when (val outcome = EventCoverImageProcessor.process(bytes = bytes, format = sniffed, limits = limits)) {
                is CoverProcessingResult.DimensionsTooSmall -> {
                    val aspectIsTheCause =
                        kind == VideoOverlayKind.FRAME &&
                            outcome.width >= VideoOverlayRules.FRAME_MIN_WIDTH_PX &&
                            outcome.height >= VideoOverlayRules.FRAME_MIN_HEIGHT_PX
                    val error = if (aspectIsTheCause) VideoOverlayUploadError.FRAME_WRONG_ASPECT else VideoOverlayUploadError.TOO_SMALL
                    call.respondOverlayError(status = HttpStatusCode.UnprocessableEntity, error = error)
                    return@post
                }
                CoverProcessingResult.DimensionsTooLarge -> {
                    call.respondOverlayError(
                        status = HttpStatusCode.UnprocessableEntity,
                        error = VideoOverlayUploadError.DIMENSIONS_TOO_LARGE,
                    )
                    return@post
                }
                CoverProcessingResult.Undecodable -> {
                    call.respondOverlayError(status = HttpStatusCode.UnprocessableEntity, error = VideoOverlayUploadError.UNDECODABLE)
                    return@post
                }
                is CoverProcessingResult.Ok -> outcome
            }
        if (kind == VideoOverlayKind.FRAME) {
            val transparent =
                EventCoverImageProcessor.withDecodePermit {
                    withContext(Dispatchers.IO) { VideoOverlayImagePolicy.hasTransparentPixel(processed.bytes) }
                }
            if (!transparent) {
                call.respondOverlayError(status = HttpStatusCode.UnprocessableEntity, error = VideoOverlayUploadError.FRAME_NEEDS_ALPHA)
                return@post
            }
        }

        val format = if (processed.format == CoverImageFormat.PNG) VideoOverlayFormat.PNG else VideoOverlayFormat.JPG
        val id = Uuid.random()
        val token = VideoOverlayStore.newAccessToken()
        val sha256 = MessageDigest.getInstance("SHA-256").digest(processed.bytes).joinToString("") { "%02x".format(it) }
        val now = DbClock.nowLocalDateTime()
        storage.write(id = id, format = format, bytes = processed.bytes)
        val committed: OverlayUploadCommit =
            try {
                transaction {
                    VideoOverlayStore.lockLibrary()
                    if (VideoOverlayStore.count() >= VideoOverlayRules.MAX_ITEMS) return@transaction OverlayUploadCommit.LibraryFull
                    val row =
                        VideoOverlayStore.insert(
                            id = id,
                            kind = kind,
                            name = name,
                            format = format,
                            width = processed.width,
                            height = processed.height,
                            sizeBytes = processed.bytes.size.toLong(),
                            sha256 = sha256,
                            token = token,
                            now = now,
                        )
                    VideoOverlayStore.recordAudit(
                        actorMemberId = current.memberId,
                        actorRole = current.role,
                        entityId = id,
                        action = AuditAction.CREATE,
                        before = null,
                        after = VideoOverlayStore.snapshotJson(row),
                        now = now,
                    )
                    OverlayUploadCommit.Stored(VideoOverlayStore.toItem(row))
                }
            } catch (e: Exception) {
                storage.delete(id)
                throw e
            }
        when (committed) {
            OverlayUploadCommit.LibraryFull -> {
                storage.delete(id)
                call.respondOverlayError(status = HttpStatusCode.Conflict, error = VideoOverlayUploadError.LIBRARY_FULL)
                return@post
            }
            is OverlayUploadCommit.Stored -> {
                // Sizes only -- never the name, the token or any byte of the file.
                logger.info { "Video overlay stored: kind=$kind ${processed.width}x${processed.height} bytes=${processed.bytes.size}" }
                call.respond(HttpStatusCode.Created, VideoOverlayUploadResultDto(ok = true, item = committed.item))
            }
        }
    }

    get("/api/video-overlays/{token}") {
        // Every defensive header goes on BEFORE the existence decision, so a hit and every kind of miss are built identically.
        call.response.header("X-Content-Type-Options", "nosniff")
        call.response.header("Referrer-Policy", "no-referrer")
        call.response.header("Cross-Origin-Resource-Policy", "same-origin")
        call.response.header("Content-Security-Policy", "default-src 'none'; sandbox")
        val current: CurrentMember =
            try {
                resolveCurrentMember(call)
            } catch (_: UnauthenticatedException) {
                call.response.header(HttpHeaders.CacheControl, "no-store")
                call.respond(HttpStatusCode.Unauthorized)
                return@get
            }
        if (!readRateLimiter.checkAndRecord("member:${current.memberId}")) {
            call.response.header(HttpHeaders.CacheControl, "no-store")
            call.respond(HttpStatusCode.TooManyRequests)
            return@get
        }
        val servable =
            call.parameters["token"]?.let { token ->
                transaction {
                    val visible =
                        current.role == AccountRole.ADMIN ||
                            (VideoOverlaysOfferedStore.load() && isConferenceEligible(current))
                    if (visible) VideoOverlayStore.findByToken(token) else null
                }
            }
        val file = servable?.let { storage.resolve(id = it.id, format = it.format) }
        if (servable == null || file == null) {
            call.respondVideoOverlayNotFound()
            return@get
        }
        call.response.header(HttpHeaders.ContentDisposition, "inline; filename=\"overlay.${servable.format.extension}\"")
        // Set exactly once, here -- `response.header` APPENDS. The token is new on every upload, so the bytes behind a URL never change.
        call.response.header(HttpHeaders.CacheControl, "private, max-age=86400, immutable")
        call.respond(LocalFileContent(file, ContentType.parse(servable.format.contentType)))
    }
}

/** The ONE miss response of the delivery route -- a bare 404, identical for every reason. */
internal suspend fun ApplicationCall.respondVideoOverlayNotFound() {
    response.header(HttpHeaders.CacheControl, "no-store")
    respond(HttpStatusCode.NotFound)
}

private fun isConferenceEligible(current: CurrentMember): Boolean =
    try {
        requireConferenceEligibleMembership(memberId = current.memberId)
        true
    } catch (_: ForbiddenException) {
        false
    }

private suspend fun ApplicationCall.respondOverlayError(
    status: HttpStatusCode,
    error: VideoOverlayUploadError,
) {
    response.header(HttpHeaders.CacheControl, "no-store")
    respond(status, VideoOverlayUploadResultDto(ok = false, error = error))
}

private sealed interface OverlayUploadCommit {
    data object LibraryFull : OverlayUploadCommit

    class Stored(
        val item: network.lapis.cloud.shared.domain.VideoOverlayItemDto,
    ) : OverlayUploadCommit
}
