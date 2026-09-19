package network.lapis.cloud.server.routes

import io.ktor.http.Cookie
import io.ktor.http.CookieEncoding
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.plugins.origin
import io.ktor.server.response.respond
import io.ktor.server.response.respondRedirect
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
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
 * bridge is replaced by a single-use, short-lived ticket (V1.5.2 ticket bridge), the routes are only
 * registered when `LAPIS_MOBILE_WEBVIEW_BRIDGE_ENABLED` is exactly `true` (case-insensitive).
 * Unset, empty or any other value keeps them off (the paths then answer 404).
 *
 * The deploy compose files deliberately do NOT pass this variable through, so PdV, ELB and Staging
 * stay off unless an operator adds it to the service `environment:` block on purpose.
 */
internal fun mobileWebviewBridgeEnabled(getenv: (String) -> String? = System::getenv): Boolean =
    getenv("LAPIS_MOBILE_WEBVIEW_BRIDGE_ENABLED")?.trim().equals("true", ignoreCase = true)

/**
 * V1.5.1 Mobile App -- übersetzt einen bereits gültigen mobilen Bearer-Session-Token EINMALIG in
 * ein `lapis_session`-Cookie im WebView-eigenen Cookie-Jar, dann Redirect in die unveränderte
 * KVision-SPA. Mintet NIEMALS einen neuen Token -- reine Cookie/Bearer-Übersetzung desselben,
 * bereits durch [SessionStore.resolve] validierten Tokens. Siehe das `Lapis-Cloud-Mobile`-Repo,
 * `docs/webview-join-flow.adoc`, für den vollständigen Sicherheits-Kontrakt (Query-Parameter-
 * Token-Risiko + Gegenmaßnahmen).
 *
 * **Warum ein Query-Parameter, nicht `Authorization`-Header**: dieser Endpunkt wird von der
 * WebView SELBST per Navigation aufgerufen (nicht vom nativen Ktor-Client der App) -- eine
 * WebView-Navigation kann keinen Custom-Header setzen. Das Sicherheitsrisiko eines Tokens in der
 * URL wird dadurch begrenzt, dass (a) kein neuer Token gemintet wird -- der bereits validierte
 * Bearer-Token wird nur 1:1 als Cookie übersetzt --, (b) die Zielseite (`/app`) keine externen
 * Drittanbieter-Ressourcen mit Referrer-Tracking lädt, und (c) die WebView ohne Adressleiste läuft
 * (kein System-Browser, kein für die App-Bedienperson einsehbarer Verlauf).
 */
fun Route.registerMobileWebviewSessionRoutes(
    cookieSecure: Boolean,
    rateLimiter: LoginRateLimiter,
) {
    get("/api/mobile/v1/conference/rooms/{roomId}/webview-session") {
        val roomId = call.parameters["roomId"]
        val token = call.request.queryParameters["token"]
        if (roomId.isNullOrBlank() || token.isNullOrBlank()) {
            call.respond(HttpStatusCode.BadRequest, "roomId and token are required")
            return@get
        }
        if (cookieSecure && call.request.origin.scheme != "https") {
            call.respond(HttpStatusCode.BadRequest, "HTTPS required")
            return@get
        }
        val ipKey = "ip:${call.request.origin.remoteHost}"
        if (!rateLimiter.checkAllowed(ipKey)) {
            call.respond(HttpStatusCode.TooManyRequests, "Too many requests")
            return@get
        }
        val resolved = SessionStore.resolve(token)
        if (resolved == null) {
            rateLimiter.recordFailure(ipKey)
            call.respond(HttpStatusCode.Unauthorized, "Invalid or expired session")
            return@get
        }
        rateLimiter.reset(ipKey)
        call.response.cookies.append(
            Cookie(
                name = SESSION_COOKIE_NAME,
                value = token,
                encoding = CookieEncoding.URI_ENCODING,
                maxAge = SessionStore.SESSION_TTL.inWholeSeconds.toInt(),
                path = "/",
                secure = cookieSecure,
                httpOnly = true,
                // "Strict" würde diesen Redirect-Cookie beim allerersten Cross-Navigation-Sprung
                // (WebView-Navigation zu dieser Route kommt vom nativen App-Code, nicht von
                // derselben Origin) potenziell verwerfen -- "Lax" reicht hier, weil die eigentlich
                // schützenswerte Aktion (Login/Passwort) nicht über diesen Endpunkt läuft, sondern
                // nur ein bereits gültiger Token weitergereicht wird.
                extensions = mapOf("SameSite" to "Lax"),
            ),
        )
        call.respondRedirect(url = "/app#/conference/$roomId", permanent = false)
    }
}
