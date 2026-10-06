package network.lapis.cloud.server.routes

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import io.ktor.client.HttpClient
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.parseServerSetCookieHeader
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.datetime.LocalDate
import network.lapis.cloud.server.db.generated.AccountTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.server.db.generated.SessionTable
import network.lapis.cloud.server.federation.FederationInboxRateLimiter
import network.lapis.cloud.server.security.LoginRateLimiter
import network.lapis.cloud.server.security.SessionStore
import network.lapis.cloud.server.testdb.TestDatabase
import network.lapis.cloud.server.testdb.installLaneGuards
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.MemberStatus
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.time.Duration.Companion.minutes
import kotlin.uuid.Uuid

/**
 * V1.9.62 Begegnungsraum (B2) -- the WebView bridge route of one encounter space
 * (`/api/mobile/v1/encounter/spaces/{spaceId}/webview-session`) plus the `encounter` section key, on H2 and on a real PostgreSQL (the
 * session store runs real transactions). Scenario spec of the Postgres lane: it never calls `module()` or `DatabaseConfig.connect()` itself
 * (`TestDatabase.activate()` does, for H2); the routes are mounted in isolation with their own limiter instances.
 *
 * Pinned: a valid header redirects to exactly `/app#/begegnung/<id>` with the full cookie contract; `?token=` never authenticates;
 * an id outside the allowlist charset is a fixed 400 that reflects nothing and sets no cookie; the failure budget behaves like the
 * conference route's; the `encounter` section key maps to the room list.
 */
abstract class MobileEncounterWebviewSessionScenarios(
    private val db: TestDatabase,
) : FunSpec({
        val createdMemberIds = mutableListOf<Uuid>()

        beforeSpec { db.activate() }
        installLaneGuards(db = db)
        afterSpec {
            try {
                transaction {
                    if (createdMemberIds.isNotEmpty()) {
                        SessionTable.deleteWhere { SessionTable.memberId inList createdMemberIds }
                        AccountTable.deleteWhere { AccountTable.memberId inList createdMemberIds }
                        MemberTable.deleteWhere { MemberTable.id inList createdMemberIds }
                    }
                }
            } finally {
                db.deactivate()
            }
        }

        fun createMemberWithSession(): String {
            val id = Uuid.random()
            transaction {
                MemberTable.insert {
                    it[MemberTable.id] = id
                    it[displayName] = "Encounter-Bridge Testmitglied"
                    it[email] = "encounter-bridge-${Uuid.random()}@example.org"
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

        fun ApplicationTestBuilder.mountRoute(
            failureLimiter: LoginRateLimiter = LoginRateLimiter(maxFailures = 30, window = 15.minutes),
            requestLimiter: FederationInboxRateLimiter = FederationInboxRateLimiter(maxRequests = 1_000, window = 1.minutes),
        ) {
            application {
                routing {
                    registerMobileWebviewSessionRoutes(
                        cookieSecure = false,
                        failureLimiter = failureLimiter,
                        requestLimiter = requestLimiter,
                    )
                }
            }
        }

        fun ApplicationTestBuilder.noRedirectClient(): HttpClient = createClient { followRedirects = false }

        suspend fun HttpClient.space(
            spacePath: String,
            token: String? = null,
            block: HttpRequestBuilder.() -> Unit = {},
        ) = get("/api/mobile/v1/encounter/spaces/$spacePath/webview-session") {
            if (token != null) header(HttpHeaders.Authorization, "Bearer $token")
            block()
        }

        test("a valid header redirects to exactly /app#/begegnung/<id> and mints the bridge cookie") {
            testApplication {
                mountRoute()
                val token = createMemberWithSession()
                val spaceId = Uuid.random().toString()

                val response = noRedirectClient().space(spaceId, token)

                response.status shouldBe HttpStatusCode.Found
                response.headers[HttpHeaders.Location] shouldBe "/app#/begegnung/$spaceId"
                val cookie = parseServerSetCookieHeader(requireNotNull(response.headers[HttpHeaders.SetCookie]))
                cookie.name shouldBe "lapis_session"
                cookie.value shouldBe token
                cookie.httpOnly shouldBe true
                cookie.path shouldBe "/"
                cookie.extensions["SameSite"] shouldBe "Strict"
            }
        }

        test("a ?token= query parameter alone never authenticates (401, no cookie)") {
            testApplication {
                mountRoute()
                val token = createMemberWithSession()

                val response = noRedirectClient().space("some-space") { parameter("token", token) }

                response.status shouldBe HttpStatusCode.Unauthorized
                response.headers[HttpHeaders.SetCookie] shouldBe null
            }
        }

        test("no header is 401, a foreign lapis_session cookie without a header neither authenticates nor sets a cookie") {
            testApplication {
                mountRoute()
                val victimCookieToken = createMemberWithSession()
                val client = noRedirectClient()

                client.space("some-space").status shouldBe HttpStatusCode.Unauthorized
                val response = client.space("some-space") { header(HttpHeaders.Cookie, "lapis_session=$victimCookieToken") }
                response.status shouldBe HttpStatusCode.Unauthorized
                response.headers[HttpHeaders.SetCookie] shouldBe null
            }
        }

        test("an id outside the allowlist charset (traversal, header injection, 65 characters) is a fixed 400 that reflects nothing") {
            testApplication {
                mountRoute()
                val token = createMemberWithSession()
                val client = noRedirectClient()

                listOf("..%2Fx", "a%0d%0aX-Injected:1", "a%23b", "a%3Fb", "a%20b", "a".repeat(65)).forEach { spaceId ->
                    val response = client.space(spaceId, token)
                    response.status shouldBe HttpStatusCode.BadRequest
                    response.headers[HttpHeaders.Location] shouldBe null
                    response.headers[HttpHeaders.SetCookie] shouldBe null
                    response.bodyAsText() shouldNotContain "Injected"
                    response.bodyAsText() shouldNotContain "aaaa"
                }
            }
        }

        test("a 64-character id is still accepted (the boundary of the allowlist)") {
            testApplication {
                mountRoute()
                val token = createMemberWithSession()

                val response = noRedirectClient().space("a".repeat(64), token)

                response.status shouldBe HttpStatusCode.Found
                response.headers[HttpHeaders.Location] shouldBe "/app#/begegnung/${"a".repeat(64)}"
            }
        }

        test("repeated missing-header calls trip the IP failure budget (429) and a success does not reset it") {
            testApplication {
                mountRoute(failureLimiter = LoginRateLimiter(maxFailures = 3, window = 15.minutes))
                val client = noRedirectClient()
                val good = createMemberWithSession()

                client.space("some-space", "bad-1").status shouldBe HttpStatusCode.Unauthorized
                client.space("some-space", "bad-2").status shouldBe HttpStatusCode.Unauthorized
                client.space("some-space", good).status shouldBe HttpStatusCode.Found
                client.space("some-space", "bad-3").status shouldBe HttpStatusCode.Unauthorized
                // The IP budget is exhausted: had the success reset it, this would be 302.
                val statuses = listOf(client.space("some-space", good).status)
                statuses shouldContain HttpStatusCode.TooManyRequests
            }
        }

        test("the encounter section key redirects to the room list with the full cookie contract") {
            testApplication {
                mountRoute()
                val token = createMemberWithSession()

                val response =
                    noRedirectClient().get("/api/mobile/v1/webview-session") {
                        parameter("section", "encounter")
                        header(HttpHeaders.Authorization, "Bearer $token")
                    }

                response.status shouldBe HttpStatusCode.Found
                response.headers[HttpHeaders.Location] shouldBe "/app#/begegnung"
                parseServerSetCookieHeader(requireNotNull(response.headers[HttpHeaders.SetCookie])).value shouldBe token
            }
        }
    })
