package network.lapis.cloud.server.routes

import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.plugins.origin
import io.ktor.server.request.path
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import kotlinx.coroutines.CancellationException
import network.lapis.cloud.server.embed.EmbedConfig
import network.lapis.cloud.server.embed.EmbedCorsResult
import network.lapis.cloud.server.embed.applyEmbedCors
import network.lapis.cloud.server.embed.respondEmbedForbiddenOrigin
import network.lapis.cloud.server.federation.FederationInboxRateLimiter
import network.lapis.cloud.server.rpc.MembershipApplicationStore
import network.lapis.cloud.server.rpc.membershipApplicationOpen

private val logger = KotlinLogging.logger {}

private val EMBED_JOIN_JSON_CONTENT_TYPE = ContentType.Application.Json.withParameter("charset", "utf-8")

/**
 * Welle V1.9.315 -- `GET /api/embed/v1/join`, answering exactly `{"open":true|false}`: whether the "Mitglied werden" building block of the
 * website widget should be shown. Called from [registerEmbedRoutes] INSIDE the `LAPIS_EMBED_ENABLED` gate (embed off => 404 as before).
 *
 * Same posture as the other embed GET feeds: per-handler CORS via [applyEmbedCors] (a present-but-disallowed `Origin` -> 403 before any
 * database access; no `Origin` -> served, public by design), an IP rate limit, `X-Content-Type-Options: nosniff`. `Cache-Control: no-store`
 * and `Vary: Origin` are set by [applyEmbedCors] itself -- NOT set a second time here. No cookie is read or written. The widget fails open:
 * on any error or a non-allowed page it keeps its plain fallback link. The server gate in `RegistrationService` stays the authority.
 *
 * [keycloakEnabled] is `keycloakConfig.enabled`, the same flag the server gate uses.
 */
internal fun Route.registerEmbedJoinRoutes(
    config: EmbedConfig,
    keycloakEnabled: Boolean,
    rateLimiter: FederationInboxRateLimiter,
) {
    get("/api/embed/v1/join") {
        try {
            val cors = call.applyEmbedCors(allowlist = config.allowlist, allowInsecure = config.allowInsecureOrigins)
            if (cors is EmbedCorsResult.Rejected) {
                call.respondEmbedForbiddenOrigin()
                return@get
            }
            val rateLimitKey = rateLimitKeyFor(remoteHost = call.request.origin.remoteHost)
            if (!rateLimiter.checkAndRecord(rateLimitKey)) {
                call.response.header(HttpHeaders.RetryAfter, rateLimiter.retryAfterSeconds(rateLimitKey).toString())
                call.respond(HttpStatusCode.TooManyRequests)
                return@get
            }
            val open =
                membershipApplicationOpen(enabled = MembershipApplicationStore.currentValueForDisplay(), keycloakEnabled = keycloakEnabled)
            call.response.header("X-Content-Type-Options", "nosniff")
            call.respondText(text = "{\"open\":$open}", contentType = EMBED_JOIN_JSON_CONTENT_TYPE, status = HttpStatusCode.OK)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.error(e) { "Unhandled exception in the embed join handler (${call.request.path()})" }
            runCatching { call.respond(HttpStatusCode.InternalServerError) }
        }
    }
}
