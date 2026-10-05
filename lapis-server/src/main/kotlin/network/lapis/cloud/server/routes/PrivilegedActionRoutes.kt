package network.lapis.cloud.server.routes

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.plugins.origin
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import network.lapis.cloud.server.member.PrivilegedActionService
import network.lapis.cloud.server.security.LoginRateLimiter
import java.net.URI

@Serializable
data class PrivilegedActionVetoRequest(
    val token: String,
)

/**
 * Welle V1.9.57 "Admin-Peer-Schutz" -- the one unauthenticated endpoint of the four-eyes lifecycle, deliberately outside Kilua
 * RPC like the other `/api/auth/...` token endpoints (the person clicking the mail link may not be signed in):
 *
 * - `POST /api/auth/privileged-action/veto` `{token}` -- the TARGET's objection to a temporary-password request.
 *
 * **Strictly POST, never GET** -- opening the link in a mail client or link scanner must never change anything; the client
 * page only renders a button, the POST happens on a click. **No redirect, no target parameter.** **Same-origin only**
 * ([isSameOriginRequest]). **One answer, `204`, for every token** -- valid, wrong, used, expired, foreign: the caller never
 * learns which (no enumeration of requests, no timing oracle beyond the shared constant-shape lookup). `429` when the per-IP
 * budget is exhausted (every request counts, valid or not).
 */
internal fun Route.registerPrivilegedActionRoutes(
    service: PrivilegedActionService,
    ipRateLimiter: LoginRateLimiter,
    baseUrl: String,
) {
    val canonicalOrigin = runCatching { URI(baseUrl) }.getOrNull()

    post("/api/auth/privileged-action/veto") {
        if (!isSameOriginRequest(headers = call.request.headers, canonicalOrigin = canonicalOrigin)) {
            call.respond(HttpStatusCode.Forbidden, "Invalid origin")
            return@post
        }
        if (!ipRateLimiter.tryAcquire("ip:${call.request.origin.remoteHost}")) {
            call.respondText("rate-limited", status = HttpStatusCode.TooManyRequests)
            return@post
        }
        val request =
            runCatching { Json.decodeFromString(PrivilegedActionVetoRequest.serializer(), call.receiveText()) }.getOrNull()
        if (request == null || request.token.isBlank()) {
            call.respondText("invalid", status = HttpStatusCode.BadRequest)
            return@post
        }
        // The result is deliberately not reflected: a valid token ends the request, every other token does nothing -- same 204.
        service.vetoByLink(rawToken = request.token)
        call.respond(HttpStatusCode.NoContent)
    }
}
