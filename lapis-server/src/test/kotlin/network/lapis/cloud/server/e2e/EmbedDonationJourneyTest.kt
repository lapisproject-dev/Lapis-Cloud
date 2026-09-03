package network.lapis.cloud.server.e2e

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
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
import kotlinx.datetime.LocalDate
import network.lapis.cloud.server.db.DatabaseConfig
import network.lapis.cloud.server.db.DbClock
import network.lapis.cloud.server.db.DevSeedData
import network.lapis.cloud.server.db.generated.AuditLogEntryTable
import network.lapis.cloud.server.db.generated.ExternalDonorTable
import network.lapis.cloud.server.db.generated.JournalEntryTable
import network.lapis.cloud.server.db.generated.LedgerAccountTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.server.db.generated.OrganizationSettingsTable
import network.lapis.cloud.server.db.generated.PaymentCheckoutSessionTable
import network.lapis.cloud.server.db.generated.PaymentGatewayComplianceAcknowledgmentTable
import network.lapis.cloud.server.db.generated.PaymentTransactionTable
import network.lapis.cloud.server.db.generated.PostingTable
import network.lapis.cloud.server.db.generated.PspWebhookEventTable
import network.lapis.cloud.server.embed.EmbedConfig
import network.lapis.cloud.server.embed.EmbedOriginAllowlist
import network.lapis.cloud.server.federation.FederationInboxRateLimiter
import network.lapis.cloud.server.module
import network.lapis.cloud.server.payment.psp.PspConfig
import network.lapis.cloud.server.payment.psp.PspConfigState
import network.lapis.cloud.server.payment.psp.StripeCheckoutClient
import network.lapis.cloud.server.routes.registerEmbedDonationRoutes
import network.lapis.cloud.server.routes.registerPspWebhookRoutes
import network.lapis.cloud.shared.domain.AuditEntityType
import network.lapis.cloud.shared.domain.DonorCategory
import network.lapis.cloud.shared.domain.LedgerAccountType
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.domain.PaymentProvider
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.uuid.Uuid

private const val E2E_EMBED_WEBHOOK_SECRET = "whsec_embed_donation_journey_e2e"

/** Same HMAC idiom [GatewayPaymentJourneyTest]/`PspWebhookRoutesTest` establish, duplicated per that precedent. */
private fun e2eEmbedHmacHex(
    secret: String,
    data: ByteArray,
): String {
    val mac = Mac.getInstance("HmacSHA256")
    mac.init(SecretKeySpec(secret.toByteArray(Charsets.UTF_8), "HmacSHA256"))
    return mac.doFinal(data).joinToString(separator = "") { byte -> "%02x".format(byte) }
}

private fun e2eEmbedSignedHeader(body: ByteArray): String {
    val timestamp = Clock.System.now().epochSeconds
    val signedPayload = "$timestamp.".toByteArray(Charsets.UTF_8) + body
    return "t=$timestamp,v1=${e2eEmbedHmacHex(secret = E2E_EMBED_WEBHOOK_SECRET, data = signedPayload)}"
}

/**
 * Welle V1.4.1b "Öffentliche Website-Integration -- anonymer Spenden-Pfad" -- end-to-end journey
 * through the REAL [module] plus [registerEmbedDonationRoutes]/[registerPspWebhookRoutes] mounted
 * under isolated `/e2e-embed`/`/e2e-embed-psp` path prefixes (same layering technique
 * [GatewayPaymentJourneyTest] uses for `PaymentGatewayService`/`registerPspWebhookRoutes`, since the
 * real `module()` reads [network.lapis.cloud.server.embed.EmbedConfig] from unset environment
 * variables in this sandbox -> `enabled = false`, so the production route registration never runs):
 * an anonymous donor's real HTTP checkout call, followed by a REAL, correctly-signed
 * `checkout.session.completed` webhook delivery through the SAME [registerPspWebhookRoutes] handler
 * production uses -- proving the whole chain from "a stranger clicks the donate widget" through
 * "money is posted to the general ledger with a named responsible bookkeeping actor", without
 * mocking anything server-side except the one genuinely external call this codebase ever makes
 * (Stripe's own `POST /v1/checkout/sessions`), via a [MockEngine]-backed [StripeCheckoutClient].
 *
 * **§25-PartG-before-Stripe scenario, adapted (see also `AnonymousDonationCheckout.kt` KDoc "defense-
 * in-depth")**: for [DonorCategory.ANONYMOUS], [network.lapis.cloud.server.rpc
 * .PartyDonationComplianceCalculator.check] can NEVER return `PROHIBITED` -- only the four
 * structural categories and [DonorCategory.NON_EU_FOREIGN_NATURAL_PERSON]'s aggregate cap can, and
 * neither ever applies to an anonymous donor. A donation that WOULD trigger
 * [network.lapis.cloud.shared.domain.DonationDuty.ANONYMOUS_FORWARDING_REQUIRED] is likewise
 * structurally unreachable through this HTTP path, because [EmbedDonationLimits.MAX_AMOUNT_EUR]
 * equals `ANONYMOUS_FORWARDING_THRESHOLD_EUR` exactly, and the amount-range check (step 3) rejects
 * anything above it BEFORE the §25 check (step 4) ever runs. This test therefore verifies the
 * REACHABLE half of that guarantee instead: a political-party organization accepts a normal, in-
 * range anonymous donation (the §25 gate runs and does not block it) and the Stripe call still
 * happens exactly once -- proving the gate is wired into the real HTTP path without asserting an
 * outcome the domain rules make impossible to construct.
 */
class EmbedDonationJourneyTest :
    FunSpec({
        val createdMemberIds = mutableListOf<Uuid>()
        val createdLedgerAccountIds = mutableListOf<Uuid>()
        val createdExternalDonorIds = mutableListOf<Uuid>()
        val createdCheckoutSessionIds = mutableListOf<Uuid>()

        beforeSpec {
            DatabaseConfig.connect()
            DevSeedData.seedIfEmpty(force = true)
        }

        afterSpec {
            transaction {
                OrganizationSettingsTable.update(
                    { OrganizationSettingsTable.id eq network.lapis.cloud.server.rpc.ORGANIZATION_SETTINGS_ID },
                ) {
                    it[paymentGatewayEnabled] = false
                    it[paymentGatewayProvider] = null
                    it[paymentBankAccountId] = null
                    it[donationIncomeAccountId] = null
                    it[isPoliticalParty] = false
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
                if (createdExternalDonorIds.isNotEmpty()) {
                    ExternalDonorTable.deleteWhere { ExternalDonorTable.id inList createdExternalDonorIds }
                }
                if (createdLedgerAccountIds.isNotEmpty()) {
                    LedgerAccountTable.deleteWhere { LedgerAccountTable.id inList createdLedgerAccountIds }
                }
                if (createdMemberIds.isNotEmpty()) {
                    AuditLogEntryTable.update({ AuditLogEntryTable.actorMemberId inList createdMemberIds }) {
                        it[actorMemberId] = null
                    }
                    PaymentGatewayComplianceAcknowledgmentTable.deleteWhere {
                        PaymentGatewayComplianceAcknowledgmentTable.acknowledgedByMemberId inList createdMemberIds
                    }
                    MemberTable.deleteWhere { MemberTable.id inList createdMemberIds }
                }
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
                    it[name] = "E2E Embed-Spenden-Testkonto $number"
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
            isPoliticalParty: Boolean = false,
        ) {
            transaction {
                OrganizationSettingsTable.update(
                    { OrganizationSettingsTable.id eq network.lapis.cloud.server.rpc.ORGANIZATION_SETTINGS_ID },
                ) {
                    it[paymentGatewayEnabled] = true
                    it[paymentGatewayProvider] = PaymentProvider.STRIPE
                    it[paymentBankAccountId] = bankAccountId
                    it[OrganizationSettingsTable.donationIncomeAccountId] = donationIncomeAccountId
                    it[OrganizationSettingsTable.isPoliticalParty] = isPoliticalParty
                }
                PaymentGatewayComplianceAcknowledgmentTable.insert {
                    it[id] = Uuid.random()
                    it[PaymentGatewayComplianceAcknowledgmentTable.acknowledgedByMemberId] = acknowledgedByMemberId
                    it[acknowledgedAt] = DbClock.nowLocalDateTime()
                    it[disclaimerVersion] = network.lapis.cloud.server.rpc.PaymentGatewayComplianceDisclaimer.VERSION
                    it[disclaimerSha256] = network.lapis.cloud.server.rpc.PaymentGatewayComplianceDisclaimer.SHA256
                    it[provider] = PaymentProvider.STRIPE
                }
            }
        }

        fun createAdmin(): Uuid {
            val id = Uuid.random()
            transaction {
                MemberTable.insert {
                    it[MemberTable.id] = id
                    it[displayName] = "E2E Embed Donation Admin"
                    it[email] = "e2e-embed-donation-admin-${Uuid.random()}@example.org"
                    it[status] = MemberStatus.ACTIVE
                    it[joinedAt] = LocalDate(2026, 1, 1)
                    it[membershipTierId] = null
                }
            }
            createdMemberIds += id
            return id
        }

        fun e2eConfig(): PspConfigState.Configured =
            requireNotNull(
                PspConfig.load {
                    when (it) {
                        PspConfig.ENV_SECRET_KEY -> "sk_test_embed_donation_journey"
                        PspConfig.ENV_WEBHOOK_SIGNING_SECRET -> E2E_EMBED_WEBHOOK_SECRET
                        else -> null
                    }
                } as? PspConfigState.Configured,
            )

        val embedTestConfig =
            EmbedConfig(
                enabled = true,
                allowlist = EmbedOriginAllowlist.parse(raw = "https://partei.example", allowInsecure = false).allowlist,
                allowInsecureOrigins = false,
            )

        test(
            "anonymous checkout via the real HTTP endpoint -> external_donor + payment_checkout_session, " +
                "a real signed webhook posts a balanced journal_entry with created_by = the disclaimer " +
                "acknowledger, PAYMENT_TRANSACTION/JOURNAL_ENTRY audit entries exist, no email is stored anywhere",
        ) {
            testApplication {
                val stripeSessionId = "cs_e2e_embed_donation_${Uuid.random()}"
                val stripeCallCount = intArrayOf(0)
                var capturedRequestBody = ""
                val mockStripeClient =
                    HttpClient(
                        MockEngine { request ->
                            stripeCallCount[0]++
                            capturedRequestBody = (request.body as io.ktor.content.TextContent).text
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
                        route("/e2e-embed") {
                            registerEmbedDonationRoutes(
                                config = embedTestConfig,
                                pspConfigState = config,
                                checkoutClient = checkoutClient,
                                donationCheckoutRateLimiter = FederationInboxRateLimiter(maxRequests = 100, window = 1.hours),
                                donationCheckoutAttemptRateLimiter = FederationInboxRateLimiter(maxRequests = 100, window = 1.hours),
                                donationPageRateLimiter = FederationInboxRateLimiter(maxRequests = 100, window = 1.minutes),
                                baseUrl = "https://lapis.example",
                                brandTitle = "E2E-Verein",
                            )
                        }
                        route("/e2e-embed-psp") {
                            registerPspWebhookRoutes(pspConfig = config, rateLimiter = FederationInboxRateLimiter())
                        }
                        post("/e2e-embed/list-checkout-sessions") {
                            val rows =
                                transaction {
                                    PaymentCheckoutSessionTable
                                        .selectAll()
                                        .where { PaymentCheckoutSessionTable.providerSessionId eq stripeSessionId }
                                        .map { it[PaymentCheckoutSessionTable.id].toString() }
                                }
                            call.respondText(rows.joinToString(","))
                        }
                    }
                }

                val bankAccountId = createLedgerAccount(number = "F1${Uuid.random().toString().take(6)}", type = LedgerAccountType.ASSET)
                val incomeAccountId = createLedgerAccount(number = "F2${Uuid.random().toString().take(6)}", type = LedgerAccountType.INCOME)
                val acknowledgerId = createAdmin()
                enableGatewayFor(
                    bankAccountId = bankAccountId,
                    donationIncomeAccountId = incomeAccountId,
                    acknowledgedByMemberId = acknowledgerId,
                )

                // ── Step 1: the anonymous donor's real HTTP checkout call. ──────────────────────
                val checkoutResponse =
                    client.post("/e2e-embed/api/embed/v1/donation/checkout") {
                        header(HttpHeaders.Origin, "https://partei.example")
                        contentType(ContentType.Application.Json)
                        setBody("""{"amount":"42.00"}""")
                    }
                checkoutResponse.status shouldBe HttpStatusCode.OK
                checkoutResponse.bodyAsText().contains("checkout.stripe.com") shouldBe true
                stripeCallCount[0] shouldBe 1

                val sessionIdsRaw =
                    client
                        .post("/e2e-embed/list-checkout-sessions")
                        .bodyAsText()
                        .split(",")
                        .filter { it.isNotBlank() }
                sessionIdsRaw shouldHaveSize 1
                val checkoutSessionId = Uuid.parse(sessionIdsRaw[0])
                createdCheckoutSessionIds += checkoutSessionId

                val sessionRow =
                    transaction {
                        PaymentCheckoutSessionTable
                            .selectAll()
                            .where { PaymentCheckoutSessionTable.id eq checkoutSessionId }
                            .single()
                    }
                sessionRow[PaymentCheckoutSessionTable.memberId] shouldBe null
                sessionRow[PaymentCheckoutSessionTable.embedOrigin] shouldBe "https://partei.example"
                sessionRow[PaymentCheckoutSessionTable.donorCategory] shouldBe DonorCategory.ANONYMOUS
                val externalDonorId = sessionRow[PaymentCheckoutSessionTable.externalDonorId]
                externalDonorId.shouldNotBeNull()
                createdExternalDonorIds += externalDonorId

                // Fix (Review MAJOR #1) test coverage: PENDING immediately after checkout, BEFORE
                // any webhook confirms the money arrived -- must never surface as a real donor yet.
                transaction {
                    ExternalDonorTable.selectAll().where { ExternalDonorTable.id eq externalDonorId }.single()[
                        ExternalDonorTable.active,
                    ]
                } shouldBe false

                // ── Step 2: a real, correctly-signed checkout.session.completed webhook, through ──
                // ── the REAL registerPspWebhookRoutes handler. ────────────────────────────────────
                val eventId = "evt_e2e_embed_donation_${Uuid.random()}"
                val body =
                    """
                    {"id":"$eventId","type":"checkout.session.completed","data":{"object":{"id":"$stripeSessionId",
                    "payment_intent":"pi_${eventId}_intent","amount_total":4200,"currency":"eur"}}}
                    """.trimIndent().toByteArray(Charsets.UTF_8)
                val webhookResponse =
                    client.post("/e2e-embed-psp/api/webhooks/stripe") {
                        header("Stripe-Signature", e2eEmbedSignedHeader(body = body))
                        contentType(ContentType.Application.Json)
                        setBody(body)
                    }
                webhookResponse.status shouldBe HttpStatusCode.OK

                // ── Step 3: ground truth -- payment_transaction (member_id NULL) + balanced ──────
                // ── journal_entry (external_donor_id set, donor_member_id NULL, created_by = the ──
                // ── disclaimer acknowledger), audit entries, no rollback, no email anywhere. ──────
                val transactionRow =
                    transaction {
                        PaymentTransactionTable
                            .selectAll()
                            .where { PaymentTransactionTable.checkoutSessionId eq checkoutSessionId }
                            .single()
                    }
                transactionRow[PaymentTransactionTable.memberId] shouldBe null
                val journalEntryId = transactionRow[PaymentTransactionTable.journalEntryId]
                journalEntryId.shouldNotBeNull()

                val journalEntryRow =
                    transaction { JournalEntryTable.selectAll().where { JournalEntryTable.id eq journalEntryId }.single() }
                journalEntryRow[JournalEntryTable.externalDonorId] shouldBe externalDonorId
                journalEntryRow[JournalEntryTable.donorMemberId] shouldBe null
                journalEntryRow[JournalEntryTable.createdBy] shouldBe acknowledgerId

                // Fix (Review MAJOR #1) test coverage: the webhook confirming the money arrived
                // promotes the donor from PENDING to real/active -- now visible in the default
                // activeOnly=true donor picker (LedgerScreen/DonorsScreen).
                transaction {
                    ExternalDonorTable.selectAll().where { ExternalDonorTable.id eq externalDonorId }.single()[
                        ExternalDonorTable.active,
                    ]
                } shouldBe true

                val postingCount = transaction { PostingTable.selectAll().where { PostingTable.journalEntryId eq journalEntryId }.count() }
                postingCount shouldBe 2L

                val paymentAudit =
                    transaction {
                        AuditLogEntryTable
                            .selectAll()
                            .where {
                                (AuditLogEntryTable.entityType eq AuditEntityType.PAYMENT_TRANSACTION) and
                                    (AuditLogEntryTable.entityId eq transactionRow[PaymentTransactionTable.id])
                            }.singleOrNull()
                    }
                paymentAudit.shouldNotBeNull()
                val journalAudit =
                    transaction {
                        AuditLogEntryTable
                            .selectAll()
                            .where {
                                (AuditLogEntryTable.entityType eq AuditEntityType.JOURNAL_ENTRY) and
                                    (AuditLogEntryTable.entityId eq journalEntryId)
                            }.singleOrNull()
                    }
                journalAudit.shouldNotBeNull()

                // No email anywhere: external_donor has no email column at all; a data-level guard
                // that nothing in the two audit snapshot payloads carries an '@' value.
                paymentAudit[AuditLogEntryTable.afterSnapshot].orEmpty().contains("@") shouldBe false
                journalAudit[AuditLogEntryTable.afterSnapshot].orEmpty().contains("@") shouldBe false

                // ── Return URLs: the request Stripe actually received points at the embed return ──
                // ── pages, NOT /#/payment-return, and carries no session identifier. ─────────────
                capturedRequestBody.contains("success_url=https%3A%2F%2Flapis.example%2Fembed%2Fv1%2Fspende%2Fdanke") shouldBe true
                capturedRequestBody.contains("payment-return") shouldBe false
                capturedRequestBody.contains("session%3D") shouldBe false
            }
        }

        test("honeypot filled -> 200, redirects to the cancelled page, zero new rows, Stripe never called") {
            testApplication {
                var stripeCallCount = 0
                val mockStripeClient =
                    HttpClient(
                        MockEngine { _ ->
                            stripeCallCount++
                            respond("{}", HttpStatusCode.OK)
                        },
                    )
                val config = e2eConfig()
                val checkoutClient = StripeCheckoutClient(pspConfig = config.config, httpClient = mockStripeClient)

                application {
                    module()
                    routing {
                        route("/e2e-embed-hp") {
                            registerEmbedDonationRoutes(
                                config = embedTestConfig,
                                pspConfigState = config,
                                checkoutClient = checkoutClient,
                                donationCheckoutRateLimiter = FederationInboxRateLimiter(maxRequests = 100, window = 1.hours),
                                donationCheckoutAttemptRateLimiter = FederationInboxRateLimiter(maxRequests = 100, window = 1.hours),
                                donationPageRateLimiter = FederationInboxRateLimiter(maxRequests = 100, window = 1.minutes),
                                baseUrl = "https://lapis.example",
                                brandTitle = "E2E-Verein",
                            )
                        }
                    }
                }

                val bankAccountId = createLedgerAccount(number = "F3${Uuid.random().toString().take(6)}", type = LedgerAccountType.ASSET)
                val incomeAccountId = createLedgerAccount(number = "F4${Uuid.random().toString().take(6)}", type = LedgerAccountType.INCOME)
                val acknowledgerId = createAdmin()
                enableGatewayFor(
                    bankAccountId = bankAccountId,
                    donationIncomeAccountId = incomeAccountId,
                    acknowledgedByMemberId = acknowledgerId,
                )

                val donorCountBefore = transaction { ExternalDonorTable.selectAll().count() }
                val sessionCountBefore = transaction { PaymentCheckoutSessionTable.selectAll().count() }

                val response =
                    client.post("/e2e-embed-hp/api/embed/v1/donation/checkout") {
                        header(HttpHeaders.Origin, "https://partei.example")
                        contentType(ContentType.Application.Json)
                        setBody("""{"amount":"25.00","kommentar":"x"}""")
                    }

                response.status shouldBe HttpStatusCode.OK
                response.bodyAsText().contains("/embed/v1/spende/abgebrochen") shouldBe true
                stripeCallCount shouldBe 0
                transaction { ExternalDonorTable.selectAll().count() } shouldBe donorCountBefore
                transaction { PaymentCheckoutSessionTable.selectAll().count() } shouldBe sessionCountBefore
            }
        }

        test(
            "Fix (Review MINOR #6a): Stripe itself rejects the checkout call -> 502 GATEWAY_ERROR, " +
                "Stripe's own error text never reaches the response body, zero new rows",
        ) {
            testApplication {
                var stripeCallCount = 0
                val mockStripeClient =
                    HttpClient(
                        MockEngine { _ ->
                            stripeCallCount++
                            respond(
                                """{"error":{"message":"Your card number is incorrect. -- internal Stripe detail"}}""",
                                HttpStatusCode.BadRequest,
                                headersOf(HttpHeaders.ContentType, "application/json"),
                            )
                        },
                    )
                val config = e2eConfig()
                val checkoutClient = StripeCheckoutClient(pspConfig = config.config, httpClient = mockStripeClient)

                application {
                    module()
                    routing {
                        route("/e2e-embed-stripe-fail") {
                            registerEmbedDonationRoutes(
                                config = embedTestConfig,
                                pspConfigState = config,
                                checkoutClient = checkoutClient,
                                donationCheckoutRateLimiter = FederationInboxRateLimiter(maxRequests = 100, window = 1.hours),
                                donationCheckoutAttemptRateLimiter = FederationInboxRateLimiter(maxRequests = 100, window = 1.hours),
                                donationPageRateLimiter = FederationInboxRateLimiter(maxRequests = 100, window = 1.minutes),
                                baseUrl = "https://lapis.example",
                                brandTitle = "E2E-Verein",
                            )
                        }
                    }
                }

                val bankAccountId = createLedgerAccount(number = "F7${Uuid.random().toString().take(6)}", type = LedgerAccountType.ASSET)
                val incomeAccountId = createLedgerAccount(number = "F8${Uuid.random().toString().take(6)}", type = LedgerAccountType.INCOME)
                val acknowledgerId = createAdmin()
                enableGatewayFor(
                    bankAccountId = bankAccountId,
                    donationIncomeAccountId = incomeAccountId,
                    acknowledgedByMemberId = acknowledgerId,
                )

                val donorCountBefore = transaction { ExternalDonorTable.selectAll().count() }
                val sessionCountBefore = transaction { PaymentCheckoutSessionTable.selectAll().count() }

                val response =
                    client.post("/e2e-embed-stripe-fail/api/embed/v1/donation/checkout") {
                        header(HttpHeaders.Origin, "https://partei.example")
                        contentType(ContentType.Application.Json)
                        setBody("""{"amount":"25.00"}""")
                    }

                response.status shouldBe HttpStatusCode.BadGateway
                response.bodyAsText() shouldBe """{"error":"GATEWAY_ERROR"}"""
                response.bodyAsText().contains("card number") shouldBe false
                stripeCallCount shouldBe 1
                transaction { ExternalDonorTable.selectAll().count() } shouldBe donorCountBefore
                transaction { PaymentCheckoutSessionTable.selectAll().count() } shouldBe sessionCountBefore
            }
        }

        test(
            "political-party organization accepts an in-range anonymous donation -- the §25 gate runs and does not block it, Stripe is called exactly once",
        ) {
            testApplication {
                val stripeSessionId = "cs_e2e_embed_party_${Uuid.random()}"
                var stripeCallCount = 0
                val mockStripeClient =
                    HttpClient(
                        MockEngine { _ ->
                            stripeCallCount++
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
                        route("/e2e-embed-party") {
                            registerEmbedDonationRoutes(
                                config = embedTestConfig,
                                pspConfigState = config,
                                checkoutClient = checkoutClient,
                                donationCheckoutRateLimiter = FederationInboxRateLimiter(maxRequests = 100, window = 1.hours),
                                donationCheckoutAttemptRateLimiter = FederationInboxRateLimiter(maxRequests = 100, window = 1.hours),
                                donationPageRateLimiter = FederationInboxRateLimiter(maxRequests = 100, window = 1.minutes),
                                baseUrl = "https://lapis.example",
                                brandTitle = "E2E-Partei",
                            )
                        }
                    }
                }

                val bankAccountId = createLedgerAccount(number = "F5${Uuid.random().toString().take(6)}", type = LedgerAccountType.ASSET)
                val incomeAccountId = createLedgerAccount(number = "F6${Uuid.random().toString().take(6)}", type = LedgerAccountType.INCOME)
                val acknowledgerId = createAdmin()
                enableGatewayFor(
                    bankAccountId = bankAccountId,
                    donationIncomeAccountId = incomeAccountId,
                    acknowledgedByMemberId = acknowledgerId,
                    isPoliticalParty = true,
                )

                val response =
                    client.post("/e2e-embed-party/api/embed/v1/donation/checkout") {
                        header(HttpHeaders.Origin, "https://partei.example")
                        contentType(ContentType.Application.Json)
                        setBody("""{"amount":"500.00"}""")
                    }

                response.status shouldBe HttpStatusCode.OK
                stripeCallCount shouldBe 1

                val sessionId =
                    transaction {
                        PaymentCheckoutSessionTable
                            .selectAll()
                            .where { PaymentCheckoutSessionTable.providerSessionId eq stripeSessionId }
                            .single()[
                            PaymentCheckoutSessionTable.id,
                        ]
                    }
                createdCheckoutSessionIds += sessionId
                val externalDonorId =
                    transaction { PaymentCheckoutSessionTable.selectAll().where { PaymentCheckoutSessionTable.id eq sessionId }.single() }[
                        PaymentCheckoutSessionTable.externalDonorId,
                    ]
                if (externalDonorId != null) createdExternalDonorIds += externalDonorId
            }
        }
    })
