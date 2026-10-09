package network.lapis.cloud.server.mcp

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
import kotlinx.serialization.json.Json
import network.lapis.cloud.server.ai.config.AiConfig
import network.lapis.cloud.server.db.DatabaseConfig
import network.lapis.cloud.server.db.generated.AccountTable
import network.lapis.cloud.server.db.generated.McpMemberBlockTable
import network.lapis.cloud.server.db.generated.McpToolCallAuditTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.server.db.generated.OidcAuthorizationCodeTable
import network.lapis.cloud.server.db.generated.OidcClientRedirectUriTable
import network.lapis.cloud.server.db.generated.OidcClientRegistrationTable
import network.lapis.cloud.server.db.generated.OidcIssuedTokenTable
import network.lapis.cloud.server.db.generated.SessionTable
import network.lapis.cloud.server.federation.OidcPkce
import network.lapis.cloud.server.federation.OidcTokenResponseDto
import network.lapis.cloud.server.mcp.config.McpConfig
import network.lapis.cloud.server.mcp.optin.McpMemberBlockStore
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
import java.net.URI
import java.net.URLEncoder
import kotlin.uuid.Uuid

private val JSON = Json { ignoreUnknownKeys = true }
private const val RESOURCE = "http://localhost:8080/mcp"

private fun cfg(write: Boolean): McpConfig =
    McpConfig.load {
        when (it) {
            McpConfig.ENV_ENABLED -> "true"
            McpConfig.ENV_WRITE_ENABLED -> if (write) "true" else null
            else -> null
        }
    }

/**
 * V1.9.86 -- simulates Claude Code's MCP sign-in end to end against the real module: 401 challenge
 * -> protected-resource metadata -> AS metadata -> DCR (`http://localhost:<port>`) -> `/authorize`
 * without `nonce` on a different port -> consent -> `/token` -> `/mcp`. The request shapes are
 * reconstructed from the MCP authorization spec and SDK behaviour, NOT captured from a real client.
 */
class McpClaudeCodeLoopbackFlowTest :
    FunSpec({
        val createdMemberIds = mutableListOf<Uuid>()
        val createdClientIds = mutableListOf<String>()

        beforeSpec { DatabaseConfig.connect() }

        afterSpec {
            transaction {
                McpMemberBlockTable.deleteWhere { McpMemberBlockTable.memberId inList createdMemberIds }
                McpToolCallAuditTable.deleteWhere { McpToolCallAuditTable.memberId inList createdMemberIds }
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

        fun createTestMember(): Pair<Uuid, String> {
            val id = Uuid.random()
            transaction {
                MemberTable.insert {
                    it[MemberTable.id] = id
                    it[displayName] = "Claude Code Flow Testmitglied"
                    it[email] = "cc-flow-${Uuid.random()}@example.org"
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
            return id to SessionStore.createSession(id).rawToken
        }

        suspend fun mcp(
            client: HttpClient,
            token: String?,
            body: String,
            protocolHeader: String? = null,
        ): HttpResponse =
            client.post("/mcp") {
                contentType(ContentType.Application.Json)
                if (token != null) header(HttpHeaders.Authorization, "Bearer $token")
                if (protocolHeader != null) header("MCP-Protocol-Version", protocolHeader)
                setBody(body)
            }

        test("PRM scopes_supported follows the write switch: read only by default, read+write when write is operational") {
            suspend fun scopes(write: Boolean): String {
                var text = ""
                testApplication {
                    application { module(aiConfig = AiConfig.load { null }, mcpConfig = cfg(write)) }
                    val response = client.get("/.well-known/oauth-protected-resource/mcp")
                    response.status shouldBe HttpStatusCode.OK
                    text = response.bodyAsText()
                }
                return text
            }
            val readOnly = scopes(write = false)
            readOnly shouldContain "mcp:member_read"
            readOnly shouldNotContain "mcp:member_write"
            scopes(write = true) shouldContain "mcp:member_write"
        }

        test(
            "Claude Code sign-in: 401 challenge -> metadata -> DCR(localhost) -> authorize without nonce on another port -> token -> /mcp -> refresh",
        ) {
            testApplication {
                val noRedirect = createClient { followRedirects = false }
                application { module(aiConfig = AiConfig.load { null }, mcpConfig = cfg(write = false)) }

                // 1. unauthenticated POST -> 401 with resource_metadata and the least-privilege scope
                val challenge = mcp(client, null, """{"jsonrpc":"2.0","id":1,"method":"initialize"}""")
                challenge.status shouldBe HttpStatusCode.Unauthorized
                val www = requireNotNull(challenge.headers[HttpHeaders.WWWAuthenticate])
                www shouldContain "resource_metadata=\""
                www shouldContain "scope=\"mcp:member_read\""

                // 2. protected-resource metadata
                val prm = client.get("/.well-known/oauth-protected-resource/mcp")
                prm.status shouldBe HttpStatusCode.OK
                prm.bodyAsText() shouldContain "\"resource\":\"$RESOURCE\""

                // 3. AS metadata: RFC 8414 path is absent, OIDC discovery carries S256, none and registration
                client.get("/.well-known/oauth-authorization-server").status shouldBe HttpStatusCode.NotFound
                val disco = client.get("/.well-known/openid-configuration")
                disco.status shouldBe HttpStatusCode.OK
                val discoBody = disco.bodyAsText()
                discoBody shouldContain "S256"
                discoBody shouldContain "\"none\""
                discoBody shouldContain "registration_endpoint"

                // 4. DCR as Claude Code would send it
                val dcr =
                    client.post("/federation/oidc/register") {
                        contentType(ContentType.Application.Json)
                        setBody(
                            """{"client_name":"Claude Code (lapis-pdv)","redirect_uris":["http://localhost:53682/callback"],""" +
                                """"grant_types":["authorization_code","refresh_token"],"response_types":["code"],""" +
                                """"token_endpoint_auth_method":"none"}""",
                        )
                    }
                dcr.status shouldBe HttpStatusCode.Created
                val clientId = Regex("\"client_id\":\"([^\"]+)\"").find(dcr.bodyAsText())!!.groupValues[1]
                createdClientIds += clientId

                // 5. /authorize: no nonce, scope from the challenge, S256, resource, DIFFERENT port (re-auth case)
                val verifier = "claude-code-verifier-padding-padding-padding-123456"
                val codeChallenge = OidcPkce.codeChallengeS256(verifier)
                val redirect = "http://localhost:61111/callback"
                val authorize =
                    "/federation/oidc/authorize?response_type=code&client_id=$clientId" +
                        "&redirect_uri=${URLEncoder.encode(redirect, "UTF-8")}&state=xyz" +
                        "&code_challenge=$codeChallenge&code_challenge_method=S256" +
                        "&resource=${URLEncoder.encode(RESOURCE, "UTF-8")}&scope=mcp%3Amember_read"

                // 6. no session -> login redirect; with session -> consent page
                val anon = noRedirect.get(authorize)
                anon.status shouldBe HttpStatusCode.Found
                requireNotNull(anon.headers[HttpHeaders.Location]) shouldContain "/app#/login"
                val (_, session) = createTestMember()
                val consentPage = client.get(authorize) { header(HttpHeaders.Cookie, "lapis_session=$session") }
                consentPage.status shouldBe HttpStatusCode.OK
                consentPage.bodyAsText() shouldNotContain "name=\"nonce\""

                // 7. consent POST (the fields the page renders, no nonce)
                val consent =
                    noRedirect.post("/federation/oidc/authorize/consent") {
                        header(HttpHeaders.Cookie, "lapis_session=$session")
                        contentType(ContentType.Application.FormUrlEncoded)
                        setBody(
                            Parameters
                                .build {
                                    append("decision", "allow")
                                    append("client_id", clientId)
                                    append("redirect_uri", redirect)
                                    append("scope", "mcp:member_read")
                                    append("state", "xyz")
                                    append("code_challenge", codeChallenge)
                                    append("resource", RESOURCE)
                                    append("connection_label", "Claude Code")
                                }.formUrlEncode(),
                        )
                    }
                consent.status shouldBe HttpStatusCode.Found
                val location = URI(requireNotNull(consent.headers[HttpHeaders.Location]))
                location.host shouldBe "localhost"
                location.port shouldBe 61111
                location.path shouldBe "/callback"
                location.query shouldContain "state=xyz"
                val code = Regex("code=([^&]+)").find(location.query)!!.groupValues[1]

                // 8. token
                val tokenResponse =
                    client.post("/federation/oidc/token") {
                        contentType(ContentType.Application.FormUrlEncoded)
                        setBody(
                            Parameters
                                .build {
                                    append("grant_type", "authorization_code")
                                    append("code", code)
                                    append("code_verifier", verifier)
                                    append("redirect_uri", redirect)
                                    append("client_id", clientId)
                                    append("resource", RESOURCE)
                                }.formUrlEncode(),
                        )
                    }
                tokenResponse.status shouldBe HttpStatusCode.OK
                val tokenBody = tokenResponse.bodyAsText()
                // The MCP TypeScript SDK parses the token response strictly: no key may carry null (V1.9.88), and a token response is never cached.
                tokenBody shouldNotContain ":null"
                tokenResponse.headers["Cache-Control"] shouldBe "no-store"
                val dto = JSON.decodeFromString(OidcTokenResponseDto.serializer(), tokenBody)
                dto.scope shouldBe "mcp:member_read"

                // 9. /mcp with the token
                val init = mcp(client, dto.access_token, """{"jsonrpc":"2.0","id":1,"method":"initialize"}""")
                init.status shouldBe HttpStatusCode.OK
                init.bodyAsText() shouldContain "2025-06-18"
                mcp(client, dto.access_token, """{"jsonrpc":"2.0","id":2,"method":"tools/list"}""").status shouldBe HttpStatusCode.OK

                // 10. refresh with resource, new token works
                val refreshed =
                    client.post("/federation/oidc/token") {
                        contentType(ContentType.Application.FormUrlEncoded)
                        setBody(
                            Parameters
                                .build {
                                    append("grant_type", "refresh_token")
                                    append("refresh_token", requireNotNull(dto.refresh_token))
                                    append("client_id", clientId)
                                    append("resource", RESOURCE)
                                }.formUrlEncode(),
                        )
                    }
                refreshed.status shouldBe HttpStatusCode.OK
                val dto2 = JSON.decodeFromString(OidcTokenResponseDto.serializer(), refreshed.bodyAsText())
                mcp(client, dto2.access_token, """{"jsonrpc":"2.0","id":3,"method":"ping"}""").status shouldBe HttpStatusCode.OK

                // 11. replaying the code fails
                val replay =
                    client.post("/federation/oidc/token") {
                        contentType(ContentType.Application.FormUrlEncoded)
                        setBody(
                            Parameters
                                .build {
                                    append("grant_type", "authorization_code")
                                    append("code", code)
                                    append("code_verifier", verifier)
                                    append("redirect_uri", redirect)
                                    append("client_id", clientId)
                                }.formUrlEncode(),
                        )
                    }
                replay.status shouldBe HttpStatusCode.BadRequest
                replay.bodyAsText() shouldContain "invalid_grant"
            }
        }

        test("a member with the kill-switch on gets access_denied at consent, even over the localhost redirect") {
            testApplication {
                val noRedirect = createClient { followRedirects = false }
                application { module(aiConfig = AiConfig.load { null }, mcpConfig = cfg(write = false)) }
                val dcr =
                    client.post("/federation/oidc/register") {
                        contentType(ContentType.Application.Json)
                        setBody(
                            """{"client_name":"Claude Code","redirect_uris":["http://localhost:53682/callback"],""" +
                                """"token_endpoint_auth_method":"none"}""",
                        )
                    }
                val clientId = Regex("\"client_id\":\"([^\"]+)\"").find(dcr.bodyAsText())!!.groupValues[1]
                createdClientIds += clientId
                val (memberId, session) = createTestMember()
                McpMemberBlockStore.setBlocked(memberId = memberId, blocked = true)
                val consent =
                    noRedirect.post("/federation/oidc/authorize/consent") {
                        header(HttpHeaders.Cookie, "lapis_session=$session")
                        contentType(ContentType.Application.FormUrlEncoded)
                        setBody(
                            Parameters
                                .build {
                                    append("decision", "allow")
                                    append("client_id", clientId)
                                    append("redirect_uri", "http://localhost:61111/callback")
                                    append("scope", "mcp:member_read")
                                    append("state", "xyz")
                                    append("code_challenge", OidcPkce.codeChallengeS256("v-padding-padding-padding-padding-padding-1"))
                                    append("resource", RESOURCE)
                                    append("connection_label", "Claude Code")
                                }.formUrlEncode(),
                        )
                    }
                consent.status shouldBe HttpStatusCode.Found
                requireNotNull(consent.headers[HttpHeaders.Location]) shouldContain "error=access_denied"
            }
        }
    })
