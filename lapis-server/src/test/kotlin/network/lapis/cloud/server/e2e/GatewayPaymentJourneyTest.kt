package network.lapis.cloud.server.e2e

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.headersOf
import io.ktor.server.application.call
import io.ktor.server.response.respondText
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import network.lapis.cloud.server.db.DatabaseConfig
import network.lapis.cloud.server.db.DbClock
import network.lapis.cloud.server.db.DevSeedData
import network.lapis.cloud.server.db.generated.AuditLogEntryTable
import network.lapis.cloud.server.db.generated.JournalEntryTable
import network.lapis.cloud.server.db.generated.LedgerAccountTable
import network.lapis.cloud.server.db.generated.OrganizationSettingsTable
import network.lapis.cloud.server.db.generated.PaymentCheckoutSessionTable
import network.lapis.cloud.server.db.generated.PaymentGatewayComplianceAcknowledgmentTable
import network.lapis.cloud.server.db.generated.PaymentTransactionTable
import network.lapis.cloud.server.db.generated.PostingTable
import network.lapis.cloud.server.db.generated.PspWebhookEventTable
import network.lapis.cloud.server.federation.FederationInboxRateLimiter
import network.lapis.cloud.server.module
import network.lapis.cloud.server.payment.psp.PspConfig
import network.lapis.cloud.server.payment.psp.PspConfigState
import network.lapis.cloud.server.payment.psp.StripeCheckoutClient
import network.lapis.cloud.server.routes.registerPspWebhookRoutes
import network.lapis.cloud.server.rpc.ORGANIZATION_SETTINGS_ID
import network.lapis.cloud.server.rpc.PaymentGatewayComplianceDisclaimer
import network.lapis.cloud.server.rpc.PaymentGatewayService
import network.lapis.cloud.shared.domain.AuditEntityType
import network.lapis.cloud.shared.domain.DonationCheckoutInput
import network.lapis.cloud.shared.domain.LedgerAccountType
import network.lapis.cloud.shared.domain.PaymentCheckoutSessionStatus
import network.lapis.cloud.shared.domain.PaymentIntent
import network.lapis.cloud.shared.domain.PaymentProvider
import network.lapis.cloud.shared.domain.PaymentTransactionQuery
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import java.math.BigDecimal
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.time.Clock
import kotlin.uuid.Uuid

private const val E2E_WEBHOOK_SECRET = "whsec_gateway_payment_journey_e2e"

/** Same HMAC idiom [network.lapis.cloud.server.routes.PspWebhookRoutesTest] establishes, duplicated here per that file's own "private to their own file" precedent. */
private fun e2eHmacHex(
    secret: String,
    data: ByteArray,
): String {
    val mac = Mac.getInstance("HmacSHA256")
    mac.init(SecretKeySpec(secret.toByteArray(Charsets.UTF_8), "HmacSHA256"))
    return mac.doFinal(data).joinToString(separator = "") { byte -> "%02x".format(byte) }
}

private fun e2eSignedHeader(
    body: ByteArray,
    secret: String = E2E_WEBHOOK_SECRET,
): String {
    val timestamp = Clock.System.now().epochSeconds
    val signedPayload = "$timestamp.".toByteArray(Charsets.UTF_8) + body
    return "t=$timestamp,v1=${e2eHmacHex(secret = secret, data = signedPayload)}"
}

/**
 * Welle V1.2.8 "PSP-Checkout (Stripe)" (GitHub Issue #6) -- end-to-end journey through the REAL,
 * fully-wired [module] (mounted exactly like every other scenario in this package, see [E2eSupport]
 * KDoc): a donor's real RPC checkout-creation call, followed by a REAL, correctly-signed
 * `checkout.session.completed` webhook delivery through the SAME [registerPspWebhookRoutes] handler
 * production uses (never bypassed) -- proving the whole chain from "member clicks donate" through
 * "money is posted to the general ledger and visible to a treasurer" without mocking anything on the
 * server side except the one genuinely external call this codebase ever makes (Stripe's own
 * `POST /v1/checkout/sessions`, via a [MockEngine]-backed [StripeCheckoutClient], same house rule
 * [StripeCheckoutClientTest] establishes for outbound PSP HTTP).
 *
 * Because the REAL `IPaymentGatewayService`/`registerPspWebhookRoutes` mounted by [module] read
 * [PspConfig] from real environment variables (unset in this sandbox -> `NotConfigured`), this
 * scenario -- like [ConferenceBreakoutJourneyTest] does for `ConferenceConfig` -- layers small
 * throwaway routes on top of the SAME `module()`-wired application: one constructing
 * [PaymentGatewayService] directly with an E2E-configured [PspConfigState.Configured], and one
 * mounting the REAL [registerPspWebhookRoutes] a second time under an isolated `/e2e-psp` path
 * prefix with that SAME E2E config (that function is not baked into a fixed environment the way
 * `IConferenceService`'s factory is -- it already takes `pspConfig` as an explicit parameter, so no
 * "construct the service directly instead" workaround is needed for the webhook leg: the actual
 * signature-verification/DoS-guard/dispatch code from `PspWebhookRoutes.kt` runs unmodified).
 *
 * The donor's own checkout-creation call goes through a REAL session-cookie login
 * ([E2eSupport.realLogin]) -- this is the specific member identity whose journey this scenario
 * proves, never `X-Member-Id`. The treasurer who later reviews the posted transaction IS a scene
 * partner ([TREASURER_ID], a pre-seeded demo account) and uses `X-Member-Id`, matching
 * [E2eSupport]'s own documented header-auth posture.
 */
class GatewayPaymentJourneyTest :
    FunSpec({
        val createdMemberIds = mutableListOf<Uuid>()
        val createdLedgerAccountIds = mutableListOf<Uuid>()
        val createdCheckoutSessionIds = mutableListOf<Uuid>()

        beforeSpec {
            DatabaseConfig.connect()
            DevSeedData.seedIfEmpty(force = true)
        }

        afterSpec {
            transaction {
                OrganizationSettingsTable.update({ OrganizationSettingsTable.id eq ORGANIZATION_SETTINGS_ID }) {
                    it[paymentGatewayEnabled] = false
                    it[paymentGatewayProvider] = null
                    it[paymentBankAccountId] = null
                    it[donationIncomeAccountId] = null
                }
                // See ContributionPostingBridgeTest/PspWebhookRoutesTest's own afterSpec KDoc -- the
                // PAYMENT_TRANSACTION/JOURNAL_ENTRY audit rows this scenario wrote reference
                // actor_member_id via a real FK; null it rather than delete (append-only chain).
                if (createdMemberIds.isNotEmpty()) {
                    AuditLogEntryTable.update({ AuditLogEntryTable.actorMemberId inList createdMemberIds }) {
                        it[actorMemberId] = null
                    }
                }
                if (createdCheckoutSessionIds.isNotEmpty()) {
                    val transactionIds =
                        PaymentTransactionTable
                            .selectAll()
                            .where { PaymentTransactionTable.checkoutSessionId inList createdCheckoutSessionIds }
                            .map { it[PaymentTransactionTable.id] }
                    if (transactionIds.isNotEmpty()) {
                        val journalEntryIds =
                            PaymentTransactionTable
                                .selectAll()
                                .where { PaymentTransactionTable.id inList transactionIds }
                                .mapNotNull { it[PaymentTransactionTable.journalEntryId] }
                        PspWebhookEventTable.deleteWhere { PspWebhookEventTable.paymentTransactionId inList transactionIds }
                        PaymentTransactionTable.deleteWhere { PaymentTransactionTable.id inList transactionIds }
                        if (journalEntryIds.isNotEmpty()) {
                            PostingTable.deleteWhere { PostingTable.journalEntryId inList journalEntryIds }
                            AuditLogEntryTable.deleteWhere { AuditLogEntryTable.entityId inList journalEntryIds }
                            JournalEntryTable.deleteWhere { JournalEntryTable.id inList journalEntryIds }
                        }
                    }
                    PaymentCheckoutSessionTable.deleteWhere { PaymentCheckoutSessionTable.id inList createdCheckoutSessionIds }
                }
                if (createdLedgerAccountIds.isNotEmpty()) {
                    LedgerAccountTable.deleteWhere { LedgerAccountTable.id inList createdLedgerAccountIds }
                }
                if (createdMemberIds.isNotEmpty()) {
                    PaymentGatewayComplianceAcknowledgmentTable.deleteWhere {
                        PaymentGatewayComplianceAcknowledgmentTable.acknowledgedByMemberId inList createdMemberIds
                    }
                }
                hardDeleteGovernanceAndMembershipFixtures(committeeIds = emptyList(), memberIds = createdMemberIds)
            }
        }

        fun createLedgerAccount(
            number: String,
            type: LedgerAccountType,
        ): Uuid {
            val id = Uuid.random()
            transaction {
                LedgerAccountTable.insert {
                    it[LedgerAccountTable.id] = id
                    it[accountNumber] = number
                    it[name] = "E2E Spenden-Testkonto $number"
                    it[accountClass] = 0
                    it[LedgerAccountTable.type] = type
                    it[active] = true
                    it[reserveType] = null
                    it[isCashRegister] = false
                }
            }
            createdLedgerAccountIds += id
            return id
        }

        fun enableGatewayFor(
            bankAccountId: Uuid,
            donationIncomeAccountId: Uuid,
            acknowledgedByMemberId: Uuid,
        ) {
            transaction {
                OrganizationSettingsTable.update({ OrganizationSettingsTable.id eq ORGANIZATION_SETTINGS_ID }) {
                    it[paymentGatewayEnabled] = true
                    it[paymentGatewayProvider] = PaymentProvider.STRIPE
                    it[paymentBankAccountId] = bankAccountId
                    it[OrganizationSettingsTable.donationIncomeAccountId] = donationIncomeAccountId
                    it[isPoliticalParty] = false
                }
                PaymentGatewayComplianceAcknowledgmentTable.insert {
                    it[id] = Uuid.random()
                    it[PaymentGatewayComplianceAcknowledgmentTable.acknowledgedByMemberId] = acknowledgedByMemberId
                    it[acknowledgedAt] = DbClock.nowLocalDateTime()
                    it[disclaimerVersion] = PaymentGatewayComplianceDisclaimer.VERSION
                    it[disclaimerSha256] = PaymentGatewayComplianceDisclaimer.SHA256
                    it[provider] = PaymentProvider.STRIPE
                }
            }
        }

        fun e2eConfig(): PspConfigState.Configured =
            requireNotNull(
                PspConfig.load {
                    when (it) {
                        PspConfig.ENV_SECRET_KEY -> "sk_test_gateway_payment_journey"
                        PspConfig.ENV_WEBHOOK_SIGNING_SECRET -> E2E_WEBHOOK_SECRET
                        else -> null
                    }
                } as? PspConfigState.Configured,
            )

        test(
            "donor creates a real Stripe checkout via RPC, a correctly-signed webhook delivery through " +
                "the REAL route posts a balanced journal entry, a treasurer sees it via listPaymentTransactions, " +
                "and a replayed delivery is a no-op",
        ) {
            testApplication {
                val stripeSessionId = "cs_e2e_journey_${Uuid.random()}"
                val mockStripeClient =
                    HttpClient(
                        MockEngine { _ ->
                            respond(
                                """{"id":"$stripeSessionId","url":"https://checkout.stripe.com/c/pay/$stripeSessionId"}""",
                                HttpStatusCode.OK,
                                headersOf(HttpHeaders.ContentType, "application/json"),
                            )
                        },
                    )
                val config = e2eConfig()
                val checkoutClient = StripeCheckoutClient(pspConfig = config.config, httpClient = mockStripeClient)

                application {
                    module()
                    routing {
                        route("/e2e-psp") {
                            registerPspWebhookRoutes(pspConfig = config, rateLimiter = FederationInboxRateLimiter())
                        }
                        post("/e2e-psp/create-donation-checkout") {
                            val service = PaymentGatewayService(call = call, pspConfigState = config, checkoutClient = checkoutClient)
                            val dto =
                                service.createDonationCheckout(
                                    DonationCheckoutInput(
                                        amount = BigDecimal("25.00"),
                                        donorCategory = null,
                                        purpose = "E2E Spende fuer die Vereinsarbeit",
                                    ),
                                )
                            call.respondText("${dto.id}|${dto.status}|${dto.amount}")
                        }
                        post("/e2e-psp/get-checkout-session/{id}") {
                            val service = PaymentGatewayService(call = call, pspConfigState = config, checkoutClient = checkoutClient)
                            val dto = service.getCheckoutSession(requireNotNull(call.parameters["id"]))
                            call.respondText("${dto.status}|${dto.paymentTransactionId}|${dto.journalEntryId}")
                        }
                        post("/e2e-psp/list-transactions") {
                            val service = PaymentGatewayService(call = call, pspConfigState = config, checkoutClient = checkoutClient)
                            // Test-quality fix (code review, Welle V1.2.8): scoped by the optional
                            // memberId query param instead of always querying UNFILTERED -- see the
                            // call site below for why (shared-test-DB flakiness, same scoping
                            // discipline PspWebhookRoutesTest's own lines ~448-453 already document).
                            val memberIdFilter = call.request.queryParameters["memberId"]
                            val page = service.listPaymentTransactions(PaymentTransactionQuery(memberId = memberIdFilter))
                            call.respondText(
                                page.rows.joinToString(";") { row -> "${row.amount}|${row.intent}|${row.journalEntryId}" },
                            )
                        }
                    }
                }

                fun checkoutCompletedBody(
                    eventId: String,
                    amountTotalMinorUnits: Long,
                ): ByteArray =
                    """
                    {"id":"$eventId","type":"checkout.session.completed","data":{"object":{"id":"$stripeSessionId",
                    "payment_intent":"pi_${eventId}_intent","amount_total":$amountTotalMinorUnits,"currency":"eur"}}}
                    """.trimIndent().toByteArray(Charsets.UTF_8)

                // ── Setup: the accounting side of the gateway ("scene", not this journey's own actor). ──
                val bankAccountId = createLedgerAccount(number = "E1${Uuid.random().toString().take(6)}", type = LedgerAccountType.ASSET)
                val incomeAccountId =
                    createLedgerAccount(number = "E2${Uuid.random().toString().take(6)}", type = LedgerAccountType.INCOME)
                val adminId = createRealMember(displayName = "E2E PSP Admin", email = "e2e-psp-admin-${Uuid.random()}@example.org")
                createdMemberIds += adminId
                enableGatewayFor(
                    bankAccountId = bankAccountId,
                    donationIncomeAccountId = incomeAccountId,
                    acknowledgedByMemberId = adminId,
                )

                // ── Step 1: the donor -- THIS journey's own actor -- logs in for real and creates a ──────
                // ── checkout session via the real RPC service. ──────────────────────────────────────────
                val donorEmail = "e2e-psp-donor-${Uuid.random()}@example.org"
                val donorId = createRealMember(displayName = "E2E PSP Spenderin", email = donorEmail, password = E2E_STRONG_PASSWORD)
                createdMemberIds += donorId
                val donorToken = client.realLogin(email = donorEmail, password = E2E_STRONG_PASSWORD)
                val createResponse =
                    client
                        .post("/e2e-psp/create-donation-checkout") { withSession(donorToken) }
                        .bodyAsText()
                        .split("|")
                val checkoutSessionId = createResponse[0]
                createResponse[1] shouldBe PaymentCheckoutSessionStatus.CREATED.toString()
                createResponse[2] shouldBe "25.00"
                createdCheckoutSessionIds += Uuid.parse(checkoutSessionId)

                // ── Step 2: Stripe delivers a correctly-signed checkout.session.completed webhook to ────
                // ── the REAL registerPspWebhookRoutes handler (mounted under /e2e-psp, same code as ─────
                // ── production's own /api/webhooks/stripe). ──────────────────────────────────────────────
                val eventId = "evt_e2e_journey_${Uuid.random()}"
                val body = checkoutCompletedBody(eventId = eventId, amountTotalMinorUnits = 2500)
                val webhookResponse =
                    client.post("/e2e-psp/api/webhooks/stripe") {
                        header("Stripe-Signature", e2eSignedHeader(body = body))
                        contentType(ContentType.Application.Json)
                        setBody(body)
                    }
                webhookResponse.status shouldBe HttpStatusCode.OK

                // ── Step 3: the donor's own checkout session now shows COMPLETED with a linked ──────────
                // ── payment_transaction/journal_entry -- proven through the same real RPC, not a raw ─────
                // ── DB peek. ──────────────────────────────────────────────────────────────────────────────
                val sessionAfter =
                    client
                        .post("/e2e-psp/get-checkout-session/$checkoutSessionId") { withSession(donorToken) }
                        .bodyAsText()
                        .split("|")
                sessionAfter[0] shouldBe PaymentCheckoutSessionStatus.COMPLETED.toString()
                val paymentTransactionId = sessionAfter[1]
                paymentTransactionId shouldNotBe "null"
                val journalEntryId = sessionAfter[2]
                journalEntryId shouldNotBe "null"

                // ── Step 4: DB-level ground truth -- exactly one balanced (2-posting) journal entry, ────
                // ── and a PAYMENT_TRANSACTION audit entry, written by the REAL ingestion code path. ───────
                transaction {
                    val postingCount =
                        PostingTable.selectAll().where { PostingTable.journalEntryId eq Uuid.parse(journalEntryId) }.count()
                    postingCount shouldBe 2L
                    val auditRow =
                        AuditLogEntryTable
                            .selectAll()
                            .where {
                                (AuditLogEntryTable.entityType eq AuditEntityType.PAYMENT_TRANSACTION) and
                                    (AuditLogEntryTable.entityId eq Uuid.parse(paymentTransactionId))
                            }.singleOrNull()
                    auditRow.shouldNotBeNull()
                }

                // ── Step 5: the treasurer -- a scene partner, X-Member-Id per E2eSupport's own posture -- ─
                // ── sees the posted transaction via the real listPaymentTransactions RPC. ──────────────────
                // Test-quality fix (code review, Welle V1.2.8): the previous, unfiltered
                // listPaymentTransactions() call was exposed to the same JVM-worker-sharing
                // flakiness PspWebhookRoutesTest's own lines ~448-453 already document and guard
                // against for its own query -- scoping by donorId (unique to this test) instead of
                // asserting a bare "size 1" against the shared test DB's ENTIRE transaction table.
                val treasurerRows =
                    client
                        .post("/e2e-psp/list-transactions?memberId=$donorId") { header("X-Member-Id", TREASURER_ID) }
                        .bodyAsText()
                        .split(";")
                treasurerRows shouldHaveSize 1
                val (amount, intent, journalEntryIdFromList) = treasurerRows[0].split("|")
                amount shouldBe "25.00"
                intent shouldBe PaymentIntent.DONATION.toString()
                journalEntryIdFromList shouldBe journalEntryId

                // ── Step 6: Stripe redelivers the SAME event (its own documented at-least-once ────────────
                // ── semantics) -- the real ingestion's idempotency anchor makes this a pure no-op, no ──────
                // ── second journal entry, still 200 OK (Stripe must not retry forever). ────────────────────
                val replayResponse =
                    client.post("/e2e-psp/api/webhooks/stripe") {
                        header("Stripe-Signature", e2eSignedHeader(body = body))
                        contentType(ContentType.Application.Json)
                        setBody(body)
                    }
                replayResponse.status shouldBe HttpStatusCode.OK
                transaction {
                    val transactionCount =
                        PaymentTransactionTable
                            .selectAll()
                            .where { PaymentTransactionTable.checkoutSessionId eq Uuid.parse(checkoutSessionId) }
                            .count()
                    transactionCount shouldBe 1L
                }
            }
        }
    })
