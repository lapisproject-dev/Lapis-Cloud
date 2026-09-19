package network.lapis.cloud.server.routes

import io.ktor.http.Cookie
import io.ktor.http.CookieEncoding
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.plugins.origin
import io.ktor.server.response.respond
import io.ktor.server.response.respondRedirect
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import network.lapis.cloud.server.federation.FederationInboxRateLimiter
import network.lapis.cloud.server.security.ApiKeyStore
import network.lapis.cloud.server.security.LoginRateLimiter
import network.lapis.cloud.server.security.SESSION_COOKIE_NAME
import network.lapis.cloud.server.security.SessionStore

/**
 * Kill switch for the WebView session bridge routes ([registerMobileWebviewSessionRoutes]) --
 * **opt-in, default OFF** (security mitigation 2026-09-19).
 *
 * Independent security audit finding (MAJOR, login CSRF / session fixation): the bridge is
 * unauthenticated by design and installs ANY valid bearer token as the browser's `lapis_session`
 * cookie. A member can craft `.../webview-session?token=<own token>` and send it to another member;
 * opening it in a desktop browser silently logs the victim into the attacker's account. Until the
 * bridge is possibly replaced by a single-use, short-lived ticket (not planned yet), the routes are only
 * registered when `LAPIS_MOBILE_WEBVIEW_BRIDGE_ENABLED` is exactly `true` (case-insensitive).
 * Unset, empty or any other value keeps them off (the paths then answer 404).
 *
 * V1.5.2 update (header bridge): the routes now authenticate via the `Authorization` header only (see
 * [registerMobileWebviewSessionRoutes]), which closes the link-based fixation vector. The default
 * nevertheless stays **OFF** -- switching it on is a separate, deliberate operator decision that
 * should follow the real-device verification of `SameSite=Strict` (Android + iOS).
 *
 * The deploy compose files deliberately do NOT pass this variable through, so PdV, ELB and Staging
 * stay off unless an operator adds it to the service `environment:` block on purpose.
 */
internal fun mobileWebviewBridgeEnabled(getenv: (String) -> String? = System::getenv): Boolean =
    getenv("LAPIS_MOBILE_WEBVIEW_BRIDGE_ENABLED")?.trim().equals("true", ignoreCase = true)

/**
 * V1.5.2 -- server-side allowlist of the generic section bridge: `section` key -> redirect target.
 * Mirrors `AppSection` in the `Lapis-Cloud-Mobile` repo one-to-one (`docs/webview-join-flow.adoc`,
 * "Required server extension"). The app never sends a route or URL, only one of these keys; the
 * value of an unknown key is NEVER reflected (no open redirect, no reflected content). Role
 * restrictions (e.g. `events` = BOARD/ADMIN) are enforced by the web route guards after the redirect.
 */
internal val MOBILE_SECTION_TARGETS: Map<String, String> =
    mapOf(
        "dashboard" to "/app#/dashboard",
        "contributions" to "/app#/contributions",
        "documents" to "/app#/documents",
        "volunteer-shifts" to "/app#/my-volunteer-shifts",
        "committees" to "/app#/committees",
        "meetings" to "/app#/meetings",
        "motions" to "/app#/motions",
        "events" to "/app#/events",
        "conference" to "/app#/conference",
    )

/**
 * Reserved, deliberately NON-allowlisted probe key -- see [registerMobileWebviewSessionRoutes]
 * "Capability probe". Never special-cased in code: it falls into the same 400 path as every other
 * unknown `section` value.
 */
internal const val MOBILE_WEBVIEW_CAPABILITY_PROBE_SECTION: String = "__capability_probe__"

/** Room ids are opaque LiveKit identifiers -- an allowlist charset, never reflected verbatim into a Location header. */
private val MOBILE_ROOM_ID_PATTERN = Regex("^[A-Za-z0-9_-]{1,64}$")

private const val WEBVIEW_BRIDGE_UNAUTHORIZED_MESSAGE = "Invalid or expired session"

/**
 * Strict, HEADER-ONLY bearer extraction for the WebView bridge. Deliberately NOT
 * [network.lapis.cloud.server.security.extractSessionToken]: that helper reads the
 * `lapis_session` COOKIE first, which is exactly the session-fixation vector this wave closes --
 * a request carrying only a (possibly foreign) cookie must never authenticate here, and must
 * never cause a Set-Cookie.
 *
 * `substring(7)`, not `removePrefix("Bearer ")`: the prefix match is case-insensitive, so a
 * `bearer `/`BEARER ` header must have its literal 7 characters cut, never the case-sensitive
 * literal again (identical reasoning to `extractSessionToken`'s own KDoc, point 1).
 *
 * An [ApiKeyStore.API_KEY_TOKEN_PREFIX]-prefixed value is rejected: a public-API key must never
 * be installable as a browser session cookie (mirror of `extractSessionToken`'s guarantee 2).
 */
internal fun extractWebviewBridgeBearerToken(call: ApplicationCall): String? {
    val authHeader = call.request.headers[HttpHeaders.Authorization] ?: return null
    if (!authHeader.startsWith("Bearer ", ignoreCase = true)) return null
    val token = authHeader.substring(7).trim()
    if (token.startsWith(ApiKeyStore.API_KEY_TOKEN_PREFIX)) return null
    return token.ifBlank { null }
}

private fun ApplicationCall.webviewBridgeIpKey(): String = "webview-bridge:${rateLimitKeyFor(remoteHost = request.origin.remoteHost)}"

/**
 * Runs gates 1-5 of the binding gate order (see [registerMobileWebviewSessionRoutes]). Returns the
 * validated raw token, or `null` if it has ALREADY responded (400/401/429) -- the caller must then
 * just `return@get` and must not respond a second time.
 */
private suspend fun ApplicationCall.authorizeWebviewBridge(
    cookieSecure: Boolean,
    failureLimiter: LoginRateLimiter,
    requestLimiter: FederationInboxRateLimiter,
): String? {
    // Gate 1: HTTPS.
    if (cookieSecure && request.origin.scheme != "https") {
        respond(HttpStatusCode.BadRequest, "HTTPS required")
        return null
    }
    val ipKey = webviewBridgeIpKey()
    // Gate 2: request-rate guard (every call costs a SHA-256 + a DB lookup, also with a valid token).
    if (!requestLimiter.checkAndRecord(ipKey)) {
        respond(HttpStatusCode.TooManyRequests, "Too many requests")
        return null
    }
    // Gate 3: IP failure budget.
    if (!failureLimiter.checkAllowed(ipKey)) {
        respond(HttpStatusCode.TooManyRequests, "Too many requests")
        return null
    }
    // Gate 4: header token. No cookie / query fallback -- there is no code path that reads either.
    val token = extractWebviewBridgeBearerToken(this)
    if (token == null) {
        failureLimiter.recordFailure(ipKey)
        respond(HttpStatusCode.Unauthorized, WEBVIEW_BRIDGE_UNAUTHORIZED_MESSAGE)
        return null
    }
    // Gate 5: session. A success deliberately clears NOTHING -- never reset(ipKey): with one valid
    // token an attacker could otherwise wipe the IP budget between brute-force bursts. There is also
    // no per-token failure key: it would be attacker-controlled key space in the limiter map, and it
    // could never trip before the IP budget (every failure counts against both).
    if (SessionStore.resolve(token) == null) {
        failureLimiter.recordFailure(ipKey)
        respond(HttpStatusCode.Unauthorized, WEBVIEW_BRIDGE_UNAUTHORIZED_MESSAGE)
        return null
    }
    return token
}

/** Gate 7: translates the already-validated token 1:1 into the WebView cookie (never mints a new token) and redirects. */
private suspend fun ApplicationCall.completeWebviewBridge(
    rawToken: String,
    target: String,
    cookieSecure: Boolean,
) {
    response.cookies.append(
        Cookie(
            name = SESSION_COOKIE_NAME,
            value = rawToken,
            encoding = CookieEncoding.URI_ENCODING,
            maxAge = SessionStore.SESSION_TTL.inWholeSeconds.toInt(),
            path = "/",
            secure = cookieSecure,
            httpOnly = true,
            // Same flag as the login cookie in AuthRoutes. An app-initiated top-level navigation
            // (loadUrl / URLRequest) has no initiator site and counts as same-site; the following 302
            // stays inside the same site. Real-device verification: see CHANGELOG / V1.5.2 notes.
            extensions = mapOf("SameSite" to "Strict"),
        ),
    )
    respondRedirect(url = target, permanent = false)
}

/**
 * V1.5.1/V1.5.2 Mobile App -- translates an already valid mobile bearer session token ONCE into a
 * `lapis_session` cookie in the WebView's own cookie jar, then redirects into the unchanged KVision
 * SPA. NEVER mints a new token -- pure bearer-to-cookie translation of the same token that
 * [SessionStore.resolve] has just validated.
 *
 * **Authentication contract (V1.5.2 header bridge)**: exclusively `Authorization: Bearer <token>`. The app starts
 * the entry navigation with the header (Android `WebView.loadUrl(url, additionalHttpHeaders)`,
 * iOS `WKWebView.load(URLRequest)` with the header set). A link opened from a browser or mail
 * client cannot set that header, which closes login CSRF / session fixation via a crafted link, and
 * the 8h token no longer appears in URLs or reverse-proxy access logs. A `?token=` query parameter
 * and the `lapis_session` cookie are NEVER read by these routes -- there is no code path that could
 * turn them into a cookie. Every 401 is `Set-Cookie`-free and carries the same generic text.
 *
 * **Gate order (BINDING)**:
 * 1. HTTPS (`cookieSecure && scheme != https` -> 400)
 * 2. request-rate guard ([requestLimiter], keyed by IP) -> 429
 * 3. IP failure budget ([failureLimiter]) -> 429
 * 4. header token present and well-formed -> else 401
 * 5. session resolves -> else 401 (a failure counts against the IP budget; a success clears NO budget)
 * 6. target resolution (section allowlist / roomId charset) -> 400, value never reflected
 * 7. `Set-Cookie` + 302
 *
 * **Rate limiting**: own budgets, shared with neither the login limiter nor the conference limiter.
 * Failures-only, keyed by IP. A success NEVER resets the IP key, so one valid token cannot be used
 * to clear brute-force counters. Deliberately no per-token key: every failure would count against
 * the IP key too, so a token key could never trip first, while each attacker-chosen token would
 * add an entry to the limiter map that only expiry (not capacity pressure) removes.
 *
 * **Capability probe**: the app probes for the generic route with the reserved, non-allowlisted
 * `section=__capability_probe__` ([MOBILE_WEBVIEW_CAPABILITY_PROBE_SECTION]) plus a valid header:
 * 400 = route present, 401 = header missing/invalid, 404 = bridge switched off (kill switch) or
 * route unknown. Because target resolution comes after the session gate, an unauthenticated probe
 * answers 401, not 400.
 *
 * **Kill switch**: registration stays gated by [mobileWebviewBridgeEnabled] (default OFF).
 */
fun Route.registerMobileWebviewSessionRoutes(
    cookieSecure: Boolean,
    failureLimiter: LoginRateLimiter,
    requestLimiter: FederationInboxRateLimiter,
) {
    // Literal route first, then the parameterised one.
    get("/api/mobile/v1/webview-session") {
        val token =
            call.authorizeWebviewBridge(
                cookieSecure = cookieSecure,
                failureLimiter = failureLimiter,
                requestLimiter = requestLimiter,
            ) ?: return@get
        // Only now the section: an unknown/missing key is a fixed 400, the value is never echoed.
        val target = call.request.queryParameters["section"]?.let { MOBILE_SECTION_TARGETS[it] }
        if (target == null) {
            call.respond(HttpStatusCode.BadRequest, "unknown section")
            return@get
        }
        call.completeWebviewBridge(rawToken = token, target = target, cookieSecure = cookieSecure)
    }

    get("/api/mobile/v1/conference/rooms/{roomId}/webview-session") {
        val token =
            call.authorizeWebviewBridge(
                cookieSecure = cookieSecure,
                failureLimiter = failureLimiter,
                requestLimiter = requestLimiter,
            ) ?: return@get
        val roomId = call.parameters["roomId"]
        if (roomId == null || !MOBILE_ROOM_ID_PATTERN.matches(roomId)) {
            call.respond(HttpStatusCode.BadRequest, "unknown room")
            return@get
        }
        call.completeWebviewBridge(rawToken = token, target = "/app#/conference/$roomId", cookieSecure = cookieSecure)
    }
}
