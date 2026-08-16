package network.lapis.cloud.server.routes

import com.nimbusds.jwt.JWTClaimsSet
import io.ktor.client.request.forms.FormDataContent
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.Cookie
import io.ktor.http.CookieEncoding
import io.ktor.http.HttpStatusCode
import io.ktor.http.Parameters
import io.ktor.http.isSuccess
import io.ktor.server.application.call
import io.ktor.server.plugins.origin
import io.ktor.server.request.receiveParameters
import io.ktor.server.request.receiveText
import io.ktor.server.request.uri
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
import kotlinx.serialization.json.Json
import network.lapis.cloud.server.audit.OidcLoginAuditRecorder
import network.lapis.cloud.server.db.DbClock
import network.lapis.cloud.server.db.generated.AccountTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.server.db.generated.OidcAuthorizationCodeTable
import network.lapis.cloud.server.db.generated.OidcClientRedirectUriTable
import network.lapis.cloud.server.db.generated.OidcClientRegistrationTable
import network.lapis.cloud.server.db.generated.OidcHomeServerRegistrationTable
import network.lapis.cloud.server.db.generated.OidcIssuedTokenTable
import network.lapis.cloud.server.db.generated.OidcRpLoginAttemptTable
import network.lapis.cloud.server.db.generated.OidcSigningKeyTable
import network.lapis.cloud.server.federation.FederationConfig
import network.lapis.cloud.server.federation.OIDC_SIGNING_KEY_ID
import network.lapis.cloud.server.federation.OidcClientRegistrar
import network.lapis.cloud.server.federation.OidcClientRegistrationOutcome
import network.lapis.cloud.server.federation.OidcDiscoveryDocument
import network.lapis.cloud.server.federation.OidcDiscoveryDto
import network.lapis.cloud.server.federation.OidcDynamicClientRegistrationRequest
import network.lapis.cloud.server.federation.OidcDynamicClientRegistrationResponse
import network.lapis.cloud.server.federation.OidcGuestClaims
import network.lapis.cloud.server.federation.OidcGuestMemberStore
import network.lapis.cloud.server.federation.OidcJwks
import network.lapis.cloud.server.federation.OidcJwt
import network.lapis.cloud.server.federation.OidcPkce
import network.lapis.cloud.server.federation.OidcScopes
import network.lapis.cloud.server.federation.OidcTokenErrorDto
import network.lapis.cloud.server.federation.OidcTokenResponseDto
import network.lapis.cloud.server.federation.federationHttpClient
import network.lapis.cloud.server.federation.readCappedFederationBodyOrNull
import network.lapis.cloud.server.federation.requireSafeFederationUrl
import network.lapis.cloud.server.security.LoginRateLimiter
import network.lapis.cloud.server.security.SESSION_COOKIE_NAME
import network.lapis.cloud.server.security.SessionStore
import network.lapis.cloud.server.security.SessionTokens
import network.lapis.cloud.server.security.resolveCurrentMember
import network.lapis.cloud.shared.domain.MemberStatusSets
import network.lapis.cloud.shared.domain.OidcLoginEventType
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import java.net.URI
import java.net.URLEncoder
import java.security.MessageDigest
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid

private val OIDC_JSON = Json { ignoreUnknownKeys = true }

private val AUTHORIZATION_CODE_TTL = 60.seconds
private val RP_LOGIN_ATTEMPT_TTL = 10.minutes
private val ACCESS_TOKEN_TTL = 1.hours
private val REFRESH_TOKEN_TTL = 30.days

/**
 * V0.8.2 OIDC-Gastzugang-Federation -- individual-MEMBER identity federation: a member of "home
 * server A" logging into "visited server B" using their home-server identity. A DIFFERENT
 * mechanism from V0.8.1's server-to-server CONTENT federation (`FederationRoutes.kt`) -- see
 * `25-oidc-guest-federation.kuml.kts` file header.
 *
 * **Scope boundary (deliberate, read before extending)**: this wave builds the OIDC Issuer + OIDC
 * Relying Party + the guest identity/session model needed to represent "a logged-in guest"
 * server-side. It does NOT build: real LTR-earning-as-a-guest mechanics (posting/reacting that
 * costs local LTR), the guest timeline badge/UI (V0.8.4), or Trust-Anchor/OpenID-Federation
 * governance (V0.8.3). Voting rights are NEVER an OIDC scope, full stop -- see [OidcScopes] KDoc.
 *
 * **Standard**: OAuth 2.0 / OpenID Connect. Home server = Identity Provider (IdP) / OIDC Issuer.
 * Visited server = Relying Party (RP) / OAuth client. Every endpoint below is spec-mandated
 * (`/.well-known/...`, `/authorize`, `/token`, `/jwks`, `/register`) -- dedicated Ktor routes, NOT
 * Kilua RPC, same "spec-shaped/pre-auth/external payload shape" reasoning
 * [registerFederationRoutes]/[registerAuthRoutes] already establish for their own non-RPC surfaces.
 *
 * **Client registration**: RFC 7591 Dynamic Client Registration, the open-federation default (no
 * trust-anchor pool yet -- that is V0.8.3). This server needs BOTH a DCR endpoint (as Issuer, for
 * other servers registering as clients against this server) AND DCR client logic (as RP,
 * registering itself against a guest's claimed home server) -- see [OidcClientRegistrar].
 *
 * **PKCE, `state`, `nonce`**: `code_challenge_method=S256` only (this codebase never implements the
 * `plain` PKCE method). `state` is this RP's own CSRF defense on `/rp/callback` -- unguessable,
 * single-use, generated server-side, never accepted twice. `nonce` defeats ID-Token replay across
 * login attempts. `redirect_uri` is validated by EXACT string match, both at `/authorize` (against
 * the client's registered set) and at `/token` (against the SPECIFIC value stored on the
 * authorization code) -- defeats a "register two URIs, redirect to one, redeem against the other"
 * mix-up, on top of the baseline open-redirect defense.
 *
 * **`alg` confusion / ID-Token verification**: see [OidcJwt] KDoc -- RS256 is hard-pinned at the
 * code level, never selected by the token's own header.
 *
 * **SSRF-defense reuse**: every outbound fetch this wave adds (home-server discovery, home-server
 * JWKS, token-endpoint POST, registration-endpoint POST, our own outbound Logout Token delivery)
 * goes through [requireSafeFederationUrl]/[federationHttpClient] UNCHANGED -- no new SSRF-guard
 * code is written this wave (see [network.lapis.cloud.server.federation.requireSafeFederationUrl]
 * KDoc for the one documented residual risk, DNS-rebinding TOCTOU, inherited unchanged).
 *
 * **Back-Channel Logout receiver "reject before fetch"**: [OidcJwt.extractUnverifiedIssuer] is used
 * to look up a KNOWN, already-registered home server BEFORE any JWKS fetch is attempted -- an
 * attacker declaring an unregistered `iss` is rejected immediately, closing the "make us SSRF-fetch
 * an arbitrary attacker-controlled JWKS URL" path before any network call happens (defense in depth
 * on top of [requireSafeFederationUrl], which would also block private-range targets but not a
 * public attacker-controlled server).
 */
fun Route.registerOidcRoutes(
    cookieSecure: Boolean,
    registrationRateLimiter: LoginRateLimiter,
) {
    get("/.well-known/openid-configuration") {
        call.respondText(
            OidcDiscoveryDocument.toJson(OidcDiscoveryDocument.build()),
            contentType = io.ktor.http.ContentType.Application.Json,
        )
    }

    get("/federation/oidc/jwks") {
        val signingKey = transaction { loadSigningKeyRow() }
        if (signingKey == null) {
            call.respond(HttpStatusCode.ServiceUnavailable, "OIDC signing key not yet provisioned")
            return@get
        }
        call.respondText(
            OidcJwks.buildJwksJson(publicKeyPem = signingKey.publicKeyPem, kid = signingKey.kid),
            contentType = io.ktor.http.ContentType.Application.Json,
        )
    }

    get("/federation/oidc/authorize") {
        val params = call.request.queryParameters
        val responseType = params["response_type"]
        val clientId = params["client_id"]
        val redirectUri = params["redirect_uri"]
        val scopeParam = params["scope"] ?: ""
        val state = params["state"]
        val codeChallenge = params["code_challenge"]
        val codeChallengeMethod = params["code_challenge_method"]
        val nonce = params["nonce"]

        if (responseType != "code" ||
            clientId.isNullOrBlank() ||
            redirectUri.isNullOrBlank() ||
            state.isNullOrBlank() ||
            codeChallenge.isNullOrBlank() ||
            nonce.isNullOrBlank()
        ) {
            call.respond(HttpStatusCode.BadRequest, "Missing or invalid required authorize parameter(s)")
            return@get
        }
        if (codeChallengeMethod != "S256") {
            call.respond(HttpStatusCode.BadRequest, "code_challenge_method must be S256")
            return@get
        }
        val requestedScopes = scopeParam.split(" ").filter { it.isNotBlank() }.toSet()
        if (requestedScopes.isEmpty() || !requestedScopes.all { it in OidcScopes.ALL }) {
            call.respond(HttpStatusCode.BadRequest, "Missing or unknown scope")
            return@get
        }

        val clientRow =
            transaction {
                OidcClientRegistrationTable.selectAll().where { OidcClientRegistrationTable.clientId eq clientId }.singleOrNull()
            }
        if (clientRow == null) {
            call.respond(HttpStatusCode.BadRequest, "Unknown client_id")
            return@get
        }
        val clientRegistrationId = clientRow[OidcClientRegistrationTable.id]
        val redirectUriRegistered =
            transaction {
                OidcClientRedirectUriTable
                    .selectAll()
                    .where {
                        (OidcClientRedirectUriTable.clientRegistrationId eq clientRegistrationId) and
                            (OidcClientRedirectUriTable.redirectUri eq redirectUri)
                    }.count() > 0
            }
        if (!redirectUriRegistered) {
            call.respond(HttpStatusCode.BadRequest, "redirect_uri is not registered for this client")
            return@get
        }

        val current = runCatching { resolveCurrentMember(call) }.getOrNull()
        if (current == null) {
            val returnTo = URLEncoder.encode(call.request.uri, "UTF-8")
            call.respondRedirect("/#/login?returnTo=$returnTo")
            return@get
        }

        call.respondText(
            consentPageHtml(
                clientName = clientRow[OidcClientRegistrationTable.clientName],
                scopes = requestedScopes,
                clientId = clientId,
                redirectUri = redirectUri,
                scopeParam = scopeParam,
                state = state,
                codeChallenge = codeChallenge,
                nonce = nonce,
            ),
            contentType = io.ktor.http.ContentType.Text.Html,
        )
    }

    post("/federation/oidc/authorize/consent") {
        val current =
            runCatching { resolveCurrentMember(call) }.getOrElse {
                call.respond(HttpStatusCode.Unauthorized, "No active session")
                return@post
            }
        val form = call.receiveParameters()
        val decision = form["decision"]
        val clientId = form["client_id"]
        val redirectUri = form["redirect_uri"]
        val scope = form["scope"] ?: ""
        val state = form["state"]
        val codeChallenge = form["code_challenge"]
        val nonce = form["nonce"]

        if (clientId.isNullOrBlank() || redirectUri.isNullOrBlank() || state.isNullOrBlank() || codeChallenge.isNullOrBlank()) {
            call.respond(HttpStatusCode.BadRequest, "Missing consent parameter(s)")
            return@post
        }

        val clientRow =
            transaction {
                OidcClientRegistrationTable.selectAll().where { OidcClientRegistrationTable.clientId eq clientId }.singleOrNull()
            }
        if (clientRow == null) {
            call.respond(HttpStatusCode.BadRequest, "Unknown client_id")
            return@post
        }
        val clientRegistrationId = clientRow[OidcClientRegistrationTable.id]
        val redirectUriRegistered =
            transaction {
                OidcClientRedirectUriTable
                    .selectAll()
                    .where {
                        (OidcClientRedirectUriTable.clientRegistrationId eq clientRegistrationId) and
                            (OidcClientRedirectUriTable.redirectUri eq redirectUri)
                    }.count() > 0
            }
        if (!redirectUriRegistered) {
            call.respond(HttpStatusCode.BadRequest, "redirect_uri is not registered for this client")
            return@post
        }

        if (decision != "allow") {
            call.respondRedirect(buildRedirectUrl(redirectUri = redirectUri, params = mapOf("error" to "access_denied", "state" to state)))
            return@post
        }

        val rawCode = SessionTokens.newRawToken()
        val now = nowLocalDateTime()
        transaction {
            OidcAuthorizationCodeTable.insert {
                it[id] = Uuid.random()
                it[codeHash] = SessionTokens.hash(rawCode)
                it[OidcAuthorizationCodeTable.clientRegistrationId] = clientRegistrationId
                it[memberId] = current.memberId
                it[OidcAuthorizationCodeTable.redirectUri] = redirectUri
                it[OidcAuthorizationCodeTable.scope] = scope
                it[OidcAuthorizationCodeTable.codeChallenge] = codeChallenge
                it[OidcAuthorizationCodeTable.nonce] = nonce
                it[createdAt] = now
                it[expiresAt] = plus(start = now, duration = AUTHORIZATION_CODE_TTL)
                it[consumedAt] = null
            }
        }
        call.respondRedirect(buildRedirectUrl(redirectUri = redirectUri, params = mapOf("code" to rawCode, "state" to state)))
    }

    post("/federation/oidc/token") {
        val form = call.receiveParameters()
        val grantType = form["grant_type"]
        val clientId = form["client_id"]
        val clientSecret = form["client_secret"]

        if (clientId.isNullOrBlank() || clientSecret.isNullOrBlank()) {
            call.respondText(
                OIDC_JSON.encodeToString(OidcTokenErrorDto.serializer(), OidcTokenErrorDto(error = "invalid_client")),
                contentType = io.ktor.http.ContentType.Application.Json,
                status = HttpStatusCode.BadRequest,
            )
            return@post
        }
        val clientRow =
            transaction {
                OidcClientRegistrationTable.selectAll().where { OidcClientRegistrationTable.clientId eq clientId }.singleOrNull()
            }
        val clientSecretValid =
            clientRow != null &&
                MessageDigest.isEqual(
                    SessionTokens.hash(clientSecret).toByteArray(Charsets.UTF_8),
                    clientRow[OidcClientRegistrationTable.clientSecretHash].toByteArray(Charsets.UTF_8),
                )
        if (clientRow == null || !clientSecretValid) {
            OidcLoginAuditRecorder.record(
                eventType = OidcLoginEventType.ISSUER_TOKEN_ISSUE_FAILED,
                remoteParty = clientId,
                reason = "INVALID_CLIENT",
            )
            call.respondText(
                OIDC_JSON.encodeToString(OidcTokenErrorDto.serializer(), OidcTokenErrorDto(error = "invalid_client")),
                contentType = io.ktor.http.ContentType.Application.Json,
                status = HttpStatusCode.Unauthorized,
            )
            return@post
        }
        val clientRegistrationId = clientRow[OidcClientRegistrationTable.id]

        when (grantType) {
            "authorization_code" ->
                handleAuthorizationCodeGrant(
                    call = call,
                    form = form,
                    clientRegistrationId = clientRegistrationId,
                    clientId = clientId,
                )
            "refresh_token" ->
                handleRefreshTokenGrant(
                    call = call,
                    form = form,
                    clientRegistrationId = clientRegistrationId,
                    clientId = clientId,
                )
            else -> {
                call.respondText(
                    OIDC_JSON.encodeToString(OidcTokenErrorDto.serializer(), OidcTokenErrorDto(error = "unsupported_grant_type")),
                    contentType = io.ktor.http.ContentType.Application.Json,
                    status = HttpStatusCode.BadRequest,
                )
            }
        }
    }

    post("/federation/oidc/register") {
        val ipKey = "ip:${call.request.origin.remoteHost}"
        if (!registrationRateLimiter.checkAllowed(ipKey)) {
            call.respond(HttpStatusCode.TooManyRequests, "Too many registration attempts -- try again later")
            return@post
        }
        // Every attempt (successful or not) counts against the throttle -- this is an open,
        // unauthenticated, spam-prone endpoint, not a brute-force-only guard like the login limiter.
        registrationRateLimiter.recordFailure(ipKey)

        val body =
            runCatching {
                OIDC_JSON.decodeFromString(
                    OidcDynamicClientRegistrationRequest.serializer(),
                    call.receiveText(),
                )
            }.getOrNull()
        if (body == null || body.client_name.isBlank() || body.redirect_uris.isEmpty()) {
            call.respond(HttpStatusCode.BadRequest, "client_name and at least one redirect_uri are required")
            return@post
        }
        // HTTPS-only gate -- see class KDoc: this is the root defense against ever sending an
        // authorization code to a plain-HTTP (interceptable) redirect target.
        if (body.redirect_uris.any { !it.startsWith("https://") } ||
            (body.backchannel_logout_uri != null && !body.backchannel_logout_uri.startsWith("https://"))
        ) {
            call.respond(HttpStatusCode.BadRequest, "redirect_uris and backchannel_logout_uri must be HTTPS")
            return@post
        }

        val newClientId = Uuid.random().toString()
        val rawClientSecret = SessionTokens.newRawToken()
        val now = nowLocalDateTime()
        transaction {
            val registrationId = Uuid.random()
            OidcClientRegistrationTable.insert {
                it[id] = registrationId
                it[OidcClientRegistrationTable.clientId] = newClientId
                it[clientSecretHash] = SessionTokens.hash(rawClientSecret)
                it[clientName] = body.client_name
                it[backchannelLogoutUri] = body.backchannel_logout_uri
                it[createdAt] = now
            }
            body.redirect_uris.forEach { uri ->
                OidcClientRedirectUriTable.insert {
                    it[id] = Uuid.random()
                    it[clientRegistrationId] = registrationId
                    it[redirectUri] = uri
                }
            }
        }
        call.respond(
            HttpStatusCode.Created,
            OidcDynamicClientRegistrationResponse(
                client_id = newClientId,
                client_secret = rawClientSecret,
                client_id_issued_at = now.toInstant(TimeZone.UTC).epochSeconds,
                client_secret_expires_at = 0,
                redirect_uris = body.redirect_uris,
                grant_types = listOf("authorization_code", "refresh_token"),
                response_types = listOf("code"),
                token_endpoint_auth_method = "client_secret_post",
            ),
        )
    }

    get("/federation/oidc/rp/login") {
        call.respondText(rpLoginFormHtml(), contentType = io.ktor.http.ContentType.Text.Html)
    }

    post("/federation/oidc/rp/login") {
        val form = call.receiveParameters()
        val rawInput = form["home_server"]?.trim()
        if (rawInput.isNullOrBlank()) {
            call.respond(HttpStatusCode.BadRequest, "home_server is required")
            return@post
        }
        val homeServer = normalizeHomeServerOrigin(rawInput)
        if (homeServer == null) {
            OidcLoginAuditRecorder.record(
                eventType = OidcLoginEventType.RP_LOGIN_FAILED,
                remoteParty = rawInput.take(2048),
                reason = "INVALID_HOME_SERVER_URL",
            )
            call.respondText(
                errorPageHtml("Invalid home-server address."),
                contentType = io.ktor.http.ContentType.Text.Html,
                status = HttpStatusCode.BadRequest,
            )
            return@post
        }

        val discovery = fetchDiscoveryDocument(homeServer)
        if (discovery == null) {
            OidcLoginAuditRecorder.record(
                eventType = OidcLoginEventType.RP_LOGIN_FAILED,
                remoteParty = homeServer,
                reason = "DISCOVERY_FAILED",
            )
            call.respondText(
                errorPageHtml("Could not reach the OIDC configuration of $homeServer."),
                contentType = io.ktor.http.ContentType.Text.Html,
                status = HttpStatusCode.BadGateway,
            )
            return@post
        }
        if (discovery.issuer != homeServer) {
            OidcLoginAuditRecorder.record(
                eventType = OidcLoginEventType.RP_LOGIN_FAILED,
                remoteParty = homeServer,
                reason = "ISSUER_MISMATCH",
            )
            call.respondText(
                errorPageHtml("The home server's own discovery document disagrees about its issuer identity."),
                contentType = io.ktor.http.ContentType.Text.Html,
                status = HttpStatusCode.BadGateway,
            )
            return@post
        }

        val registrationRow =
            transaction {
                OidcHomeServerRegistrationTable
                    .selectAll()
                    .where {
                        OidcHomeServerRegistrationTable.issuerUrl eq
                            homeServer
                    }.singleOrNull()
            }
        val registration =
            registrationRow ?: run {
                if (!discovery.registration_endpoint.startsWith("https://")) {
                    OidcLoginAuditRecorder.record(
                        eventType = OidcLoginEventType.RP_LOGIN_FAILED,
                        remoteParty = homeServer,
                        reason = "REGISTRATION_ENDPOINT_NOT_HTTPS",
                    )
                    return@run null
                }
                val redirectUri = "${FederationConfig.publicBaseUrl}/federation/oidc/rp/callback"
                val backchannelUri = "${FederationConfig.publicBaseUrl}/federation/oidc/backchannel-logout"
                val outcome =
                    OidcClientRegistrar.register(
                        registrationEndpoint = discovery.registration_endpoint,
                        clientName = "Lapis Cloud",
                        redirectUri = redirectUri,
                        backchannelLogoutUri = backchannelUri,
                    )
                when (outcome) {
                    is OidcClientRegistrationOutcome.Failure -> {
                        OidcLoginAuditRecorder.record(
                            eventType = OidcLoginEventType.RP_LOGIN_FAILED,
                            remoteParty = homeServer,
                            reason = "DCR_FAILED:${outcome.reason}".take(255),
                        )
                        null
                    }
                    is OidcClientRegistrationOutcome.Success -> {
                        val now = nowLocalDateTime()
                        transaction {
                            OidcHomeServerRegistrationTable.insert {
                                it[id] = Uuid.random()
                                it[issuerUrl] = homeServer
                                it[authorizationEndpoint] = discovery.authorization_endpoint
                                it[tokenEndpoint] = discovery.token_endpoint
                                it[jwksUri] = discovery.jwks_uri
                                it[clientId] = outcome.response.client_id
                                it[clientSecret] = outcome.response.client_secret
                                it[registeredAt] = now
                            }
                        }
                        transaction {
                            OidcHomeServerRegistrationTable
                                .selectAll()
                                .where {
                                    OidcHomeServerRegistrationTable.issuerUrl eq
                                        homeServer
                                }.single()
                        }
                    }
                }
            }
        if (registration == null) {
            call.respondText(
                errorPageHtml("Could not register with the home server."),
                contentType = io.ktor.http.ContentType.Text.Html,
                status = HttpStatusCode.BadGateway,
            )
            return@post
        }

        val state = SessionTokens.newRawToken()
        val nonce = SessionTokens.newRawToken()
        val codeVerifier = SessionTokens.newRawToken()
        val codeChallenge = OidcPkce.codeChallengeS256(codeVerifier)
        val redirectUri = "${FederationConfig.publicBaseUrl}/federation/oidc/rp/callback"
        val now = nowLocalDateTime()
        transaction {
            OidcRpLoginAttemptTable.insert {
                it[id] = Uuid.random()
                it[stateHash] = SessionTokens.hash(state)
                it[homeServerRegistrationId] = registration[OidcHomeServerRegistrationTable.id]
                it[OidcRpLoginAttemptTable.codeVerifier] = codeVerifier
                it[OidcRpLoginAttemptTable.nonce] = nonce
                it[OidcRpLoginAttemptTable.redirectUri] = redirectUri
                it[createdAt] = now
                it[expiresAt] = plus(start = now, duration = RP_LOGIN_ATTEMPT_TTL)
                it[consumedAt] = null
            }
        }

        val scope = OidcScopes.ALL.joinToString(" ")
        val authorizeUrl =
            buildString {
                append(registration[OidcHomeServerRegistrationTable.authorizationEndpoint])
                append("?response_type=code")
                append("&client_id=").append(URLEncoder.encode(registration[OidcHomeServerRegistrationTable.clientId], "UTF-8"))
                append("&redirect_uri=").append(URLEncoder.encode(redirectUri, "UTF-8"))
                append("&scope=").append(URLEncoder.encode(scope, "UTF-8"))
                append("&state=").append(URLEncoder.encode(state, "UTF-8"))
                append("&code_challenge=").append(URLEncoder.encode(codeChallenge, "UTF-8"))
                append("&code_challenge_method=S256")
                append("&nonce=").append(URLEncoder.encode(nonce, "UTF-8"))
            }
        call.respondRedirect(authorizeUrl)
    }

    get("/federation/oidc/rp/callback") {
        val params = call.request.queryParameters
        val code = params["code"]
        val state = params["state"]
        val error = params["error"]

        if (state.isNullOrBlank()) {
            call.respond(HttpStatusCode.BadRequest, "Missing state parameter")
            return@get
        }
        val stateHashValue = SessionTokens.hash(state)
        val now = nowLocalDateTime()
        val attempt =
            transaction {
                val row =
                    OidcRpLoginAttemptTable
                        .selectAll()
                        .where {
                            (OidcRpLoginAttemptTable.stateHash eq stateHashValue) and
                                OidcRpLoginAttemptTable.consumedAt.isNull() and
                                (OidcRpLoginAttemptTable.expiresAt greater now)
                        }.forUpdate()
                        .singleOrNull() ?: return@transaction null
                val updated =
                    OidcRpLoginAttemptTable.update({
                        (OidcRpLoginAttemptTable.stateHash eq stateHashValue) and OidcRpLoginAttemptTable.consumedAt.isNull()
                    }) { it[consumedAt] = now }
                if (updated == 0) return@transaction null
                row
            }
        if (attempt == null) {
            // CSRF/replay defense: an attacker cannot forge this callback without knowing a
            // `state` value this server itself generated and never disclosed except via the 302
            // Location header to the legitimate browser.
            OidcLoginAuditRecorder.record(eventType = OidcLoginEventType.RP_LOGIN_FAILED, reason = "UNKNOWN_OR_EXPIRED_STATE")
            call.respondText(
                errorPageHtml("Invalid or expired login attempt."),
                contentType = io.ktor.http.ContentType.Text.Html,
                status = HttpStatusCode.Unauthorized,
            )
            return@get
        }

        val registration =
            transaction {
                OidcHomeServerRegistrationTable
                    .selectAll()
                    .where {
                        OidcHomeServerRegistrationTable.id eq
                            attempt[OidcRpLoginAttemptTable.homeServerRegistrationId]
                    }.single()
            }
        val homeServerIssuer = registration[OidcHomeServerRegistrationTable.issuerUrl]

        if (error != null || code.isNullOrBlank()) {
            OidcLoginAuditRecorder.record(
                eventType = OidcLoginEventType.RP_LOGIN_FAILED,
                remoteParty = homeServerIssuer,
                reason =
                    (
                        error
                            ?: "MISSING_CODE"
                    ).take(255),
            )
            call.respondText(
                errorPageHtml("Login was not completed."),
                contentType = io.ktor.http.ContentType.Text.Html,
                status = HttpStatusCode.Unauthorized,
            )
            return@get
        }

        val tokenResponse =
            runCatching {
                val tokenEndpoint = registration[OidcHomeServerRegistrationTable.tokenEndpoint]
                val target = requireSafeFederationUrl(tokenEndpoint)
                federationHttpClient(target).use { client ->
                    val response =
                        client.post(tokenEndpoint) {
                            setBody(
                                FormDataContent(
                                    Parameters.build {
                                        append("grant_type", "authorization_code")
                                        append("code", code)
                                        append("redirect_uri", attempt[OidcRpLoginAttemptTable.redirectUri])
                                        append("client_id", registration[OidcHomeServerRegistrationTable.clientId])
                                        append("client_secret", registration[OidcHomeServerRegistrationTable.clientSecret])
                                        append("code_verifier", attempt[OidcRpLoginAttemptTable.codeVerifier])
                                    },
                                ),
                            )
                        }
                    if (!response.status.isSuccess()) return@use null
                    val bytes = response.readCappedFederationBodyOrNull() ?: return@use null
                    runCatching {
                        OIDC_JSON.decodeFromString(
                            OidcTokenResponseDto.serializer(),
                            bytes.toString(Charsets.UTF_8),
                        )
                    }.getOrNull()
                }
            }.getOrNull()
        if (tokenResponse == null) {
            OidcLoginAuditRecorder.record(
                eventType = OidcLoginEventType.RP_LOGIN_FAILED,
                remoteParty = homeServerIssuer,
                reason = "TOKEN_EXCHANGE_FAILED",
            )
            call.respondText(
                errorPageHtml("Could not exchange the authorization code."),
                contentType = io.ktor.http.ContentType.Text.Html,
                status = HttpStatusCode.BadGateway,
            )
            return@get
        }

        val jwksJson =
            runCatching {
                val jwksUri = registration[OidcHomeServerRegistrationTable.jwksUri]
                val target = requireSafeFederationUrl(jwksUri)
                federationHttpClient(target).use { client ->
                    val response = client.get(jwksUri)
                    if (!response.status.isSuccess()) return@use null
                    response.readCappedFederationBodyOrNull()?.toString(Charsets.UTF_8)
                }
            }.getOrNull()
        if (jwksJson == null) {
            OidcLoginAuditRecorder.record(
                eventType = OidcLoginEventType.RP_LOGIN_FAILED,
                remoteParty = homeServerIssuer,
                reason = "JWKS_FETCH_FAILED",
            )
            call.respondText(
                errorPageHtml("Could not fetch the home server's signing keys."),
                contentType = io.ktor.http.ContentType.Text.Html,
                status = HttpStatusCode.BadGateway,
            )
            return@get
        }

        val kid = OidcJwt.extractUnverifiedKid(tokenResponse.id_token)
        val publicKeyPem = kid?.let { OidcJwks.findRsaPublicKeyPem(jwksJson = jwksJson, kid = it) }
        if (publicKeyPem == null) {
            OidcLoginAuditRecorder.record(
                eventType = OidcLoginEventType.RP_LOGIN_FAILED,
                remoteParty = homeServerIssuer,
                reason = "UNKNOWN_KID",
            )
            call.respondText(
                errorPageHtml("Unknown signing key."),
                contentType = io.ktor.http.ContentType.Text.Html,
                status = HttpStatusCode.Unauthorized,
            )
            return@get
        }

        val verification =
            OidcJwt.verifyIdToken(
                compact = tokenResponse.id_token,
                publicKeyPem = publicKeyPem,
                expectedIssuer = homeServerIssuer,
                expectedAudience = registration[OidcHomeServerRegistrationTable.clientId],
                expectedNonce = attempt[OidcRpLoginAttemptTable.nonce],
            )
        if (verification is OidcJwt.VerificationResult.Invalid) {
            OidcLoginAuditRecorder.record(
                eventType = OidcLoginEventType.RP_LOGIN_FAILED,
                remoteParty = homeServerIssuer,
                reason = verification.reason,
            )
            call.respondText(
                errorPageHtml("ID token verification failed."),
                contentType = io.ktor.http.ContentType.Text.Html,
                status = HttpStatusCode.Unauthorized,
            )
            return@get
        }
        val claims = (verification as OidcJwt.VerificationResult.Valid).claims
        val subject = claims.subject
        if (subject.isNullOrBlank()) {
            OidcLoginAuditRecorder.record(
                eventType = OidcLoginEventType.RP_LOGIN_FAILED,
                remoteParty = homeServerIssuer,
                reason = "MISSING_SUB",
            )
            call.respondText(
                errorPageHtml("ID token is missing a subject."),
                contentType = io.ktor.http.ContentType.Text.Html,
                status = HttpStatusCode.Unauthorized,
            )
            return@get
        }

        val guestClaims =
            OidcGuestClaims(
                issuer = homeServerIssuer,
                subject = subject,
                name = runCatching { claims.getStringClaim("name") }.getOrNull(),
                picture = runCatching { claims.getStringClaim("picture") }.getOrNull(),
                preferredUsername = runCatching { claims.getStringClaim("preferred_username") }.getOrNull(),
                homeserverUrl = runCatching { claims.getStringClaim("homeserver_url") }.getOrNull() ?: homeServerIssuer,
                membershipStatus = runCatching { claims.getStringClaim("membership_status") }.getOrNull(),
            )
        // The home server's own token response's `scope` is what it actually granted -- this
        // server's local org policy further intersects it against the scopes THIS org's
        // configuration allows a guest to be granted (deferred to a future org-settings knob this
        // wave, see class KDoc "Explicit non-goals" -- for now, every scope the home server
        // returned that this server also recognizes is honored; "pzb:comment"/"pzb:post_paid" are
        // recognized as scope literals but NOT wired into any write path yet, so granting them here
        // has no economic/posting effect this wave regardless).
        val grantedScope =
            tokenResponse.scope
                .split(" ")
                .filter {
                    it in OidcScopes.ALL
                }.joinToString(" ")
                .ifBlank { OidcScopes.ALWAYS_GRANTED.joinToString(" ") }

        val memberId = OidcGuestMemberStore.resolveOrCreateGuestMember(claims = guestClaims, grantedScope = grantedScope)
        val issuedSession = SessionStore.createSession(memberId)
        call.response.cookies.append(
            Cookie(
                name = SESSION_COOKIE_NAME,
                value = issuedSession.rawToken,
                encoding = CookieEncoding.URI_ENCODING,
                maxAge = SessionStore.SESSION_TTL.inWholeSeconds.toInt(),
                path = "/",
                secure = cookieSecure,
                httpOnly = true,
                extensions = mapOf("SameSite" to "Strict"),
            ),
        )
        OidcLoginAuditRecorder.record(eventType = OidcLoginEventType.RP_LOGIN_SUCCESS, memberId = memberId, remoteParty = homeServerIssuer)
        call.respondRedirect("/")
    }

    post("/federation/oidc/backchannel-logout") {
        val form = call.receiveParameters()
        val logoutToken = form["logout_token"]
        if (logoutToken.isNullOrBlank()) {
            call.respond(HttpStatusCode.BadRequest, "logout_token is required")
            return@post
        }

        val claimedIssuer = OidcJwt.extractUnverifiedIssuer(logoutToken)
        if (claimedIssuer.isNullOrBlank()) {
            call.respond(HttpStatusCode.BadRequest, "Malformed logout_token")
            return@post
        }
        // "reject before fetch" -- see class KDoc.
        val registration =
            transaction {
                OidcHomeServerRegistrationTable
                    .selectAll()
                    .where {
                        OidcHomeServerRegistrationTable.issuerUrl eq
                            claimedIssuer
                    }.singleOrNull()
            }
        if (registration == null) {
            OidcLoginAuditRecorder.record(
                eventType = OidcLoginEventType.BACKCHANNEL_LOGOUT_RECEIVED,
                remoteParty = claimedIssuer,
                reason = "UNKNOWN_ISSUER",
            )
            call.respond(HttpStatusCode.BadRequest, "Unknown issuer")
            return@post
        }

        val jwksJson =
            runCatching {
                val jwksUri = registration[OidcHomeServerRegistrationTable.jwksUri]
                val target = requireSafeFederationUrl(jwksUri)
                federationHttpClient(target).use { client ->
                    val response = client.get(jwksUri)
                    if (!response.status.isSuccess()) return@use null
                    response.readCappedFederationBodyOrNull()?.toString(Charsets.UTF_8)
                }
            }.getOrNull()
        if (jwksJson == null) {
            OidcLoginAuditRecorder.record(
                eventType = OidcLoginEventType.BACKCHANNEL_LOGOUT_RECEIVED,
                remoteParty = claimedIssuer,
                reason = "JWKS_FETCH_FAILED",
            )
            call.respond(HttpStatusCode.BadRequest, "Could not fetch signing keys")
            return@post
        }
        val kid = OidcJwt.extractUnverifiedKid(logoutToken)
        val publicKeyPem = kid?.let { OidcJwks.findRsaPublicKeyPem(jwksJson = jwksJson, kid = it) }
        if (publicKeyPem == null) {
            OidcLoginAuditRecorder.record(
                eventType = OidcLoginEventType.BACKCHANNEL_LOGOUT_RECEIVED,
                remoteParty = claimedIssuer,
                reason = "UNKNOWN_KID",
            )
            call.respond(HttpStatusCode.BadRequest, "Unknown signing key")
            return@post
        }

        val verification =
            OidcJwt.verifyLogoutToken(
                compact = logoutToken,
                publicKeyPem = publicKeyPem,
                expectedIssuer = registration[OidcHomeServerRegistrationTable.issuerUrl],
                expectedAudience = registration[OidcHomeServerRegistrationTable.clientId],
            )
        if (verification is OidcJwt.VerificationResult.Invalid) {
            OidcLoginAuditRecorder.record(
                eventType = OidcLoginEventType.BACKCHANNEL_LOGOUT_RECEIVED,
                remoteParty = claimedIssuer,
                reason = verification.reason,
            )
            call.respond(HttpStatusCode.BadRequest, "Invalid logout_token: ${verification.reason}")
            return@post
        }

        val subject = (verification as OidcJwt.VerificationResult.Valid).claims.subject
        val guestMemberId =
            subject?.let { sub ->
                transaction {
                    (AccountTable innerJoin MemberTable)
                        .selectAll()
                        .where { (AccountTable.oidcIssuer eq claimedIssuer) and (AccountTable.oidcSubject eq sub) }
                        .singleOrNull()
                        ?.get(MemberTable.id)
                }
            }
        // Idempotent, no information leak: 200 whether or not the subject is currently known here.
        if (guestMemberId != null) {
            SessionStore.revokeAllForMember(memberId = guestMemberId)
        }
        OidcLoginAuditRecorder.record(
            eventType = OidcLoginEventType.BACKCHANNEL_LOGOUT_RECEIVED,
            memberId = guestMemberId,
            remoteParty = claimedIssuer,
        )
        call.respond(HttpStatusCode.OK)
    }
}

private data class SigningKeyRow(
    val kid: String,
    val publicKeyPem: String,
    val privateKeyPem: String,
)

private fun loadSigningKeyRow(): SigningKeyRow? =
    OidcSigningKeyTable
        .selectAll()
        .where { OidcSigningKeyTable.id eq OIDC_SIGNING_KEY_ID }
        .singleOrNull()
        ?.let {
            SigningKeyRow(
                kid = it[OidcSigningKeyTable.kid],
                publicKeyPem = it[OidcSigningKeyTable.publicKeyPem],
                privateKeyPem = it[OidcSigningKeyTable.privateKeyPem],
            )
        }

private suspend fun handleAuthorizationCodeGrant(
    call: io.ktor.server.application.ApplicationCall,
    form: Parameters,
    clientRegistrationId: Uuid,
    clientId: String,
) {
    val code = form["code"]
    val redirectUri = form["redirect_uri"]
    val codeVerifier = form["code_verifier"]
    if (code.isNullOrBlank() || redirectUri.isNullOrBlank() || codeVerifier.isNullOrBlank()) {
        call.respondText(
            OIDC_JSON.encodeToString(OidcTokenErrorDto.serializer(), OidcTokenErrorDto(error = "invalid_request")),
            contentType = io.ktor.http.ContentType.Application.Json,
            status = HttpStatusCode.BadRequest,
        )
        return
    }

    val codeHashValue = SessionTokens.hash(code)
    val now = nowLocalDateTime()
    val consumedRow =
        transaction {
            val row =
                OidcAuthorizationCodeTable
                    .selectAll()
                    .where {
                        (OidcAuthorizationCodeTable.codeHash eq codeHashValue) and
                            OidcAuthorizationCodeTable.consumedAt.isNull() and
                            (OidcAuthorizationCodeTable.expiresAt greater now)
                    }.forUpdate()
                    .singleOrNull() ?: return@transaction null
            val updated =
                OidcAuthorizationCodeTable.update({
                    (OidcAuthorizationCodeTable.codeHash eq codeHashValue) and OidcAuthorizationCodeTable.consumedAt.isNull()
                }) { it[consumedAt] = now }
            if (updated == 0) return@transaction null
            row
        }
    if (consumedRow == null) {
        OidcLoginAuditRecorder.record(
            eventType = OidcLoginEventType.ISSUER_TOKEN_ISSUE_FAILED,
            remoteParty = clientId,
            reason = "INVALID_OR_EXPIRED_CODE",
        )
        call.respondText(
            OIDC_JSON.encodeToString(OidcTokenErrorDto.serializer(), OidcTokenErrorDto(error = "invalid_grant")),
            contentType = io.ktor.http.ContentType.Application.Json,
            status = HttpStatusCode.BadRequest,
        )
        return
    }
    if (consumedRow[OidcAuthorizationCodeTable.clientRegistrationId] != clientRegistrationId) {
        OidcLoginAuditRecorder.record(
            eventType = OidcLoginEventType.ISSUER_TOKEN_ISSUE_FAILED,
            memberId = consumedRow[OidcAuthorizationCodeTable.memberId],
            remoteParty = clientId,
            reason = "CLIENT_MISMATCH",
        )
        call.respondText(
            OIDC_JSON.encodeToString(OidcTokenErrorDto.serializer(), OidcTokenErrorDto(error = "invalid_grant")),
            contentType = io.ktor.http.ContentType.Application.Json,
            status = HttpStatusCode.BadRequest,
        )
        return
    }
    if (consumedRow[OidcAuthorizationCodeTable.redirectUri] != redirectUri) {
        OidcLoginAuditRecorder.record(
            eventType = OidcLoginEventType.ISSUER_TOKEN_ISSUE_FAILED,
            memberId = consumedRow[OidcAuthorizationCodeTable.memberId],
            remoteParty = clientId,
            reason = "REDIRECT_URI_MISMATCH",
        )
        call.respondText(
            OIDC_JSON.encodeToString(OidcTokenErrorDto.serializer(), OidcTokenErrorDto(error = "invalid_grant")),
            contentType = io.ktor.http.ContentType.Application.Json,
            status = HttpStatusCode.BadRequest,
        )
        return
    }
    val expectedChallenge = OidcPkce.codeChallengeS256(codeVerifier)
    if (expectedChallenge != consumedRow[OidcAuthorizationCodeTable.codeChallenge]) {
        OidcLoginAuditRecorder.record(
            eventType = OidcLoginEventType.ISSUER_TOKEN_ISSUE_FAILED,
            memberId = consumedRow[OidcAuthorizationCodeTable.memberId],
            remoteParty = clientId,
            reason = "PKCE_VERIFIER_MISMATCH",
        )
        call.respondText(
            OIDC_JSON.encodeToString(OidcTokenErrorDto.serializer(), OidcTokenErrorDto(error = "invalid_grant")),
            contentType = io.ktor.http.ContentType.Application.Json,
            status = HttpStatusCode.BadRequest,
        )
        return
    }

    val memberId = consumedRow[OidcAuthorizationCodeTable.memberId]
    val scope = consumedRow[OidcAuthorizationCodeTable.scope]
    val nonce = consumedRow[OidcAuthorizationCodeTable.nonce]
    issueTokens(
        call = call,
        clientRegistrationId = clientRegistrationId,
        clientId = clientId,
        memberId = memberId,
        scope = scope,
        nonce = nonce,
    )
}

private suspend fun handleRefreshTokenGrant(
    call: io.ktor.server.application.ApplicationCall,
    form: Parameters,
    clientRegistrationId: Uuid,
    clientId: String,
) {
    val refreshToken = form["refresh_token"]
    if (refreshToken.isNullOrBlank()) {
        call.respondText(
            OIDC_JSON.encodeToString(OidcTokenErrorDto.serializer(), OidcTokenErrorDto(error = "invalid_request")),
            contentType = io.ktor.http.ContentType.Application.Json,
            status = HttpStatusCode.BadRequest,
        )
        return
    }
    val refreshHashValue = SessionTokens.hash(refreshToken)
    val now = nowLocalDateTime()
    val existing =
        transaction {
            OidcIssuedTokenTable
                .selectAll()
                .where {
                    (OidcIssuedTokenTable.refreshTokenHash eq refreshHashValue) and
                        OidcIssuedTokenTable.revokedAt.isNull() and
                        (OidcIssuedTokenTable.refreshExpiresAt greater now)
                }.singleOrNull()
        }
    if (existing == null) {
        // Reuse detection (RFC 9700 SS4.14.2): a hash match against an ALREADY-REVOKED row means
        // this exact refresh token was legitimately rotated away earlier and is now being replayed
        // -- by the original holder if it lost track of the rotation response, or by an attacker
        // who stole the token before it was ever rotated. Either way this is the signal a stolen
        // token has surfaced, so the entire chain for that (member, client) pair is revoked as a
        // precaution rather than silently returning the same generic "invalid_grant" a merely
        // expired/unknown token would get -- this bounds the blast radius of a leaked refresh
        // token to "usable once more after theft, then the whole grant dies," instead of "usable
        // by the thief indefinitely as long as they keep rotating it themselves."
        val revokedMatch =
            transaction {
                OidcIssuedTokenTable
                    .selectAll()
                    .where { OidcIssuedTokenTable.refreshTokenHash eq refreshHashValue }
                    .singleOrNull()
            }
        if (revokedMatch != null && revokedMatch[OidcIssuedTokenTable.revokedAt] != null) {
            val victimMemberId = revokedMatch[OidcIssuedTokenTable.memberId]
            val victimClientRegistrationId = revokedMatch[OidcIssuedTokenTable.clientRegistrationId]
            transaction {
                OidcIssuedTokenTable.update({
                    (OidcIssuedTokenTable.memberId eq victimMemberId) and
                        (OidcIssuedTokenTable.clientRegistrationId eq victimClientRegistrationId) and
                        OidcIssuedTokenTable.revokedAt.isNull()
                }) { it[revokedAt] = now }
            }
            OidcLoginAuditRecorder.record(
                eventType = OidcLoginEventType.ISSUER_TOKEN_ISSUE_FAILED,
                memberId = victimMemberId,
                remoteParty = clientId,
                reason = "REFRESH_TOKEN_REUSE_DETECTED",
            )
        } else {
            OidcLoginAuditRecorder.record(
                eventType = OidcLoginEventType.ISSUER_TOKEN_ISSUE_FAILED,
                remoteParty = clientId,
                reason = "INVALID_OR_EXPIRED_REFRESH_TOKEN",
            )
        }
        call.respondText(
            OIDC_JSON.encodeToString(OidcTokenErrorDto.serializer(), OidcTokenErrorDto(error = "invalid_grant")),
            contentType = io.ktor.http.ContentType.Application.Json,
            status = HttpStatusCode.BadRequest,
        )
        return
    }
    // A refresh token minted for client A must never be redeemable by client B.
    if (existing[OidcIssuedTokenTable.clientRegistrationId] != clientRegistrationId) {
        OidcLoginAuditRecorder.record(
            eventType = OidcLoginEventType.ISSUER_TOKEN_ISSUE_FAILED,
            memberId = existing[OidcIssuedTokenTable.memberId],
            remoteParty = clientId,
            reason = "CLIENT_MISMATCH",
        )
        call.respondText(
            OIDC_JSON.encodeToString(OidcTokenErrorDto.serializer(), OidcTokenErrorDto(error = "invalid_grant")),
            contentType = io.ktor.http.ContentType.Application.Json,
            status = HttpStatusCode.BadRequest,
        )
        return
    }

    // Rotation: the old refresh token is revoked, a fresh access+refresh pair is minted --
    // standard refresh-token-rotation hygiene against replay of a stolen refresh token.
    transaction {
        OidcIssuedTokenTable.update({ OidcIssuedTokenTable.id eq existing[OidcIssuedTokenTable.id] }) { it[revokedAt] = now }
    }
    issueTokens(
        call = call,
        clientRegistrationId = clientRegistrationId,
        clientId = clientId,
        memberId = existing[OidcIssuedTokenTable.memberId],
        scope = existing[OidcIssuedTokenTable.scope],
        nonce = null,
    )
}

private suspend fun issueTokens(
    call: io.ktor.server.application.ApplicationCall,
    clientRegistrationId: Uuid,
    clientId: String,
    memberId: Uuid,
    scope: String,
    nonce: String?,
) {
    val memberRow = transaction { MemberTable.selectAll().where { MemberTable.id eq memberId }.singleOrNull() }
    val signingKey = transaction { loadSigningKeyRow() }
    if (memberRow == null || signingKey == null) {
        OidcLoginAuditRecorder.record(
            eventType = OidcLoginEventType.ISSUER_TOKEN_ISSUE_FAILED,
            memberId = memberId,
            remoteParty = clientId,
            reason = "MEMBER_OR_SIGNING_KEY_MISSING",
        )
        call.respondText(
            OIDC_JSON.encodeToString(OidcTokenErrorDto.serializer(), OidcTokenErrorDto(error = "server_error")),
            contentType = io.ktor.http.ContentType.Application.Json,
            status = HttpStatusCode.InternalServerError,
        )
        return
    }
    // V0.11.0 security fix -- the single most severe gap the FRIEND wave forced into the open: this
    // OP issued ID tokens (an outward-facing "this member is a GUEST of a trusted org" federation
    // credential) with NO membership-status gate at all. Before FRIEND existed this already let any
    // logged-in APPLICATION/GUEST caller federate outward; a self-service FRIEND account would have
    // turned this endpoint into an open identity-laundering service -- anyone self-registers as
    // FRIEND here, then walks into any federated partner org as "a GUEST from a trusted home
    // server". Checked on EVERY call to issueTokens -- i.e. both the authorization_code grant AND
    // the refresh_token grant (the latter re-issues from a stored memberId without otherwise
    // re-validating the subject, see the refresh_token grant handler above), so a status change
    // between grants (e.g. leaveMembership) takes effect on the very next refresh.
    if (memberRow[MemberTable.status] !in MemberStatusSets.ORGANIZATION_MEMBER) {
        OidcLoginAuditRecorder.record(
            eventType = OidcLoginEventType.ISSUER_TOKEN_ISSUE_FAILED,
            memberId = memberId,
            remoteParty = clientId,
            reason = "MEMBER_NOT_ORGANIZATION_MEMBER",
        )
        call.respondText(
            OIDC_JSON.encodeToString(OidcTokenErrorDto.serializer(), OidcTokenErrorDto(error = "invalid_grant")),
            contentType = io.ktor.http.ContentType.Application.Json,
            status = HttpStatusCode.BadRequest,
        )
        return
    }

    val now = Clock.System.now()
    val claimsBuilder =
        JWTClaimsSet
            .Builder()
            .issuer(FederationConfig.publicBaseUrl)
            .subject(memberId.toString())
            .audience(clientId)
            .claim("name", memberRow[MemberTable.displayName])
            .claim("preferred_username", memberRow[MemberTable.displayName])
            .claim("homeserver_url", FederationConfig.publicBaseUrl)
            .claim("membership_status", memberRow[MemberTable.status].name)
            .issueTime(OidcJwt.toJavaDate(now))
            .expirationTime(OidcJwt.toJavaDate(now + ACCESS_TOKEN_TTL))
    if (nonce != null) claimsBuilder.claim("nonce", nonce)
    val idToken = OidcJwt.sign(claimsSet = claimsBuilder.build(), kid = signingKey.kid, privateKeyPem = signingKey.privateKeyPem)

    val rawAccessToken = SessionTokens.newRawToken()
    val rawRefreshToken = SessionTokens.newRawToken()
    val nowLocal = nowLocalDateTime()
    transaction {
        OidcIssuedTokenTable.insert {
            it[id] = Uuid.random()
            it[OidcIssuedTokenTable.clientRegistrationId] = clientRegistrationId
            it[OidcIssuedTokenTable.memberId] = memberId
            it[accessTokenHash] = SessionTokens.hash(rawAccessToken)
            it[refreshTokenHash] = SessionTokens.hash(rawRefreshToken)
            it[OidcIssuedTokenTable.scope] = scope
            it[issuedAt] = nowLocal
            it[accessExpiresAt] = plus(start = nowLocal, duration = ACCESS_TOKEN_TTL)
            it[refreshExpiresAt] = plus(start = nowLocal, duration = REFRESH_TOKEN_TTL)
            it[revokedAt] = null
        }
    }
    OidcLoginAuditRecorder.record(eventType = OidcLoginEventType.ISSUER_TOKEN_ISSUED, memberId = memberId, remoteParty = clientId)
    call.respond(
        OidcTokenResponseDto(
            access_token = rawAccessToken,
            token_type = "Bearer",
            expires_in = ACCESS_TOKEN_TTL.inWholeSeconds,
            refresh_token = rawRefreshToken,
            id_token = idToken,
            scope = scope,
        ),
    )
}

private suspend fun fetchDiscoveryDocument(homeServer: String): OidcDiscoveryDto? =
    runCatching {
        val discoveryUrl = "$homeServer/.well-known/openid-configuration"
        val target = requireSafeFederationUrl(discoveryUrl)
        federationHttpClient(target).use { client ->
            val response = client.get(discoveryUrl)
            if (!response.status.isSuccess()) return@use null
            val bytes = response.readCappedFederationBodyOrNull() ?: return@use null
            runCatching { OIDC_JSON.decodeFromString(OidcDiscoveryDto.serializer(), bytes.toString(Charsets.UTF_8)) }.getOrNull()
        }
    }.getOrNull()

/** Normalizes free-text user input to a bare `https://host[:port]` origin -- strips path/query/fragment (WebFinger discovery deferred, plain-domain input only, per concept). `null` if unparseable or not resolvable to an HTTPS origin. */
private fun normalizeHomeServerOrigin(input: String): String? {
    val candidate = if (input.contains("://")) input else "https://$input"
    val uri = runCatching { URI(candidate) }.getOrNull() ?: return null
    if (uri.scheme != "https" || uri.host.isNullOrBlank()) return null
    val portSuffix = if (uri.port in 1..65535) ":${uri.port}" else ""
    return "https://${uri.host}$portSuffix"
}

private fun buildRedirectUrl(
    redirectUri: String,
    params: Map<String, String>,
): String {
    val separator = if (redirectUri.contains("?")) "&" else "?"
    val query = params.entries.joinToString("&") { (key, value) -> "$key=${URLEncoder.encode(value, "UTF-8")}" }
    return "$redirectUri$separator$query"
}

private fun htmlEscape(value: String): String =
    value
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&#39;")

private fun consentPageHtml(
    clientName: String,
    scopes: Set<String>,
    clientId: String,
    redirectUri: String,
    scopeParam: String,
    state: String,
    codeChallenge: String,
    nonce: String,
): String =
    """
    <!doctype html>
    <html><head><meta charset="utf-8"><title>Gastzugang genehmigen</title></head>
    <body>
    <h1>${htmlEscape(clientName)} moechte auf Ihre Lapis-Cloud-Identitaet zugreifen</h1>
    <p>Angeforderte Berechtigungen:</p>
    <ul>${scopes.joinToString("") { "<li>${htmlEscape(it)}</li>" }}</ul>
    <form method="post" action="/federation/oidc/authorize/consent">
      <input type="hidden" name="client_id" value="${htmlEscape(clientId)}">
      <input type="hidden" name="redirect_uri" value="${htmlEscape(redirectUri)}">
      <input type="hidden" name="scope" value="${htmlEscape(scopeParam)}">
      <input type="hidden" name="state" value="${htmlEscape(state)}">
      <input type="hidden" name="code_challenge" value="${htmlEscape(codeChallenge)}">
      <input type="hidden" name="nonce" value="${htmlEscape(nonce)}">
      <button type="submit" name="decision" value="allow">Erlauben</button>
      <button type="submit" name="decision" value="deny">Ablehnen</button>
    </form>
    </body></html>
    """.trimIndent()

private fun rpLoginFormHtml(): String =
    """
    <!doctype html>
    <html><head><meta charset="utf-8"><title>Mit Heimatserver anmelden</title></head>
    <body>
    <h1>Gastzugang -- mit Ihrem Heimatserver anmelden</h1>
    <form method="post" action="/federation/oidc/rp/login">
      <label for="home_server">Heimatserver-Adresse</label>
      <input id="home_server" name="home_server" placeholder="https://ihr-heimatserver.example">
      <button type="submit">Weiter</button>
    </form>
    </body></html>
    """.trimIndent()

private fun errorPageHtml(message: String): String =
    """
    <!doctype html>
    <html><head><meta charset="utf-8"><title>Anmeldung fehlgeschlagen</title></head>
    <body><h1>Anmeldung fehlgeschlagen</h1><p>${htmlEscape(message)}</p></body></html>
    """.trimIndent()

private fun nowLocalDateTime(): LocalDateTime = DbClock.nowLocalDateTime(TimeZone.UTC)

private fun plus(
    start: LocalDateTime,
    duration: kotlin.time.Duration,
): LocalDateTime = (start.toInstant(TimeZone.UTC) + duration).toLocalDateTime(TimeZone.UTC)
