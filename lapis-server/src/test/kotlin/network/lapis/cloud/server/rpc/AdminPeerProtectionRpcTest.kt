package network.lapis.cloud.server.rpc

import dev.kilua.rpc.AbstractServiceException
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.response.respondText
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.datetime.LocalDate
import network.lapis.cloud.server.db.DatabaseConfig
import network.lapis.cloud.server.db.generated.DsgvoAuditLogTable
import network.lapis.cloud.server.db.generated.ErasureRequestTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.server.federation.FederationInboxRateLimiter
import network.lapis.cloud.server.keycloak.KeycloakConfig
import network.lapis.cloud.server.mail.FakeAdminPasswordResetNotificationMailer
import network.lapis.cloud.server.mail.FakeFriendVerificationMailer
import network.lapis.cloud.server.mail.FakePasswordResetMailer
import network.lapis.cloud.server.mail.FakePeerNotificationMailer
import network.lapis.cloud.server.mail.KeycloakLinkChange
import network.lapis.cloud.server.mail.KeycloakLinkNotificationMailer
import network.lapis.cloud.server.member.PeerFixture
import network.lapis.cloud.server.member.configuredSmtp
import network.lapis.cloud.server.security.LoginRateLimiter
import network.lapis.cloud.server.security.PasswordHasher
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.AdminCreateMemberInput
import network.lapis.cloud.shared.domain.MemberStatus
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.minutes
import kotlin.uuid.Uuid

private const val SECRET_STREET = "Q9PX-Hauptstrasse"
private const val SECRET_NATIONALITY = "Q9PX-nat"
private const val ISSUER = "https://keycloak.example.org/realms/lapis"

/**
 * Welle V1.9.57 "Admin-Peer-Schutz" -- the DIRECT paths of the member administration against the peer-protection matrix: what an
 * administrator may do to another administrator without a second one (reset mail, address data, status without blocking effect,
 * promotion), what is refused with the typed approval/denial exceptions (temporary password, demotion, suspension, erasure, identity
 * link, emergency e-mail change), what BOARD may and may not see of an administrator's address and beneficial-owner data, and the
 * notices that go out. H2 only: the paths are called through a real `MemberService` with a call (`X-Member-Id` test auth); the
 * lifecycle and the races are in [network.lapis.cloud.server.member.AdminPeerProtectionScenarios] (H2 and PostgreSQL).
 */
class AdminPeerProtectionRpcTest :
    FunSpec({
        val fx = PeerFixture()
        val mailer = FakePeerNotificationMailer()
        val resetMailer = FakePasswordResetMailer()
        val keycloakMailer =
            object : KeycloakLinkNotificationMailer {
                override fun send(
                    email: String,
                    change: KeycloakLinkChange,
                    occurredAt: kotlinx.datetime.LocalDateTime,
                ) = network.lapis.cloud.shared.domain.DeliveryStatus.SENT
            }
        val changePasswordLimiter = LoginRateLimiter(maxFailures = 5, window = 15.minutes)

        beforeSpec { DatabaseConfig.connect() }
        afterSpec {
            transaction {
                val requestIds =
                    ErasureRequestTable
                        .selectAll()
                        .where {
                            (ErasureRequestTable.subjectMemberId inList fx.createdMemberIds) or
                                (ErasureRequestTable.requestedBy inList fx.createdMemberIds)
                        }.map { it[ErasureRequestTable.id] }
                if (requestIds.isNotEmpty()) {
                    DsgvoAuditLogTable.deleteWhere { requestId inList requestIds }
                    ErasureRequestTable.deleteWhere { id inList requestIds }
                }
                DsgvoAuditLogTable.update({ DsgvoAuditLogTable.actorMemberId inList fx.createdMemberIds }) { it[actorMemberId] = null }
            }
            fx.cleanUp()
        }
        beforeTest { fx.isolateAdmins() }
        afterTest { fx.restoreAdmins() }

        fun memberService(call: ApplicationCall): MemberService =
            MemberService(
                call = call,
                passwordResetMailer = resetMailer,
                adminPasswordResetNotificationMailer = FakeAdminPasswordResetNotificationMailer(),
                smtpConfigState = configuredSmtp(),
                adminPasswordMailTargetRateLimiter = FederationInboxRateLimiter(maxRequests = 1000),
                adminPasswordMailActorRateLimiter = FederationInboxRateLimiter(maxRequests = 1000),
                adminPasswordNotificationTargetRateLimiter = FederationInboxRateLimiter(maxRequests = 1000),
                memberCardIssueRateLimiter = FederationInboxRateLimiter(maxRequests = 1000),
                memberAddressAdminReadRateLimiter = FederationInboxRateLimiter(maxRequests = 1000),
                peerNotificationMailer = mailer,
            )

        fun Application.setup() {
            install(StatusPages) {
                exception<AbstractServiceException> {
                    call,
                    cause,
                    ->
                    call.respondText(cause::class.simpleName!!, status = HttpStatusCode.Conflict)
                }
            }
            routing {
                post("/t/role/{id}") {
                    val dto =
                        memberService(
                            call,
                        ).updateMemberRole(
                            memberId = call.parameters["id"]!!,
                            newRole = AccountRole.valueOf(call.request.queryParameters["role"]!!),
                        )
                    call.respondText("OK:${dto.role}")
                }
                post("/t/status/{id}") {
                    val q = call.request.queryParameters
                    val dto =
                        memberService(
                            call,
                        ).updateMemberStatus(
                            memberId = call.parameters["id"]!!,
                            newStatus = MemberStatus.valueOf(q["status"]!!),
                            reason = "Peer-Schutz Testgrund",
                            dateOfDeath = null,
                        )
                    call.respondText("OK:${dto.status}")
                }
                post("/t/temp/{id}") {
                    memberService(
                        call,
                    ).setTemporaryPasswordForMember(
                        memberId = call.parameters["id"]!!,
                        newPassword = null,
                        reason = "Peer-Schutz Testgrund",
                    )
                    call.respondText("OK")
                }
                post("/t/temppw/{id}") {
                    memberService(
                        call,
                    ).setTemporaryPasswordForMember(
                        memberId = call.parameters["id"]!!,
                        newPassword = call.request.queryParameters["pw"],
                        reason = "Peer-Schutz Testgrund",
                    )
                    call.respondText("OK")
                }
                post("/t/resetmail/{id}") {
                    val r = memberService(call).sendPasswordResetMailToMember(call.parameters["id"]!!)
                    call.respondText("OK:${r.delivery}")
                }
                post("/t/read/{id}") {
                    val d = memberService(call).getMemberAddressForAdministration(call.parameters["id"]!!)
                    call.respondText(
                        "OK:${d.protectedTarget}|${d.street}|${d.postalCode}|${d.city}|${d.dateOfBirth}|${d.nationality}|${d.displayName}",
                    )
                }
                post("/t/write/{id}") {
                    memberService(
                        call,
                    ).updateMemberAddress(
                        memberId = call.parameters["id"]!!,
                        street = "Neue Strasse 1",
                        postalCode = "38100",
                        city = "Braunschweig",
                        country = "DE",
                    )
                    call.respondText("OK")
                }
                post("/t/beneficial/{id}") {
                    memberService(
                        call,
                    ).updateMemberBeneficialOwnerData(
                        memberId = call.parameters["id"]!!,
                        dateOfBirth = LocalDate(1975, 3, 4),
                        nationality = "DE",
                    )
                    call.respondText("OK")
                }
                post("/t/grant/{id}") {
                    memberService(
                        call,
                    ).grantMemberAccount(
                        memberId = call.parameters["id"]!!,
                        temporaryPassword = "a-genuinely-strong-password-1",
                        role = AccountRole.valueOf(call.request.queryParameters["role"]!!),
                    )
                    call.respondText("OK")
                }
                post("/t/erase-request/{id}") {
                    val r =
                        DsgvoService(
                            call = call,
                        ).requestErasure(
                            subjectMemberId = call.parameters["id"]!!,
                            reason = "Art. 17 Testgrund",
                            mode = network.lapis.cloud.shared.domain.ErasureMode.ANONYMIZE,
                        )
                    call.respondText("OK:${r.id}")
                }
                post("/t/erase-execute/{id}") {
                    DsgvoService(call = call).executeErasure(call.parameters["id"]!!)
                    call.respondText("OK")
                }
                post("/t/link/{id}") {
                    val config =
                        KeycloakConfig.load(
                            mapOf(
                                KeycloakConfig.ENV_ENABLED to "true",
                                KeycloakConfig.ENV_ISSUER_URL to ISSUER,
                                KeycloakConfig.ENV_CLIENT_ID to "lapis-cloud",
                                KeycloakConfig.ENV_CLIENT_SECRET to "kc-very-secret-client-secret-123",
                            )::get,
                        )
                    KeycloakLinkService(
                        call = call,
                        keycloakConfig = config,
                        notificationMailer = keycloakMailer,
                    ).linkMember(memberId = call.parameters["id"]!!, keycloakSubject = "sub-${Uuid.random()}")
                    call.respondText("OK")
                }
                post("/t/direct") {
                    val dto =
                        RegistrationService(
                            call = call,
                            registrationRateLimiter = LoginRateLimiter(),
                            friendRegistrationRateLimiter = LoginRateLimiter(),
                            friendSignupIpRateLimiter = FederationInboxRateLimiter(),
                            friendVerificationMailer = FakeFriendVerificationMailer(),
                            keycloakConfig = KeycloakConfig.load { null },
                            peerNotificationMailer = mailer,
                            peerSmtpConfigState = configuredSmtp(),
                        ).createMemberDirect(
                            AdminCreateMemberInput(
                                displayName = "Direkt Neu",
                                email = "direct-${Uuid.random()}@example.org",
                                role = AccountRole.valueOf(call.request.queryParameters["role"]!!),
                                temporaryPassword = "a-genuinely-strong-password-1",
                            ),
                        )
                    fx.createdMemberIds += Uuid.parse(dto.id)
                    call.respondText("OK:${dto.id}")
                }
                post("/t/changepw") {
                    val q = call.request.queryParameters
                    AuthService(
                        call = call,
                        changePasswordRateLimiter = changePasswordLimiter,
                    ).changePassword(currentPassword = q["cur"]!!, newPassword = q["new"]!!)
                    call.respondText("OK")
                }
            }
        }

        suspend fun HttpClient.act(
            path: String,
            actor: Uuid,
        ): Pair<HttpStatusCode, String> {
            val r = post(path) { header("X-Member-Id", actor.toString()) }
            return r.status to r.bodyAsText()
        }

        fun createWithData(role: AccountRole): Uuid {
            val id = fx.member(role = role)
            transaction {
                MemberTable.update({ MemberTable.id eq id }) {
                    it[street] = SECRET_STREET
                    it[postalCode] = "38100"
                    it[city] = "Braunschweig"
                    it[country] = "DE"
                    it[dateOfBirth] = LocalDate(1980, 5, 17)
                    it[nationality] = SECRET_NATIONALITY
                }
            }
            return id
        }

        fun peerAuditEvents(id: Uuid): List<String> = fx.auditJsonFor(id).filter { it.contains("peerAction") }

        // ───────────────────────────── protected data ─────────────────────────────

        test("BOARD reads an ADMIN's address and GwG data as a marked, value-free answer; no read audit entry, no error") {
            testApplication {
                application { setup() }
                val board = fx.member(role = AccountRole.BOARD)
                val admin = createWithData(AccountRole.ADMIN)
                val (status, body) = client.act("/t/read/$admin", board)
                status shouldBe HttpStatusCode.OK
                body shouldBe "OK:true|null|null|null|null|null|Peer Testmitglied"
                body shouldNotContain SECRET_STREET
                body shouldNotContain SECRET_NATIONALITY
                fx.auditJsonFor(admin).none { it.contains("ADDRESS_READ") } shouldBe true
            }
        }

        test(
            "BOARD reads a BOARD / TREASURER / MEMBER address as before (with the read audit entry); ADMIN reads an ADMIN's data (audited)",
        ) {
            testApplication {
                application { setup() }
                val board = fx.member(role = AccountRole.BOARD)
                val admin = fx.admin()
                listOf(AccountRole.BOARD, AccountRole.TREASURER, AccountRole.MEMBER).forEach { role ->
                    val target = createWithData(role)
                    val (status, body) = client.act("/t/read/$target", board)
                    status shouldBe HttpStatusCode.OK
                    body shouldContain "OK:false|$SECRET_STREET|38100|Braunschweig|1980-05-17|$SECRET_NATIONALITY"
                    fx.auditJsonFor(target).count { it.contains("ADDRESS_READ") } shouldBe 1
                }
                val adminTarget = createWithData(AccountRole.ADMIN)
                val (status, body) = client.act("/t/read/$adminTarget", admin)
                status shouldBe HttpStatusCode.OK
                body shouldContain "OK:false|$SECRET_STREET"
                fx.auditJsonFor(adminTarget).count { it.contains("ADDRESS_READ") } shouldBe 1
            }
        }

        test("BOARD cannot write an ADMIN's address or GwG data (typed denial, nothing changed); BOARD writes BOARD and MEMBER as before") {
            testApplication {
                application { setup() }
                val board = fx.member(role = AccountRole.BOARD)
                val admin = createWithData(AccountRole.ADMIN)
                client.act("/t/write/$admin", board) shouldBe (HttpStatusCode.Conflict to "PeerProtectionDeniedException")
                client.act("/t/beneficial/$admin", board) shouldBe (HttpStatusCode.Conflict to "PeerProtectionDeniedException")
                transaction { MemberTable.selectAll().where { MemberTable.id eq admin }.single()[MemberTable.street] } shouldBe
                    SECRET_STREET
                listOf(AccountRole.BOARD, AccountRole.MEMBER).forEach { role ->
                    val target = createWithData(role)
                    client.act("/t/write/$target", board).first shouldBe HttpStatusCode.OK
                    client.act("/t/beneficial/$target", board).first shouldBe HttpStatusCode.OK
                }
                // the refusal is audited without a field value
                peerAuditEvents(admin).joinToString("\n") shouldContain "\"event\":\"DENIED\""
                fx.auditJsonFor(admin).joinToString("\n") shouldNotContain SECRET_STREET
            }
        }

        test("an ADMIN may write another ADMIN's data; the target gets a notice and the audit entry exists") {
            testApplication {
                mailer.protectedDataNotices.clear()
                application { setup() }
                val actor = fx.admin(displayName = "Schreibender Admin")
                val target = createWithData(AccountRole.ADMIN)
                client.act("/t/write/$target", actor).first shouldBe HttpStatusCode.OK
                client.act("/t/beneficial/$target", actor).first shouldBe HttpStatusCode.OK
                mailer.protectedDataNotices.map { it.actorName }.distinct() shouldBe listOf("Schreibender Admin")
                mailer.protectedDataNotices shouldHaveSize 2
                // writing one's OWN data and a MEMBER's data tells nobody anything
                mailer.protectedDataNotices.clear()
                client.act("/t/write/$actor", actor).first shouldBe HttpStatusCode.OK
                client.act("/t/write/${fx.member()}", actor).first shouldBe HttpStatusCode.OK
                mailer.protectedDataNotices.shouldBeEmpty()
            }
        }

        // ───────────────────────────── the guarded actions ─────────────────────────────

        test(
            "temporary password against an ADMIN: with a second eligible ADMIN the approval path is required, without one NoSecondAdmin; the hash never changes",
        ) {
            testApplication {
                application { setup() }
                val a = fx.admin()
                val b = fx.admin()
                val before = fx.passwordHashOf(b)
                client.act("/t/temp/$b", a) shouldBe (HttpStatusCode.Conflict to "NoSecondAdminException")
                fx.admin()
                client.act("/t/temp/$b", a) shouldBe (HttpStatusCode.Conflict to "PeerApprovalRequiredException")
                // a caller-chosen password makes no difference
                client.act("/t/temppw/$b?pw=an-even-stronger-chosen-password-1", a) shouldBe
                    (HttpStatusCode.Conflict to "PeerApprovalRequiredException")
                fx.passwordHashOf(b) shouldBe before
                // a refusal is audited, throttled per caller, and carries no secret
                peerAuditEvents(b).joinToString("\n") shouldContain "\"event\":\"DENIED\""
            }
        }

        test("temporary password against a non-ADMIN target stays a direct action (unchanged), against oneself it stays forbidden") {
            testApplication {
                application { setup() }
                val a = fx.admin()
                val target = fx.member(role = AccountRole.BOARD)
                val before = fx.passwordHashOf(target)
                client.act("/t/temp/$target", a).first shouldBe HttpStatusCode.OK
                (fx.passwordHashOf(target) != before) shouldBe true
                client.act("/t/temp/$a", a) shouldBe (HttpStatusCode.Conflict to "ForbiddenException")
            }
        }

        test("demotion and suspension of an ADMIN are never executed directly (approval path, or NoSecondAdmin); nothing changes") {
            testApplication {
                application { setup() }
                val a = fx.admin()
                val b = fx.admin()
                client.act("/t/role/$b?role=MEMBER", a) shouldBe (HttpStatusCode.Conflict to "NoSecondAdminException")
                client.act("/t/status/$b?status=WITHDRAWN", a) shouldBe (HttpStatusCode.Conflict to "NoSecondAdminException")
                fx.admin()
                client.act("/t/role/$b?role=MEMBER", a) shouldBe (HttpStatusCode.Conflict to "PeerApprovalRequiredException")
                client.act("/t/status/$b?status=WITHDRAWN", a) shouldBe (HttpStatusCode.Conflict to "PeerApprovalRequiredException")
                fx.roleOf(b) shouldBe AccountRole.ADMIN
                fx.statusOf(b) shouldBe MemberStatus.ACTIVE
            }
        }

        test("BOARD still cannot touch an escalated target's role or status (Forbidden stays Forbidden, not a peer denial)") {
            testApplication {
                application { setup() }
                val board = fx.member(role = AccountRole.BOARD)
                val admin = fx.admin()
                client.act("/t/status/$admin?status=WITHDRAWN", board) shouldBe (HttpStatusCode.Conflict to "ForbiddenException")
                client.act("/t/role/$admin?role=MEMBER", board) shouldBe (HttpStatusCode.Conflict to "ForbiddenException")
            }
        }

        test("a status change of an ADMIN without blocking effect (re-activation) is allowed; the target is told") {
            testApplication {
                mailer.executedMails.clear()
                application { setup() }
                val a = fx.admin(displayName = "Reaktivierender Admin")
                val b = fx.admin(status = MemberStatus.DONOR)
                client.act("/t/status/$b?status=ACTIVE", a) shouldBe (HttpStatusCode.OK to "OK:ACTIVE")
                fx.statusOf(b) shouldBe MemberStatus.ACTIVE
                mailer.executedMails.single().actorName shouldBe "Reaktivierender Admin"
            }
        }

        test(
            "a reset mail for another ADMIN is allowed: the target is told, the reset link goes to the stored address, the audit entry names the actor",
        ) {
            testApplication {
                mailer.resetMailNotices.clear()
                application { setup() }
                val a = fx.admin(displayName = "Reset Admin")
                val b = fx.admin()
                client.act("/t/resetmail/$b", a).first shouldBe HttpStatusCode.OK
                mailer.resetMailNotices.single().actorName shouldBe "Reset Admin"
                peerAuditEvents(b).joinToString("\n") shouldContain "\"action\":\"RESET_MAIL\""
                // a plain member gets the mail without the extra notice
                mailer.resetMailNotices.clear()
                client.act("/t/resetmail/${fx.member()}", a).first shouldBe HttpStatusCode.OK
                mailer.resetMailNotices.shouldBeEmpty()
            }
        }

        // ───────────────────────────── promotion ─────────────────────────────

        test("promotion to ADMIN (role change, access grant, direct creation) tells every OTHER administrator and stamps role_changed_at") {
            testApplication {
                application { setup() }
                val actor = fx.admin(displayName = "Befoerdernder Admin")
                val other = fx.admin()
                // role change
                mailer.newAdminNotices.clear()
                val promoted = fx.member(role = AccountRole.BOARD, displayName = "Neuer Admin A")
                client.act("/t/role/$promoted?role=ADMIN", actor) shouldBe (HttpStatusCode.OK to "OK:ADMIN")
                (fx.roleChangedAtOf(promoted) != null) shouldBe true
                mailer.newAdminNotices.map { it.subjectName } shouldBe listOf("Neuer Admin A")
                // the actor and the new administrator are not told, the other administrator is
                mailer.newAdminNotices.single().actorName shouldBe "Befoerdernder Admin"
                transaction { MemberTable.selectAll().where { MemberTable.id eq other }.single()[MemberTable.email] }.let { otherEmail ->
                    mailer.newAdminNotices.single().email shouldBe otherEmail
                }
                peerAuditEvents(promoted).joinToString("\n") shouldContain "\"event\":\"NOTIFIED_PROMOTION\""
                // direct creation
                mailer.newAdminNotices.clear()
                val (status, body) = client.act("/t/direct?role=ADMIN", actor)
                status shouldBe HttpStatusCode.OK
                val created = Uuid.parse(body.removePrefix("OK:"))
                (fx.roleChangedAtOf(created) != null) shouldBe true
                // the other administrator AND the administrator promoted above (now an ADMIN too) are told, actor and new admin are not
                mailer.newAdminNotices shouldHaveSize 2
                // a BOARD creation tells nobody
                mailer.newAdminNotices.clear()
                client.act("/t/direct?role=BOARD", actor).first shouldBe HttpStatusCode.OK
                mailer.newAdminNotices.shouldBeEmpty()
            }
        }

        test("grantMemberAccount with the ADMIN role tells the other administrators and stamps role_changed_at") {
            testApplication {
                application { setup() }
                val actor = fx.admin()
                fx.admin()
                mailer.newAdminNotices.clear()
                val accountless = fx.member(role = null)
                client.act("/t/grant/$accountless?role=ADMIN", actor).first shouldBe HttpStatusCode.OK
                (fx.roleChangedAtOf(accountless) != null) shouldBe true
                mailer.newAdminNotices shouldHaveSize 1
                val plain = fx.member(role = null)
                mailer.newAdminNotices.clear()
                client.act("/t/grant/$plain?role=MEMBER", actor).first shouldBe HttpStatusCode.OK
                mailer.newAdminNotices.shouldBeEmpty()
            }
        }

        // ───────────────────────────── the alternative paths ─────────────────────────────

        test(
            "GDPR erasure: a third party cannot request it against an ADMIN, an ADMIN may file their own request but it is not executed while they are ADMIN",
        ) {
            testApplication {
                application { setup() }
                val actor = fx.admin()
                val target = fx.admin()
                client.act("/t/erase-request/$target", actor) shouldBe (HttpStatusCode.Conflict to "PeerProtectionDeniedException")
                // the target files their own request (allowed), a request exists
                val (status, body) = client.act("/t/erase-request/$target", target)
                status shouldBe HttpStatusCode.OK
                val requestId = body.removePrefix("OK:")
                transaction {
                    ErasureRequestTable.update({ ErasureRequestTable.id eq Uuid.parse(requestId) }) {
                        it[ErasureRequestTable.status] =
                            network.lapis.cloud.shared.domain.ErasureStatus.APPROVED
                    }
                }
                // execution is refused for an ADMIN subject -- also for their own request, also by another ADMIN
                client.act("/t/erase-execute/$requestId", actor) shouldBe (HttpStatusCode.Conflict to "PeerProtectionDeniedException")
                client.act("/t/erase-execute/$requestId", target) shouldBe (HttpStatusCode.Conflict to "PeerProtectionDeniedException")
                transaction { MemberTable.selectAll().where { MemberTable.id eq target }.single()[MemberTable.anonymizedAt] } shouldBe null
                fx.roleOf(target) shouldBe AccountRole.ADMIN
            }
        }

        test("manual Keycloak link: an ADMIN may link their own account and any non-ADMIN, never another ADMIN's") {
            testApplication {
                application { setup() }
                val actor = fx.admin()
                val other = fx.admin()
                client.act("/t/link/$other", actor) shouldBe (HttpStatusCode.Conflict to "PeerProtectionDeniedException")
                client.act("/t/link/$actor", actor).first shouldBe HttpStatusCode.OK
                client.act("/t/link/${fx.member()}", actor).first shouldBe HttpStatusCode.OK
                transaction {
                    network.lapis.cloud.server.db.generated.KeycloakAccountLinkTable
                        .deleteWhere { memberId inList fx.createdMemberIds }
                }
            }
        }

        test(
            "an APPLICATION applicant who already holds an ADMIN account is handled by an ADMIN only, and the rejection follows the suspension rule",
        ) {
            testApplication {
                application {
                    install(StatusPages) {
                        exception<AbstractServiceException> {
                            call,
                            cause,
                            ->
                            call.respondText(cause::class.simpleName!!, status = HttpStatusCode.Conflict)
                        }
                    }
                    routing {
                        post("/t/reject/{id}") {
                            RegistrationService(
                                call = call,
                                registrationRateLimiter = LoginRateLimiter(),
                                friendRegistrationRateLimiter = LoginRateLimiter(),
                                friendSignupIpRateLimiter = FederationInboxRateLimiter(),
                                friendVerificationMailer = FakeFriendVerificationMailer(),
                                keycloakConfig = KeycloakConfig.load { null },
                            ).rejectApplication(memberId = call.parameters["id"]!!, reason = "Testgrund")
                            call.respondText("OK")
                        }
                    }
                }
                val board = fx.member(role = AccountRole.BOARD)
                val actor = fx.admin()
                val applicantAdmin = fx.member(role = AccountRole.ADMIN, status = MemberStatus.APPLICATION)
                client.act("/t/reject/$applicantAdmin", board) shouldBe (HttpStatusCode.Conflict to "ForbiddenException")
                client.act("/t/reject/$applicantAdmin", actor) shouldBe (HttpStatusCode.Conflict to "NoSecondAdminException")
                fx.statusOf(applicantAdmin) shouldBe MemberStatus.APPLICATION
            }
        }

        // ───────────────────────────── audit hygiene ─────────────────────────────

        test("refusals are audited at most 30 times per hour and caller; no entry carries a secret") {
            testApplication {
                application { setup() }
                val a = fx.admin()
                val b = fx.admin()
                repeat(35) { client.act("/t/role/$b?role=MEMBER", a) }
                val denied = peerAuditEvents(b).count { it.contains("\"event\":\"DENIED\"") }
                (denied in 1..30) shouldBe true
                fx.auditJsonFor(b).joinToString("\n") shouldNotContain "@"
            }
        }

        // ───────────────────────────── change password limit ─────────────────────────────

        test("changePassword: the sixth attempt within the window is refused even with the correct password; a success resets the budget") {
            testApplication {
                application { setup() }
                val user = fx.member()
                val wrong = AtomicInteger()
                repeat(5) {
                    val r =
                        client.post(
                            "/t/changepw?cur=wrong-password-$it&new=a-brand-new-password-1234",
                        ) { header("X-Member-Id", user.toString()) }
                    r.bodyAsText() shouldBe "InvalidPasswordException"
                    wrong.incrementAndGet()
                }
                client
                    .post("/t/changepw?cur=${network.lapis.cloud.server.member.PEER_PASSWORD}&new=a-brand-new-password-1234") {
                        header("X-Member-Id", user.toString())
                    }.bodyAsText() shouldBe "ConflictException"
                PasswordHasher.verify(
                    rawPassword = network.lapis.cloud.server.member.PEER_PASSWORD,
                    storedHash = fx.passwordHashOf(user),
                ) shouldBe
                    true
                // another member is not affected, and a success resets that member's own budget
                val other = fx.member()
                repeat(3) {
                    client.post("/t/changepw?cur=wrong-$it&new=a-brand-new-password-1234") { header("X-Member-Id", other.toString()) }
                }
                client
                    .post("/t/changepw?cur=${network.lapis.cloud.server.member.PEER_PASSWORD}&new=another-brand-new-password-5678") {
                        header("X-Member-Id", other.toString())
                    }.bodyAsText() shouldBe "OK"
                repeat(4) {
                    client
                        .post("/t/changepw?cur=wrong-again-$it&new=a-brand-new-password-1234") {
                            header("X-Member-Id", other.toString())
                        }.bodyAsText() shouldBe
                        "InvalidPasswordException"
                }
            }
        }
    })
