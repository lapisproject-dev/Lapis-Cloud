package network.lapis.cloud.server.rpc

import io.kotest.assertions.throwables.shouldThrow
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
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.response.respondText
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.server.db.DatabaseConfig
import network.lapis.cloud.server.db.generated.AccountTable
import network.lapis.cloud.server.db.generated.ContributionTable
import network.lapis.cloud.server.db.generated.LedgerAccountTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.server.db.generated.MembershipTierTable
import network.lapis.cloud.server.db.generated.OrganizationSettingsTable
import network.lapis.cloud.server.db.generated.PaymentCheckoutSessionTable
import network.lapis.cloud.server.db.generated.PaymentGatewayComplianceAcknowledgmentTable
import network.lapis.cloud.server.federation.FederationInboxRateLimiter
import network.lapis.cloud.server.payment.psp.PspConfig
import network.lapis.cloud.server.payment.psp.PspConfigState
import network.lapis.cloud.server.payment.psp.StripeCheckoutClient
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.BillingInterval
import network.lapis.cloud.shared.domain.ContributionCheckoutInput
import network.lapis.cloud.shared.domain.ContributionStatus
import network.lapis.cloud.shared.domain.DonationCheckoutInput
import network.lapis.cloud.shared.domain.DonorCategory
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.domain.PaymentProvider
import network.lapis.cloud.shared.rpc.BadRequestException
import network.lapis.cloud.shared.rpc.ConflictException
import network.lapis.cloud.shared.rpc.ForbiddenException
import network.lapis.cloud.shared.rpc.NotFoundException
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import java.math.BigDecimal
import kotlin.uuid.Uuid

/**
 * Welle V1.2.8 "PSP-Checkout (Stripe)" (GitHub Issue #6) -- exercises [PaymentGatewayService]'s new
 * checkout-creation/listing methods over a real `testApplication`, same house style
 * [ContributionPaymentRpcTest]/[PaymentGatewayServiceTest] establish. `checkoutClient = null`
 * throughout (`pspConfigState` also `NotConfigured` unless a test explicitly wires it) for every
 * test that means to exercise the GATE/VALIDATION logic that runs BEFORE any Stripe call -- never
 * the outbound HTTP itself (that is [network.lapis.cloud.server.payment.psp.StripeCheckoutClientTest]'s
 * job). One test (below, the maxCheckoutAmountEur regression pin) deliberately runs a real
 * `createContributionCheckout` call PAST that gate against a [fakeSuccessfulCheckoutClient] --
 * MockEngine-backed, never the real network -- because it is proving something about behavior on
 * the SUCCESS path, not about a gate rejecting the call.
 */
class PaymentGatewayCheckoutServiceTest :
    FunSpec({
        val createdMemberIds = mutableListOf<Uuid>()
        val createdTierIds = mutableListOf<Uuid>()
        val createdContributionIds = mutableListOf<Uuid>()
        val createdLedgerAccountIds = mutableListOf<Uuid>()

        beforeSpec { DatabaseConfig.connect() }

        afterTest {
            transaction {
                OrganizationSettingsTable.update({ OrganizationSettingsTable.id eq ORGANIZATION_SETTINGS_ID }) {
                    it[paymentGatewayEnabled] = false
                    it[paymentGatewayProvider] = null
                    it[isPoliticalParty] = false
                }
            }
        }

        afterSpec {
            transaction {
                if (createdContributionIds.isNotEmpty()) {
                    // The new maxCheckoutAmountEur regression pin (below) is the first test in this
                    // file to actually run createContributionCheckout PAST the gate with a working
                    // checkoutClient -- it persists a real payment_checkout_session row referencing
                    // its contribution, which must go first or ContributionTable's delete below
                    // fails its FK constraint.
                    PaymentCheckoutSessionTable.deleteWhere { PaymentCheckoutSessionTable.contributionId inList createdContributionIds }
                    ContributionTable.deleteWhere { ContributionTable.id inList createdContributionIds }
                }
                if (createdTierIds.isNotEmpty()) {
                    MembershipTierTable.deleteWhere { MembershipTierTable.id inList createdTierIds }
                }
                if (createdLedgerAccountIds.isNotEmpty()) {
                    LedgerAccountTable.deleteWhere { LedgerAccountTable.id inList createdLedgerAccountIds }
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

        fun createMember(
            email: String,
            role: AccountRole = AccountRole.MEMBER,
        ): Uuid {
            val id = Uuid.random()
            transaction {
                MemberTable.insert {
                    it[MemberTable.id] = id
                    it[displayName] = "CheckoutServiceTest Testmitglied"
                    it[MemberTable.email] = email
                    it[status] = MemberStatus.ACTIVE
                    it[joinedAt] = LocalDate(2026, 1, 1)
                    it[membershipTierId] = null
                }
                AccountTable.insert {
                    it[AccountTable.id] = Uuid.random()
                    it[memberId] = id
                    it[AccountTable.role] = role
                }
            }
            createdMemberIds += id
            return id
        }

        fun createTier(): Uuid {
            val id = Uuid.random()
            transaction {
                MembershipTierTable.insert {
                    it[MembershipTierTable.id] = id
                    it[name] = "CheckoutServiceTest Tarif ${id.toString().take(6)}"
                    it[description] = "Test-Tarif"
                    it[contributionAmount] = BigDecimal("50.00")
                    it[billingInterval] = BillingInterval.YEARLY
                    it[active] = true
                    it[paymentTermDays] = 14
                }
            }
            createdTierIds += id
            return id
        }

        fun createContribution(
            memberId: Uuid,
            tierId: Uuid,
            status: ContributionStatus = ContributionStatus.OPEN,
        ): Uuid {
            val id = Uuid.random()
            transaction {
                ContributionTable.insert {
                    it[ContributionTable.id] = id
                    it[periodStart] = LocalDate(2026, 1, 1)
                    it[periodEnd] = LocalDate(2026, 12, 31)
                    it[amountDue] = BigDecimal("50.00")
                    it[ContributionTable.status] = status
                    it[ContributionTable.createdAt] = LocalDateTime(2026, 1, 1, 0, 0)
                    it[ContributionTable.memberId] = memberId
                    it[ContributionTable.membershipTierId] = tierId
                    it[dueDate] = LocalDate(2026, 1, 15)
                }
            }
            createdContributionIds += id
            return id
        }

        // requirePaymentGatewayUsable()'s third gate (PspConfigState.Configured) reads
        // LAPIS_STRIPE_* env vars via PaymentGatewayService's own default `pspConfigState =
        // PspConfig.load()` -- absent in this test environment, which would make every gate-passing
        // test below fail on the CONFIG gate before ever reaching the amount/donorCategory logic
        // this file actually means to exercise. Fixture config, checkoutClient stays null (never
        // dereferenced -- every test below either throws before reaching it or asserts a
        // BadRequestException/ConflictException raised earlier in the method).
        //
        // maxCheckoutAmountEur is overridable (mirrors PaymentGatewayAvailabilityTest's own
        // testPspConfigState) so a test can pin a known, deliberately-low cap -- see
        // "createContributionCheckout succeeds for an amount ABOVE maxCheckoutAmountEur" below.
        fun testPspConfigState(maxCheckoutAmountEur: String = "10000.00"): PspConfigState.Configured =
            PspConfigState.Configured(
                config =
                    requireNotNull(
                        (
                            PspConfig.load {
                                when (it) {
                                    PspConfig.ENV_SECRET_KEY -> "sk_test_checkout_service_test"
                                    PspConfig.ENV_WEBHOOK_SIGNING_SECRET -> "whsec_test_checkout_service_test"
                                    PspConfig.ENV_MAX_CHECKOUT_AMOUNT_EUR -> maxCheckoutAmountEur
                                    else -> null
                                }
                            } as? PspConfigState.Configured
                        )?.config,
                    ),
            )

        // Stripe-response-shaped MockEngine client so createContributionCheckout can run to a real
        // success outcome without ever touching the network -- same MockEngine house style
        // StripeCheckoutClientTest establishes for exercising StripeCheckoutClient itself.
        fun fakeSuccessfulCheckoutClient(pspConfigState: PspConfigState.Configured): StripeCheckoutClient =
            StripeCheckoutClient(
                pspConfig = pspConfigState.config,
                httpClient =
                    HttpClient(
                        MockEngine { _ ->
                            respond(
                                """{"id":"cs_test_fake","url":"https://checkout.stripe.com/c/pay/cs_test_fake"}""",
                                HttpStatusCode.OK,
                                headersOf(HttpHeaders.ContentType, "application/json"),
                            )
                        },
                    ),
            )

        fun enableGate() {
            transaction {
                OrganizationSettingsTable.update({ OrganizationSettingsTable.id eq ORGANIZATION_SETTINGS_ID }) {
                    it[paymentGatewayEnabled] = true
                    it[paymentGatewayProvider] = PaymentProvider.STRIPE
                }
                PaymentGatewayComplianceAcknowledgmentTable.insert {
                    it[id] = Uuid.random()
                    it[acknowledgedByMemberId] = createMember("checkout-service-ack-${Uuid.random()}@example.org")
                    it[acknowledgedAt] = LocalDateTime(2026, 4, 1, 9, 0)
                    it[disclaimerVersion] = PaymentGatewayComplianceDisclaimer.VERSION
                    it[disclaimerSha256] = PaymentGatewayComplianceDisclaimer.SHA256
                    it[provider] = PaymentProvider.STRIPE
                }
            }
        }

        test("createContributionCheckout with the gate off -> ConflictException") {
            testApplication {
                application {
                    install(StatusPages) {
                        exception<ForbiddenException> {
                            call,
                            cause,
                            ->
                            call.respondText(cause.message, status = HttpStatusCode.Forbidden)
                        }
                    }
                    routing {
                        post("/test/checkout") {
                            val id = call.request.queryParameters["contributionId"]!!
                            shouldThrow<ConflictException> {
                                PaymentGatewayService(
                                    call = call,
                                ).createContributionCheckout(ContributionCheckoutInput(contributionId = id))
                            }
                            call.respondText("ok")
                        }
                    }
                }
                val member = createMember("checkout-gate-off-${Uuid.random()}@example.org")
                val tier = createTier()
                val contributionId = createContribution(memberId = member, tierId = tier)
                val response =
                    client.post("/test/checkout?contributionId=$contributionId") { header("X-Member-Id", member.toString()) }
                response.status shouldBe HttpStatusCode.OK
                response.bodyAsText() shouldBe "ok"
            }
        }

        // MAJOR fix #1 (code review, Welle V1.2.8): a contribution currently bound up in an
        // in-flight SEPA debit run must reject an online checkout the same way an already-settled
        // one does, else a member could pay online AND be debited by the same running SEPA batch.
        test("createContributionCheckout against a DEBIT_SCHEDULED/DEBIT_SUBMITTED contribution -> ConflictException") {
            testApplication {
                application {
                    routing {
                        post("/test/checkout-in-flight") {
                            val id = call.request.queryParameters["contributionId"]!!
                            shouldThrow<ConflictException> {
                                PaymentGatewayService(call = call, pspConfigState = testPspConfigState()).createContributionCheckout(
                                    ContributionCheckoutInput(contributionId = id),
                                )
                            }
                            call.respondText("ok")
                        }
                    }
                }
                val member = createMember("checkout-debit-in-flight-${Uuid.random()}@example.org")
                enableGate()
                // Bugfix (build verification, Welle V1.2.8): a fresh tier per iteration -- reusing
                // the same member+tier across both loop iterations violated the real
                // uq_contribution_member_tier_period unique index (member_id, membership_tier_id,
                // period_start, period_end are all identical between createContribution() calls
                // otherwise).
                for (status in listOf(ContributionStatus.DEBIT_SCHEDULED, ContributionStatus.DEBIT_SUBMITTED)) {
                    val tier = createTier()
                    val contributionId = createContribution(memberId = member, tierId = tier, status = status)
                    val response =
                        client.post("/test/checkout-in-flight?contributionId=$contributionId") {
                            header("X-Member-Id", member.toString())
                        }
                    response.status shouldBe HttpStatusCode.OK
                }
            }
        }

        test("createContributionCheckout against an already-PAID contribution -> ConflictException") {
            testApplication {
                application {
                    routing {
                        post("/test/checkout-settled") {
                            val id = call.request.queryParameters["contributionId"]!!
                            shouldThrow<ConflictException> {
                                PaymentGatewayService(call = call, pspConfigState = testPspConfigState()).createContributionCheckout(
                                    ContributionCheckoutInput(contributionId = id),
                                )
                            }
                            call.respondText("ok")
                        }
                    }
                }
                val member = createMember("checkout-already-settled-${Uuid.random()}@example.org")
                val tier = createTier()
                enableGate()
                val contributionId = createContribution(memberId = member, tierId = tier, status = ContributionStatus.PAID)
                val response =
                    client.post("/test/checkout-settled?contributionId=$contributionId") { header("X-Member-Id", member.toString()) }
                response.status shouldBe HttpStatusCode.OK
            }
        }

        // Regression pin (code review, Welle V1.2.9 round 2 -- MINOR finding: the test that was
        // supposed to prove this in PaymentGatewayAvailabilityTest never actually called
        // createContributionCheckout at all, it only checked getPaymentGatewayAvailability's
        // reported ceiling). PspConfig.maxCheckoutAmountEur is documented ONLY as an
        // "Abuse/DoS cap on createDonationCheckout" -- this proves createContributionCheckout
        // really does ignore it end-to-end: a contribution well above a deliberately tiny cap still
        // produces a successful CheckoutSessionDto for the contribution's own amountDue, never a
        // BadRequestException.
        test("createContributionCheckout succeeds for an amount ABOVE maxCheckoutAmountEur -- the cap is donation-only") {
            testApplication {
                application {
                    routing {
                        post("/test/checkout-above-cap") {
                            val id = call.request.queryParameters["contributionId"]!!
                            val pspConfigState = testPspConfigState(maxCheckoutAmountEur = "10.00")
                            val session =
                                PaymentGatewayService(
                                    call = call,
                                    pspConfigState = pspConfigState,
                                    checkoutClient = fakeSuccessfulCheckoutClient(pspConfigState),
                                ).createContributionCheckout(ContributionCheckoutInput(contributionId = id))
                            call.respondText("${session.amount}")
                        }
                    }
                }
                val member = createMember("checkout-above-cap-${Uuid.random()}@example.org")
                val tier = createTier()
                enableGate()
                // amountDue fixed at 50.00 by createContribution() -- comfortably above the 10.00
                // cap configured above.
                val contributionId = createContribution(memberId = member, tierId = tier)
                val response =
                    client.post("/test/checkout-above-cap?contributionId=$contributionId") {
                        header("X-Member-Id", member.toString())
                    }
                response.status shouldBe HttpStatusCode.OK
                response.bodyAsText() shouldBe "50.00"
            }
        }

        test("createContributionCheckout for ANOTHER member's contribution by a plain MEMBER -> NotFoundException") {
            testApplication {
                application {
                    routing {
                        post("/test/checkout-foreign") {
                            val id = call.request.queryParameters["contributionId"]!!
                            shouldThrow<NotFoundException> {
                                PaymentGatewayService(call = call, pspConfigState = testPspConfigState()).createContributionCheckout(
                                    ContributionCheckoutInput(contributionId = id),
                                )
                            }
                            call.respondText("ok")
                        }
                    }
                }
                val owner = createMember("checkout-foreign-owner-${Uuid.random()}@example.org")
                val otherMember = createMember("checkout-foreign-other-${Uuid.random()}@example.org")
                val tier = createTier()
                enableGate()
                val contributionId = createContribution(memberId = owner, tierId = tier)
                val response =
                    client.post("/test/checkout-foreign?contributionId=$contributionId") { header("X-Member-Id", otherMember.toString()) }
                response.status shouldBe HttpStatusCode.OK
            }
        }

        // MAJOR fix #2 (code review, Welle V1.2.8): purpose > 200 chars must be rejected BEFORE any
        // Stripe call -- see PaymentGatewayService.createDonationCheckout's MAX_DONATION_PURPOSE_LENGTH
        // check.
        test("createDonationCheckout purpose > 200 chars -> BadRequestException, before any Stripe call") {
            testApplication {
                application {
                    routing {
                        post("/test/donate-long-purpose") {
                            shouldThrow<BadRequestException> {
                                PaymentGatewayService(call = call, pspConfigState = testPspConfigState()).createDonationCheckout(
                                    DonationCheckoutInput(
                                        amount = BigDecimal("10.00"),
                                        donorCategory = null,
                                        purpose = "x".repeat(201),
                                    ),
                                )
                            }
                            call.respondText("ok")
                        }
                    }
                }
                val member = createMember("checkout-donation-longpurpose-${Uuid.random()}@example.org")
                enableGate()
                val response = client.post("/test/donate-long-purpose") { header("X-Member-Id", member.toString()) }
                response.status shouldBe HttpStatusCode.OK
            }
        }

        // Security audit finding (Welle V1.2.8, MAJOR) test coverage -- checkoutCreateRateLimiter must
        // actually be consulted, and BEFORE any Stripe call: checkoutClient stays null throughout
        // (testPspConfigState() default), so a fake-passing test would have thrown NotFoundException
        // (checkoutClient == null) or ConflictException (usability gate) at some LATER point instead --
        // this pins that the rate-limit rejection happens first, per-member.
        test("createDonationCheckout: over checkoutCreateRateLimiter budget -> ConflictException, before any Stripe call") {
            testApplication {
                application {
                    routing {
                        post("/test/donate-rate-limited") {
                            PaymentGatewayService(
                                call = call,
                                pspConfigState = testPspConfigState(),
                                checkoutCreateRateLimiter =
                                    FederationInboxRateLimiter(maxRequests = 1, window = kotlin.time.Duration.INFINITE),
                            ).let { service ->
                                // First call consumes the sole budget slot; NOT expected to succeed all
                                // the way through (checkoutClient is null / gate disabled) -- only that
                                // it does NOT fail with ConflictException("Zu viele Anfragen...").
                                runCatching {
                                    service.createDonationCheckout(
                                        DonationCheckoutInput(amount = BigDecimal("10.00"), donorCategory = null, purpose = null),
                                    )
                                }
                                val second =
                                    shouldThrow<ConflictException> {
                                        service.createDonationCheckout(
                                            DonationCheckoutInput(amount = BigDecimal("10.00"), donorCategory = null, purpose = null),
                                        )
                                    }
                                second.message shouldBe "Zu viele Anfragen -- bitte spaeter erneut versuchen."
                            }
                            call.respondText("ok")
                        }
                    }
                }
                val member = createMember("checkout-donation-ratelimited-${Uuid.random()}@example.org")
                enableGate()
                val response = client.post("/test/donate-rate-limited") { header("X-Member-Id", member.toString()) }
                response.status shouldBe HttpStatusCode.OK
            }
        }

        test("createDonationCheckout amount 0/negative/3-decimals -> BadRequestException, before the gate is even reached") {
            testApplication {
                application {
                    routing {
                        post("/test/donate") {
                            val amount = BigDecimal(call.request.queryParameters["amount"]!!)
                            shouldThrow<BadRequestException> {
                                PaymentGatewayService(call = call, pspConfigState = testPspConfigState()).createDonationCheckout(
                                    DonationCheckoutInput(amount = amount, donorCategory = null, purpose = null),
                                )
                            }
                            call.respondText("ok")
                        }
                    }
                }
                val member = createMember("checkout-donation-badamount-${Uuid.random()}@example.org")
                enableGate()
                // Test-quality fix (code review, Welle V1.2.8): the original test only ever exercised
                // 0.00 despite its own name promising 0/negative/3-decimals -- now all three.
                for (amount in listOf("0.00", "-10.00", "10.123")) {
                    val response = client.post("/test/donate?amount=$amount") { header("X-Member-Id", member.toString()) }
                    response.status shouldBe HttpStatusCode.OK
                }
            }
        }

        test("createDonationCheckout: is_political_party=true and donorCategory=null -> BadRequestException") {
            testApplication {
                application {
                    routing {
                        post("/test/donate") {
                            shouldThrow<BadRequestException> {
                                PaymentGatewayService(call = call, pspConfigState = testPspConfigState()).createDonationCheckout(
                                    DonationCheckoutInput(amount = BigDecimal("10.00"), donorCategory = null, purpose = null),
                                )
                            }
                            call.respondText("ok")
                        }
                    }
                }
                val member = createMember("checkout-donation-nocategory-${Uuid.random()}@example.org")
                enableGate()
                transaction {
                    OrganizationSettingsTable.update({ OrganizationSettingsTable.id eq ORGANIZATION_SETTINGS_ID }) {
                        it[isPoliticalParty] =
                            true
                    }
                }
                val response = client.post("/test/donate") { header("X-Member-Id", member.toString()) }
                response.status shouldBe HttpStatusCode.OK
            }
        }

        test("createDonationCheckout: PROHIBITED category+amount -> ConflictException before any Stripe call (checkoutClient is null)") {
            testApplication {
                application {
                    routing {
                        post("/test/donate") {
                            shouldThrow<ConflictException> {
                                PaymentGatewayService(call = call, pspConfigState = testPspConfigState()).createDonationCheckout(
                                    DonationCheckoutInput(
                                        amount = BigDecimal("10.00"),
                                        donorCategory = DonorCategory.PUBLIC_LAW_CORPORATION,
                                        purpose = null,
                                    ),
                                )
                            }
                            call.respondText("ok")
                        }
                    }
                }
                val member = createMember("checkout-donation-prohibited-${Uuid.random()}@example.org")
                enableGate()
                transaction {
                    OrganizationSettingsTable.update({ OrganizationSettingsTable.id eq ORGANIZATION_SETTINGS_ID }) {
                        it[isPoliticalParty] =
                            true
                    }
                }
                val response = client.post("/test/donate") { header("X-Member-Id", member.toString()) }
                response.status shouldBe HttpStatusCode.OK
            }
        }

        test("listPaymentTransactions as MEMBER -> ForbiddenException") {
            testApplication {
                application {
                    routing {
                        post("/test/list") {
                            shouldThrow<ForbiddenException> {
                                PaymentGatewayService(
                                    call = call,
                                ).listPaymentTransactions(
                                    network.lapis.cloud.shared.domain
                                        .PaymentTransactionQuery(),
                                )
                            }
                            call.respondText("ok")
                        }
                    }
                }
                val member = createMember("checkout-list-member-${Uuid.random()}@example.org", role = AccountRole.MEMBER)
                val response = client.post("/test/list") { header("X-Member-Id", member.toString()) }
                response.status shouldBe HttpStatusCode.OK
            }
        }

        // Test-quality fix (code review, Welle V1.2.8): the old test's name claimed "limit clamped to
        // 200 for a privileged caller" but never actually issued a > 200 request -- this one does and
        // asserts the returned page respects the cap.
        test("listPaymentTransactions: limit > 200 is clamped to 200 for a privileged caller") {
            testApplication {
                application {
                    routing {
                        post("/test/list-clamped") {
                            val dto =
                                PaymentGatewayService(call = call).listPaymentTransactions(
                                    network.lapis.cloud.shared.domain
                                        .PaymentTransactionQuery(limit = 500),
                                )
                            call.respondText(dto.limit.toString())
                        }
                    }
                }
                val treasurer = createMember("checkout-list-clamp-treasurer-${Uuid.random()}@example.org", role = AccountRole.TREASURER)
                val response = client.post("/test/list-clamped") { header("X-Member-Id", treasurer.toString()) }
                response.status shouldBe HttpStatusCode.OK
                response.bodyAsText() shouldBe "200"
            }
        }

        test("getPspConfigStatus as TREASURER -> ForbiddenException; as ADMIN -> booleans, never a fixture secret value") {
            testApplication {
                application {
                    routing {
                        post("/test/status/treasurer") {
                            shouldThrow<ForbiddenException> { PaymentGatewayService(call = call).getPspConfigStatus() }
                            call.respondText("ok")
                        }
                        post("/test/status/admin") {
                            // Test-quality fix (code review, Welle V1.2.8): the default
                            // `pspConfigState = PspConfig.load()` reads unset env vars in this test
                            // environment (NotConfigured), so there was never anything to leak in the
                            // first place -- the "never a fixture secret value" assertion below was
                            // vacuous. testPspConfigState() actually carries the fixture secrets.
                            val dto = PaymentGatewayService(call = call, pspConfigState = testPspConfigState()).getPspConfigStatus()
                            call.respondText("${dto.secretKeyConfigured}:${dto.webhookSecretConfigured}")
                        }
                    }
                }
                val treasurer = createMember("checkout-status-treasurer-${Uuid.random()}@example.org", role = AccountRole.TREASURER)
                val admin = createMember("checkout-status-admin-${Uuid.random()}@example.org", role = AccountRole.ADMIN)
                val treasurerResponse = client.post("/test/status/treasurer") { header("X-Member-Id", treasurer.toString()) }
                treasurerResponse.status shouldBe HttpStatusCode.OK
                val adminResponse = client.post("/test/status/admin") { header("X-Member-Id", admin.toString()) }
                adminResponse.status shouldBe HttpStatusCode.OK
                adminResponse.bodyAsText().contains("sk_") shouldBe false
                adminResponse.bodyAsText().contains("whsec_") shouldBe false
            }
        }

        test("enablePaymentGateway(PAYPAL, ...) -> BadRequestException") {
            testApplication {
                application {
                    routing {
                        post("/test/enable-paypal") {
                            shouldThrow<BadRequestException> {
                                PaymentGatewayService(call = call).enablePaymentGateway(
                                    provider = PaymentProvider.PAYPAL,
                                    acknowledgment =
                                        network.lapis.cloud.shared.domain.PaymentGatewayComplianceAcknowledgmentInput(
                                            disclaimerVersion = PaymentGatewayComplianceDisclaimer.VERSION,
                                            disclaimerSha256 = PaymentGatewayComplianceDisclaimer.SHA256,
                                        ),
                                )
                            }
                            call.respondText("ok")
                        }
                    }
                }
                val admin = createMember("checkout-enable-paypal-${Uuid.random()}@example.org", role = AccountRole.ADMIN)
                val response = client.post("/test/enable-paypal") { header("X-Member-Id", admin.toString()) }
                response.status shouldBe HttpStatusCode.OK
            }
        }
    })
