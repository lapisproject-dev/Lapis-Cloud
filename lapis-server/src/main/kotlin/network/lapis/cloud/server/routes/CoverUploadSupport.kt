package network.lapis.cloud.server.routes

import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.PartData
import io.ktor.http.content.forEachPart
import io.ktor.server.application.ApplicationCall
import io.ktor.server.http.content.LocalFileContent
import io.ktor.server.request.formFieldLimit
import io.ktor.server.request.receiveMultipart
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readAvailable
import network.lapis.cloud.server.events.CoverProcessingResult
import network.lapis.cloud.server.events.EventCoverImageProcessor
import network.lapis.cloud.server.events.EventCoverPolicy
import network.lapis.cloud.server.events.EventCoverStorage
import java.io.ByteArrayOutputStream
import java.net.URI
import kotlin.uuid.Uuid

private val logger = KotlinLogging.logger {}

internal const val COVER_UPLOAD_MAX_BYTES_WITH_MULTIPART_OVERHEAD = EventCoverPolicy.MAX_UPLOAD_BYTES + 64 * 1024
private const val COVER_SNIFF_BYTES = 8

/** Same `[a-z0-9-]{1,120}` slug pattern used across every route family with a public `{slug}` path segment (events, articles). */
internal val COVER_SLUG_PATTERN = Regex("^[a-z0-9-]{1,120}$")

// Welle V1.4.36 "Nachrichten-/Artikel-Modul, Folgewelle" -- extracted, WITHOUT any behavior change,
// from [EventCoverRoutes] (which was the only caller before this wave) so [ArticleCoverRoutes] can
// reuse the exact same multipart-receive/sniff/process/serve pipeline rather than duplicating it.
// `EventCoverRoutesTest` staying green after this extraction is the regression guard for "no
// behavior change" -- see that file's own KDoc.

/** `true` iff [headers] carry no evidence of a cross-origin request -- see [EventCoverRoutes]' own KDoc "isSameOriginRequest" for the full CSRF-defense reasoning this function is the load-bearing part of. */
internal fun isSameOriginRequest(
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

internal fun effectivePort(uri: URI): Int =
    if (uri.port != -1) {
        uri.port
    } else {
        when (uri.scheme) {
            "https" -> 443
            "http" -> 80
            else -> -1
        }
    }

/** Serves the processed cover image file for [coverImageId] from [storage], or a 404 if the file is unexpectedly missing on disk (DB/filesystem drift). Sets the same three defense-in-depth response headers every cover-serving route in this codebase sets. */
internal suspend fun ApplicationCall.respondCoverFile(
    storage: EventCoverStorage,
    coverImageId: Uuid,
) {
    val resolved = storage.resolve(coverImageId)
    if (resolved == null) {
        logger.warn { "Cover file missing on disk for id=$coverImageId" }
        respond(HttpStatusCode.NotFound)
        return
    }
    val (file, format) = resolved
    response.header("X-Content-Type-Options", "nosniff")
    response.header("Content-Security-Policy", "default-src 'none'")
    response.header("Cross-Origin-Resource-Policy", "cross-origin")
    respond(LocalFileContent(file, format.contentType))
}

/** One decoded, validated cover-image upload, ready for [EventCoverStorage.write]. */
internal data class ProcessedCoverUpload(
    val bytes: ByteArray,
    val format: network.lapis.cloud.server.events.CoverImageFormat,
)

/**
 * Reads the multipart body of the current request (an early `Content-Length` pre-check must
 * already have run at the call site BEFORE this is called, see [EventCoverRoutes]/
 * [ArticleCoverRoutes] own upload handlers), streams the single expected file part with a hard
 * [EventCoverPolicy.MAX_UPLOAD_BYTES] cap, sniffs its format, and runs it through
 * [EventCoverImageProcessor.process]. On ANY failure this function itself calls [ApplicationCall
 * .respond] with the identical status code + German message [EventCoverRoutes] used before this
 * extraction, and returns `null` -- the caller's own handler must `return@post`/`return@put`
 * immediately in that case, exactly as the pre-extraction inline code did.
 */
internal suspend fun ApplicationCall.receiveSingleCoverUpload(): ProcessedCoverUpload? {
    formFieldLimit = COVER_UPLOAD_MAX_BYTES_WITH_MULTIPART_OVERHEAD

    var uploadBytes: ByteArray? = null
    var tooLarge = false
    var fileItemCount = 0
    try {
        receiveMultipart().forEachPart { part ->
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
        logger.info(e) { "Cover upload stream failed" }
        throw e
    }

    if (tooLarge) {
        respond(HttpStatusCode.PayloadTooLarge, "Max upload size is ${EventCoverPolicy.MAX_UPLOAD_BYTES} bytes")
        return null
    }
    if (fileItemCount > 1) {
        respond(HttpStatusCode.BadRequest, "Request must contain exactly one file part")
        return null
    }
    val bytes = uploadBytes
    if (bytes == null || bytes.isEmpty()) {
        respond(HttpStatusCode.BadRequest, "No file part in request")
        return null
    }

    val format = EventCoverImageProcessor.sniff(bytes.copyOfRange(0, minOf(COVER_SNIFF_BYTES, bytes.size)))
    if (format == null) {
        respond(HttpStatusCode.UnsupportedMediaType, "Nur JPEG oder PNG werden unterstuetzt.")
        return null
    }

    val result = EventCoverImageProcessor.process(bytes = bytes, format = format)
    when (result) {
        CoverProcessingResult.DimensionsTooLarge -> {
            respond(HttpStatusCode.UnprocessableEntity, "Das Bild ist zu gross (maximal ${EventCoverPolicy.MAX_EDGE_PX}px Kantenlaenge).")
            return null
        }
        CoverProcessingResult.DimensionsTooSmall -> {
            respond(
                HttpStatusCode.UnprocessableEntity,
                "Das Bild ist zu klein (mindestens ${EventCoverPolicy.MIN_LONG_EDGE_PX}x${EventCoverPolicy.MIN_SHORT_EDGE_PX}px, je nach Ausrichtung).",
            )
            return null
        }
        CoverProcessingResult.Undecodable -> {
            respond(HttpStatusCode.UnprocessableEntity, "Die Bilddatei konnte nicht gelesen werden.")
            return null
        }
        is CoverProcessingResult.Ok -> return ProcessedCoverUpload(bytes = result.bytes, format = result.format)
    }
}
