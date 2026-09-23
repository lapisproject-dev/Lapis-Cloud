package network.lapis.cloud.server.routes

import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.client.request.forms.FormDataContent
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.Cookie
import io.ktor.http.CookieEncoding
import io.ktor.http.HttpStatusCode
import io.ktor.http.Parameters
import io.ktor.server.application.call
import io.ktor.server.plugins.origin
import io.ktor.server.response.respond
import io.ktor.server.response.respondRedirect
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import network.lapis.cloud.server.audit.OidcLoginAuditRecorder
import network.lapis.cloud.server.db.DbClock
import network.lapis.cloud.server.db.generated.KeycloakLoginAttemptTable
import network.lapis.cloud.server.federation.FederationInboxRateLimiter
import network.lapis.cloud.server.federation.OidcJwt
import network.lapis.cloud.server.federation.OidcPkce
import network.lapis.cloud.server.federation.OidcTokenResponseDto
import network.lapis.cloud.server.federation.readCappedFederationBodyOrNull
import network.lapis.cloud.server.keycloak.KeycloakAccountLinker
import network.lapis.cloud.server.keycloak.KeycloakConfig
import network.lapis.cloud.server.keycloak.KeycloakIssuerUrlGuard
import network.lapis.cloud.server.keycloak.KeycloakOidcMetadata
import network.lapis.cloud.server.keycloak.keycloakHttpClient
import network.lapis.cloud.server.security.LoginRateLimiter
import network.lapis.cloud.server.security.SESSION_COOKIE_NAME
import network.lapis.cloud.server.security.SessionStore
import network.lapis.cloud.server.security.SessionTokens
import network.lapis.cloud.shared.domain.OidcLoginEventType
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import java.net.URLEncoder
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.uuid.Uuid

private val logger = KotlinLogging.logger {}
private val KEYCLOAK_JSON = Json { ignoreUnknownKeys = true }
private val KEYCLOAK_LOGIN_ATTEMPT_TTL = 10.minutes

/**
 * Security-audit fix (MAJOR 1, V1.7.1b) -- HttpOnly cookie name binding a `keycloak_login_attempt`
 * row to the specific browser that started it at `/start`. Deliberately a SEPARATE cookie/name
 * from [SESSION_COOKIE_NAME] -- this one carries no authentication power of its own, it only proves
 * "this browser is the one that initiated THIS login attempt".
 *
 * **`__Host-` prefix (round-2 security-audit recommendation, LOW)** -- this cookie already
 * satisfies the prefix's requirements (`Secure`, `Path=/`, no explicit `Domain` attribute, see both
 * `Cookie(...)` construction sites below), and the prefix additionally prevents a sibling-subdomain
 * attacker (or one with XSS there) from overwriting the cookie with a known value in this browser,
 * which would otherwise let them forge a `browser_binding_hash` match without ever seeing this
 * server's real cookie.
 */
internal const val KEYCLOAK_LOGIN_BINDING_COOKIE_NAME = "__Host-lapis_keycloak_binding"

/**
 * Security-audit fix (MAJOR 2a, V1.7.1b) -- how far back an opportunistic `/start` cleanup sweep
 * reaches: any `keycloak_login_attempt` row whose [KeycloakLoginAttemptTable.expiresAt] is older
 * than this is deleted. Comfortably larger than [KEYCLOAK_LOGIN_ATTEMPT_TTL] (10 minutes) so a row
 * is never swept while it could still be a legitimate in-flight attempt.
 *
 * Round-2 security-audit fix: the purge predicate used to ALSO check
 * `consumedAt < cleanupCutoff`, on an unindexed column -- forcing a full table scan on every
 * `/start` call and largely defeating the `expires_at` index this sweep otherwise uses. Dropped:
 * a consumed row's `consumedAt` is always within [KEYCLOAK_LOGIN_ATTEMPT_TTL] of its `expiresAt`
 * (the row is created with both stamped from the same `/start` call), so once `expiresAt` clears
 * this cutoff the row is unusable regardless of whether it was ever consumed -- the `expiresAt`
 * half alone is sufficient.
 */
private val KEYCLOAK_LOGIN_ATTEMPT_CLEANUP_AGE = 1.hours

/**
 * RFC 6749 §4.1.2.1's OAuth `error` codes are all lowercase ASCII with underscores (e.g.
 * `access_denied`, `invalid_request`, `server_error`). Security-audit fix (MINOR 4, V1.7.1b): the
 * `error` callback query parameter is attacker-controlled and arrives before any authentication --
 * validating it against this restrictive class before it ever reaches a log line or the audit
 * `reason` column closes a CRLF log-injection vector this codebase's logback config does not itself
 * sanitize.
 */
private val OIDC_ERROR_CODE_PATTERN = Regex("^[A-Za-z0-9_.-]{1,64}\$")
private const val OIDC_ERROR_CODE_FALLBACK = "PROVIDER_ERROR_OTHER"

/** See [OIDC_ERROR_CODE_PATTERN] KDoc. Never returns the raw, unvalidated [raw] value. */
private fun sanitizeOidcErrorCode(raw: String): String = if (OIDC_ERROR_CODE_PATTERN.matches(raw)) raw else OIDC_ERROR_CODE_FALLBACK

@Serializable
private data class KeycloakLogoutRedirectResponse(
    val endSessionUrl: String?,
)

/**
 * Sub-wave V1.7.1b "Keycloak als externe Benutzerverwaltung -- Server-Kern". Three endpoints
 * implementing the Relying-Party half of the flow, mirroring
 * [network.lapis.cloud.server.routes.registerOidcRoutes]'s own `/rp/login` + `/rp/callback`
 * security ordering (atomic single-use `state` consumption, PKCE, nonce-bound ID-token
 * verification) -- but pointed at ONE operator-pinned Keycloak issuer instead of an
 * arbitrary claimed home server, and NEVER creating a member on a miss (see
 * [KeycloakAccountLinker] KDoc for why this is a fundamentally different trust model from
 * [network.lapis.cloud.server.federation.OidcGuestMemberStore]).
 *
 * **Never persists or logs a Keycloak token.** `id_token`/`access_token` are read once, in
 * memory, for claim extraction/verification, then discarded -- neither this file nor any table
 * it writes to ever stores one.
 */
internal fun Route.registerKeycloakAuthRoutes(
    config: KeycloakConfig,
    metadata: KeycloakOidcMetadata,
    startRateLimiter: LoginRateLimiter,
    /**
     * Security-audit fix (MAJOR 2b, V1.7.1b) -- a SEPARATE, independent limiter instance from
     * [startRateLimiter]: this one counts EVERY `/start` request (successful or not), same "counts
     * every request" idiom [FederationInboxRateLimiter] already establishes for the public
     * federation inbox, chosen deliberately over [LoginRateLimiter] (which only counts FAILURES --
     * correct for a brute-force guard, but useless against an anonymous flood of `/start` calls
     * that each cost this server one DB write and never fail). A generous per-IP budget
     * appropriate for a login-INITIATION endpoint (default 30/minute), not the tighter
     * failure-counting budget [startRateLimiter] uses.
     */
    startFloodLimiter: FederationInboxRateLimiter = FederationInboxRateLimiter(maxRequests = 30, window = 1.minutes),
    /**
     * A FACTORY, not a shared instance -- called once per callback request, mirroring
     * `OidcRoutes.kt`'s own `federationHttpClient(target).use { ... }` per-request construction
     * house style (see that file's `/rp/callback` handler). Defaults to [keycloakHttpClient];
     * tests inject a `{ HttpClient(MockEngine { ... }) }` factory instead (same "MockEngine
     * injected via the constructor rather than real network I/O" house rule
     * `LetterxpressPostalMailProviderTest` documents).
     */
    tokenHttpClientFactory: () -> io.ktor.client.HttpClient = { keycloakHttpClient() },
) {
    get("/auth/keycloak/start") {
        if (!config.isOperational) {
            call.respond(HttpStatusCode.NotFound)
            return@get
        }
        // Review finding 1 fix: `startRateLimiter` is keyed by CLIENT IP and is now used the same
        // way `LoginRateLimiter` is everywhere else in this codebase -- it counts FAILED attempts,
        // not every request. Previously this handler called `recordFailure(ipKey)` unconditionally
        // on every `/start` hit (i.e. every login INITIATION, successful or not), which meant the
        // only non-admin login entry point in Keycloak mode hard-blocked after 5 page loads per
        // client IP -- trivially hit by a user retrying login, or by any group sharing one NAT/
        // CGNAT egress (party office, carrier). `recordFailure` is now only ever called from the
        // `/callback` handler below, on a genuine rejection, keyed by that request's own client IP
        // -- see its call sites for the full reasoning.
        //
        // Round-2 security-audit fix: this used to be the raw `remoteHost` string, which for an
        // IPv6 client is trivially bypassed -- any host with a /64 allocation (the norm) can rotate
        // its source address per request and get a fresh budget every time. `rateLimitKeyFor` (see
        // `SocialPublicRoutes.kt`, already used by every OTHER public rate limiter in this codebase)
        // groups IPv6 addresses by their /64 network instead.
        val ipKey = rateLimitKeyFor(remoteHost = call.request.origin.remoteHost)
        // Security-audit fix (MAJOR 2b): a SEPARATE limiter from `startRateLimiter` above -- see
        // `startFloodLimiter` KDoc on the function signature for why a failure-counting limiter
        // alone cannot bound an anonymous flood of `/start` calls.
        if (!startFloodLimiter.checkAndRecord(ipKey)) {
            call.respond(HttpStatusCode.TooManyRequests, "Too many requests -- try again later")
            return@get
        }
        if (!startRateLimiter.checkAllowed(ipKey)) {
            call.respond(HttpStatusCode.TooManyRequests, "Too many requests -- try again later")
            return@get
        }

        val discovery = metadata.discoveryDocument()
        if (discovery == null) {
            logger.warn { "Keycloak /auth/keycloak/start: discovery document unavailable" }
            call.respondText(
                keycloakErrorPageHtml("Der externe Anmeldedienst ist derzeit nicht erreichbar."),
                contentType = ContentType.Text.Html,
                status = HttpStatusCode.BadGateway,
            )
            return@get
        }

        // Review finding 7 fix: `discovery.authorization_endpoint` comes straight from the Keycloak
        // issuer's OWN discovery document -- re-validate it against the operator-PINNED issuer URL
        // before ever redirecting the browser to it, exactly like the `token_endpoint` re-check
        // below in `/callback`. Without this, a tampered/misconfigured discovery document turns this
        // endpoint into an open redirect.
        val authorizationEndpoint =
            runCatching {
                KeycloakIssuerUrlGuard.requireAllowedRequestUrl(
                    urlString = discovery.authorization_endpoint,
                    pinnedIssuerUrl = requireNotNull(config.issuerUrl),
                )
                discovery.authorization_endpoint
            }.getOrNull()
        if (authorizationEndpoint == null) {
            logger.warn { "Keycloak /auth/keycloak/start: authorization_endpoint failed issuer-URL re-validation" }
            call.respondText(
                keycloakErrorPageHtml("Der externe Anmeldedienst ist derzeit nicht erreichbar."),
                contentType = ContentType.Text.Html,
                status = HttpStatusCode.BadGateway,
            )
            return@get
        }

        val state = SessionTokens.newRawToken()
        val nonce = SessionTokens.newRawToken()
        val codeVerifier = SessionTokens.newRawToken()
        val codeChallenge = OidcPkce.codeChallengeS256(codeVerifier)
        val redirectUri = "${keycloakPublicBaseUrl()}/auth/keycloak/callback"
        // Security-audit fix (MAJOR 1): a fresh, unguessable value minted HERE and handed to the
        // browser only as an HttpOnly cookie (never in the redirect URL/query string) -- only a
        // HASH of it is stored, ties this specific browser to this specific attempt/state. See
        // `KEYCLOAK_LOGIN_BINDING_COOKIE_NAME` KDoc and `/callback`'s binding check below.
        val bindingValue = SessionTokens.newRawToken()
        val now = nowLocalDateTime()
        transaction {
            // Security-audit fix (MAJOR 2a): opportunistic cleanup of old rows on the hottest write
            // path for this table, same "no scheduler exists in this codebase" idiom
            // `SessionStore.purgeExpired`'s own KDoc documents -- run on EVERY `/start` call (unlike
            // `SessionStore`'s probabilistic trigger) because the very threat this closes is an
            // anonymous flood from many DIFFERENT source IPs, which a single-instance probability
            // gate would not reliably bound; a single indexed DELETE (see the `expires_at` index
            // added by V47) against a table this endpoint itself keeps small is cheap on every call.
            val cleanupCutoff = minusDuration(start = now, duration = KEYCLOAK_LOGIN_ATTEMPT_CLEANUP_AGE)
            // Round-2 security-audit fix: `expiresAt` alone -- see KEYCLOAK_LOGIN_ATTEMPT_CLEANUP_AGE
            // KDoc for why the previous `consumedAt` half was dropped (unindexed full-table-scan
            // trigger, redundant with this half regardless).
            KeycloakLoginAttemptTable.deleteWhere {
                KeycloakLoginAttemptTable.expiresAt less cleanupCutoff
            }
            KeycloakLoginAttemptTable.insert {
                it[id] = Uuid.random()
                it[stateHash] = SessionTokens.hash(state)
                it[KeycloakLoginAttemptTable.codeVerifier] = codeVerifier
                it[KeycloakLoginAttemptTable.nonce] = nonce
                it[KeycloakLoginAttemptTable.redirectUri] = redirectUri
                it[createdAt] = now
                it[expiresAt] = plusDuration(start = now, duration = KEYCLOAK_LOGIN_ATTEMPT_TTL)
                it[consumedAt] = null
                it[browserBindingHash] = SessionTokens.hash(bindingValue)
            }
        }

        val authorizeUrl =
            buildString {
                append(authorizationEndpoint)
                append("?response_type=code")
                append("&client_id=").append(URLEncoder.encode(config.clientId, "UTF-8"))
                append("&redirect_uri=").append(URLEncoder.encode(redirectUri, "UTF-8"))
                append("&scope=").append(URLEncoder.encode(config.scopes, "UTF-8"))
                append("&state=").append(URLEncoder.encode(state, "UTF-8"))
                append("&nonce=").append(URLEncoder.encode(nonce, "UTF-8"))
                append("&code_challenge=").append(URLEncoder.encode(codeChallenge, "UTF-8"))
                append("&code_challenge_method=S256")
            }
        // Security-audit fix (MAJOR 1): SameSite=Lax, NOT Strict -- the callback arrives as a
        // cross-site TOP-LEVEL GET redirect from Keycloak, which a Strict cookie would not be sent
        // on, defeating the binding entirely. HttpOnly/Secure/Path=/ mirror the session cookie
        // below; Max-Age matches the state's own TTL.
        call.response.cookies.append(
            Cookie(
                name = KEYCLOAK_LOGIN_BINDING_COOKIE_NAME,
                value = bindingValue,
                encoding = CookieEncoding.URI_ENCODING,
                maxAge = KEYCLOAK_LOGIN_ATTEMPT_TTL.inWholeSeconds.toInt(),
                path = "/",
                secure = true,
                httpOnly = true,
                extensions = mapOf("SameSite" to "Lax"),
            ),
        )
        call.respondRedirect(authorizeUrl)
    }

    get("/auth/keycloak/callback") {
        if (!config.isOperational) {
            call.respond(HttpStatusCode.NotFound)
            return@get
        }
        // Review finding 1 + 2: this request's own client IP is the key `startRateLimiter` tracks
        // failures under (see `/start` KDoc above) AND the `remoteParty` recorded on every audit
        // row below -- both read once, here, before any rejection branch.
        //
        // Round-2 security-audit fix: `rateLimitKeyFor`, same IPv6-/64-bypass reasoning as `/start`
        // above -- `startRateLimiter.recordFailure`/`checkAllowed` is keyed by this same value, so
        // both endpoints must use the same normalization or an attacker could dodge one but not the
        // other.
        val ipKey = rateLimitKeyFor(remoteHost = call.request.origin.remoteHost)
        val params = call.request.queryParameters
        val stateParam = params["state"]
        val errorParam = params["error"]
        val codeParam = params["code"]

        if (stateParam.isNullOrBlank()) {
            logger.warn { "Keycloak callback rejected: MISSING_STATE" }
            startRateLimiter.recordFailure(ipKey)
            OidcLoginAuditRecorder.record(
                eventType = OidcLoginEventType.KEYCLOAK_LOGIN_FAILED,
                remoteParty = config.issuerUrl,
                reason = "MISSING_STATE",
            )
            call.respond(HttpStatusCode.Unauthorized, "Missing state parameter")
            return@get
        }

        val stateHashValue = SessionTokens.hash(stateParam)
        val now = nowLocalDateTime()
        val attempt =
            transaction {
                val row =
                    KeycloakLoginAttemptTable
                        .selectAll()
                        .where {
                            (KeycloakLoginAttemptTable.stateHash eq stateHashValue) and
                                KeycloakLoginAttemptTable.consumedAt.isNull() and
                                (KeycloakLoginAttemptTable.expiresAt greater now)
                        }.forUpdate()
                        .singleOrNull() ?: return@transaction null
                val updated =
                    KeycloakLoginAttemptTable.update({
                        (KeycloakLoginAttemptTable.stateHash eq stateHashValue) and KeycloakLoginAttemptTable.consumedAt.isNull()
                    }) { it[consumedAt] = now }
                if (updated == 0) return@transaction null
                row
            }
        if (attempt == null) {
            // CSRF/replay defense -- see OidcRoutes.kt "/rp/callback" KDoc for the identical
            // reasoning: an attacker cannot forge this callback without a `state` value this
            // server itself generated and never disclosed except via the 302 to the legitimate
            // browser.
            logger.warn { "Keycloak callback rejected: UNKNOWN_OR_EXPIRED_STATE" }
            startRateLimiter.recordFailure(ipKey)
            OidcLoginAuditRecorder.record(
                eventType = OidcLoginEventType.KEYCLOAK_LOGIN_FAILED,
                remoteParty = config.issuerUrl,
                reason = "UNKNOWN_OR_EXPIRED_STATE",
            )
            call.respondText(
                keycloakErrorPageHtml("Ungueltiger oder abgelaufener Anmeldeversuch."),
                contentType = ContentType.Text.Html,
                status = HttpStatusCode.Unauthorized,
            )
            return@get
        }

        // Security-audit fix (MAJOR 1): the `state` row is genuine, unconsumed-until-just-now, and
        // unexpired -- but that alone does not prove THIS browser is the one that started it at
        // `/start` (see `KEYCLOAK_LOGIN_BINDING_COOKIE_NAME` KDoc for the attack this closes). The
        // binding cookie is consumed/expired here regardless of the outcome -- it has done its job
        // the moment this check runs, whether it matched or not.
        val bindingCookieValue = call.request.cookies[KEYCLOAK_LOGIN_BINDING_COOKIE_NAME]
        val storedBindingHash = attempt[KeycloakLoginAttemptTable.browserBindingHash]
        val bindingMatches =
            bindingCookieValue != null && storedBindingHash != null && SessionTokens.hash(bindingCookieValue) == storedBindingHash
        expireBindingCookie(call)
        if (!bindingMatches) {
            logger.warn { "Keycloak callback rejected: BROWSER_BINDING_MISMATCH" }
            startRateLimiter.recordFailure(ipKey)
            OidcLoginAuditRecorder.record(
                eventType = OidcLoginEventType.KEYCLOAK_LOGIN_FAILED,
                remoteParty = config.issuerUrl,
                reason = "BROWSER_BINDING_MISMATCH",
            )
            call.respondText(
                keycloakErrorPageHtml("Ungueltiger oder abgelaufener Anmeldeversuch."),
                contentType = ContentType.Text.Html,
                status = HttpStatusCode.Unauthorized,
            )
            return@get
        }

        if (errorParam != null || codeParam.isNullOrBlank()) {
            // Security-audit fix (MINOR 4): `errorParam` is attacker-controlled and arrives before
            // any authentication -- sanitize it against a restrictive character class BEFORE it
            // ever reaches a log line or the audit `reason` column (this codebase's logback config
            // does not itself strip CR/LF, so an unsanitized value could forge fake log lines).
            // `error_description` (a separate, even-less-trustworthy parameter) is never read or
            // logged at all.
            val reason = errorParam?.let(::sanitizeOidcErrorCode) ?: "MISSING_CODE"
            logger.warn { "Keycloak callback rejected: $reason" }
            startRateLimiter.recordFailure(ipKey)
            OidcLoginAuditRecorder.record(
                eventType = OidcLoginEventType.KEYCLOAK_LOGIN_FAILED,
                remoteParty = config.issuerUrl,
                reason = reason.take(255),
            )
            call.respondText(
                keycloakErrorPageHtml("Die Anmeldung wurde nicht abgeschlossen."),
                contentType = ContentType.Text.Html,
                status = HttpStatusCode.Unauthorized,
            )
            return@get
        }

        val discovery = metadata.discoveryDocument()
        if (discovery == null) {
            logger.warn { "Keycloak callback rejected: DISCOVERY_UNAVAILABLE" }
            startRateLimiter.recordFailure(ipKey)
            OidcLoginAuditRecorder.record(
                eventType = OidcLoginEventType.KEYCLOAK_LOGIN_FAILED,
                remoteParty = config.issuerUrl,
                reason = "DISCOVERY_UNAVAILABLE",
            )
            call.respondText(
                keycloakErrorPageHtml("Der externe Anmeldedienst ist derzeit nicht erreichbar."),
                contentType = ContentType.Text.Html,
                status = HttpStatusCode.BadGateway,
            )
            return@get
        }

        val tokenResponse =
            runCatching {
                // Defense in depth -- discovery.token_endpoint is a value the Keycloak issuer's
                // OWN discovery document supplied; re-validate it against the operator-PINNED
                // issuer URL before ever POSTing to it (same "every concrete request URL is
                // re-checked" contract KeycloakIssuerUrlGuard KDoc documents for
                // KeycloakOidcMetadata's discovery/JWKS fetches -- this closes the same class of
                // gap for the token endpoint, which those two fetches don't cover).
                KeycloakIssuerUrlGuard.requireAllowedRequestUrl(
                    urlString = discovery.token_endpoint,
                    pinnedIssuerUrl = requireNotNull(config.issuerUrl),
                )
                tokenHttpClientFactory().use { client ->
                    val response =
                        client.post(discovery.token_endpoint) {
                            setBody(
                                FormDataContent(
                                    Parameters.build {
                                        append("grant_type", "authorization_code")
                                        append("code", codeParam)
                                        append("redirect_uri", attempt[KeycloakLoginAttemptTable.redirectUri])
                                        append("client_id", config.clientId ?: "")
                                        append("client_secret", config.clientSecret ?: "")
                                        append("code_verifier", attempt[KeycloakLoginAttemptTable.codeVerifier])
                                    },
                                ),
                            )
                        }
                    if (response.status.value !in 200..299) return@use null
                    val bytes = response.readCappedFederationBodyOrNull() ?: return@use null
                    runCatching {
                        KEYCLOAK_JSON.decodeFromString(OidcTokenResponseDto.serializer(), bytes.toString(Charsets.UTF_8))
                    }.getOrNull()
                }
            }.getOrNull()
        if (tokenResponse == null) {
            logger.warn { "Keycloak callback rejected: TOKEN_EXCHANGE_FAILED" }
            startRateLimiter.recordFailure(ipKey)
            OidcLoginAuditRecorder.record(
                eventType = OidcLoginEventType.KEYCLOAK_LOGIN_FAILED,
                remoteParty = config.issuerUrl,
                reason = "TOKEN_EXCHANGE_FAILED",
            )
            call.respondText(
                keycloakErrorPageHtml("Der Autorisierungscode konnte nicht eingeloest werden."),
                contentType = ContentType.Text.Html,
                status = HttpStatusCode.BadGateway,
            )
            return@get
        }

        val kid = OidcJwt.extractUnverifiedKid(tokenResponse.id_token)
        val publicKeyPem = kid?.let { metadata.publicKeyPem(it) }
        if (publicKeyPem == null) {
            logger.warn { "Keycloak callback rejected: UNKNOWN_KID" }
            startRateLimiter.recordFailure(ipKey)
            OidcLoginAuditRecorder.record(
                eventType = OidcLoginEventType.KEYCLOAK_LOGIN_FAILED,
                remoteParty = config.issuerUrl,
                reason = "UNKNOWN_KID",
            )
            call.respondText(
                keycloakErrorPageHtml("Unbekannter Signaturschluessel."),
                contentType = ContentType.Text.Html,
                status = HttpStatusCode.Unauthorized,
            )
            return@get
        }

        val verification =
            OidcJwt.verifyIdToken(
                compact = tokenResponse.id_token,
                publicKeyPem = publicKeyPem,
                expectedIssuer = requireNotNull(config.issuerUrl),
                expectedAudience = requireNotNull(config.clientId),
                expectedNonce = attempt[KeycloakLoginAttemptTable.nonce],
            )
        if (verification is OidcJwt.VerificationResult.Invalid) {
            logger.warn { "Keycloak callback rejected: ID_TOKEN_${verification.reason}" }
            startRateLimiter.recordFailure(ipKey)
            OidcLoginAuditRecorder.record(
                eventType = OidcLoginEventType.KEYCLOAK_LOGIN_FAILED,
                remoteParty = config.issuerUrl,
                reason = "ID_TOKEN_${verification.reason}".take(255),
            )
            call.respondText(
                keycloakErrorPageHtml("Die Signatur der Anmeldung konnte nicht geprueft werden."),
                contentType = ContentType.Text.Html,
                status = HttpStatusCode.Unauthorized,
            )
            return@get
        }
        val claims = (verification as OidcJwt.VerificationResult.Valid).claims
        val subject = claims.subject
        if (subject.isNullOrBlank()) {
            logger.warn { "Keycloak callback rejected: MISSING_SUB" }
            startRateLimiter.recordFailure(ipKey)
            OidcLoginAuditRecorder.record(
                eventType = OidcLoginEventType.KEYCLOAK_LOGIN_FAILED,
                remoteParty = config.issuerUrl,
                reason = "MISSING_SUB",
            )
            call.respondText(
                keycloakErrorPageHtml("Die Anmeldung enthaelt kein Subjekt."),
                contentType = ContentType.Text.Html,
                status = HttpStatusCode.Unauthorized,
            )
            return@get
        }
        val email = runCatching { claims.getStringClaim("email") }.getOrNull()
        val emailVerified = runCatching { claims.getBooleanClaim("email_verified") }.getOrNull() ?: false
        if (email.isNullOrBlank()) {
            logger.warn { "Keycloak callback rejected: MISSING_EMAIL" }
            startRateLimiter.recordFailure(ipKey)
            OidcLoginAuditRecorder.record(
                eventType = OidcLoginEventType.KEYCLOAK_LOGIN_FAILED,
                remoteParty = config.issuerUrl,
                reason = "MISSING_EMAIL",
            )
            call.respondText(
                keycloakErrorPageHtml("Die Anmeldung enthaelt keine E-Mail-Adresse."),
                contentType = ContentType.Text.Html,
                status = HttpStatusCode.Unauthorized,
            )
            return@get
        }

        val linkOutcome =
            KeycloakAccountLinker.linkOrResolve(
                issuer = requireNotNull(config.issuerUrl),
                subject = subject,
                email = email,
                emailVerified = emailVerified,
                requireVerifiedEmail = config.requireVerifiedEmail,
            )
        val memberId =
            when (linkOutcome) {
                is KeycloakAccountLinker.LinkOutcome.Linked -> linkOutcome.memberId
                is KeycloakAccountLinker.LinkOutcome.Rejected -> {
                    logger.warn { "Keycloak callback rejected: LINK_${linkOutcome.reason}" }
                    startRateLimiter.recordFailure(ipKey)
                    OidcLoginAuditRecorder.record(
                        eventType = OidcLoginEventType.KEYCLOAK_LOGIN_FAILED,
                        remoteParty = config.issuerUrl,
                        reason = "LINK_${linkOutcome.reason}".take(255),
                    )
                    // NO_MATCHING_MEMBER is also recorded as its own distinct KEYCLOAK_LINK_MISS
                    // event -- see OidcLoginEventType KDoc; this is the "verified identity, but
                    // nobody has manually linked it yet" case an operator needs to be able to find
                    // without grepping generic KEYCLOAK_LOGIN_FAILED rows for a reason string.
                    if (linkOutcome.reason == KeycloakAccountLinker.RejectionReason.NO_MATCHING_MEMBER) {
                        // Do NOT persist the raw email here: oidc_guest_login_event is allowlisted
                        // in PersonalDataRegistry.kt / OidcGuestPersonalData.kt as containing NO
                        // personal data (subjects referenced only by an unconstrained UUID). Use a
                        // fixed code constant, matching every other reason= call site in this file --
                        // only the Keycloak `subject` (never the email) is logged here, transiently,
                        // for operator debugging (an operator can look the subject up in the
                        // Keycloak admin console if needed; round 3 review finding F3 fix -- an
                        // earlier version of this comment incorrectly claimed the EMAIL was logged).
                        logger.info { "Keycloak login: no matching member for verified identity (subject=$subject)" }
                        OidcLoginAuditRecorder.record(
                            eventType = OidcLoginEventType.KEYCLOAK_LINK_MISS,
                            remoteParty = config.issuerUrl,
                            reason = "NO_MATCHING_MEMBER",
                        )
                    }
                    call.respondText(
                        keycloakErrorPageHtml("Kein zugeordnetes Mitgliedskonto -- bitte an die Verwaltung wenden."),
                        contentType = ContentType.Text.Html,
                        status = HttpStatusCode.Unauthorized,
                    )
                    return@get
                }
            }

        // Never persist/log id_token/tokenResponse itself beyond this point -- see class KDoc.
        val issuedSession = SessionStore.createSession(memberId)
        call.response.cookies.append(
            Cookie(
                name = SESSION_COOKIE_NAME,
                value = issuedSession.rawToken,
                encoding = CookieEncoding.URI_ENCODING,
                maxAge = SessionStore.SESSION_TTL.inWholeSeconds.toInt(),
                path = "/",
                secure = true,
                httpOnly = true,
                extensions = mapOf("SameSite" to "Strict"),
            ),
        )
        // Review finding 1: a successful login clears any earlier failures accumulated for this
        // client IP -- same "reset on success" contract LoginRateLimiter.reset's own KDoc documents
        // for the password-login endpoint, so a legitimate user is never penalized by their own
        // earlier failed attempts once they DO get in.
        startRateLimiter.reset(ipKey)
        // Review finding 2: forensic audit trail -- see OidcLoginAuditRecorder KDoc; reused as-is
        // (no parallel logging mechanism), same "success AND every distinct failure reason" shape
        // OidcRoutes.kt's own RP callback already establishes.
        OidcLoginAuditRecorder.record(
            eventType = OidcLoginEventType.KEYCLOAK_LOGIN_SUCCESS,
            memberId = memberId,
            remoteParty = config.issuerUrl,
        )
        if (linkOutcome.wasNewLink) {
            OidcLoginAuditRecorder.record(
                eventType = OidcLoginEventType.KEYCLOAK_LINK_CREATED,
                memberId = memberId,
                remoteParty = config.issuerUrl,
            )
        }
        logger.info { "Keycloak login succeeded for memberId=$memberId" }
        // "/app#/dashboard" is the literal, hardcoded redirect target -- deliberately no
        // `redirect_to` query param support this wave (open-redirect prevention, see class KDoc).
        call.respondRedirect("/app#/dashboard")
    }

    post("/auth/keycloak/logout-redirect") {
        if (!config.isOperational || !config.rpInitiatedLogout) {
            call.respond(HttpStatusCode.NotFound)
            return@post
        }
        // Review finding 4 fix: this endpoint is NOT currently wired into `/api/auth/logout`
        // (`AuthRoutes.kt`'s `POST /api/auth/logout` handler never calls it) -- that wiring is
        // planned for Wave 2 (the frontend needs to actually redirect the browser to the URL this
        // returns). The comment previously here falsely claimed it was already wired in; fixed to
        // say so plainly instead of leaving a stale, misleading claim in the code.
        val discovery = metadata.discoveryDocument()
        val endSessionEndpoint = discovery?.end_session_endpoint
        if (endSessionEndpoint == null) {
            call.respond(HttpStatusCode.OK, KeycloakLogoutRedirectResponse(endSessionUrl = null))
            return@post
        }
        // Review finding 7 fix: `discovery.end_session_endpoint` is, like `authorization_endpoint`
        // above, a value the Keycloak issuer's OWN discovery document supplied -- re-validate it
        // against the operator-PINNED issuer URL before ever handing it back to a client, same
        // reasoning as the `/start`/`/callback` re-checks.
        val validatedEndSessionEndpoint =
            runCatching {
                KeycloakIssuerUrlGuard.requireAllowedRequestUrl(
                    urlString = endSessionEndpoint,
                    pinnedIssuerUrl = requireNotNull(config.issuerUrl),
                )
                endSessionEndpoint
            }.getOrNull()
        if (validatedEndSessionEndpoint == null) {
            logger.warn { "Keycloak /auth/keycloak/logout-redirect: end_session_endpoint failed issuer-URL re-validation" }
            call.respond(HttpStatusCode.OK, KeycloakLogoutRedirectResponse(endSessionUrl = null))
            return@post
        }
        val postLogoutRedirectUri = "${keycloakPublicBaseUrl()}/"
        val url =
            buildString {
                append(validatedEndSessionEndpoint)
                append("?client_id=").append(URLEncoder.encode(config.clientId ?: "", "UTF-8"))
                append("&post_logout_redirect_uri=").append(URLEncoder.encode(postLogoutRedirectUri, "UTF-8"))
                // Deliberately NO id_token_hint -- no Keycloak token is ever stored to supply one,
                // see class KDoc "Never persists or logs a Keycloak token".
            }
        call.respond(HttpStatusCode.OK, KeycloakLogoutRedirectResponse(endSessionUrl = url))
    }
}

private fun keycloakPublicBaseUrl(): String =
    network.lapis.cloud.server.federation.FederationConfig.publicBaseUrl
        .trimEnd('/')

private fun keycloakErrorPageHtml(message: String): String =
    """
    <!doctype html>
    <html><head><meta charset="utf-8"><title>Anmeldung fehlgeschlagen</title></head>
    <body><h1>Anmeldung fehlgeschlagen</h1><p>${keycloakHtmlEscape(message)}</p></body></html>
    """.trimIndent()

private fun keycloakHtmlEscape(value: String): String =
    value
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&#39;")

private fun nowLocalDateTime(): LocalDateTime = DbClock.nowLocalDateTime(TimeZone.UTC)

private fun plusDuration(
    start: LocalDateTime,
    duration: kotlin.time.Duration,
): LocalDateTime = (start.toInstant(TimeZone.UTC) + duration).toLocalDateTime(TimeZone.UTC)

/** See [plusDuration]; used by the MAJOR 2a cleanup sweep's cutoff computation. */
private fun minusDuration(
    start: LocalDateTime,
    duration: kotlin.time.Duration,
): LocalDateTime = (start.toInstant(TimeZone.UTC) - duration).toLocalDateTime(TimeZone.UTC)

/**
 * Security-audit fix (MAJOR 1): expires [KEYCLOAK_LOGIN_BINDING_COOKIE_NAME] on the browser --
 * called once the callback has read whatever it needed from the cookie, regardless of outcome
 * (success or any rejection branch), so it never lingers past the single callback it was minted
 * for. Same `Cookie(...)` construction as every other cookie in this file (an empty value +
 * `maxAge = 0` is the "expire now" idiom, rather than relying on a `ResponseCookies.appendExpired`
 * extension not otherwise used in this codebase).
 */
private fun expireBindingCookie(call: io.ktor.server.application.ApplicationCall) {
    call.response.cookies.append(
        Cookie(
            name = KEYCLOAK_LOGIN_BINDING_COOKIE_NAME,
            value = "",
            encoding = CookieEncoding.URI_ENCODING,
            maxAge = 0,
            path = "/",
            secure = true,
            httpOnly = true,
            extensions = mapOf("SameSite" to "Lax"),
        ),
    )
}
