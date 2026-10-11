package network.lapis.cloud.server.routes

import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.PartData
import io.ktor.http.content.forEachPart
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.call
import io.ktor.server.request.contentLength
import io.ktor.server.request.formFieldLimit
import io.ktor.server.request.httpMethod
import io.ktor.server.request.path
import io.ktor.server.request.receiveMultipart
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import network.lapis.cloud.server.articles.ArticleImportImages
import network.lapis.cloud.server.articles.ArticleImportZipPolicy
import network.lapis.cloud.server.articles.ZipRejectedException
import network.lapis.cloud.server.db.DbClock
import network.lapis.cloud.server.events.EventCoverStorage
import network.lapis.cloud.server.federation.FederationInboxRateLimiter
import network.lapis.cloud.server.rpc.normalizedPathSegments
import network.lapis.cloud.server.rpc.requireActiveMembership
import network.lapis.cloud.server.security.CurrentMember
import network.lapis.cloud.server.security.requireRole
import network.lapis.cloud.server.security.resolveCurrentMember
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.rpc.BadRequestException
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.io.ByteArrayOutputStream
import java.net.URI
import kotlin.uuid.Uuid

private val logger = KotlinLogging.logger {}

/** Both routes live under this prefix; the body-size guard [installArticleImportImagesBodyLimit] matches on it. */
internal const val ARTICLE_IMPORT_IMAGES_PATH_PREFIX = "/api/articles/import/images/"

/** The ZIP (25 MiB) plus multipart framing and the small text fields. The reverse proxy in front of the server must let this much through. */
internal const val ARTICLE_IMPORT_ZIP_MAX_REQUEST_BYTES: Long = ArticleImportZipPolicy.MAX_ZIP_BYTES + 128L * 1024L

private const val MAX_FIELD_BYTES = 16 * 1024

/** `true` iff [segments] (decoded path segments) address one of the two image routes. */
internal fun isArticleImportImagesRoute(segments: List<String>): Boolean =
    segments.size >= 4 && segments[0] == "api" && segments[1] == "articles" && segments[2] == "import" && segments[3] == "images"

/**
 * Welle V1.9.106 -- 413 for an oversized or length-less POST to the image routes, answered BEFORE authentication and before any body byte is
 * read (twin of `installArticleImportBodyLimit`). A request without `Content-Length` (chunked) is refused too: unlike the cover routes, these
 * routes accept nothing whose size is not announced.
 */
fun Application.installArticleImportImagesBodyLimit() {
    intercept(ApplicationCallPipeline.Setup) {
        if (call.request.httpMethod != HttpMethod.Post) return@intercept
        val segments = normalizedPathSegments(call.request.path())
        if (segments != null && isArticleImportImagesRoute(segments)) {
            val length = call.request.contentLength()
            if (length == null || length > ARTICLE_IMPORT_ZIP_MAX_REQUEST_BYTES) {
                call.respondText(text = "Payload too large", status = HttpStatusCode.PayloadTooLarge)
                finish()
            }
        }
    }
}

/** One received multipart request: the ZIP bytes plus the allow-listed text fields. */
internal class ZipImportUpload(
    val zip: ByteArray,
    val fields: Map<String, String>,
)

internal sealed interface ZipImportReceive {
    class Ok(
        val upload: ZipImportUpload,
    ) : ZipImportReceive

    data object TooLarge : ZipImportReceive

    /** Unknown part, a second file, no file, an oversized text field, or no multipart body at all. */
    data object Malformed : ZipImportReceive
}

/**
 * Streams the multipart body: exactly ONE file part named `zip` (hard cap [maxZipBytes], counted while streaming), and text parts only from
 * [allowedFields] (each at most 16 KiB). Anything else is [ZipImportReceive.Malformed]. Never responds on its own.
 */
internal suspend fun ApplicationCall.receiveZipImportUpload(
    maxZipBytes: Long,
    allowedFields: Set<String>,
): ZipImportReceive {
    formFieldLimit = multipartCap(maxZipBytes)
    var zip: ByteArray? = null
    var tooLarge = false
    var malformed = false
    var fileCount = 0
    val fields = mutableMapOf<String, String>()
    try {
        receiveMultipart().forEachPart { part ->
            when (part) {
                is PartData.FileItem -> {
                    fileCount++
                    if (part.name != "zip" || fileCount > 1) {
                        malformed = true
                    } else {
                        val buffer = ByteArrayOutputStream()
                        val channel: ByteReadChannel = part.provider()
                        val chunk = ByteArray(8192)
                        var total = 0L
                        while (true) {
                            val read = channel.readAvailable(chunk)
                            if (read == -1) break
                            total += read
                            if (total > maxZipBytes) {
                                tooLarge = true
                                break
                            }
                            buffer.write(chunk, 0, read)
                        }
                        if (!tooLarge) zip = buffer.toByteArray()
                    }
                    part.release()
                }
                is PartData.FormItem -> {
                    val name = part.name
                    if (name == null || name !in allowedFields || name in fields || part.value.length > MAX_FIELD_BYTES) {
                        malformed = true
                    } else {
                        fields[name] = part.value
                    }
                    part.release()
                }
                else -> {
                    malformed = true
                    part.release()
                }
            }
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        logger.info { "Article import image upload stream failed: ${e::class.simpleName}" }
        return ZipImportReceive.Malformed
    }
    if (tooLarge) return ZipImportReceive.TooLarge
    val bytes = zip
    if (malformed || bytes == null || bytes.isEmpty()) return ZipImportReceive.Malformed
    return ZipImportReceive.Ok(ZipImportUpload(zip = bytes, fields = fields))
}

/**
 * Welle V1.9.106 -- the two multipart routes of the article import image ZIP (see `docs/api/article-import.adoc`):
 *
 * - `POST /api/articles/import/images/preview` -- fields `zip` (file) and `slugs` (JSON array of the import preview's slugs, at most 100);
 *   writes nothing, answers an `ArticleImportImagesPreviewDto`.
 * - `POST /api/articles/import/images/commit` -- fields `zip` (file) and `importBatchId` (UUID of the import run); stores the covers of THAT
 *   run's articles that have none yet, answers an `ArticleImportImagesResultDto`.
 *
 * **Order in every handler (a test pins the observable order):** same-origin check (CSRF; 403) -> session + BOARD/ADMIN + active membership
 * (401/403) -> rate limit (429 with `Retry-After`) -> `Content-Length` present and <= [ARTICLE_IMPORT_ZIP_MAX_REQUEST_BYTES] (413, nothing read)
 * -> multipart receive with a hard streaming cap -> ZIP validation (422 with a fixed German sentence, never a file name). The same size guard
 * also runs earlier, before authentication, as [installArticleImportImagesBodyLimit].
 */
internal fun Route.registerArticleImportImageRoutes(
    storage: EventCoverStorage,
    baseUrl: String,
    previewRateLimiter: FederationInboxRateLimiter,
    commitRateLimiter: FederationInboxRateLimiter,
) {
    val canonicalOrigin = runCatching { URI(baseUrl) }.getOrNull()

    suspend fun ApplicationCall.authorize(limiter: FederationInboxRateLimiter): CurrentMember? {
        if (!isSameOriginRequest(headers = request.headers, canonicalOrigin = canonicalOrigin)) {
            respond(HttpStatusCode.Forbidden, "Invalid origin")
            return null
        }
        val current = resolveCurrentMember(this)
        current.requireRole(AccountRole.BOARD, AccountRole.ADMIN)
        transaction { requireActiveMembership(memberId = current.memberId) }
        val key = "member:${current.memberId}"
        if (!limiter.checkAndRecord(key)) {
            response.header(HttpHeaders.RetryAfter, limiter.retryAfterSeconds(key).toString())
            respond(HttpStatusCode.TooManyRequests, "Zu viele Anfragen -- bitte später erneut versuchen.")
            return null
        }
        val length = request.contentLength()
        if (length == null || length > ARTICLE_IMPORT_ZIP_MAX_REQUEST_BYTES) {
            respond(HttpStatusCode.PayloadTooLarge, "Payload too large")
            return null
        }
        return current
    }

    suspend fun ApplicationCall.receive(field: String): ZipImportUpload? =
        when (val received = receiveZipImportUpload(maxZipBytes = ArticleImportZipPolicy.MAX_ZIP_BYTES, allowedFields = setOf(field))) {
            is ZipImportReceive.Ok -> received.upload
            ZipImportReceive.TooLarge -> {
                respond(HttpStatusCode.PayloadTooLarge, "Payload too large")
                null
            }
            ZipImportReceive.Malformed -> {
                respond(HttpStatusCode.BadRequest, "Ungültige Anfrage: erwartet werden genau die Felder zip und $field.")
                null
            }
        }

    post("/api/articles/import/images/preview") {
        call.authorize(previewRateLimiter) ?: return@post
        val upload = call.receive("slugs") ?: return@post
        val slugs =
            runCatching { Json.decodeFromString(ListSerializer(String.serializer()), upload.fields["slugs"].orEmpty()) }.getOrNull()
        if (slugs == null) {
            call.respond(HttpStatusCode.BadRequest, "Ungültige Anfrage: slugs muss eine JSON-Liste sein.")
            return@post
        }
        try {
            call.response.header(HttpHeaders.CacheControl, "no-store")
            call.respond(HttpStatusCode.OK, ArticleImportImages.preview(zip = upload.zip, slugs = slugs))
        } catch (e: ZipRejectedException) {
            call.respond(HttpStatusCode.UnprocessableEntity, e.reason.messageDe)
        } catch (e: BadRequestException) {
            call.respond(HttpStatusCode.BadRequest, e.message)
        }
    }

    post("/api/articles/import/images/commit") {
        val current = call.authorize(commitRateLimiter) ?: return@post
        val upload = call.receive("importBatchId") ?: return@post
        val batchId = upload.fields["importBatchId"]?.let { runCatching { Uuid.parse(it.trim()) }.getOrNull() }
        if (batchId == null) {
            call.respond(HttpStatusCode.BadRequest, "Ungültige Anfrage: importBatchId fehlt oder ist ungültig.")
            return@post
        }
        try {
            val result =
                ArticleImportImages.commit(
                    zip = upload.zip,
                    batchId = batchId,
                    actorMemberId = current.memberId,
                    actorRole = current.role,
                    storage = storage,
                    now = DbClock.nowLocalDateTime(),
                )
            call.response.header(HttpHeaders.CacheControl, "no-store")
            call.respond(HttpStatusCode.OK, result)
        } catch (e: ZipRejectedException) {
            call.respond(HttpStatusCode.UnprocessableEntity, e.reason.messageDe)
        }
    }
}
