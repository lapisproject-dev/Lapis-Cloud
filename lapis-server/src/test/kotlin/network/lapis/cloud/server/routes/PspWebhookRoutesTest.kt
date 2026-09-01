package network.lapis.cloud.server.routes

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
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
import network.lapis.cloud.server.payment.psp.PspConfig
import network.lapis.cloud.server.payment.psp.PspConfigState
import network.lapis.cloud.server.rpc.ORGANIZATION_SETTINGS_ID
import network.lapis.cloud.server.rpc.PaymentGatewayComplianceDisclaimer
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.BillingInterval
import network.lapis.cloud.shared.domain.ContributionPaymentMethod
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
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlin.uuid.Uuid

private const val TEST_WEBHOOK_SECRET = "whsec_psp_webhook_routes_test"

private fun hmacHex(
    secret: String,
    data: ByteArray,
): String {
    val mac = Mac.getInstance("HmacSHA256")
    mac.init(SecretKeySpec(secret.toByteArray(Charsets.UTF_8), "HmacSHA256"))
    return mac.doFinal(data).joinToString(separator = "") { byte -> "%02x".format(byte) }
}

private fun signedHeader(
    body: ByteArray,
    timestampEpochSeconds: Long = Clock.System.now().epochSeconds,
    secret: String = TEST_WEBHOOK_SECRET,
): String {
    val signedPayload = "$timestampEpochSeconds.".toByteArray(Charsets.UTF_8) + body
    return "t=$timestampEpochSeconds,v1=${hmacHex(secret = secret, data = signedPayload)}"
}

/**
 * Welle V1.2.8 "PSP-Checkout (Stripe)" (GitHub Issue #6) -- `POST /api/webhooks/stripe` end-to-end
 * over a real `testApplication`, same house style [SepaRoutesTest]/[ContributionPaymentRpcTest]
 * establish for HTTP-route test coverage. Deliberately never calls the real Stripe API -- every
 * fixture body is hand-built and self-signed with [TEST_WEBHOOK_SECRET].
 */
class PspWebhookRoutesTest :
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
                // PspWebhookIngestion writes a PAYMENT_TRANSACTION audit_log_entry row keyed by
                // entityId = payment_transaction.id (NOT journalEntryId) -- nulling actor_member_id
                // for every row this Spec's own members ever wrote (rather than deleting only rows
                // matched by journalEntryIds below) avoids an FK violation on the MemberTable delete
                // at the bottom of this block. Same "null, don't delete, the audit trail is
                // append-only" idiom ContributionPostingBridgeTest's own afterSpec establishes.
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
                    it[displayName] = "PspWebhookRoutes Testmitglied"
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
                    it[name] = "PspWebhookRoutes Konto $number"
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
                    it[name] = "PspWebhookRoutes Tarif ${id.toString().take(6)}"
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
            status: ContributionStatus = ContributionStatus.OPEN,
        ): Uuid {
            val id = Uuid.random()
            transaction {
                ContributionTable.insert {
                    it[ContributionTable.id] = id
                    it[periodStart] = LocalDate(2026, 1, 1)
                    it[periodEnd] = LocalDate(2026, 12, 31)
                    it[ContributionTable.amountDue] = amountDue
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

        fun createCheckoutSession(
            memberId: Uuid,
            contributionId: Uuid?,
            amount: BigDecimal,
            providerSessionId: String,
        ): Uuid {
            val id = Uuid.random()
            transaction {
                PaymentCheckoutSessionTable.insert {
                    it[PaymentCheckoutSessionTable.id] = id
                    it[provider] = PaymentProvider.STRIPE
                    it[PaymentCheckoutSessionTable.providerSessionId] = providerSessionId
                    it[status] = PaymentCheckoutSessionStatus.CREATED
                    it[intent] = if (contributionId != null) PaymentIntent.CONTRIBUTION else PaymentIntent.DONATION
                    it[PaymentCheckoutSessionTable.contributionId] = contributionId
                    it[PaymentCheckoutSessionTable.memberId] = memberId
                    it[PaymentCheckoutSessionTable.amount] = amount
                    it[currency] = "EUR"
                    it[donorCategory] = null
                    it[purpose] = null
                    it[createdAt] = LocalDateTime(2026, 4, 1, 10, 0)
                    it[expiresAt] = LocalDateTime(2026, 4, 1, 11, 0)
                    it[completedAt] = null
                    it[providerIdempotencyKey] = "idem-${id.toString().take(8)}"
                    it[redirectUrl] = "https://checkout.stripe.com/c/pay/$providerSessionId"
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
                    it[paymentGatewayProvider] = PaymentProvider.STRIPE
                    it[paymentBankAccountId] = bankAccountId
                    it[contributionIncomeAccountId] = incomeAccountId
                }
                PaymentGatewayComplianceAcknowledgmentTable.insert {
                    it[id] = Uuid.random()
                    it[acknowledgedByMemberId] = createMember("psp-webhook-ack-${Uuid.random()}@example.org")
                    it[acknowledgedAt] = LocalDateTime(2026, 4, 1, 9, 0)
                    it[disclaimerVersion] = PaymentGatewayComplianceDisclaimer.VERSION
                    it[disclaimerSha256] = PaymentGatewayComplianceDisclaimer.SHA256
                    it[provider] = PaymentProvider.STRIPE
                }
            }
        }

        fun checkoutCompletedBody(
            eventId: String,
            sessionId: String,
            amountTotalMinorUnits: Long,
            currency: String = "eur",
            // Security audit finding (Welle V1.2.8, MINOR/hardening) test coverage -- defaults to
            // omitting payment_status entirely, exactly like every pre-existing test body, so the
            // decode-as-null path stays covered too (see StripeCheckoutSessionObject KDoc).
            paymentStatus: String? = null,
        ): ByteArray {
            val paymentStatusField = paymentStatus?.let { ""","payment_status":"$it"""" } ?: ""
            return """
                {"id":"$eventId","type":"checkout.session.completed","data":{"object":{"id":"$sessionId",
                "payment_intent":"pi_${eventId}_intent","amount_total":$amountTotalMinorUnits,"currency":"$currency"$paymentStatusField}}}
                """.trimIndent().toByteArray(Charsets.UTF_8)
        }

        fun testConfig(): PspConfigState.Configured =
            PspConfigState.Configured(
                config =
                    requireNotNull(
                        (
                            PspConfig.load {
                                when (it) {
                                    PspConfig.ENV_SECRET_KEY -> "sk_test_psp_webhook_routes"
                                    PspConfig.ENV_WEBHOOK_SIGNING_SECRET -> TEST_WEBHOOK_SECRET
                                    else -> null
                                }
                            } as? PspConfigState.Configured
                        )?.config,
                    ),
            )

        test("happy path (contribution): signed checkout.session.completed -> 200, PAID, one balanced journal entry, PROCESSED") {
            testApplication {
                application { routing { registerPspWebhookRoutes(pspConfig = testConfig(), rateLimiter = FederationInboxRateLimiter()) } }

                val member = createMember("psp-webhook-happy-${Uuid.random()}@example.org")
                val tier = createTier()
                val contributionId = createOpenContribution(memberId = member, tierId = tier, amountDue = BigDecimal("50.00"))
                val bankAccountId = createLedgerAccount(number = "W1${Uuid.random().toString().take(6)}", type = LedgerAccountType.ASSET)
                val incomeAccountId = createLedgerAccount(number = "W2${Uuid.random().toString().take(6)}", type = LedgerAccountType.INCOME)
                enableGateway(bankAccountId = bankAccountId, incomeAccountId = incomeAccountId)
                val sessionId = "cs_happy_${Uuid.random()}"
                createCheckoutSession(
                    memberId = member,
                    contributionId = contributionId,
                    amount = BigDecimal("50.00"),
                    providerSessionId = sessionId,
                )

                val body =
                    checkoutCompletedBody(eventId = "evt_happy_${Uuid.random()}", sessionId = sessionId, amountTotalMinorUnits = 5000)
                val response =
                    client.post("/api/webhooks/stripe") {
                        header("Stripe-Signature", signedHeader(body = body))
                        contentType(ContentType.Application.Json)
                        setBody(body)
                    }

                response.status shouldBe HttpStatusCode.OK

                val contributionStatus =
                    transaction {
                        ContributionTable.selectAll().where { ContributionTable.id eq contributionId }.single()[ContributionTable.status]
                    }
                contributionStatus shouldBe ContributionStatus.PAID
                val paymentMethod =
                    transaction {
                        ContributionTable
                            .selectAll()
                            .where {
                                ContributionTable.id eq contributionId
                            }.single()[ContributionTable.paymentMethod]
                    }
                paymentMethod shouldBe ContributionPaymentMethod.GATEWAY

                val transactionRow =
                    transaction {
                        PaymentTransactionTable.selectAll().where { PaymentTransactionTable.contributionId eq contributionId }.single()
                    }
                transactionRow[PaymentTransactionTable.journalEntryId].shouldNotBeNull()

                val postingsBalanced =
                    transaction {
                        val postings =
                            PostingTable
                                .selectAll()
                                .where {
                                    PostingTable.journalEntryId eq
                                        requireNotNull(transactionRow[PaymentTransactionTable.journalEntryId])
                                }.toList()
                        val debit =
                            postings
                                .filter { it[PostingTable.side] == network.lapis.cloud.shared.domain.PostingSide.DEBIT }
                                .fold(BigDecimal.ZERO) { acc, row -> acc + row[PostingTable.amount] }
                        val credit =
                            postings
                                .filter { it[PostingTable.side] == network.lapis.cloud.shared.domain.PostingSide.CREDIT }
                                .fold(BigDecimal.ZERO) { acc, row -> acc + row[PostingTable.amount] }
                        debit.compareTo(credit) == 0
                    }
                postingsBalanced shouldBe true

                val webhookEventOutcome =
                    transaction {
                        PspWebhookEventTable
                            .selectAll()
                            .where { PspWebhookEventTable.paymentTransactionId eq transactionRow[PaymentTransactionTable.id] }
                            .single()[PspWebhookEventTable.outcome]
                    }
                webhookEventOutcome shouldBe network.lapis.cloud.server.payment.psp.PspWebhookOutcome.PROCESSED.name
            }
        }

        test("duplicate redelivery of the identical signed body -> 200, still exactly one journal entry, second row DUPLICATE") {
            testApplication {
                application { routing { registerPspWebhookRoutes(pspConfig = testConfig(), rateLimiter = FederationInboxRateLimiter()) } }

                val member = createMember("psp-webhook-dup-${Uuid.random()}@example.org")
                val tier = createTier()
                val contributionId = createOpenContribution(memberId = member, tierId = tier, amountDue = BigDecimal("20.00"))
                val bankAccountId = createLedgerAccount(number = "W3${Uuid.random().toString().take(6)}", type = LedgerAccountType.ASSET)
                val incomeAccountId = createLedgerAccount(number = "W4${Uuid.random().toString().take(6)}", type = LedgerAccountType.INCOME)
                enableGateway(bankAccountId = bankAccountId, incomeAccountId = incomeAccountId)
                val sessionId = "cs_dup_${Uuid.random()}"
                createCheckoutSession(
                    memberId = member,
                    contributionId = contributionId,
                    amount = BigDecimal("20.00"),
                    providerSessionId = sessionId,
                )

                val eventId = "evt_dup_${Uuid.random()}"
                val body = checkoutCompletedBody(eventId = eventId, sessionId = sessionId, amountTotalMinorUnits = 2000)
                val header = signedHeader(body = body)

                val first =
                    client.post("/api/webhooks/stripe") {
                        header("Stripe-Signature", header)
                        contentType(ContentType.Application.Json)
                        setBody(body)
                    }
                first.status shouldBe HttpStatusCode.OK
                val second =
                    client.post("/api/webhooks/stripe") {
                        header("Stripe-Signature", header)
                        contentType(ContentType.Application.Json)
                        setBody(body)
                    }
                second.status shouldBe HttpStatusCode.OK

                // Test-quality fix (code review, Welle V1.2.8): this variable was named
                // journalEntryCount but actually counted payment_transaction rows -- renamed to match
                // what it counts, plus a real journal_entry-count assertion alongside it (the
                // duplicate redelivery must not create a second journal entry either).
                val paymentTransactionCount =
                    transaction {
                        PaymentTransactionTable
                            .selectAll()
                            .where { PaymentTransactionTable.contributionId eq contributionId }
                            .count()
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
                // Scoped by this test's own providerEventId -- a DUPLICATE-outcome row carries
                // paymentTransactionId = null (PspWebhookRoutes maps Duplicate -> null), so it is
                // never caught by the paymentTransactionId-inList cleanup other tests/specs use
                // (e.g. GatewayPaymentJourneyTest's own replay step leaves exactly such a row
                // behind). An unscoped selectAll() here would flake whenever that row shares this
                // test's JVM-worker DatabaseConfig instance.
                val outcomes =
                    transaction {
                        PspWebhookEventTable
                            .selectAll()
                            .where { PspWebhookEventTable.providerEventId eq eventId }
                            .map { it[PspWebhookEventTable.outcome] }
                    }
                outcomes.count { it == network.lapis.cloud.server.payment.psp.PspWebhookOutcome.DUPLICATE.name } shouldBe 1
            }
        }

        test(
            "second webhook with a DIFFERENT event.id for an ALREADY-COMPLETED session -> 200, still exactly " +
                "one payment_transaction/journal entry, second delivery outcome DUPLICATE",
        ) {
            // Welle V1.2.9 -- closes the gap the review flagged: the existing "duplicate redelivery"
            // test above only ever replays the IDENTICAL signed body (same event.id), which exercises
            // the unique-index guard on (provider, provider_event_id) inside PspWebhookIngestion's
            // step 2. This test instead sends a genuinely DIFFERENT event.id against a session that
            // is already PaymentCheckoutSessionStatus.COMPLETED -- that is PspWebhookIngestion's
            // EARLIER step-1 guard (a plain status-column read, not a DB unique-constraint
            // violation), so unlike the H2-vs-Postgres-sensitive unique-index path, this assertion
            // needs no Postgres-only exception-mapping behaviour to be meaningful on H2.
            testApplication {
                application { routing { registerPspWebhookRoutes(pspConfig = testConfig(), rateLimiter = FederationInboxRateLimiter()) } }

                val member = createMember("psp-webhook-dup-event-${Uuid.random()}@example.org")
                val tier = createTier()
                val contributionId = createOpenContribution(memberId = member, tierId = tier, amountDue = BigDecimal("30.00"))
                val bankAccountId = createLedgerAccount(number = "W5${Uuid.random().toString().take(6)}", type = LedgerAccountType.ASSET)
                val incomeAccountId = createLedgerAccount(number = "W6${Uuid.random().toString().take(6)}", type = LedgerAccountType.INCOME)
                enableGateway(bankAccountId = bankAccountId, incomeAccountId = incomeAccountId)
                val sessionId = "cs_dup_event_${Uuid.random()}"
                createCheckoutSession(
                    memberId = member,
                    contributionId = contributionId,
                    amount = BigDecimal("30.00"),
                    providerSessionId = sessionId,
                )

                val firstEventId = "evt_dup_event_first_${Uuid.random()}"
                val firstBody = checkoutCompletedBody(eventId = firstEventId, sessionId = sessionId, amountTotalMinorUnits = 3000)
                val first =
                    client.post("/api/webhooks/stripe") {
                        header("Stripe-Signature", signedHeader(body = firstBody))
                        contentType(ContentType.Application.Json)
                        setBody(firstBody)
                    }
                first.status shouldBe HttpStatusCode.OK

                // A DIFFERENT event.id, same Stripe session -- Stripe itself can and does deliver
                // more than one distinct event for one checkout (e.g. a dashboard-triggered resend
                // mints a fresh event.id). The session is now COMPLETED, so this must hit step 1's
                // guard, never re-process the payment a second time.
                val secondEventId = "evt_dup_event_second_${Uuid.random()}"
                val secondBody = checkoutCompletedBody(eventId = secondEventId, sessionId = sessionId, amountTotalMinorUnits = 3000)
                val second =
                    client.post("/api/webhooks/stripe") {
                        header("Stripe-Signature", signedHeader(body = secondBody))
                        contentType(ContentType.Application.Json)
                        setBody(secondBody)
                    }
                second.status shouldBe HttpStatusCode.OK

                val paymentTransactionCount =
                    transaction {
                        PaymentTransactionTable
                            .selectAll()
                            .where { PaymentTransactionTable.contributionId eq contributionId }
                            .count()
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

                // Scoped by the SECOND event's own providerEventId -- see the identical-body
                // duplicate test above for why an unscoped selectAll() here would flake.
                val secondOutcome =
                    transaction {
                        PspWebhookEventTable
                            .selectAll()
                            .where { PspWebhookEventTable.providerEventId eq secondEventId }
                            .single()[PspWebhookEventTable.outcome]
                    }
                secondOutcome shouldBe network.lapis.cloud.server.payment.psp.PspWebhookOutcome.DUPLICATE.name
            }
        }

        test("tampered body -> 401, zero payment_transaction, one psp_webhook_event with signature_verified=false") {
            testApplication {
                application { routing { registerPspWebhookRoutes(pspConfig = testConfig(), rateLimiter = FederationInboxRateLimiter()) } }

                val eventId = "evt_tamper_${Uuid.random()}"
                val body = checkoutCompletedBody(eventId = eventId, sessionId = "cs_unknown", amountTotalMinorUnits = 100)
                val header = signedHeader(body = body)
                val tamperedBody = String(body, Charsets.UTF_8).replace("100", "999").toByteArray(Charsets.UTF_8)

                val response =
                    client.post("/api/webhooks/stripe") {
                        header("Stripe-Signature", header)
                        contentType(ContentType.Application.Json)
                        setBody(tamperedBody)
                    }
                response.status shouldBe HttpStatusCode.Unauthorized

                // Test-quality fix (code review, Welle V1.2.8): the test's own name promised these
                // two assertions but only ever checked the HTTP status.
                val paymentTransactionCount =
                    transaction {
                        PaymentTransactionTable
                            .selectAll()
                            .where { PaymentTransactionTable.rawPayloadDigest eq sha256Hex(tamperedBody) }
                            .count()
                    }
                paymentTransactionCount shouldBe 0L
                // A tampered body never reaches the typed decode step, so the event id inside the
                // (rejected) body is never persisted as providerEventId -- scope by bodySha256 instead,
                // same "scope by something unique to this test" discipline the duplicate-redelivery
                // test above already documents.
                val loggedRow =
                    transaction {
                        PspWebhookEventTable.selectAll().where { PspWebhookEventTable.bodySha256 eq sha256Hex(tamperedBody) }.single()
                    }
                loggedRow[PspWebhookEventTable.signatureVerified] shouldBe false
            }
        }

        test("missing Stripe-Signature header -> 401") {
            testApplication {
                application { routing { registerPspWebhookRoutes(pspConfig = testConfig(), rateLimiter = FederationInboxRateLimiter()) } }
                val body =
                    checkoutCompletedBody(eventId = "evt_nosig_${Uuid.random()}", sessionId = "cs_unknown", amountTotalMinorUnits = 100)
                val response =
                    client.post("/api/webhooks/stripe") {
                        contentType(ContentType.Application.Json)
                        setBody(body)
                    }
                response.status shouldBe HttpStatusCode.Unauthorized
            }
        }

        test("stale timestamp -> 401") {
            testApplication {
                application { routing { registerPspWebhookRoutes(pspConfig = testConfig(), rateLimiter = FederationInboxRateLimiter()) } }
                val body =
                    checkoutCompletedBody(eventId = "evt_stale_${Uuid.random()}", sessionId = "cs_unknown", amountTotalMinorUnits = 100)
                val staleHeader = signedHeader(body = body, timestampEpochSeconds = Clock.System.now().epochSeconds - 10_000)
                val response =
                    client.post("/api/webhooks/stripe") {
                        header("Stripe-Signature", staleHeader)
                        contentType(ContentType.Application.Json)
                        setBody(body)
                    }
                response.status shouldBe HttpStatusCode.Unauthorized
            }
        }

        test("gate disabled (payment_gateway_enabled=false) -> 503, no accounting touched") {
            testApplication {
                application { routing { registerPspWebhookRoutes(pspConfig = testConfig(), rateLimiter = FederationInboxRateLimiter()) } }
                val eventId = "evt_gate_${Uuid.random()}"
                val body = checkoutCompletedBody(eventId = eventId, sessionId = "cs_unknown", amountTotalMinorUnits = 100)
                val response =
                    client.post("/api/webhooks/stripe") {
                        header("Stripe-Signature", signedHeader(body = body))
                        contentType(ContentType.Application.Json)
                        setBody(body)
                    }
                response.status shouldBe HttpStatusCode.ServiceUnavailable

                // Test-quality fix (code review, Welle V1.2.8): "no accounting touched" was only
                // ever asserted by name -- the gate check runs BEFORE dispatch, so
                // ingestCheckoutCompleted is never even called; confirm no payment_transaction row
                // exists for this event, and the psp_webhook_event row records the REJECTED/GATE_DISABLED
                // outcome rather than anything posting-shaped.
                val paymentTransactionCount =
                    transaction { PaymentTransactionTable.selectAll().where { PaymentTransactionTable.providerEventId eq eventId }.count() }
                paymentTransactionCount shouldBe 0L
                val loggedRow =
                    transaction { PspWebhookEventTable.selectAll().where { PspWebhookEventTable.providerEventId eq eventId }.single() }
                loggedRow[PspWebhookEventTable.outcome] shouldBe network.lapis.cloud.server.payment.psp.PspWebhookOutcome.REJECTED.name
                loggedRow[PspWebhookEventTable.rejectReason] shouldBe "GATE_DISABLED"
            }
        }

        test("amount mismatch: stored session says 50.00, webhook says 5.00 -> 200, journal_entry_id IS NULL, UNPOSTED") {
            testApplication {
                application { routing { registerPspWebhookRoutes(pspConfig = testConfig(), rateLimiter = FederationInboxRateLimiter()) } }

                val member = createMember("psp-webhook-mismatch-${Uuid.random()}@example.org")
                val tier = createTier()
                val contributionId = createOpenContribution(memberId = member, tierId = tier, amountDue = BigDecimal("50.00"))
                val bankAccountId = createLedgerAccount(number = "W5${Uuid.random().toString().take(6)}", type = LedgerAccountType.ASSET)
                val incomeAccountId = createLedgerAccount(number = "W6${Uuid.random().toString().take(6)}", type = LedgerAccountType.INCOME)
                enableGateway(bankAccountId = bankAccountId, incomeAccountId = incomeAccountId)
                val sessionId = "cs_mismatch_${Uuid.random()}"
                createCheckoutSession(
                    memberId = member,
                    contributionId = contributionId,
                    amount = BigDecimal("50.00"),
                    providerSessionId = sessionId,
                )

                val body =
                    checkoutCompletedBody(eventId = "evt_mismatch_${Uuid.random()}", sessionId = sessionId, amountTotalMinorUnits = 500)
                val response =
                    client.post("/api/webhooks/stripe") {
                        header("Stripe-Signature", signedHeader(body = body))
                        contentType(ContentType.Application.Json)
                        setBody(body)
                    }
                response.status shouldBe HttpStatusCode.OK

                val transactionRow =
                    transaction {
                        PaymentTransactionTable
                            .selectAll()
                            .where { PaymentTransactionTable.contributionId eq contributionId }
                            .single()
                    }
                transactionRow[PaymentTransactionTable.journalEntryId] shouldBe null
                val contributionStatus =
                    transaction {
                        ContributionTable.selectAll().where { ContributionTable.id eq contributionId }.single()[ContributionTable.status]
                    }
                contributionStatus shouldBe ContributionStatus.OPEN
            }
        }

        // MAJOR fix #1 (code review, Welle V1.2.8) test coverage: a webhook landing on a
        // contribution that is currently bound up in an in-flight SEPA debit run must not flip it to
        // PAID -- see PspWebhookIngestion's own widened notInList guard.
        test("checkout.session.completed webhook for a DEBIT_SCHEDULED contribution -> 200, UNPOSTED, status unchanged") {
            testApplication {
                application { routing { registerPspWebhookRoutes(pspConfig = testConfig(), rateLimiter = FederationInboxRateLimiter()) } }

                val member = createMember("psp-webhook-debit-in-flight-${Uuid.random()}@example.org")
                val tier = createTier()
                val contributionId =
                    createOpenContribution(
                        memberId = member,
                        tierId = tier,
                        amountDue = BigDecimal("50.00"),
                        status = ContributionStatus.DEBIT_SCHEDULED,
                    )
                val bankAccountId =
                    createLedgerAccount(number = "W9${Uuid.random().toString().take(6)}", type = LedgerAccountType.ASSET)
                val incomeAccountId =
                    createLedgerAccount(number = "WA${Uuid.random().toString().take(6)}", type = LedgerAccountType.INCOME)
                enableGateway(bankAccountId = bankAccountId, incomeAccountId = incomeAccountId)
                val sessionId = "cs_debit_in_flight_${Uuid.random()}"
                createCheckoutSession(
                    memberId = member,
                    contributionId = contributionId,
                    amount = BigDecimal("50.00"),
                    providerSessionId = sessionId,
                )

                val eventId = "evt_debit_in_flight_${Uuid.random()}"
                val body = checkoutCompletedBody(eventId = eventId, sessionId = sessionId, amountTotalMinorUnits = 5000)
                val response =
                    client.post("/api/webhooks/stripe") {
                        header("Stripe-Signature", signedHeader(body = body))
                        contentType(ContentType.Application.Json)
                        setBody(body)
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
                // Still DEBIT_SCHEDULED -- the webhook must NOT flip it to PAID while a SEPA batch
                // may already be collecting it.
                contributionStatus shouldBe ContributionStatus.DEBIT_SCHEDULED
                val loggedRow =
                    transaction { PspWebhookEventTable.selectAll().where { PspWebhookEventTable.providerEventId eq eventId }.single() }
                loggedRow[PspWebhookEventTable.outcome] shouldBe network.lapis.cloud.server.payment.psp.PspWebhookOutcome.UNPOSTED.name
            }
        }

        // MINOR fix #15 (code review, Welle V1.2.8) test coverage: currency mismatch, distinct from
        // the amount-mismatch case above.
        test("currency mismatch: stored session says EUR, webhook says USD -> 200, journal_entry_id IS NULL, UNPOSTED") {
            testApplication {
                application { routing { registerPspWebhookRoutes(pspConfig = testConfig(), rateLimiter = FederationInboxRateLimiter()) } }

                val member = createMember("psp-webhook-currency-mismatch-${Uuid.random()}@example.org")
                val tier = createTier()
                val contributionId = createOpenContribution(memberId = member, tierId = tier, amountDue = BigDecimal("50.00"))
                val bankAccountId =
                    createLedgerAccount(number = "WB${Uuid.random().toString().take(6)}", type = LedgerAccountType.ASSET)
                val incomeAccountId =
                    createLedgerAccount(number = "WC${Uuid.random().toString().take(6)}", type = LedgerAccountType.INCOME)
                enableGateway(bankAccountId = bankAccountId, incomeAccountId = incomeAccountId)
                val sessionId = "cs_currency_mismatch_${Uuid.random()}"
                createCheckoutSession(
                    memberId = member,
                    contributionId = contributionId,
                    amount = BigDecimal("50.00"),
                    providerSessionId = sessionId,
                )

                val body =
                    checkoutCompletedBody(
                        eventId = "evt_currency_mismatch_${Uuid.random()}",
                        sessionId = sessionId,
                        amountTotalMinorUnits = 5000,
                        currency = "usd",
                    )
                val response =
                    client.post("/api/webhooks/stripe") {
                        header("Stripe-Signature", signedHeader(body = body))
                        contentType(ContentType.Application.Json)
                        setBody(body)
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

        // Security audit finding (Welle V1.2.8, MINOR/hardening) test coverage: a payment_status
        // other than "paid" (delayed/async payment method) must NOT be booked as CAPTURED/PAID even
        // though amount+currency match -- see PspWebhookIngestion Step 3's payment_status check.
        test("payment_status='unpaid': amount/currency match but session not actually paid -> 200, journal_entry_id IS NULL, UNPOSTED") {
            testApplication {
                application { routing { registerPspWebhookRoutes(pspConfig = testConfig(), rateLimiter = FederationInboxRateLimiter()) } }

                val member = createMember("psp-webhook-unpaid-${Uuid.random()}@example.org")
                val tier = createTier()
                val contributionId = createOpenContribution(memberId = member, tierId = tier, amountDue = BigDecimal("50.00"))
                val bankAccountId =
                    createLedgerAccount(number = "WF${Uuid.random().toString().take(6)}", type = LedgerAccountType.ASSET)
                val incomeAccountId =
                    createLedgerAccount(number = "WG${Uuid.random().toString().take(6)}", type = LedgerAccountType.INCOME)
                enableGateway(bankAccountId = bankAccountId, incomeAccountId = incomeAccountId)
                val sessionId = "cs_unpaid_${Uuid.random()}"
                createCheckoutSession(
                    memberId = member,
                    contributionId = contributionId,
                    amount = BigDecimal("50.00"),
                    providerSessionId = sessionId,
                )

                val eventId = "evt_unpaid_${Uuid.random()}"
                val body =
                    checkoutCompletedBody(
                        eventId = eventId,
                        sessionId = sessionId,
                        amountTotalMinorUnits = 5000,
                        paymentStatus = "unpaid",
                    )
                val response =
                    client.post("/api/webhooks/stripe") {
                        header("Stripe-Signature", signedHeader(body = body))
                        contentType(ContentType.Application.Json)
                        setBody(body)
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
                val loggedRow =
                    transaction { PspWebhookEventTable.selectAll().where { PspWebhookEventTable.providerEventId eq eventId }.single() }
                loggedRow[PspWebhookEventTable.outcome] shouldBe network.lapis.cloud.server.payment.psp.PspWebhookOutcome.UNPOSTED.name
            }
        }

        // MINOR fix #15 (code review, Welle V1.2.8) test coverage: route-level checkout.session.expired.
        test("checkout.session.expired event -> 200, checkout session marked EXPIRED") {
            testApplication {
                application { routing { registerPspWebhookRoutes(pspConfig = testConfig(), rateLimiter = FederationInboxRateLimiter()) } }

                val member = createMember("psp-webhook-expired-${Uuid.random()}@example.org")
                val tier = createTier()
                val contributionId = createOpenContribution(memberId = member, tierId = tier, amountDue = BigDecimal("50.00"))
                val bankAccountId =
                    createLedgerAccount(number = "WD${Uuid.random().toString().take(6)}", type = LedgerAccountType.ASSET)
                val incomeAccountId =
                    createLedgerAccount(number = "WE${Uuid.random().toString().take(6)}", type = LedgerAccountType.INCOME)
                enableGateway(bankAccountId = bankAccountId, incomeAccountId = incomeAccountId)
                val sessionId = "cs_expired_${Uuid.random()}"
                val checkoutSessionId =
                    createCheckoutSession(
                        memberId = member,
                        contributionId = contributionId,
                        amount = BigDecimal("50.00"),
                        providerSessionId = sessionId,
                    )

                val eventId = "evt_expired_${Uuid.random()}"
                val body =
                    """
                    {"id":"$eventId","type":"checkout.session.expired","data":{"object":{"id":"$sessionId"}}}
                    """.trimIndent().toByteArray(Charsets.UTF_8)
                val response =
                    client.post("/api/webhooks/stripe") {
                        header("Stripe-Signature", signedHeader(body = body))
                        contentType(ContentType.Application.Json)
                        setBody(body)
                    }
                response.status shouldBe HttpStatusCode.OK

                val sessionStatus =
                    transaction {
                        PaymentCheckoutSessionTable
                            .selectAll()
                            .where { PaymentCheckoutSessionTable.id eq checkoutSessionId }
                            .single()[PaymentCheckoutSessionTable.status]
                    }
                sessionStatus shouldBe PaymentCheckoutSessionStatus.EXPIRED
                val loggedRow =
                    transaction { PspWebhookEventTable.selectAll().where { PspWebhookEventTable.providerEventId eq eventId }.single() }
                loggedRow[PspWebhookEventTable.outcome] shouldBe network.lapis.cloud.server.payment.psp.PspWebhookOutcome.PROCESSED.name
            }
        }

        test("unknown event type (invoice.paid) -> 200, IGNORED, no mutation") {
            testApplication {
                application { routing { registerPspWebhookRoutes(pspConfig = testConfig(), rateLimiter = FederationInboxRateLimiter()) } }
                val bankAccountId = createLedgerAccount(number = "W7${Uuid.random().toString().take(6)}", type = LedgerAccountType.ASSET)
                val incomeAccountId = createLedgerAccount(number = "W8${Uuid.random().toString().take(6)}", type = LedgerAccountType.INCOME)
                enableGateway(bankAccountId = bankAccountId, incomeAccountId = incomeAccountId)
                val eventId = "evt_ignored_${Uuid.random()}"
                val body = """{"id":"$eventId","type":"invoice.paid","data":{"object":{"id":"cs_ignored"}}}""".toByteArray(Charsets.UTF_8)
                val response =
                    client.post("/api/webhooks/stripe") {
                        header("Stripe-Signature", signedHeader(body = body))
                        contentType(ContentType.Application.Json)
                        setBody(body)
                    }
                response.status shouldBe HttpStatusCode.OK

                // Test-quality fix (code review, Welle V1.2.8): the test's own name promised
                // "IGNORED, no mutation" but only ever checked the HTTP status.
                val loggedRow =
                    transaction { PspWebhookEventTable.selectAll().where { PspWebhookEventTable.providerEventId eq eventId }.single() }
                loggedRow[PspWebhookEventTable.outcome] shouldBe network.lapis.cloud.server.payment.psp.PspWebhookOutcome.IGNORED.name
                val paymentTransactionCount =
                    transaction { PaymentTransactionTable.selectAll().where { PaymentTransactionTable.providerEventId eq eventId }.count() }
                paymentTransactionCount shouldBe 0L
            }
        }

        test("oversized body (>64 KiB) -> 413, body never parsed") {
            testApplication {
                application { routing { registerPspWebhookRoutes(pspConfig = testConfig(), rateLimiter = FederationInboxRateLimiter()) } }
                // Test-quality fix (code review, Welle V1.2.8): "body never parsed" was only ever
                // asserted by name. The oversized-body streaming-read guard is one of the THREE
                // early-exit branches (see PspWebhookRoutes' own class KDoc) that respond directly
                // and write no psp_webhook_event row at all -- there is no parsed event id to scope
                // a lookup by, so a before/after row-count snapshot (Kotest FunSpec tests in this
                // Spec instance run sequentially, so no other test's write can land between the two
                // counts below) is the way to prove nothing was logged for this request.
                val countBefore = transaction { PspWebhookEventTable.selectAll().count() }
                val oversizedBody = "x".repeat(70 * 1024).toByteArray(Charsets.UTF_8)
                val response =
                    client.post("/api/webhooks/stripe") {
                        header("Stripe-Signature", signedHeader(body = oversizedBody))
                        contentType(ContentType.Application.Json)
                        setBody(oversizedBody)
                    }
                response.status shouldBe HttpStatusCode.PayloadTooLarge
                val countAfter = transaction { PspWebhookEventTable.selectAll().count() }
                countAfter shouldBe countBefore
            }
        }

        test("rate limit: exceeding the window -> 429") {
            testApplication {
                application {
                    routing {
                        registerPspWebhookRoutes(
                            pspConfig = testConfig(),
                            rateLimiter = FederationInboxRateLimiter(maxRequests = 1, window = 1.minutes),
                        )
                    }
                }
                val body =
                    checkoutCompletedBody(eventId = "evt_rate_${Uuid.random()}", sessionId = "cs_unknown", amountTotalMinorUnits = 100)
                val header = signedHeader(body = body)
                client.post("/api/webhooks/stripe") {
                    this.header("Stripe-Signature", header)
                    contentType(ContentType.Application.Json)
                    setBody(body)
                }
                val second =
                    client.post("/api/webhooks/stripe") {
                        this.header("Stripe-Signature", header)
                        contentType(ContentType.Application.Json)
                        setBody(body)
                    }
                second.status shouldBe HttpStatusCode.TooManyRequests
            }
        }
    })
