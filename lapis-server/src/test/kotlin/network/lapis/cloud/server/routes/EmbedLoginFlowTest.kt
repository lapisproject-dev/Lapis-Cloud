package network.lapis.cloud.server.routes

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.forwardedheaders.XForwardedHeaders
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.response.respondText
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.datetime.LocalDate
import network.lapis.cloud.server.db.DatabaseConfig
import network.lapis.cloud.server.db.DevSeedData
import network.lapis.cloud.server.db.generated.AccountTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.server.db.generated.SessionTable
import network.lapis.cloud.server.embed.EmbedConfig
import network.lapis.cloud.server.embed.EmbedOriginAllowlist
import network.lapis.cloud.server.federation.FederationInboxRateLimiter
import network.lapis.cloud.server.mail.PasswordResetMailer
import network.lapis.cloud.server.security.LoginRateLimiter
import network.lapis.cloud.server.security.PasswordHasher
import network.lapis.cloud.server.security.SessionStore
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.DeliveryStatus
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.rpc.ForbiddenException
import network.lapis.cloud.shared.rpc.UnauthenticatedException
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.time.Duration.Companion.minutes
import kotlin.uuid.Uuid

/**
 * Welle V1.4.1a "Öffentliche Website-Integration" -- der wichtigste Testfall der Welle:
 * `POST /api/auth/login` bleibt SameSite=Strict/HttpOnly/Secure UNVERÄNDERT, auch bei aktiviertem
 * Embed und einem gesetzten `Origin`-Header einer erlaubten Embed-Origin. Zusätzlich der komplette
 * Popup-Login-Flow (`GET /embed/v1/login`, `GET /api/embed/v1/session`).
 */
class EmbedLoginFlowTest :
    FunSpec({
        val createdMemberIds = mutableListOf<Uuid>()

        beforeSpec {
            DatabaseConfig.connect()
            DevSeedData.seedIfEmpty(force = true)
        }

        afterSpec {
            transaction {
                if (createdMemberIds.isNotEmpty()) {
                    // SessionTable rows (created via SessionStore.createSession in the session-probe
                    // test, or via a real POST /api/auth/login) must be deleted FIRST -- fk_session_member_id.
                    SessionTable.deleteWhere { memberId inList createdMemberIds }
                    AccountTable.deleteWhere { memberId inList createdMemberIds }
                    MemberTable.deleteWhere { id inList createdMemberIds }
                }
            }
        }

        fun createMemberWithPassword(
            email: String,
            password: String,
            displayName: String = "Embed-Test-Mitglied",
        ): Uuid {
            val id = Uuid.random()
            transaction {
                MemberTable.insert {
                    it[MemberTable.id] = id
                    it[MemberTable.displayName] = displayName
                    it[MemberTable.email] = email
                    it[status] = MemberStatus.ACTIVE
                    it[joinedAt] = LocalDate(2026, 1, 1)
                    it[membershipTierId] = null
                }
                AccountTable.insert {
                    it[AccountTable.id] = Uuid.random()
                    it[memberId] = id
                    it[role] = AccountRole.MEMBER
                    it[passwordHash] = PasswordHasher.hash(password)
                }
            }
            createdMemberIds += id
            return id
        }

        fun generousLimiter() = FederationInboxRateLimiter(maxRequests = 10_000, window = 1.minutes)

        val enabledConfig =
            EmbedConfig(
                enabled = true,
                allowlist = EmbedOriginAllowlist.parse(raw = "https://partei.example", allowInsecure = false).allowlist,
                allowInsecureOrigins = false,
            )

        suspend fun testApp(block: suspend ApplicationTestBuilder.() -> Unit) {
            testApplication {
                application {
                    install(XForwardedHeaders) { useLastProxy() }
                    install(ContentNegotiation) { json() }
                    install(StatusPages) {
                        exception<UnauthenticatedException> {
                            call,
                            cause,
                            ->
                            call.respondText(cause.message, status = HttpStatusCode.Unauthorized)
                        }
                        exception<ForbiddenException> { call, cause -> call.respondText(cause.message, status = HttpStatusCode.Forbidden) }
                    }
                    routing {
                        registerAuthRoutes(
                            rateLimiter = LoginRateLimiter(),
                            cookieSecure = true,
                            passwordResetRateLimiter = LoginRateLimiter(),
                            passwordResetMailer = NoopMailer,
                            friendEmailVerifyRateLimiter = LoginRateLimiter(),
                        )
                        registerEmbedRoutes(
                            config = enabledConfig,
                            assetRateLimiter = generousLimiter(),
                            loginPageRateLimiter = generousLimiter(),
                            sessionRateLimiter = generousLimiter(),
                            adminStatusRateLimiter = generousLimiter(),
                        )
                    }
                }
                block()
            }
        }

        test("POST /api/auth/login sets HttpOnly/Secure/SameSite=Strict even with an allowed embed Origin header, embed enabled") {
            testApp {
                val email = "embed-login-flow@example.org"
                createMemberWithPassword(email = email, password = "correct horse battery staple 42")
                val response =
                    client.post("/api/auth/login") {
                        header(HttpHeaders.Origin, "https://partei.example")
                        setBody("""{"email":"$email","password":"correct horse battery staple 42"}""")
                    }
                response.status shouldBe HttpStatusCode.OK
                val setCookie = response.headers[HttpHeaders.SetCookie].orEmpty()
                setCookie shouldContain "HttpOnly"
                setCookie shouldContain "Secure"
                setCookie shouldContain "SameSite=Strict"
                response.headers[HttpHeaders.AccessControlAllowOrigin] shouldBe null
            }
        }

        test("GET /embed/v1/login with an unknown origin: 403, the value is echoed NOWHERE in the body") {
            testApp {
                val response = client.get("/embed/v1/login?state=${"a".repeat(32)}&origin=https%3A%2F%2Fevil.example")
                response.status shouldBe HttpStatusCode.Forbidden
                val body = response.bodyAsText()
                body shouldNotContain "evil.example"
            }
        }

        test(
            "GET /embed/v1/login with an allowed origin: 200, host of the allowlist entry in the body, data-target-origin is the canonical entry",
        ) {
            testApp {
                val response = client.get("/embed/v1/login?state=${"a".repeat(32)}&origin=https%3A%2F%2Fpartei.example")
                response.status shouldBe HttpStatusCode.OK
                val body = response.bodyAsText()
                body shouldContain "partei.example"
                body shouldContain "data-target-origin=\"https://partei.example\""
            }
        }

        test("malformed state (too short, non-hex, script injection attempt): 400, never echoed") {
            testApp {
                for (badState in listOf("abc", "g".repeat(32), "\"><script>")) {
                    val response =
                        client.get(
                            "/embed/v1/login?state=${java.net.URLEncoder.encode(badState, "UTF-8")}&origin=https%3A%2F%2Fpartei.example",
                        )
                    response.status shouldBe HttpStatusCode.BadRequest
                    response.bodyAsText() shouldNotContain "<script>"
                }
            }
        }

        test("CSP header is exactly the specified string, no unsafe-inline") {
            testApp {
                val response = client.get("/embed/v1/login?state=${"a".repeat(32)}&origin=https%3A%2F%2Fpartei.example")
                val csp = response.headers["Content-Security-Policy"].orEmpty()
                csp shouldContain "default-src 'none'"
                csp shouldContain "script-src 'self'"
                csp shouldContain "style-src 'self'"
                csp shouldContain "connect-src 'self'"
                csp shouldContain "form-action 'none'"
                csp shouldContain "frame-ancestors 'none'"
                csp shouldNotContain "unsafe-inline"
            }
        }

        test("GET /api/embed/v1/session without a cookie: 200 signedIn:false") {
            testApp {
                val response = client.get("/api/embed/v1/session")
                response.status shouldBe HttpStatusCode.OK
                response.bodyAsText() shouldBe """{"signedIn":false,"displayName":null}"""
                response.headers[HttpHeaders.CacheControl] shouldBe "no-store"
            }
        }

        test("GET /api/embed/v1/session with a valid same-origin cookie: signedIn:true with the display name") {
            testApp {
                val memberId = createMemberWithPassword(email = "embed-session-probe@example.org", password = "another-strong-password-99")
                val issued = SessionStore.createSession(memberId)
                val response = client.get("/api/embed/v1/session") { header(HttpHeaders.Cookie, "lapis_session=${issued.rawToken}") }
                response.status shouldBe HttpStatusCode.OK
                response.bodyAsText() shouldContain "\"signedIn\":true"
                response.bodyAsText() shouldContain "Embed-Test-Mitglied"
            }
        }

        test("no-store on all three response types: login page, session probe, rejected page") {
            testApp {
                client
                    .get("/embed/v1/login?state=${"a".repeat(32)}&origin=https%3A%2F%2Fpartei.example")
                    .headers[HttpHeaders.CacheControl] shouldBe "no-store"
                client.get("/api/embed/v1/session").headers[HttpHeaders.CacheControl] shouldBe "no-store"
                client
                    .get("/embed/v1/login?state=${"a".repeat(32)}&origin=https%3A%2F%2Fevil.example")
                    .headers[HttpHeaders.CacheControl] shouldBe "no-store"
            }
        }
    })

private object NoopMailer : PasswordResetMailer {
    override fun send(
        email: String,
        rawToken: String,
    ): DeliveryStatus = DeliveryStatus.SENT
}
