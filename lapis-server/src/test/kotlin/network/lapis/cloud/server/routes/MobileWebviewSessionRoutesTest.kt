package network.lapis.cloud.server.routes

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.ktor.client.HttpClient
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.parseServerSetCookieHeader
import io.ktor.server.application.install
import io.ktor.server.plugins.forwardedheaders.XForwardedHeaders
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.server.db.DatabaseConfig
import network.lapis.cloud.server.db.generated.AccountTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.server.db.generated.SessionTable
import network.lapis.cloud.server.federation.FederationInboxRateLimiter
import network.lapis.cloud.server.security.ApiKeyStore
import network.lapis.cloud.server.security.LoginRateLimiter
import network.lapis.cloud.server.security.SessionStore
import network.lapis.cloud.server.security.SessionTokens
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.MemberStatus
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import kotlin.time.Duration.Companion.minutes
import kotlin.uuid.Uuid

/**
 * V1.5.2 -- [registerMobileWebviewSessionRoutes]: header-only (`Authorization: Bearer`) bridge from a
 * mobile session token to the `lapis_session` cookie, for both the conference-room and the generic
 * section route. The key regressions pinned here: a `?token=` query parameter and a foreign
 * `lapis_session` cookie can NEVER authenticate or cause a `Set-Cookie` (login CSRF / session
 * fixation), a success never resets the IP failure budget, and unknown targets are never reflected.
 *
 * Mounted in isolation with own limiter instances per test (idiom of [AuthRoutesTest]). Where the
 * `Secure` flag must be observable, [XForwardedHeaders] is installed BEFORE routing -- a controlled
 * deviation from "mount only the route": with `cookieSecure=true` the HTTPS gate otherwise rejects
 * the plain-HTTP test client first.
 */
class MobileWebviewSessionRoutesTest :
    FunSpec({
        val createdMemberIds = mutableListOf<Uuid>()

        beforeSpec { DatabaseConfig.connect() }

        afterSpec {
            transaction {
                if (createdMemberIds.isNotEmpty()) {
                    SessionTable.deleteWhere { SessionTable.memberId inList createdMemberIds }
                    AccountTable.deleteWhere { AccountTable.memberId inList createdMemberIds }
                    MemberTable.deleteWhere { MemberTable.id inList createdMemberIds }
                }
            }
        }

        fun createMemberWithSession(): String {
            val id = Uuid.random()
            transaction {
                MemberTable.insert {
                    it[MemberTable.id] = id
                    it[displayName] = "Webview-Session-Route Testmitglied"
                    it[email] = "webview-session-route-${Uuid.random()}@example.org"
                    it[status] = MemberStatus.ACTIVE
                    it[joinedAt] = LocalDate(2026, 1, 1)
                    it[membershipTierId] = null
                }
                AccountTable.insert {
                    it[AccountTable.id] = Uuid.random()
                    it[memberId] = id
                    it[role] = AccountRole.MEMBER
                }
            }
            createdMemberIds += id
            return SessionStore.createSession(id).rawToken
        }

        fun createExpiredSession(): String {
            val rawToken = createMemberWithSession()
            transaction {
                SessionTable.update({ SessionTable.tokenHash eq SessionTokens.hash(rawToken) }) {
                    it[expiresAt] = LocalDateTime(2020, 1, 1, 0, 0)
                }
            }
            return rawToken
        }

        fun ApplicationTestBuilder.mountRoute(
            cookieSecure: Boolean,
            failureLimiter: LoginRateLimiter = LoginRateLimiter(maxFailures = 30, window = 15.minutes),
            requestLimiter: FederationInboxRateLimiter = FederationInboxRateLimiter(maxRequests = 1_000, window = 1.minutes),
            trustForwardedHeaders: Boolean = false,
        ) {
            application {
                if (trustForwardedHeaders) {
                    install(XForwardedHeaders) { useLastProxy() }
                }
                routing {
                    registerMobileWebviewSessionRoutes(
                        cookieSecure = cookieSecure,
                        failureLimiter = failureLimiter,
                        requestLimiter = requestLimiter,
                    )
                }
            }
        }

        fun HttpRequestBuilder.bearer(token: String) {
            header(HttpHeaders.Authorization, "Bearer $token")
        }

        // The default ktor test client follows redirects, which would hide the 302 and its
        // Set-Cookie/Location headers -- every test uses a no-redirect client.
        fun ApplicationTestBuilder.noRedirectClient(): HttpClient = createClient { followRedirects = false }

        fun assertBridgeCookie(
            setCookieHeader: String?,
            expectedToken: String,
            expectSecure: Boolean,
        ) {
            val cookie = parseServerSetCookieHeader(requireNotNull(setCookieHeader))
            cookie.name shouldBe "lapis_session"
            cookie.value shouldBe expectedToken
            cookie.httpOnly shouldBe true
            cookie.secure shouldBe expectSecure
            cookie.path shouldBe "/"
            cookie.maxAge shouldBe SessionStore.SESSION_TTL.inWholeSeconds.toInt()
            cookie.extensions["SameSite"] shouldBe "Strict"
        }

        suspend fun HttpClient.room(
            roomPath: String,
            token: String? = null,
            block: HttpRequestBuilder.() -> Unit = {},
        ): HttpResponse =
            get("/api/mobile/v1/conference/rooms/$roomPath/webview-session") {
                if (token != null) bearer(token)
                block()
            }

        suspend fun HttpClient.section(
            section: String?,
            token: String? = null,
            block: HttpRequestBuilder.() -> Unit = {},
        ): HttpResponse =
            get("/api/mobile/v1/webview-session") {
                if (section != null) parameter("section", section)
                if (token != null) bearer(token)
                block()
            }

        // ---------------------------------------------------------------- meeting route

        test("meeting: valid header mints the cookie (HttpOnly, Strict, Path=/, Max-Age from SESSION_TTL, no Secure) and redirects") {
            testApplication {
                mountRoute(cookieSecure = false)
                val token = createMemberWithSession()

                val response = noRedirectClient().room("room-abc-123", token)

                response.status shouldBe HttpStatusCode.Found
                response.headers[HttpHeaders.Location] shouldBe "/app#/conference/room-abc-123"
                assertBridgeCookie(response.headers[HttpHeaders.SetCookie], expectedToken = token, expectSecure = false)
            }
        }

        test("meeting: cookieSecure=true behind X-Forwarded-Proto https passes the HTTPS gate and sets a Secure cookie") {
            testApplication {
                mountRoute(cookieSecure = true, trustForwardedHeaders = true)
                val token = createMemberWithSession()

                val response =
                    noRedirectClient().room("room-abc-123", token) {
                        header("X-Forwarded-Proto", "https")
                    }

                response.status shouldBe HttpStatusCode.Found
                assertBridgeCookie(response.headers[HttpHeaders.SetCookie], expectedToken = token, expectSecure = true)
            }
        }

        test("meeting: fixation regression -- no Authorization header is 401 and never sets a cookie") {
            testApplication {
                mountRoute(cookieSecure = false)

                val response = noRedirectClient().room("room-abc-123")

                response.status shouldBe HttpStatusCode.Unauthorized
                response.bodyAsText() shouldContain "Invalid or expired session"
                response.headers[HttpHeaders.SetCookie] shouldBe null
            }
        }

        test("meeting: a ?token= query parameter alone never authenticates (401, no cookie)") {
            testApplication {
                mountRoute(cookieSecure = false)
                val token = createMemberWithSession()

                val response = noRedirectClient().room("room-abc-123") { parameter("token", token) }

                response.status shouldBe HttpStatusCode.Unauthorized
                response.headers[HttpHeaders.SetCookie] shouldBe null
            }
        }

        test("meeting: ?token= of member A plus header of member B -- the cookie carries B's header token") {
            testApplication {
                mountRoute(cookieSecure = false)
                val tokenA = createMemberWithSession()
                val tokenB = createMemberWithSession()

                val response = noRedirectClient().room("room-abc-123", tokenB) { parameter("token", tokenA) }

                response.status shouldBe HttpStatusCode.Found
                assertBridgeCookie(response.headers[HttpHeaders.SetCookie], expectedToken = tokenB, expectSecure = false)
            }
        }

        test("meeting: wrong Authorization schemes and empty bearer values are 401 without a cookie") {
            testApplication {
                mountRoute(cookieSecure = false)
                val token = createMemberWithSession()
                val client = noRedirectClient()

                listOf("Basic $token", "Token $token", token, "Bearer", "Bearer   ").forEach { header ->
                    val response =
                        client.room("room-abc-123") {
                            header(HttpHeaders.Authorization, header)
                        }
                    response.status shouldBe HttpStatusCode.Unauthorized
                    response.headers[HttpHeaders.SetCookie] shouldBe null
                }
            }
        }

        test("meeting: the Bearer scheme is matched case-insensitively (bearer / BEARER)") {
            testApplication {
                mountRoute(cookieSecure = false)
                val token = createMemberWithSession()
                val client = noRedirectClient()

                listOf("bearer $token", "BEARER $token").forEach { header ->
                    val response =
                        client.room("room-abc-123") {
                            header(HttpHeaders.Authorization, header)
                        }
                    response.status shouldBe HttpStatusCode.Found
                    assertBridgeCookie(response.headers[HttpHeaders.SetCookie], expectedToken = token, expectSecure = false)
                }
            }
        }

        test("meeting: an API-key-prefixed bearer value is rejected (401, no cookie)") {
            testApplication {
                mountRoute(cookieSecure = false)

                val response = noRedirectClient().room("room-abc-123", "${ApiKeyStore.API_KEY_TOKEN_PREFIX}some-api-key")

                response.status shouldBe HttpStatusCode.Unauthorized
                response.headers[HttpHeaders.SetCookie] shouldBe null
            }
        }

        test("meeting: a revoked session is 401 without a cookie") {
            testApplication {
                mountRoute(cookieSecure = false)
                val token = createMemberWithSession()
                SessionStore.revoke(token)

                val response = noRedirectClient().room("room-abc-123", token)

                response.status shouldBe HttpStatusCode.Unauthorized
                response.headers[HttpHeaders.SetCookie] shouldBe null
            }
        }

        test("meeting: an expired session is 401 without a cookie") {
            testApplication {
                mountRoute(cookieSecure = false)
                val token = createExpiredSession()

                val response = noRedirectClient().room("room-abc-123", token)

                response.status shouldBe HttpStatusCode.Unauthorized
                response.headers[HttpHeaders.SetCookie] shouldBe null
            }
        }

        test("meeting: a foreign lapis_session cookie without a header is 401 and triggers no Set-Cookie") {
            testApplication {
                mountRoute(cookieSecure = false)
                val victimCookieToken = createMemberWithSession()

                val response =
                    noRedirectClient().room("room-abc-123") {
                        header(HttpHeaders.Cookie, "lapis_session=$victimCookieToken")
                    }

                response.status shouldBe HttpStatusCode.Unauthorized
                response.headers[HttpHeaders.SetCookie] shouldBe null
            }
        }

        test("meeting: a foreign lapis_session cookie plus a valid header -- Set-Cookie carries the HEADER token") {
            testApplication {
                mountRoute(cookieSecure = false)
                val cookieToken = createMemberWithSession()
                val headerToken = createMemberWithSession()

                val response =
                    noRedirectClient().room("room-abc-123", headerToken) {
                        header(HttpHeaders.Cookie, "lapis_session=$cookieToken")
                    }

                response.status shouldBe HttpStatusCode.Found
                assertBridgeCookie(response.headers[HttpHeaders.SetCookie], expectedToken = headerToken, expectSecure = false)
            }
        }

        test("meeting: HTTPS gate -- cookieSecure=true rejects plain HTTP with 400 even for a valid header, no cookie") {
            testApplication {
                mountRoute(cookieSecure = true)
                val token = createMemberWithSession()

                val response = noRedirectClient().room("room-abc-123", token)

                response.status shouldBe HttpStatusCode.BadRequest
                response.bodyAsText() shouldContain "HTTPS required"
                response.headers[HttpHeaders.SetCookie] shouldBe null
            }
        }

        test("meeting: a malformed roomId is 400, never reflected into Location/body, no cookie") {
            testApplication {
                mountRoute(cookieSecure = false)
                val token = createMemberWithSession()
                val client = noRedirectClient()

                listOf("a%0d%0aX-Injected:1", "a%23b", "a%3Fb", "a%2Fb", "a".repeat(100), "a%20b").forEach { roomId ->
                    val response = client.room(roomId, token)
                    response.status shouldBe HttpStatusCode.BadRequest
                    response.headers[HttpHeaders.Location] shouldBe null
                    response.headers[HttpHeaders.SetCookie] shouldBe null
                    response.bodyAsText() shouldNotContain "Injected"
                    response.bodyAsText() shouldNotContain "aaaa"
                }
            }
        }

        test("meeting: repeated missing-header calls trip the IP failure budget (429)") {
            testApplication {
                mountRoute(cookieSecure = false, failureLimiter = LoginRateLimiter(maxFailures = 3, window = 15.minutes))
                val client = noRedirectClient()

                val statuses = (1..6).map { client.room("room-abc-123").status }

                statuses shouldContain HttpStatusCode.TooManyRequests
            }
        }

        test("meeting: a successful call does NOT reset the IP failure budget") {
            testApplication {
                mountRoute(cookieSecure = false, failureLimiter = LoginRateLimiter(maxFailures = 3, window = 15.minutes))
                val client = noRedirectClient()
                val good = createMemberWithSession()

                client.room("room-abc-123", "bad-1").status shouldBe HttpStatusCode.Unauthorized
                client.room("room-abc-123", "bad-2").status shouldBe HttpStatusCode.Unauthorized
                client.room("room-abc-123", good).status shouldBe HttpStatusCode.Found
                client.room("room-abc-123", "bad-3").status shouldBe HttpStatusCode.Unauthorized
                // IP budget is exhausted (3 failures); had the success reset it, this would be 302.
                client.room("room-abc-123", good).status shouldBe HttpStatusCode.TooManyRequests
            }
        }

        test("meeting: rotating distinct invalid tokens still trips the IP failure budget (429)") {
            testApplication {
                mountRoute(cookieSecure = false, failureLimiter = LoginRateLimiter(maxFailures = 3, window = 15.minutes))
                val client = noRedirectClient()

                val statuses = (1..6).map { client.room("room-abc-123", "attacker-token-$it").status }

                statuses.take(3).forEach { it shouldBe HttpStatusCode.Unauthorized }
                statuses.drop(3).forEach { it shouldBe HttpStatusCode.TooManyRequests }
            }
        }

        test("section: a successful call does NOT reset the IP failure budget either") {
            testApplication {
                mountRoute(cookieSecure = false, failureLimiter = LoginRateLimiter(maxFailures = 2, window = 15.minutes))
                val client = noRedirectClient()
                val good = createMemberWithSession()

                client.section("dashboard", "bad-1").status shouldBe HttpStatusCode.Unauthorized
                client.section("dashboard", good).status shouldBe HttpStatusCode.Found
                client.section("dashboard", "bad-2").status shouldBe HttpStatusCode.Unauthorized
                client.section("dashboard", good).status shouldBe HttpStatusCode.TooManyRequests
            }
        }

        test("meeting: the request-rate guard caps even valid calls (429)") {
            testApplication {
                mountRoute(
                    cookieSecure = false,
                    requestLimiter = FederationInboxRateLimiter(maxRequests = 2, window = 1.minutes),
                )
                val client = noRedirectClient()
                val token = createMemberWithSession()

                client.room("room-abc-123", token).status shouldBe HttpStatusCode.Found
                client.room("room-abc-123", token).status shouldBe HttpStatusCode.Found
                client.room("room-abc-123", token).status shouldBe HttpStatusCode.TooManyRequests
            }
        }

        // ---------------------------------------------------------------- section route

        test("section: every allowlisted key redirects to its mapped target with the full cookie contract") {
            testApplication {
                mountRoute(cookieSecure = false)
                val client = noRedirectClient()
                val token = createMemberWithSession()

                MOBILE_SECTION_TARGETS.forEach { (key, target) ->
                    val response = client.section(key, token)
                    response.status shouldBe HttpStatusCode.Found
                    response.headers[HttpHeaders.Location] shouldBe target
                    assertBridgeCookie(response.headers[HttpHeaders.SetCookie], expectedToken = token, expectSecure = false)
                }
            }
        }

        test("section: allowlist contract is pinned (exact key set, documents and volunteer-shifts mappings)") {
            MOBILE_SECTION_TARGETS.keys shouldBe
                setOf(
                    "dashboard",
                    "contributions",
                    "documents",
                    "volunteer-shifts",
                    "committees",
                    "meetings",
                    "motions",
                    "events",
                    "conference",
                )
            MOBILE_SECTION_TARGETS["documents"] shouldBe "/app#/documents"
            MOBILE_SECTION_TARGETS["volunteer-shifts"] shouldBe "/app#/my-volunteer-shifts"
        }

        test("section: unknown or malicious section values are 400, never reflected, no cookie") {
            testApplication {
                mountRoute(cookieSecure = false)
                val client = noRedirectClient()
                val token = createMemberWithSession()

                listOf("https://evil.example", "//evil.example", "/app#/admin", "../x", "documents/../x", "Documents", " documents")
                    .forEach { value ->
                        val response = client.section(value, token)
                        response.status shouldBe HttpStatusCode.BadRequest
                        response.headers[HttpHeaders.Location] shouldBe null
                        response.headers[HttpHeaders.SetCookie] shouldBe null
                        response.bodyAsText() shouldNotContain "evil"
                        response.bodyAsText() shouldNotContain "admin"
                    }
            }
        }

        test("section: a missing section parameter with a valid header is 400 without a cookie") {
            testApplication {
                mountRoute(cookieSecure = false)
                val token = createMemberWithSession()

                val response = noRedirectClient().section(section = null, token = token)

                response.status shouldBe HttpStatusCode.BadRequest
                response.headers[HttpHeaders.SetCookie] shouldBe null
            }
        }

        test("section: capability probe with a valid header is 400 (route present), value not reflected, no cookie") {
            testApplication {
                mountRoute(cookieSecure = false)
                val token = createMemberWithSession()

                val response = noRedirectClient().section(MOBILE_WEBVIEW_CAPABILITY_PROBE_SECTION, token)

                response.status shouldBe HttpStatusCode.BadRequest
                response.headers[HttpHeaders.SetCookie] shouldBe null
                response.bodyAsText() shouldNotContain "capability"
            }
        }

        test("section: capability probe WITHOUT a header is 401, not 400 (session gate precedes target resolution)") {
            testApplication {
                mountRoute(cookieSecure = false)

                val response = noRedirectClient().section(MOBILE_WEBVIEW_CAPABILITY_PROBE_SECTION)

                response.status shouldBe HttpStatusCode.Unauthorized
                response.headers[HttpHeaders.SetCookie] shouldBe null
            }
        }

        test("section: capability probe with ?token= instead of the header is 401") {
            testApplication {
                mountRoute(cookieSecure = false)
                val token = createMemberWithSession()

                val response =
                    noRedirectClient().section(MOBILE_WEBVIEW_CAPABILITY_PROBE_SECTION) {
                        parameter("token", token)
                    }

                response.status shouldBe HttpStatusCode.Unauthorized
                response.headers[HttpHeaders.SetCookie] shouldBe null
            }
        }

        test("section: no header, wrong scheme, or ?token= alone are 401 without a cookie") {
            testApplication {
                mountRoute(cookieSecure = false)
                val client = noRedirectClient()
                val token = createMemberWithSession()

                val responses =
                    listOf(
                        client.section("dashboard"),
                        client.section("dashboard") { header(HttpHeaders.Authorization, "Basic $token") },
                        client.section("dashboard") { parameter("token", token) },
                    )
                responses.forEach {
                    it.status shouldBe HttpStatusCode.Unauthorized
                    it.headers[HttpHeaders.SetCookie] shouldBe null
                }
            }
        }

        test("section: HTTPS gate -- cookieSecure=true rejects plain HTTP with 400 for a valid header, no cookie") {
            testApplication {
                mountRoute(cookieSecure = true)
                val token = createMemberWithSession()

                val response = noRedirectClient().section("dashboard", token)

                response.status shouldBe HttpStatusCode.BadRequest
                response.headers[HttpHeaders.SetCookie] shouldBe null
            }
        }
    })
