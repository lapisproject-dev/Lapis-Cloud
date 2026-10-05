package network.lapis.cloud.server.rpc

import io.kotest.core.annotation.EnabledIf
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.server.response.respondText
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.server.db.generated.AccountTable
import network.lapis.cloud.server.db.generated.ContributionTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.server.db.generated.MembershipTierTable
import network.lapis.cloud.server.db.generated.OrganizationSettingsTable
import network.lapis.cloud.server.db.generated.PaymentCheckoutSessionTable
import network.lapis.cloud.server.db.generated.PaymentGatewayComplianceAcknowledgmentTable
import network.lapis.cloud.server.payment.psp.PspConfig
import network.lapis.cloud.server.payment.psp.PspConfigState
import network.lapis.cloud.server.payment.psp.StripeCheckoutClient
import network.lapis.cloud.server.testdb.PostgresConfigured
import network.lapis.cloud.server.testdb.TestDatabase
import network.lapis.cloud.server.testdb.installLaneGuards
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.BillingInterval
import network.lapis.cloud.shared.domain.ContributionCheckoutInput
import network.lapis.cloud.shared.domain.ContributionStatus
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.domain.PaymentProvider
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import java.math.BigDecimal
import java.util.concurrent.atomic.AtomicInteger
import kotlin.uuid.Uuid

/**
 * Welle V1.9.55 -- two parallel `createContributionCheckout` calls for the SAME contribution used to both pass the unlocked
 * "reusable session?" check and mint two hosted Stripe sessions (a member could then pay twice). The reuse check, the provider
 * call and the persist now run under a per-contribution lock: exactly one provider call, both callers get the same session.
 */
abstract class ContributionCheckoutSingleFlightScenarios(
    private val db: TestDatabase,
) : FunSpec({
        val createdMemberIds = mutableListOf<Uuid>()
        val createdTierIds = mutableListOf<Uuid>()
        val createdContributionIds = mutableListOf<Uuid>()

        beforeSpec { db.activate() }
        installLaneGuards(db = db)
        afterSpec {
            transaction {
                OrganizationSettingsTable.update({ OrganizationSettingsTable.id eq ORGANIZATION_SETTINGS_ID }) {
                    it[paymentGatewayEnabled] = false
                    it[paymentGatewayProvider] = null
                }
                if (createdContributionIds.isNotEmpty()) {
                    PaymentCheckoutSessionTable.deleteWhere { PaymentCheckoutSessionTable.contributionId inList createdContributionIds }
                    ContributionTable.deleteWhere { ContributionTable.id inList createdContributionIds }
                }
                if (createdTierIds.isNotEmpty()) MembershipTierTable.deleteWhere { MembershipTierTable.id inList createdTierIds }
                if (createdMemberIds.isNotEmpty()) {
                    PaymentGatewayComplianceAcknowledgmentTable.deleteWhere {
                        PaymentGatewayComplianceAcknowledgmentTable.acknowledgedByMemberId inList createdMemberIds
                    }
                    AccountTable.deleteWhere { AccountTable.memberId inList createdMemberIds }
                    MemberTable.deleteWhere { MemberTable.id inList createdMemberIds }
                }
            }
            db.deactivate()
        }

        fun createMember(): Uuid {
            val id = Uuid.random()
            transaction {
                MemberTable.insert {
                    it[MemberTable.id] = id
                    it[displayName] = "SingleFlight Testmitglied"
                    it[email] = "single-flight-$id@example.org"
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

        fun createContribution(member: Uuid): Uuid {
            val tierId = Uuid.random()
            val contributionId = Uuid.random()
            transaction {
                MembershipTierTable.insert {
                    it[MembershipTierTable.id] = tierId
                    it[nameKey] = tierId.toString()
                    it[name] = "SingleFlight Tarif ${tierId.toString().take(6)}"
                    it[description] = "Test-Tarif"
                    it[contributionAmount] = BigDecimal("50.00")
                    it[billingInterval] = BillingInterval.YEARLY
                    it[active] = true
                    it[paymentTermDays] = 14
                }
                ContributionTable.insert {
                    it[ContributionTable.id] = contributionId
                    it[periodStart] = LocalDate(2026, 1, 1)
                    it[periodEnd] = LocalDate(2026, 12, 31)
                    it[amountDue] = BigDecimal("50.00")
                    it[status] = ContributionStatus.OPEN
                    it[createdAt] = LocalDateTime(2026, 1, 1, 0, 0)
                    it[memberId] = member
                    it[membershipTierId] = tierId
                    it[dueDate] = LocalDate(2026, 1, 15)
                }
            }
            createdTierIds += tierId
            createdContributionIds += contributionId
            return contributionId
        }

        fun pspConfigState(): PspConfigState.Configured =
            PspConfigState.Configured(
                config =
                    requireNotNull(
                        (
                            PspConfig.load {
                                when (it) {
                                    PspConfig.ENV_SECRET_KEY -> "sk_test_single_flight"
                                    PspConfig.ENV_WEBHOOK_SIGNING_SECRET -> "whsec_test_single_flight"
                                    else -> null
                                }
                            } as? PspConfigState.Configured
                        )?.config,
                    ),
            )

        test("two parallel checkouts for the same contribution make exactly one provider call and return the same session") {
            val member = createMember()
            val contributionId = createContribution(member)
            transaction {
                OrganizationSettingsTable.update({ OrganizationSettingsTable.id eq ORGANIZATION_SETTINGS_ID }) {
                    it[paymentGatewayEnabled] = true
                    it[paymentGatewayProvider] = PaymentProvider.STRIPE
                }
                PaymentGatewayComplianceAcknowledgmentTable.insert {
                    it[id] = Uuid.random()
                    it[acknowledgedByMemberId] = createMember()
                    it[acknowledgedAt] = LocalDateTime(2026, 4, 1, 9, 0)
                    it[disclaimerVersion] = PaymentGatewayComplianceDisclaimer.VERSION
                    it[disclaimerSha256] = PaymentGatewayComplianceDisclaimer.SHA256
                    it[provider] = PaymentProvider.STRIPE
                }
            }
            val providerCalls = AtomicInteger()
            val state = pspConfigState()
            val gateway =
                StripeCheckoutClient(
                    pspConfig = state.config,
                    httpClient =
                        HttpClient(
                            MockEngine { _ ->
                                providerCalls.incrementAndGet()
                                // A slow provider widens the race window: without the lock both callers pass the reuse check.
                                delay(400)
                                respond(
                                    """{"id":"cs_test_${Uuid.random()}","url":"https://checkout.stripe.com/c/pay/cs_test_x"}""",
                                    HttpStatusCode.OK,
                                    headersOf(HttpHeaders.ContentType, "application/json"),
                                )
                            },
                        ),
                )
            testApplication {
                application {
                    routing {
                        post("/test/single-flight") {
                            val session =
                                PaymentGatewayService(
                                    call = call,
                                    pspConfigState = state,
                                    gateways = mapOf(PaymentProvider.STRIPE to gateway),
                                ).createContributionCheckout(ContributionCheckoutInput(contributionId = contributionId.toString()))
                            call.respondText(session.id)
                        }
                    }
                }
                val ids =
                    coroutineScope {
                        (1..2)
                            .map {
                                async {
                                    client
                                        .post("/test/single-flight") { header("X-Member-Id", member.toString()) }
                                        .bodyAsText()
                                }
                            }.awaitAll()
                    }
                ids[0] shouldBe ids[1]
            }
            providerCalls.get() shouldBe 1
            transaction {
                PaymentCheckoutSessionTable
                    .selectAll()
                    .where { PaymentCheckoutSessionTable.contributionId eq contributionId }
                    .count()
            } shouldBe 1L
        }
    })

class ContributionCheckoutSingleFlightTest : ContributionCheckoutSingleFlightScenarios(TestDatabase.H2)

@Tags("Postgres")
@EnabledIf(PostgresConfigured::class)
class ContributionCheckoutSingleFlightPostgresTest : ContributionCheckoutSingleFlightScenarios(TestDatabase.Postgres())
