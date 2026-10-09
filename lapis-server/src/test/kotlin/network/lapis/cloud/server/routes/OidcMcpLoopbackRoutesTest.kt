package network.lapis.cloud.server.routes

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
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
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import network.lapis.cloud.server.ai.config.AiConfig
import network.lapis.cloud.server.db.DatabaseConfig
import network.lapis.cloud.server.db.generated.AccountTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.server.db.generated.OidcAuthorizationCodeTable
import network.lapis.cloud.server.db.generated.OidcClientRedirectUriTable
import network.lapis.cloud.server.db.generated.OidcClientRegistrationTable
import network.lapis.cloud.server.db.generated.OidcIssuedTokenTable
import network.lapis.cloud.server.db.generated.SessionTable
import network.lapis.cloud.server.federation.OidcPkce
import network.lapis.cloud.server.federation.OidcTokenResponseDto
import network.lapis.cloud.server.mcp.config.McpConfig
import network.lapis.cloud.server.module
import network.lapis.cloud.server.security.PasswordHasher
import network.lapis.cloud.server.security.SessionStore
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.MemberStatus
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import java.util.Base64
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours
import kotlin.uuid.Uuid

private val JSON = Json { ignoreUnknownKeys = true }
private const val MCP_RESOURCE = "http://localhost:8080/mcp"
private const val READ = "mcp:member_read"

private fun mcpOn(): McpConfig = McpConfig.load { if (it == McpConfig.ENV_ENABLED) "true" else null }

/**
 * V1.9.86 -- loopback `localhost` redirect URIs for public PKCE clients (Claude Code), optional
 * `nonce` and default scope for MCP grants, PKCE shape validation, and RFC 8707 `invalid_target`
 * at `/token`. All requests go through the real, fully-wired module on H2; the "loopback client" is
 * simulated (no sockets) -- ports inside redirect URI strings are plain text.
 */
class OidcMcpLoopbackRoutesTest :
    FunSpec({
        val createdMemberIds = mutableListOf<Uuid>()
        val createdClientIds = mutableListOf<String>()

        beforeSpec { DatabaseConfig.connect() }

        afterSpec {
            transaction {
                OidcAuthorizationCodeTable.deleteWhere { OidcAuthorizationCodeTable.memberId inList createdMemberIds }
                OidcIssuedTokenTable.deleteWhere { OidcIssuedTokenTable.memberId inList createdMemberIds }
                SessionTable.deleteWhere { SessionTable.memberId inList createdMemberIds }
                AccountTable.deleteWhere { AccountTable.memberId inList createdMemberIds }
                MemberTable.deleteWhere { MemberTable.id inList createdMemberIds }
                val registrationIds =
                    OidcClientRegistrationTable
                        .selectAll()
                        .where { OidcClientRegistrationTable.clientId inList createdClientIds }
                        .map { it[OidcClientRegistrationTable.id] }
                OidcClientRedirectUriTable.deleteWhere { OidcClientRedirectUriTable.clientRegistrationId inList registrationIds }
                OidcClientRegistrationTable.deleteWhere { OidcClientRegistrationTable.clientId inList createdClientIds }
            }
        }

        fun createTestMember(): String {
            val id = Uuid.random()
            transaction {
                MemberTable.insert {
                    it[MemberTable.id] = id
                    it[displayName] = "Loopback Testmitglied"
                    it[email] = "loopback-${Uuid.random()}@example.org"
                    it[status] = MemberStatus.ACTIVE
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

        fun dcrBody(
            redirectUris: List<String>,
            authMethod: String?,
            name: String = "Claude Code (lapis-test)",
        ): String =
            JsonObject(
                buildMap {
                    put("client_name", JsonPrimitive(name))
                    put("redirect_uris", JsonArray(redirectUris.map { JsonPrimitive(it) }))
                    put("grant_types", JsonArray(listOf(JsonPrimitive("authorization_code"), JsonPrimitive("refresh_token"))))
                    put("response_types", JsonArray(listOf(JsonPrimitive("code"))))
                    put("scope", JsonPrimitive(READ))
                    put("client_uri", JsonPrimitive("https://claude.ai"))
                    if (authMethod != null) put("token_endpoint_auth_method", JsonPrimitive(authMethod))
                },
            ).toString()

        suspend fun register(
            client: HttpClient,
            redirectUri: String,
            authMethod: String? = "none",
        ): String {
            val response =
                client.post("/federation/oidc/register") {
                    contentType(ContentType.Application.Json)
                    setBody(dcrBody(listOf(redirectUri), authMethod))
                }
            response.status shouldBe HttpStatusCode.Created
            val body = response.bodyAsText()
            val clientId = Regex("\"client_id\":\"([^\"]+)\"").find(body)!!.groupValues[1]
            createdClientIds += clientId
            return clientId
        }

        fun authorizeUrl(
            clientId: String,
            redirectUri: String,
            challenge: String,
            scope: String? = READ,
            resource: String? = MCP_RESOURCE,
            challengeMethod: String? = "S256",
            includeNonce: Boolean = false,
        ): String =
            buildString {
                append("/federation/oidc/authorize?response_type=code&state=st1")
                append("&client_id=").append(clientId)
                append("&redirect_uri=").append(java.net.URLEncoder.encode(redirectUri, "UTF-8"))
                append("&code_challenge=").append(challenge)
                if (challengeMethod != null) append("&code_challenge_method=").append(challengeMethod)
                if (scope != null) append("&scope=").append(java.net.URLEncoder.encode(scope, "UTF-8"))
                if (resource != null) append("&resource=").append(java.net.URLEncoder.encode(resource, "UTF-8"))
                if (includeNonce) append("&nonce=n1")
            }

        suspend fun consent(
            client: HttpClient,
            session: String,
            clientId: String,
            redirectUri: String,
            challenge: String,
            scope: String = READ,
            nonce: String? = null,
        ): HttpResponse =
            client.post("/federation/oidc/authorize/consent") {
                header(HttpHeaders.Cookie, "lapis_session=$session")
                contentType(ContentType.Application.FormUrlEncoded)
                setBody(
                    Parameters
                        .build {
                            append("decision", "allow")
                            append("client_id", clientId)
                            append("redirect_uri", redirectUri)
                            append("scope", scope)
                            append("state", "st1")
                            append("code_challenge", challenge)
                            if (nonce != null) append("nonce", nonce)
                            append("resource", MCP_RESOURCE)
                            append("connection_label", "Loopback Test")
                        }.formUrlEncode(),
                )
            }

        fun codeOf(response: HttpResponse): String {
            val location = requireNotNull(response.headers[HttpHeaders.Location])
            return requireNotNull(Regex("code=([^&]+)").find(location)) { "no code in $location" }.groupValues[1]
        }

        suspend fun token(
            client: HttpClient,
            clientId: String,
            code: String,
            verifier: String?,
            redirectUri: String,
            resource: String? = null,
        ): HttpResponse =
            client.post("/federation/oidc/token") {
                contentType(ContentType.Application.FormUrlEncoded)
                setBody(
                    Parameters
                        .build {
                            append("grant_type", "authorization_code")
                            append("code", code)
                            append("redirect_uri", redirectUri)
                            append("client_id", clientId)
                            if (verifier != null) append("code_verifier", verifier)
                            if (resource != null) append("resource", resource)
                        }.formUrlEncode(),
                )
            }

        suspend fun refresh(
            client: HttpClient,
            clientId: String,
            refreshToken: String,
            resource: String? = null,
        ): HttpResponse =
            client.post("/federation/oidc/token") {
                contentType(ContentType.Application.FormUrlEncoded)
                setBody(
                    Parameters
                        .build {
                            append("grant_type", "refresh_token")
                            append("refresh_token", refreshToken)
                            append("client_id", clientId)
                            if (resource != null) append("resource", resource)
                        }.formUrlEncode(),
                )
            }

        val verifier = "loopback-verifier-padding-padding-padding-1234567890"
        val challenge = OidcPkce.codeChallengeS256(verifier)

        /** Full simulated Claude Code grant: returns (clientId, code) for a port-deviating redirect. */
        suspend fun grantCode(
            client: HttpClient,
            noRedirect: HttpClient,
            registeredUri: String = "http://localhost:53682/callback",
            presentedUri: String = "http://localhost:61111/callback",
        ): Pair<String, String> {
            val clientId = register(client, registeredUri)
            val session = createTestMember()
            val response = consent(noRedirect, session, clientId, presentedUri, challenge)
            response.status shouldBe HttpStatusCode.Found
            return clientId to codeOf(response)
        }

        // ── DCR ──────────────────────────────────────────────────────────────

        test("DCR: Claude-Code-shaped public client with http://localhost redirect is accepted, no client_secret") {
            testApplication {
                application { module(aiConfig = AiConfig.load { null }, mcpConfig = mcpOn()) }
                val response =
                    client.post("/federation/oidc/register") {
                        contentType(ContentType.Application.Json)
                        setBody(dcrBody(listOf("http://localhost:53682/callback"), "none"))
                    }
                response.status shouldBe HttpStatusCode.Created
                val body = response.bodyAsText()
                // A public client gets NO client_secret key at all (never "client_secret":null): the MCP TypeScript SDK of Claude Code
                // rejects a null here and aborts the sign-in after the registration (V1.9.88).
                body shouldNotContain "client_secret\""
                body shouldNotContain ":null"
                body shouldContain "\"client_secret_expires_at\":0"
                body shouldContain "\"token_endpoint_auth_method\":\"none\""
                createdClientIds += Regex("\"client_id\":\"([^\"]+)\"").find(body)!!.groupValues[1]
            }
        }

        test(
            "DCR: localhost redirect for a client_secret_post client, or with token_endpoint_auth_method omitted, gets the dedicated message",
        ) {
            testApplication {
                application { module(aiConfig = AiConfig.load { null }, mcpConfig = mcpOn()) }
                listOf<String?>("client_secret_post", null).forEach { method ->
                    val response =
                        client.post("/federation/oidc/register") {
                            contentType(ContentType.Application.Json)
                            setBody(dcrBody(listOf("http://localhost:53682/callback-marker"), method))
                        }
                    response.status shouldBe HttpStatusCode.BadRequest
                    val text = response.bodyAsText()
                    text shouldBe DCR_ERR_LOOPBACK_REQUIRES_PUBLIC
                    text shouldNotContain "callback-marker"
                }
            }
        }

        val tamperUris =
            listOf(
                "http://LOCALHOST:1/cb-marker",
                "http://localhost.:1234/cb-marker",
                "http://evil.localhost:1/cb-marker",
                "http://localhost.evil.com/cb-marker",
                "http://localhost@evil.com/cb-marker",
                "http://user@localhost:1/cb-marker",
                "http://localhost:1/cb-marker#x",
                "http://localhost:1/cb-marker#",
                "http://localhost:0/cb-marker",
                "http://localhost:65536/cb-marker",
                "http://localhost:1/c b-marker",
                "http://example.com/cb-marker",
                "javascript:alert(1)//marker",
            )
        tamperUris.chunked(4).forEachIndexed { index, chunk ->
            test("DCR: tampered loopback redirect URIs are rejected with the generic message and never echoed (batch $index)") {
                testApplication {
                    application { module(aiConfig = AiConfig.load { null }, mcpConfig = mcpOn()) }
                    chunk.forEach { uri ->
                        val response =
                            client.post("/federation/oidc/register") {
                                contentType(ContentType.Application.Json)
                                setBody(dcrBody(listOf(uri), "none"))
                            }
                        response.status shouldBe HttpStatusCode.BadRequest
                        val text = response.bodyAsText()
                        text shouldBe DCR_ERR_REDIRECT_URIS
                        text shouldNotContain "marker"
                    }
                }
            }
        }

        test("DCR: http backchannel_logout_uri with otherwise valid redirects gets exactly the backchannel message") {
            testApplication {
                application { module(aiConfig = AiConfig.load { null }, mcpConfig = mcpOn()) }
                val response =
                    client.post("/federation/oidc/register") {
                        contentType(ContentType.Application.Json)
                        setBody(
                            """{"client_name":"X","redirect_uris":["http://localhost:1/cb"],"token_endpoint_auth_method":"none",""" +
                                """"backchannel_logout_uri":"http://rp.example/bc-marker"}""",
                        )
                    }
                response.status shouldBe HttpStatusCode.BadRequest
                response.bodyAsText() shouldBe DCR_ERR_BACKCHANNEL
            }
        }

        test("DCR: a public localhost client with MCP switched off keeps the existing 'none is not enabled' rejection") {
            testApplication {
                application { module() }
                val response =
                    client.post("/federation/oidc/register") {
                        contentType(ContentType.Application.Json)
                        setBody(dcrBody(listOf("http://localhost:53682/callback"), "none"))
                    }
                response.status shouldBe HttpStatusCode.BadRequest
                response.bodyAsText() shouldContain "not enabled"
            }
        }

        // ── /authorize ───────────────────────────────────────────────────────

        test("/authorize: registered localhost client with a different presented port reaches consent; no nonce, scope from request") {
            testApplication {
                application { module(aiConfig = AiConfig.load { null }, mcpConfig = mcpOn()) }
                val clientId = register(client, "http://localhost:53682/callback")
                val session = createTestMember()
                val response =
                    client.get(authorizeUrl(clientId, "http://localhost:61111/callback", challenge)) {
                        header(HttpHeaders.Cookie, "lapis_session=$session")
                    }
                response.status shouldBe HttpStatusCode.OK
                val html = response.bodyAsText()
                html shouldContain "name=\"scope\" value=\"$READ\""
                html shouldNotContain "name=\"nonce\""
            }
        }

        test("/authorize: localhost vs 127.0.0.1 are not interchangeable in either direction") {
            testApplication {
                application { module(aiConfig = AiConfig.load { null }, mcpConfig = mcpOn()) }
                val loopbackId = register(client, "http://127.0.0.1:1111/callback")
                val localhostId = register(client, "http://localhost:1111/callback")
                val session = createTestMember()
                val a =
                    client.get(authorizeUrl(loopbackId, "http://localhost:1111/callback", challenge)) {
                        header(HttpHeaders.Cookie, "lapis_session=$session")
                    }
                a.status shouldBe HttpStatusCode.BadRequest
                a.bodyAsText() shouldContain "redirect_uri is not registered"
                val b =
                    client.get(authorizeUrl(localhostId, "http://127.0.0.1:1111/callback", challenge)) {
                        header(HttpHeaders.Cookie, "lapis_session=$session")
                    }
                b.status shouldBe HttpStatusCode.BadRequest
            }
        }

        test("/authorize: a confidential client never gets port flexibility") {
            testApplication {
                application { module(aiConfig = AiConfig.load { null }, mcpConfig = mcpOn()) }
                val clientId = register(client, "https://rp.example/callback", authMethod = "client_secret_post")
                val session = createTestMember()
                val response =
                    client.get(authorizeUrl(clientId, "https://rp.example:8443/callback", challenge)) {
                        header(HttpHeaders.Cookie, "lapis_session=$session")
                    }
                response.status shouldBe HttpStatusCode.BadRequest
            }
        }

        test("/authorize: PKCE is enforced -- missing/plain/absent method and malformed challenge are 400, never 500") {
            testApplication {
                application { module(aiConfig = AiConfig.load { null }, mcpConfig = mcpOn()) }
                val clientId = register(client, "http://localhost:53682/callback")
                val session = createTestMember()
                val redirect = "http://localhost:53682/callback"

                suspend fun status(url: String) = client.get(url) { header(HttpHeaders.Cookie, "lapis_session=$session") }.status
                status(authorizeUrl(clientId, redirect, challenge, challengeMethod = "plain")) shouldBe HttpStatusCode.BadRequest
                status(authorizeUrl(clientId, redirect, challenge, challengeMethod = null)) shouldBe HttpStatusCode.BadRequest
                status(authorizeUrl(clientId, redirect, "a".repeat(129))) shouldBe HttpStatusCode.BadRequest
                status(authorizeUrl(clientId, redirect, "short")) shouldBe HttpStatusCode.BadRequest
                status(authorizeUrl(clientId, redirect, challenge.dropLast(1) + "!")) shouldBe HttpStatusCode.BadRequest
                val noChallenge =
                    authorizeUrl(clientId, redirect, challenge).replace("&code_challenge=$challenge", "")
                status(noChallenge) shouldBe HttpStatusCode.BadRequest
            }
        }

        test("/authorize: MCP request without scope defaults to read-only when resource matches; otherwise 400") {
            testApplication {
                application { module(aiConfig = AiConfig.load { null }, mcpConfig = mcpOn()) }
                val clientId = register(client, "http://localhost:53682/callback")
                val session = createTestMember()
                val redirect = "http://localhost:53682/callback"

                suspend fun get(url: String) = client.get(url) { header(HttpHeaders.Cookie, "lapis_session=$session") }

                val ok = get(authorizeUrl(clientId, redirect, challenge, scope = null))
                ok.status shouldBe HttpStatusCode.OK
                ok.bodyAsText() shouldContain "name=\"scope\" value=\"$READ\""
                get(authorizeUrl(clientId, redirect, challenge, scope = null, resource = null)).status shouldBe HttpStatusCode.BadRequest
                get(
                    authorizeUrl(clientId, redirect, challenge, scope = null, resource = "https://other.example/mcp"),
                ).status shouldBe HttpStatusCode.BadRequest
                // explicit scope with a foreign resource stays rejected too
                get(authorizeUrl(clientId, redirect, challenge, resource = "https://other.example/mcp")).status shouldBe
                    HttpStatusCode.BadRequest
            }
        }

        test("/authorize/consent: port deviation redirects to exactly the presented URI with code and state; nonce is stored as null") {
            testApplication {
                val noRedirect = createClient { followRedirects = false }
                application { module(aiConfig = AiConfig.load { null }, mcpConfig = mcpOn()) }
                val clientId = register(client, "http://localhost:53682/callback")
                val session = createTestMember()
                val response = consent(noRedirect, session, clientId, "http://localhost:61111/callback", challenge, nonce = "")
                response.status shouldBe HttpStatusCode.Found
                val location = requireNotNull(response.headers[HttpHeaders.Location])
                location.startsWith("http://localhost:61111/callback?") shouldBe true
                location shouldContain "state=st1"
                val stored =
                    transaction {
                        OidcAuthorizationCodeTable
                            .selectAll()
                            .where { OidcAuthorizationCodeTable.memberId inList createdMemberIds }
                            .map { it[OidcAuthorizationCodeTable.nonce] }
                    }
                stored.all { it == null } shouldBe true
            }
        }

        // ── /token ───────────────────────────────────────────────────────────

        test("/token: happy path with matching resource; ID token carries no nonce claim; code is single-use") {
            testApplication {
                val noRedirect = createClient { followRedirects = false }
                application { module(aiConfig = AiConfig.load { null }, mcpConfig = mcpOn()) }
                val (clientId, code) = grantCode(client, noRedirect)
                val redirect = "http://localhost:61111/callback"
                val ok = token(client, clientId, code, verifier, redirect, resource = MCP_RESOURCE)
                ok.status shouldBe HttpStatusCode.OK
                val dto = JSON.decodeFromString(OidcTokenResponseDto.serializer(), ok.bodyAsText())
                val payload = String(Base64.getUrlDecoder().decode(dto.id_token.split(".")[1]), Charsets.UTF_8)
                payload shouldNotContain "\"nonce\""
                val again = token(client, clientId, code, verifier, redirect, resource = MCP_RESOURCE)
                again.status shouldBe HttpStatusCode.BadRequest
                again.bodyAsText() shouldContain "invalid_grant"
            }
        }

        test("/token: wrong verifier -> invalid_grant, missing verifier -> invalid_request, different redirect port -> invalid_grant") {
            testApplication {
                val noRedirect = createClient { followRedirects = false }
                application { module(aiConfig = AiConfig.load { null }, mcpConfig = mcpOn()) }
                val clientId = register(client, "http://localhost:53682/callback")
                val session = createTestMember()
                val redirect = "http://localhost:61111/callback"

                val c1 = codeOf(consent(noRedirect, session, clientId, redirect, challenge))
                val wrong = token(client, clientId, c1, "wrong-verifier-padding-padding-padding-1234567", redirect)
                wrong.status shouldBe HttpStatusCode.BadRequest
                wrong.bodyAsText() shouldContain "invalid_grant"

                val c2 = codeOf(consent(noRedirect, session, clientId, redirect, challenge))
                val missing = token(client, clientId, c2, null, redirect)
                missing.status shouldBe HttpStatusCode.BadRequest
                missing.bodyAsText() shouldContain "invalid_request"

                val c3 = codeOf(consent(noRedirect, session, clientId, redirect, challenge))
                val otherPort = token(client, clientId, c3, verifier, "http://localhost:61112/callback")
                otherPort.status shouldBe HttpStatusCode.BadRequest
                otherPort.bodyAsText() shouldContain "invalid_grant"
            }
        }

        test("/token: a resource differing from the grant's binding -> invalid_target (code grant burns the code)") {
            testApplication {
                val noRedirect = createClient { followRedirects = false }
                application { module(aiConfig = AiConfig.load { null }, mcpConfig = mcpOn()) }
                val (clientId, code) = grantCode(client, noRedirect)
                val redirect = "http://localhost:61111/callback"
                val bad = token(client, clientId, code, verifier, redirect, resource = "https://other.example/mcp")
                bad.status shouldBe HttpStatusCode.BadRequest
                bad.bodyAsText() shouldContain "invalid_target"
                token(client, clientId, code, verifier, redirect, resource = MCP_RESOURCE).bodyAsText() shouldContain "invalid_grant"
            }
        }

        test("/token refresh: matching resource rotates, mismatching resource -> invalid_target without rotating") {
            testApplication {
                val noRedirect = createClient { followRedirects = false }
                application { module(aiConfig = AiConfig.load { null }, mcpConfig = mcpOn()) }
                val (clientId, code) = grantCode(client, noRedirect)
                val redirect = "http://localhost:61111/callback"
                val dto =
                    JSON.decodeFromString(
                        OidcTokenResponseDto.serializer(),
                        token(client, clientId, code, verifier, redirect).bodyAsText(),
                    )
                val rt = requireNotNull(dto.refresh_token)
                val bad = refresh(client, clientId, rt, resource = "https://other.example/mcp")
                bad.status shouldBe HttpStatusCode.BadRequest
                bad.bodyAsText() shouldContain "invalid_target"
                // not rotated: the same refresh token still works with the right resource
                refresh(client, clientId, rt, resource = MCP_RESOURCE).status shouldBe HttpStatusCode.OK
            }
        }

        test("/token: an expired code is rejected with invalid_grant") {
            testApplication {
                val noRedirect = createClient { followRedirects = false }
                application { module(aiConfig = AiConfig.load { null }, mcpConfig = mcpOn()) }
                val (clientId, code) = grantCode(client, noRedirect)
                val past =
                    Clock.System
                        .now()
                        .minus(1.hours)
                        .toLocalDateTime(TimeZone.UTC)
                transaction {
                    OidcAuthorizationCodeTable.update({ OidcAuthorizationCodeTable.memberId inList createdMemberIds }) {
                        it[expiresAt] = past
                    }
                }
                val response = token(client, clientId, code, verifier, "http://localhost:61111/callback")
                response.status shouldBe HttpStatusCode.BadRequest
                response.bodyAsText() shouldContain "invalid_grant"
            }
        }
    })
