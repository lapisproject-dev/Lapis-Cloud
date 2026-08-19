package network.lapis.cloud.server.rpc

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.server.db.DatabaseConfig
import network.lapis.cloud.server.db.generated.AccountTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.server.db.generated.OrganizationSettingsTable
import network.lapis.cloud.server.db.generated.PaymentGatewayComplianceAcknowledgmentTable
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.domain.PaymentGatewayComplianceAcknowledgmentInput
import network.lapis.cloud.shared.domain.PaymentProvider
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import kotlin.uuid.Uuid

/**
 * Security Round 1 (2026-08-19) regression coverage for [PaymentGatewayService]:
 * - [paymentGatewayDisclaimerIsCurrentlyAcknowledged] (SHOULD-3), direct-insert unit style mirroring
 *   [SepaServiceTest].
 * - `disablePaymentGateway` now also clears `paymentGatewayProvider` (SHOULD-4 nit), exercised
 *   through the real `enablePaymentGateway`/`disablePaymentGateway` RPC round-trip.
 */
class PaymentGatewayServiceTest :
    FunSpec({
        val createdMemberIds = mutableListOf<Uuid>()

        beforeSpec { DatabaseConfig.connect() }

        // paymentGatewayDisclaimerIsCurrentlyAcknowledged() deliberately has no member scoping -- it
        // reads the single, org-wide latest acknowledgment row. [PaymentComplianceGateTest] writes to
        // this SAME table (and cleans up after itself via its own afterTest, same "delete all rows"
        // idiom) -- clearing here too, before each test, keeps this Spec's assertions deterministic
        // regardless of cross-Spec test execution order.
        beforeTest {
            transaction {
                PaymentGatewayComplianceAcknowledgmentTable.deleteWhere {
                    PaymentGatewayComplianceAcknowledgmentTable.id eq PaymentGatewayComplianceAcknowledgmentTable.id
                }
            }
        }

        afterSpec {
            transaction {
                OrganizationSettingsTable.update({ OrganizationSettingsTable.id eq ORGANIZATION_SETTINGS_ID }) {
                    it[paymentGatewayEnabled] = false
                    it[paymentGatewayProvider] = null
                }
                if (createdMemberIds.isNotEmpty()) {
                    PaymentGatewayComplianceAcknowledgmentTable.deleteWhere {
                        PaymentGatewayComplianceAcknowledgmentTable.acknowledgedByMemberId inList createdMemberIds
                    }
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
                    it[displayName] = "PaymentGatewayService Testmitglied"
                    it[MemberTable.email] = email
                    it[status] = MemberStatus.ACTIVE
                    it[joinedAt] = LocalDate(2026, 1, 1)
                    it[membershipTierId] = null
                }
                AccountTable.insert {
                    it[AccountTable.id] = Uuid.random()
                    it[memberId] = id
                    it[AccountTable.role] = AccountRole.ADMIN
                }
            }
            createdMemberIds += id
            return id
        }

        fun insertAcknowledgment(
            memberId: Uuid,
            version: String,
            acknowledgedAt: LocalDateTime,
        ) {
            transaction {
                PaymentGatewayComplianceAcknowledgmentTable.insert {
                    it[id] = Uuid.random()
                    it[acknowledgedByMemberId] = memberId
                    it[PaymentGatewayComplianceAcknowledgmentTable.acknowledgedAt] = acknowledgedAt
                    it[disclaimerVersion] = version
                    it[disclaimerSha256] = "0".repeat(64)
                    it[provider] = PaymentProvider.STRIPE
                }
            }
        }

        test(
            "only acknowledgment on record matches the CURRENT PaymentGatewayComplianceDisclaimer.VERSION -> " +
                "currently acknowledged (Security Round 1, SHOULD-3)",
        ) {
            val member = createTestMember("gateway-disclaimer-current-${Uuid.random()}@example.org")
            insertAcknowledgment(
                memberId = member,
                version = PaymentGatewayComplianceDisclaimer.VERSION,
                acknowledgedAt = LocalDateTime(2026, 8, 19, 12, 0),
            )

            paymentGatewayDisclaimerIsCurrentlyAcknowledged() shouldBe true
        }

        test("latest acknowledgment is NOT the current version -> NOT currently acknowledged (Security Round 1, SHOULD-3)") {
            val member = createTestMember("gateway-disclaimer-stale-${Uuid.random()}@example.org")
            insertAcknowledgment(
                memberId = member,
                version = PaymentGatewayComplianceDisclaimer.VERSION,
                acknowledgedAt = LocalDateTime(2026, 8, 19, 12, 0),
            )
            insertAcknowledgment(
                memberId = member,
                version = "2020-01-01.v0-not-the-current-version",
                acknowledgedAt = LocalDateTime(2026, 8, 19, 12, 1),
            )

            paymentGatewayDisclaimerIsCurrentlyAcknowledged() shouldBe false
        }

        test("disablePaymentGateway clears paymentGatewayProvider, not just paymentGatewayEnabled (nit)") {
            testApplication {
                application {
                    routing {
                        post("/test/enable") {
                            val service = PaymentGatewayService(call)
                            val dto =
                                service.enablePaymentGateway(
                                    provider = PaymentProvider.STRIPE,
                                    acknowledgment =
                                        PaymentGatewayComplianceAcknowledgmentInput(
                                            disclaimerVersion = PaymentGatewayComplianceDisclaimer.VERSION,
                                            disclaimerSha256 = PaymentGatewayComplianceDisclaimer.SHA256,
                                        ),
                                )
                            call.respondText("${dto.paymentGatewayEnabled}:${dto.paymentGatewayProvider}")
                        }
                        post("/test/disable") {
                            val dto = PaymentGatewayService(call).disablePaymentGateway()
                            call.respondText("${dto.paymentGatewayEnabled}:${dto.paymentGatewayProvider}")
                        }
                        get("/test/get") {
                            val dto = PaymentGatewayService(call).getPaymentGatewaySettings()
                            call.respondText("${dto.paymentGatewayEnabled}:${dto.paymentGatewayProvider}")
                        }
                    }
                }

                val admin = createTestMember("gateway-disable-provider-${Uuid.random()}@example.org")

                val enabled = client.post("/test/enable") { header("X-Member-Id", admin.toString()) }
                enabled.status shouldBe HttpStatusCode.OK
                enabled.bodyAsText() shouldBe "true:STRIPE"

                val disabled = client.post("/test/disable") { header("X-Member-Id", admin.toString()) }
                disabled.status shouldBe HttpStatusCode.OK
                // The regression this test guards: before the fix, this was "false:STRIPE" -- a
                // disabled gateway still reporting a stale provider.
                disabled.bodyAsText() shouldBe "false:null"

                val getAfterDisable = client.get("/test/get") { header("X-Member-Id", admin.toString()) }
                getAfterDisable.bodyAsText() shouldBe "false:null"
            }
        }
    })
