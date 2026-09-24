package network.lapis.cloud.server.mcp

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
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
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.uuid.Uuid

private val TEST_JSON = Json { ignoreUnknownKeys = true }
private const val MCP_RESOURCE = "http://localhost:8080/mcp"

private fun operationalMcpConfig(): McpConfig = McpConfig.load { if (it == McpConfig.ENV_ENABLED) "true" else null }

/**
 * End-to-end coverage of the MCP resource-server path -- the piece the implementation plan (§0)
 * identifies as the actual gap this wave closes. Exercises the REAL grant flow (public-client DCR
 * -> `/authorize` with `mcp:member_read` + `resource` -> MCP consent -> `/token` without a
 * `client_secret`) against the fully-wired [network.lapis.cloud.server.module], same house pattern
 * [network.lapis.cloud.server.routes.OidcRoutesTest] already establishes.
 */
class McpEndToEndTest :
    FunSpec({
        // The H2 in-memory database is shared across every spec in this JVM test run --
        // pre-existing tests (e.g. ServiceIntegrationTest's "listMembers ... leaks no email/role")
        // assert an EXACT count of ACTIVE members from DevSeedData. Every member/client row this
        // spec creates MUST be torn down in afterSpec, same discipline OidcRoutesTest already
        // establishes -- otherwise this spec would silently corrupt an unrelated test's assertion.
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
                    it[displayName] = "MCP E2E Testmitglied"
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
            redirectUri: String = "http://127.0.0.1:19999/cb",
        ): String {
            val response =
                client.post("/federation/oidc/register") {
                    contentType(ContentType.Application.Json)
                    setBody(
                        """{"client_name":"MCP Test Agent","redirect_uris":["$redirectUri"],""" +
                            """"token_endpoint_auth_method":"none"}""",
                    )
                }
            response.status shouldBe HttpStatusCode.Created
            val body = response.bodyAsText()
            body shouldContain "\"client_secret\":null"
            val clientId = Regex("\"client_id\":\"([^\"]+)\"").find(body)!!.groupValues[1]
            createdClientIds += clientId
            return clientId
        }

        suspend fun grantMcpAccess(
            client: HttpClient,
            noRedirectClient: HttpClient,
            rawSession: String,
            clientId: String,
            redirectUri: String = "http://127.0.0.1:19999/cb",
        ): String {
            val codeVerifier = "mcp-e2e-code-verifier-${Uuid.random()}-padding-padding-1234"
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
                                append("resource", MCP_RESOURCE)
                                append("connection_label", "Test Agent Connection")
                            }.formUrlEncode(),
                    )
                }
            consentResponse.status shouldBe HttpStatusCode.Found
            val location = requireNotNull(consentResponse.headers[HttpHeaders.Location])
            location shouldContain "code="
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
            val tokenDto = TEST_JSON.decodeFromString(OidcTokenResponseDto.serializer(), tokenResponse.bodyAsText())
            tokenDto.scope shouldBe "mcp:member_read"
            return tokenDto.access_token
        }

        suspend fun mcpCall(
            client: HttpClient,
            accessToken: String?,
            body: String,
        ): HttpResponse =
            client.post("/mcp") {
                contentType(ContentType.Application.Json)
                if (accessToken != null) header(HttpHeaders.Authorization, "Bearer $accessToken")
                setBody(body)
            }

        test("public-client happy path: DCR -> authorize+consent -> token (no secret) -> initialize/tools list/tools call") {
            testApplication {
                val noRedirectClient = createClient { followRedirects = false }
                application { module(aiConfig = AiConfig.load { null }, mcpConfig = operationalMcpConfig()) }

                val clientId = registerPublicClient(client)
                val (_, rawSession) = createTestMember("mcp-e2e-happy-${Uuid.random()}@example.org")
                val accessToken =
                    grantMcpAccess(client = client, noRedirectClient = noRedirectClient, rawSession = rawSession, clientId = clientId)

                val initResponse = mcpCall(client, accessToken, """{"jsonrpc":"2.0","id":1,"method":"initialize"}""")
                initResponse.status shouldBe HttpStatusCode.OK
                initResponse.bodyAsText() shouldContain "2025-06-18"

                val listResponse = mcpCall(client, accessToken, """{"jsonrpc":"2.0","id":2,"method":"tools/list"}""")
                listResponse.status shouldBe HttpStatusCode.OK
                val listBody = listResponse.bodyAsText()
                listOf(
                    "get_my_contribution_status",
                    "get_my_ltr_balance",
                    "search_statute",
                    "list_upcoming_events",
                    "get_my_ballots",
                ).forEach {
                    listBody shouldContain it
                }

                val callResponse =
                    mcpCall(
                        client,
                        accessToken,
                        """{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"get_my_contribution_status","arguments":{}}}""",
                    )
                callResponse.status shouldBe HttpStatusCode.OK
                val callBody = callResponse.bodyAsText()
                callBody shouldContain "totalDue"
                callBody shouldContain "\"isError\":false"

                val pingResponse = mcpCall(client, accessToken, """{"jsonrpc":"2.0","id":4,"method":"ping"}""")
                pingResponse.status shouldBe HttpStatusCode.OK

                mcpCall(client, accessToken, """{"jsonrpc":"2.0","method":"notifications/initialized"}""").status shouldBe
                    HttpStatusCode.Accepted
            }
        }

        test(
            "the four remaining tools (get_my_ltr_balance, search_statute, list_upcoming_events, get_my_ballots) are actually invoked via tools/call, not just listed",
        ) {
            testApplication {
                val noRedirectClient = createClient { followRedirects = false }
                application { module(aiConfig = AiConfig.load { null }, mcpConfig = operationalMcpConfig()) }
                val clientId = registerPublicClient(client, redirectUri = "http://127.0.0.1:19993/cb")
                val (_, rawSession) = createTestMember("mcp-all-tools-${Uuid.random()}@example.org")
                val accessToken =
                    grantMcpAccess(
                        client = client,
                        noRedirectClient = noRedirectClient,
                        rawSession = rawSession,
                        clientId = clientId,
                        redirectUri = "http://127.0.0.1:19993/cb",
                    )

                val ltrResponse =
                    mcpCall(
                        client,
                        accessToken,
                        """{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"get_my_ltr_balance","arguments":{}}}""",
                    )
                ltrResponse.status shouldBe HttpStatusCode.OK
                ltrResponse.bodyAsText() shouldContain "freeBalanceLtr"
                ltrResponse.bodyAsText() shouldContain "\"isError\":false"

                val searchResponse =
                    mcpCall(
                        client,
                        accessToken,
                        """{"jsonrpc":"2.0","id":2,"method":"tools/call",""" +
                            """"params":{"name":"search_statute","arguments":{"query":"Mitgliedsbeitrag Satzung"}}}""",
                    )
                searchResponse.status shouldBe HttpStatusCode.OK
                searchResponse.bodyAsText() shouldContain "citations"
                searchResponse.bodyAsText() shouldContain "\"isError\":false"

                val eventsResponse =
                    mcpCall(
                        client,
                        accessToken,
                        """{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"list_upcoming_events","arguments":{}}}""",
                    )
                eventsResponse.status shouldBe HttpStatusCode.OK
                eventsResponse.bodyAsText() shouldContain "events"
                eventsResponse.bodyAsText() shouldContain "\"isError\":false"

                val ballotsResponse =
                    mcpCall(
                        client,
                        accessToken,
                        """{"jsonrpc":"2.0","id":4,"method":"tools/call","params":{"name":"get_my_ballots","arguments":{}}}""",
                    )
                ballotsResponse.status shouldBe HttpStatusCode.OK
                ballotsResponse.bodyAsText() shouldContain "ballots"
                ballotsResponse.bodyAsText() shouldContain "\"isError\":false"
            }
        }

        test("search_statute: a query shorter than 8 characters is a tool-result error, not a 500 or an internal error") {
            testApplication {
                val noRedirectClient = createClient { followRedirects = false }
                application { module(aiConfig = AiConfig.load { null }, mcpConfig = operationalMcpConfig()) }
                val clientId = registerPublicClient(client, redirectUri = "http://127.0.0.1:19992/cb")
                val (_, rawSession) = createTestMember("mcp-search-too-short-${Uuid.random()}@example.org")
                val accessToken =
                    grantMcpAccess(
                        client = client,
                        noRedirectClient = noRedirectClient,
                        rawSession = rawSession,
                        clientId = clientId,
                        redirectUri = "http://127.0.0.1:19992/cb",
                    )
                val response =
                    mcpCall(
                        client,
                        accessToken,
                        """{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"search_statute","arguments":{"query":"kurz"}}}""",
                    )
                response.status shouldBe HttpStatusCode.OK
                val body = response.bodyAsText()
                body shouldContain "\"isError\":true"
                body shouldContain "'query' must be between"
            }
        }

        test(
            "a non-primitive (object) argument value comes back as a tool-result error naming the argument, " +
                "NOT as an opaque \"Internal error\"",
        ) {
            testApplication {
                val noRedirectClient = createClient { followRedirects = false }
                application { module(aiConfig = AiConfig.load { null }, mcpConfig = operationalMcpConfig()) }
                val clientId = registerPublicClient(client, redirectUri = "http://127.0.0.1:19991/cb")
                val (_, rawSession) = createTestMember("mcp-non-primitive-arg-${Uuid.random()}@example.org")
                val accessToken =
                    grantMcpAccess(
                        client = client,
                        noRedirectClient = noRedirectClient,
                        rawSession = rawSession,
                        clientId = clientId,
                        redirectUri = "http://127.0.0.1:19991/cb",
                    )
                val response =
                    mcpCall(
                        client,
                        accessToken,
                        """{"jsonrpc":"2.0","id":1,"method":"tools/call",""" +
                            """"params":{"name":"get_my_ltr_balance","arguments":{"limit":{"min":1}}}}""",
                    )
                response.status shouldBe HttpStatusCode.OK
                val body = response.bodyAsText()
                body shouldContain "\"isError\":true"
                body shouldContain "'limit' must be an integer"
                (body.contains("Internal error")) shouldBe false
            }
        }

        test("scope=openid mcp:member_read (mixed) is rejected even posted DIRECTLY to /authorize/consent, not just at GET /authorize") {
            testApplication {
                val noRedirectClient = createClient { followRedirects = false }
                application { module(aiConfig = AiConfig.load { null }, mcpConfig = operationalMcpConfig()) }
                val clientId = registerPublicClient(client, redirectUri = "http://127.0.0.1:19990/cb")
                val (_, rawSession) = createTestMember("mcp-mixed-scope-consent-post-${Uuid.random()}@example.org")

                val response =
                    noRedirectClient.post("/federation/oidc/authorize/consent") {
                        header(HttpHeaders.Cookie, "lapis_session=$rawSession")
                        contentType(ContentType.Application.FormUrlEncoded)
                        setBody(
                            Parameters
                                .build {
                                    append("decision", "allow")
                                    append("client_id", clientId)
                                    append("redirect_uri", "http://127.0.0.1:19990/cb")
                                    append("scope", "openid mcp:member_read")
                                    append("state", "s")
                                    append("code_challenge", "cc")
                                    append("nonce", "n")
                                    append("resource", MCP_RESOURCE)
                                    append("connection_label", "Mixed Scope Bypass Attempt")
                                }.formUrlEncode(),
                        )
                    }
                response.status shouldBe HttpStatusCode.BadRequest
            }
        }

        test(
            "a blocked member cannot use a mixed-scope POST straight to /authorize/consent to mint an " +
                "invisible, unrevokable mcp:member_read grant -- rejected the same way as an unblocked member's",
        ) {
            testApplication {
                val noRedirectClient = createClient { followRedirects = false }
                application { module(aiConfig = AiConfig.load { null }, mcpConfig = operationalMcpConfig()) }
                val clientId = registerPublicClient(client, redirectUri = "http://127.0.0.1:19989/cb")
                val (memberId, rawSession) = createTestMember("mcp-blocked-mixed-scope-${Uuid.random()}@example.org")
                McpMemberBlockStore.setBlocked(memberId = memberId, blocked = true)

                val response =
                    noRedirectClient.post("/federation/oidc/authorize/consent") {
                        header(HttpHeaders.Cookie, "lapis_session=$rawSession")
                        contentType(ContentType.Application.FormUrlEncoded)
                        setBody(
                            Parameters
                                .build {
                                    append("decision", "allow")
                                    append("client_id", clientId)
                                    append("redirect_uri", "http://127.0.0.1:19989/cb")
                                    append("scope", "openid mcp:member_read")
                                    append("state", "s")
                                    append("code_challenge", "cc")
                                    append("nonce", "n")
                                    append("resource", MCP_RESOURCE)
                                    append("connection_label", "Blocked Member Mixed Scope Attempt")
                                }.formUrlEncode(),
                        )
                    }
                // Rejected outright -- never redirected with a minted code, blocked or not.
                response.status shouldBe HttpStatusCode.BadRequest
            }
        }

        test(
            "scope=\"mcp:member_read \" (trailing whitespace -- set-equal but not exact-equal to the " +
                "canonical literal) is normalized before it is persisted, so the resulting token actually " +
                "authenticates against /mcp and stays reachable by the kill-switch, instead of silently " +
                "minting an unusable, unrevokable grant",
        ) {
            testApplication {
                val noRedirectClient = createClient { followRedirects = false }
                application { module(aiConfig = AiConfig.load { null }, mcpConfig = operationalMcpConfig()) }
                val clientId = registerPublicClient(client, redirectUri = "http://127.0.0.1:19988/cb")
                val (memberId, rawSession) = createTestMember("mcp-scope-normalize-${Uuid.random()}@example.org")

                val codeVerifier = "mcp-scope-normalize-code-verifier-padding-padding-1234"
                val codeChallenge = OidcPkce.codeChallengeS256(codeVerifier)
                val state = "state-${Uuid.random()}"
                val nonce = "nonce-${Uuid.random()}"

                val consentResponse =
                    noRedirectClient.post("/federation/oidc/authorize/consent") {
                        header(HttpHeaders.Cookie, "lapis_session=$rawSession")
                        contentType(ContentType.Application.FormUrlEncoded)
                        setBody(
                            Parameters
                                .build {
                                    append("decision", "allow")
                                    append("client_id", clientId)
                                    append("redirect_uri", "http://127.0.0.1:19988/cb")
                                    // Set-equal to {"mcp:member_read"} (the trailing token is blank and
                                    // filtered), but NOT exact-equal to the canonical literal -- the exact
                                    // shape this finding is about.
                                    append("scope", "mcp:member_read ")
                                    append("state", state)
                                    append("code_challenge", codeChallenge)
                                    append("nonce", nonce)
                                    append("resource", MCP_RESOURCE)
                                    append("connection_label", "Scope Normalization Test Agent")
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
                                    append("redirect_uri", "http://127.0.0.1:19988/cb")
                                    append("client_id", clientId)
                                    append("code_verifier", codeVerifier)
                                }.formUrlEncode(),
                        )
                    }
                tokenResponse.status shouldBe HttpStatusCode.OK
                val tokenDto = TEST_JSON.decodeFromString(OidcTokenResponseDto.serializer(), tokenResponse.bodyAsText())
                // The response already proves normalization happened.
                tokenDto.scope shouldBe "mcp:member_read"

                // The PERSISTED row must be exact-equal to the canonical literal too, not just what the
                // response claims -- every MCP gate (McpTokenAuth, McpTokenRevoker, McpAccessService)
                // compares the stored column, not the token response.
                val storedScope =
                    transaction {
                        OidcIssuedTokenTable
                            .selectAll()
                            .where { OidcIssuedTokenTable.memberId eq memberId }
                            .single()[OidcIssuedTokenTable.scope]
                    }
                storedScope shouldBe "mcp:member_read"

                // The token must actually authenticate -- before this fix it 401'd here (McpTokenAuth's
                // exact-equality check never matched the un-normalized stored value).
                mcpCall(client, tokenDto.access_token, """{"jsonrpc":"2.0","id":1,"method":"initialize"}""").status shouldBe
                    HttpStatusCode.OK

                // ...and the kill-switch must still reach it.
                McpMemberBlockStore.setBlocked(memberId = memberId, blocked = true)
                mcpCall(client, tokenDto.access_token, """{"jsonrpc":"2.0","id":2,"method":"initialize"}""").status shouldBe
                    HttpStatusCode.Unauthorized
            }
        }

        test("no bearer token at all -- 401 with WWW-Authenticate resource_metadata") {
            testApplication {
                application { module(aiConfig = AiConfig.load { null }, mcpConfig = operationalMcpConfig()) }
                val response = mcpCall(client, null, """{"jsonrpc":"2.0","id":1,"method":"initialize"}""")
                response.status shouldBe HttpStatusCode.Unauthorized
                requireNotNull(response.headers[HttpHeaders.WWWAuthenticate]) shouldContain "resource_metadata"
            }
        }

        test("a session cookie alone (no bearer) is never accepted as MCP auth") {
            testApplication {
                application { module(aiConfig = AiConfig.load { null }, mcpConfig = operationalMcpConfig()) }
                val (_, rawSession) = createTestMember("mcp-cookie-only-${Uuid.random()}@example.org")
                val response =
                    client.post("/mcp") {
                        contentType(ContentType.Application.Json)
                        header(HttpHeaders.Cookie, "lapis_session=$rawSession")
                        setBody("""{"jsonrpc":"2.0","id":1,"method":"initialize"}""")
                    }
                response.status shouldBe HttpStatusCode.Unauthorized
            }
        }

        test("an API key (lapis_ prefix) is never accepted as MCP auth") {
            testApplication {
                application { module(aiConfig = AiConfig.load { null }, mcpConfig = operationalMcpConfig()) }
                val response =
                    mcpCall(client, "lapis_not-a-real-key-but-has-the-prefix", """{"jsonrpc":"2.0","id":1,"method":"initialize"}""")
                response.status shouldBe HttpStatusCode.Unauthorized
            }
        }

        test("a garbage/unknown token is rejected exactly like a well-formed-but-wrong one -- same 401, no oracle") {
            testApplication {
                application { module(aiConfig = AiConfig.load { null }, mcpConfig = operationalMcpConfig()) }
                val unknown = mcpCall(client, "totally-unknown-token-value", """{"jsonrpc":"2.0","id":1,"method":"initialize"}""")
                val malformed = mcpCall(client, "", """{"jsonrpc":"2.0","id":1,"method":"initialize"}""")
                unknown.status shouldBe HttpStatusCode.Unauthorized
                malformed.status shouldBe HttpStatusCode.Unauthorized
                unknown.bodyAsText() shouldBe malformed.bodyAsText()
            }
        }

        test("scope=openid mcp:member_read (mixed) is rejected at /authorize") {
            testApplication {
                application { module(aiConfig = AiConfig.load { null }, mcpConfig = operationalMcpConfig()) }
                val clientId = registerPublicClient(client, redirectUri = "http://127.0.0.1:19998/cb")
                val (_, rawSession) = createTestMember("mcp-mixed-scope-${Uuid.random()}@example.org")
                val response =
                    client.get(
                        "/federation/oidc/authorize?response_type=code&client_id=$clientId" +
                            "&redirect_uri=http://127.0.0.1:19998/cb&scope=openid%20mcp:member_read" +
                            "&state=s&code_challenge=cc&code_challenge_method=S256&nonce=n&resource=$MCP_RESOURCE",
                    ) {
                        header(HttpHeaders.Cookie, "lapis_session=$rawSession")
                    }
                response.status shouldBe HttpStatusCode.BadRequest
            }
        }

        test("/authorize with mcp:member_read but a foreign resource value is rejected") {
            testApplication {
                application { module(aiConfig = AiConfig.load { null }, mcpConfig = operationalMcpConfig()) }
                val clientId = registerPublicClient(client, redirectUri = "http://127.0.0.1:19997/cb")
                val (_, rawSession) = createTestMember("mcp-wrong-resource-${Uuid.random()}@example.org")
                val response =
                    client.get(
                        "/federation/oidc/authorize?response_type=code&client_id=$clientId" +
                            "&redirect_uri=http://127.0.0.1:19997/cb&scope=mcp:member_read" +
                            "&state=s&code_challenge=cc&code_challenge_method=S256&nonce=n&resource=https://evil.example/mcp",
                    ) {
                        header(HttpHeaders.Cookie, "lapis_session=$rawSession")
                    }
                response.status shouldBe HttpStatusCode.BadRequest
            }
        }

        test("a blocked member's existing MCP token stops working immediately") {
            testApplication {
                val noRedirectClient = createClient { followRedirects = false }
                application { module(aiConfig = AiConfig.load { null }, mcpConfig = operationalMcpConfig()) }
                val clientId = registerPublicClient(client, redirectUri = "http://127.0.0.1:19996/cb")
                val (memberId, rawSession) = createTestMember("mcp-blocked-${Uuid.random()}@example.org")
                val accessToken =
                    grantMcpAccess(
                        client = client,
                        noRedirectClient = noRedirectClient,
                        rawSession = rawSession,
                        clientId = clientId,
                        redirectUri = "http://127.0.0.1:19996/cb",
                    )

                mcpCall(client, accessToken, """{"jsonrpc":"2.0","id":1,"method":"initialize"}""").status shouldBe HttpStatusCode.OK

                McpMemberBlockStore.setBlocked(memberId = memberId, blocked = true)

                mcpCall(client, accessToken, """{"jsonrpc":"2.0","id":2,"method":"initialize"}""").status shouldBe
                    HttpStatusCode.Unauthorized
            }
        }

        test("an unknown tool name in tools/call comes back as a tool-result error, not a protocol crash") {
            testApplication {
                val noRedirectClient = createClient { followRedirects = false }
                application { module(aiConfig = AiConfig.load { null }, mcpConfig = operationalMcpConfig()) }
                val clientId = registerPublicClient(client, redirectUri = "http://127.0.0.1:19995/cb")
                val (_, rawSession) = createTestMember("mcp-unknown-tool-${Uuid.random()}@example.org")
                val accessToken =
                    grantMcpAccess(
                        client = client,
                        noRedirectClient = noRedirectClient,
                        rawSession = rawSession,
                        clientId = clientId,
                        redirectUri = "http://127.0.0.1:19995/cb",
                    )
                val response =
                    mcpCall(client, accessToken, """{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"delete_everything"}}""")
                response.status shouldBe HttpStatusCode.OK
                response.bodyAsText() shouldContain "\"isError\":true"
            }
        }

        test("GET /mcp is 405 when the feature is on") {
            testApplication {
                application { module(aiConfig = AiConfig.load { null }, mcpConfig = operationalMcpConfig()) }
                client.get("/mcp").status shouldBe HttpStatusCode.MethodNotAllowed
            }
        }

        test("a batch (JSON array) request is rejected with Invalid Request") {
            testApplication {
                val noRedirectClient = createClient { followRedirects = false }
                application { module(aiConfig = AiConfig.load { null }, mcpConfig = operationalMcpConfig()) }
                val clientId = registerPublicClient(client, redirectUri = "http://127.0.0.1:19994/cb")
                val (_, rawSession) = createTestMember("mcp-batch-${Uuid.random()}@example.org")
                val accessToken =
                    grantMcpAccess(
                        client = client,
                        noRedirectClient = noRedirectClient,
                        rawSession = rawSession,
                        clientId = clientId,
                        redirectUri = "http://127.0.0.1:19994/cb",
                    )
                val response = mcpCall(client, accessToken, """[{"jsonrpc":"2.0","id":1,"method":"ping"}]""")
                response.bodyAsText() shouldContain "-32600"
            }
        }
    })
