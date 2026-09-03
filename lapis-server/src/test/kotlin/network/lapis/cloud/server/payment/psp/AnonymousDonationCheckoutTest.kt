package network.lapis.cloud.server.payment.psp

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.LocalDate
import network.lapis.cloud.server.db.DatabaseConfig
import network.lapis.cloud.server.db.DbClock
import network.lapis.cloud.server.db.DevSeedData
import network.lapis.cloud.server.db.generated.ExternalDonorTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.server.db.generated.OrganizationSettingsTable
import network.lapis.cloud.server.db.generated.PaymentCheckoutSessionTable
import network.lapis.cloud.server.db.generated.PaymentGatewayComplianceAcknowledgmentTable
import network.lapis.cloud.server.federation.FederationInboxRateLimiter
import network.lapis.cloud.server.rpc.ORGANIZATION_SETTINGS_ID
import network.lapis.cloud.server.rpc.PaymentGatewayComplianceDisclaimer
import network.lapis.cloud.shared.domain.DonorCategory
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.domain.PaymentProvider
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.like
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import java.math.BigDecimal
import kotlin.time.Duration.Companion.hours
import kotlin.uuid.Uuid

/**
 * Welle V1.4.1b "Öffentliche Website-Integration -- anonymer Spenden-Pfad" -- exercises
 * [AnonymousDonationCheckout] directly, proving the mandatory Prüfreihenfolge (Honeypot -> Gateway
 * -> Betrag -> §25 PartG -> Stripe -> Persistenz) without an HTTP stack, same "pure helper, unit
 * testable" treatment [StripeCheckoutClientTest] establishes for outbound PSP HTTP.
 */
class AnonymousDonationCheckoutTest :
    FunSpec({
        val createdMemberIds = mutableListOf<Uuid>()

        beforeSpec {
            DatabaseConfig.connect()
            DevSeedData.seedIfEmpty(force = true)
        }

        afterSpec {
            transaction {
                if (createdMemberIds.isNotEmpty()) {
                    PaymentGatewayComplianceAcknowledgmentTable.deleteWhere {
                        PaymentGatewayComplianceAcknowledgmentTable.acknowledgedByMemberId inList createdMemberIds
                    }
                    MemberTable.deleteWhere { MemberTable.id inList createdMemberIds }
                }
            }
        }

        afterTest {
            transaction {
                OrganizationSettingsTable.update({ OrganizationSettingsTable.id eq ORGANIZATION_SETTINGS_ID }) {
                    it[paymentGatewayEnabled] = false
                    it[paymentGatewayProvider] = null
                    it[isPoliticalParty] = false
                }
                val rows =
                    PaymentCheckoutSessionTable
                        .selectAll()
                        .where { PaymentCheckoutSessionTable.providerSessionId like "cs_anon_test_%" }
                        .toList()
                val sessionIds = rows.map { it[PaymentCheckoutSessionTable.id] }
                val donorIds = rows.mapNotNull { it[PaymentCheckoutSessionTable.externalDonorId] }
                if (sessionIds.isNotEmpty()) {
                    PaymentCheckoutSessionTable.deleteWhere { PaymentCheckoutSessionTable.id inList sessionIds }
                }
                if (donorIds.isNotEmpty()) {
                    ExternalDonorTable.deleteWhere { ExternalDonorTable.id inList donorIds }
                }
            }
        }

        fun mockStripeClient(): StripeCheckoutClient {
            val engine =
                MockEngine { _ ->
                    respond(
                        """{"id":"cs_anon_test_${Uuid.random()}","url":"https://checkout.stripe.com/c/pay/anon"}""",
                        HttpStatusCode.OK,
                        headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                }
            val pspConfig =
                (
                    PspConfig.load {
                        when (it) {
                            PspConfig.ENV_SECRET_KEY -> "sk_test_anon_donation"
                            PspConfig.ENV_WEBHOOK_SIGNING_SECRET -> "whsec_test_anon_donation"
                            else -> null
                        }
                    } as PspConfigState.Configured
                ).config
            return StripeCheckoutClient(pspConfig = pspConfig, httpClient = HttpClient(engine))
        }

        fun enableGateway(isPoliticalParty: Boolean = false) {
            val adminId = Uuid.random()
            transaction {
                MemberTable.insert {
                    it[id] = adminId
                    it[displayName] = "AnonymousDonationCheckoutTest Admin"
                    it[email] = "anon-donation-admin-${Uuid.random()}@example.org"
                    it[status] = MemberStatus.ACTIVE
                    it[joinedAt] = LocalDate(2026, 1, 1)
                    it[membershipTierId] = null
                }
                OrganizationSettingsTable.update({ OrganizationSettingsTable.id eq ORGANIZATION_SETTINGS_ID }) {
                    it[paymentGatewayEnabled] = true
                    it[paymentGatewayProvider] = PaymentProvider.STRIPE
                    it[OrganizationSettingsTable.isPoliticalParty] = isPoliticalParty
                }
                PaymentGatewayComplianceAcknowledgmentTable.insert {
                    it[id] = Uuid.random()
                    it[acknowledgedByMemberId] = adminId
                    it[acknowledgedAt] = DbClock.nowLocalDateTime()
                    it[disclaimerVersion] = PaymentGatewayComplianceDisclaimer.VERSION
                    it[disclaimerSha256] = PaymentGatewayComplianceDisclaimer.SHA256
                    it[provider] = PaymentProvider.STRIPE
                }
            }
            createdMemberIds += adminId
        }

        fun generousLimiter() = FederationInboxRateLimiter(maxRequests = 10_000, window = 1.hours)

        fun testPspConfigState(secretKeySuffix: String): PspConfigState =
            PspConfig.load {
                when (it) {
                    PspConfig.ENV_SECRET_KEY -> "sk_test_$secretKeySuffix"
                    PspConfig.ENV_WEBHOOK_SIGNING_SECRET -> "whsec_test_$secretKeySuffix"
                    else -> null
                }
            }

        test("honeypot filled -> HoneypotTripped, no Stripe call, no DB row") {
            var stripeCalled = false
            val engine =
                MockEngine { _ ->
                    stripeCalled = true
                    respond("{}", HttpStatusCode.OK)
                }
            val pspConfigState = testPspConfigState("honeypot")
            val checkout =
                AnonymousDonationCheckout(
                    pspConfigState = pspConfigState,
                    checkoutClient =
                        StripeCheckoutClient(
                            pspConfig = (pspConfigState as PspConfigState.Configured).config,
                            httpClient = HttpClient(engine),
                        ),
                    baseUrl = "https://lapis.example",
                    checkoutRateLimiter = generousLimiter(),
                )

            val result =
                runBlocking {
                    checkout.create(
                        amountEur = BigDecimal("25.00"),
                        honeypotValue = "x",
                        canonicalOrigin = "https://partei.example",
                        rateLimitKey = "test-honeypot",
                    )
                }

            (result is AnonymousDonationResult.HoneypotTripped) shouldBe true
            (result as AnonymousDonationResult.HoneypotTripped).redirectUrl.contains("/embed/v1/spende/abgebrochen") shouldBe true
            stripeCalled shouldBe false
        }

        test("gateway not enabled -> GatewayUnavailable") {
            val checkout =
                AnonymousDonationCheckout(
                    pspConfigState = PspConfigState.NotConfigured,
                    checkoutClient = null,
                    baseUrl = "https://lapis.example",
                    checkoutRateLimiter = generousLimiter(),
                )

            val result =
                runBlocking {
                    checkout.create(
                        amountEur = BigDecimal("25.00"),
                        honeypotValue = null,
                        canonicalOrigin = "https://partei.example",
                        rateLimitKey = "test-gateway-unavailable",
                    )
                }

            result shouldBe AnonymousDonationResult.GatewayUnavailable
        }

        test("amount below MIN_AMOUNT_EUR -> AmountOutOfRange") {
            enableGateway()
            val checkout =
                AnonymousDonationCheckout(
                    pspConfigState = testPspConfigState("range"),
                    checkoutClient = mockStripeClient(),
                    baseUrl = "https://lapis.example",
                    checkoutRateLimiter = generousLimiter(),
                )

            val result =
                runBlocking {
                    checkout.create(
                        amountEur = BigDecimal("4.99"),
                        honeypotValue = null,
                        canonicalOrigin = "https://partei.example",
                        rateLimitKey = "test-below-min",
                    )
                }

            result shouldBe AnonymousDonationResult.AmountOutOfRange
        }

        test("amount above 500.00 -> AmountOutOfRange, 500.00 itself is accepted") {
            enableGateway()
            val checkout =
                AnonymousDonationCheckout(
                    pspConfigState = testPspConfigState("range2"),
                    checkoutClient = mockStripeClient(),
                    baseUrl = "https://lapis.example",
                    checkoutRateLimiter = generousLimiter(),
                )

            val tooHigh =
                runBlocking {
                    checkout.create(
                        amountEur = BigDecimal("500.01"),
                        honeypotValue = null,
                        canonicalOrigin = "https://partei.example",
                        rateLimitKey = "test-above-max",
                    )
                }
            tooHigh shouldBe AnonymousDonationResult.AmountOutOfRange

            val atCeiling =
                runBlocking {
                    checkout.create(
                        amountEur = BigDecimal("500.00"),
                        honeypotValue = null,
                        canonicalOrigin = "https://partei.example",
                        rateLimitKey = "test-above-max",
                    )
                }
            (atCeiling is AnonymousDonationResult.Success) shouldBe true
        }

        test("political party org, amount at the 500 EUR ceiling still succeeds (per-donation ANONYMOUS rule, not aggregate)") {
            enableGateway(isPoliticalParty = true)
            val checkout =
                AnonymousDonationCheckout(
                    pspConfigState = testPspConfigState("party"),
                    checkoutClient = mockStripeClient(),
                    baseUrl = "https://lapis.example",
                    checkoutRateLimiter = generousLimiter(),
                )

            val result =
                runBlocking {
                    checkout.create(
                        amountEur = BigDecimal("500.00"),
                        honeypotValue = null,
                        canonicalOrigin = "https://partei.example",
                        rateLimitKey = "test-party",
                    )
                }

            (result is AnonymousDonationResult.Success) shouldBe true
        }

        test(
            "successful checkout persists exactly one external_donor + payment_checkout_session row " +
                "(member_id NULL, external_donor_id set, embed_origin/donor_category set, donor PENDING i.e. active=false)",
        ) {
            enableGateway()
            val checkout =
                AnonymousDonationCheckout(
                    pspConfigState = testPspConfigState("success"),
                    checkoutClient = mockStripeClient(),
                    baseUrl = "https://lapis.example",
                    checkoutRateLimiter = generousLimiter(),
                )

            val result =
                runBlocking {
                    checkout.create(
                        amountEur = BigDecimal("25.00"),
                        honeypotValue = null,
                        canonicalOrigin = "https://partei.example",
                        rateLimitKey = "test-success",
                    )
                }

            (result is AnonymousDonationResult.Success) shouldBe true

            val row =
                transaction {
                    PaymentCheckoutSessionTable
                        .selectAll()
                        .where { PaymentCheckoutSessionTable.providerSessionId like "cs_anon_test_%" }
                        .orderBy(PaymentCheckoutSessionTable.createdAt, SortOrder.DESC)
                        .limit(1)
                        .single()
                }
            row[PaymentCheckoutSessionTable.memberId] shouldBe null
            row[PaymentCheckoutSessionTable.embedOrigin] shouldBe "https://partei.example"
            row[PaymentCheckoutSessionTable.donorCategory] shouldBe DonorCategory.ANONYMOUS
            val donorId = requireNotNull(row[PaymentCheckoutSessionTable.externalDonorId]) { "externalDonorId must be set" }

            val donorRow = transaction { ExternalDonorTable.selectAll().where { ExternalDonorTable.id eq donorId }.single() }
            donorRow[ExternalDonorTable.displayName] shouldBe "Online-Spende ohne Namensangabe"
            donorRow[ExternalDonorTable.donorCategory] shouldBe DonorCategory.ANONYMOUS
            // Fix (Review MAJOR #1): PENDING, not yet a confirmed donor -- only
            // PspWebhookIngestion.ingestCheckoutCompleted flips this to true once Stripe confirms the
            // money actually arrived (see AnonymousDonationCheckoutTest's sibling e2e coverage in
            // EmbedDonationJourneyTest for that half of the round trip).
            donorRow[ExternalDonorTable.active] shouldBe false
        }

        test(
            "Fix (Review MAJOR #4): the strict checkout budget is consulted only immediately before " +
                "the Stripe call -- a pre-Stripe AmountOutOfRange rejection does not consume it, so the " +
                "genuine attempt right after an exhausting run of rejections still succeeds",
        ) {
            enableGateway()
            val tightLimiter = FederationInboxRateLimiter(maxRequests = 1, window = 1.hours)
            val checkout =
                AnonymousDonationCheckout(
                    pspConfigState = testPspConfigState("ratelimit-ordering"),
                    checkoutClient = mockStripeClient(),
                    baseUrl = "https://lapis.example",
                    checkoutRateLimiter = tightLimiter,
                )
            val key = "test-ratelimit-ordering"

            // Three pre-Stripe rejections (amount above the 500 EUR ceiling) against a limiter whose
            // budget is only 1 -- if the rate limit were still checked before the amount check (the
            // pre-fix ordering), the second of these would already be a RateLimited, not
            // AmountOutOfRange.
            repeat(3) {
                val rejected =
                    runBlocking {
                        checkout.create(
                            amountEur = BigDecimal("999.00"),
                            honeypotValue = null,
                            canonicalOrigin = "https://partei.example",
                            rateLimitKey = key,
                        )
                    }
                rejected shouldBe AnonymousDonationResult.AmountOutOfRange
            }

            val firstRealAttempt =
                runBlocking {
                    checkout.create(
                        amountEur = BigDecimal("25.00"),
                        honeypotValue = null,
                        canonicalOrigin = "https://partei.example",
                        rateLimitKey = key,
                    )
                }
            (firstRealAttempt is AnonymousDonationResult.Success) shouldBe true

            // The budget (1) is now genuinely exhausted -- the SECOND real attempt is rejected.
            val secondRealAttempt =
                runBlocking {
                    checkout.create(
                        amountEur = BigDecimal("25.00"),
                        honeypotValue = null,
                        canonicalOrigin = "https://partei.example",
                        rateLimitKey = key,
                    )
                }
            (secondRealAttempt is AnonymousDonationResult.RateLimited) shouldBe true
        }
    })
