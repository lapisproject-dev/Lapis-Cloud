package network.lapis.cloud.server.rpc

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.plugins.statuspages.StatusPagesConfig
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import network.lapis.cloud.server.crypto.SecretBox
import network.lapis.cloud.server.db.DatabaseConfig
import network.lapis.cloud.server.db.DevSeedData
import network.lapis.cloud.server.db.generated.AccountTable
import network.lapis.cloud.server.db.generated.AuditLogEntryTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.server.db.generated.MembershipAgreementAcknowledgmentTable
import network.lapis.cloud.server.db.generated.SessionTable
import network.lapis.cloud.server.federation.FederationInboxRateLimiter
import network.lapis.cloud.server.mail.FakeFriendVerificationMailer
import network.lapis.cloud.server.security.LoginRateLimiter
import network.lapis.cloud.server.webhook.WebhookConfig
import network.lapis.cloud.server.webhook.WebhookDeliveryQueue
import network.lapis.cloud.server.webhook.WebhookEndpointStore
import network.lapis.cloud.server.webhook.WebhookEventPublisher
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.AdminCreateMemberInput
import network.lapis.cloud.shared.domain.RegistrationInput
import network.lapis.cloud.shared.domain.WebhookEventType
import network.lapis.cloud.shared.rpc.ConflictException
import network.lapis.cloud.shared.rpc.ForbiddenException
import network.lapis.cloud.shared.rpc.NotFoundException
import network.lapis.cloud.shared.rpc.UnauthenticatedException
import network.lapis.cloud.shared.rpc.WeakPasswordException
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import java.security.SecureRandom
import kotlin.uuid.Uuid

private const val ADMIN_ID = "00000000-0000-0000-0000-000000000001"
private const val BOARD_ID = "00000000-0000-0000-0000-000000000002"
private const val STRONG_PASSWORD = "a-genuinely-strong-password-1"

/**
 * Welle V1.3.2 "Webhooks" (ausgehend) -- Design-Team decision D8's Thin-event scope, exercised end
 * to end through [RegistrationService] and [ApiKeyService] rather than through a fake call site:
 * only a `member.created` firing counted by actually inspecting [WebhookDeliveryQueue] rows for a
 * REAL active [WebhookEndpointStore] entry is trustworthy here -- unit-testing
 * [WebhookEventPublisher.publish] in isolation would not have caught the S24-adjacent gap this file
 * exists to guard: [RegistrationService.approveApplication] performs the APPLICATION -> ACTIVE
 * transition via a RAW `MemberTable.update`, never through [MemberService.updateMemberStatus] --
 * [network.lapis.cloud.shared.domain.MemberStatusTransitions.allowedTargets] deliberately excludes
 * APPLICATION as a `from` status (applications have their own dedicated approve/reject workflow),
 * so [MemberService.updateMemberStatus]'s own D8 hook structurally can never fire for this, the
 * MOST COMMON path a new member reaches ACTIVE (self-registration, then BOARD approval).
 * [RegistrationService.approveApplication] therefore carries its OWN `member.created` publish call
 * -- this file is the regression guard for that wiring.
 */
class WebhookEventScopeTest :
    FunSpec({
        val createdMemberIds = mutableListOf<Uuid>()

        beforeSpec {
            DatabaseConfig.connect()
            DevSeedData.seedIfEmpty(force = true)
            WebhookEventPublisher.install(
                WebhookConfig(
                    enabled = true,
                    allowInsecureHttp = false,
                    pollIntervalSeconds = 10,
                    maxDeliveriesPerTick = 50,
                    maxConcurrentDeliveries = 4,
                    retentionDays = 30,
                    secretEncryptionKey = null,
                ),
            )
        }

        afterSpec {
            WebhookEventPublisher.resetForTests()
            cleanUpWebhookEventScopeTestData(createdMemberIds)
        }

        fun randomKey(): ByteArray = ByteArray(SecretBox.KEY_SIZE_BYTES).also(SecureRandom()::nextBytes)

        /** A fresh, ACTIVE webhook endpoint on a throwaway API key -- [WebhookEventPublisher.publish]'s fan-out target for every test below. */
        fun freshActiveEndpoint(): Uuid {
            val apiKeyId =
                transaction {
                    network.lapis.cloud.server.security.ApiKeyStore
                        .issue(
                            label = "D8 scope test",
                            createdByMemberId = Uuid.parse(ADMIN_ID),
                        ).id
                }
            val secretBox = SecretBox(randomKey())
            val (row, _) =
                WebhookEndpointStore.create(
                    apiKeyId = apiKeyId,
                    url = "https://example.com/d8-scope-hook",
                    createdByMemberId = Uuid.parse(ADMIN_ID),
                    secretBox = secretBox,
                )
            return row.id
        }

        fun deliveriesFor(
            endpointId: Uuid,
            entityId: Uuid,
        ) = WebhookDeliveryQueue
            .listByEndpoint(endpointId = endpointId, limit = 100, offset = 0)
            .filter { it.entityId == entityId && it.eventType == WebhookEventType.MEMBER_CREATED }

        suspend fun testApp(block: suspend io.ktor.server.testing.ApplicationTestBuilder.() -> Unit) {
            testApplication {
                application {
                    install(StatusPages) { installWebhookScopeExceptionHandlers() }
                    routing { registerWebhookScopeTestRoutes() }
                }
                block()
            }
        }

        test("D8: self-registration (APPLICATION) fires NO member.created") {
            testApp {
                val endpointId = freshActiveEndpoint()
                val email = "d8-self-${Uuid.random()}@example.com"
                client.post("/test/register?email=$email").bodyAsText() shouldBe "OK"
                val memberId =
                    transaction { MemberTable.selectAll().where { MemberTable.email eq email.lowercase() }.single()[MemberTable.id] }
                createdMemberIds += memberId

                deliveriesFor(endpointId, memberId) shouldBe emptyList()
            }
        }

        test("D8: RegistrationService.createMemberDirect (ACTIVE) fires exactly one member.created") {
            testApp {
                val endpointId = freshActiveEndpoint()
                val email = "d8-direct-${Uuid.random()}@example.com"
                val resp =
                    client.post("/test/create-direct?email=$email") { header("X-Member-Id", BOARD_ID) }.bodyAsText()
                val memberId = Uuid.parse(resp.substringBefore(":"))
                createdMemberIds += memberId

                deliveriesFor(endpointId, memberId).size shouldBe 1
            }
        }

        test("D8 / S24 regression: RegistrationService.approveApplication (APPLICATION -> ACTIVE) fires exactly one member.created") {
            testApp {
                val endpointId = freshActiveEndpoint()
                val email = "d8-approve-${Uuid.random()}@example.com"
                client.post("/test/register?email=$email")
                val memberId =
                    transaction { MemberTable.selectAll().where { MemberTable.email eq email.lowercase() }.single()[MemberTable.id] }
                createdMemberIds += memberId

                // Before approval: still APPLICATION, no event yet.
                deliveriesFor(endpointId, memberId) shouldBe emptyList()

                client.post("/test/approve/$memberId") { header("X-Member-Id", BOARD_ID) }

                deliveriesFor(endpointId, memberId).size shouldBe 1
            }
        }
    })

private fun StatusPagesConfig.installWebhookScopeExceptionHandlers() {
    exception<UnauthenticatedException> {
        call,
        cause,
        ->
        call.respondText(cause.message, status = io.ktor.http.HttpStatusCode.Unauthorized)
    }
    exception<ForbiddenException> { call, cause -> call.respondText(cause.message, status = io.ktor.http.HttpStatusCode.Forbidden) }
    exception<NotFoundException> { call, cause -> call.respondText(cause.message, status = io.ktor.http.HttpStatusCode.NotFound) }
    exception<ConflictException> { call, cause -> call.respondText(cause.message, status = io.ktor.http.HttpStatusCode.Conflict) }
    exception<WeakPasswordException> { call, cause -> call.respondText(cause.message, status = io.ktor.http.HttpStatusCode.BadRequest) }
}

/** Minimal throwaway routes -- only the three D8-relevant `RegistrationService` calls this file exercises, mirrors [RegistrationServiceTest]'s own house style. */
private fun Route.registerWebhookScopeTestRoutes() {
    val registrationRateLimiter = LoginRateLimiter()
    val friendRateLimiter = LoginRateLimiter()
    val friendIpRateLimiter = FederationInboxRateLimiter()

    fun registrationService(call: ApplicationCall) =
        RegistrationService(
            call = call,
            registrationRateLimiter = registrationRateLimiter,
            friendRegistrationRateLimiter = friendRateLimiter,
            friendSignupIpRateLimiter = friendIpRateLimiter,
            friendVerificationMailer = FakeFriendVerificationMailer(),
        )

    post("/test/register") {
        val email = call.request.queryParameters["email"]!!
        registrationService(call).registerApplication(
            RegistrationInput(
                displayName = "D8 Scope Testmitglied",
                email = email,
                password = STRONG_PASSWORD,
                agreementVersion = MembershipAgreementDisclaimer.VERSION,
                agreementSha256 = MembershipAgreementDisclaimer.SHA256,
            ),
        )
        call.respondText("OK")
    }
    post("/test/approve/{id}") {
        val dto = registrationService(call).approveApplication(memberId = call.parameters["id"]!!)
        call.respondText(dto.status.name)
    }
    post("/test/create-direct") {
        val email = call.request.queryParameters["email"]!!
        val dto =
            registrationService(call).createMemberDirect(
                AdminCreateMemberInput(
                    displayName = "D8 Scope Direktmitglied",
                    email = email,
                    role = AccountRole.MEMBER,
                    temporaryPassword = STRONG_PASSWORD,
                ),
            )
        call.respondText("${dto.id}:${dto.status}")
    }
}

/** Mirrors [RegistrationServiceTest]'s own `cleanUpRegistrationTestData` ordering (FKs before the `member` row itself). */
private fun cleanUpWebhookEventScopeTestData(memberIds: List<Uuid>) {
    if (memberIds.isEmpty()) return
    transaction {
        AuditLogEntryTable.update({ AuditLogEntryTable.actorMemberId inList memberIds }) { it[actorMemberId] = null }
        SessionTable.deleteWhere { SessionTable.memberId inList memberIds }
        MembershipAgreementAcknowledgmentTable.deleteWhere { MembershipAgreementAcknowledgmentTable.memberId inList memberIds }
        AccountTable.deleteWhere { AccountTable.memberId inList memberIds }
        MemberTable.deleteWhere { MemberTable.id inList memberIds }
    }
}
