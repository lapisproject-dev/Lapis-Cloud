package network.lapis.cloud.server.routes

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import network.lapis.cloud.server.db.DatabaseConfig
import network.lapis.cloud.server.db.DevSeedData
import network.lapis.cloud.server.db.generated.AccountTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.server.db.generated.PasswordResetTokenTable
import network.lapis.cloud.server.db.generated.SessionTable
import network.lapis.cloud.server.mail.PasswordResetMailer
import network.lapis.cloud.server.module
import network.lapis.cloud.server.security.LoginRateLimiter
import network.lapis.cloud.server.security.PasswordHasher
import network.lapis.cloud.server.security.SessionStore
import network.lapis.cloud.server.security.SessionTokens
import network.lapis.cloud.server.security.resolveCurrentMember
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.DeliveryStatus
import network.lapis.cloud.shared.domain.MemberStatus
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours
import kotlin.uuid.Uuid

private const val ADMIN_EMAIL = "amara.admin@example.org"
private const val ADMIN_ID = "00000000-0000-0000-0000-000000000001"

/**
 * Exercises `/api/auth/login` and `/api/auth/logout` end to end through the REAL, fully-wired
 * [network.lapis.cloud.server.module] (not a throwaway routing block) -- this is the test that
 * would have caught `registerAuthRoutes` never being mounted in [network.lapis.cloud.server.module].
 */
class AuthRoutesTest :
    FunSpec({
        val createdMemberIds = mutableListOf<Uuid>()

        beforeSpec {
            DatabaseConfig.connect()
            DevSeedData.seedIfEmpty(force = true)
        }

        afterSpec { cleanUpAuthRoutesTestData(createdMemberIds) }

        fun createTestMemberWithoutPassword(email: String): Uuid {
            val id = Uuid.random()
            transaction {
                MemberTable.insert {
                    it[MemberTable.id] = id
                    it[displayName] = "Auth-Routes Testmitglied ohne Passwort"
                    it[MemberTable.email] = email
                    it[status] = MemberStatus.ACTIVE
                    it[joinedAt] = LocalDate(2026, 1, 1)
                    it[membershipTierId] = null
                }
                AccountTable.insert {
                    it[AccountTable.id] = Uuid.random()
                    it[memberId] = id
                    it[role] = AccountRole.MEMBER
                    // passwordHash left unset -- nullable, models a seeded-but-never-logged-in account.
                }
            }
            createdMemberIds += id
            return id
        }

        fun createTestMemberWithPassword(
            email: String,
            password: String,
            status: MemberStatus = MemberStatus.ACTIVE,
        ): Uuid {
            val id = Uuid.random()
            transaction {
                MemberTable.insert {
                    it[MemberTable.id] = id
                    it[displayName] = "Auth-Routes Testmitglied mit Passwort"
                    it[MemberTable.email] = email
                    it[MemberTable.status] = status
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

        fun rawTokenFromSetCookie(setCookieHeader: String): String {
            val match = Regex("lapis_session=([^;]+)").find(setCookieHeader)
            return requireNotNull(match) { "no lapis_session cookie in: $setCookieHeader" }.groupValues[1]
        }

        test("login with correct demo credentials succeeds and sets a HttpOnly/Secure/SameSite=Strict session cookie") {
            testApplication {
                application { module() }

                val response =
                    client.post("/api/auth/login") {
                        setBody("""{"email":"$ADMIN_EMAIL","password":"${DevSeedData.DEMO_PASSWORD}"}""")
                    }
                response.status shouldBe HttpStatusCode.OK
                response.bodyAsText() shouldContain ADMIN_ID

                val setCookie = response.headers[HttpHeaders.SetCookie]
                requireNotNull(setCookie)
                setCookie shouldContain "lapis_session="
                setCookie shouldContain "HttpOnly"
                setCookie shouldContain "Secure"
                setCookie shouldContain "SameSite=Strict"
            }
        }

        test("the cookie issued by login actually authenticates a subsequent request via resolveCurrentMember") {
            testApplication {
                application {
                    module()
                    routing {
                        get("/test/whoami") {
                            val current = resolveCurrentMember(call)
                            call.respondText(current.memberId.toString())
                        }
                    }
                }

                val loginResponse =
                    client.post("/api/auth/login") {
                        setBody("""{"email":"$ADMIN_EMAIL","password":"${DevSeedData.DEMO_PASSWORD}"}""")
                    }
                val setCookie = requireNotNull(loginResponse.headers[HttpHeaders.SetCookie])
                val rawToken = rawTokenFromSetCookie(setCookie)

                val whoamiResponse =
                    client.get("/test/whoami") { header(HttpHeaders.Cookie, "lapis_session=$rawToken") }
                whoamiResponse.status shouldBe HttpStatusCode.OK
                whoamiResponse.bodyAsText() shouldBe ADMIN_ID
            }
        }

        test("login with a wrong password is rejected with 401 and a generic message") {
            testApplication {
                application { module() }

                val response =
                    client.post("/api/auth/login") {
                        setBody("""{"email":"$ADMIN_EMAIL","password":"definitely-the-wrong-password"}""")
                    }
                response.status shouldBe HttpStatusCode.Unauthorized
                response.bodyAsText() shouldBe "Invalid credentials"
            }
        }

        test(
            "login with an unknown email is rejected with the IDENTICAL status and body as a wrong password (account-enumeration hardening)",
        ) {
            testApplication {
                application { module() }

                val unknownResponse =
                    client.post("/api/auth/login") {
                        setBody("""{"email":"no-such-account@example.org","password":"whatever-12345"}""")
                    }
                val wrongPasswordResponse =
                    client.post("/api/auth/login") {
                        setBody("""{"email":"$ADMIN_EMAIL","password":"definitely-the-wrong-password"}""")
                    }

                unknownResponse.status shouldBe wrongPasswordResponse.status
                unknownResponse.bodyAsText() shouldBe wrongPasswordResponse.bodyAsText()
            }
        }

        test("login against a seeded account with a NULL password hash (never logged in) is rejected identically, not with a 500/NPE") {
            testApplication {
                application { module() }

                val email = "auth-routes-null-hash@example.org"
                createTestMemberWithoutPassword(email)

                val response =
                    client.post("/api/auth/login") {
                        setBody("""{"email":"$email","password":"whatever-12345"}""")
                    }
                response.status shouldBe HttpStatusCode.Unauthorized
                response.bodyAsText() shouldBe "Invalid credentials"
            }
        }

        test("login with blank email/password is rejected with 400, before touching the DB or the rate limiter") {
            testApplication {
                application { module() }

                client.post("/api/auth/login") { setBody("""{"email":"","password":"whatever-12345"}""") }.status shouldBe
                    HttpStatusCode.BadRequest
                client.post("/api/auth/login") { setBody("""{"email":"$ADMIN_EMAIL","password":""}""") }.status shouldBe
                    HttpStatusCode.BadRequest
                client.post("/api/auth/login") { setBody("not even json") }.status shouldBe HttpStatusCode.BadRequest
            }
        }

        test("repeated failed logins for the same email trip the rate limiter -- eventually 429, not endless 401s") {
            testApplication {
                application { module() }

                val statuses =
                    (1..10).map {
                        client
                            .post("/api/auth/login") {
                                setBody("""{"email":"rate-limit-target@example.org","password":"wrong-$it"}""")
                            }.status
                    }
                statuses shouldContain HttpStatusCode.TooManyRequests
            }
        }

        test("logout revokes the session -- a previously valid cookie is rejected afterward") {
            testApplication {
                application {
                    module()
                    routing {
                        get("/test/whoami") {
                            val current = resolveCurrentMember(call)
                            call.respondText(current.memberId.toString())
                        }
                    }
                }

                val loginResponse =
                    client.post("/api/auth/login") {
                        setBody("""{"email":"$ADMIN_EMAIL","password":"${DevSeedData.DEMO_PASSWORD}"}""")
                    }
                val rawToken = rawTokenFromSetCookie(requireNotNull(loginResponse.headers[HttpHeaders.SetCookie]))

                client.get("/test/whoami") { header(HttpHeaders.Cookie, "lapis_session=$rawToken") }.status shouldBe
                    HttpStatusCode.OK

                val logoutResponse = client.post("/api/auth/logout") { header(HttpHeaders.Cookie, "lapis_session=$rawToken") }
                logoutResponse.status shouldBe HttpStatusCode.NoContent

                client.get("/test/whoami") { header(HttpHeaders.Cookie, "lapis_session=$rawToken") }.status shouldBe
                    HttpStatusCode.Unauthorized
            }
        }

        test("logout without any cookie still succeeds (idempotent, never leaks whether a session existed)") {
            testApplication {
                application { module() }

                client.post("/api/auth/logout").status shouldBe HttpStatusCode.NoContent
                client.post("/api/auth/logout") { header(HttpHeaders.Cookie, "lapis_session=never-issued-token") }.status shouldBe
                    HttpStatusCode.NoContent
            }
        }

        test("a stray/attacker-supplied lapis_session cookie sent alongside login is never reused as the new session's identity") {
            testApplication {
                application { module() }

                val loginResponse =
                    client.post("/api/auth/login") {
                        header(HttpHeaders.Cookie, "lapis_session=attacker-chosen-fixed-token")
                        setBody("""{"email":"$ADMIN_EMAIL","password":"${DevSeedData.DEMO_PASSWORD}"}""")
                    }
                loginResponse.status shouldBe HttpStatusCode.OK
                val issuedToken = rawTokenFromSetCookie(requireNotNull(loginResponse.headers[HttpHeaders.SetCookie]))
                issuedToken shouldNotBe "attacker-chosen-fixed-token"
            }
        }

        // ── V0.7.2 login gate ────────────────────────────────────────────────────────────

        test(
            "login against an AUSGETRETEN account with the correct password is rejected with the SAME generic message as a wrong password",
        ) {
            testApplication {
                application { module() }

                val email = "auth-routes-ausgetreten@example.org"
                val password = "a-genuinely-strong-password-1"
                createTestMemberWithPassword(email, password, status = MemberStatus.WITHDRAWN)

                val response = client.post("/api/auth/login") { setBody("""{"email":"$email","password":"$password"}""") }
                response.status shouldBe HttpStatusCode.Unauthorized
                response.bodyAsText() shouldBe "Invalid credentials"
            }
        }

        test("login against an ABGELEHNT account with the correct password is rejected with the SAME generic message as a wrong password") {
            testApplication {
                application { module() }

                val email = "auth-routes-abgelehnt@example.org"
                val password = "a-genuinely-strong-password-1"
                createTestMemberWithPassword(email, password, status = MemberStatus.REJECTED)

                val response = client.post("/api/auth/login") { setBody("""{"email":"$email","password":"$password"}""") }
                response.status shouldBe HttpStatusCode.Unauthorized
                response.bodyAsText() shouldBe "Invalid credentials"
            }
        }

        test("login against a still-ANTRAG account with the correct password still succeeds -- only AUSGETRETEN/ABGELEHNT are blocked") {
            testApplication {
                application { module() }

                val email = "auth-routes-antrag@example.org"
                val password = "a-genuinely-strong-password-1"
                createTestMemberWithPassword(email, password, status = MemberStatus.APPLICATION)

                val response = client.post("/api/auth/login") { setBody("""{"email":"$email","password":"$password"}""") }
                response.status shouldBe HttpStatusCode.OK
            }
        }

        // ── V0.7.2 password reset ───────────────────────────────────────────────────────

        test("password-reset/request: an existing and a non-existent email get the IDENTICAL response (account-enumeration hardening)") {
            testApplication {
                val mailer = CapturingPasswordResetMailer()
                application {
                    routing {
                        registerAuthRoutes(
                            rateLimiter = LoginRateLimiter(),
                            cookieSecure = true,
                            passwordResetRateLimiter = LoginRateLimiter(),
                            passwordResetMailer = mailer,
                            friendEmailVerifyRateLimiter = LoginRateLimiter(),
                        )
                    }
                }

                val email = "auth-routes-reset-existing@example.org"
                createTestMemberWithPassword(email, "a-genuinely-strong-password-1")

                val existingResponse = client.post("/api/auth/password-reset/request") { setBody("""{"email":"$email"}""") }
                val unknownResponse =
                    client.post("/api/auth/password-reset/request") { setBody("""{"email":"no-such-account@example.org"}""") }

                existingResponse.status shouldBe unknownResponse.status
                existingResponse.bodyAsText() shouldBe unknownResponse.bodyAsText()

                // Only the EXISTING email actually triggers a (simulated) send.
                mailer.lastEmail shouldBe email
            }
        }

        // V1.2.3 Echter SMTP-Versand -- a throwing mailer must never surface to the caller. This is
        // what proves the fire-and-forget contract PasswordResetMailer.send/MailDispatcher document
        // actually holds at this route: even a mailer that throws SYNCHRONOUSLY (worse than a real
        // MailDispatcher, which only ever fails asynchronously inside its own coroutine) must not
        // change `/request`'s response at all -- same generic 200, same body, regardless of email.
        test("password-reset/request: a throwing mailer never changes the response (fire-and-forget contract)") {
            testApplication {
                val mailer = ThrowingPasswordResetMailer()
                application {
                    routing {
                        registerAuthRoutes(
                            rateLimiter = LoginRateLimiter(),
                            cookieSecure = true,
                            passwordResetRateLimiter = LoginRateLimiter(),
                            passwordResetMailer = mailer,
                            friendEmailVerifyRateLimiter = LoginRateLimiter(),
                        )
                    }
                }

                val email = "auth-routes-reset-throwing-mailer@example.org"
                createTestMemberWithPassword(email, "a-genuinely-strong-password-1")

                val response = client.post("/api/auth/password-reset/request") { setBody("""{"email":"$email"}""") }

                response.status shouldBe HttpStatusCode.OK
                response.bodyAsText() shouldBe "If this email is registered, a password-reset link has been sent."
            }
        }

        test("password-reset/request: repeated requests eventually trip the rate limiter") {
            testApplication {
                val mailer = CapturingPasswordResetMailer()
                application {
                    routing {
                        registerAuthRoutes(
                            rateLimiter = LoginRateLimiter(),
                            cookieSecure = true,
                            passwordResetRateLimiter = LoginRateLimiter(),
                            passwordResetMailer = mailer,
                            friendEmailVerifyRateLimiter = LoginRateLimiter(),
                        )
                    }
                }

                val statuses =
                    (1..10).map {
                        client
                            .post("/api/auth/password-reset/request") {
                                setBody("""{"email":"auth-routes-reset-rate-limit@example.org"}""")
                            }.status
                    }
                statuses shouldContain HttpStatusCode.TooManyRequests
            }
        }

        test(
            "password-reset/confirm: happy path -- new password works for login, old password is rejected, every prior session is revoked",
        ) {
            testApplication {
                val mailer = CapturingPasswordResetMailer()
                application {
                    // The success path of /api/auth/login responds with a typed LoginResponse,
                    // which needs a ContentNegotiation converter to serialize -- module() normally
                    // provides this via initRpc's own internal install (see Application.kt KDoc);
                    // this test mounts registerAuthRoutes directly (to inject a capturing mailer),
                    // so it must install the same JSON converter itself.
                    install(ContentNegotiation) { json() }
                    routing {
                        registerAuthRoutes(
                            rateLimiter = LoginRateLimiter(),
                            cookieSecure = true,
                            passwordResetRateLimiter = LoginRateLimiter(),
                            passwordResetMailer = mailer,
                            friendEmailVerifyRateLimiter = LoginRateLimiter(),
                        )
                    }
                }

                val email = "auth-routes-reset-confirm@example.org"
                val oldPassword = "the-original-strong-password-1"
                val newPassword = "a-brand-new-strong-password-2"
                val memberId = createTestMemberWithPassword(email, oldPassword)
                val priorSession = SessionStore.createSession(memberId)

                client.post("/api/auth/password-reset/request") { setBody("""{"email":"$email"}""") }
                val rawToken = requireNotNull(mailer.lastRawToken)

                val confirmResponse =
                    client.post("/api/auth/password-reset/confirm") {
                        setBody("""{"token":"$rawToken","newPassword":"$newPassword"}""")
                    }
                confirmResponse.status shouldBe HttpStatusCode.NoContent

                val newLogin = client.post("/api/auth/login") { setBody("""{"email":"$email","password":"$newPassword"}""") }
                newLogin.status shouldBe HttpStatusCode.OK
                val oldLogin = client.post("/api/auth/login") { setBody("""{"email":"$email","password":"$oldPassword"}""") }
                oldLogin.status shouldBe HttpStatusCode.Unauthorized

                SessionStore.resolve(priorSession.rawToken) shouldBe null
            }
        }

        test("password-reset/confirm: an already-consumed token is rejected (single-use, tamper/replay test)") {
            testApplication {
                val mailer = CapturingPasswordResetMailer()
                application {
                    routing {
                        registerAuthRoutes(
                            rateLimiter = LoginRateLimiter(),
                            cookieSecure = true,
                            passwordResetRateLimiter = LoginRateLimiter(),
                            passwordResetMailer = mailer,
                            friendEmailVerifyRateLimiter = LoginRateLimiter(),
                        )
                    }
                }

                val email = "auth-routes-reset-replay@example.org"
                createTestMemberWithPassword(email, "the-original-strong-password-1")
                client.post("/api/auth/password-reset/request") { setBody("""{"email":"$email"}""") }
                val rawToken = requireNotNull(mailer.lastRawToken)

                val first =
                    client.post("/api/auth/password-reset/confirm") {
                        setBody("""{"token":"$rawToken","newPassword":"a-brand-new-strong-password-2"}""")
                    }
                first.status shouldBe HttpStatusCode.NoContent

                val replay =
                    client.post("/api/auth/password-reset/confirm") {
                        setBody("""{"token":"$rawToken","newPassword":"yet-another-strong-password-3"}""")
                    }
                replay.status shouldBe HttpStatusCode.BadRequest
            }
        }

        test("password-reset/confirm: an expired token is rejected") {
            testApplication {
                val mailer = CapturingPasswordResetMailer()
                application {
                    routing {
                        registerAuthRoutes(
                            rateLimiter = LoginRateLimiter(),
                            cookieSecure = true,
                            passwordResetRateLimiter = LoginRateLimiter(),
                            passwordResetMailer = mailer,
                            friendEmailVerifyRateLimiter = LoginRateLimiter(),
                        )
                    }
                }

                val email = "auth-routes-reset-expired@example.org"
                createTestMemberWithPassword(email, "the-original-strong-password-1")
                client.post("/api/auth/password-reset/request") { setBody("""{"email":"$email"}""") }
                val rawToken = requireNotNull(mailer.lastRawToken)

                // Directly age the token past its expiry -- same manipulation-of-persisted-state
                // idiom SessionStoreTest's own purgeExpired test uses.
                val nowMinusHour = (Clock.System.now() - 1.hours).toLocalDateTime(TimeZone.UTC)
                transaction {
                    PasswordResetTokenTable.update({
                        PasswordResetTokenTable.tokenHash eq SessionTokens.hash(rawToken)
                    }) {
                        it[expiresAt] = nowMinusHour
                    }
                }

                val response =
                    client.post("/api/auth/password-reset/confirm") {
                        setBody("""{"token":"$rawToken","newPassword":"a-brand-new-strong-password-2"}""")
                    }
                response.status shouldBe HttpStatusCode.BadRequest
            }
        }

        test("password-reset/confirm: an unknown token is rejected, never a 500") {
            testApplication {
                val mailer = CapturingPasswordResetMailer()
                application {
                    routing {
                        registerAuthRoutes(
                            rateLimiter = LoginRateLimiter(),
                            cookieSecure = true,
                            passwordResetRateLimiter = LoginRateLimiter(),
                            passwordResetMailer = mailer,
                            friendEmailVerifyRateLimiter = LoginRateLimiter(),
                        )
                    }
                }

                val response =
                    client.post("/api/auth/password-reset/confirm") {
                        setBody("""{"token":"this-token-was-never-issued","newPassword":"a-brand-new-strong-password-2"}""")
                    }
                response.status shouldBe HttpStatusCode.BadRequest
            }
        }

        test(
            "password-reset/confirm: a weak newPassword is rejected by PasswordPolicy WITHOUT burning the token -- " +
                "the same token can be retried afterward with a strong password",
        ) {
            testApplication {
                val mailer = CapturingPasswordResetMailer()
                application {
                    routing {
                        registerAuthRoutes(
                            rateLimiter = LoginRateLimiter(),
                            cookieSecure = true,
                            passwordResetRateLimiter = LoginRateLimiter(),
                            passwordResetMailer = mailer,
                            friendEmailVerifyRateLimiter = LoginRateLimiter(),
                        )
                    }
                }

                val email = "auth-routes-reset-weak@example.org"
                createTestMemberWithPassword(email, "the-original-strong-password-1")
                client.post("/api/auth/password-reset/request") { setBody("""{"email":"$email"}""") }
                val rawToken = requireNotNull(mailer.lastRawToken)

                val weakAttempt =
                    client.post("/api/auth/password-reset/confirm") {
                        setBody("""{"token":"$rawToken","newPassword":"short"}""")
                    }
                weakAttempt.status shouldBe HttpStatusCode.BadRequest

                // The token must still be alive -- a rejected newPassword is a validation failure,
                // not a reason to burn an otherwise-valid single-use token (see
                // PasswordResetTokenStore.peekMemberId KDoc). Retrying the SAME token with a strong
                // password now must succeed.
                val strongRetry =
                    client.post("/api/auth/password-reset/confirm") {
                        setBody("""{"token":"$rawToken","newPassword":"a-brand-new-strong-password-2"}""")
                    }
                strongRetry.status shouldBe HttpStatusCode.NoContent

                // NOW the token really is spent -- a second use of the same (already-consumed) token fails.
                val thirdAttempt =
                    client.post("/api/auth/password-reset/confirm") {
                        setBody("""{"token":"$rawToken","newPassword":"yet-another-strong-password-3"}""")
                    }
                thirdAttempt.status shouldBe HttpStatusCode.BadRequest
            }
        }
    })

/** Captures the last [send] call's arguments instead of only logging -- lets tests recover the raw, otherwise-never-persisted reset token, same role a real inbox would play. */
private class CapturingPasswordResetMailer : PasswordResetMailer {
    var lastEmail: String? = null
    var lastRawToken: String? = null

    override fun send(
        email: String,
        rawToken: String,
    ): DeliveryStatus {
        lastEmail = email
        lastRawToken = rawToken
        return DeliveryStatus.SENT
    }
}

/** V1.2.3 -- proves `/api/auth/password-reset/request`'s response is unaffected by a mailer that throws. */
private class ThrowingPasswordResetMailer : PasswordResetMailer {
    override fun send(
        email: String,
        rawToken: String,
    ): DeliveryStatus = throw IllegalStateException("simulated mailer failure")
}

private fun cleanUpAuthRoutesTestData(memberIds: List<Uuid>) {
    if (memberIds.isEmpty()) return
    transaction {
        SessionTable.deleteWhere { SessionTable.memberId inList memberIds }
        PasswordResetTokenTable.deleteWhere { PasswordResetTokenTable.memberId inList memberIds }
        AccountTable.deleteWhere { AccountTable.memberId inList memberIds }
        MemberTable.deleteWhere { MemberTable.id inList memberIds }
    }
}
