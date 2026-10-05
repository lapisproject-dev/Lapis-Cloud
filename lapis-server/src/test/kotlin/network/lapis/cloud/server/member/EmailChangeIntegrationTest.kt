package network.lapis.cloud.server.member

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldNotContain
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.response.respondText
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import network.lapis.cloud.server.db.DatabaseConfig
import network.lapis.cloud.server.db.DevSeedData
import network.lapis.cloud.server.db.generated.KeycloakAccountLinkTable
import network.lapis.cloud.server.db.generated.MemberEmailChangeTable
import network.lapis.cloud.server.dsgvo.RegistrationPersonalData
import network.lapis.cloud.server.federation.FederationInboxRateLimiter
import network.lapis.cloud.server.keycloak.KeycloakAccountLinker
import network.lapis.cloud.server.mail.FakeAdminPasswordResetNotificationMailer
import network.lapis.cloud.server.mail.FakeEmailChangeMailer
import network.lapis.cloud.server.mail.FakePasswordResetMailer
import network.lapis.cloud.server.mail.SmtpConfigState
import network.lapis.cloud.server.rpc.MemberService
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.ErasureMode
import network.lapis.cloud.shared.rpc.ConflictException
import network.lapis.cloud.shared.rpc.ForbiddenException
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.uuid.Uuid

/**
 * Welle V1.9.56 -- the address change against its neighbours: the Keycloak account link (reads `member.email`, so a
 * PENDING address must never link), `MemberService.grantMemberAccount` (refuses while a change is open), and the GDPR
 * export / erasure of `member_email_change`.
 */
class EmailChangeIntegrationTest :
    FunSpec({
        val fx = EmailChangeFixture()

        beforeSpec {
            DatabaseConfig.connect()
            DevSeedData.seedIfEmpty(force = true)
        }
        afterSpec {
            transaction { KeycloakAccountLinkTable.deleteWhere { memberId inList fx.createdMemberIds } }
            fx.cleanUp()
        }

        fun newAddress() = "ec-int-${Uuid.random().toString().take(12)}@example.org"

        // ───────────────────────────── Keycloak ─────────────────────────────

        test(
            "Keycloak link: a PENDING address never links, only the current address does, and it stops matching once the change is applied",
        ) {
            val mailer = FakeEmailChangeMailer()
            val svc = fx.service(mailer = mailer)
            val old = "kc-old-${Uuid.random().toString().take(8)}@example.org"
            val id = fx.member(email = old)
            val fresh = newAddress()
            svc.propose(actor = fx.initiator(), targetIdRaw = id.toString(), newEmail = fresh, newEmailRepeat = fresh)

            // the pending (new) address resolves to nobody
            val viaPending =
                KeycloakAccountLinker.linkOrResolve(
                    issuer = "https://kc.example.org/realms/x",
                    subject = "sub-pending-${Uuid.random()}",
                    email = fresh,
                    emailVerified = true,
                    requireVerifiedEmail = true,
                )
            (viaPending as KeycloakAccountLinker.LinkOutcome.Rejected).reason shouldBe
                KeycloakAccountLinker.RejectionReason.NO_MATCHING_MEMBER

            // the current address still links
            val viaCurrent =
                KeycloakAccountLinker.linkOrResolve(
                    issuer = "https://kc.example.org/realms/x",
                    subject = "sub-current-${Uuid.random()}",
                    email = old,
                    emailVerified = true,
                    requireVerifiedEmail = true,
                )
            (viaCurrent as KeycloakAccountLinker.LinkOutcome.Linked).memberId shouldBe id
        }

        // ───────────────────────────── grantMemberAccount ─────────────────────────────

        test("grantMemberAccount is refused while an address change is open, and works once it is resolved") {
            val mailer = FakeEmailChangeMailer()
            val svc = fx.service(mailer = mailer)
            val accountless = fx.member(role = null)
            val fresh = newAddress()
            val pending =
                svc.propose(
                    actor = fx.initiator(),
                    targetIdRaw = accountless.toString(),
                    newEmail = fresh,
                    newEmailRepeat = fresh,
                )

            testApplication {
                application {
                    install(StatusPages) {
                        exception<ForbiddenException> { call, cause -> call.respondText(cause.message, status = HttpStatusCode.Forbidden) }
                        exception<ConflictException> { call, cause -> call.respondText(cause.message, status = HttpStatusCode.Conflict) }
                    }
                    routing {
                        post("/test/grant/{id}") {
                            val service =
                                MemberService(
                                    call = call,
                                    passwordResetMailer = FakePasswordResetMailer(),
                                    adminPasswordResetNotificationMailer = FakeAdminPasswordResetNotificationMailer(),
                                    smtpConfigState = SmtpConfigState.NotConfigured,
                                    adminPasswordMailTargetRateLimiter = FederationInboxRateLimiter(),
                                    adminPasswordMailActorRateLimiter = FederationInboxRateLimiter(),
                                    adminPasswordNotificationTargetRateLimiter = FederationInboxRateLimiter(),
                                    memberCardIssueRateLimiter = FederationInboxRateLimiter(),
                                    memberAddressAdminReadRateLimiter = FederationInboxRateLimiter(),
                                )
                            service.grantMemberAccount(
                                memberId = call.parameters["id"]!!,
                                temporaryPassword = EC_PASSWORD,
                                role = AccountRole.MEMBER,
                            )
                            call.respondText("granted")
                        }
                    }
                }
                val blocked = client.post("/test/grant/$accountless") { header("X-Member-Id", EC_SEED_ADMIN.memberId.toString()) }
                blocked.status shouldBe HttpStatusCode.Conflict
                blocked.bodyAsText() shouldNotBe "granted"

                svc.withdraw(actor = EC_SEED_ADMIN, changeIdRaw = pending.changeId)
                val granted = client.post("/test/grant/$accountless") { header("X-Member-Id", EC_SEED_ADMIN.memberId.toString()) }
                granted.status shouldBe HttpStatusCode.OK
            }
        }

        // ───────────────────────────── DSGVO ─────────────────────────────

        test("GDPR export: carries the pending address, kind, status and timestamps, never a token hash") {
            val mailer = FakeEmailChangeMailer()
            val svc = fx.service(mailer = mailer)
            val id = fx.member()
            val fresh = "export-${Uuid.random().toString().take(8)}@example.org"
            svc.propose(actor = EC_SEED_ADMIN, targetIdRaw = id.toString(), newEmail = fresh, newEmailRepeat = fresh)
            val confirm = mailer.confirmMails.single().rawToken
            val revoke = mailer.warningMails.single().rawRevokeToken

            val export = transaction { RegistrationPersonalData.exportMember(id) }.jsonObject
            val changes: JsonArray = export.getValue("emailChanges").jsonArray
            changes.size shouldBe 1
            val change: JsonObject = changes.single().jsonObject
            change.getValue("pendingEmail").jsonPrimitive.content shouldBe fresh
            change.getValue("kind").jsonPrimitive.content shouldBe "PROPOSAL"
            change.getValue("status").jsonPrimitive.content shouldBe "PENDING"
            change.keys.none { it.contains("hash", ignoreCase = true) || it.contains("token", ignoreCase = true) } shouldBe true
            val text = export.toString()
            text.shouldNotContain(EmailChangeTokens.hash(confirm))
            text.shouldNotContain(EmailChangeTokens.hash(revoke))
            text.shouldNotContain(confirm)
        }

        test(
            "GDPR erasure: the member's own changes are hard-deleted in both modes, rows they initiated against others lose requested_by",
        ) {
            val svc = fx.service()
            ErasureMode.entries.forEach { mode ->
                val subject = fx.member(role = AccountRole.BOARD)
                val other = fx.member()
                val own = fx.member()
                val a = newAddress()
                val b = newAddress()
                // a change ABOUT the subject (by an admin) and a change the subject initiated ABOUT someone else
                svc.propose(actor = EC_SEED_ADMIN, targetIdRaw = subject.toString(), newEmail = a, newEmailRepeat = a)
                val initiated =
                    svc.propose(
                        actor = fx.actor(id = subject, role = AccountRole.BOARD),
                        targetIdRaw = other.toString(),
                        newEmail = b,
                        newEmailRepeat = b,
                    )
                fx.rowsOf(subject).size shouldBe 1

                val outcomes = transaction { RegistrationPersonalData.eraseMember(memberId = subject, mode = mode) }
                outcomes.single { it.table == "member_email_change" }.rowsDeleted shouldBe 1
                fx.rowsOf(subject).shouldBeEmpty()
                val remaining = fx.row(initiated.changeId)
                remaining[MemberEmailChangeTable.requestedBy] shouldBe null
                remaining[MemberEmailChangeTable.memberId] shouldBe other
                fx.openCount(other) shouldBe 1L
                transaction { MemberEmailChangeTable.selectAll().where { MemberEmailChangeTable.memberId eq own }.count() } shouldBe 0L
            }
        }

        test("an applied change by an initiator who has since been erased is discarded, not applied (requested_by is gone)") {
            val mailer = FakeEmailChangeMailer()
            val svc = fx.service(mailer = mailer)
            val initiatorId = fx.member(role = AccountRole.BOARD)
            val target = fx.member()
            val fresh = newAddress()
            val p =
                svc.propose(
                    actor = fx.actor(id = initiatorId, role = AccountRole.BOARD),
                    targetIdRaw = target.toString(),
                    newEmail = fresh,
                    newEmailRepeat = fresh,
                )
            transaction { RegistrationPersonalData.eraseMember(memberId = initiatorId, mode = ErasureMode.ANONYMIZE) }
            val before = fx.emailOf(target)
            svc.confirmByLink(rawToken = mailer.confirmMails.single().rawToken, password = EC_PASSWORD) shouldBe LinkResult.INVALID
            fx.statusOf(p.changeId) shouldBe "WITHDRAWN"
            fx.emailOf(target) shouldBe before
        }
    })
