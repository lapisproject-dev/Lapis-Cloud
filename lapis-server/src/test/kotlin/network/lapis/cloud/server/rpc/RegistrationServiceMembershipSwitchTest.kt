package network.lapis.cloud.server.rpc

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.datetime.LocalDate
import network.lapis.cloud.server.db.DatabaseConfig
import network.lapis.cloud.server.db.DevSeedData
import network.lapis.cloud.server.db.generated.AccountTable
import network.lapis.cloud.server.db.generated.FriendEmailVerificationTokenTable
import network.lapis.cloud.server.db.generated.FriendTermsAcknowledgmentTable
import network.lapis.cloud.server.db.generated.MemberStatusHistoryTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.server.db.generated.MembershipAgreementAcknowledgmentTable
import network.lapis.cloud.server.db.generated.OrganizationSettingsTable
import network.lapis.cloud.server.federation.FederationInboxRateLimiter
import network.lapis.cloud.server.keycloak.KeycloakConfig
import network.lapis.cloud.server.mail.FakeFriendVerificationMailer
import network.lapis.cloud.server.security.LoginRateLimiter
import network.lapis.cloud.server.security.PasswordHasher
import network.lapis.cloud.server.testdb.legacyAccountRole
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.FriendRegistrationInput
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.domain.RegistrationInput
import network.lapis.cloud.shared.rpc.MEMBERSHIP_APPLICATION_CLOSED_MESSAGE
import network.lapis.cloud.shared.rpc.MembershipApplicationClosedException
import network.lapis.cloud.shared.rpc.UnauthenticatedException
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import kotlin.uuid.Uuid

private const val PASSWORD = "a-genuinely-strong-password-1"

/**
 * V1.9.315 -- the server gate of the switch "Mitglied werden": with the switch off every self-service path that creates a membership
 * application ([RegistrationService.registerApplication], [RegistrationService.getMembershipAgreement],
 * [RegistrationService.applyForMembership]) answers the same constant [MembershipApplicationClosedException], independent of the input
 * (no existence oracle), without any database write or rate-limiter entry. Friend registration and the chapter list are unaffected.
 */
class RegistrationServiceMembershipSwitchTest :
    FunSpec({
        val createdMemberIds = mutableListOf<Uuid>()

        beforeSpec {
            DatabaseConfig.connect()
            DevSeedData.seedIfEmpty(force = true)
        }

        fun setEnabled(value: Boolean) =
            transaction {
                OrganizationSettingsTable.update({ OrganizationSettingsTable.id eq ORGANIZATION_SETTINGS_ID }) {
                    it[membershipApplicationEnabled] = value
                }
            }

        beforeTest { setEnabled(true) }

        afterSpec {
            setEnabled(true)
            transaction {
                if (createdMemberIds.isNotEmpty()) {
                    FriendEmailVerificationTokenTable.deleteWhere { FriendEmailVerificationTokenTable.memberId inList createdMemberIds }
                    FriendTermsAcknowledgmentTable.deleteWhere { FriendTermsAcknowledgmentTable.memberId inList createdMemberIds }
                    MembershipAgreementAcknowledgmentTable.deleteWhere {
                        MembershipAgreementAcknowledgmentTable.memberId inList
                            createdMemberIds
                    }
                    AccountTable.deleteWhere { AccountTable.memberId inList createdMemberIds }
                    MemberStatusHistoryTable.deleteWhere { MemberStatusHistoryTable.memberId inList createdMemberIds }
                    MemberTable.deleteWhere { MemberTable.id inList createdMemberIds }
                }
            }
        }

        fun idByEmail(email: String): Uuid? =
            transaction {
                MemberTable
                    .selectAll()
                    .where { MemberTable.email eq email }
                    .singleOrNull()
                    ?.get(MemberTable.id)
            }

        fun memberCount(): Long = transaction { MemberTable.selectAll().count() }

        fun accountCount(): Long = transaction { AccountTable.selectAll().count() }

        fun friend(email: String): Uuid {
            val id = Uuid.random()
            transaction {
                MemberTable.insert {
                    it[MemberTable.id] = id
                    it[displayName] = "Schalter Freund"
                    it[MemberTable.email] = email
                    it[status] = MemberStatus.FRIEND
                    it[joinedAt] = LocalDate(2026, 1, 1)
                    it[membershipTierId] = null
                }
                AccountTable.insert {
                    it[AccountTable.id] = Uuid.random()
                    it[memberId] = id
                    it.legacyAccountRole(role = AccountRole.MEMBER)
                    it[passwordHash] = PasswordHasher.hash(PASSWORD)
                }
            }
            createdMemberIds += id
            return id
        }

        fun withApp(
            limiter: LoginRateLimiter = LoginRateLimiter(),
            keycloak: KeycloakConfig = KeycloakConfig.load(env = { null }),
            block: suspend io.ktor.server.testing.ApplicationTestBuilder.() -> Unit,
        ) = testApplication {
            application {
                install(StatusPages) {
                    exception<MembershipApplicationClosedException> { call, cause ->
                        call.respondText(cause.message, status = HttpStatusCode.Gone)
                    }
                    exception<UnauthenticatedException> { call, cause ->
                        call.respondText(cause.message, status = HttpStatusCode.Unauthorized)
                    }
                }
                routing { registerMembershipSwitchTestRoutes(limiter = limiter, keycloak = keycloak) }
            }
            block()
        }

        test("switch on: registerApplication works as before") {
            withApp {
                val email = "switch-on-${Uuid.random()}@example.org"
                client.post("/t/register?email=$email").status shouldBe HttpStatusCode.OK
                createdMemberIds += requireNotNull(idByEmail(email))
                client.get("/t/agreement").status shouldBe HttpStatusCode.OK
            }
        }

        test("switch off: a new and an existing e-mail get the IDENTICAL constant answer, nothing is written, the limiter is untouched") {
            withApp(limiter = LoginRateLimiter(maxFailures = 2)) {
                val existing = "switch-existing-${Uuid.random()}@example.org"
                client.post("/t/register?email=$existing").status shouldBe HttpStatusCode.OK
                createdMemberIds += requireNotNull(idByEmail(existing))

                setEnabled(false)
                val members = memberCount()
                val accounts = accountCount()
                val fresh = "switch-fresh-${Uuid.random()}@example.org"
                val a = client.post("/t/register?email=$fresh")
                val b = client.post("/t/register?email=$existing")
                a.status shouldBe HttpStatusCode.Gone
                b.status shouldBe a.status
                b.bodyAsText() shouldBe a.bodyAsText()
                a.bodyAsText() shouldBe MEMBERSHIP_APPLICATION_CLOSED_MESSAGE
                repeat(6) { client.post("/t/register?email=$fresh") }
                memberCount() shouldBe members
                accountCount() shouldBe accounts
                idByEmail(fresh) shouldBe null

                // The failure-window limiter (2 failures) was never fed by the closed calls: switched on again, the first one works.
                setEnabled(true)
                client.post("/t/register?email=$fresh").status shouldBe HttpStatusCode.OK
                createdMemberIds += requireNotNull(idByEmail(fresh))
            }
        }

        test("switch off: the closed answer does not depend on the input (invalid input, even Keycloak mode) and names no Keycloak") {
            withApp(
                keycloak =
                    KeycloakConfig.load(env = { name -> if (name == KeycloakConfig.ENV_ENABLED) "true" else null }),
            ) {
                setEnabled(false)
                val answer = client.post("/t/register?email=not-an-email&password=x")
                answer.status shouldBe HttpStatusCode.Gone
                answer.bodyAsText() shouldBe MEMBERSHIP_APPLICATION_CLOSED_MESSAGE
                answer.bodyAsText().lowercase() shouldNotContain "keycloak"
            }
        }

        test("switch off: getMembershipAgreement is refused, the chapter list and friend registration keep working") {
            withApp {
                setEnabled(false)
                client.get("/t/agreement").status shouldBe HttpStatusCode.Gone
                client.get("/t/chapters").status shouldBe HttpStatusCode.OK
                val email = "switch-friend-${Uuid.random()}@example.org"
                client.post("/t/register-friend?email=$email").status shouldBe HttpStatusCode.OK
                createdMemberIds += requireNotNull(idByEmail(email))
            }
        }

        test("switch off: applyForMembership (FRIEND to APPLICATION) is refused and the friend stays a friend; on again it works") {
            withApp {
                val id = friend("switch-friend-apply-${Uuid.random()}@example.org")
                setEnabled(false)
                client.post("/t/apply") { header("X-Member-Id", id.toString()) }.status shouldBe HttpStatusCode.Gone
                transaction { MemberTable.selectAll().where { MemberTable.id eq id }.single()[MemberTable.status] } shouldBe
                    MemberStatus.FRIEND
                setEnabled(true)
                client.post("/t/apply") { header("X-Member-Id", id.toString()) }.status shouldBe HttpStatusCode.OK
                transaction { MemberTable.selectAll().where { MemberTable.id eq id }.single()[MemberTable.status] } shouldBe
                    MemberStatus.APPLICATION
            }
        }

        test("the display store answers on when the row is unreadable and the strict load reads the real value") {
            setEnabled(false)
            transaction { MembershipApplicationStore.load() } shouldBe false
            MembershipApplicationStore.currentValueForDisplay() shouldBe false
            setEnabled(true)
            MembershipApplicationStore.currentValueForDisplay() shouldBe true
            membershipApplicationOpen(enabled = true, keycloakEnabled = false) shouldBe true
            membershipApplicationOpen(enabled = true, keycloakEnabled = true) shouldBe false
            membershipApplicationOpen(enabled = false, keycloakEnabled = false) shouldBe false
        }
    })

private fun Route.registerMembershipSwitchTestRoutes(
    limiter: LoginRateLimiter,
    keycloak: KeycloakConfig,
) {
    fun service(call: ApplicationCall) =
        RegistrationService(
            call = call,
            registrationRateLimiter = limiter,
            friendRegistrationRateLimiter = LoginRateLimiter(),
            friendSignupIpRateLimiter = FederationInboxRateLimiter(),
            friendVerificationMailer = FakeFriendVerificationMailer(),
            keycloakConfig = keycloak,
        )
    post("/t/register") {
        val q = call.request.queryParameters
        service(call).registerApplication(
            RegistrationInput(
                displayName = "Schalter Test",
                email = q["email"]!!,
                password = q["password"] ?: PASSWORD,
                agreementVersion = MembershipAgreementDisclaimer.VERSION,
                agreementSha256 = MembershipAgreementDisclaimer.SHA256,
            ),
        )
        call.respondText("OK")
    }
    get("/t/agreement") { call.respondText(service(call).getMembershipAgreement().version) }
    get("/t/chapters") { call.respondText(service(call).listRegionalChapterOptions().size.toString()) }
    post("/t/register-friend") {
        service(call).registerFriend(
            FriendRegistrationInput(
                displayName = "Schalter Freund",
                email = call.request.queryParameters["email"]!!,
                password = PASSWORD,
                termsVersion = FriendTermsDisclaimer.VERSION,
                termsSha256 = FriendTermsDisclaimer.SHA256,
            ),
        )
        call.respondText("OK")
    }
    post("/t/apply") {
        val dto =
            service(call).applyForMembership(
                agreementVersion = MembershipAgreementDisclaimer.VERSION,
                agreementSha256 = MembershipAgreementDisclaimer.SHA256,
                regionalChapterId = null,
            )
        call.respondText(dto.status.name)
    }
}
