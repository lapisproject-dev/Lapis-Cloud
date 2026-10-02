package network.lapis.cloud.server.rpc

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.response.respondText
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import network.lapis.cloud.server.db.DatabaseConfig
import network.lapis.cloud.server.db.DevSeedData
import network.lapis.cloud.server.db.generated.MemberPublicBioTable
import network.lapis.cloud.server.federation.FederationInboxRateLimiter
import network.lapis.cloud.server.keycloak.KeycloakConfig
import network.lapis.cloud.server.mail.FakeAdminPasswordResetNotificationMailer
import network.lapis.cloud.server.mail.FakeFriendVerificationMailer
import network.lapis.cloud.server.mail.FakePasswordResetMailer
import network.lapis.cloud.server.mail.SmtpConfigState
import network.lapis.cloud.server.memberbio.PublicProfilesFixtures
import network.lapis.cloud.server.routes.PublicProfilesReader
import network.lapis.cloud.server.security.LoginRateLimiter
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.CommitteeRole
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.domain.PublicRankingKind
import network.lapis.cloud.shared.rpc.ConflictException
import network.lapis.cloud.shared.rpc.ForbiddenException
import network.lapis.cloud.shared.rpc.NotFoundException
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.uuid.Uuid

/**
 * Welle V1.9.20 "Öffentliche Seiten" -- a member who leaves the ORGANIZATION_MEMBER status set loses a
 * PUBLISHED short introduction AND a granted politician-listing consent in the SAME transaction as the
 * status change (`MemberService.updateMemberStatus`, `RegistrationService.leaveMembership`); both vanish
 * from the public read layer at once and a later reactivation does NOT bring either back.
 */
class MemberPublicBioStatusLossTest :
    FunSpec({
        val fixtures = PublicProfilesFixtures()

        beforeSpec {
            DatabaseConfig.connect()
            DevSeedData.seedIfEmpty(force = true)
        }
        afterSpec { fixtures.cleanup() }
        beforeTest { fixtures.neutralizeForeignProfiles() }

        fun routes(): Application.() -> Unit =
            {
                install(ContentNegotiation) { json() }
                install(StatusPages) {
                    exception<ForbiddenException> { call, cause -> call.respondText(cause.message, status = HttpStatusCode.Forbidden) }
                    exception<NotFoundException> { call, cause -> call.respondText(cause.message, status = HttpStatusCode.NotFound) }
                    exception<ConflictException> { call, cause -> call.respondText(cause.message, status = HttpStatusCode.Conflict) }
                }
                routing {
                    post("/test/status/{id}") {
                        val service =
                            MemberService(
                                call = call,
                                friendVerificationMailer = FakeFriendVerificationMailer(),
                                memberCoreDataFriendMailRateLimiter = FederationInboxRateLimiter(),
                                memberCoreDataFriendMailActorRateLimiter = FederationInboxRateLimiter(),
                                passwordResetMailer = FakePasswordResetMailer(),
                                adminPasswordResetNotificationMailer = FakeAdminPasswordResetNotificationMailer(),
                                smtpConfigState = SmtpConfigState.NotConfigured,
                                adminPasswordMailTargetRateLimiter = FederationInboxRateLimiter(),
                                adminPasswordMailActorRateLimiter = FederationInboxRateLimiter(),
                                adminPasswordNotificationTargetRateLimiter = FederationInboxRateLimiter(),
                                memberCardIssueRateLimiter = FederationInboxRateLimiter(),
                                memberAddressAdminReadRateLimiter = FederationInboxRateLimiter(),
                                regionalChapterEnforcementConfig = RegionalChapterEnforcementConfig.load(env = { null }),
                            )
                        val dto =
                            service.updateMemberStatus(
                                memberId = call.parameters["id"]!!,
                                newStatus = MemberStatus.valueOf(call.request.queryParameters["status"]!!),
                                reason = "Testgrund fuer Statuswechsel",
                                dateOfDeath = null,
                            )
                        call.respondText(dto.status.name)
                    }
                    post("/test/leave") {
                        val service =
                            RegistrationService(
                                call = call,
                                registrationRateLimiter = LoginRateLimiter(),
                                friendRegistrationRateLimiter = LoginRateLimiter(),
                                friendSignupIpRateLimiter = FederationInboxRateLimiter(),
                                friendVerificationMailer = FakeFriendVerificationMailer(),
                                keycloakConfig = KeycloakConfig.load(env = { null }),
                                regionalChapterEnforcementConfig = RegionalChapterEnforcementConfig.load(env = { null }),
                            )
                        call.respondText(service.leaveMembership().status.name)
                    }
                }
            }

        fun boardNames() = transaction { PublicProfilesReader.loadBoardCards() }.map { it.card.name }

        fun boardBios() = transaction { PublicProfilesReader.loadBoardCards() }.associate { it.card.name to it.card.bio }

        fun politicianNames() = transaction { PublicProfilesReader.loadPoliticianCards() }.map { it.name }

        fun listingEffective(member: Uuid) =
            transaction { PublicRankingConsentStore.currentState(member) }
                .single { it.kind == PublicRankingKind.POLITICIAN_LISTING }
                .effective

        test("ACTIVE -> WITHDRAWN / DONOR / DECEASED unpublishes the bio and revokes the listing consent in the same transaction") {
            testApplication {
                application(routes())
                val admin = fixtures.newMember(role = AccountRole.ADMIN)
                listOf(MemberStatus.WITHDRAWN, MemberStatus.DONOR, MemberStatus.DECEASED).forEach { target ->
                    val member = fixtures.newMember(displayName = "Statusverlust ${target.name}")
                    fixtures.makePolitician(memberId = member)
                    fixtures.grantConsent(memberId = member)
                    fixtures.seedBio(memberId = member, text = "Oeffentlicher Text", publish = true)
                    politicianNames().contains("Statusverlust ${target.name}") shouldBe true

                    client.post("/test/status/$member?status=${target.name}") { header("X-Member-Id", admin.toString()) }.status shouldBe
                        HttpStatusCode.OK

                    val row = fixtures.bioRow(member)!!
                    row[MemberPublicBioTable.consentGrantedAt] shouldBe null
                    row[MemberPublicBioTable.consentTextVersion] shouldBe null
                    listingEffective(member) shouldBe false
                    politicianNames().contains("Statusverlust ${target.name}") shouldBe false
                    fixtures.memberAuditAfterSnapshots(member).count { it.contains("REVOKED_ON_STATUS_LOSS") } shouldBe 1
                }
            }
        }

        test("a reactivation does NOT silently bring the bio or the listing back") {
            testApplication {
                application(routes())
                val admin = fixtures.newMember(role = AccountRole.ADMIN)
                val member = fixtures.newMember(displayName = "Reaktiviert Rita")
                fixtures.addBoardSeat(memberId = member, role = CommitteeRole.ASSESSOR)
                fixtures.makePolitician(memberId = member)
                fixtures.grantConsent(memberId = member)
                fixtures.seedBio(memberId = member, text = "Mein Text", publish = true)
                boardBios()["Reaktiviert Rita"] shouldBe "Mein Text"

                client.post("/test/status/$member?status=WITHDRAWN") { header("X-Member-Id", admin.toString()) }.status shouldBe
                    HttpStatusCode.OK
                client.post("/test/status/$member?status=ACTIVE") { header("X-Member-Id", admin.toString()) }.status shouldBe
                    HttpStatusCode.OK

                // The board seat is a public-record office (name + role), but the bio and the listing stay OFF.
                boardBios()["Reaktiviert Rita"] shouldBe null
                politicianNames().contains("Reaktiviert Rita") shouldBe false
                listingEffective(member) shouldBe false
            }
        }

        test("a PRIVATE bio and a member without a consent are left alone: no state change, no status-loss audit entry") {
            testApplication {
                application(routes())
                val admin = fixtures.newMember(role = AccountRole.ADMIN)
                val member = fixtures.newMember()
                fixtures.seedBio(memberId = member, text = "Nur privat", publish = false)
                client.post("/test/status/$member?status=WITHDRAWN") { header("X-Member-Id", admin.toString()) }.status shouldBe
                    HttpStatusCode.OK
                fixtures.bioRowCount(member) shouldBe 1L
                fixtures.memberAuditAfterSnapshots(member).count { it.contains("REVOKED_ON_STATUS_LOSS") } shouldBe 0
            }
        }

        test("leaveMembership (self-service exit) revokes the publication and the listing too") {
            testApplication {
                application(routes())
                val member = fixtures.newMember(displayName = "Austritt Anton")
                fixtures.makePolitician(memberId = member)
                fixtures.grantConsent(memberId = member)
                fixtures.seedBio(memberId = member, text = "Text", publish = true)
                client.post("/test/leave") { header("X-Member-Id", member.toString()) }.status shouldBe HttpStatusCode.OK
                transaction { MemberPublicBioTable.selectAll().where { MemberPublicBioTable.memberId eq member }.single() }[
                    MemberPublicBioTable.consentGrantedAt,
                ] shouldBe null
                listingEffective(member) shouldBe false
                politicianNames().contains("Austritt Anton") shouldBe false
                fixtures.memberAuditAfterSnapshots(member).count { it.contains("REVOKED_ON_STATUS_LOSS") } shouldBe 1
            }
        }
    })
