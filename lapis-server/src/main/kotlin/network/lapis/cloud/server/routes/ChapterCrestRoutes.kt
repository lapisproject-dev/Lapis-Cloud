package network.lapis.cloud.server.routes

import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.http.content.LocalFileContent
import io.ktor.server.plugins.origin
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import network.lapis.cloud.server.chapters.ChapterCrestPolicy
import network.lapis.cloud.server.chapters.ChapterCrestStore
import network.lapis.cloud.server.db.DbClock
import network.lapis.cloud.server.db.generated.RegionalChapterTable
import network.lapis.cloud.server.events.CoverImageLimits
import network.lapis.cloud.server.events.CoverProcessingResult
import network.lapis.cloud.server.events.EventCoverImageProcessor
import network.lapis.cloud.server.events.EventCoverStorage
import network.lapis.cloud.server.federation.FederationInboxRateLimiter
import network.lapis.cloud.server.security.CurrentMember
import network.lapis.cloud.server.security.resolveCurrentMember
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.ChapterCrestUploadError
import network.lapis.cloud.shared.domain.ChapterCrestUploadResultDto
import network.lapis.cloud.shared.domain.RegionalChapterPublicRules
import network.lapis.cloud.shared.rpc.UnauthenticatedException
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.net.URI
import kotlin.uuid.Uuid

private val logger = KotlinLogging.logger {}

private const val CREST_SNIFF_BYTES = 8

/**
 * Welle V1.9.20 "Öffentliche Seiten" -- the byte-carrying routes of the regional chapter crest.
 * Registered ALWAYS (not inside `registerEmbedRoutes`, which returns early when
 * `LAPIS_EMBED_ENABLED` is false) and before `staticFiles`.
 *
 * 1. `POST /api/regional-chapters/{chapterId}/crest` -- BOARD/ADMIN upload. Order of checks:
 *    same-origin (the only CSRF defense -- multipart is a CORS "simple request") -> authenticated
 *    member -> role -> per-actor rate limit -> `Content-Length` cap -> streamed single-file receive
 *    with the same cap -> magic-byte sniff (JPEG/PNG only -- SVG, GIF, WebP are rejected) ->
 *    [EventCoverImageProcessor.process] with [CoverImageLimits.CHAPTER_CREST] (header-dimension and
 *    decompression-bomb guards BEFORE decoding, then a fresh, metadata-free re-encode that also
 *    neutralizes polyglot payloads; PNG keeps its alpha channel) -> chapter lock (an unknown id is a
 *    404, decided only AFTER the role check, so the status code reveals nothing to a non-officer)
 *    -> write the file, commit the new token, THEN delete the previous file (a failed commit deletes
 *    the new file instead). Every answer is JSON with an enum code only, never free text.
 * 2. `GET /public/chapter-crests/{token}` -- public delivery. Existence-oracle discipline: rate
 *    limit per IP first, then the token pattern, then the database; unknown token, malformed token,
 *    removed crest and missing file are ALL the same bare 404 with the same headers. Headers:
 *    `nosniff`, `Content-Disposition: inline`, `CSP default-src 'none'; sandbox`,
 *    `Cross-Origin-Resource-Policy: cross-origin`, `Referrer-Policy: no-referrer`, and (hit only)
 *    `Cache-Control: public, max-age=300`. Deliberately NO `applyEmbedCors`: a crest is an `<img>`
 *    source and needs no CORS -- and a CORS grant for organization data is exactly what the allowlist
 *    is not for. The token is new on every upload, so a replaced crest's URL changes.
 */
internal fun Route.registerChapterCrestRoutes(
    storage: EventCoverStorage,
    baseUrl: String,
    uploadRateLimiter: FederationInboxRateLimiter,
    publicReadRateLimiter: FederationInboxRateLimiter,
) {
    val canonicalOrigin = runCatching { URI(baseUrl) }.getOrNull()

    post("/api/regional-chapters/{chapterId}/crest") {
        if (!isSameOriginRequest(headers = call.request.headers, canonicalOrigin = canonicalOrigin)) {
            call.respondCrestError(status = HttpStatusCode.Forbidden, error = ChapterCrestUploadError.INVALID_REQUEST)
            return@post
        }
        val current: CurrentMember =
            try {
                resolveCurrentMember(call)
            } catch (_: UnauthenticatedException) {
                call.respond(HttpStatusCode.Unauthorized)
                return@post
            }
        if (current.role != AccountRole.BOARD && current.role != AccountRole.ADMIN) {
            call.respondCrestError(status = HttpStatusCode.Forbidden, error = ChapterCrestUploadError.FORBIDDEN)
            return@post
        }
        val rateKey = "actor:${current.memberId}"
        if (!uploadRateLimiter.checkAndRecord(rateKey)) {
            call.response.header(HttpHeaders.RetryAfter, uploadRateLimiter.retryAfterSeconds(rateKey).toString())
            call.respondCrestError(status = HttpStatusCode.TooManyRequests, error = ChapterCrestUploadError.RATE_LIMITED)
            return@post
        }
        val chapterId = runCatching { Uuid.parse(call.parameters["chapterId"].orEmpty()) }.getOrNull()
        if (chapterId == null) {
            call.respondCrestError(status = HttpStatusCode.NotFound, error = ChapterCrestUploadError.NOT_FOUND)
            return@post
        }
        val declared = call.request.headers[HttpHeaders.ContentLength]?.toLongOrNull()
        if (declared == null || declared > multipartCap(RegionalChapterPublicRules.CREST_MAX_UPLOAD_BYTES)) {
            call.respondCrestError(status = HttpStatusCode.PayloadTooLarge, error = ChapterCrestUploadError.FILE_TOO_LARGE)
            return@post
        }
        val bytes =
            when (val received = call.receiveSingleUploadBytes(RegionalChapterPublicRules.CREST_MAX_UPLOAD_BYTES)) {
                SingleUploadReceive.TooLarge -> {
                    call.respondCrestError(status = HttpStatusCode.PayloadTooLarge, error = ChapterCrestUploadError.FILE_TOO_LARGE)
                    return@post
                }
                SingleUploadReceive.MultipleParts, SingleUploadReceive.NoFile -> {
                    call.respondCrestError(status = HttpStatusCode.BadRequest, error = ChapterCrestUploadError.INVALID_REQUEST)
                    return@post
                }
                is SingleUploadReceive.Ok -> received.bytes
            }
        val format = EventCoverImageProcessor.sniff(bytes.copyOfRange(0, minOf(CREST_SNIFF_BYTES, bytes.size)))
        if (format == null) {
            call.respondCrestError(status = HttpStatusCode.UnsupportedMediaType, error = ChapterCrestUploadError.UNSUPPORTED_FORMAT)
            return@post
        }
        val processed =
            when (val outcome = EventCoverImageProcessor.process(bytes = bytes, format = format, limits = CoverImageLimits.CHAPTER_CREST)) {
                CoverProcessingResult.DimensionsTooSmall -> {
                    call.respondCrestError(status = HttpStatusCode.UnprocessableEntity, error = ChapterCrestUploadError.TOO_SMALL)
                    return@post
                }
                CoverProcessingResult.DimensionsTooLarge -> {
                    call.respondCrestError(
                        status = HttpStatusCode.UnprocessableEntity,
                        error = ChapterCrestUploadError.DIMENSIONS_TOO_LARGE,
                    )
                    return@post
                }
                CoverProcessingResult.Undecodable -> {
                    call.respondCrestError(status = HttpStatusCode.UnprocessableEntity, error = ChapterCrestUploadError.UNDECODABLE)
                    return@post
                }
                is CoverProcessingResult.Ok -> outcome
            }

        val imageId = Uuid.random()
        storage.write(id = imageId, format = processed.format, bytes = processed.bytes)
        val token = ChapterCrestStore.newPublicToken()
        val now = DbClock.nowLocalDateTime()
        val result: CrestCommit =
            try {
                transaction {
                    val row = ChapterCrestStore.lockChapter(chapterId) ?: return@transaction CrestCommit.ChapterGone
                    val hadCrest = row[RegionalChapterTable.crestImageId] != null
                    val hasDescription = !row[RegionalChapterTable.description].isNullOrBlank()
                    val previous =
                        ChapterCrestStore.setCrest(chapterId = chapterId, imageId = imageId, format = processed.format, token = token)
                    ChapterCrestStore.recordAudit(
                        actorMemberId = current.memberId,
                        actorRole = current.role,
                        chapterId = chapterId,
                        name = row[RegionalChapterTable.name],
                        before = hasDescription to hadCrest,
                        after = hasDescription to true,
                        now = now,
                    )
                    CrestCommit.Stored(previousImageId = previous)
                }
            } catch (e: Exception) {
                storage.delete(imageId)
                throw e
            }
        when (result) {
            CrestCommit.ChapterGone -> {
                storage.delete(imageId)
                call.respondCrestError(status = HttpStatusCode.NotFound, error = ChapterCrestUploadError.NOT_FOUND)
                return@post
            }
            is CrestCommit.Stored -> result.previousImageId?.let { storage.delete(it) }
        }
        logger.info { "Chapter crest stored: chapterId=$chapterId actor=${current.memberId} bytes=${processed.bytes.size}" }
        call.response.header(HttpHeaders.CacheControl, "no-store")
        call.respond(HttpStatusCode.OK, ChapterCrestUploadResultDto())
    }

    get("/public/chapter-crests/{token}") {
        // Rate limit per client IP FIRST -- before the token is even looked at, let alone the database.
        if (!publicReadRateLimiter.checkAndRecord(rateLimitKeyFor(remoteHost = call.request.origin.remoteHost))) {
            call.respond(HttpStatusCode.TooManyRequests)
            return@get
        }
        // Every defensive header goes on BEFORE the existence decision, so a hit and every kind of
        // miss are built identically (only the Cache-Control of a hit, Content-Type and body differ).
        call.response.header("X-Content-Type-Options", "nosniff")
        call.response.header("Referrer-Policy", "no-referrer")
        call.response.header("Cross-Origin-Resource-Policy", "cross-origin")
        call.response.header("Content-Security-Policy", "default-src 'none'; sandbox")

        val token = call.parameters["token"]
        if (token == null || !ChapterCrestPolicy.PUBLIC_TOKEN_PATTERN.matches(token)) {
            call.respondChapterCrestNotFound()
            return@get
        }
        val servable = transaction { ChapterCrestStore.findServable(token) }
        val resolved = servable?.let { (imageId, _) -> storage.resolve(imageId) }
        if (resolved == null) {
            call.respondChapterCrestNotFound()
            return@get
        }
        val (file, format) = resolved
        call.response.header(HttpHeaders.ContentDisposition, "inline")
        // Set exactly once, here -- `response.header` APPENDS.
        call.response.header(HttpHeaders.CacheControl, "public, max-age=300")
        call.respond(LocalFileContent(file, format.contentType))
    }
}

/** The ONE miss response of the public route -- a bare 404, identical for every reason. */
internal suspend fun ApplicationCall.respondChapterCrestNotFound() {
    respond(HttpStatusCode.NotFound)
}

private suspend fun ApplicationCall.respondCrestError(
    status: HttpStatusCode,
    error: ChapterCrestUploadError,
) {
    response.header(HttpHeaders.CacheControl, "no-store")
    respond(status, ChapterCrestUploadResultDto(error = error))
}

private sealed interface CrestCommit {
    data object ChapterGone : CrestCommit

    class Stored(
        val previousImageId: Uuid?,
    ) : CrestCommit
}
