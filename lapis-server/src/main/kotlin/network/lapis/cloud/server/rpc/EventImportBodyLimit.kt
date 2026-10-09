package network.lapis.cloud.server.rpc

import io.ktor.http.HttpStatusCode
import io.ktor.http.decodeURLPart
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.call
import io.ktor.server.request.contentLength
import io.ktor.server.request.path
import io.ktor.server.response.respondText
import network.lapis.cloud.server.events.EventImportPolicy

/**
 * Welle V1.9.82 -- size guard for the event-import RPC route, evaluated BEFORE Kilua reads and deserializes the body (a limit inside the
 * service would only trigger after the whole body was already in memory).
 *
 * Kilua RPC routes are `/rpc/route<Interface without "I"><Manager><index>` (e.g. `/rpc/routeEventImportServiceManager0`); this guard
 * matches on the common prefix so both methods are covered. A request without a `Content-Length` header (chunked) or with a value above
 * [MAX_REQUEST_BYTES] is answered with `413` and never read. The limit covers the 2 MiB payload after double JSON escaping plus envelope.
 * Browsers always send a `Content-Length` for a string body, so the legitimate client is unaffected. Authentication still happens in the
 * service (BOARD/ADMIN); this only bounds memory use of an unauthenticated or low-privileged caller.
 */
internal const val EVENT_IMPORT_ROUTE_PREFIX = "/rpc/routeEventImportServiceManager"

private const val RPC_SEGMENT = "rpc"
private const val EVENT_IMPORT_SEGMENT_PREFIX = "routeEventImportServiceManager"

/**
 * Normalizes a raw request path the way Ktor routing does before matching: split on '/', skip empty segments, percent-decode every
 * segment. Returns `null` if a segment has invalid percent-encoding.
 */
internal fun normalizedPathSegments(rawPath: String): List<String>? =
    try {
        rawPath.split('/').filter { it.isNotEmpty() }.map { it.decodeURLPart() }
    } catch (_: Exception) {
        null
    }

internal fun isEventImportRoute(segments: List<String>): Boolean =
    segments.size >= 2 && segments[0] == RPC_SEGMENT && segments[1].startsWith(EVENT_IMPORT_SEGMENT_PREFIX)

/**
 * Kilua sends every RPC parameter as a JSON-encoded string INSIDE the JSON request envelope, so the import text is escaped twice. The
 * worst case is 4 bytes on the wire per payload byte (a `"` becomes `\\\"`, a `\\` becomes four backslashes; a `\\uXXXX` escape from a
 * non-ASCII export grows from 6 to 8 bytes), so the limit is 4 x the 2 MiB payload plus 512 KiB envelope slack. That still bounds memory
 * (about 8.5 MiB) while a legitimate near-limit file never gets a bare 413 instead of a validation message.
 */
internal const val MAX_REQUEST_BYTES: Long = EventImportPolicy.MAX_PAYLOAD_BYTES * 4L + 512L * 1024L

fun Application.installEventImportBodyLimit() {
    intercept(ApplicationCallPipeline.Setup) {
        val segments = normalizedPathSegments(call.request.path())
        if (segments == null) {
            call.respondText(text = "Bad request", status = HttpStatusCode.BadRequest)
            finish()
        } else if (isEventImportRoute(segments)) {
            val length = call.request.contentLength()
            if (length == null || length > MAX_REQUEST_BYTES) {
                call.respondText(text = "Payload too large", status = HttpStatusCode.PayloadTooLarge)
                finish()
            }
        }
    }
}
