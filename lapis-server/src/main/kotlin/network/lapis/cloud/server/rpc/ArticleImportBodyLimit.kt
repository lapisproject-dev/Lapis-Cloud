package network.lapis.cloud.server.rpc

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.call
import io.ktor.server.request.contentLength
import io.ktor.server.request.path
import io.ktor.server.response.respondText
import network.lapis.cloud.server.articles.ArticleImportPolicy

/**
 * Welle V1.9.105 -- size guard for the article-import RPC route, evaluated BEFORE Kilua reads and deserializes the body (see
 * [installEventImportBodyLimit] for the full reasoning; this is its twin for the article family).
 *
 * Kilua RPC routes are `/rpc/route<Interface without "I"><Manager><index>` (e.g. `/rpc/routeArticleImportServiceManager0`). The prefix does
 * NOT overlap with `routeArticleServiceManager` (the ordinary article service), so a normal article call with a large body is never
 * answered with 413 by this guard. A request without a `Content-Length` header (chunked) or with a value above
 * [ARTICLE_IMPORT_MAX_REQUEST_BYTES] is answered with `413` and never read.
 */
internal const val ARTICLE_IMPORT_ROUTE_PREFIX = "/rpc/routeArticleImportServiceManager"

private const val ARTICLE_IMPORT_RPC_SEGMENT = "rpc"
private const val ARTICLE_IMPORT_SEGMENT_PREFIX = "routeArticleImportServiceManager"

internal fun isArticleImportRoute(segments: List<String>): Boolean =
    segments.size >= 2 && segments[0] == ARTICLE_IMPORT_RPC_SEGMENT && segments[1].startsWith(ARTICLE_IMPORT_SEGMENT_PREFIX)

/** 4 x the 2 MiB payload (double JSON escaping, worst case) plus 512 KiB envelope slack -- same formula as the event import. */
internal const val ARTICLE_IMPORT_MAX_REQUEST_BYTES: Long = ArticleImportPolicy.MAX_PAYLOAD_BYTES * 4L + 512L * 1024L

fun Application.installArticleImportBodyLimit() {
    intercept(ApplicationCallPipeline.Setup) {
        val segments = normalizedPathSegments(call.request.path())
        if (segments != null && isArticleImportRoute(segments)) {
            val length = call.request.contentLength()
            if (length == null || length > ARTICLE_IMPORT_MAX_REQUEST_BYTES) {
                call.respondText(text = "Payload too large", status = HttpStatusCode.PayloadTooLarge)
                finish()
            }
        }
    }
}
