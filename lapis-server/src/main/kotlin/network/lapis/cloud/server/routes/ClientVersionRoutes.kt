package network.lapis.cloud.server.routes

import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.plugins.origin
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import network.lapis.cloud.server.clientversion.ClientBuildId
import network.lapis.cloud.server.federation.FederationInboxRateLimiter

/**
 * Welle V1.4.20 "Client-Hinweis: Neue Version verfuegbar" -- `GET /api/client-version`: answers with
 * the build id of the client bundle this server currently serves (see [ClientBuildId]), as
 * `text/plain` -- exactly [ClientBuildId.ID_LENGTH] hex characters, nothing else (no JSON, no DTO).
 * A long-lived browser tab compares it against the id it was loaded with and, on a mismatch, shows
 * a non-blocking "new version available" hint (see the client's `ClientVersionWatcher`).
 *
 * **Public, read-only, leaks nothing.** The id is a hash over a publicly downloadable file
 * (`GET /main.bundle.js`), so this route reveals no dependency version, host name or path. It reads
 * no query parameter, no body and no header except the remote host for the rate limit; there is no
 * write path. `Cache-Control: no-store` (a cached answer would defeat the purpose) and
 * `X-Content-Type-Options: nosniff`. No `Vary: Authorization` -- the answer is identity independent.
 *
 * **DoS.** Per-IP rate limit (IPv6 normalised to /64 by [rateLimitKeyFor]) with `Retry-After`; the
 * 429 carries an empty body. The hash itself is computed once per process, not per request:
 * [buildIdProvider] is a supplier so `Application.kt`'s `by lazy` stays lazy (the route
 * registration at server start does not trigger the hashing).
 */
internal fun Route.registerClientVersionRoutes(
    rateLimiter: FederationInboxRateLimiter,
    buildIdProvider: () -> String?,
) {
    get(ClientBuildId.ROUTE_PATH) {
        call.response.header(HttpHeaders.CacheControl, "no-store")
        call.response.header("X-Content-Type-Options", "nosniff")
        val key = rateLimitKeyFor(remoteHost = call.request.origin.remoteHost)
        if (!rateLimiter.checkAndRecord(key)) {
            call.response.header(HttpHeaders.RetryAfter, rateLimiter.retryAfterSeconds(key).toString())
            call.respond(HttpStatusCode.TooManyRequests)
            return@get
        }
        val id = buildIdProvider()
        if (id == null || !ClientBuildId.isWellFormed(id)) {
            call.respond(HttpStatusCode.NotFound)
            return@get
        }
        call.respondText(text = id, contentType = ContentType.Text.Plain)
    }
}
