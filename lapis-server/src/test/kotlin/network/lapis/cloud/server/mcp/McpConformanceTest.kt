package network.lapis.cloud.server.mcp

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotBeBlank
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
import java.net.URLEncoder
import kotlin.uuid.Uuid

private val CONFORMANCE_JSON = Json { ignoreUnknownKeys = true }
private const val CONFORMANCE_MCP_RESOURCE = "http://localhost:8080/mcp"

// Welle V1.8.2b (S1) -- ENV_WRITE_ENABLED must be "true" here too, or every pre-existing
// write-capable test in this file (e.g. "tools/list ... sees all 7 catalog entries" below) goes
// red the instant McpConfig.writeEnabled defaults to false: writeEnabled=false now ALSO
// suppresses the two writing tools from tools/list, on top of principal.canWrite.
private fun conformanceMcpConfig(): McpConfig =
    McpConfig.load { if (it == McpConfig.ENV_ENABLED || it == McpConfig.ENV_WRITE_ENABLED) "true" else null }

/** Welle V1.8.2b -- MCP on, but writing OFF: the negative counterpart to [conformanceMcpConfig]. */
private fun conformanceMcpConfigWriteDisabled(): McpConfig = McpConfig.load { if (it == McpConfig.ENV_ENABLED) "true" else null }

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
            // Welle V1.8.2 wave 2 -- defaults to the pre-existing read-only grant so every
            // pre-existing call site is unaffected; pass "mcp:member_read mcp:member_write" to
            // exercise the write-capable path (see the `tools/list` write-scope test below).
            scope: String = "mcp:member_read",
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
                                append("scope", scope)
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
            scope: String = "mcp:member_read",
        ): String {
            val clientId = registerPublicClient(client, redirectUri)
            val (_, rawSession) = createTestMember("mcp-conformance-${Uuid.random()}@example.org")
            return grantMcpAccess(
                client = client,
                noRedirectClient = noRedirectClient,
                rawSession = rawSession,
                clientId = clientId,
                redirectUri = redirectUri,
                scope = scope,
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

        // Welle V1.8.2 wave 2 review fix: `McpWriteToolsDispatcherTest`'s class KDoc claimed
        // coverage for "the tools/list catalog filtering by write scope" that did not actually
        // exist anywhere -- `toolsListResult` (routes.McpRoutes) is `private`, so only an
        // end-to-end HTTP call like this one can exercise it. This test, together with the
        // read-only one above (5 entries), is what that KDoc's claim now actually points to.
        test("tools/list: a write-capable principal (mcp:member_read + mcp:member_write) sees all 7 catalog entries") {
            testApplication {
                val noRedirectClient = createClient { followRedirects = false }
                application { module(aiConfig = AiConfig.load { null }, mcpConfig = conformanceMcpConfig()) }
                val accessToken =
                    grantFreshToken(
                        client,
                        noRedirectClient,
                        "http://127.0.0.1:19713/cb",
                        scope = "mcp:member_read mcp:member_write",
                    )

                val response = mcpCall(client, accessToken, """{"jsonrpc":"2.0","id":1,"method":"tools/list"}""")
                val tools = CONFORMANCE_JSON.parseToJsonElement(response.bodyAsText()).jsonObject["result"]!!.jsonObject["tools"]
                val toolArray = tools as JsonArray
                toolArray.size shouldBe 7
                val names = toolArray.map { it.jsonObject["name"]!!.jsonPrimitive.content }.toSet()
                names shouldBe
                    setOf(
                        "get_my_contribution_status",
                        "get_my_ltr_balance",
                        "search_statute",
                        "list_upcoming_events",
                        "get_my_ballots",
                        "register_for_event",
                        "create_post_draft",
                    )
            }
        }

        // Review fix: `toolsListResult`'s SECOND filter condition (`writeEnabled`, McpConfig
        // .isWriteOperational) had no test of its own -- only "sees all 7" above (writeEnabled=true)
        // and McpWriteToolsDispatcherTest's "writeEnabled=false: ... Forbidden for a writing tool"
        // (the DISPATCH-time enforcement, not this ADVERTISING-time filter). Neither would catch a
        // future regression that turned `!it.writing || (principal.canWrite && writeEnabled)` back
        // into `!it.writing || principal.canWrite` -- a write-scoped token would then be offered
        // the two writing tools again even while an operator has switched writing off. The token
        // here MUST be granted under a write-ENABLED app instance -- both "GET /authorize" and
        // "POST /authorize/consent" below reject a request naming mcp:member_write outright once
        // writing is disabled, so this scope could never be granted directly under
        // [conformanceMcpConfigWriteDisabled] -- then reused against a SEPARATE, write-DISABLED app
        // instance for the actual tools/list call: the access token is an opaque bearer value looked
        // up by hash in `OidcIssuedTokenTable` ([network.lapis.cloud.server.mcp.auth.McpTokenAuth
        // .resolve]), not tied to the app instance that issued it, so this is the same "grant once,
        // call from wherever" persistence every other test in this file relies on implicitly by
        // sharing one H2 database across all of a spec's `testApplication` blocks.
        test("tools/list: a write-capable principal sees only the 5 reading tools once writing is disabled") {
            lateinit var accessToken: String
            testApplication {
                val noRedirectClient = createClient { followRedirects = false }
                application { module(aiConfig = AiConfig.load { null }, mcpConfig = conformanceMcpConfig()) }
                accessToken =
                    grantFreshToken(
                        client,
                        noRedirectClient,
                        "http://127.0.0.1:19716/cb",
                        scope = "mcp:member_read mcp:member_write",
                    )
            }

            testApplication {
                application { module(aiConfig = AiConfig.load { null }, mcpConfig = conformanceMcpConfigWriteDisabled()) }
                val response = mcpCall(client, accessToken, """{"jsonrpc":"2.0","id":1,"method":"tools/list"}""")
                val tools = CONFORMANCE_JSON.parseToJsonElement(response.bodyAsText()).jsonObject["result"]!!.jsonObject["tools"]
                val toolArray = tools as JsonArray
                toolArray.size shouldBe 5
                val names = toolArray.map { it.jsonObject["name"]!!.jsonPrimitive.content }.toSet()
                names.contains("register_for_event") shouldBe false
                names.contains("create_post_draft") shouldBe false
            }
        }

        // Welle V1.8.2b -- LAPIS_MCP_WRITE_ENABLED=false: GET /authorize and POST
        // /authorize/consent both reject a request naming mcp:member_write, never silently
        // narrowing it to a read-only grant (see routes.OidcRoutes mcpWriteEnabled KDoc).
        test("GET /authorize with mcp:member_write requested is rejected 400 when writing is disabled") {
            testApplication {
                application { module(aiConfig = AiConfig.load { null }, mcpConfig = conformanceMcpConfigWriteDisabled()) }
                val clientId = registerPublicClient(client, "http://127.0.0.1:19714/cb")
                val (_, rawSession) = createTestMember("mcp-conformance-write-switch-authorize-${Uuid.random()}@example.org")
                val codeChallenge = OidcPkce.codeChallengeS256("write-switch-verifier-padding-padding-1234")
                val authorizeUrl =
                    "/federation/oidc/authorize?response_type=code&client_id=$clientId&redirect_uri=" +
                        "${URLEncoder.encode("http://127.0.0.1:19714/cb", "UTF-8")}&scope=${
                            URLEncoder.encode("mcp:member_read mcp:member_write", "UTF-8")
                        }&state=s1&code_challenge=$codeChallenge&code_challenge_method=S256&nonce=n1&resource=${
                            URLEncoder.encode(CONFORMANCE_MCP_RESOURCE, "UTF-8")
                        }"
                val response = client.get(authorizeUrl) { header(HttpHeaders.Cookie, "lapis_session=$rawSession") }
                response.status shouldBe HttpStatusCode.BadRequest
            }
        }

        test("POST /authorize/consent with mcp:member_write requested is rejected 400 when writing is disabled") {
            testApplication {
                val noRedirectClient = createClient { followRedirects = false }
                application { module(aiConfig = AiConfig.load { null }, mcpConfig = conformanceMcpConfigWriteDisabled()) }
                val clientId = registerPublicClient(client, "http://127.0.0.1:19715/cb")
                val (_, rawSession) = createTestMember("mcp-conformance-write-switch-consent-${Uuid.random()}@example.org")
                val response =
                    noRedirectClient.post("/federation/oidc/authorize/consent") {
                        header(HttpHeaders.Cookie, "lapis_session=$rawSession")
                        contentType(ContentType.Application.FormUrlEncoded)
                        setBody(
                            Parameters
                                .build {
                                    append("decision", "allow")
                                    append("client_id", clientId)
                                    append("redirect_uri", "http://127.0.0.1:19715/cb")
                                    append("scope", "mcp:member_read mcp:member_write")
                                    append("state", "state-${Uuid.random()}")
                                    append(
                                        "code_challenge",
                                        OidcPkce.codeChallengeS256("write-switch-consent-verifier-padding-padding-1234"),
                                    )
                                    append("nonce", "nonce-${Uuid.random()}")
                                    append("resource", CONFORMANCE_MCP_RESOURCE)
                                    append("connection_label", "Write Switch Test Agent")
                                }.formUrlEncode(),
                        )
                    }
                response.status shouldBe HttpStatusCode.BadRequest
            }
        }

        test("OidcDiscoveryDocument via /.well-known/openid-configuration never advertises mcp:member_write when writing is disabled") {
            testApplication {
                application { module(aiConfig = AiConfig.load { null }, mcpConfig = conformanceMcpConfigWriteDisabled()) }
                val response = client.get("/.well-known/openid-configuration")
                val scopes =
                    (CONFORMANCE_JSON.parseToJsonElement(response.bodyAsText()).jsonObject["scopes_supported"] as JsonArray)
                        .map { it.jsonPrimitive.content }
                scopes.contains("mcp:member_write") shouldBe false
                scopes.contains("mcp:member_read") shouldBe true
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
