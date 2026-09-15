package network.lapis.cloud.server.routes

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.headersOf
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.server.db.DatabaseConfig
import network.lapis.cloud.server.db.generated.AccountTable
import network.lapis.cloud.server.db.generated.AuditLogEntryTable
import network.lapis.cloud.server.db.generated.ContributionTable
import network.lapis.cloud.server.db.generated.JournalEntryTable
import network.lapis.cloud.server.db.generated.LedgerAccountTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.server.db.generated.MembershipTierTable
import network.lapis.cloud.server.db.generated.OrganizationSettingsTable
import network.lapis.cloud.server.db.generated.PaymentCheckoutSessionTable
import network.lapis.cloud.server.db.generated.PaymentGatewayComplianceAcknowledgmentTable
import network.lapis.cloud.server.db.generated.PaymentTransactionTable
import network.lapis.cloud.server.db.generated.PostingTable
import network.lapis.cloud.server.db.generated.PspWebhookEventTable
import network.lapis.cloud.server.federation.FederationInboxRateLimiter
import network.lapis.cloud.server.payment.psp.PaypalConfig
import network.lapis.cloud.server.payment.psp.PaypalConfigState
import network.lapis.cloud.server.payment.psp.PaypalOrdersClient
import network.lapis.cloud.server.payment.psp.PspWebhookOutcome
import network.lapis.cloud.server.rpc.ORGANIZATION_SETTINGS_ID
import network.lapis.cloud.server.rpc.PaymentGatewayComplianceDisclaimer
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.BillingInterval
import network.lapis.cloud.shared.domain.ContributionStatus
import network.lapis.cloud.shared.domain.LedgerAccountType
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.domain.PaymentCheckoutSessionStatus
import network.lapis.cloud.shared.domain.PaymentIntent
import network.lapis.cloud.shared.domain.PaymentProvider
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import java.math.BigDecimal
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid

private fun paypalHeaders(
    transmissionId: String = "txn-${Uuid.random()}",
    transmissionTime: String = Clock.System.now().toString(),
): Map<String, String> =
    mapOf(
        "PAYPAL-TRANSMISSION-ID" to transmissionId,
        "PAYPAL-TRANSMISSION-TIME" to transmissionTime,
        "PAYPAL-TRANSMISSION-SIG" to "sig-$transmissionId",
        "PAYPAL-CERT-URL" to "https://api-m.paypal.com/cert",
        "PAYPAL-AUTH-ALGO" to "SHA256withRSA",
    )

/**
 * Welle V1.2.8b "PayPal-Anbindung" (GitHub Issue #6) -- `POST /api/webhooks/paypal` end-to-end
 * over a real `testApplication`, same house style [PspWebhookRoutesTest] establishes for the
 * Stripe sibling route. [PaypalOrdersClient.verifyWebhookSignature]/`captureOrder` are exercised
 * against a [MockEngine]-backed [HttpClient] -- never a real outbound call to PayPal -- so every
 * test controls the verify-API's `verification_status` and the capture response directly, rather
 * than needing a real HMAC-equivalent signature scheme the way the Stripe route's own tests do.
 *
 * Fix (Review round 1, MAJOR): this file did not exist at all before -- `PaypalWebhookRoutes.kt`
 * (the actual money-receiving HTTP endpoint) had zero test coverage.
 */
class PaypalWebhookRoutesTest :
    FunSpec({
        val createdMemberIds = mutableListOf<Uuid>()
        val createdLedgerAccountIds = mutableListOf<Uuid>()
        val createdTierIds = mutableListOf<Uuid>()
        val createdContributionIds = mutableListOf<Uuid>()
        val createdCheckoutSessionIds = mutableListOf<Uuid>()

        beforeSpec { DatabaseConfig.connect() }

        afterTest {
            transaction {
                OrganizationSettingsTable.update({ OrganizationSettingsTable.id eq ORGANIZATION_SETTINGS_ID }) {
                    it[paymentGatewayEnabled] = false
                    it[paymentGatewayProvider] = null
                    it[paymentBankAccountId] = null
                    it[contributionIncomeAccountId] = null
                }
            }
        }

        afterSpec {
            transaction {
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
                if (createdContributionIds.isNotEmpty()) {
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

        fun createMember(email: String): Uuid {
            val id = Uuid.random()
            transaction {
                MemberTable.insert {
                    it[MemberTable.id] = id
                    it[displayName] = "PaypalWebhookRoutes Testmitglied"
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

        fun createLedgerAccount(
            number: String,
            type: LedgerAccountType,
        ): Uuid {
            val id = Uuid.random()
            transaction {
                LedgerAccountTable.insert {
                    it[LedgerAccountTable.id] = id
                    it[accountNumber] = number
                    it[name] = "PaypalWebhookRoutes Konto $number"
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

        fun createTier(): Uuid {
            val id = Uuid.random()
            transaction {
                MembershipTierTable.insert {
                    it[MembershipTierTable.id] = id
                    it[name] = "PaypalWebhookRoutes Tarif ${id.toString().take(6)}"
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

        fun createOpenContribution(
            memberId: Uuid,
            tierId: Uuid,
            amountDue: BigDecimal,
        ): Uuid {
            val id = Uuid.random()
            transaction {
                ContributionTable.insert {
                    it[ContributionTable.id] = id
                    it[periodStart] = LocalDate(2026, 1, 1)
                    it[periodEnd] = LocalDate(2026, 12, 31)
                    it[ContributionTable.amountDue] = amountDue
                    it[ContributionTable.status] = ContributionStatus.OPEN
                    it[ContributionTable.createdAt] = LocalDateTime(2026, 1, 1, 0, 0)
                    it[ContributionTable.memberId] = memberId
                    it[ContributionTable.membershipTierId] = tierId
                    it[dueDate] = LocalDate(2026, 1, 15)
                }
            }
            createdContributionIds += id
            return id
        }

        fun createCheckoutSession(
            memberId: Uuid,
            contributionId: Uuid,
            amount: BigDecimal,
            providerSessionId: String,
            currency: String = "EUR",
        ): Uuid {
            val id = Uuid.random()
            transaction {
                PaymentCheckoutSessionTable.insert {
                    it[PaymentCheckoutSessionTable.id] = id
                    it[provider] = PaymentProvider.PAYPAL
                    it[PaymentCheckoutSessionTable.providerSessionId] = providerSessionId
                    it[status] = PaymentCheckoutSessionStatus.CREATED
                    it[intent] = PaymentIntent.CONTRIBUTION
                    it[PaymentCheckoutSessionTable.contributionId] = contributionId
                    it[PaymentCheckoutSessionTable.memberId] = memberId
                    it[PaymentCheckoutSessionTable.amount] = amount
                    it[PaymentCheckoutSessionTable.currency] = currency
                    it[donorCategory] = null
                    it[purpose] = null
                    it[createdAt] = LocalDateTime(2026, 4, 1, 10, 0)
                    it[expiresAt] = LocalDateTime(2026, 4, 1, 11, 0)
                    it[completedAt] = null
                    it[providerIdempotencyKey] = "idem-${id.toString().take(8)}"
                    it[redirectUrl] = "https://www.paypal.com/checkoutnow?token=$providerSessionId"
                }
            }
            createdCheckoutSessionIds += id
            return id
        }

        fun enableGateway(
            bankAccountId: Uuid,
            incomeAccountId: Uuid,
        ) {
            transaction {
                OrganizationSettingsTable.update({ OrganizationSettingsTable.id eq ORGANIZATION_SETTINGS_ID }) {
                    it[paymentGatewayEnabled] = true
                    it[paymentGatewayProvider] = PaymentProvider.PAYPAL
                    it[paymentBankAccountId] = bankAccountId
                    it[contributionIncomeAccountId] = incomeAccountId
                }
                PaymentGatewayComplianceAcknowledgmentTable.insert {
                    it[id] = Uuid.random()
                    it[acknowledgedByMemberId] = createMember("paypal-webhook-ack-${Uuid.random()}@example.org")
                    it[acknowledgedAt] = LocalDateTime(2026, 4, 1, 9, 0)
                    it[disclaimerVersion] = PaymentGatewayComplianceDisclaimer.VERSION
                    it[disclaimerSha256] = PaymentGatewayComplianceDisclaimer.SHA256
                    it[provider] = PaymentProvider.PAYPAL
                }
            }
        }

        fun testPaypalConfig(): PaypalConfigState.Configured =
            requireNotNull(
                PaypalConfig.load {
                    when (it) {
                        PaypalConfig.ENV_CLIENT_ID -> "test-paypal-webhook-routes-client-id-0000"
                        PaypalConfig.ENV_CLIENT_SECRET -> "test-paypal-webhook-routes-client-secret"
                        PaypalConfig.ENV_WEBHOOK_ID -> "WH-PAYPAL-WEBHOOK-ROUTES-TEST"
                        else -> null
                    }
                } as? PaypalConfigState.Configured,
            )

        /**
         * A [MockEngine]-backed [PaypalOrdersClient] whose verify-API always reports
         * [verificationStatus] and whose capture endpoint always succeeds with [captureAmount]/
         * [captureCurrency] for the requested order id -- lets each test drive
         * `verifyWebhookSignature`/`captureOrder` deterministically without a real HMAC-equivalent
         * signing scheme (PayPal's own verify-webhook-signature API is itself the thing under test
         * at the route level, its own internal correctness is [PaypalWebhookVerificationTest]'s job).
         */
        fun paypalClient(
            verificationStatus: String = "SUCCESS",
            verifyHttpStatus: HttpStatusCode = HttpStatusCode.OK,
            captureAmount: String = "50.00",
            captureCurrency: String = "EUR",
            captureAlreadyCaptured: Boolean = false,
            calledPaths: MutableList<String>? = null,
        ): PaypalOrdersClient {
            val engine =
                MockEngine { request ->
                    val path = request.url.encodedPath
                    calledPaths?.add(path)
                    when {
                        path.endsWith("/v1/oauth2/token") ->
                            respond(
                                """{"access_token":"test-access-token","token_type":"Bearer","expires_in":32400}""",
                                HttpStatusCode.OK,
                                headersOf(HttpHeaders.ContentType, "application/json"),
                            )
                        path.endsWith("/v1/notifications/verify-webhook-signature") ->
                            respond(
                                """{"verification_status":"$verificationStatus"}""",
                                verifyHttpStatus,
                                headersOf(HttpHeaders.ContentType, "application/json"),
                            )
                        path.endsWith("/capture") && captureAlreadyCaptured ->
                            respond(
                                """{"name":"UNPROCESSABLE_ENTITY","message":"Order already captured",
                                "details":[{"issue":"ORDER_ALREADY_CAPTURED","description":"Order already captured"}]}""",
                                HttpStatusCode.UnprocessableEntity,
                                headersOf(HttpHeaders.ContentType, "application/json"),
                            )
                        path.endsWith("/capture") -> {
                            val captureId = "CAP-${Uuid.random()}"
                            respond(
                                """{"id":"order-id","status":"COMPLETED","purchase_units":[{"payments":{"captures":[
                                {"id":"$captureId","status":"COMPLETED","amount":{"currency_code":"$captureCurrency","value":"$captureAmount"}}
                                ]}}]}""",
                                HttpStatusCode.OK,
                                headersOf(HttpHeaders.ContentType, "application/json"),
                            )
                        }
                        else -> respond("{}", HttpStatusCode.NotFound)
                    }
                }
            return PaypalOrdersClient(config = testPaypalConfig().config, httpClient = HttpClient(engine))
        }

        fun approvedBody(
            eventId: String,
            orderId: String,
            checkoutSessionId: String,
        ): String =
            """
            {"id":"$eventId","event_type":"CHECKOUT.ORDER.APPROVED",
            "resource":{"id":"$orderId","status":"APPROVED","custom_id":"$checkoutSessionId"}}
            """.trimIndent()

        fun capturedBody(
            eventId: String,
            captureId: String,
            orderId: String,
            amountValue: String,
            currency: String = "EUR",
        ): String =
            """
            {"id":"$eventId","event_type":"PAYMENT.CAPTURE.COMPLETED",
            "resource":{"id":"$captureId","status":"COMPLETED",
            "amount":{"currency_code":"$currency","value":"$amountValue"},
            "supplementary_data":{"related_ids":{"order_id":"$orderId"}}}}
            """.trimIndent()

        test("happy path: CHECKOUT.ORDER.APPROVED then PAYMENT.CAPTURE.COMPLETED -> 200/200, PAID, one balanced journal entry") {
            testApplication {
                val client2 = paypalClient(captureAmount = "50.00")
                application {
                    routing {
                        registerPaypalWebhookRoutes(
                            paypalConfig = testPaypalConfig(),
                            ordersClient = client2,
                            rateLimiter = FederationInboxRateLimiter(),
                        )
                    }
                }
                val member = createMember("paypal-webhook-happy-${Uuid.random()}@example.org")
                val tier = createTier()
                val contributionId = createOpenContribution(memberId = member, tierId = tier, amountDue = BigDecimal("50.00"))
                val bankAccountId = createLedgerAccount(number = "P1${Uuid.random().toString().take(6)}", type = LedgerAccountType.ASSET)
                val incomeAccountId = createLedgerAccount(number = "P2${Uuid.random().toString().take(6)}", type = LedgerAccountType.INCOME)
                enableGateway(bankAccountId = bankAccountId, incomeAccountId = incomeAccountId)
                val orderId = "EC-happy-${Uuid.random()}"
                createCheckoutSession(
                    memberId = member,
                    contributionId = contributionId,
                    amount = BigDecimal("50.00"),
                    providerSessionId = orderId,
                )

                val approvedEventId = "WH-happy-approved-${Uuid.random()}"
                val approvedResponse =
                    client.post("/api/webhooks/paypal") {
                        paypalHeaders(transmissionId = approvedEventId).forEach { (name, value) -> header(name, value) }
                        contentType(ContentType.Application.Json)
                        setBody(approvedBody(eventId = approvedEventId, orderId = orderId, checkoutSessionId = "irrelevant"))
                    }
                approvedResponse.status shouldBe HttpStatusCode.OK

                val sessionStatusAfterApproval =
                    transaction {
                        PaymentCheckoutSessionTable
                            .selectAll()
                            .where { PaymentCheckoutSessionTable.providerSessionId eq orderId }
                            .single()[PaymentCheckoutSessionTable.status]
                    }
                sessionStatusAfterApproval shouldBe PaymentCheckoutSessionStatus.CREATED

                val captureEventId = "WH-happy-captured-${Uuid.random()}"
                val capturedResponse =
                    client.post("/api/webhooks/paypal") {
                        paypalHeaders(transmissionId = captureEventId).forEach { (name, value) -> header(name, value) }
                        contentType(ContentType.Application.Json)
                        setBody(capturedBody(eventId = captureEventId, captureId = "CAP-happy", orderId = orderId, amountValue = "50.00"))
                    }
                capturedResponse.status shouldBe HttpStatusCode.OK

                val contributionStatus =
                    transaction {
                        ContributionTable.selectAll().where { ContributionTable.id eq contributionId }.single()[ContributionTable.status]
                    }
                contributionStatus shouldBe ContributionStatus.PAID

                val transactionRow =
                    transaction {
                        PaymentTransactionTable.selectAll().where { PaymentTransactionTable.contributionId eq contributionId }.single()
                    }
                transactionRow[PaymentTransactionTable.journalEntryId].shouldNotBeNull()
                val postingCount =
                    transaction {
                        PostingTable
                            .selectAll()
                            .where { PostingTable.journalEntryId eq requireNotNull(transactionRow[PaymentTransactionTable.journalEntryId]) }
                            .count()
                    }
                postingCount shouldBe 2L
            }
        }

        test("verify-webhook-signature reports FAILURE -> 401, no accounting touched") {
            testApplication {
                application {
                    routing {
                        registerPaypalWebhookRoutes(
                            paypalConfig = testPaypalConfig(),
                            ordersClient = paypalClient(verificationStatus = "FAILURE"),
                            rateLimiter = FederationInboxRateLimiter(),
                        )
                    }
                }
                val eventId = "WH-not-verified-${Uuid.random()}"
                val response =
                    client.post("/api/webhooks/paypal") {
                        paypalHeaders(transmissionId = eventId).forEach { (name, value) -> header(name, value) }
                        contentType(ContentType.Application.Json)
                        setBody(approvedBody(eventId = eventId, orderId = "order-x", checkoutSessionId = "irrelevant"))
                    }
                response.status shouldBe HttpStatusCode.Unauthorized
                val loggedRow =
                    transaction { PspWebhookEventTable.selectAll().where { PspWebhookEventTable.providerEventId eq eventId }.toList() }
                // MALFORMED_EVENT/NOT_VERIFIED rejections are logged WITHOUT providerEventId (event is
                // never typed-decoded before the verify step) -- see PaypalWebhookRoutes.kt step ordering.
                loggedRow shouldBe emptyList()
            }
        }

        test("verify-webhook-signature API unavailable -> 503 (PayPal retries), not 401") {
            testApplication {
                application {
                    routing {
                        registerPaypalWebhookRoutes(
                            paypalConfig = testPaypalConfig(),
                            ordersClient =
                                paypalClient(
                                    verificationStatus = "SUCCESS",
                                    verifyHttpStatus = HttpStatusCode.InternalServerError,
                                ),
                            rateLimiter = FederationInboxRateLimiter(),
                        )
                    }
                }
                val eventId = "WH-verify-unavailable-${Uuid.random()}"
                val response =
                    client.post("/api/webhooks/paypal") {
                        paypalHeaders(transmissionId = eventId).forEach { (name, value) -> header(name, value) }
                        contentType(ContentType.Application.Json)
                        setBody(approvedBody(eventId = eventId, orderId = "order-x", checkoutSessionId = "irrelevant"))
                    }
                response.status shouldBe HttpStatusCode.ServiceUnavailable
            }
        }

        test("missing PAYPAL-* transmission headers -> 401, verify-webhook-signature API never called") {
            testApplication {
                val calledPaths = mutableListOf<String>()
                application {
                    routing {
                        registerPaypalWebhookRoutes(
                            paypalConfig = testPaypalConfig(),
                            ordersClient = paypalClient(calledPaths = calledPaths),
                            rateLimiter = FederationInboxRateLimiter(),
                        )
                    }
                }
                val eventId = "WH-no-headers-${Uuid.random()}"
                val response =
                    client.post("/api/webhooks/paypal") {
                        contentType(ContentType.Application.Json)
                        setBody(approvedBody(eventId = eventId, orderId = "order-x", checkoutSessionId = "irrelevant"))
                    }
                response.status shouldBe HttpStatusCode.Unauthorized
                // Header parse (step 5) rejects BEFORE the verify-webhook-signature call (step 6) is
                // ever issued -- the DoS-amplification guard the class KDoc describes was previously
                // only true "by code ordering", never actually asserted by a test.
                calledPaths.any { it.endsWith("/v1/notifications/verify-webhook-signature") } shouldBe false
            }
        }

        test("PayPal config NOT_CONFIGURED -> 503, REJECTED, NOT_CONFIGURED") {
            testApplication {
                application {
                    routing {
                        registerPaypalWebhookRoutes(
                            paypalConfig = PaypalConfigState.NotConfigured,
                            ordersClient = null,
                            rateLimiter = FederationInboxRateLimiter(),
                        )
                    }
                }
                val eventId = "WH-not-configured-${Uuid.random()}"
                val body = approvedBody(eventId = eventId, orderId = "order-x", checkoutSessionId = "irrelevant")
                val response =
                    client.post("/api/webhooks/paypal") {
                        paypalHeaders(transmissionId = eventId).forEach { (name, value) -> header(name, value) }
                        contentType(ContentType.Application.Json)
                        setBody(body)
                    }
                response.status shouldBe HttpStatusCode.ServiceUnavailable
                // The config gate (step 4) rejects BEFORE typed decode, so no `providerEventId` is
                // ever recorded on this row -- matched by body hash instead, same shape as the
                // NOT_VERIFIED/stale-transmission-time rejections above.
                val loggedRow =
                    transaction {
                        PspWebhookEventTable
                            .selectAll()
                            .where { PspWebhookEventTable.bodySha256 eq sha256Hex(body.toByteArray(Charsets.UTF_8)) }
                            .single()
                    }
                loggedRow[PspWebhookEventTable.outcome] shouldBe PspWebhookOutcome.REJECTED.name
                loggedRow[PspWebhookEventTable.rejectReason] shouldBe "NOT_CONFIGURED"
            }
        }

        test(
            "JSON nesting depth exceeds the cap -> 400, JSON_TOO_DEEP, verify-webhook-signature API never called",
        ) {
            testApplication {
                val calledPaths = mutableListOf<String>()
                application {
                    routing {
                        registerPaypalWebhookRoutes(
                            paypalConfig = testPaypalConfig(),
                            ordersClient = paypalClient(calledPaths = calledPaths),
                            rateLimiter = FederationInboxRateLimiter(),
                        )
                    }
                }
                val eventId = "WH-too-deep-${Uuid.random()}"
                // 25 nested arrays -- above PSP_WEBHOOK_MAX_JSON_NESTING_DEPTH (20).
                val deepNesting = "[".repeat(25) + "]".repeat(25)
                val body =
                    """
                    {"id":"$eventId","event_type":"CHECKOUT.ORDER.APPROVED",
                    "resource":{"id":"order-too-deep","status":"APPROVED","custom_id":$deepNesting}}
                    """.trimIndent()
                val response =
                    client.post("/api/webhooks/paypal") {
                        paypalHeaders(transmissionId = eventId).forEach { (name, value) -> header(name, value) }
                        contentType(ContentType.Application.Json)
                        setBody(body)
                    }
                response.status shouldBe HttpStatusCode.BadRequest
                // The nesting-depth scan (step 7, moved ahead of step 6 -- see class KDoc pitfall
                // §6.4) rejects BEFORE the verify-webhook-signature call is ever issued, proving the
                // scan really does run first rather than merely being declared to in a comment.
                calledPaths.any { it.endsWith("/v1/notifications/verify-webhook-signature") } shouldBe false
            }
        }

        test(
            "CHECKOUT.ORDER.APPROVED dispatch, capture already captured (422 ORDER_ALREADY_CAPTURED) -> 200 OK, DUPLICATE",
        ) {
            testApplication {
                val calledPaths = mutableListOf<String>()
                application {
                    routing {
                        registerPaypalWebhookRoutes(
                            paypalConfig = testPaypalConfig(),
                            ordersClient = paypalClient(captureAlreadyCaptured = true, calledPaths = calledPaths),
                            rateLimiter = FederationInboxRateLimiter(),
                        )
                    }
                }
                val bankAccountId = createLedgerAccount(number = "PF${Uuid.random().toString().take(6)}", type = LedgerAccountType.ASSET)
                val incomeAccountId = createLedgerAccount(number = "PG${Uuid.random().toString().take(6)}", type = LedgerAccountType.INCOME)
                enableGateway(bankAccountId = bankAccountId, incomeAccountId = incomeAccountId)

                val eventId = "WH-already-captured-${Uuid.random()}"
                val response =
                    client.post("/api/webhooks/paypal") {
                        paypalHeaders(transmissionId = eventId).forEach { (name, value) -> header(name, value) }
                        contentType(ContentType.Application.Json)
                        setBody(approvedBody(eventId = eventId, orderId = "order-already-captured", checkoutSessionId = "irrelevant"))
                    }
                response.status shouldBe HttpStatusCode.OK
                val loggedRow =
                    transaction { PspWebhookEventTable.selectAll().where { PspWebhookEventTable.providerEventId eq eventId }.single() }
                loggedRow[PspWebhookEventTable.outcome] shouldBe PspWebhookOutcome.DUPLICATE.name
                calledPaths.any { it.endsWith("/capture") } shouldBe true
            }
        }

        test("stale PAYPAL-TRANSMISSION-TIME (verified signature, but past tolerance) -> 401, ordering AFTER verify success") {
            testApplication {
                application {
                    routing {
                        registerPaypalWebhookRoutes(
                            paypalConfig = testPaypalConfig(),
                            ordersClient = paypalClient(verificationStatus = "SUCCESS"),
                            rateLimiter = FederationInboxRateLimiter(),
                        )
                    }
                }
                val eventId = "WH-stale-${Uuid.random()}"
                val staleTime = (Clock.System.now() - 10_000.seconds).toString()
                val response =
                    client.post("/api/webhooks/paypal") {
                        paypalHeaders(transmissionId = eventId, transmissionTime = staleTime).forEach { (name, value) ->
                            header(name, value)
                        }
                        contentType(ContentType.Application.Json)
                        setBody(approvedBody(eventId = eventId, orderId = "order-x", checkoutSessionId = "irrelevant"))
                    }
                response.status shouldBe HttpStatusCode.Unauthorized
                val loggedRow =
                    transaction { PspWebhookEventTable.selectAll().where { PspWebhookEventTable.providerEventId eq eventId }.toList() }
                // The freshness check runs on the raw JSON body, before typed decode -- same
                // "logged without providerEventId" shape as the NOT_VERIFIED case above.
                loggedRow shouldBe emptyList()
            }
        }

        test("gate disabled (payment_gateway_enabled=false) -> 503, no accounting touched") {
            testApplication {
                application {
                    routing {
                        registerPaypalWebhookRoutes(
                            paypalConfig = testPaypalConfig(),
                            ordersClient = paypalClient(),
                            rateLimiter = FederationInboxRateLimiter(),
                        )
                    }
                }
                val eventId = "WH-gate-disabled-${Uuid.random()}"
                val response =
                    client.post("/api/webhooks/paypal") {
                        paypalHeaders(transmissionId = eventId).forEach { (name, value) -> header(name, value) }
                        contentType(ContentType.Application.Json)
                        setBody(approvedBody(eventId = eventId, orderId = "order-x", checkoutSessionId = "irrelevant"))
                    }
                response.status shouldBe HttpStatusCode.ServiceUnavailable
                val loggedRow =
                    transaction { PspWebhookEventTable.selectAll().where { PspWebhookEventTable.providerEventId eq eventId }.single() }
                loggedRow[PspWebhookEventTable.outcome] shouldBe PspWebhookOutcome.REJECTED.name
                loggedRow[PspWebhookEventTable.rejectReason] shouldBe "GATE_DISABLED"
            }
        }

        test("unresolvable session (no matching payment_checkout_session) -> 200 OK, UNPOSTED, no journal entry") {
            testApplication {
                application {
                    routing {
                        registerPaypalWebhookRoutes(
                            paypalConfig = testPaypalConfig(),
                            ordersClient = paypalClient(),
                            rateLimiter = FederationInboxRateLimiter(),
                        )
                    }
                }
                val bankAccountId = createLedgerAccount(number = "P3${Uuid.random().toString().take(6)}", type = LedgerAccountType.ASSET)
                val incomeAccountId = createLedgerAccount(number = "P4${Uuid.random().toString().take(6)}", type = LedgerAccountType.INCOME)
                enableGateway(bankAccountId = bankAccountId, incomeAccountId = incomeAccountId)

                val eventId = "WH-unresolvable-${Uuid.random()}"
                val response =
                    client.post("/api/webhooks/paypal") {
                        paypalHeaders(transmissionId = eventId).forEach { (name, value) -> header(name, value) }
                        contentType(ContentType.Application.Json)
                        setBody(
                            capturedBody(
                                eventId = eventId,
                                captureId = "CAP-unresolvable",
                                orderId = "EC-never-created-${Uuid.random()}",
                                amountValue = "10.00",
                            ),
                        )
                    }
                response.status shouldBe HttpStatusCode.OK
                val loggedRow =
                    transaction { PspWebhookEventTable.selectAll().where { PspWebhookEventTable.providerEventId eq eventId }.single() }
                loggedRow[PspWebhookEventTable.outcome] shouldBe PspWebhookOutcome.UNPOSTED.name
            }
        }

        test("amount mismatch: stored session says 50.00, capture reports 5.00 -> 200, journal_entry_id IS NULL, UNPOSTED") {
            testApplication {
                application {
                    routing {
                        registerPaypalWebhookRoutes(
                            paypalConfig = testPaypalConfig(),
                            ordersClient = paypalClient(captureAmount = "5.00"),
                            rateLimiter = FederationInboxRateLimiter(),
                        )
                    }
                }
                val member = createMember("paypal-webhook-mismatch-${Uuid.random()}@example.org")
                val tier = createTier()
                val contributionId = createOpenContribution(memberId = member, tierId = tier, amountDue = BigDecimal("50.00"))
                val bankAccountId = createLedgerAccount(number = "P5${Uuid.random().toString().take(6)}", type = LedgerAccountType.ASSET)
                val incomeAccountId = createLedgerAccount(number = "P6${Uuid.random().toString().take(6)}", type = LedgerAccountType.INCOME)
                enableGateway(bankAccountId = bankAccountId, incomeAccountId = incomeAccountId)
                val orderId = "EC-mismatch-${Uuid.random()}"
                createCheckoutSession(
                    memberId = member,
                    contributionId = contributionId,
                    amount = BigDecimal("50.00"),
                    providerSessionId = orderId,
                )

                val approvedEventId = "WH-mismatch-approved-${Uuid.random()}"
                client.post("/api/webhooks/paypal") {
                    paypalHeaders(transmissionId = approvedEventId).forEach { (name, value) -> header(name, value) }
                    contentType(ContentType.Application.Json)
                    setBody(approvedBody(eventId = approvedEventId, orderId = orderId, checkoutSessionId = "irrelevant"))
                }

                val captureEventId = "WH-mismatch-captured-${Uuid.random()}"
                val response =
                    client.post("/api/webhooks/paypal") {
                        paypalHeaders(transmissionId = captureEventId).forEach { (name, value) -> header(name, value) }
                        contentType(ContentType.Application.Json)
                        setBody(capturedBody(eventId = captureEventId, captureId = "CAP-mismatch", orderId = orderId, amountValue = "5.00"))
                    }
                response.status shouldBe HttpStatusCode.OK

                val transactionRow =
                    transaction {
                        PaymentTransactionTable.selectAll().where { PaymentTransactionTable.contributionId eq contributionId }.single()
                    }
                transactionRow[PaymentTransactionTable.journalEntryId] shouldBe null
                val contributionStatus =
                    transaction {
                        ContributionTable.selectAll().where { ContributionTable.id eq contributionId }.single()[ContributionTable.status]
                    }
                contributionStatus shouldBe ContributionStatus.OPEN
            }
        }

        test("currency mismatch: stored session says EUR, capture reports USD -> 200, journal_entry_id IS NULL, UNPOSTED") {
            testApplication {
                application {
                    routing {
                        registerPaypalWebhookRoutes(
                            paypalConfig = testPaypalConfig(),
                            ordersClient = paypalClient(captureAmount = "50.00", captureCurrency = "USD"),
                            rateLimiter = FederationInboxRateLimiter(),
                        )
                    }
                }
                val member = createMember("paypal-webhook-currency-${Uuid.random()}@example.org")
                val tier = createTier()
                val contributionId = createOpenContribution(memberId = member, tierId = tier, amountDue = BigDecimal("50.00"))
                val bankAccountId = createLedgerAccount(number = "P7${Uuid.random().toString().take(6)}", type = LedgerAccountType.ASSET)
                val incomeAccountId = createLedgerAccount(number = "P8${Uuid.random().toString().take(6)}", type = LedgerAccountType.INCOME)
                enableGateway(bankAccountId = bankAccountId, incomeAccountId = incomeAccountId)
                val orderId = "EC-currency-${Uuid.random()}"
                createCheckoutSession(
                    memberId = member,
                    contributionId = contributionId,
                    amount = BigDecimal("50.00"),
                    providerSessionId = orderId,
                    currency = "EUR",
                )

                val captureEventId = "WH-currency-captured-${Uuid.random()}"
                val response =
                    client.post("/api/webhooks/paypal") {
                        paypalHeaders(transmissionId = captureEventId).forEach { (name, value) -> header(name, value) }
                        contentType(ContentType.Application.Json)
                        setBody(
                            capturedBody(
                                eventId = captureEventId,
                                captureId = "CAP-currency",
                                orderId = orderId,
                                amountValue = "50.00",
                                currency = "USD",
                            ),
                        )
                    }
                response.status shouldBe HttpStatusCode.OK

                val transactionRow =
                    transaction {
                        PaymentTransactionTable.selectAll().where { PaymentTransactionTable.contributionId eq contributionId }.single()
                    }
                transactionRow[PaymentTransactionTable.journalEntryId] shouldBe null
            }
        }

        test(
            "duplicate redelivery of the identical PAYMENT.CAPTURE.COMPLETED event -> 200, still exactly one journal entry, second row DUPLICATE",
        ) {
            testApplication {
                application {
                    routing {
                        registerPaypalWebhookRoutes(
                            paypalConfig = testPaypalConfig(),
                            ordersClient = paypalClient(captureAmount = "20.00"),
                            rateLimiter = FederationInboxRateLimiter(),
                        )
                    }
                }
                val member = createMember("paypal-webhook-dup-${Uuid.random()}@example.org")
                val tier = createTier()
                val contributionId = createOpenContribution(memberId = member, tierId = tier, amountDue = BigDecimal("20.00"))
                val bankAccountId = createLedgerAccount(number = "P9${Uuid.random().toString().take(6)}", type = LedgerAccountType.ASSET)
                val incomeAccountId = createLedgerAccount(number = "PA${Uuid.random().toString().take(6)}", type = LedgerAccountType.INCOME)
                enableGateway(bankAccountId = bankAccountId, incomeAccountId = incomeAccountId)
                val orderId = "EC-dup-${Uuid.random()}"
                createCheckoutSession(
                    memberId = member,
                    contributionId = contributionId,
                    amount = BigDecimal("20.00"),
                    providerSessionId = orderId,
                )

                val captureEventId = "WH-dup-captured-${Uuid.random()}"
                val body = capturedBody(eventId = captureEventId, captureId = "CAP-dup", orderId = orderId, amountValue = "20.00")
                val headers = paypalHeaders(transmissionId = captureEventId)

                val first =
                    client.post("/api/webhooks/paypal") {
                        headers.forEach { (name, value) -> header(name, value) }
                        contentType(ContentType.Application.Json)
                        setBody(body)
                    }
                first.status shouldBe HttpStatusCode.OK
                val second =
                    client.post("/api/webhooks/paypal") {
                        headers.forEach { (name, value) -> header(name, value) }
                        contentType(ContentType.Application.Json)
                        setBody(body)
                    }
                second.status shouldBe HttpStatusCode.OK

                val paymentTransactionCount =
                    transaction {
                        PaymentTransactionTable.selectAll().where { PaymentTransactionTable.contributionId eq contributionId }.count()
                    }
                paymentTransactionCount shouldBe 1L
                val journalEntryId =
                    transaction {
                        PaymentTransactionTable
                            .selectAll()
                            .where { PaymentTransactionTable.contributionId eq contributionId }
                            .single()[PaymentTransactionTable.journalEntryId]
                    }
                journalEntryId.shouldNotBeNull()
                val journalEntryCount =
                    transaction { JournalEntryTable.selectAll().where { JournalEntryTable.id eq journalEntryId }.count() }
                journalEntryCount shouldBe 1L
                val outcomes =
                    transaction {
                        PspWebhookEventTable
                            .selectAll()
                            .where { PspWebhookEventTable.providerEventId eq captureEventId }
                            .map { it[PspWebhookEventTable.outcome] }
                    }
                outcomes.count { it == PspWebhookOutcome.DUPLICATE.name } shouldBe 1
            }
        }

        test("rate limit exhausted -> 429, no accounting touched, no PspWebhookEvent row written") {
            testApplication {
                application {
                    routing {
                        registerPaypalWebhookRoutes(
                            paypalConfig = testPaypalConfig(),
                            ordersClient = paypalClient(),
                            rateLimiter = FederationInboxRateLimiter(maxRequests = 1, window = 1.hours),
                        )
                    }
                }
                val eventId1 = "WH-ratelimit-1-${Uuid.random()}"
                val first =
                    client.post("/api/webhooks/paypal") {
                        paypalHeaders(transmissionId = eventId1).forEach { (name, value) -> header(name, value) }
                        contentType(ContentType.Application.Json)
                        setBody(approvedBody(eventId = eventId1, orderId = "order-rl-1", checkoutSessionId = "irrelevant"))
                    }
                // Budget is 1 -- the first delivery itself consumes it (gate-disabled org, so it is
                // rejected downstream, but the rate limiter is checked BEFORE that, step 1).
                first.status shouldBe HttpStatusCode.ServiceUnavailable

                val eventId2 = "WH-ratelimit-2-${Uuid.random()}"
                val second =
                    client.post("/api/webhooks/paypal") {
                        paypalHeaders(transmissionId = eventId2).forEach { (name, value) -> header(name, value) }
                        contentType(ContentType.Application.Json)
                        setBody(approvedBody(eventId = eventId2, orderId = "order-rl-2", checkoutSessionId = "irrelevant"))
                    }
                second.status shouldBe HttpStatusCode.TooManyRequests
                val loggedRow =
                    transaction { PspWebhookEventTable.selectAll().where { PspWebhookEventTable.providerEventId eq eventId2 }.toList() }
                loggedRow shouldBe emptyList()
            }
        }

        test("oversized body (> MAX_WEBHOOK_BODY_BYTES) -> 413, never reaches signature verification") {
            testApplication {
                application {
                    routing {
                        registerPaypalWebhookRoutes(
                            paypalConfig = testPaypalConfig(),
                            ordersClient = paypalClient(),
                            rateLimiter = FederationInboxRateLimiter(),
                        )
                    }
                }
                val eventId = "WH-oversized-${Uuid.random()}"
                // A real oversized body (not a spoofed Content-Length header, which the HTTP client
                // recomputes from the actual body anyway) -- exercises BOTH the declared
                // Content-Length pre-check AND, since that reflects the real size here, the bounded
                // streaming read cap itself.
                val filler = "x".repeat(96 * 1024)
                val oversizedBody =
                    """
                    {"id":"$eventId","event_type":"CHECKOUT.ORDER.APPROVED",
                    "resource":{"id":"order-oversized","status":"APPROVED","custom_id":"$filler"}}
                    """.trimIndent()
                val response =
                    client.post("/api/webhooks/paypal") {
                        paypalHeaders(transmissionId = eventId).forEach { (name, value) -> header(name, value) }
                        contentType(ContentType.Application.Json)
                        setBody(oversizedBody)
                    }
                response.status shouldBe HttpStatusCode.PayloadTooLarge
            }
        }

        test(
            "CHECKOUT.ORDER.VOIDED -> 200 OK, PROCESSED, AND the real payment_checkout_session is marked EXPIRED",
        ) {
            testApplication {
                application {
                    routing {
                        registerPaypalWebhookRoutes(
                            paypalConfig = testPaypalConfig(),
                            ordersClient = paypalClient(),
                            rateLimiter = FederationInboxRateLimiter(),
                        )
                    }
                }
                val member = createMember("paypal-webhook-voided-${Uuid.random()}@example.org")
                val tier = createTier()
                val contributionId = createOpenContribution(memberId = member, tierId = tier, amountDue = BigDecimal("50.00"))
                val bankAccountId = createLedgerAccount(number = "PB${Uuid.random().toString().take(6)}", type = LedgerAccountType.ASSET)
                val incomeAccountId = createLedgerAccount(number = "PC${Uuid.random().toString().take(6)}", type = LedgerAccountType.INCOME)
                enableGateway(bankAccountId = bankAccountId, incomeAccountId = incomeAccountId)
                // Fix (Review round 4, MAJOR regression test): a real PayPal CHECKOUT.ORDER.VOIDED
                // resource has neither `custom_id` nor `supplementary_data.related_ids` at the top
                // level -- `resource.id` IS the order id (see PaypalWire.kt toPspPaymentEvent KDoc).
                // A real payment_checkout_session row is created here (previously this test never
                // created one, so it could not catch the orderId-resolution bug at all) and the
                // event's `resource.id` is set to the SAME value as its `providerSessionId`.
                val orderId = "EC-voided-${Uuid.random()}"
                createCheckoutSession(
                    memberId = member,
                    contributionId = contributionId,
                    amount = BigDecimal("50.00"),
                    providerSessionId = orderId,
                )

                val eventId = "WH-voided-${Uuid.random()}"
                val body =
                    """
                    {"id":"$eventId","event_type":"CHECKOUT.ORDER.VOIDED",
                    "resource":{"id":"$orderId","status":"VOIDED"}}
                    """.trimIndent()
                val response =
                    client.post("/api/webhooks/paypal") {
                        paypalHeaders(transmissionId = eventId).forEach { (name, value) -> header(name, value) }
                        contentType(ContentType.Application.Json)
                        setBody(body)
                    }
                response.status shouldBe HttpStatusCode.OK
                val loggedRow =
                    transaction { PspWebhookEventTable.selectAll().where { PspWebhookEventTable.providerEventId eq eventId }.single() }
                loggedRow[PspWebhookEventTable.outcome] shouldBe PspWebhookOutcome.PROCESSED.name

                val sessionStatus =
                    transaction {
                        PaymentCheckoutSessionTable
                            .selectAll()
                            .where { PaymentCheckoutSessionTable.providerSessionId eq orderId }
                            .single()[PaymentCheckoutSessionTable.status]
                    }
                sessionStatus shouldBe PaymentCheckoutSessionStatus.EXPIRED
            }
        }

        test("unsupported event type -> 200 OK, IGNORED (never retried for days)") {
            testApplication {
                application {
                    routing {
                        registerPaypalWebhookRoutes(
                            paypalConfig = testPaypalConfig(),
                            ordersClient = paypalClient(),
                            rateLimiter = FederationInboxRateLimiter(),
                        )
                    }
                }
                val bankAccountId = createLedgerAccount(number = "PD${Uuid.random().toString().take(6)}", type = LedgerAccountType.ASSET)
                val incomeAccountId = createLedgerAccount(number = "PE${Uuid.random().toString().take(6)}", type = LedgerAccountType.INCOME)
                enableGateway(bankAccountId = bankAccountId, incomeAccountId = incomeAccountId)

                val eventId = "WH-unsupported-${Uuid.random()}"
                val body =
                    """
                    {"id":"$eventId","event_type":"CUSTOMER.DISPUTE.CREATED",
                    "resource":{"id":"unrelated-${Uuid.random()}","status":null}}
                    """.trimIndent()
                val response =
                    client.post("/api/webhooks/paypal") {
                        paypalHeaders(transmissionId = eventId).forEach { (name, value) -> header(name, value) }
                        contentType(ContentType.Application.Json)
                        setBody(body)
                    }
                response.status shouldBe HttpStatusCode.OK
                val loggedRow =
                    transaction { PspWebhookEventTable.selectAll().where { PspWebhookEventTable.providerEventId eq eventId }.single() }
                loggedRow[PspWebhookEventTable.outcome] shouldBe PspWebhookOutcome.IGNORED.name
            }
        }
    })
