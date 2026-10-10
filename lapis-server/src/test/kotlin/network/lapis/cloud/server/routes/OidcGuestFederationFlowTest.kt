package network.lapis.cloud.server.routes

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.Parameters
import io.ktor.http.contentType
import io.ktor.http.formUrlEncode
import io.ktor.server.testing.testApplication
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.json.Json
import network.lapis.cloud.server.ai.config.AiConfig
import network.lapis.cloud.server.db.DatabaseConfig
import network.lapis.cloud.server.db.generated.AccountTable
import network.lapis.cloud.server.db.generated.MemberStatusHistoryTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.server.db.generated.OidcAuthorizationCodeTable
import network.lapis.cloud.server.db.generated.OidcClientRedirectUriTable
import network.lapis.cloud.server.db.generated.OidcClientRegistrationTable
import network.lapis.cloud.server.db.generated.OidcGuestLoginEventTable
import network.lapis.cloud.server.db.generated.OidcGuestProfileTable
import network.lapis.cloud.server.db.generated.OidcHomeServerRegistrationTable
import network.lapis.cloud.server.db.generated.OidcIssuedTokenTable
import network.lapis.cloud.server.db.generated.OidcRpLoginAttemptTable
import network.lapis.cloud.server.db.generated.SessionTable
import network.lapis.cloud.server.federation.FederationConfig
import network.lapis.cloud.server.federation.OidcDynamicClientRegistrationResponse
import network.lapis.cloud.server.federation.OidcGuestAuthorizeRequest
import network.lapis.cloud.server.federation.OidcGuestClaims
import network.lapis.cloud.server.federation.OidcGuestMemberStore
import network.lapis.cloud.server.federation.OidcJwks
import network.lapis.cloud.server.federation.OidcJwt
import network.lapis.cloud.server.federation.OidcPkce
import network.lapis.cloud.server.federation.OidcScopes
import network.lapis.cloud.server.federation.OidcTokenResponseDto
import network.lapis.cloud.server.mcp.config.McpConfig
import network.lapis.cloud.server.module
import network.lapis.cloud.server.security.PasswordHasher
import network.lapis.cloud.server.security.SessionStore
import network.lapis.cloud.server.security.SessionTokens
import network.lapis.cloud.shared.auth.safeReturnTo
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.MemberStatus
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlin.uuid.Uuid

private val FLOW_JSON = Json { ignoreUnknownKeys = true }
private const val RP_REDIRECT = "https://rp.example/federation/oidc/rp/callback"
private const val GUEST_HOME_ISSUER = "https://home.example"
private const val MCP_RESOURCE_URL = "http://localhost:8080/mcp"

private data class McpMode(
    val label: String,
    val enabled: Boolean,
    val write: Boolean,
) {
    fun config(): McpConfig =
        McpConfig.load {
            when (it) {
                McpConfig.ENV_ENABLED -> if (enabled) "true" else null
                McpConfig.ENV_WRITE_ENABLED -> if (write) "true" else null
                else -> null
            }
        }
}

private val MODES =
    listOf(
        McpMode(label = "MCP off / write off", enabled = false, write = false),
        McpMode(label = "MCP off / write env on", enabled = false, write = true),
        McpMode(label = "MCP on / write off", enabled = true, write = false),
        McpMode(label = "MCP on / write on", enabled = true, write = true),
    )

/**
 * V1.9.99 -- guest federation, Issuer side driven by the RP's REAL authorize-URL builder, in-process.
 * The RP's HTTP transport hop (Discovery/DCR/token/JWKS fetch) cannot run in-process (the federation
 * client is CIO + SSRF-guarded on purpose; a test switch there would be a security risk), so every other hop is covered.
 */
class OidcGuestFederationFlowTest :
    FunSpec({
        val createdMemberIds = mutableListOf<Uuid>()
        val createdClientIds = mutableListOf<String>()
        val createdRegistrationIds = mutableListOf<Uuid>()
        val auditIssuers = mutableListOf<String>()

        beforeSpec { DatabaseConfig.connect() }

        afterSpec {
            transaction {
                OidcAuthorizationCodeTable.deleteWhere { OidcAuthorizationCodeTable.memberId inList createdMemberIds }
                OidcIssuedTokenTable.deleteWhere { OidcIssuedTokenTable.memberId inList createdMemberIds }
                SessionTable.deleteWhere { SessionTable.memberId inList createdMemberIds }
                OidcGuestProfileTable.deleteWhere { OidcGuestProfileTable.memberId inList createdMemberIds }
                AccountTable.deleteWhere { AccountTable.memberId inList createdMemberIds }
                MemberStatusHistoryTable.deleteWhere { MemberStatusHistoryTable.memberId inList createdMemberIds }
                MemberTable.deleteWhere { MemberTable.id inList createdMemberIds }
                val registrationIds =
                    OidcClientRegistrationTable
                        .selectAll()
                        .where { OidcClientRegistrationTable.clientId inList createdClientIds }
                        .map { it[OidcClientRegistrationTable.id] }
                OidcClientRedirectUriTable.deleteWhere { OidcClientRedirectUriTable.clientRegistrationId inList registrationIds }
                OidcClientRegistrationTable.deleteWhere { OidcClientRegistrationTable.clientId inList createdClientIds }
                OidcRpLoginAttemptTable.deleteWhere { OidcRpLoginAttemptTable.homeServerRegistrationId inList createdRegistrationIds }
                OidcHomeServerRegistrationTable.deleteWhere { OidcHomeServerRegistrationTable.id inList createdRegistrationIds }
                OidcGuestLoginEventTable.deleteWhere { OidcGuestLoginEventTable.remoteParty inList auditIssuers }
            }
        }

        fun createTestMember(status: MemberStatus = MemberStatus.ACTIVE): String {
            val id = Uuid.random()
            transaction {
                MemberTable.insert {
                    it[MemberTable.id] = id
                    it[displayName] = "Guest Federation Testmitglied"
                    it[email] = "guest-fed-${Uuid.random()}@example.org"
                    it[MemberTable.status] = status
                    it[joinedAt] = LocalDate(2026, 1, 1)
                    it[membershipTierId] = null
                }
                AccountTable.insert {
                    it[AccountTable.id] = Uuid.random()
                    it[memberId] = id
                    it[role] = AccountRole.MEMBER
                    it[passwordHash] = PasswordHasher.hash("irrelevant-password-1234")
                }
            }
            createdMemberIds += id
            return SessionStore.createSession(id).rawToken
        }

        suspend fun registerRp(client: HttpClient): OidcDynamicClientRegistrationResponse {
            val response =
                client.post("/federation/oidc/register") {
                    contentType(ContentType.Application.Json)
                    setBody("""{"client_name":"Guest Fed RP","redirect_uris":["$RP_REDIRECT"]}""")
                }
            response.status shouldBe HttpStatusCode.Created
            val dto = FLOW_JSON.decodeFromString(OidcDynamicClientRegistrationResponse.serializer(), response.bodyAsText())
            createdClientIds += dto.client_id
            return dto
        }

        fun rpAuthorizePath(
            clientId: String,
            challenge: String,
            nonce: String,
            state: String = "st-${Uuid.random()}",
        ): String {
            val url =
                OidcGuestAuthorizeRequest.buildAuthorizeUrl(
                    authorizationEndpoint = "http://localhost/federation/oidc/authorize",
                    clientId = clientId,
                    redirectUri = RP_REDIRECT,
                    state = state,
                    codeChallenge = challenge,
                    nonce = nonce,
                )
            val uri = URI(url)
            return uri.rawPath + "?" + uri.rawQuery
        }

        fun authorizePath(
            clientId: String,
            challenge: String,
            scope: String,
            nonce: String? = "n-1",
            resource: String? = null,
        ): String =
            buildString {
                append("/federation/oidc/authorize?response_type=code&state=st1&code_challenge_method=S256")
                append("&client_id=").append(URLEncoder.encode(clientId, "UTF-8"))
                append("&redirect_uri=").append(URLEncoder.encode(RP_REDIRECT, "UTF-8"))
                append("&code_challenge=").append(challenge)
                append("&scope=").append(URLEncoder.encode(scope, "UTF-8"))
                if (nonce != null) append("&nonce=").append(nonce)
                if (resource != null) append("&resource=").append(URLEncoder.encode(resource, "UTF-8"))
            }

        suspend fun postConsent(
            client: HttpClient,
            session: String,
            clientId: String,
            challenge: String,
            scope: String,
            nonce: String? = "n-1",
            state: String = "st1",
            extra: Map<String, String> = emptyMap(),
        ): HttpResponse =
            client.post("/federation/oidc/authorize/consent") {
                header(HttpHeaders.Cookie, "lapis_session=$session")
                contentType(ContentType.Application.FormUrlEncoded)
                setBody(
                    Parameters
                        .build {
                            append("decision", "allow")
                            append("client_id", clientId)
                            append("redirect_uri", RP_REDIRECT)
                            append("scope", scope)
                            append("state", state)
                            append("code_challenge", challenge)
                            if (nonce != null) append("nonce", nonce)
                            extra.forEach { (k, v) -> append(k, v) }
                        }.formUrlEncode(),
                )
            }

        val verifier = "guest-fed-verifier-padding-padding-padding-1234567890"
        val challenge = OidcPkce.codeChallengeS256(verifier)

        // ── (b) + (c): RP request against the Issuer, then all the way to a GUEST account ──────────

        MODES.forEach { mode ->
            test("[${mode.label}] RP-built authorize request is accepted; consent -> token -> verified ID token -> GUEST member") {
                testApplication {
                    val noRedirect = createClient { followRedirects = false }
                    application { module(aiConfig = AiConfig.load { null }, mcpConfig = mode.config()) }
                    val rp = registerRp(client)
                    val session = createTestMember()
                    val nonce = "nonce-${Uuid.random()}"
                    val path = rpAuthorizePath(rp.client_id, challenge, nonce)

                    // Gegenprobe: the OLD request (every scope, MCP included) is what failed with HTTP 400.
                    val oldPath = authorizePath(rp.client_id, challenge, OidcScopes.ALL.joinToString(" "), nonce)
                    noRedirect
                        .get(oldPath) { header(HttpHeaders.Cookie, "lapis_session=$session") }
                        .status shouldBe HttpStatusCode.BadRequest

                    // Without a session: bounce to the login page with a usable returnTo.
                    val anonymous = noRedirect.get(path)
                    anonymous.status shouldBe HttpStatusCode.Found
                    val location = requireNotNull(anonymous.headers[HttpHeaders.Location])
                    location.startsWith("/app#/login?returnTo=") shouldBe true
                    val returnTo = URLDecoder.decode(location.substringAfter("returnTo="), "UTF-8")
                    (safeReturnTo(returnTo) != null) shouldBe true

                    // With a session: consent page.
                    val consentPage = noRedirect.get(path) { header(HttpHeaders.Cookie, "lapis_session=$session") }
                    consentPage.status shouldBe HttpStatusCode.OK
                    val html = consentPage.bodyAsText()
                    html shouldContain "action=\"/federation/oidc/authorize/consent\""
                    html shouldNotContain "mcp"
                    html shouldNotContain "MCP"

                    // Allow -> code.
                    val scope = OidcScopes.FEDERATION.joinToString(" ")
                    val consent = postConsent(noRedirect, session, rp.client_id, challenge, scope, nonce, state = "st-flow")
                    consent.status shouldBe HttpStatusCode.Found
                    val redirect = requireNotNull(consent.headers[HttpHeaders.Location])
                    redirect.startsWith("$RP_REDIRECT?code=") shouldBe true
                    redirect shouldContain "state=st-flow"
                    val code = requireNotNull(Regex("code=([^&]+)").find(redirect)).groupValues[1]

                    val tokenResponse =
                        client.post("/federation/oidc/token") {
                            contentType(ContentType.Application.FormUrlEncoded)
                            setBody(
                                Parameters
                                    .build {
                                        append("grant_type", "authorization_code")
                                        append("code", code)
                                        append("redirect_uri", RP_REDIRECT)
                                        append("client_id", rp.client_id)
                                        append("client_secret", rp.client_secret!!)
                                        append("code_verifier", verifier)
                                    }.formUrlEncode(),
                            )
                        }
                    tokenResponse.status shouldBe HttpStatusCode.OK
                    val token = FLOW_JSON.decodeFromString(OidcTokenResponseDto.serializer(), tokenResponse.bodyAsText())
                    token.scope.split(" ").toSet() shouldBe OidcScopes.FEDERATION.toSet()

                    val jwks = client.get("/federation/oidc/jwks").bodyAsText()
                    val kid = requireNotNull(OidcJwt.extractUnverifiedKid(token.id_token))
                    val pem = requireNotNull(OidcJwks.findRsaPublicKeyPem(jwksJson = jwks, kid = kid))
                    val verification =
                        OidcJwt.verifyIdToken(
                            compact = token.id_token,
                            publicKeyPem = pem,
                            expectedIssuer = FederationConfig.publicBaseUrl,
                            expectedAudience = rp.client_id,
                            expectedNonce = nonce,
                        )
                    verification.shouldBeInstanceOf<OidcJwt.VerificationResult.Valid>()
                    val sub = requireNotNull(verification.claims.subject)

                    // RP-side tail: the exact functions the callback uses.
                    val granted = OidcGuestAuthorizeRequest.grantedScope(token.scope)
                    val guestId =
                        OidcGuestMemberStore.resolveOrCreateGuestMember(
                            claims =
                                OidcGuestClaims(
                                    issuer = GUEST_HOME_ISSUER,
                                    subject = "$sub-${Uuid.random()}",
                                    name = "Gast",
                                    picture = null,
                                    preferredUsername = null,
                                    homeserverUrl = GUEST_HOME_ISSUER,
                                    membershipStatus = null,
                                ),
                            grantedScope = granted,
                        )
                    createdMemberIds += guestId
                    transaction {
                        MemberTable.selectAll().where { MemberTable.id eq guestId }.single()[MemberTable.status] shouldBe MemberStatus.GUEST
                        val profile = OidcGuestProfileTable.selectAll().where { OidcGuestProfileTable.memberId eq guestId }.single()
                        profile[OidcGuestProfileTable.grantedScope] shouldNotContain "mcp:"
                    }
                }
            }
        }

        // ── (d) negative tests ────────────────────────────────────────────────────────────────────

        test("GET /authorize: mixed MCP + federation scopes is 400 with MCP on (shape) and off (not enabled)") {
            val mixed = "openid mcp:member_read"
            MODES.filter { it.enabled }.forEach { mode ->
                testApplication {
                    application { module(aiConfig = AiConfig.load { null }, mcpConfig = mode.config()) }
                    val rp = registerRp(client)
                    val session = createTestMember()
                    val r =
                        client.get(authorizePath(rp.client_id, challenge, mixed, resource = MCP_RESOURCE_URL)) {
                            header(HttpHeaders.Cookie, "lapis_session=$session")
                        }
                    r.status shouldBe HttpStatusCode.BadRequest
                    r.bodyAsText() shouldContain "MCP scope requests may only combine"
                }
            }
            MODES.filter { !it.enabled }.forEach { mode ->
                testApplication {
                    application { module(aiConfig = AiConfig.load { null }, mcpConfig = mode.config()) }
                    val rp = registerRp(client)
                    val session = createTestMember()
                    listOf(mixed, "mcp:member_read").forEach { scope ->
                        val r =
                            client.get(authorizePath(rp.client_id, challenge, scope, resource = MCP_RESOURCE_URL)) {
                                header(HttpHeaders.Cookie, "lapis_session=$session")
                            }
                        r.status shouldBe HttpStatusCode.BadRequest
                        r.bodyAsText() shouldContain "MCP access is not enabled"
                    }
                }
            }
        }

        test("GET /authorize rejects an unknown scope") {
            testApplication {
                application { module(aiConfig = AiConfig.load { null }, mcpConfig = MODES[0].config()) }
                val rp = registerRp(client)
                val session = createTestMember()
                val r =
                    client.get(
                        authorizePath(rp.client_id, challenge, "openid foo:bar"),
                    ) { header(HttpHeaders.Cookie, "lapis_session=$session") }
                r.status shouldBe HttpStatusCode.BadRequest
                r.bodyAsText() shouldContain "Missing or unknown scope"
            }
        }

        test("POST /authorize/consent rejects an unknown scope, a mixed MCP set, and mcp:member_write while write is off") {
            testApplication {
                application { module(aiConfig = AiConfig.load { null }, mcpConfig = MODES[2].config()) }
                val rp = registerRp(client)
                val session = createTestMember()

                val unknown = postConsent(client, session, rp.client_id, challenge, "openid foo:bar")
                unknown.status shouldBe HttpStatusCode.BadRequest
                unknown.bodyAsText() shouldContain "Missing or unknown scope"

                val mixed =
                    postConsent(
                        client,
                        session,
                        rp.client_id,
                        challenge,
                        "openid mcp:member_read",
                        extra = mapOf("resource" to MCP_RESOURCE_URL, "connection_label" to "x"),
                    )
                mixed.status shouldBe HttpStatusCode.BadRequest
                mixed.bodyAsText() shouldContain "MCP scope requests may only combine"

                val write =
                    postConsent(
                        client,
                        session,
                        rp.client_id,
                        challenge,
                        "mcp:member_read mcp:member_write",
                        nonce = null,
                        extra = mapOf("resource" to MCP_RESOURCE_URL, "connection_label" to "x"),
                    )
                write.status shouldBe HttpStatusCode.BadRequest
                write.bodyAsText() shouldContain "mcp:member_write is not enabled"
            }
        }

        // ── B1 / B2 ───────────────────────────────────────────────────────────────────────────────

        fun insertRpFixture(): Pair<String, String> {
            val issuer = "https://localhost-fixture-${Uuid.random()}.example"
            auditIssuers += issuer
            val registrationId = Uuid.random()
            val state = SessionTokens.newRawToken()
            val now = Clock.System.now().toLocalDateTime(TimeZone.UTC)
            val later = (Clock.System.now() + 10.minutes).toLocalDateTime(TimeZone.UTC)
            createdRegistrationIds += registrationId
            transaction {
                OidcHomeServerRegistrationTable.insert {
                    it[id] = registrationId
                    it[issuerUrl] = issuer
                    it[authorizationEndpoint] = "$issuer/authorize"
                    it[tokenEndpoint] = "https://localhost/token"
                    it[jwksUri] = "https://localhost/jwks"
                    it[clientId] = "fixture-client"
                    it[clientSecret] = "fixture-secret"
                    it[registeredAt] = now
                }
                OidcRpLoginAttemptTable.insert {
                    it[id] = Uuid.random()
                    it[stateHash] = SessionTokens.hash(state)
                    it[homeServerRegistrationId] = registrationId
                    it[codeVerifier] = "fixture-verifier"
                    it[nonce] = "fixture-nonce"
                    it[redirectUri] = RP_REDIRECT
                    it[createdAt] = now
                    it[expiresAt] = later
                    it[consumedAt] = null
                }
            }
            return issuer to state
        }

        fun bindingMismatchCount(issuer: String): Long =
            transaction {
                OidcGuestLoginEventTable
                    .selectAll()
                    .where { OidcGuestLoginEventTable.remoteParty eq issuer }
                    .count { it[OidcGuestLoginEventTable.reason] == "BROWSER_BINDING_MISMATCH" }
                    .toLong()
            }

        test("RP callback without the binding cookie is 401 BROWSER_BINDING_MISMATCH, the attempt is burned, the cookie is expired") {
            testApplication {
                application { module(aiConfig = AiConfig.load { null }, mcpConfig = MODES[0].config()) }
                val (issuer, state) = insertRpFixture()
                val r = client.get("/federation/oidc/rp/callback?code=abc&state=$state")
                r.status shouldBe HttpStatusCode.Unauthorized
                bindingMismatchCount(issuer) shouldBe 1L
                r.headers.getAll(HttpHeaders.SetCookie).orEmpty().any {
                    it.startsWith(OIDC_RP_BINDING_COOKIE_NAME) &&
                        it.contains("Max-Age=0")
                } shouldBe
                    true
                val consumed =
                    transaction {
                        OidcRpLoginAttemptTable
                            .selectAll()
                            .where { OidcRpLoginAttemptTable.stateHash eq SessionTokens.hash(state) }
                            .single()[OidcRpLoginAttemptTable.consumedAt]
                    }
                (consumed != null) shouldBe true
            }
        }

        test("RP callback with a WRONG binding cookie is 401") {
            testApplication {
                application { module(aiConfig = AiConfig.load { null }, mcpConfig = MODES[0].config()) }
                val (issuer, state) = insertRpFixture()
                val r =
                    client.get("/federation/oidc/rp/callback?code=abc&state=$state") {
                        header(HttpHeaders.Cookie, "$OIDC_RP_BINDING_COOKIE_NAME=not-the-state")
                    }
                r.status shouldBe HttpStatusCode.Unauthorized
                bindingMismatchCount(issuer) shouldBe 1L
            }
        }

        test("RP callback with the RIGHT binding cookie passes the binding (fails later at the SSRF-guarded token exchange: 502)") {
            testApplication {
                application { module(aiConfig = AiConfig.load { null }, mcpConfig = MODES[0].config()) }
                val (issuer, state) = insertRpFixture()
                val r =
                    client.get("/federation/oidc/rp/callback?code=abc&state=$state") {
                        header(HttpHeaders.Cookie, "$OIDC_RP_BINDING_COOKIE_NAME=$state")
                    }
                r.status shouldBe HttpStatusCode.BadGateway
                bindingMismatchCount(issuer) shouldBe 0L
                r.headers.getAll(HttpHeaders.SetCookie).orEmpty().any {
                    it.startsWith(OIDC_RP_BINDING_COOKIE_NAME) &&
                        it.contains("Max-Age=0")
                } shouldBe
                    true
            }
        }

        test("POST /rp/login is rejected cross-origin (Sec-Fetch-Site and Origin), same as the other state-changing form posts") {
            testApplication {
                application { module(aiConfig = AiConfig.load { null }, mcpConfig = MODES[0].config()) }
                val body = Parameters.build { append("home_server", "https://localhost") }.formUrlEncode()
                client
                    .post("/federation/oidc/rp/login") {
                        header("Sec-Fetch-Site", "cross-site")
                        contentType(ContentType.Application.FormUrlEncoded)
                        setBody(body)
                    }.status shouldBe HttpStatusCode.Forbidden
                client
                    .post("/federation/oidc/rp/login") {
                        header(HttpHeaders.Origin, "https://evil.example")
                        contentType(ContentType.Application.FormUrlEncoded)
                        setBody(body)
                    }.status shouldBe HttpStatusCode.Forbidden
            }
        }
    })
