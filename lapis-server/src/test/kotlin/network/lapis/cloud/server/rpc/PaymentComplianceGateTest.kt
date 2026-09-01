package network.lapis.cloud.server.rpc

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.plugins.statuspages.StatusPagesConfig
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
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.server.db.generated.OrganizationSettingsTable
import network.lapis.cloud.server.db.generated.PaymentGatewayComplianceAcknowledgmentTable
import network.lapis.cloud.server.db.generated.SepaComplianceAcknowledgmentTable
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.domain.PaymentGatewayComplianceAcknowledgmentInput
import network.lapis.cloud.shared.domain.PaymentProvider
import network.lapis.cloud.shared.domain.SepaComplianceAcknowledgmentInput
import network.lapis.cloud.shared.rpc.BadRequestException
import network.lapis.cloud.shared.rpc.ConflictException
import network.lapis.cloud.shared.rpc.ForbiddenException
import network.lapis.cloud.shared.rpc.NotFoundException
import network.lapis.cloud.shared.rpc.UnauthenticatedException
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import kotlin.uuid.Uuid

private const val ADMIN_ID = "00000000-0000-0000-0000-000000000001"

/**
 * Exercises [ISepaService]/[IPaymentGatewayService]'s opt-in-gate mechanism end to end -- both are
 * exact mirrors of [AuctionService]'s own `enableAuction`/`disableAuction` mechanism (see
 * [AuctionServiceTest] for the fuller precedent this test's harness mirrors), so this Spec only
 * covers what is genuinely new: the gate itself, for BOTH services, plus
 * [PaymentGatewayService.enablePaymentGateway]'s extra `provider` guard.
 */
class PaymentComplianceGateTest :
    FunSpec({
        val createdMemberIds = mutableListOf<Uuid>()

        beforeSpec {
            DatabaseConfig.connect()
            DevSeedData.seedIfEmpty(force = true)
        }

        afterTest {
            transaction {
                SepaComplianceAcknowledgmentTable.deleteWhere {
                    SepaComplianceAcknowledgmentTable.id eq SepaComplianceAcknowledgmentTable.id
                }
                PaymentGatewayComplianceAcknowledgmentTable.deleteWhere {
                    PaymentGatewayComplianceAcknowledgmentTable.id eq PaymentGatewayComplianceAcknowledgmentTable.id
                }
                OrganizationSettingsTable.update({ OrganizationSettingsTable.id eq ORGANIZATION_SETTINGS_ID }) {
                    it[sepaDebitEnabled] = false
                    it[paymentGatewayEnabled] = false
                    it[paymentGatewayProvider] = null
                }
            }
        }

        afterSpec {
            transaction {
                if (createdMemberIds.isNotEmpty()) {
                    AccountTable.deleteWhere { AccountTable.memberId inList createdMemberIds }
                    MemberTable.deleteWhere { MemberTable.id inList createdMemberIds }
                }
            }
        }

        fun createTestMember(email: String): Uuid {
            val id = Uuid.random()
            transaction {
                MemberTable.insert {
                    it[MemberTable.id] = id
                    it[displayName] = "Payment-Gate Testmitglied"
                    it[MemberTable.email] = email
                    it[status] = MemberStatus.ACTIVE
                    it[joinedAt] = LocalDate(2026, 1, 1)
                    it[membershipTierId] = null
                }
                AccountTable.insert {
                    it[AccountTable.id] = Uuid.random()
                    it[memberId] = id
                    it[role] = AccountRole.MEMBER
                }
            }
            createdMemberIds += id
            return id
        }

        test(
            "SEPA gate: MEMBER forbidden, wrong hash rejected with zero side effect, correct hash enables + writes one auditable ack row",
        ) {
            testApplication {
                application {
                    install(StatusPages) { installPaymentGateExceptionHandlers() }
                    routing { registerPaymentGateTestRoutes() }
                }
                val member = createTestMember("sepa-gate-member-${Uuid.random()}@example.org")

                val forbidden = client.get("/test/sepa-disclaimer") { header("X-Member-Id", member.toString()) }
                forbidden.status shouldBe HttpStatusCode.Forbidden

                val disclaimer = client.get("/test/sepa-disclaimer") { header("X-Member-Id", ADMIN_ID) }.bodyAsText().split("|")
                val version = disclaimer[0]
                val sha256 = disclaimer[1]

                val wrongHash =
                    client.post("/test/sepa-enable?version=$version&sha256=deadbeef") { header("X-Member-Id", ADMIN_ID) }
                wrongHash.status shouldBe HttpStatusCode.Conflict
                transaction { SepaComplianceAcknowledgmentTable.selectAll().count() } shouldBe 0L

                val correct = client.post("/test/sepa-enable?version=$version&sha256=$sha256") { header("X-Member-Id", ADMIN_ID) }
                correct.status shouldBe HttpStatusCode.OK
                correct.bodyAsText() shouldBe "true"
                transaction { SepaComplianceAcknowledgmentTable.selectAll().count() } shouldBe 1L

                val settings = client.get("/test/sepa-settings") { header("X-Member-Id", ADMIN_ID) }.bodyAsText().split("|")
                settings[0] shouldBe "true"

                val disabled = client.post("/test/sepa-disable") { header("X-Member-Id", ADMIN_ID) }.bodyAsText()
                disabled shouldBe "false"
                // Disabling does NOT erase the acknowledgment history.
                transaction { SepaComplianceAcknowledgmentTable.selectAll().count() } shouldBe 1L
            }
        }

        test(
            "Payment-gateway gate: provider=MANUAL rejected, wrong hash rejected, correct hash+STRIPE enables + records provider " +
                "(Welle V1.2.8: provider=PAYPAL is now ALSO rejected -- see IPaymentGatewayService class KDoc)",
        ) {
            testApplication {
                application {
                    install(StatusPages) { installPaymentGateExceptionHandlers() }
                    routing { registerPaymentGateTestRoutes() }
                }
                val disclaimer = client.get("/test/gateway-disclaimer") { header("X-Member-Id", ADMIN_ID) }.bodyAsText().split("|")
                val version = disclaimer[0]
                val sha256 = disclaimer[1]

                val manualRejected =
                    client.post("/test/gateway-enable?provider=MANUAL&version=$version&sha256=$sha256") {
                        header("X-Member-Id", ADMIN_ID)
                    }
                manualRejected.status shouldBe HttpStatusCode.BadRequest

                // Welle V1.2.8 scope decision -- PayPal stays a valid PaymentProvider literal
                // (enum-order-pinned by PaymentsSchemaDriftTest) but is no longer accepted here.
                val paypalRejected =
                    client.post("/test/gateway-enable?provider=PAYPAL&version=$version&sha256=$sha256") {
                        header("X-Member-Id", ADMIN_ID)
                    }
                paypalRejected.status shouldBe HttpStatusCode.BadRequest

                val wrongHash =
                    client.post("/test/gateway-enable?provider=STRIPE&version=$version&sha256=deadbeef") {
                        header("X-Member-Id", ADMIN_ID)
                    }
                wrongHash.status shouldBe HttpStatusCode.Conflict

                val correct =
                    client.post("/test/gateway-enable?provider=STRIPE&version=$version&sha256=$sha256") {
                        header("X-Member-Id", ADMIN_ID)
                    }
                correct.status shouldBe HttpStatusCode.OK
                correct.bodyAsText() shouldBe "true|STRIPE"
            }
        }
    })

private fun StatusPagesConfig.installPaymentGateExceptionHandlers() {
    exception<UnauthenticatedException> { call, cause -> call.respondText(cause.message, status = HttpStatusCode.Unauthorized) }
    exception<ForbiddenException> { call, cause -> call.respondText(cause.message, status = HttpStatusCode.Forbidden) }
    exception<NotFoundException> { call, cause -> call.respondText(cause.message, status = HttpStatusCode.NotFound) }
    exception<ConflictException> { call, cause -> call.respondText(cause.message, status = HttpStatusCode.Conflict) }
    exception<BadRequestException> { call, cause ->
        call.respondText(cause.message, status = HttpStatusCode.BadRequest)
    }
}

private fun Route.registerPaymentGateTestRoutes() {
    get("/test/sepa-disclaimer") {
        val dto = SepaService(call = call).getSepaComplianceDisclaimer()
        call.respondText("${dto.version}|${dto.sha256}")
    }
    post("/test/sepa-enable") {
        val q = call.request.queryParameters
        val dto =
            SepaService(call = call).enableSepaDebit(
                SepaComplianceAcknowledgmentInput(disclaimerVersion = q["version"]!!, disclaimerSha256 = q["sha256"]!!),
            )
        call.respondText("${dto.sepaDebitEnabled}")
    }
    post("/test/sepa-disable") {
        val dto = SepaService(call = call).disableSepaDebit()
        call.respondText("${dto.sepaDebitEnabled}")
    }
    get("/test/sepa-settings") {
        val dto = SepaService(call = call).getSepaSettings()
        call.respondText("${dto.sepaDebitEnabled}|${dto.lastDisclaimerVersion}")
    }
    get("/test/gateway-disclaimer") {
        val dto = PaymentGatewayService(call = call).getPaymentGatewayComplianceDisclaimer()
        call.respondText("${dto.version}|${dto.sha256}")
    }
    post("/test/gateway-enable") {
        val q = call.request.queryParameters
        val dto =
            PaymentGatewayService(call = call).enablePaymentGateway(
                provider = PaymentProvider.valueOf(q["provider"]!!),
                acknowledgment =
                    PaymentGatewayComplianceAcknowledgmentInput(disclaimerVersion = q["version"]!!, disclaimerSha256 = q["sha256"]!!),
            )
        call.respondText("${dto.paymentGatewayEnabled}|${dto.paymentGatewayProvider}")
    }
    post("/test/gateway-disable") {
        val dto = PaymentGatewayService(call = call).disablePaymentGateway()
        call.respondText("${dto.paymentGatewayEnabled}")
    }
}
