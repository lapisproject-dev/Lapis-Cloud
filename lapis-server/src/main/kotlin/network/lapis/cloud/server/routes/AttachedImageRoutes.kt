package network.lapis.cloud.server.routes

import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.http.ContentType
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
import network.lapis.cloud.server.db.DbClock
import network.lapis.cloud.server.federation.FederationInboxRateLimiter
import network.lapis.cloud.server.gallery.AttachedImageStorage
import network.lapis.cloud.server.gallery.AttachedImageStore
import network.lapis.cloud.server.gallery.GalleryCommitResult
import network.lapis.cloud.server.gallery.GalleryGateResult
import network.lapis.cloud.server.gallery.GalleryImageProcessor
import network.lapis.cloud.server.gallery.GalleryLimits
import network.lapis.cloud.server.gallery.GalleryOwnerPolicy
import network.lapis.cloud.server.gallery.GalleryProcessingResult
import network.lapis.cloud.server.gallery.GalleryUploadGate
import network.lapis.cloud.server.gallery.GalleryUrls
import network.lapis.cloud.server.security.CurrentMember
import network.lapis.cloud.server.security.resolveCurrentMember
import network.lapis.cloud.shared.domain.AttachedImageOwnerType
import network.lapis.cloud.shared.domain.AttachedImageUploadResultDto
import network.lapis.cloud.shared.domain.GalleryRules
import network.lapis.cloud.shared.domain.GalleryUploadErrorCode
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.net.URI
import kotlin.uuid.Uuid

private val logger = KotlinLogging.logger {}

/**
 * V1.9.222 "Gemeinsame Bildergalerie" -- the two byte-carrying routes of the gallery. Registered UNCONDITIONALLY next to
 * [registerEventCoverRoutes]. Everything that is not bytes (list, re-order, cover, caption, delete) is the RPC service
 * `IAttachedImageService`.
 *
 * **`POST /api/images/{ownerType}/{ownerId}`** (multipart, ONE file part) -- order of the checks, each cheaper than the next:
 * 1. same-origin (`Origin` / `Sec-Fetch-Site`): a multipart POST is a "simple request" without preflight, so this is the ONLY CSRF
 * defence;
 * 2. session, 3. per-member upload budget (429 + `Retry-After`), 4. strict parsing of `ownerType` (exact enum name) and `ownerId`
 * (UUID),
 * 5. a read-only gate (visible? creator? not frozen? room left?) BEFORE any byte is read or decoded, 6. `Content-Length` pre-check,
 * 7. the streamed read with a hard 5 MiB cap, 8. magic-byte sniff (the declared `Content-Type` is never read) and the decode/re-encode,
 * 9.
 * the
 * file is written under a fresh server `storage_key`, 10. one transaction that locks the owner row and REPEATS every check from 5
 * (TOCTOU),
 * inserts
 * and audits; on any refusal or exception the file is deleted again, 11. the answer.
 * Every error is JSON `{"error": CODE}` -- a stable code the client translates, never free text.
 *
 * **`GET /api/images/{imageId}`** -- a picture is private to members who may see its owner. An unknown id, an owner the caller may not
 * see,
 * a
 * missing file and an anonymous caller all get the IDENTICAL bare 404 (no oracle). The response is `inline` with a fixed file name,
 * `nosniff`,
 * `default-src 'none'`, `Cross-Origin-Resource-Policy: same-origin` and `Referrer-Policy: no-referrer`.
 *
 * Cache: with a `v` that equals the row's current tag the answer is `private, max-age=31536000, immutable`, otherwise `private,
 * no-cache`. EXCEPT a social-post picture (V1.9.223): always `private, no-store`, so a legal removal or a hidden post takes effect
 * at once, also in the browser cache.
 *
 * V1.9.223: for `SOCIAL_POST` the upload additionally spends the author's DAILY budget ([socialDailyLimiter], 20 pictures per 24 h,
 * in memory like every limiter here, so it resets on restart) right after the read-only gate and before a byte is read: 429 +
 * `Retry-After` + `DAILY_LIMIT_REACHED`. Attempts that pass the gate count, also pictures deleted later.
 * Limitation (documented in image-gallery.adoc): a browser keeps a picture for up to a year even if the viewer later loses the right to
 * see
 * it
 * (auction switched off, member left) -- the server side is enforced for every fresh request only. `private` keeps shared caches out.
 */
internal fun Route.registerAttachedImageRoutes(
    storage: AttachedImageStorage,
    baseUrl: String,
    uploadRateLimiter: FederationInboxRateLimiter,
    readRateLimiter: FederationInboxRateLimiter,
    socialDailyLimiter: FederationInboxRateLimiter,
    limits: GalleryLimits = GalleryLimits(),
) {
    val canonicalOrigin = runCatching { URI(baseUrl) }.getOrNull()

    post("/api/images/{ownerType}/{ownerId}") {
        if (!isSameOriginRequest(headers = call.request.headers, canonicalOrigin = canonicalOrigin)) {
            call.respondGalleryError(status = HttpStatusCode.Forbidden, code = GalleryUploadErrorCode.INVALID_ORIGIN)
            return@post
        }
        val current = resolveCurrentMember(call)
        val rateKey = "member:${current.memberId}"
        if (!uploadRateLimiter.checkAndRecord(rateKey)) {
            call.response.header(HttpHeaders.RetryAfter, uploadRateLimiter.retryAfterSeconds(rateKey).toString())
            call.respondGalleryError(status = HttpStatusCode.TooManyRequests, code = GalleryUploadErrorCode.RATE_LIMITED)
            return@post
        }
        val ownerType = AttachedImageOwnerType.entries.firstOrNull { it.name == call.parameters["ownerType"] }
        val ownerId = call.parameters["ownerId"]?.let { runCatching { Uuid.parse(it) }.getOrNull() }
        if (ownerType == null || ownerId == null) {
            call.respondGalleryError(status = HttpStatusCode.NotFound, code = GalleryUploadErrorCode.NOT_FOUND)
            return@post
        }

        // 5. read-only gate before a single byte is read
        val pre = transaction { gate(type = ownerType, ownerId = ownerId, caller = current, limits = limits, newBytes = 0, lock = false) }
        if (pre !is GalleryGateResult.Allowed) {
            call.respondGate(pre)
            return@post
        }

        if (ownerType == AttachedImageOwnerType.SOCIAL_POST) {
            val dailyKey = "social-images:member:${current.memberId}"
            if (!socialDailyLimiter.checkAndRecord(dailyKey)) {
                call.response.header(HttpHeaders.RetryAfter, socialDailyLimiter.retryAfterSeconds(dailyKey).toString())
                call.respondGalleryError(status = HttpStatusCode.TooManyRequests, code = GalleryUploadErrorCode.DAILY_LIMIT_REACHED)
                return@post
            }
        }

        val contentLength = call.request.headers[HttpHeaders.ContentLength]?.toLongOrNull()
        if (contentLength != null && contentLength > multipartCap(GalleryRules.MAX_UPLOAD_BYTES)) {
            call.respondGalleryError(status = HttpStatusCode.PayloadTooLarge, code = GalleryUploadErrorCode.IMAGE_TOO_LARGE)
            return@post
        }
        val bytes =
            when (val received = call.receiveSingleUploadBytes(GalleryRules.MAX_UPLOAD_BYTES)) {
                SingleUploadReceive.TooLarge -> {
                    call.respondGalleryError(status = HttpStatusCode.PayloadTooLarge, code = GalleryUploadErrorCode.IMAGE_TOO_LARGE)
                    return@post
                }
                SingleUploadReceive.MultipleParts, SingleUploadReceive.NoFile -> {
                    call.respondGalleryError(status = HttpStatusCode.BadRequest, code = GalleryUploadErrorCode.IMAGE_UNREADABLE)
                    return@post
                }
                is SingleUploadReceive.Ok -> received.bytes
            }

        val input = GalleryImageProcessor.sniff(bytes)
        if (input == null) {
            call.respondGalleryError(status = HttpStatusCode.UnsupportedMediaType, code = GalleryUploadErrorCode.IMAGE_TYPE_NOT_ALLOWED)
            return@post
        }
        val processed =
            when (val result = GalleryImageProcessor.process(bytes = bytes, input = input)) {
                is GalleryProcessingResult.Ok -> result
                GalleryProcessingResult.DimensionsTooLarge -> {
                    call.respondGalleryError(
                        status = HttpStatusCode.UnprocessableEntity,
                        code = GalleryUploadErrorCode.IMAGE_DIMENSIONS_EXCEEDED,
                    )
                    return@post
                }
                GalleryProcessingResult.Undecodable -> {
                    call.respondGalleryError(status = HttpStatusCode.UnprocessableEntity, code = GalleryUploadErrorCode.IMAGE_UNREADABLE)
                    return@post
                }
                GalleryProcessingResult.EncodedTooLarge -> {
                    call.respondGalleryError(status = HttpStatusCode.PayloadTooLarge, code = GalleryUploadErrorCode.IMAGE_TOO_LARGE)
                    return@post
                }
                GalleryProcessingResult.Busy -> {
                    call.response.header(HttpHeaders.RetryAfter, "10")
                    call.respondGalleryError(status = HttpStatusCode.ServiceUnavailable, code = GalleryUploadErrorCode.SERVER_BUSY)
                    return@post
                }
            }

        val storageKey = Uuid.random()
        val imageId = Uuid.random()
        storage.write(storageKey = storageKey, format = processed.format, bytes = processed.bytes)
        val committed =
            try {
                transaction {
                    GalleryUploadGate.commitInTx(
                        type = ownerType,
                        ownerId = ownerId,
                        caller = current,
                        now = DbClock.nowLocalDateTime(),
                        limits = limits,
                        imageId = imageId,
                        storageKey = storageKey,
                        mimeType = AttachedImageStorage.mimeTypeFor(processed.format),
                        byteSize = processed.bytes.size.toLong(),
                        width = processed.width,
                        height = processed.height,
                    )
                }
            } catch (e: Exception) {
                storage.delete(storageKey)
                throw e
            }
        when (committed) {
            is GalleryCommitResult.Refused -> {
                storage.delete(storageKey)
                call.respondGate(committed.gate)
            }
            is GalleryCommitResult.Added -> {
                logger.info {
                    "Gallery image added: imageId=$imageId ownerType=$ownerType memberId=${current.memberId} bytes=${processed.bytes.size}"
                }
                call.response.header(HttpHeaders.CacheControl, "no-store")
                call.respond(
                    HttpStatusCode.OK,
                    AttachedImageUploadResultDto(
                        id = imageId.toString(),
                        url = GalleryUrls.imageUrl(imageId = imageId, storageKey = storageKey),
                        position = committed.row.position,
                    ),
                )
            }
        }
    }

    get("/api/images/{imageId}") {
        // The session is resolved WITHOUT letting a failure throw: an anonymous caller must see the same 404 as an unknown id.
        val current = runCatching { resolveCurrentMember(call) }.getOrNull()
        val rateKey = if (current != null) "member:${current.memberId}" else rateLimitKeyFor(remoteHost = call.request.origin.remoteHost)
        if (!readRateLimiter.checkAndRecord(rateKey)) {
            call.respond(HttpStatusCode.TooManyRequests)
            return@get
        }
        val imageId = call.parameters["imageId"]?.let { runCatching { Uuid.parse(it) }.getOrNull() }
        if (current == null || imageId == null) {
            call.respond(HttpStatusCode.NotFound)
            return@get
        }
        val row =
            transaction {
                val image = AttachedImageStore.findInTx(imageId) ?: return@transaction null
                GalleryOwnerPolicy.readInTx(
                    type = image.ownerType,
                    ownerId = image.ownerId,
                    now = DbClock.nowLocalDateTime(),
                    viewer = current,
                ) ?: return@transaction null
                image
            }
        val file = row?.let { storage.resolve(storageKey = it.storageKey, mimeType = it.mimeType) }
        if (row == null || file == null) {
            call.respond(HttpStatusCode.NotFound)
            return@get
        }
        val matches = call.request.queryParameters["v"] == GalleryUrls.versionTag(row.storageKey)
        val cacheControl =
            when {
                row.ownerType == AttachedImageOwnerType.SOCIAL_POST -> "private, no-store"
                matches -> "private, max-age=31536000, immutable"
                else -> "private, no-cache"
            }
        call.respondGalleryFile(file = file, mimeType = row.mimeType, cacheControl = cacheControl)
    }
}

private fun gate(
    type: AttachedImageOwnerType,
    ownerId: Uuid,
    caller: CurrentMember,
    limits: GalleryLimits,
    newBytes: Long,
    lock: Boolean,
    now: kotlinx.datetime.LocalDateTime = DbClock.nowLocalDateTime(),
): GalleryGateResult =
    GalleryUploadGate.checkInTx(
        type = type,
        ownerId = ownerId,
        caller = caller,
        now = now,
        limits = limits,
        newBytes = newBytes,
        lock = lock,
    )

private suspend fun ApplicationCall.respondGalleryError(
    status: HttpStatusCode,
    code: GalleryUploadErrorCode,
) {
    response.header(HttpHeaders.CacheControl, "no-store")
    respond(status, AttachedImageUploadResultDto(error = code))
}

private suspend fun ApplicationCall.respondGate(result: GalleryGateResult) {
    when (result) {
        is GalleryGateResult.Allowed -> error("An allowed gate is not an error")
        GalleryGateResult.NotFound -> respondGalleryError(status = HttpStatusCode.NotFound, code = GalleryUploadErrorCode.NOT_FOUND)
        GalleryGateResult.Locked -> respondGalleryError(status = HttpStatusCode.Conflict, code = GalleryUploadErrorCode.GALLERY_LOCKED)
        GalleryGateResult.Forbidden -> respondGalleryError(status = HttpStatusCode.Forbidden, code = GalleryUploadErrorCode.FORBIDDEN)
        GalleryGateResult.Full -> respondGalleryError(status = HttpStatusCode.Conflict, code = GalleryUploadErrorCode.GALLERY_FULL)
        GalleryGateResult.BytesExceeded ->
            respondGalleryError(
                status = HttpStatusCode.Conflict,
                code = GalleryUploadErrorCode.GALLERY_BYTES_EXCEEDED,
            )
    }
}

/**
 * NOT [respondCoverFile]: that one sets `Cross-Origin-Resource-Policy: cross-origin` for PUBLIC covers. A gallery picture is private,
 * so
 * the
 * policy is `same-origin`, the cache is `private`, and the file is `inline` under a fixed name (never a user-chosen one).
 */
private suspend fun ApplicationCall.respondGalleryFile(
    file: java.io.File,
    mimeType: String,
    cacheControl: String,
) {
    val extension =
        AttachedImageStorage.extensionFor(mimeType) ?: run {
            respond(HttpStatusCode.NotFound)
            return
        }
    response.header("X-Content-Type-Options", "nosniff")
    response.header("Content-Security-Policy", "default-src 'none'")
    response.header("Cross-Origin-Resource-Policy", "same-origin")
    response.header("Referrer-Policy", "no-referrer")
    response.header(HttpHeaders.ContentDisposition, "inline; filename=\"bild.$extension\"")
    response.header(HttpHeaders.CacheControl, cacheControl)
    respond(LocalFileContent(file, ContentType.parse(mimeType)))
}
