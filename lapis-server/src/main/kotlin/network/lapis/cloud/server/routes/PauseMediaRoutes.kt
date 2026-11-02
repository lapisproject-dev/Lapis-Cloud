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
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import network.lapis.cloud.server.audit.AuditLogRecorder
import network.lapis.cloud.server.conference.pausefeed.AudioSniffer
import network.lapis.cloud.server.conference.pausefeed.MusicProcessingResult
import network.lapis.cloud.server.conference.pausefeed.PauseImageProcessor
import network.lapis.cloud.server.conference.pausefeed.PauseImageResult
import network.lapis.cloud.server.conference.pausefeed.PauseMediaDb
import network.lapis.cloud.server.conference.pausefeed.PauseMediaKind
import network.lapis.cloud.server.conference.pausefeed.PauseMediaRow
import network.lapis.cloud.server.conference.pausefeed.PauseMediaStore
import network.lapis.cloud.server.conference.pausefeed.PauseMusicProcessor
import network.lapis.cloud.server.conference.pausefeed.sanitizeMusicLabel
import network.lapis.cloud.server.db.DbClock
import network.lapis.cloud.server.federation.FederationInboxRateLimiter
import network.lapis.cloud.server.rpc.ORGANIZATION_SETTINGS_ID
import network.lapis.cloud.server.security.CurrentMember
import network.lapis.cloud.server.security.resolveCurrentMember
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.AuditAction
import network.lapis.cloud.shared.domain.AuditEntityType
import network.lapis.cloud.shared.domain.PauseMediaResultDto
import network.lapis.cloud.shared.domain.PauseMediaRules
import network.lapis.cloud.shared.domain.PauseMediaUploadError
import network.lapis.cloud.shared.rpc.UnauthenticatedException
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.io.File
import java.net.URI
import kotlin.uuid.Uuid

private val logger = KotlinLogging.logger {}

private const val SNIFF_BYTES = 16

/**
 * V1.9.255 -- the byte-carrying routes of the pause picture and the pause music (what the SERVER sends to the stream targets while the room
 * pause holds the live stream). Registered ALWAYS and before `staticFiles`. **ADMIN only, for every method** (the same circle as the
 * organization settings and the video overlay library); everybody else gets the same bare 403.
 *
 *  * `POST /api/conference/pause-media/image` and `.../music` (`?name=` = the file name, only used as a sanitized display label of the music):
 *    same-origin (the only CSRF defence -- multipart is a CORS "simple request") -> authenticated -> ADMIN -> per-actor rate limit ->
 *    `Content-Length` present and within the cap -> streamed single-file receive with the same cap -> magic-byte sniff (the client-declared
 *    type and the file name are never consulted) -> the processor (picture: header guards BEFORE decoding, a fresh metadata-free PNG, letterboxed
 *    to 1280x720; music: forced demuxer, ffprobe checks, AAC re-encode) -> file write -> ONE transaction (settings lock, key swap, audit) -> the
 *    OLD file is retired only after the commit and only when no feed is running. A failed commit deletes the new file.
 *  * `DELETE` the same paths: back to the built-in picture / no music.
 *  * `GET` the same paths (`?v=` is only a cache-buster): the stored file or, for the picture, the built-in default. `Cache-Control: private,
 *    no-store`, `nosniff`, `Content-Disposition: inline`, fixed content types.
 *
 * Every answer to a refused upload is JSON with an enum code only, never free text. Nothing here logs a name, a path or a byte of a file.
 */
internal fun Route.registerPauseMediaRoutes(
    store: PauseMediaStore,
    musicProcessor: PauseMusicProcessor,
    ffmpegAvailable: Boolean,
    baseUrl: String,
    uploadRateLimiter: FederationInboxRateLimiter,
    readRateLimiter: FederationInboxRateLimiter,
) {
    val canonicalOrigin = runCatching { URI(baseUrl) }.getOrNull()

    /** Common gate of every method; `null` = the answer was already sent. */
    suspend fun ApplicationCall.admin(
        mutating: Boolean,
        limiter: FederationInboxRateLimiter,
    ): CurrentMember? {
        if (mutating && !isSameOriginRequest(headers = request.headers, canonicalOrigin = canonicalOrigin)) {
            respondPauseError(status = HttpStatusCode.Forbidden, error = PauseMediaUploadError.FORBIDDEN)
            return null
        }
        val current: CurrentMember =
            try {
                resolveCurrentMember(this)
            } catch (_: UnauthenticatedException) {
                response.header(HttpHeaders.CacheControl, "no-store")
                respond(HttpStatusCode.Unauthorized)
                return null
            }
        if (current.role != AccountRole.ADMIN) {
            respondPauseError(status = HttpStatusCode.Forbidden, error = PauseMediaUploadError.FORBIDDEN)
            return null
        }
        val rateKey = "actor:${current.memberId}"
        if (!limiter.checkAndRecord(rateKey)) {
            response.header(HttpHeaders.RetryAfter, limiter.retryAfterSeconds(rateKey).toString())
            respondPauseError(status = HttpStatusCode.TooManyRequests, error = PauseMediaUploadError.RATE_LIMITED)
            return null
        }
        return current
    }

    /** Reads the single uploaded file within [cap]; `null` = the answer was already sent. */
    suspend fun ApplicationCall.receiveCapped(cap: Long): ByteArray? {
        val declared = request.headers[HttpHeaders.ContentLength]?.toLongOrNull()
        if (declared == null || declared > multipartCap(cap)) {
            respondPauseError(status = HttpStatusCode.PayloadTooLarge, error = PauseMediaUploadError.FILE_TOO_LARGE)
            return null
        }
        return when (val received = receiveSingleUploadBytes(cap)) {
            SingleUploadReceive.TooLarge -> {
                respondPauseError(status = HttpStatusCode.PayloadTooLarge, error = PauseMediaUploadError.FILE_TOO_LARGE)
                null
            }
            SingleUploadReceive.MultipleParts, SingleUploadReceive.NoFile -> {
                respondPauseError(status = HttpStatusCode.BadRequest, error = PauseMediaUploadError.INVALID_REQUEST)
                null
            }
            is SingleUploadReceive.Ok -> received.bytes
        }
    }

    // ── picture ─────────────────────────────────────────────────────────────────────────────────────

    post("/api/conference/pause-media/image") {
        val current = call.admin(mutating = true, limiter = uploadRateLimiter) ?: return@post
        val bytes = call.receiveCapped(PauseMediaRules.IMAGE_MAX_UPLOAD_BYTES) ?: return@post
        val sniffed = PauseImageProcessor.sniff(bytes.copyOfRange(0, minOf(SNIFF_BYTES, bytes.size)))
        if (sniffed == null) {
            call.respondPauseError(status = HttpStatusCode.UnsupportedMediaType, error = PauseMediaUploadError.UNSUPPORTED_FORMAT)
            return@post
        }
        val processed =
            when (val outcome = PauseImageProcessor.process(bytes = bytes, input = sniffed)) {
                PauseImageResult.DimensionsTooLarge -> {
                    call.respondPauseError(status = HttpStatusCode.UnprocessableEntity, error = PauseMediaUploadError.DIMENSIONS_TOO_LARGE)
                    return@post
                }
                PauseImageResult.TooSmall -> {
                    call.respondPauseError(status = HttpStatusCode.UnprocessableEntity, error = PauseMediaUploadError.TOO_SMALL)
                    return@post
                }
                PauseImageResult.Undecodable -> {
                    call.respondPauseError(status = HttpStatusCode.UnprocessableEntity, error = PauseMediaUploadError.UNDECODABLE)
                    return@post
                }
                PauseImageResult.Busy -> {
                    call.respondPauseError(status = HttpStatusCode.ServiceUnavailable, error = PauseMediaUploadError.BUSY)
                    return@post
                }
                is PauseImageResult.Ok -> outcome
            }
        val key = Uuid.random()
        store.write(key = key, kind = PauseMediaKind.IMAGE, bytes = processed.bytes)
        val old: PauseMediaRow =
            try {
                transaction {
                    PauseMediaDb.lock()
                    val before = PauseMediaDb.load()
                    PauseMediaDb.setImage(key = key, now = DbClock.nowLocalDateTime())
                    recordPauseAudit(current = current, after = """{"pauseImage":"set"}""")
                    before
                }
            } catch (e: Exception) {
                store.deleteNow(key = key, kind = PauseMediaKind.IMAGE)
                throw e
            }
        old.imageKey?.let { store.retire(key = it, kind = PauseMediaKind.IMAGE) }
        logger.info { "Pause picture stored: bytes=${processed.bytes.size}" }
        call.respondCurrent(HttpStatusCode.Created)
    }

    delete("/api/conference/pause-media/image") {
        val current = call.admin(mutating = true, limiter = uploadRateLimiter) ?: return@delete
        val old =
            transaction {
                PauseMediaDb.lock()
                val before = PauseMediaDb.load()
                if (before.imageKey != null) {
                    PauseMediaDb.setImage(key = null, now = null)
                    recordPauseAudit(current = current, after = """{"pauseImage":"removed"}""")
                }
                before
            }
        old.imageKey?.let { store.retire(key = it, kind = PauseMediaKind.IMAGE) }
        call.respondCurrent(HttpStatusCode.OK)
    }

    get("/api/conference/pause-media/image") {
        call.admin(mutating = false, limiter = readRateLimiter) ?: return@get
        val file: File = withContext(Dispatchers.IO) { store.imageFile() }
        call.respondMediaFile(file = file, contentType = ContentType.Image.PNG, name = "pause.png")
    }

    // ── music ───────────────────────────────────────────────────────────────────────────────────────

    post("/api/conference/pause-media/music") {
        val current = call.admin(mutating = true, limiter = uploadRateLimiter) ?: return@post
        if (!ffmpegAvailable) {
            call.respondPauseError(status = HttpStatusCode.ServiceUnavailable, error = PauseMediaUploadError.PROCESSING_UNAVAILABLE)
            return@post
        }
        val bytes = call.receiveCapped(PauseMediaRules.MUSIC_MAX_UPLOAD_BYTES) ?: return@post
        val sniffed = AudioSniffer.sniff(bytes.copyOfRange(0, minOf(SNIFF_BYTES, bytes.size)))
        if (sniffed == null) {
            call.respondPauseError(status = HttpStatusCode.UnsupportedMediaType, error = PauseMediaUploadError.UNSUPPORTED_FORMAT)
            return@post
        }
        val label = sanitizeMusicLabel(call.request.queryParameters["name"])
        val input = withContext(Dispatchers.IO) { store.newTempFile() }
        val output = withContext(Dispatchers.IO) { store.newTempFile() }
        try {
            withContext(Dispatchers.IO) { input.writeBytes(bytes) }
            val processed =
                when (val outcome = musicProcessor.process(input = input, sniffed = sniffed, out = output)) {
                    MusicProcessingResult.Unsupported -> {
                        call.respondPauseError(
                            status = HttpStatusCode.UnsupportedMediaType,
                            error = PauseMediaUploadError.UNSUPPORTED_FORMAT,
                        )
                        return@post
                    }
                    MusicProcessingResult.TooShort -> {
                        call.respondPauseError(status = HttpStatusCode.UnprocessableEntity, error = PauseMediaUploadError.TOO_SHORT)
                        return@post
                    }
                    MusicProcessingResult.TooLong -> {
                        call.respondPauseError(status = HttpStatusCode.UnprocessableEntity, error = PauseMediaUploadError.TOO_LONG)
                        return@post
                    }
                    MusicProcessingResult.HasVideo -> {
                        call.respondPauseError(status = HttpStatusCode.UnprocessableEntity, error = PauseMediaUploadError.HAS_VIDEO)
                        return@post
                    }
                    MusicProcessingResult.Failed -> {
                        call.respondPauseError(status = HttpStatusCode.UnprocessableEntity, error = PauseMediaUploadError.UNDECODABLE)
                        return@post
                    }
                    is MusicProcessingResult.Ok -> outcome
                }
            val key = Uuid.random()
            withContext(Dispatchers.IO) { store.adopt(key = key, kind = PauseMediaKind.MUSIC, source = processed.file) }
            val old: PauseMediaRow =
                try {
                    transaction {
                        PauseMediaDb.lock()
                        val before = PauseMediaDb.load()
                        PauseMediaDb.setMusic(
                            key = key,
                            label = label,
                            durationSeconds = processed.durationSeconds,
                            now = DbClock.nowLocalDateTime(),
                        )
                        recordPauseAudit(current = current, after = """{"pauseMusic":"set"}""")
                        before
                    }
                } catch (e: Exception) {
                    store.deleteNow(key = key, kind = PauseMediaKind.MUSIC)
                    throw e
                }
            old.musicKey?.let { store.retire(key = it, kind = PauseMediaKind.MUSIC) }
            logger.info { "Pause music stored: seconds=${processed.durationSeconds}" }
            call.respondCurrent(HttpStatusCode.Created)
        } finally {
            withContext(Dispatchers.IO) {
                runCatching { input.delete() }
                runCatching { output.delete() }
            }
        }
    }

    delete("/api/conference/pause-media/music") {
        val current = call.admin(mutating = true, limiter = uploadRateLimiter) ?: return@delete
        val old =
            transaction {
                PauseMediaDb.lock()
                val before = PauseMediaDb.load()
                if (before.musicKey != null) {
                    PauseMediaDb.setMusic(key = null, label = null, durationSeconds = null, now = null)
                    recordPauseAudit(current = current, after = """{"pauseMusic":"removed"}""")
                }
                before
            }
        old.musicKey?.let { store.retire(key = it, kind = PauseMediaKind.MUSIC) }
        call.respondCurrent(HttpStatusCode.OK)
    }

    get("/api/conference/pause-media/music") {
        call.admin(mutating = false, limiter = readRateLimiter) ?: return@get
        val file: File? = withContext(Dispatchers.IO) { store.musicFile() }
        if (file == null) {
            call.response.header(HttpHeaders.CacheControl, "no-store")
            call.respond(HttpStatusCode.NotFound)
            return@get
        }
        call.respondMediaFile(file = file, contentType = ContentType.parse("audio/mp4"), name = "pause.m4a")
    }
}

private fun recordPauseAudit(
    current: CurrentMember,
    after: String,
) {
    AuditLogRecorder.record(
        actorMemberId = current.memberId,
        actorRole = current.role,
        entityType = AuditEntityType.ORGANIZATION_SETTINGS,
        entityId = ORGANIZATION_SETTINGS_ID,
        action = AuditAction.UPDATE,
        after = after,
    )
}

private suspend fun ApplicationCall.respondMediaFile(
    file: File,
    contentType: ContentType,
    name: String,
) {
    response.header("X-Content-Type-Options", "nosniff")
    response.header("Referrer-Policy", "no-referrer")
    response.header("Cross-Origin-Resource-Policy", "same-origin")
    response.header("Content-Security-Policy", "default-src 'none'; sandbox")
    response.header(HttpHeaders.CacheControl, "private, no-store")
    response.header(HttpHeaders.ContentDisposition, "inline; filename=\"$name\"")
    respond(LocalFileContent(file, contentType))
}

private suspend fun ApplicationCall.respondPauseError(
    status: HttpStatusCode,
    error: PauseMediaUploadError,
) {
    response.header(HttpHeaders.CacheControl, "no-store")
    respond(status, PauseMediaResultDto(ok = false, error = error))
}

private suspend fun ApplicationCall.respondCurrent(status: HttpStatusCode) {
    val row = transaction { PauseMediaDb.load() }
    response.header(HttpHeaders.CacheControl, "no-store")
    respond(
        status,
        PauseMediaResultDto(
            ok = true,
            imageCustom = row.imageKey != null,
            imageVersion = row.imageKey?.toString()?.take(8),
            musicLabel = row.musicLabel,
            musicDurationSeconds = row.musicDurationSeconds,
            musicVersion = row.musicKey?.toString()?.take(8),
        ),
    )
}
