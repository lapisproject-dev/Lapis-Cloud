package network.lapis.cloud.server.routes

import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.plugins.origin
import io.ktor.server.response.header
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondRedirect
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import kotlinx.coroutines.CancellationException
import network.lapis.cloud.server.db.DbClock
import network.lapis.cloud.server.federation.FederationInboxRateLimiter
import network.lapis.cloud.server.mail.newsletter.MailingTrackingData
import network.lapis.cloud.server.mail.newsletter.MailingTrackingTarget
import network.lapis.cloud.server.mail.newsletter.MailingTrackingToken
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.uuid.Uuid

private val logger = KotlinLogging.logger {}

/** The classic 43-byte transparent 1x1 GIF. */
private val TRANSPARENT_GIF: ByteArray =
    "47494638396101000100800000000000ffffff21f90401000000002c00000000010001000002024401003b"
        .chunked(2)
        .map { it.toInt(16).toByte() }
        .toByteArray()

private const val GIF_SUFFIX = ".gif"

/**
 * Welle V1.9.15 -- the two public tracking endpoints, registered before `staticFiles`:
 *
 *  - `GET /m/c/{token}` -- click redirect. The target URL is resolved ONLY from the message's own
 *    `mailing_message_link` row addressed by (delivery -> message, link index); no query parameter
 *    or path segment is ever read as a destination, so this is not an open redirect. The target is
 *    re-validated (http/https, no control characters/CRLF, length, parseable, ASCII-encoded) before a
 *    `302` is emitted. EVERY failure is the same neutral 404 page WITHOUT a `Location` header.
 *  - `GET /m/o/{token}.gif` -- open pixel. ALWAYS answers the same 43-byte GIF with 200 (even over the
 *    rate limit: no oracle for "is this a valid token?").
 *
 * **Counting condition** (all must hold): not a `HEAD` request (`AutoHeadResponse` routes scanners'
 * HEAD probes into the GET handler -- they must never count), the token parses and verifies, the
 * delivery's send-time SNAPSHOT is on, AND the recipient's CURRENT consent is still set on a still-
 * active subscription of a non-anonymized member (withdrawal is effective for mails already sent).
 * A failed check still redirects/answers normally -- it just does not count.
 *
 * Nothing here logs a token, nonce, target URL or member id.
 */
internal fun Route.registerMailingTrackingRoutes(
    trackingToken: MailingTrackingToken,
    clickRateLimiter: FederationInboxRateLimiter,
    pixelRateLimiter: FederationInboxRateLimiter,
    brandTitle: String,
) {
    get("/m/c/{token}") {
        call.withTrackingErrorHandling(brandTitle = brandTitle) {
            call.handleClick(trackingToken = trackingToken, rateLimiter = clickRateLimiter, brandTitle = brandTitle)
        }
    }
    get("/m/o/{file}") {
        call.withPixelErrorHandling {
            call.handlePixel(trackingToken = trackingToken, rateLimiter = pixelRateLimiter)
        }
    }
}

private suspend fun ApplicationCall.handleClick(
    trackingToken: MailingTrackingToken,
    rateLimiter: FederationInboxRateLimiter,
    brandTitle: String,
) {
    applyTrackingHeaders()
    if (!rateLimiter.checkAndRecord(rateLimitKeyFor(remoteHost = request.origin.remoteHost))) {
        response.header(HttpHeaders.RetryAfter, "60")
        applyPublicPageHeaders()
        respondText(MailingTrackingHtml.tooManyRequestsPage(brandTitle), HTML_CONTENT_TYPE, HttpStatusCode.TooManyRequests)
        return
    }
    val parsed = parameters["token"]?.let { trackingToken.parse(it) } as? MailingTrackingToken.Parsed.Click
    val resolved =
        parsed?.let { click ->
            transaction {
                val ref = MailingTrackingData.findDeliveryByHash(MailingTrackingToken.hashNonce(click.nonce))
                val target =
                    ref
                        ?.let {
                            MailingTrackingData.resolveTarget(messageId = it.messageId, linkIndex = click.linkIndex)
                        }?.let(MailingTrackingTarget::safeRedirectTarget)
                if (ref == null || target == null) {
                    null
                } else {
                    ResolvedClick(
                        deliveryLogId = ref.deliveryLogId,
                        linkIndex = click.linkIndex,
                        target = target,
                        mayCount = MailingTrackingData.mayCountClick(ref),
                    )
                }
            }
        }
    if (resolved == null) {
        respondNotFound(brandTitle)
        return
    }
    if (resolved.mayCount && !isHeadRequest()) {
        MailingTrackingData.recordClick(
            deliveryLogId = resolved.deliveryLogId,
            linkIndex = resolved.linkIndex,
            now = DbClock.nowLocalDateTime(),
        )
    }
    respondRedirect(resolved.target, permanent = false)
}

private data class ResolvedClick(
    val deliveryLogId: Uuid,
    val linkIndex: Int,
    val target: String,
    val mayCount: Boolean,
)

private suspend fun ApplicationCall.handlePixel(
    trackingToken: MailingTrackingToken,
    rateLimiter: FederationInboxRateLimiter,
) {
    applyTrackingHeaders()
    val withinLimit = rateLimiter.checkAndRecord(rateLimitKeyFor(remoteHost = request.origin.remoteHost))
    val token = parameters["file"]?.takeIf { it.endsWith(GIF_SUFFIX) }?.removeSuffix(GIF_SUFFIX)
    val parsed = token?.let { trackingToken.parse(it) } as? MailingTrackingToken.Parsed.Open
    if (withinLimit && parsed != null && !isHeadRequest()) {
        val ref =
            transaction {
                MailingTrackingData
                    .findDeliveryByHash(
                        MailingTrackingToken.hashNonce(parsed.nonce),
                    )?.takeIf { MailingTrackingData.mayCountOpen(it) }
            }
        if (ref != null) MailingTrackingData.recordOpen(deliveryLogId = ref.deliveryLogId, now = DbClock.nowLocalDateTime())
    }
    respondPixel()
}

/**
 * `AutoHeadResponse` (installed globally) rewrites the ORIGIN method of a HEAD request to GET before
 * routing, so `request.httpMethod`/`request.origin.method` already read GET in the handler -- only
 * `request.local.method` still carries what the client actually sent.
 */
private fun ApplicationCall.isHeadRequest(): Boolean = request.local.method == HttpMethod.Head

private fun ApplicationCall.applyTrackingHeaders() {
    response.header(HttpHeaders.CacheControl, "no-store")
    response.header("Referrer-Policy", "no-referrer")
    response.header("X-Robots-Tag", "noindex")
    response.header("X-Content-Type-Options", "nosniff")
}

private suspend fun ApplicationCall.respondNotFound(brandTitle: String) {
    applyPublicPageHeaders()
    respondText(MailingTrackingHtml.notFoundPage(brandTitle), HTML_CONTENT_TYPE, HttpStatusCode.NotFound)
}

private suspend fun ApplicationCall.respondPixel() {
    respondBytes(TRANSPARENT_GIF, ContentType.Image.GIF, HttpStatusCode.OK)
}

private suspend fun ApplicationCall.withTrackingErrorHandling(
    brandTitle: String,
    block: suspend () -> Unit,
) {
    try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        // No path, no token, no target in the log -- the URL itself is the credential.
        logger.error { "GET /m/c failed (${e::class.simpleName})" }
        runCatching { respondNotFound(brandTitle) }
    }
}

private suspend fun ApplicationCall.withPixelErrorHandling(block: suspend () -> Unit) {
    try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        logger.error { "GET /m/o failed (${e::class.simpleName})" }
        runCatching { respondPixel() }
    }
}
