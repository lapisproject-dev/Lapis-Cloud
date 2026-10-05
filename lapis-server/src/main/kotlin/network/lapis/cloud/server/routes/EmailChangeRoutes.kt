package network.lapis.cloud.server.routes

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.plugins.origin
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import network.lapis.cloud.server.member.EmailChangeService
import network.lapis.cloud.server.member.LinkResult
import network.lapis.cloud.server.security.LoginRateLimiter
import java.net.URI

@Serializable
data class EmailChangeConfirmRequest(
    val token: String,
    /** Mandatory for a proposal the owner accepts (path B), must be absent for the ownership-proof links (B0/C). */
    val password: String? = null,
)

@Serializable
data class EmailChangeRevokeRequest(
    val token: String,
)

/**
 * Welle V1.9.56 "E-Mail-Änderung absichern" -- the two unauthenticated link endpoints of the address-change lifecycle,
 * deliberately outside Kilua RPC like the other `/api/auth/...` token endpoints (the person clicking a mail link has no
 * session):
 *
 * - `POST /api/auth/email-change/confirm` `{token, password?}` -- link to the NEW address.
 * - `POST /api/auth/email-change/revoke` `{token}` -- link to the OLD address, rejects the change.
 *
 * **Strictly POST, never GET** -- opening a link in a mail client (or a link scanner) must never change anything; the
 * client pages only render a button, the POST happens on a click. **No redirect, no target parameter** (no open
 * redirect): the page navigates clientside to a fixed route afterwards. **Same-origin only** ([isSameOriginRequest],
 * defence in depth on top of the unguessable 256-bit token). **One answer for every unusable token** (wrong, used,
 * expired, foreign, wrong kind): `400 invalid`. `400 wrong-password` is returned only for a VALID token (anyone holding
 * a 256-bit token already knows it is valid); five wrong passwords burn that change. `429` when the per-IP budget is
 * exhausted (every request counts, valid or not).
 */
internal fun Route.registerEmailChangeRoutes(
    service: EmailChangeService,
    ipRateLimiter: LoginRateLimiter,
    baseUrl: String,
) {
    val canonicalOrigin = runCatching { URI(baseUrl) }.getOrNull()

    suspend fun ApplicationCall.guard(): Boolean {
        if (!isSameOriginRequest(headers = request.headers, canonicalOrigin = canonicalOrigin)) {
            respond(HttpStatusCode.Forbidden, "Invalid origin")
            return false
        }
        val ipKey = "ip:${request.origin.remoteHost}"
        if (!ipRateLimiter.tryAcquire(ipKey)) {
            respondText("rate-limited", status = HttpStatusCode.TooManyRequests)
            return false
        }
        return true
    }

    suspend fun ApplicationCall.respondLink(result: LinkResult) {
        when (result) {
            LinkResult.OK -> respond(HttpStatusCode.NoContent)
            LinkResult.CONFIRMED_PENDING -> respond(HttpStatusCode.Accepted)
            LinkResult.INVALID -> respondText("invalid", status = HttpStatusCode.BadRequest)
            LinkResult.WRONG_PASSWORD -> respondText("wrong-password", status = HttpStatusCode.BadRequest)
            LinkResult.ADDRESS_UNAVAILABLE -> respondText("unavailable", status = HttpStatusCode.BadRequest)
            LinkResult.RATE_LIMITED -> respondText("rate-limited", status = HttpStatusCode.TooManyRequests)
        }
    }

    post("/api/auth/email-change/confirm") {
        if (!call.guard()) return@post
        val request = runCatching { Json.decodeFromString(EmailChangeConfirmRequest.serializer(), call.receiveText()) }.getOrNull()
        if (request == null || request.token.isBlank()) {
            call.respondText("invalid", status = HttpStatusCode.BadRequest)
            return@post
        }
        call.respondLink(service.confirmByLink(rawToken = request.token, password = request.password))
    }

    post("/api/auth/email-change/revoke") {
        if (!call.guard()) return@post
        val request = runCatching { Json.decodeFromString(EmailChangeRevokeRequest.serializer(), call.receiveText()) }.getOrNull()
        if (request == null || request.token.isBlank()) {
            call.respondText("invalid", status = HttpStatusCode.BadRequest)
            return@post
        }
        call.respondLink(service.revokeByLink(rawToken = request.token))
    }
}
