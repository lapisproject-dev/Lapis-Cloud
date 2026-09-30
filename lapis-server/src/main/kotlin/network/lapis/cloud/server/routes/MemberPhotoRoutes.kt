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
import network.lapis.cloud.server.db.generated.MemberPhotoTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.server.embed.EmbedConfig
import network.lapis.cloud.server.embed.EmbedCorsResult
import network.lapis.cloud.server.embed.applyEmbedCors
import network.lapis.cloud.server.embed.respondEmbedForbiddenOrigin
import network.lapis.cloud.server.events.EventCoverImageProcessor
import network.lapis.cloud.server.federation.FederationInboxRateLimiter
import network.lapis.cloud.server.memberphoto.MemberPhotoImageProcessor
import network.lapis.cloud.server.memberphoto.MemberPhotoPolicy
import network.lapis.cloud.server.memberphoto.MemberPhotoProcessingResult
import network.lapis.cloud.server.memberphoto.MemberPhotoStorage
import network.lapis.cloud.server.memberphoto.MemberPhotoStore
import network.lapis.cloud.server.security.CurrentMember
import network.lapis.cloud.server.security.resolveCurrentMember
import network.lapis.cloud.shared.domain.MemberPhotoAuditAction
import network.lapis.cloud.shared.domain.MemberPhotoRules
import network.lapis.cloud.shared.domain.MemberPhotoUploadError
import network.lapis.cloud.shared.domain.MemberPhotoUploadResultDto
import network.lapis.cloud.shared.domain.MemberPhotoVisibility
import network.lapis.cloud.shared.domain.MemberStatusSets
import network.lapis.cloud.shared.rpc.UnauthenticatedException
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.net.URI
import kotlin.uuid.Uuid

private val logger = KotlinLogging.logger {}

private const val SNIFF_BYTES = 8

/**
 * Welle V1.9.19 "Mitglieder-Foto" -- byte-carrying routes of the member photo. Registered ALWAYS,
 * next to [registerEventCoverRoutes] and NOT inside `registerEmbedRoutes` (which returns early when
 * `LAPIS_EMBED_ENABLED` is false), and before `staticFiles`.
 *
 * Three routes:
 * 1. `POST /api/member-photo` -- upload of the CALLER's own photo. There is structurally no
 *    member-id parameter, so there is no IDOR surface. Same-origin check is the only CSRF defense
 *    (multipart is a CORS "simple request"). Every error is a JSON [MemberPhotoUploadResultDto]
 *    carrying an enum code only -- no server free text ever reaches the browser.
 * 2. `GET /api/member-photo/own` -- private preview for the caller. `private, no-store`.
 * 3. `GET /public/member-photos/{publicToken}` -- public delivery. Existence-oracle discipline:
 *    malformed token, unknown token, PRIVATE, withdrawn, rotated token, member not ACTIVE and
 *    missing file are ALL answered by the SAME bare 404 ([respondMemberPhotoNotFound]), with the
 *    same headers. Rate-limit and CORS rejection happen BEFORE any database access.
 *    `Cache-Control: no-store` is set exactly once, by [applyEmbedCors] -- `response.header`
 *    APPENDS, so it must never be set a second time here.
 */
internal fun Route.registerMemberPhotoRoutes(
    storage: MemberPhotoStorage,
    baseUrl: String,
    embedConfig: EmbedConfig,
    uploadRateLimiter: FederationInboxRateLimiter,
    ownReadRateLimiter: FederationInboxRateLimiter,
    publicReadRateLimiter: FederationInboxRateLimiter,
) {
    val canonicalOrigin = runCatching { URI(baseUrl) }.getOrNull()

    post("/api/member-photo") {
        if (!isSameOriginRequest(headers = call.request.headers, canonicalOrigin = canonicalOrigin)) {
            call.respondUploadError(status = HttpStatusCode.Forbidden, error = MemberPhotoUploadError.INVALID_REQUEST)
            return@post
        }
        val current: CurrentMember =
            try {
                resolveCurrentMember(call)
            } catch (_: UnauthenticatedException) {
                call.respond(HttpStatusCode.Unauthorized)
                return@post
            }
        if (current.status !in MemberStatusSets.MEMBER_PHOTO_ELIGIBLE) {
            call.respondUploadError(status = HttpStatusCode.Forbidden, error = MemberPhotoUploadError.NOT_ELIGIBLE)
            return@post
        }
        val rateKey = "member:${current.memberId}"
        if (!uploadRateLimiter.checkAndRecord(rateKey)) {
            call.response.header(HttpHeaders.RetryAfter, uploadRateLimiter.retryAfterSeconds(rateKey).toString())
            call.respondUploadError(status = HttpStatusCode.TooManyRequests, error = MemberPhotoUploadError.RATE_LIMITED)
            return@post
        }
        val declared = call.request.headers[HttpHeaders.ContentLength]?.toLongOrNull()
        if (declared == null || declared > multipartCap(MemberPhotoRules.MAX_UPLOAD_BYTES)) {
            call.respondUploadError(status = HttpStatusCode.PayloadTooLarge, error = MemberPhotoUploadError.FILE_TOO_LARGE)
            return@post
        }

        val bytes =
            when (val received = call.receiveSingleUploadBytes(MemberPhotoRules.MAX_UPLOAD_BYTES)) {
                SingleUploadReceive.TooLarge -> {
                    call.respondUploadError(status = HttpStatusCode.PayloadTooLarge, error = MemberPhotoUploadError.FILE_TOO_LARGE)
                    return@post
                }
                SingleUploadReceive.MultipleParts, SingleUploadReceive.NoFile -> {
                    call.respondUploadError(status = HttpStatusCode.BadRequest, error = MemberPhotoUploadError.INVALID_REQUEST)
                    return@post
                }
                is SingleUploadReceive.Ok -> received.bytes
            }

        val format = EventCoverImageProcessor.sniff(bytes.copyOfRange(0, minOf(SNIFF_BYTES, bytes.size)))
        if (format == null) {
            call.respondUploadError(status = HttpStatusCode.UnprocessableEntity, error = MemberPhotoUploadError.UNSUPPORTED_FORMAT)
            return@post
        }

        val outcome =
            EventCoverImageProcessor.withDecodePermitOrNull(waitMillis = MemberPhotoPolicy.DECODE_WAIT_MILLIS) {
                MemberPhotoImageProcessor.process(bytes = bytes, format = format)
            }
        val processed =
            when (outcome) {
                null -> {
                    call.respondUploadError(status = HttpStatusCode.ServiceUnavailable, error = MemberPhotoUploadError.BUSY)
                    return@post
                }
                MemberPhotoProcessingResult.TooSmall -> {
                    call.respondUploadError(status = HttpStatusCode.UnprocessableEntity, error = MemberPhotoUploadError.TOO_SMALL)
                    return@post
                }
                MemberPhotoProcessingResult.DimensionsTooLarge -> {
                    call.respondUploadError(
                        status = HttpStatusCode.UnprocessableEntity,
                        error = MemberPhotoUploadError.DIMENSIONS_TOO_LARGE,
                    )
                    return@post
                }
                MemberPhotoProcessingResult.Undecodable -> {
                    call.respondUploadError(status = HttpStatusCode.UnprocessableEntity, error = MemberPhotoUploadError.UNDECODABLE)
                    return@post
                }
                is MemberPhotoProcessingResult.Ok -> outcome
            }

        val storageKey = storage.write(key = Uuid.random(), bytes = processed.jpeg)
        val now = DbClock.nowLocalDateTime()
        val result: UploadCommit =
            try {
                transaction {
                    val member = MemberPhotoStore.lockMember(current.memberId) ?: return@transaction UploadCommit.MemberGone
                    if (member[MemberTable.status] !in MemberStatusSets.MEMBER_PHOTO_ELIGIBLE) {
                        return@transaction UploadCommit.NotEligible
                    }
                    val upsert =
                        MemberPhotoStore.upsertAfterUpload(
                            memberId = current.memberId,
                            storageKey = storageKey,
                            widthPx = processed.edgePx,
                            heightPx = processed.edgePx,
                            sizeBytes = processed.jpeg.size.toLong(),
                            now = now,
                        )
                    if (upsert.publicationReset) {
                        MemberPhotoStore.recordAudit(
                            actorMemberId = current.memberId,
                            actorRole = current.role,
                            targetMemberId = current.memberId,
                            targetStatus = member[MemberTable.status],
                            action = MemberPhotoAuditAction.UNPUBLISHED_BY_REPLACEMENT,
                            beforeVisibility = MemberPhotoVisibility.PUBLIC,
                            beforeVersion = upsert.previousConsentTextVersion,
                            now = now,
                        )
                    }
                    UploadCommit.Stored(previousStorageKey = upsert.previousStorageKey)
                }
            } catch (e: Exception) {
                storage.delete(storageKey)
                throw e
            }
        when (result) {
            UploadCommit.MemberGone -> {
                storage.delete(storageKey)
                call.respond(HttpStatusCode.NotFound)
                return@post
            }
            UploadCommit.NotEligible -> {
                storage.delete(storageKey)
                call.respondUploadError(status = HttpStatusCode.Forbidden, error = MemberPhotoUploadError.NOT_ELIGIBLE)
                return@post
            }
            is UploadCommit.Stored -> {
                // Old file goes only AFTER the commit -- a failed delete leaves an unreferenced
                // file that is never served (no route resolves a key no row points to).
                result.previousStorageKey?.let { storage.delete(it) }
            }
        }
        logger.info { "Member photo stored: memberId=${current.memberId} bytes=${processed.jpeg.size}" }
        call.response.header(HttpHeaders.CacheControl, "no-store")
        call.respond(HttpStatusCode.OK, MemberPhotoUploadResultDto())
    }

    get("/api/member-photo/own") {
        val current: CurrentMember =
            try {
                resolveCurrentMember(call)
            } catch (_: UnauthenticatedException) {
                call.respond(HttpStatusCode.Unauthorized)
                return@get
            }
        if (!ownReadRateLimiter.checkAndRecord("member:${current.memberId}")) {
            call.respond(HttpStatusCode.TooManyRequests)
            return@get
        }
        val key: String? = transaction { MemberPhotoStore.findByMember(current.memberId)?.get(MemberPhotoTable.storageKey) }
        val file = key?.let { storage.resolve(it) }
        if (file == null) {
            call.respond(HttpStatusCode.NotFound)
            return@get
        }
        call.response.header(HttpHeaders.CacheControl, "private, no-store")
        call.response.header("X-Content-Type-Options", "nosniff")
        call.response.header("Cross-Origin-Resource-Policy", "same-origin")
        call.response.header("Content-Security-Policy", "default-src 'none'; sandbox")
        call.respond(LocalFileContent(file, ContentType.Image.JPEG))
    }

    get("/public/member-photos/{publicToken}") {
        // 1. CORS first -- sets Vary: Origin + Cache-Control: no-store (once). A present-but-
        //    disallowed Origin is rejected BEFORE any database access: no oracle.
        when (call.applyEmbedCors(allowlist = embedConfig.allowlist, allowInsecure = embedConfig.allowInsecureOrigins)) {
            EmbedCorsResult.Rejected -> {
                call.respondEmbedForbiddenOrigin()
                return@get
            }
            EmbedCorsResult.NoOriginHeader, is EmbedCorsResult.Allowed -> Unit
        }
        // 2. Rate limit per client IP -- also before any database access.
        if (!publicReadRateLimiter.checkAndRecord(rateLimitKeyFor(remoteHost = call.request.origin.remoteHost))) {
            call.respond(HttpStatusCode.TooManyRequests)
            return@get
        }
        // 3. Every defensive header goes on BEFORE the existence decision, so a hit and every kind
        //    of miss are built identically (only Content-Type and body differ).
        call.response.header("X-Content-Type-Options", "nosniff")
        call.response.header("Referrer-Policy", "no-referrer")
        call.response.header("Cross-Origin-Resource-Policy", "cross-origin")
        call.response.header("Content-Security-Policy", "default-src 'none'; sandbox")

        val token = call.parameters["publicToken"]
        if (token == null || !MemberPhotoPolicy.PUBLIC_TOKEN_PATTERN.matches(token)) {
            call.respondMemberPhotoNotFound()
            return@get
        }
        val storageKey = transaction { MemberPhotoStore.findPublicServable(token) }
        val file = storageKey?.let { storage.resolve(it) }
        if (file == null) {
            call.respondMemberPhotoNotFound()
            return@get
        }
        call.response.header(HttpHeaders.ContentDisposition, "inline")
        call.respond(LocalFileContent(file, ContentType.Image.JPEG))
    }
}

/** The ONE miss response of the public route -- a bare 404, identical for every reason. */
internal suspend fun ApplicationCall.respondMemberPhotoNotFound() {
    respond(HttpStatusCode.NotFound)
}

private suspend fun ApplicationCall.respondUploadError(
    status: HttpStatusCode,
    error: MemberPhotoUploadError,
) {
    response.header(HttpHeaders.CacheControl, "no-store")
    respond(status, MemberPhotoUploadResultDto(error = error))
}

private sealed interface UploadCommit {
    data object MemberGone : UploadCommit

    data object NotEligible : UploadCommit

    class Stored(
        val previousStorageKey: String?,
    ) : UploadCommit
}
