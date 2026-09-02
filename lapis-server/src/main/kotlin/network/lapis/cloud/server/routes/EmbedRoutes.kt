package network.lapis.cloud.server.routes

import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.plugins.origin
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.options
import kotlinx.serialization.Serializable
import network.lapis.cloud.server.branding.BrandConfig
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.server.embed.EmbedAssets
import network.lapis.cloud.server.embed.EmbedConfig
import network.lapis.cloud.server.embed.EmbedCorsResult
import network.lapis.cloud.server.embed.applyEmbedCors
import network.lapis.cloud.server.embed.respondEmbedForbiddenOrigin
import network.lapis.cloud.server.embed.respondEmbedPreflight
import network.lapis.cloud.server.federation.FederationConfig
import network.lapis.cloud.server.federation.FederationInboxRateLimiter
import network.lapis.cloud.server.security.SESSION_COOKIE_NAME
import network.lapis.cloud.server.security.SessionStore
import network.lapis.cloud.server.security.requireRole
import network.lapis.cloud.server.security.resolveCurrentMember
import network.lapis.cloud.shared.domain.AccountRole
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.security.MessageDigest

private val STATE_PATTERN = Regex("^[0-9a-f]{32}$")
private val JS_CONTENT_TYPE = ContentType("application", "javascript").withParameter("charset", "utf-8")
private val JSON_CONTENT_TYPE = ContentType.Application.Json.withParameter("charset", "utf-8")

/**
 * Welle V1.4.1a "Öffentliche Website-Integration -- Fundament + Login-Widget". Registers the
 * routes under `/embed/v1/` and `/api/embed/v1/` that let a partner (party/association) website
 * embed a "Login"/"Mitglied werden" widget without ever sharing a session cookie across origins --
 * see `docs/api/embed-widgets.adoc` for the full partner-facing contract and
 * `network.lapis.cloud.server.embed.EmbedCors`/[EmbedHtml] for the two halves of the security
 * story this file wires together.
 *
 * **Registered BEFORE `staticFiles` in `Application.kt`**, same "literal beats catch-all" reasoning
 * as [registerSocialPublicRoutes]'s own routes.
 *
 * **A `false` [EmbedConfig.enabled] means this function registers only the ADMIN status endpoint**
 * (`GET /api/embed/v1/admin/status`, still ADMIN-gated) -- every OTHER path under `/embed/` or
 * `/api/embed/` then falls through to `staticFiles`' 404, exactly as if this welle did not exist.
 * The admin status endpoint stays registered unconditionally so an ADMIN who opens the
 * "Website-Integration" screen on an installation that never set `LAPIS_EMBED_ENABLED=true` (the
 * default -- see the `.env.example` files under `deploy/`) sees an honest `enabled: false` status
 * instead of a bare 404 that is indistinguishable from a real server error (Review-Fund V1.4.1a,
 * [EmbedRoutesCorsTest]).
 * `EmbedConfig.load()` itself already fail-fasts at startup if `enabled=true` with an unusable
 * allowlist (see that class's own KDoc), so whenever `enabled` is `true`, the config always carries
 * a non-empty, valid allowlist.
 *
 * **`baseUrl` comes exclusively from [FederationConfig.publicBaseUrl]**, never from the request's
 * `Host` header -- same Host-header-injection hardening as every other public route family in this
 * server.
 */
fun Route.registerEmbedRoutes(
    config: EmbedConfig,
    assetRateLimiter: FederationInboxRateLimiter,
    loginPageRateLimiter: FederationInboxRateLimiter,
    sessionRateLimiter: FederationInboxRateLimiter,
    adminStatusRateLimiter: FederationInboxRateLimiter,
    brandTitle: String = BrandConfig.DEFAULT_TITLE,
) {
    // Registered FIRST, unconditionally -- see this function's own KDoc "A false EmbedConfig.enabled
    // means...". Must work whether or not the block below runs, so it is wired before the early
    // return, not inside it.
    registerEmbedAdminStatusRoute(config = config, adminStatusRateLimiter = adminStatusRateLimiter)

    if (!config.enabled) return
    val baseUrl = FederationConfig.publicBaseUrl.trimEnd('/')
    // Computed ONCE at route-registration time, never per request -- see EmbedAssets.widgetJs KDoc.
    val widgetJsBody = EmbedAssets.widgetJs(config.allowlist)
    val widgetJsETag = computeEmbedETag(widgetJsBody)
    val loginJsBody = EmbedAssets.loginPopupJs
    val loginJsETag = computeEmbedETag(loginJsBody)

    get("/embed/v1/lapis-widgets.js") {
        if (!assetRateLimiter.checkAndRecord(rateLimitKeyFor(remoteHost = call.request.origin.remoteHost))) {
            call.response.header(HttpHeaders.CacheControl, "no-store")
            call.respond(HttpStatusCode.TooManyRequests)
            return@get
        }
        call.respondEmbedAsset(body = widgetJsBody, etag = widgetJsETag)
    }

    get("/embed/v1/login.js") {
        if (!assetRateLimiter.checkAndRecord(rateLimitKeyFor(remoteHost = call.request.origin.remoteHost))) {
            call.response.header(HttpHeaders.CacheControl, "no-store")
            call.respond(HttpStatusCode.TooManyRequests)
            return@get
        }
        call.respondEmbedAsset(body = loginJsBody, etag = loginJsETag)
    }

    // Popup-Seite -- same-origin (der Aufrufer ist das window.open()-Fenster, nicht die Partner-
    // Origin selbst), daher KEIN applyEmbedCors hier. Der komplette Handler-Körper läuft in
    // runCatching, damit niemals ein nackter 500 ohne applyEmbedPageHeaders()/no-store entweicht.
    //
    // Der Sign-in-Status ist HIER STRUKTURELL IMMER unbekannt (EmbedHtml.loginPage kennt seit
    // Review-Fund V1.4.1a gar keinen signedIn/displayName-Parameter mehr -- siehe dessen KDoc):
    // der Aufruf, der diese Seite lädt, ist window.open(...) VON DER PARTNER-ORIGIN AUS -- also
    // eine cross-site Top-Level-Navigation.
    // Der Session-Cookie trägt SameSite=Strict (siehe AuthRoutes.kt KDoc "Cookie transport"), und
    // SameSite=Strict wird -- anders als Lax -- gerade NICHT bei einer cross-site Top-Level-
    // Navigation mitgeschickt. Ein bereits eingeloggtes Mitglied kann diese Seite deshalb nie mit
    // seinem bestehenden lapis_session-Cookie erreichen; jeder Popup-Aufruf verlangt zwangsläufig
    // eine frische Anmeldung. Das ist bewusst so belassen (SameSite=Strict ist laut AuthRoutes.kt
    // die einzige CSRF-Absicherung dieser Codebase; sie für dieses eine Popup auf Lax zu lockern
    // wäre eine sitesweite Sicherheitsentscheidung, die diese Welle nicht trifft) -- siehe
    // `docs/api/embed-widgets.adoc` "Known, deliberate limitation" für die partner-seitige Doku
    // dieser Grenze. Ehemals versuchte diese Route trotzdem, das Cookie aufzulösen und den
    // Anzeigenamen aus der DB zu laden -- das war auf jedem Aufruf nutzlose Arbeit, weil das Cookie
    // hier nie ankommt (Review-Fund V1.4.1a); entfernt.
    get("/embed/v1/login") {
        runCatching {
            call.response.header(HttpHeaders.CacheControl, "no-store")
            if (!loginPageRateLimiter.checkAndRecord(rateLimitKeyFor(remoteHost = call.request.origin.remoteHost))) {
                call.applyEmbedPageHeaders()
                call.respond(HttpStatusCode.TooManyRequests)
                return@runCatching
            }
            val rawState = call.request.queryParameters["state"]
            if (rawState == null || !STATE_PATTERN.matches(rawState)) {
                call.applyEmbedPageHeaders()
                call.respondText(
                    text = EmbedHtml.badRequestPage(baseUrl = baseUrl, brandTitle = brandTitle),
                    contentType = HTML_CONTENT_TYPE,
                    status = HttpStatusCode.BadRequest,
                )
                return@runCatching
            }
            val rawOrigin = call.request.queryParameters["origin"]
            val canonicalOrigin = config.allowlist.canonicalize(rawOrigin)
            if (canonicalOrigin == null) {
                call.applyEmbedPageHeaders()
                call.respondText(
                    text = EmbedHtml.rejectedPage(baseUrl = baseUrl, brandTitle = brandTitle),
                    contentType = HTML_CONTENT_TYPE,
                    status = HttpStatusCode.Forbidden,
                )
                return@runCatching
            }
            val requesterHost = runCatching { java.net.URI(canonicalOrigin).host }.getOrNull() ?: canonicalOrigin

            // Kein Cookie-Lookup, keine DB-Abfrage hier -- siehe die KDoc-Notiz direkt über diesem
            // Handler ("Der Sign-in-Status ist HIER STRUKTURELL IMMER unbekannt"). SameSite=Strict
            // verhindert ohnehin, dass das lapis_session-Cookie bei dieser cross-site
            // Top-Level-Navigation je ankommt; `/embed/v1/login.js` bestimmt den Status stattdessen
            // zur Laufzeit über die same-origin `/api/embed/v1/session`-Probe.
            val body =
                EmbedHtml.loginPage(
                    baseUrl = baseUrl,
                    brandTitle = brandTitle,
                    requesterOriginHost = requesterHost,
                    targetOrigin = canonicalOrigin,
                    state = rawState,
                )
            call.applyEmbedPageHeaders()
            call.respondText(text = body, contentType = HTML_CONTENT_TYPE)
        }.onFailure {
            // Cache-Control: no-store is already set -- it is the very first statement in the
            // runCatching block above, and header() APPENDS rather than replaces, so re-setting it
            // here would duplicate it on the eventual response (same pattern already fixed once in
            // EmbedCors.kt's respondEmbedPreflight -- Review-Fund V1.4.1a caught the sibling case
            // here too). Everything below runs inside its OWN runCatching: if the failure above
            // happened after the response was already partially sent (e.g. the client disconnected
            // mid-respondText, or mid-respond of one of the early-return branches above),
            // applyEmbedPageHeaders()/response.header() themselves throw "headers can no longer be
            // set" -- that exception must never escape this failure handler and turn into a raw,
            // unlogged 500 with none of this route's own security headers.
            runCatching {
                call.applyEmbedPageHeaders()
                call.respond(HttpStatusCode.InternalServerError)
            }
        }
    }

    // Probe-Endpunkt, aufgerufen von `/embed/v1/login.js` (EmbedAssets.loginPopupJs) via
    // SAME-ORIGIN `fetch` -- NIEMALS vom Widget auf der Partner-Origin (`lapis-widgets.js` ruft
    // diesen Endpunkt bewusst nie auf; ein automatischer Anmeldestatus beim bloßen Laden der
    // Partnerseite, ohne Klick, wäre genau der Cross-Site-Tracking-Kanal, den diese Welle
    // vermeidet). Der Grund, warum die Popup-Seite selbst diesen Umweg braucht, statt den
    // Anmeldestatus direkt beim Rendern von `GET /embed/v1/login` zu bestimmen: JENE Route wird
    // per cross-site Top-Level-Navigation aufgerufen (SameSite=Strict liefert das Cookie dort nie
    // mit, siehe deren eigene KDoc), während dieses `fetch` -- vom bereits geladenen Popup-Dokument
    // an SEINE EIGENE Origin gerichtet -- ein reiner Same-Site-Request ist und das Cookie deshalb
    // sehr wohl bekommt. Cross-Origin liefert dieser Endpunkt wegen SameSite=Strict + fehlendem
    // CORS-Credentials-Header (siehe EmbedCors KDoc) ohnehin strukturell immer signedIn:false -- die
    // Origin-Prüfung unten dient nur der korrekten CORS-Header-Ausgabe (Vary/ACAO) für den
    // theoretischen Cross-Origin-Aufruf, nicht der Autorisierung des same-origin Regelfalls.
    get("/api/embed/v1/session") {
        if (!sessionRateLimiter.checkAndRecord(rateLimitKeyFor(remoteHost = call.request.origin.remoteHost))) {
            call.response.header(HttpHeaders.CacheControl, "no-store")
            call.respond(HttpStatusCode.TooManyRequests)
            return@get
        }
        val cors = call.applyEmbedCors(allowlist = config.allowlist, allowInsecure = config.allowInsecureOrigins)
        if (cors is EmbedCorsResult.Rejected) {
            call.respondEmbedForbiddenOrigin()
            return@get
        }
        val rawToken = call.request.cookies[SESSION_COOKIE_NAME]
        val resolved = rawToken?.let { SessionStore.resolve(it) }
        val response =
            if (resolved == null) {
                SessionStatusResponse(signedIn = false, displayName = null)
            } else {
                val displayName =
                    transaction {
                        MemberTable
                            .selectAll()
                            .where { MemberTable.id eq resolved.memberId }
                            .singleOrNull()
                            ?.get(MemberTable.displayName)
                    }
                SessionStatusResponse(signedIn = true, displayName = displayName)
            }
        call.response.header("X-Content-Type-Options", "nosniff")
        call.respondText(
            text =
                kotlinx.serialization.json.Json
                    .encodeToString(SessionStatusResponse.serializer(), response),
            contentType = JSON_CONTENT_TYPE,
        )
    }

    // Denselben sessionRateLimiter wie die GET-Route oben verwenden, NICHT ungedrosselt lassen --
    // dieser Preflight ist genauso unauthentifiziert/internet-offen erreichbar wie sein GET-
    // Gegenstück, und alle vier anderen Embed-Routen dieser Welle sind gedrosselt (Review-Fund
    // V1.4.1a: dieser OPTIONS-Handler war der einzige Sonderfall ohne checkAndRecord).
    options("/api/embed/v1/session") {
        if (!sessionRateLimiter.checkAndRecord(rateLimitKeyFor(remoteHost = call.request.origin.remoteHost))) {
            call.response.header(HttpHeaders.CacheControl, "no-store")
            call.respond(HttpStatusCode.TooManyRequests)
            return@options
        }
        val cors = call.applyEmbedCors(allowlist = config.allowlist, allowInsecure = config.allowInsecureOrigins)
        if (cors is EmbedCorsResult.Rejected) {
            call.respondEmbedForbiddenOrigin()
        } else {
            call.respondEmbedPreflight(allowedMethods = "GET, OPTIONS")
        }
    }
}

/**
 * ADMIN-only, read-only Statusanzeige für den Client-Screen "Website-Integration" (V1.4.1a §7).
 * Registered UNCONDITIONALLY by [registerEmbedRoutes] -- BEFORE the `!config.enabled` early return
 * -- so the screen renders an honest `enabled: false` instead of a bare 404 on every installation
 * that has not opted in (see [registerEmbedRoutes] KDoc). `publicBaseUrl` is read here (not passed
 * in) so this function stays callable on its own the same way [registerEmbedRoutes] is.
 *
 * KEIN applyEmbedCors hier, unter KEINER Bedingung -- ein ADMIN ruft diesen Endpunkt immer
 * same-origin aus der eingeloggten Lapis-Cloud-SPA auf; CORS-Header hätten hier keinen
 * legitimen Zweck und wären reine Angriffsfläche. Deshalb ist EmbedCors bewusst PER-HANDLER
 * aufgerufen, nicht als Prefix-Interceptor -- siehe EmbedCors KDoc.
 */
private fun Route.registerEmbedAdminStatusRoute(
    config: EmbedConfig,
    adminStatusRateLimiter: FederationInboxRateLimiter,
) {
    get("/api/embed/v1/admin/status") {
        if (!adminStatusRateLimiter.checkAndRecord(rateLimitKeyFor(remoteHost = call.request.origin.remoteHost))) {
            call.response.header(HttpHeaders.CacheControl, "no-store")
            call.respond(HttpStatusCode.TooManyRequests)
            return@get
        }
        val current = resolveCurrentMember(call)
        current.requireRole(AccountRole.ADMIN)
        val response =
            EmbedAdminStatusResponse(
                enabled = config.enabled,
                allowedOrigins = config.allowlist.canonicalOrigins,
                publicBaseUrl = FederationConfig.publicBaseUrl.trimEnd('/'),
                allowInsecureOrigins = config.allowInsecureOrigins,
            )
        call.response.header(HttpHeaders.CacheControl, "no-store")
        call.respondText(
            text =
                kotlinx.serialization.json.Json
                    .encodeToString(EmbedAdminStatusResponse.serializer(), response),
            contentType = JSON_CONTENT_TYPE,
        )
    }
}

@Serializable
internal data class SessionStatusResponse(
    val signedIn: Boolean,
    val displayName: String?,
)

@Serializable
internal data class EmbedAdminStatusResponse(
    val enabled: Boolean,
    val allowedOrigins: List<String>,
    val publicBaseUrl: String,
    val allowInsecureOrigins: Boolean,
)

/**
 * Security-Header für die Popup-Login-Seite -- EIGENE Funktion, NICHT [applyPublicPageHeaders]
 * (die bleibt für `/s`/`/transparenz` unverändert, siehe Welle-Plan). `script-src 'self'` statt
 * eines strengeren `'none'` (anders als `/s`'s `default-src 'none'; style-src 'self'`, das GAR
 * kein Skript zulässt) -- diese Seite lädt `/embed/v1/login.js`, eine KONSTANTE, same-origin
 * Datei ohne jede Interpolation (siehe [EmbedHtml] KDoc "Sicherheits-Vertrag" Punkt 1), weshalb
 * kein Nonce nötig ist. `connect-src 'self'` erlaubt die `fetch(...)`-Aufrufe dieser Seite --
 * `/api/embed/v1/session` (Sitzungs-Probe beim Laden) und `/api/auth/login` (Formular-Submit).
 * `form-action 'none'`: das `<form>` trägt bewusst kein `action` (Submit läuft über `fetch` +
 * `preventDefault()`); fällt das Skript aus, blockiert die CSP jede Ersatz-Navigation -- ein
 * sicherer Fehlschlag, siehe [EmbedHtml] KDoc Punkt 3.
 */
internal fun ApplicationCall.applyEmbedPageHeaders() {
    response.header(
        "Content-Security-Policy",
        "default-src 'none'; script-src 'self'; style-src 'self'; connect-src 'self'; " +
            "base-uri 'none'; form-action 'none'; frame-ancestors 'none'",
    )
    response.header("X-Content-Type-Options", "nosniff")
    response.header("Referrer-Policy", "no-referrer")
    response.header("X-Frame-Options", "DENY")
    response.header("Permissions-Policy", "geolocation=(), microphone=(), camera=()")
}

/** `MessageDigest.getInstance` ist NICHT thread-safe -- pro Aufruf neu instanziieren (Haus-Prüfliste, siehe `SocialPublicRoutes.computeETag`). Eigene, kleine Kopie statt Wiederverwendung -- jene Funktion ist `private`. */
private fun computeEmbedETag(body: String): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(body.toByteArray(Charsets.UTF_8))
    val hex = digest.joinToString(separator = "") { "%02x".format(it) }.take(32)
    return "W/\"$hex\""
}

private fun embedIfNoneMatchHits(
    headerValue: String?,
    etag: String,
): Boolean {
    if (headerValue == null) return false
    if (headerValue.trim() == "*") return true
    val target = etag.removePrefix("W/").trim('"')
    return headerValue.split(",").map { it.trim() }.any { candidate -> candidate.removePrefix("W/").trim('"') == target }
}

private suspend fun ApplicationCall.respondEmbedAsset(
    body: String,
    etag: String,
) {
    response.header(HttpHeaders.ETag, etag)
    response.header(HttpHeaders.CacheControl, "public, max-age=300, must-revalidate")
    if (embedIfNoneMatchHits(headerValue = request.headers[HttpHeaders.IfNoneMatch], etag = etag)) {
        respond(HttpStatusCode.NotModified)
    } else {
        respondText(text = body, contentType = JS_CONTENT_TYPE)
    }
}
