package network.lapis.cloud.server.routes

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.datetime.LocalDate
import network.lapis.cloud.server.db.DatabaseConfig
import network.lapis.cloud.server.db.generated.AccountTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.server.db.generated.SessionTable
import network.lapis.cloud.server.security.LoginRateLimiter
import network.lapis.cloud.server.security.SessionStore
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.MemberStatus
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.uuid.Uuid

/**
 * V1.5.1 Mobile App -- [registerMobileWebviewSessionRoutes], the one-time Bearer-token-to-cookie
 * translation the native app's WebView navigates to before loading `/app#/conference/{roomId}`
 * (see that function's own KDoc for the full security contract this exercises: HTTPS gate,
 * rate-limiting, exactly-once cookie mint). Review-Runde-2 MAJOR finding -- this route had NO test
 * coverage at all before this file; it is exactly the security-relevant "token in a URL" bridge
 * CLAUDE.md's "Testabdeckung -- verbindlich" rule calls out for mandatory negative/tamper tests.
 *
 * Mounted in isolation (own [LoginRateLimiter] instance per test, not the full [network.lapis.cloud
 * .server.module]) -- identical idiom to [AuthRoutesTest]'s own password-reset tests, which mount
 * [registerAuthRoutes] directly so each test gets an independent rate-limiter budget.
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

        fun ApplicationTestBuilder.mountRoute(
            cookieSecure: Boolean,
            rateLimiter: LoginRateLimiter = LoginRateLimiter(),
        ) {
            application {
                routing {
                    registerMobileWebviewSessionRoutes(cookieSecure = cookieSecure, rateLimiter = rateLimiter)
                }
            }
        }

        test("missing token query parameter is rejected with 400, before any rate-limit/DB lookup") {
            testApplication {
                mountRoute(cookieSecure = false)

                val response = client.get("/api/mobile/v1/conference/rooms/some-room/webview-session")
                response.status shouldBe HttpStatusCode.BadRequest
                response.bodyAsText() shouldContain "roomId and token are required"
            }
        }

        test("HTTPS gate: cookieSecure=true rejects a plain-HTTP request with 400, before touching the token or rate limiter at all") {
            testApplication {
                mountRoute(cookieSecure = true)

                // ktor's test client talks plain HTTP by default -- call.request.origin.scheme is
                // "http", exactly the case registerMobileWebviewSessionRoutes' KDoc says this gate
                // exists for (a query-parameter token must never travel unencrypted).
                val response = client.get("/api/mobile/v1/conference/rooms/some-room/webview-session?token=whatever-token")
                response.status shouldBe HttpStatusCode.BadRequest
                response.bodyAsText() shouldContain "HTTPS required"
            }
        }

        test("an invalid/never-issued token is rejected with 401 and never sets a cookie") {
            testApplication {
                mountRoute(cookieSecure = false)

                val response =
                    client.get("/api/mobile/v1/conference/rooms/some-room/webview-session?token=this-token-was-never-issued")
                response.status shouldBe HttpStatusCode.Unauthorized
                response.bodyAsText() shouldContain "Invalid or expired session"
                response.headers[HttpHeaders.SetCookie] shouldBe null
            }
        }

        test("an already-revoked (logged-out) token is rejected with 401 -- SessionStore.resolve, not a naive presence check") {
            testApplication {
                mountRoute(cookieSecure = false)

                val rawToken = createMemberWithSession()
                SessionStore.revoke(rawToken)

                val response = client.get("/api/mobile/v1/conference/rooms/some-room/webview-session?token=$rawToken")
                response.status shouldBe HttpStatusCode.Unauthorized
            }
        }

        test("repeated invalid tokens from the same caller trip the rate limiter -- eventually 429, not endless 401s") {
            testApplication {
                mountRoute(cookieSecure = false, rateLimiter = LoginRateLimiter())

                val statuses =
                    (1..10).map {
                        client
                            .get("/api/mobile/v1/conference/rooms/some-room/webview-session?token=invalid-token-$it")
                            .status
                    }
                statuses shouldContain HttpStatusCode.TooManyRequests
            }
        }

        test(
            "a valid token mints the lapis_session cookie with HttpOnly/SameSite=Lax (no Secure when cookieSecure=false) " +
                "and redirects to /app#/conference/{roomId} -- never mints a NEW token, only translates the existing one",
        ) {
            testApplication {
                mountRoute(cookieSecure = false)
                // The default ktor test client follows redirects automatically -- that would hide
                // the 302 response (and its Set-Cookie/Location headers) we actually need to
                // inspect here, same idiom PublicLandingRoutesTest's own redirect test uses.
                val noRedirectClient = createClient { followRedirects = false }

                val rawToken = createMemberWithSession()

                val response = noRedirectClient.get("/api/mobile/v1/conference/rooms/room-abc-123/webview-session?token=$rawToken")

                response.status shouldBe HttpStatusCode.Found
                response.headers[HttpHeaders.Location] shouldBe "/app#/conference/room-abc-123"

                val setCookie = requireNotNull(response.headers[HttpHeaders.SetCookie])
                setCookie shouldContain "lapis_session=$rawToken"
                setCookie shouldContain "HttpOnly"
                setCookie shouldContain "SameSite=Lax"
                setCookie shouldNotContain "Secure"
            }
        }

        test(
            "cookieSecure=true means a valid token over plain HTTP is STILL rejected by the HTTPS gate -- " +
                "no cookie is ever minted over an unencrypted connection, even with an otherwise-valid session",
        ) {
            testApplication {
                mountRoute(cookieSecure = true)

                val rawToken = createMemberWithSession()

                // ktor's test client talks plain HTTP -- this proves the gate is evaluated BEFORE
                // token resolution regardless of whether the token would otherwise have been
                // valid, i.e. cookieSecure=true has no bypass for an authenticated caller.
                val response = client.get("/api/mobile/v1/conference/rooms/room-abc-123/webview-session?token=$rawToken")
                response.status shouldBe HttpStatusCode.BadRequest
                response.headers[HttpHeaders.SetCookie] shouldBe null
            }
        }
    })
