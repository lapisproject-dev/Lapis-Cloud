package network.lapis.cloud.server.mcp

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotBeBlank
import io.ktor.client.HttpClient
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
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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
import network.lapis.cloud.server.mcp.transport.MCP_PROTOCOL_VERSION
import network.lapis.cloud.server.mcp.transport.MCP_SERVER_VERSION
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
import kotlin.uuid.Uuid

private val CONFORMANCE_JSON = Json { ignoreUnknownKeys = true }
private const val CONFORMANCE_MCP_RESOURCE = "http://localhost:8080/mcp"

private fun conformanceMcpConfig(): McpConfig = McpConfig.load { if (it == McpConfig.ENV_ENABLED) "true" else null }

/**
 * The acceptance condition `McpLayerBoundary`/`McpProtocol`/`McpScopes`/`docs/architecture/
 * mcp-server.adoc` refer to as "`McpConformanceTest`" -- protocol-WIRE-shape conformance for the
 * bespoke JSON-RPC 2.0 transport (`routes.McpRoutes`), as distinct from [McpEndToEndTest]'s own
 * focus (the full OAuth grant flow and the auth/authorization security boundary) and
 * [McpStructureTest]'s (source-scan layering rules). Deliberately duplicates a minimal slice of
 * [McpEndToEndTest]'s grant-flow setup (Kotest `FunSpec` bodies are independent lambdas, so this is
 * plain copy-paste, not a shared/imported helper) rather than share code across the two specs --
 * keeps each spec independently readable and avoids coupling this wave's fixtures to whatever the
 * other spec's helpers evolve into later.
 */
class McpConformanceTest :
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

        fun createTestMember(email: String): Pair<Uuid, String> {
            val id = Uuid.random()
            transaction {
                MemberTable.insert {
                    it[MemberTable.id] = id
                    it[displayName] = "MCP Conformance Testmitglied"
                    it[MemberTable.email] = email
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
            val rawToken = SessionStore.createSession(id).rawToken
            return id to rawToken
        }

        suspend fun registerPublicClient(
            client: HttpClient,
            redirectUri: String,
        ): String {
            val response =
                client.post("/federation/oidc/register") {
                    contentType(ContentType.Application.Json)
                    setBody(
                        """{"client_name":"MCP Conformance Test Agent","redirect_uris":["$redirectUri"],""" +
                            """"token_endpoint_auth_method":"none"}""",
                    )
                }
            response.status shouldBe HttpStatusCode.Created
            val body = response.bodyAsText()
            val clientId = Regex("\"client_id\":\"([^\"]+)\"").find(body)!!.groupValues[1]
            createdClientIds += clientId
            return clientId
        }

        suspend fun grantMcpAccess(
            client: HttpClient,
            noRedirectClient: HttpClient,
            rawSession: String,
            clientId: String,
            redirectUri: String,
        ): String {
            val codeVerifier = "mcp-conformance-code-verifier-${Uuid.random()}-padding-padding-1234"
            val codeChallenge = OidcPkce.codeChallengeS256(codeVerifier)
            val state = "state-${Uuid.random()}"
            val nonce = "nonce-${Uuid.random()}"

            val consentResponse: HttpResponse =
                noRedirectClient.post("/federation/oidc/authorize/consent") {
                    header(HttpHeaders.Cookie, "lapis_session=$rawSession")
                    contentType(ContentType.Application.FormUrlEncoded)
                    setBody(
                        Parameters
                            .build {
                                append("decision", "allow")
                                append("client_id", clientId)
                                append("redirect_uri", redirectUri)
                                append("scope", "mcp:member_read")
                                append("state", state)
                                append("code_challenge", codeChallenge)
                                append("nonce", nonce)
                                append("resource", CONFORMANCE_MCP_RESOURCE)
                                append("connection_label", "Conformance Test Agent Connection")
                            }.formUrlEncode(),
                    )
                }
            consentResponse.status shouldBe HttpStatusCode.Found
            val location = requireNotNull(consentResponse.headers[HttpHeaders.Location])
            val code = Regex("code=([^&]+)").find(location)!!.groupValues[1]

            val tokenResponse =
                client.post("/federation/oidc/token") {
                    contentType(ContentType.Application.FormUrlEncoded)
                    setBody(
                        Parameters
                            .build {
                                append("grant_type", "authorization_code")
                                append("code", code)
                                append("redirect_uri", redirectUri)
                                append("client_id", clientId)
                                append("code_verifier", codeVerifier)
                            }.formUrlEncode(),
                    )
                }
            tokenResponse.status shouldBe HttpStatusCode.OK
            val tokenDto = CONFORMANCE_JSON.decodeFromString(OidcTokenResponseDto.serializer(), tokenResponse.bodyAsText())
            return tokenDto.access_token
        }

        suspend fun mcpCall(
            client: HttpClient,
            accessToken: String?,
            body: String,
            protocolVersionHeader: String? = MCP_PROTOCOL_VERSION,
        ): HttpResponse =
            client.post("/mcp") {
                contentType(ContentType.Application.Json)
                if (accessToken != null) header(HttpHeaders.Authorization, "Bearer $accessToken")
                if (protocolVersionHeader != null) header("MCP-Protocol-Version", protocolVersionHeader)
                setBody(body)
            }

        suspend fun grantFreshToken(
            client: HttpClient,
            noRedirectClient: HttpClient,
            redirectUri: String,
        ): String {
            val clientId = registerPublicClient(client, redirectUri)
            val (_, rawSession) = createTestMember("mcp-conformance-${Uuid.random()}@example.org")
            return grantMcpAccess(
                client = client,
                noRedirectClient = noRedirectClient,
                rawSession = rawSession,
                clientId = clientId,
                redirectUri = redirectUri,
            )
        }

        test("MCP-Protocol-Version header: a mismatched value is rejected with 400 BEFORE auth even runs") {
            testApplication {
                application { module(aiConfig = AiConfig.load { null }, mcpConfig = conformanceMcpConfig()) }
                // No bearer token at all -- if this were rejected for auth reasons it would be 401,
                // not 400; a 400 here proves the header check runs first, exactly as
                // `routes.McpRoutes` KDoc's ordering promises.
                val response =
                    mcpCall(
                        client = client,
                        accessToken = null,
                        body = """{"jsonrpc":"2.0","id":1,"method":"initialize"}""",
                        protocolVersionHeader = "1999-01-01",
                    )
                response.status shouldBe HttpStatusCode.BadRequest
            }
        }

        test("MCP-Protocol-Version header: matching value and an absent header are both accepted") {
            testApplication {
                val noRedirectClient = createClient { followRedirects = false }
                application { module(aiConfig = AiConfig.load { null }, mcpConfig = conformanceMcpConfig()) }
                val accessToken = grantFreshToken(client, noRedirectClient, "http://127.0.0.1:19700/cb")

                mcpCall(client, accessToken, """{"jsonrpc":"2.0","id":1,"method":"ping"}""", protocolVersionHeader = MCP_PROTOCOL_VERSION)
                    .status shouldBe HttpStatusCode.OK
                mcpCall(client, accessToken, """{"jsonrpc":"2.0","id":2,"method":"ping"}""", protocolVersionHeader = null)
                    .status shouldBe HttpStatusCode.OK
            }
        }

        test(
            "initialize's own params.protocolVersion is pinned exactly like the header -- a mismatch " +
                "is rejected, never silently accepted",
        ) {
            testApplication {
                val noRedirectClient = createClient { followRedirects = false }
                application { module(aiConfig = AiConfig.load { null }, mcpConfig = conformanceMcpConfig()) }
                val accessToken = grantFreshToken(client, noRedirectClient, "http://127.0.0.1:19701/cb")

                // No header at all -- only params.protocolVersion states a (wrong) version.
                val response =
                    mcpCall(
                        client = client,
                        accessToken = accessToken,
                        body = """{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2024-11-05"}}""",
                        protocolVersionHeader = null,
                    )
                val json = CONFORMANCE_JSON.parseToJsonElement(response.bodyAsText()).jsonObject
                json.containsKey("error") shouldBe true
                json["error"]!!.jsonObject["code"]!!.jsonPrimitive.content shouldBe "-32602"

                // The matching version, in params only, is accepted.
                val ok =
                    mcpCall(
                        client = client,
                        accessToken = accessToken,
                        body = """{"jsonrpc":"2.0","id":2,"method":"initialize","params":{"protocolVersion":"$MCP_PROTOCOL_VERSION"}}""",
                        protocolVersionHeader = null,
                    )
                ok.bodyAsText() shouldContain "\"result\""
            }
        }

        test("initialize result: serverInfo carries a non-blank version, and the hard-pinned protocol version") {
            testApplication {
                val noRedirectClient = createClient { followRedirects = false }
                application { module(aiConfig = AiConfig.load { null }, mcpConfig = conformanceMcpConfig()) }
                val accessToken = grantFreshToken(client, noRedirectClient, "http://127.0.0.1:19702/cb")

                val response = mcpCall(client, accessToken, """{"jsonrpc":"2.0","id":1,"method":"initialize"}""")
                val result = CONFORMANCE_JSON.parseToJsonElement(response.bodyAsText()).jsonObject["result"]!!.jsonObject
                result["protocolVersion"]!!.jsonPrimitive.content shouldBe MCP_PROTOCOL_VERSION
                val serverInfo: JsonObject = result["serverInfo"]!!.jsonObject
                serverInfo["version"]!!.jsonPrimitive.content.shouldNotBeBlank()
                serverInfo["version"]!!.jsonPrimitive.content shouldBe MCP_SERVER_VERSION
            }
        }

        test("tools/list: every one of the five catalog entries carries name/title/description/inputSchema") {
            testApplication {
                val noRedirectClient = createClient { followRedirects = false }
                application { module(aiConfig = AiConfig.load { null }, mcpConfig = conformanceMcpConfig()) }
                val accessToken = grantFreshToken(client, noRedirectClient, "http://127.0.0.1:19703/cb")

                val response = mcpCall(client, accessToken, """{"jsonrpc":"2.0","id":1,"method":"tools/list"}""")
                val tools = CONFORMANCE_JSON.parseToJsonElement(response.bodyAsText()).jsonObject["result"]!!.jsonObject["tools"]
                val toolArray = tools as JsonArray
                toolArray.size shouldBe 5
                toolArray.forEach { tool ->
                    val obj = tool.jsonObject
                    obj["name"]!!.jsonPrimitive.content.shouldNotBeBlank()
                    obj["title"]!!.jsonPrimitive.content.shouldNotBeBlank()
                    obj["description"]!!.jsonPrimitive.content.shouldNotBeBlank()
                    (obj["inputSchema"] as? JsonObject).shouldNotBeNull()
                }
            }
        }

        test("a malformed JSON body comes back as JSON-RPC parse error -32700") {
            testApplication {
                val noRedirectClient = createClient { followRedirects = false }
                application { module(aiConfig = AiConfig.load { null }, mcpConfig = conformanceMcpConfig()) }
                val accessToken = grantFreshToken(client, noRedirectClient, "http://127.0.0.1:19704/cb")

                val response = mcpCall(client, accessToken, "{not json at all")
                response.bodyAsText() shouldContain "-32700"
            }
        }

        test("an unknown JSON-RPC method is rejected as method not found -32601") {
            testApplication {
                val noRedirectClient = createClient { followRedirects = false }
                application { module(aiConfig = AiConfig.load { null }, mcpConfig = conformanceMcpConfig()) }
                val accessToken = grantFreshToken(client, noRedirectClient, "http://127.0.0.1:19705/cb")

                val response = mcpCall(client, accessToken, """{"jsonrpc":"2.0","id":1,"method":"resources/list"}""")
                response.bodyAsText() shouldContain "-32601"
            }
        }

        test("a request with a missing method is rejected as Invalid Request -32600, not an unhandled 500") {
            testApplication {
                val noRedirectClient = createClient { followRedirects = false }
                application { module(aiConfig = AiConfig.load { null }, mcpConfig = conformanceMcpConfig()) }
                val accessToken = grantFreshToken(client, noRedirectClient, "http://127.0.0.1:19706/cb")

                val response = mcpCall(client, accessToken, """{"jsonrpc":"2.0","id":1}""")
                response.status shouldBe HttpStatusCode.OK
                response.bodyAsText() shouldContain "-32600"
            }
        }
    })
