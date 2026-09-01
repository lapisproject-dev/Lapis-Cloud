package network.lapis.cloud.server.rpc

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.server.application.call
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.server.db.DatabaseConfig
import network.lapis.cloud.server.db.generated.AccountTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.server.db.generated.OrganizationSettingsTable
import network.lapis.cloud.server.db.generated.PaymentGatewayComplianceAcknowledgmentTable
import network.lapis.cloud.server.payment.psp.PspConfig
import network.lapis.cloud.server.payment.psp.PspConfigState
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.domain.PaymentProvider
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import kotlin.uuid.Uuid

/**
 * Code review, Welle V1.2.9 round 2 (test-coverage finding): [PaymentGatewayService.getPaymentGatewayAvailability]
 * had NO test at all before this file, despite carrying [network.lapis.cloud.shared.domain.PaymentGatewayAvailabilityDto.maxCheckoutAmountEur]
 * -- the exact field both [network.lapis.cloud.client.DonationCheckoutScreen] and (formerly)
 * `PspCheckoutSection` gate client behavior on. Same house style
 * [PaymentGatewayCheckoutServiceTest]/[PaymentGatewayServiceTest] establish.
 */
class PaymentGatewayAvailabilityTest :
    FunSpec({
        val createdMemberIds = mutableListOf<Uuid>()

        beforeSpec { DatabaseConfig.connect() }

        afterTest {
            transaction {
                OrganizationSettingsTable.update({ OrganizationSettingsTable.id eq ORGANIZATION_SETTINGS_ID }) {
                    it[paymentGatewayEnabled] = false
                    it[paymentGatewayProvider] = null
                    it[isPoliticalParty] = false
                }
                PaymentGatewayComplianceAcknowledgmentTable.deleteWhere {
                    PaymentGatewayComplianceAcknowledgmentTable.id eq PaymentGatewayComplianceAcknowledgmentTable.id
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

        fun createMember(email: String): Uuid {
            val id = Uuid.random()
            transaction {
                MemberTable.insert {
                    it[MemberTable.id] = id
                    it[displayName] = "AvailabilityTest Testmitglied"
                    it[MemberTable.email] = email
                    it[status] = MemberStatus.ACTIVE
                    it[joinedAt] = LocalDate(2026, 1, 1)
                    it[membershipTierId] = null
                }
                AccountTable.insert {
                    it[AccountTable.id] = Uuid.random()
                    it[memberId] = id
                    it[AccountTable.role] = AccountRole.MEMBER
                }
            }
            createdMemberIds += id
            return id
        }

        // Same fixture-config helper as PaymentGatewayCheckoutServiceTest.testPspConfigState -- a
        // real Configured state without touching the process's actual LAPIS_STRIPE_* env, with an
        // overridable maxCheckoutAmountEur so this file can pin a known cap.
        fun testPspConfigState(maxCheckoutAmountEur: String = "10000.00"): PspConfigState.Configured =
            PspConfigState.Configured(
                config =
                    requireNotNull(
                        (
                            PspConfig.load {
                                when (it) {
                                    PspConfig.ENV_SECRET_KEY -> "sk_test_availability_test"
                                    PspConfig.ENV_WEBHOOK_SIGNING_SECRET -> "whsec_test_availability_test"
                                    PspConfig.ENV_MAX_CHECKOUT_AMOUNT_EUR -> maxCheckoutAmountEur
                                    else -> null
                                }
                            } as? PspConfigState.Configured
                        )?.config,
                    ),
            )

        fun enableGate(member: Uuid) {
            transaction {
                OrganizationSettingsTable.update({ OrganizationSettingsTable.id eq ORGANIZATION_SETTINGS_ID }) {
                    it[paymentGatewayEnabled] = true
                    it[paymentGatewayProvider] = PaymentProvider.STRIPE
                }
                PaymentGatewayComplianceAcknowledgmentTable.insert {
                    it[id] = Uuid.random()
                    it[acknowledgedByMemberId] = member
                    it[acknowledgedAt] = LocalDateTime(2026, 4, 1, 9, 0)
                    it[disclaimerVersion] = PaymentGatewayComplianceDisclaimer.VERSION
                    it[disclaimerSha256] = PaymentGatewayComplianceDisclaimer.SHA256
                    it[provider] = PaymentProvider.STRIPE
                }
            }
        }

        test("gate disabled -> usable=false and maxCheckoutAmountEur is null (no meaningful ceiling to show)") {
            testApplication {
                application {
                    routing {
                        get("/test/availability") {
                            val dto =
                                PaymentGatewayService(
                                    call = call,
                                    pspConfigState = testPspConfigState(),
                                ).getPaymentGatewayAvailability()
                            call.respondText(
                                "${dto.enabled}:${dto.contributionCheckoutAvailable}:${dto.donationCheckoutAvailable}:${dto.maxCheckoutAmountEur}",
                            )
                        }
                    }
                }
                val member = createMember("availability-gate-off-${Uuid.random()}@example.org")

                val response = client.get("/test/availability") { header("X-Member-Id", member.toString()) }
                response.bodyAsText() shouldBe "false:false:false:null"
            }
        }

        test(
            "gate on, disclaimer acknowledged, PSP configured with STRIPE -> usable=true and maxCheckoutAmountEur reports the real configured ceiling",
        ) {
            testApplication {
                application {
                    routing {
                        get("/test/availability") {
                            val dto =
                                PaymentGatewayService(
                                    call = call,
                                    pspConfigState = testPspConfigState("12345.67"),
                                ).getPaymentGatewayAvailability()
                            call.respondText(
                                "${dto.enabled}:${dto.contributionCheckoutAvailable}:${dto.donationCheckoutAvailable}:${dto.maxCheckoutAmountEur}",
                            )
                        }
                    }
                }
                val member = createMember("availability-usable-${Uuid.random()}@example.org")
                enableGate(member)

                val response = client.get("/test/availability") { header("X-Member-Id", member.toString()) }
                response.bodyAsText() shouldBe "true:true:true:12345.67"
            }
        }

        // getPaymentGatewayAvailability merely REPORTS whatever cap PspConfig carries -- this pins
        // that reporting for a deliberately low value. It does NOT and cannot prove anything about
        // createContributionCheckout's own enforcement (this RPC never calls that one). The actual
        // regression pin for "createContributionCheckout does not enforce maxCheckoutAmountEur --
        // the cap is donation-only" lives in PaymentGatewayCheckoutServiceTest ("createContributionCheckout
        // succeeds for an amount ABOVE maxCheckoutAmountEur"), which calls the real RPC past a fake
        // Stripe client and asserts a successful checkout above the cap -- found missing and added
        // in code review, Welle V1.2.9 round 2 (this test's name/comment previously claimed to be
        // that regression pin without actually calling createContributionCheckout anywhere).
        test("getPaymentGatewayAvailability reports the configured cap unchanged, even a deliberately low one") {
            testApplication {
                application {
                    routing {
                        get("/test/availability") {
                            val dto =
                                PaymentGatewayService(
                                    call = call,
                                    pspConfigState = testPspConfigState("100.00"),
                                ).getPaymentGatewayAvailability()
                            call.respondText("${dto.maxCheckoutAmountEur}")
                        }
                    }
                }
                val member = createMember("availability-cap-donation-only-${Uuid.random()}@example.org")
                enableGate(member)

                val response = client.get("/test/availability") { header("X-Member-Id", member.toString()) }
                response.bodyAsText() shouldBe "100.00"
            }
        }

        test("political party -> donorCategoryRequired=true only when usable") {
            testApplication {
                application {
                    routing {
                        get("/test/availability") {
                            val dto =
                                PaymentGatewayService(
                                    call = call,
                                    pspConfigState = testPspConfigState(),
                                ).getPaymentGatewayAvailability()
                            call.respondText("${dto.donorCategoryRequired}")
                        }
                    }
                }
                val member = createMember("availability-party-${Uuid.random()}@example.org")
                transaction {
                    OrganizationSettingsTable.update({ OrganizationSettingsTable.id eq ORGANIZATION_SETTINGS_ID }) {
                        it[isPoliticalParty] = true
                    }
                }

                // Gate still off -> not usable -> donorCategoryRequired must be false even though
                // isPoliticalParty=true, mirroring `usable && isPoliticalParty` in the source.
                val beforeGate = client.get("/test/availability") { header("X-Member-Id", member.toString()) }
                beforeGate.bodyAsText() shouldBe "false"

                enableGate(member)
                val afterGate = client.get("/test/availability") { header("X-Member-Id", member.toString()) }
                afterGate.bodyAsText() shouldBe "true"
            }
        }
    })
