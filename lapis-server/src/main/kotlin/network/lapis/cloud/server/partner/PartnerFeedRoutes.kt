package network.lapis.cloud.server.partner

import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.withCharset
import io.ktor.server.plugins.origin
import io.ktor.server.request.header
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import network.lapis.cloud.server.federation.FederationInboxRateLimiter
import network.lapis.cloud.server.routes.rateLimitKeyFor
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

private val logger = KotlinLogging.logger {}

private val FEED_MEMO_TTL = 60.seconds

private class CachedFeed(
    val body: ByteArray,
    val etag: String,
    val expiresAt: Instant,
)

/**
 * V1.9.244 -- `GET /api/public/partner-feed/v1`: our public event feed for partner servers. No login (it only repeats what `/veranstaltung.ics`
 * shows anybody), no CORS (server-to-server), no redirect, query parameters are ignored.
 *
 * Amplification guard: the body is memoised for [FEED_MEMO_TTL], so even the full rate budget (30 requests per minute per client) never causes
 * more than one database pass a minute. `ETag` + `If-None-Match` give partners a cheap `304`. Errors answer a fixed `{"error": ...}` -- never details.
 */
fun Route.registerPartnerFeedRoutes(
    rateLimiter: FederationInboxRateLimiter,
    clock: () -> Instant = {
        network.lapis.cloud.server.time.ServerClock
            .nowInstant()
    },
) {
    val memo = AtomicReference<CachedFeed?>(null)
    get(PARTNER_FEED_PATH) {
        if (!rateLimiter.checkAndRecord(rateLimitKeyFor(remoteHost = call.request.origin.remoteHost))) {
            call.response.header(HttpHeaders.RetryAfter, "60")
            call.respondText("""{"error":"rate_limited"}""", ContentType.Application.Json, HttpStatusCode.TooManyRequests)
            return@get
        }
        val feed =
            try {
                val now = clock()
                memo.get()?.takeIf { it.expiresAt > now } ?: run {
                    val body = PartnerFeedBuilder.build(now = now)
                    // The ETag covers the content only (generatedAt would change it on every rebuild and kill every 304).
                    val etag = "\"" + sha256Hex(stripGeneratedAt(body)) + "\""
                    CachedFeed(body = body, etag = etag, expiresAt = now + FEED_MEMO_TTL).also { memo.set(it) }
                }
            } catch (e: Exception) {
                logger.warn(e) { "PartnerFeedRoutes: feed could not be built" }
                call.respondText("""{"error":"internal"}""", ContentType.Application.Json, HttpStatusCode.InternalServerError)
                return@get
            }
        call.response.header(HttpHeaders.ETag, feed.etag)
        call.response.header(HttpHeaders.CacheControl, "public, max-age=300")
        call.response.header("X-Content-Type-Options", "nosniff")
        if (ifNoneMatchHits(headerValue = call.request.header(HttpHeaders.IfNoneMatch), etag = feed.etag)) {
            call.respond(HttpStatusCode.NotModified)
            return@get
        }
        call.respondBytes(bytes = feed.body, contentType = ContentType.Application.Json.withCharset(Charsets.UTF_8))
    }
}

private fun ifNoneMatchHits(
    headerValue: String?,
    etag: String,
): Boolean {
    if (headerValue == null) return false
    if (headerValue.trim() == "*") return true
    val target = etag.trim('"')
    return headerValue.split(",").map { it.trim().removePrefix("W/") }.any { it.trim('"') == target }
}

private val GENERATED_AT = Regex("\"generatedAt\"\\s*:\\s*\"[^\"]*\"")

private fun stripGeneratedAt(body: ByteArray): ByteArray =
    GENERATED_AT.replace(String(body, Charsets.UTF_8), "").toByteArray(Charsets.UTF_8)

private fun sha256Hex(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
