package network.lapis.cloud.server.routes

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.options
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.install
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
import network.lapis.cloud.server.security.SessionStore
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.DeliveryStatus
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.rpc.ForbiddenException
import network.lapis.cloud.shared.rpc.UnauthenticatedException
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.time.Duration.Companion.minutes
import kotlin.uuid.Uuid

/**
 * Welle V1.4.1a "Öffentliche Website-Integration" -- CORS behaviour of the embed API surface, and
 * a NICHT-REGRESSION check that no OTHER public route family accidentally started carrying CORS
 * headers as a side effect of this welle. Hand-wired `routing { }` block, same
 * `PublicTransparencyRoutesTest`-established pattern -- `EmbedConfig` is env-driven and therefore
 * not settable through the real `module()` in a test.
 */
class EmbedRoutesCorsTest :
    FunSpec({
        val createdMemberIds = mutableListOf<Uuid>()

        beforeSpec {
            DatabaseConfig.connect()
            DevSeedData.seedIfEmpty(force = true)
        }

        afterSpec {
            transaction {
                if (createdMemberIds.isNotEmpty()) {
                    SessionTable.deleteWhere { memberId inList createdMemberIds }
                    AccountTable.deleteWhere { memberId inList createdMemberIds }
                    MemberTable.deleteWhere { id inList createdMemberIds }
                }
            }
        }

        fun createAdminSessionCookie(email: String): Pair<Uuid, String> {
            val id = Uuid.random()
            transaction {
                MemberTable.insert {
                    it[MemberTable.id] = id
                    it[displayName] = "Embed-CORS-Test-ADMIN"
                    it[MemberTable.email] = email
                    it[status] = MemberStatus.ACTIVE
                    it[joinedAt] = LocalDate(2026, 1, 1)
                    it[membershipTierId] = null
                }
                AccountTable.insert {
                    it[AccountTable.id] = Uuid.random()
                    it[memberId] = id
                    it[role] = AccountRole.ADMIN
                    it[passwordHash] = null
                }
            }
            createdMemberIds += id
            return id to SessionStore.createSession(id).rawToken
        }

        /**
         * Deletes a member created by [createAdminSessionCookie] immediately, instead of leaving it
         * for [afterSpec] to sweep up at the very end of the whole spec run. `afterSpec` above stays
         * in place as a safety net for any test that does NOT call this. Review-Fund V1.4.1a MINOR:
         * this file's only ADMIN-role test fixture used to stay alive for the entire spec run,
         * widening the window in which it could overlap `MemberAdministrationTest`'s "two ADMINs
         * concurrently demoting each other" race test -- that test's own neutralization only
         * accounts for ADMINs *it* created, so a third, foreign ADMIN alive at the same moment lets
         * both concurrent demotions see more than two ADMINs and both succeed, instead of the
         * expected one-OK/one-Conflict split. Narrowing this fixture's lifetime to a single test
         * body does not eliminate the shared-database race (five other, older specs create ADMIN
         * accounts of their own, and Kotest may run specs concurrently in one JVM -- see
         * `DevSeedData.kt`'s own KDoc), but it meaningfully shrinks this file's contribution to it.
         */
        fun deleteTestMember(id: Uuid) {
            transaction {
                SessionTable.deleteWhere { memberId eq id }
                AccountTable.deleteWhere { memberId eq id }
                MemberTable.deleteWhere { MemberTable.id eq id }
            }
        }

        fun generousLimiter() = FederationInboxRateLimiter(maxRequests = 10_000, window = 1.minutes)

        val enabledConfig =
            EmbedConfig(
                enabled = true,
                allowlist = EmbedOriginAllowlist.parse(raw = "https://partei.example", allowInsecure = false).allowlist,
                allowInsecureOrigins = false,
            )

        suspend fun testApp(
            config: EmbedConfig = enabledConfig,
            withOtherPublicRoutes: Boolean = false,
            sessionRateLimiter: FederationInboxRateLimiter = generousLimiter(),
            block: suspend ApplicationTestBuilder.() -> Unit,
        ) {
            testApplication {
                application {
                    install(XForwardedHeaders) { useLastProxy() }
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
                        registerEmbedRoutes(
                            config = config,
                            assetRateLimiter = generousLimiter(),
                            loginPageRateLimiter = generousLimiter(),
                            sessionRateLimiter = sessionRateLimiter,
                            adminStatusRateLimiter = generousLimiter(),
                        )
                        if (withOtherPublicRoutes) {
                            registerAuthRoutes(
                                rateLimiter = LoginRateLimiter(),
                                cookieSecure = true,
                                passwordResetRateLimiter = LoginRateLimiter(),
                                passwordResetMailer = NoopPasswordResetMailer,
                                friendEmailVerifyRateLimiter = LoginRateLimiter(),
                            )
                            registerSocialPublicRoutes(
                                readRateLimiter = generousLimiter(),
                                sitemapRateLimiter = generousLimiter(),
                                reportRateLimiter = generousLimiter(),
                            )
                            registerPublicTransparencyRoutes(readRateLimiter = generousLimiter())
                            registerPublicApiRoutes(preAuthRateLimiter = generousLimiter(), postAuthRateLimiter = generousLimiter())
                        }
                    }
                }
                block()
            }
        }

        test("allowed Origin: Access-Control-Allow-Origin is the CANONICAL allowlist entry, Vary: Origin is set") {
            testApp {
                val response = client.get("/api/embed/v1/session") { header(HttpHeaders.Origin, "https://partei.example") }
                response.status shouldBe HttpStatusCode.OK
                response.headers[HttpHeaders.AccessControlAllowOrigin] shouldBe "https://partei.example"
                response.headers[HttpHeaders.Vary] shouldBe "Origin"
            }
        }

        test("Access-Control-Allow-Credentials is absent from EVERY response, allowed or not") {
            testApp {
                val allowed = client.get("/api/embed/v1/session") { header(HttpHeaders.Origin, "https://partei.example") }
                allowed.headers[HttpHeaders.AccessControlAllowCredentials] shouldBe null
                val rejected = client.get("/api/embed/v1/session") { header(HttpHeaders.Origin, "https://evil.example") }
                rejected.headers[HttpHeaders.AccessControlAllowCredentials] shouldBe null
                val noOrigin = client.get("/api/embed/v1/session")
                noOrigin.headers[HttpHeaders.AccessControlAllowCredentials] shouldBe null
            }
        }

        test("disallowed Origin: 403, no CORS header, Vary: Origin still set, no oracle in the body") {
            testApp {
                val response = client.get("/api/embed/v1/session") { header(HttpHeaders.Origin, "https://evil.example") }
                response.status shouldBe HttpStatusCode.Forbidden
                response.headers[HttpHeaders.AccessControlAllowOrigin] shouldBe null
                response.headers[HttpHeaders.Vary] shouldBe "Origin"
            }
        }

        test("no Origin header: 200, no CORS header at all") {
            testApp {
                val response = client.get("/api/embed/v1/session")
                response.status shouldBe HttpStatusCode.OK
                response.headers[HttpHeaders.AccessControlAllowOrigin] shouldBe null
            }
        }

        test("preflight OPTIONS: 204 with Methods/Headers/Max-Age, and Cache-Control: no-store appears EXACTLY ONCE") {
            testApp {
                val response = client.options("/api/embed/v1/session") { header(HttpHeaders.Origin, "https://partei.example") }
                response.status shouldBe HttpStatusCode.NoContent
                response.headers[HttpHeaders.AccessControlAllowMethods] shouldBe "GET, OPTIONS"
                response.headers[HttpHeaders.AccessControlAllowHeaders] shouldBe "Content-Type"
                response.headers[HttpHeaders.AccessControlMaxAge] shouldBe "600"
                // Review-Fund V1.4.1a: applyEmbedCors() and respondEmbedPreflight() both used to set
                // this header -- response.header() APPENDS, so it used to show up twice on the wire.
                response.headers.getAll(HttpHeaders.CacheControl) shouldBe listOf("no-store")
            }
        }

        test("preflight OPTIONS is rate-limited just like its GET counterpart (Review-Fund V1.4.1a)") {
            val strictLimiter = FederationInboxRateLimiter(maxRequests = 1, window = 1.minutes, maxTrackedKeys = 50_000)
            testApp(sessionRateLimiter = strictLimiter) {
                val first = client.options("/api/embed/v1/session") { header(HttpHeaders.Origin, "https://partei.example") }
                first.status shouldBe HttpStatusCode.NoContent
                val second = client.options("/api/embed/v1/session") { header(HttpHeaders.Origin, "https://partei.example") }
                second.status shouldBe HttpStatusCode.TooManyRequests
            }
        }

        test("NICHT-REGRESSION: /s, /transparenz, /api/v1/members, /api/auth/login never carry CORS headers with an embed Origin present") {
            testApp(withOtherPublicRoutes = true) {
                for (path in listOf("/s", "/transparenz", "/api/v1/members")) {
                    val response = client.get(path) { header(HttpHeaders.Origin, "https://partei.example") }
                    response.headers[HttpHeaders.AccessControlAllowOrigin] shouldBe null
                    // NOT asserting Vary is absent -- /s legitimately sets "Vary: Accept-Encoding"
                    // for its own, unrelated compression reasons (SocialPublicRoutes). Only "Vary:
                    // Origin" specifically would indicate embed CORS bled into this route.
                    (response.headers[HttpHeaders.Vary] ?: "") shouldNotContain "Origin"
                }
                val loginResponse =
                    client.post("/api/auth/login") {
                        header(HttpHeaders.Origin, "https://partei.example")
                        header(HttpHeaders.ContentType, "application/json")
                        setBody("""{"email":"nobody@example.org","password":"wrong"}""")
                    }
                loginResponse.headers[HttpHeaders.AccessControlAllowOrigin] shouldBe null
            }
        }

        test("/api/embed/v1/admin/status carries NO CORS headers even with an allowed Origin, and 401s without a session") {
            testApp {
                val response = client.get("/api/embed/v1/admin/status") { header(HttpHeaders.Origin, "https://partei.example") }
                response.headers[HttpHeaders.AccessControlAllowOrigin] shouldBe null
                response.headers[HttpHeaders.Vary] shouldBe null
                response.status shouldBe HttpStatusCode.Unauthorized
            }
        }

        test(
            "config.enabled=false: every /embed and /api/embed path is 404, EXCEPT the ADMIN status " +
                "endpoint (Review-Fund V1.4.1a: it stays registered so the admin screen shows an honest " +
                "\"disabled\" status instead of an indistinguishable-from-broken 404)",
        ) {
            testApp(config = EmbedConfig.DISABLED) {
                client.get("/embed/v1/lapis-widgets.js").status shouldBe HttpStatusCode.NotFound
                client.get("/embed/v1/login").status shouldBe HttpStatusCode.NotFound
                client.get("/api/embed/v1/session").status shouldBe HttpStatusCode.NotFound
                // Unauthorized, not NotFound -- the route IS registered, resolveCurrentMember just
                // has no session cookie to resolve in this request (see the enabled-config test above
                // for the same 401-without-a-session behaviour).
                client.get("/api/embed/v1/admin/status").status shouldBe HttpStatusCode.Unauthorized
            }
        }

        test("config.enabled=false: an authenticated ADMIN sees an honest enabled:false body, not a 404") {
            testApp(config = EmbedConfig.DISABLED) {
                val (adminId, token) = createAdminSessionCookie("embed-cors-disabled-admin@example.org")
                try {
                    val response = client.get("/api/embed/v1/admin/status") { header(HttpHeaders.Cookie, "lapis_session=$token") }
                    response.status shouldBe HttpStatusCode.OK
                    response.bodyAsText() shouldContain "\"enabled\":false"
                    response.bodyAsText() shouldContain "\"allowedOrigins\":[]"
                    response.headers[HttpHeaders.CacheControl] shouldBe "no-store"
                } finally {
                    deleteTestMember(adminId)
                }
            }
        }
    })

private object NoopPasswordResetMailer : PasswordResetMailer {
    override fun send(
        email: String,
        rawToken: String,
    ): DeliveryStatus = DeliveryStatus.SENT
}
