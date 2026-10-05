package network.lapis.cloud.server.member

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import network.lapis.cloud.server.db.DatabaseConfig
import network.lapis.cloud.server.db.DevSeedData
import network.lapis.cloud.server.db.generated.PasswordResetTokenTable
import network.lapis.cloud.server.mail.FakeEmailChangeMailer
import network.lapis.cloud.server.mail.PasswordResetMailer
import network.lapis.cloud.server.routes.registerAuthRoutes
import network.lapis.cloud.server.routes.registerEmailChangeRoutes
import network.lapis.cloud.server.rpc.MemberEmailChangeService
import network.lapis.cloud.server.security.LoginRateLimiter
import network.lapis.cloud.shared.domain.DeliveryStatus
import network.lapis.cloud.shared.rpc.EmailChangeNotAllowedException
import network.lapis.cloud.shared.rpc.ForbiddenException
import network.lapis.cloud.shared.rpc.UnauthenticatedException
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.uuid.Uuid

private const val BASE_URL = "https://lapis.example.org"

private class RecordingPasswordResetMailer : PasswordResetMailer {
    val sentTo = CopyOnWriteArrayList<String>()

    override fun send(
        email: String,
        rawToken: String,
    ): DeliveryStatus {
        sentTo += email
        return DeliveryStatus.SENT
    }
}

/**
 * Welle V1.9.56 -- the two unauthenticated link endpoints, the login / password-reset behaviour while a change is
 * pending (they must keep reading `member.email` only), and the RPC facade's authentication mapping.
 */
class EmailChangeRoutesTest :
    FunSpec({
        val fx = EmailChangeFixture()

        beforeSpec {
            DatabaseConfig.connect()
            DevSeedData.seedIfEmpty(force = true)
        }
        afterSpec { fx.cleanUp() }

        fun newAddress() = "ec-route-${Uuid.random().toString().take(12)}@example.org"

        suspend fun HttpClient.confirm(
            token: String,
            password: String? = null,
            origin: String? = BASE_URL,
        ): HttpResponse =
            post("/api/auth/email-change/confirm") {
                if (origin != null) header(HttpHeaders.Origin, origin)
                setBody(if (password == null) """{"token":"$token"}""" else """{"token":"$token","password":"$password"}""")
            }

        suspend fun HttpClient.revoke(
            token: String,
            origin: String? = BASE_URL,
        ): HttpResponse =
            post("/api/auth/email-change/revoke") {
                if (origin != null) header(HttpHeaders.Origin, origin)
                setBody("""{"token":"$token"}""")
            }

        test("confirm and revoke: 204 on success, one identical 400 for every unusable token, no redirect, no GET") {
            val mailer = FakeEmailChangeMailer()
            val svc = fx.service(mailer = mailer)
            testApplication {
                application {
                    routing {
                        registerEmailChangeRoutes(
                            service = svc,
                            ipRateLimiter = LoginRateLimiter(maxFailures = 1000),
                            baseUrl = BASE_URL,
                        )
                    }
                }
                val id = fx.member()
                val fresh = newAddress()
                svc.propose(actor = fx.initiator(), targetIdRaw = id.toString(), newEmail = fresh, newEmailRepeat = fresh)
                val confirm = mailer.confirmMails.single().rawToken
                val revoke = mailer.warningMails.single().rawRevokeToken

                // a GET never does anything, even with the token in the query string
                client.get("/api/auth/email-change/confirm?token=$confirm").status shouldNotBe HttpStatusCode.NoContent
                client.get("/api/auth/email-change/revoke?token=$revoke").status shouldNotBe HttpStatusCode.NoContent
                fx.openCount(id) shouldBe 1L

                // unusable tokens all look the same
                val garbage = client.confirm("garbage")
                val wrongKind = client.confirm(revoke, EC_PASSWORD)
                val malformed =
                    client.post("/api/auth/email-change/confirm") {
                        header(HttpHeaders.Origin, BASE_URL)
                        setBody("not json")
                    }
                val blank = client.confirm("")
                listOf(garbage, wrongKind, malformed, blank).forEach {
                    it.status shouldBe HttpStatusCode.BadRequest
                    it.bodyAsText() shouldBe "invalid"
                    it.headers[HttpHeaders.Location] shouldBe null
                }

                client.confirm(confirm, null).bodyAsText() shouldBe "invalid" // a proposal needs the password
                val wrong = client.confirm(confirm, "wrong-password-xyz")
                wrong.status shouldBe HttpStatusCode.BadRequest
                wrong.bodyAsText() shouldBe "wrong-password"

                val ok = client.confirm(confirm, EC_PASSWORD)
                ok.status shouldBe HttpStatusCode.NoContent
                ok.headers[HttpHeaders.Location] shouldBe null
                fx.emailOf(id) shouldBe fresh
                val replay = client.confirm(confirm, EC_PASSWORD)
                replay.status shouldBe HttpStatusCode.BadRequest
                replay.bodyAsText() shouldBe "invalid"

                // revoke
                val id2 = fx.member()
                val a2 = newAddress()
                svc.propose(actor = fx.initiator(), targetIdRaw = id2.toString(), newEmail = a2, newEmailRepeat = a2)
                val revoke2 = mailer.warningMails.last().rawRevokeToken
                client.revoke(revoke2).status shouldBe HttpStatusCode.NoContent
                fx.openCount(id2) shouldBe 0L
                client.revoke(revoke2).bodyAsText() shouldBe "invalid"
                client.revoke("garbage").bodyAsText() shouldBe "invalid"
            }
        }

        test("an address taken after the proposal answers 'unavailable'") {
            val mailer = FakeEmailChangeMailer()
            val svc = fx.service(mailer = mailer)
            testApplication {
                application {
                    routing {
                        registerEmailChangeRoutes(
                            service = svc,
                            ipRateLimiter = LoginRateLimiter(maxFailures = 1000),
                            baseUrl = BASE_URL,
                        )
                    }
                }
                val id = fx.member()
                val fresh = newAddress()
                svc.propose(actor = fx.initiator(), targetIdRaw = id.toString(), newEmail = fresh, newEmailRepeat = fresh)
                fx.member(email = fresh)
                val response = client.confirm(mailer.confirmMails.single().rawToken, EC_PASSWORD)
                response.status shouldBe HttpStatusCode.BadRequest
                response.bodyAsText() shouldBe "unavailable"
            }
        }

        test("same-origin only: a foreign Origin or a cross-site Sec-Fetch-Site is refused before anything happens") {
            val mailer = FakeEmailChangeMailer()
            val svc = fx.service(mailer = mailer)
            testApplication {
                application {
                    routing {
                        registerEmailChangeRoutes(
                            service = svc,
                            ipRateLimiter = LoginRateLimiter(maxFailures = 1000),
                            baseUrl = BASE_URL,
                        )
                    }
                }
                val id = fx.member()
                val fresh = newAddress()
                svc.propose(actor = fx.initiator(), targetIdRaw = id.toString(), newEmail = fresh, newEmailRepeat = fresh)
                val confirm = mailer.confirmMails.single().rawToken
                val revoke = mailer.warningMails.single().rawRevokeToken

                client.confirm(confirm, EC_PASSWORD, origin = "https://evil.example").status shouldBe HttpStatusCode.Forbidden
                client.revoke(revoke, origin = "https://evil.example").status shouldBe HttpStatusCode.Forbidden
                client.confirm(confirm, EC_PASSWORD, origin = "null").status shouldBe HttpStatusCode.Forbidden
                client
                    .post("/api/auth/email-change/revoke") {
                        header("Sec-Fetch-Site", "cross-site")
                        setBody("""{"token":"$revoke"}""")
                    }.status shouldBe HttpStatusCode.Forbidden
                fx.openCount(id) shouldBe 1L
                fx.emailOf(id) shouldNotBe fresh

                // no Origin header at all (non-browser client) and an exact same origin pass
                client.revoke(revoke, origin = null).status shouldBe HttpStatusCode.NoContent
            }
        }

        test("per-IP budget: the third request within the budget is answered 429, valid or not") {
            val svc = fx.service()
            testApplication {
                application {
                    routing {
                        registerEmailChangeRoutes(
                            service = svc,
                            ipRateLimiter = LoginRateLimiter(maxFailures = 2),
                            baseUrl = BASE_URL,
                        )
                    }
                }
                client.revoke("garbage").status shouldBe HttpStatusCode.BadRequest
                client.revoke("garbage").status shouldBe HttpStatusCode.BadRequest
                val blocked = client.revoke("garbage")
                blocked.status shouldBe HttpStatusCode.TooManyRequests
                blocked.bodyAsText() shouldBe "rate-limited"
            }
        }

        test("while a change is pending, login and password reset keep using the CURRENT address only") {
            val mailer = FakeEmailChangeMailer()
            val svc = fx.service(mailer = mailer)
            val resetMailer = RecordingPasswordResetMailer()
            testApplication {
                application {
                    install(ContentNegotiation) { json() }
                    routing {
                        registerAuthRoutes(
                            rateLimiter = LoginRateLimiter(maxFailures = 1000),
                            cookieSecure = false,
                            passwordResetRateLimiter = LoginRateLimiter(maxFailures = 1000),
                            passwordResetMailer = resetMailer,
                            friendEmailVerifyRateLimiter = LoginRateLimiter(maxFailures = 1000),
                        )
                    }
                }
                val old = "lock-old-${Uuid.random().toString().take(8)}@example.org"
                val fresh = newAddress()
                val id = fx.member(email = old)
                svc.propose(actor = fx.initiator(), targetIdRaw = id.toString(), newEmail = fresh, newEmailRepeat = fresh)

                // password reset: the PENDING address gets nothing, the current one does
                client.post("/api/auth/password-reset/request") { setBody("""{"email":"$fresh"}""") }.status shouldBe HttpStatusCode.OK
                resetMailer.sentTo.toList() shouldBe emptyList()
                client.post("/api/auth/password-reset/request") { setBody("""{"email":"$old"}""") }.status shouldBe HttpStatusCode.OK
                resetMailer.sentTo.toList() shouldBe listOf(old)
                transaction { PasswordResetTokenTable.selectAll().where { PasswordResetTokenTable.memberId eq id }.count() } shouldBe 1L

                // login: only the current address
                client.post("/api/auth/login") { setBody("""{"email":"$fresh","password":"$EC_PASSWORD"}""") }.status shouldBe
                    HttpStatusCode.Unauthorized
                client.post("/api/auth/login") { setBody("""{"email":"$old","password":"$EC_PASSWORD"}""") }.status shouldBe
                    HttpStatusCode.OK

                // after the change took effect it is the other way round
                val own = svc.ownPending(fx.actor(id = id, role = network.lapis.cloud.shared.domain.AccountRole.MEMBER))!!
                svc.acceptOwn(
                    actor = fx.actor(id = id, role = network.lapis.cloud.shared.domain.AccountRole.MEMBER),
                    changeIdRaw = own.changeId,
                    currentPassword = EC_PASSWORD,
                    ownRawSessionToken = null,
                )
                client.post("/api/auth/login") { setBody("""{"email":"$old","password":"$EC_PASSWORD"}""") }.status shouldBe
                    HttpStatusCode.Unauthorized
                client.post("/api/auth/login") { setBody("""{"email":"$fresh","password":"$EC_PASSWORD"}""") }.status shouldBe
                    HttpStatusCode.OK
            }
        }

        test("RPC facade: unauthenticated is rejected, MEMBER is forbidden, BOARD proposes, ADMIN sees the masked pending change") {
            val mailer = FakeEmailChangeMailer()
            val svc = fx.service(mailer = mailer)
            testApplication {
                application {
                    install(StatusPages) {
                        exception<UnauthenticatedException> {
                            call,
                            cause,
                            ->
                            call.respondText(cause.message, status = HttpStatusCode.Unauthorized)
                        }
                        exception<ForbiddenException> { call, cause -> call.respondText(cause.message, status = HttpStatusCode.Forbidden) }
                        exception<EmailChangeNotAllowedException> {
                            call,
                            cause,
                            ->
                            call.respondText(cause.message, status = HttpStatusCode.Conflict)
                        }
                    }
                    routing {
                        post("/t/propose/{id}") {
                            val q = call.request.queryParameters
                            val dto =
                                MemberEmailChangeService(
                                    call = call,
                                    domain = svc,
                                ).proposeEmailChange(
                                    memberId = call.parameters["id"]!!,
                                    newEmail = q["email"]!!,
                                    newEmailRepeat = q["email"]!!,
                                )
                            call.respondText("${dto.changeId}|${dto.newEmailMasked}|${dto.kind}")
                        }
                        get("/t/pending/{id}") {
                            val dto =
                                MemberEmailChangeService(
                                    call = call,
                                    domain = svc,
                                ).getPendingEmailChangeForMember(call.parameters["id"]!!)
                            call.respondText(dto.pending?.newEmailMasked ?: "none")
                        }
                        get("/t/capability") {
                            val dto = MemberEmailChangeService(call = call, domain = svc).getEmailChangeCapability()
                            call.respondText("${dto.mailDelivery}|${dto.ownChangeAvailable}")
                        }
                        get("/t/own-pending") {
                            val dto = MemberEmailChangeService(call = call, domain = svc).getOwnPendingEmailChange()
                            call.respondText(dto.pending?.newEmail ?: "none")
                        }
                    }
                }
                val id = fx.member()
                val fresh = newAddress()
                val board = "00000000-0000-0000-0000-000000000002"
                val admin = "00000000-0000-0000-0000-000000000001"
                val member = "00000000-0000-0000-0000-000000000004"

                client.post("/t/propose/$id?email=$fresh").status shouldBe HttpStatusCode.Unauthorized
                client.get("/t/capability").status shouldBe HttpStatusCode.Unauthorized
                client.post("/t/propose/$id?email=$fresh") { header("X-Member-Id", member) }.status shouldBe HttpStatusCode.Forbidden
                client.get("/t/pending/$id") { header("X-Member-Id", member) }.status shouldBe HttpStatusCode.Forbidden
                fx.openCount(id) shouldBe 0L

                val ok = client.post("/t/propose/$id?email=$fresh") { header("X-Member-Id", board) }
                ok.status shouldBe HttpStatusCode.OK
                ok.bodyAsText().endsWith("|PROPOSAL") shouldBe true
                // targeting oneself is refused with the typed exception
                client.post("/t/propose/$board?email=${newAddress()}") { header("X-Member-Id", board) }.status shouldBe
                    HttpStatusCode.Conflict
                client.get("/t/pending/$id") { header("X-Member-Id", admin) }.bodyAsText().startsWith("e***@") shouldBe true
                client.get("/t/capability") { header("X-Member-Id", member) }.bodyAsText() shouldBe "HANDED_TO_SMTP|true"
                client.get("/t/own-pending") { header("X-Member-Id", member) }.bodyAsText() shouldBe "none"
            }
        }
    })
