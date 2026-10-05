package network.lapis.cloud.server.rpc

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.response.respondText
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import network.lapis.cloud.server.db.DatabaseConfig
import network.lapis.cloud.server.db.DevSeedData
import network.lapis.cloud.server.db.generated.MemberPhotoTable
import network.lapis.cloud.server.embed.EmbedConfig
import network.lapis.cloud.server.federation.FederationInboxRateLimiter
import network.lapis.cloud.server.keycloak.KeycloakConfig
import network.lapis.cloud.server.mail.FakeAdminPasswordResetNotificationMailer
import network.lapis.cloud.server.mail.FakeFriendVerificationMailer
import network.lapis.cloud.server.mail.FakePasswordResetMailer
import network.lapis.cloud.server.mail.SmtpConfigState
import network.lapis.cloud.server.memberphoto.MemberPhotoFixtures
import network.lapis.cloud.server.memberphoto.MemberPhotoStorage
import network.lapis.cloud.server.routes.registerMemberPhotoRoutes
import network.lapis.cloud.server.security.LoginRateLimiter
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.MemberPhotoVisibility
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.rpc.ConflictException
import network.lapis.cloud.shared.rpc.ForbiddenException
import network.lapis.cloud.shared.rpc.NotFoundException
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.time.Duration.Companion.minutes

/**
 * Welle V1.9.19 "Mitglieder-Foto" -- a member who leaves the eligible status set loses a PUBLISHED
 * photo in the SAME transaction as the status change (`MemberService.updateMemberStatus`,
 * `RegistrationService.leaveMembership`): the row goes back to PRIVATE, token and consent are
 * cleared, the audit entry `UNPUBLISHED_BY_STATUS_CHANGE` exists, the public URL answers 404 -- and
 * a later reactivation does NOT silently bring the publication back.
 */
class MemberPhotoStatusLossTest :
    FunSpec({
        val fixtures = MemberPhotoFixtures()

        beforeSpec {
            DatabaseConfig.connect()
            DevSeedData.seedIfEmpty(force = true)
        }
        afterSpec { fixtures.cleanup() }

        fun routes(storage: MemberPhotoStorage): Application.() -> Unit =
            {
                install(ContentNegotiation) { json() }
                install(StatusPages) {
                    exception<ForbiddenException> { call, cause -> call.respondText(cause.message, status = HttpStatusCode.Forbidden) }
                    exception<NotFoundException> { call, cause -> call.respondText(cause.message, status = HttpStatusCode.NotFound) }
                    exception<ConflictException> { call, cause -> call.respondText(cause.message, status = HttpStatusCode.Conflict) }
                }
                routing {
                    registerMemberPhotoRoutes(
                        storage = storage,
                        baseUrl = "https://lapis.example.org",
                        embedConfig = EmbedConfig.DISABLED,
                        uploadRateLimiter = FederationInboxRateLimiter(maxRequests = 100, window = 1.minutes),
                        ownReadRateLimiter = FederationInboxRateLimiter(maxRequests = 100, window = 1.minutes),
                        publicReadRateLimiter = FederationInboxRateLimiter(maxRequests = 1000, window = 1.minutes),
                    )
                    post("/test/status/{id}") {
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

        fun rowOf(member: kotlin.uuid.Uuid) =
            transaction { MemberPhotoTable.selectAll().where { MemberPhotoTable.memberId eq member }.single() }

        fun statusLossEntries(member: kotlin.uuid.Uuid): Int =
            fixtures.memberAuditAfterSnapshots(member).count { it.contains("UNPUBLISHED_BY_STATUS_CHANGE") }

        test("updateMemberStatus ACTIVE -> WITHDRAWN / DONOR / DECEASED revokes the publication in the same transaction") {
            val storage = MemberPhotoStorage(MemberPhotoFixtures.freshRoot("loss-admin"))
            testApplication {
                application(routes(storage))
                val admin = fixtures.newMember(role = AccountRole.ADMIN)
                listOf(MemberStatus.WITHDRAWN, MemberStatus.DONOR, MemberStatus.DECEASED).forEach { target ->
                    val member = fixtures.newMember()
                    val token = fixtures.seedPhoto(storage = storage, memberId = member, publish = true)!!
                    client.get("/public/member-photos/$token").status shouldBe HttpStatusCode.OK

                    val response = client.post("/test/status/$member?status=${target.name}") { header("X-Member-Id", admin.toString()) }
                    response.status shouldBe HttpStatusCode.OK

                    val row = rowOf(member)
                    row[MemberPhotoTable.visibility] shouldBe MemberPhotoVisibility.PRIVATE
                    row[MemberPhotoTable.publicToken] shouldBe null
                    row[MemberPhotoTable.consentGrantedAt] shouldBe null
                    row[MemberPhotoTable.consentTextVersion] shouldBe null
                    statusLossEntries(member) shouldBe 1
                    client.get("/public/member-photos/$token").status shouldBe HttpStatusCode.NotFound
                }
            }
        }

        test("a reactivation does NOT silently bring the publication back") {
            val storage = MemberPhotoStorage(MemberPhotoFixtures.freshRoot("loss-reactivate"))
            testApplication {
                application(routes(storage))
                val admin = fixtures.newMember(role = AccountRole.ADMIN)
                val member = fixtures.newMember()
                val token = fixtures.seedPhoto(storage = storage, memberId = member, publish = true)!!
                client.post("/test/status/$member?status=WITHDRAWN") { header("X-Member-Id", admin.toString()) }.status shouldBe
                    HttpStatusCode.OK
                client.post("/test/status/$member?status=ACTIVE") { header("X-Member-Id", admin.toString()) }.status shouldBe
                    HttpStatusCode.OK
                rowOf(member)[MemberPhotoTable.visibility] shouldBe MemberPhotoVisibility.PRIVATE
                fixtures.tokenOf(member) shouldBe null
                client.get("/public/member-photos/$token").status shouldBe HttpStatusCode.NotFound
            }
        }

        test("a PRIVATE photo is left alone on status loss: no state change and no audit entry") {
            val storage = MemberPhotoStorage(MemberPhotoFixtures.freshRoot("loss-private"))
            testApplication {
                application(routes(storage))
                val admin = fixtures.newMember(role = AccountRole.ADMIN)
                val member = fixtures.newMember()
                fixtures.seedPhoto(storage = storage, memberId = member, publish = false)
                client.post("/test/status/$member?status=WITHDRAWN") { header("X-Member-Id", admin.toString()) }.status shouldBe
                    HttpStatusCode.OK
                fixtures.rowCount(member) shouldBe 1
                statusLossEntries(member) shouldBe 0
            }
        }

        test("leaveMembership (self-service exit) revokes the publication too") {
            val storage = MemberPhotoStorage(MemberPhotoFixtures.freshRoot("loss-leave"))
            testApplication {
                application(routes(storage))
                val member = fixtures.newMember()
                val token = fixtures.seedPhoto(storage = storage, memberId = member, publish = true)!!
                client.post("/test/leave") { header("X-Member-Id", member.toString()) }.status shouldBe HttpStatusCode.OK
                rowOf(member)[MemberPhotoTable.visibility] shouldBe MemberPhotoVisibility.PRIVATE
                fixtures.tokenOf(member) shouldBe null
                statusLossEntries(member) shouldBe 1
                client.get("/public/member-photos/$token").status shouldBe HttpStatusCode.NotFound
            }
        }
    })
