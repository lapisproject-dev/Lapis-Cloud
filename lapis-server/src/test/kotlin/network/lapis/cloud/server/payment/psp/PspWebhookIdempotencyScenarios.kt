package network.lapis.cloud.server.payment.psp

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.annotation.EnabledIf
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
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
import network.lapis.cloud.server.db.DbFailureKind
import network.lapis.cloud.server.db.DbSessionTimeouts
import network.lapis.cloud.server.db.dbFailureKind
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
import network.lapis.cloud.server.routes.registerPspWebhookRoutes
import network.lapis.cloud.server.rpc.ORGANIZATION_SETTINGS_ID
import network.lapis.cloud.server.rpc.PaymentGatewayComplianceDisclaimer
import network.lapis.cloud.server.testdb.PostgresConfigured
import network.lapis.cloud.server.testdb.TestDatabase
import network.lapis.cloud.server.testdb.installLaneGuards
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.BillingInterval
import network.lapis.cloud.shared.domain.ContributionStatus
import network.lapis.cloud.shared.domain.LedgerAccountType
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.domain.PaymentCheckoutSessionStatus
import network.lapis.cloud.shared.domain.PaymentIntent
import network.lapis.cloud.shared.domain.PaymentProvider
import network.lapis.cloud.shared.domain.PaymentTransactionStatus
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.exceptions.ExposedSQLException
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import java.math.BigDecimal
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.time.Clock
import kotlin.uuid.Uuid

private const val SCENARIO_WEBHOOK_SECRET = "whsec_psp_webhook_idempotency_scenarios"

/**
 * Welle V1.9.55 -- the PSP webhook is the single place a gateway payment becomes money, and Stripe/PayPal redeliver until they
 * see a 2xx. Idempotency therefore has to hold for sequential AND parallel redelivery, and a database timeout must NEVER be
 * mistaken for "duplicate" (the lock-timeout-as-Duplicate bug this wave fixes: a delivery waiting on its twin's unique-index
 * lock would have been acknowledged with 200 and, if the twin rolled back, the payment lost for good).
 */
abstract class PspWebhookIdempotencyScenarios(
    private val db: TestDatabase,
) : FunSpec({
        val createdMemberIds = mutableListOf<Uuid>()
        val createdLedgerAccountIds = mutableListOf<Uuid>()
        val createdTierIds = mutableListOf<Uuid>()
        val createdContributionIds = mutableListOf<Uuid>()
        val createdCheckoutSessionIds = mutableListOf<Uuid>()
        val createdEventIds = mutableListOf<String>()

        beforeSpec { db.activate() }
        installLaneGuards(db = db)
        afterSpec {
            transaction {
                OrganizationSettingsTable.update({ OrganizationSettingsTable.id eq ORGANIZATION_SETTINGS_ID }) {
                    it[paymentGatewayEnabled] = false
                    it[paymentGatewayProvider] = null
                    it[paymentBankAccountId] = null
                    it[contributionIncomeAccountId] = null
                }
                if (createdMemberIds.isNotEmpty()) {
                    AuditLogEntryTable.update({ AuditLogEntryTable.actorMemberId inList createdMemberIds }) { it[actorMemberId] = null }
                }
                val transactionIds =
                    PaymentTransactionTable
                        .selectAll()
                        .where {
                            (PaymentTransactionTable.checkoutSessionId inList createdCheckoutSessionIds) or
                                (PaymentTransactionTable.providerEventId inList createdEventIds)
                        }.map { it[PaymentTransactionTable.id] }
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
                if (createdCheckoutSessionIds.isNotEmpty()) {
                    PaymentCheckoutSessionTable.deleteWhere { PaymentCheckoutSessionTable.id inList createdCheckoutSessionIds }
                }
                if (createdContributionIds.isNotEmpty()) {
                    ContributionTable.deleteWhere {
                        ContributionTable.id inList createdContributionIds
                    }
                }
                if (createdTierIds.isNotEmpty()) MembershipTierTable.deleteWhere { MembershipTierTable.id inList createdTierIds }
                if (createdLedgerAccountIds.isNotEmpty()) {
                    LedgerAccountTable.deleteWhere {
                        LedgerAccountTable.id inList
                            createdLedgerAccountIds
                    }
                }
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
                    it[displayName] = "PspIdempotency Testmitglied"
                    it[email] = "psp-idem-$id@example.org"
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

        fun createLedgerAccount(type: LedgerAccountType): Uuid {
            val id = Uuid.random()
            transaction {
                LedgerAccountTable.insert {
                    it[LedgerAccountTable.id] = id
                    it[accountNumber] = "I${Uuid.random().toString().take(8)}"
                    it[name] = "PspIdempotency Konto"
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

        val tierId: Uuid by lazy {
            val id = Uuid.random()
            transaction {
                MembershipTierTable.insert {
                    it[MembershipTierTable.id] = id
                    it[nameKey] = id.toString()
                    it[name] = "PspIdempotency Tarif ${id.toString().take(6)}"
                    it[description] = "Test-Tarif"
                    it[contributionAmount] = BigDecimal("50.00")
                    it[billingInterval] = BillingInterval.YEARLY
                    it[active] = true
                    it[paymentTermDays] = 14
                }
            }
            createdTierIds += id
            id
        }

        fun enableGateway(provider: PaymentProvider) {
            val bank = createLedgerAccount(LedgerAccountType.ASSET)
            val income = createLedgerAccount(LedgerAccountType.INCOME)
            val acknowledger = createMember()
            transaction {
                OrganizationSettingsTable.update({ OrganizationSettingsTable.id eq ORGANIZATION_SETTINGS_ID }) {
                    it[paymentGatewayEnabled] = true
                    it[paymentGatewayProvider] = provider
                    it[paymentBankAccountId] = bank
                    it[contributionIncomeAccountId] = income
                }
                PaymentGatewayComplianceAcknowledgmentTable.insert {
                    it[id] = Uuid.random()
                    it[acknowledgedByMemberId] = acknowledger
                    it[acknowledgedAt] = LocalDateTime(2026, 4, 1, 9, 0)
                    it[disclaimerVersion] = PaymentGatewayComplianceDisclaimer.VERSION
                    it[disclaimerSha256] = PaymentGatewayComplianceDisclaimer.SHA256
                    it[PaymentGatewayComplianceAcknowledgmentTable.provider] = provider
                }
            }
        }

        class Fixture(
            val member: Uuid,
            val contributionId: Uuid,
            val checkoutSessionId: Uuid,
            val providerSessionId: String,
            val providerEventId: String,
            val provider: PaymentProvider,
        ) {
            fun event() =
                PspPaymentEvent(
                    provider = provider,
                    providerEventId = providerEventId,
                    providerSessionId = providerSessionId,
                    providerPaymentId = "pay_$providerEventId",
                    amount = BigDecimal("50.00"),
                    currency = "EUR",
                    paymentStatus = "paid",
                    payerReference = "payer_1",
                )
        }

        fun createFixture(provider: PaymentProvider = PaymentProvider.STRIPE): Fixture {
            val member = createMember()
            val contributionId = Uuid.random()
            transaction {
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
            createdContributionIds += contributionId
            val sessionId = Uuid.random()
            val providerSessionId = "cs_idem_${Uuid.random()}"
            transaction {
                PaymentCheckoutSessionTable.insert {
                    it[PaymentCheckoutSessionTable.id] = sessionId
                    it[PaymentCheckoutSessionTable.provider] = provider
                    it[PaymentCheckoutSessionTable.providerSessionId] = providerSessionId
                    it[status] = PaymentCheckoutSessionStatus.CREATED
                    it[intent] = PaymentIntent.CONTRIBUTION
                    it[PaymentCheckoutSessionTable.contributionId] = contributionId
                    it[memberId] = member
                    it[amount] = BigDecimal("50.00")
                    it[currency] = "EUR"
                    it[donorCategory] = null
                    it[purpose] = null
                    it[createdAt] = LocalDateTime(2026, 4, 1, 10, 0)
                    it[expiresAt] = LocalDateTime(2026, 4, 1, 11, 0)
                    it[completedAt] = null
                    it[providerIdempotencyKey] = "idem-${sessionId.toString().take(8)}"
                    it[redirectUrl] = "https://example.invalid/pay/$providerSessionId"
                }
            }
            createdCheckoutSessionIds += sessionId
            val eventId = "evt_idem_${Uuid.random()}"
            createdEventIds += eventId
            return Fixture(member, contributionId, sessionId, providerSessionId, eventId, provider)
        }

        fun transactionCount(providerEventId: String): Int =
            transaction {
                PaymentTransactionTable
                    .selectAll()
                    .where { PaymentTransactionTable.providerEventId eq providerEventId }
                    .count()
                    .toInt()
            }

        fun contributionStatus(id: Uuid): ContributionStatus =
            transaction { ContributionTable.selectAll().where { ContributionTable.id eq id }.single()[ContributionTable.status] }

        fun journalEntriesFor(f: Fixture): Int =
            transaction {
                PaymentTransactionTable
                    .selectAll()
                    .where { PaymentTransactionTable.checkoutSessionId eq f.checkoutSessionId }
                    .mapNotNull { it[PaymentTransactionTable.journalEntryId] }
                    .size
            }

        test("the same Stripe event delivered twice in a row: one payment_transaction, one journal entry, one settled contribution") {
            enableGateway(PaymentProvider.STRIPE)
            val f = createFixture()
            val first = PspWebhookIngestion.ingestCheckoutCompleted(event = f.event(), bodyBytes = "body".toByteArray())
            first.outcome.shouldBeInstanceOf<CheckoutCompletedIngestionOutcome.Processed>()
            val second = PspWebhookIngestion.ingestCheckoutCompleted(event = f.event(), bodyBytes = "body".toByteArray())
            second.outcome shouldBe CheckoutCompletedIngestionOutcome.Duplicate
            transactionCount(f.providerEventId) shouldBe 1
            journalEntriesFor(f) shouldBe 1
            contributionStatus(f.contributionId) shouldBe ContributionStatus.PAID
        }

        test("the same Stripe event delivered concurrently is processed exactly once") {
            enableGateway(PaymentProvider.STRIPE)
            val f = createFixture()
            val barrier = CyclicBarrier(2)
            val pool = Executors.newFixedThreadPool(2)
            try {
                val results =
                    (1..2)
                        .map {
                            pool.submit<CheckoutCompletedIngestionOutcome> {
                                barrier.await(20, TimeUnit.SECONDS)
                                PspWebhookIngestion.ingestCheckoutCompleted(event = f.event(), bodyBytes = "body".toByteArray()).outcome
                            }
                        }.map { it.get(60, TimeUnit.SECONDS) }
                results.count { it is CheckoutCompletedIngestionOutcome.Processed } shouldBe 1
                results.count { it == CheckoutCompletedIngestionOutcome.Duplicate } shouldBe 1
            } finally {
                pool.shutdownNow()
            }
            transactionCount(f.providerEventId) shouldBe 1
            journalEntriesFor(f) shouldBe 1
            contributionStatus(f.contributionId) shouldBe ContributionStatus.PAID
        }

        test("a PayPal PAYMENT.CAPTURE.COMPLETED event delivered twice is idempotent too") {
            enableGateway(PaymentProvider.PAYPAL)
            val f = createFixture(PaymentProvider.PAYPAL)
            PspWebhookIngestion
                .ingestCheckoutCompleted(event = f.event(), bodyBytes = "body".toByteArray())
                .outcome
                .shouldBeInstanceOf<CheckoutCompletedIngestionOutcome.Processed>()
            PspWebhookIngestion.ingestCheckoutCompleted(event = f.event(), bodyBytes = "body".toByteArray()).outcome shouldBe
                CheckoutCompletedIngestionOutcome.Duplicate
            transactionCount(f.providerEventId) shouldBe 1
            journalEntriesFor(f) shouldBe 1
        }

        if (db.isPostgres) {
            /**
             * A competing transaction holds an UNCOMMITTED payment_transaction row with the same (provider, provider_event_id).
             * Returns the release handle (rollback or commit).
             */
            class Blocker(
                private val f: Fixture,
            ) {
                private val holding = CountDownLatch(1)
                private val release = CountDownLatch(1)
                private val pool = Executors.newSingleThreadExecutor()
                private var commit = false
                private val job =
                    pool.submit {
                        transaction {
                            PaymentTransactionTable.insert {
                                it[id] = Uuid.random()
                                it[provider] = f.provider
                                it[providerEventId] = f.providerEventId
                                it[providerPaymentId] = "blocker_${f.providerEventId}"
                                it[status] = PaymentTransactionStatus.CAPTURED
                                it[amount] = BigDecimal("50.00")
                                it[currency] = "EUR"
                                it[feeAmount] = null
                                it[intent] = PaymentIntent.CONTRIBUTION
                                it[contributionId] = f.contributionId
                                it[memberId] = f.member
                                it[payerReference] = null
                                it[receivedAt] = LocalDateTime(2026, 4, 1, 10, 0)
                                it[reconciledAt] = null
                                it[reconciledBy] = null
                                it[journalEntryId] = null
                                it[reconciliationNote] = null
                                it[rawPayloadDigest] = "blocker"
                                it[checkoutSessionId] = null
                                it[donorCategory] = null
                            }
                            holding.countDown()
                            release.await(60, TimeUnit.SECONDS)
                            if (!commit) rollback()
                        }
                    }

                fun awaitHolding() {
                    holding.await(20, TimeUnit.SECONDS) shouldBe true
                }

                fun finish(commitIt: Boolean) {
                    commit = commitIt
                    release.countDown()
                    job.get(30, TimeUnit.SECONDS)
                    pool.shutdownNow()
                }
            }

            test("a lock timeout while the twin holds the unique-index lock is NOT a Duplicate: it throws and nothing is written") {
                enableGateway(PaymentProvider.STRIPE)
                val f = createFixture()
                val blocker = Blocker(f)
                blocker.awaitHolding()
                try {
                    val failure =
                        shouldThrow<ExposedSQLException> {
                            PspWebhookIngestion.ingestCheckoutCompleted(
                                event = f.event(),
                                bodyBytes = "body".toByteArray(),
                            )
                        }
                    failure.sqlState shouldBe "55P03"
                    failure.dbFailureKind() shouldBe DbFailureKind.LOCK_TIMEOUT
                    contributionStatus(f.contributionId) shouldBe ContributionStatus.OPEN
                    transaction {
                        PaymentCheckoutSessionTable
                            .selectAll()
                            .where { PaymentCheckoutSessionTable.id eq f.checkoutSessionId }
                            .single()[PaymentCheckoutSessionTable.status]
                    } shouldBe PaymentCheckoutSessionStatus.CREATED
                } finally {
                    blocker.finish(commitIt = false)
                }
                // The twin rolled back: the redelivery is processed normally (it would have been lost had we acknowledged it).
                PspWebhookIngestion
                    .ingestCheckoutCompleted(event = f.event(), bodyBytes = "body".toByteArray())
                    .outcome
                    .shouldBeInstanceOf<CheckoutCompletedIngestionOutcome.Processed>()
                contributionStatus(f.contributionId) shouldBe ContributionStatus.PAID
            }

            test("after the twin COMMITS, the redelivery is a genuine Duplicate (unique violation 23505)") {
                enableGateway(PaymentProvider.STRIPE)
                val f = createFixture()
                val blocker = Blocker(f)
                blocker.awaitHolding()
                try {
                    shouldThrow<ExposedSQLException> {
                        PspWebhookIngestion.ingestCheckoutCompleted(
                            event = f.event(),
                            bodyBytes = "body".toByteArray(),
                        )
                    }
                } finally {
                    blocker.finish(commitIt = true)
                }
                PspWebhookIngestion.ingestCheckoutCompleted(event = f.event(), bodyBytes = "body".toByteArray()).outcome shouldBe
                    CheckoutCompletedIngestionOutcome.Duplicate
                transactionCount(f.providerEventId) shouldBe 1
            }

            test("route: a Stripe webhook that hits a database timeout answers 5xx (so the provider redelivers), never 2xx") {
                enableGateway(PaymentProvider.STRIPE)
                val f = createFixture()
                val blocker = Blocker(f)
                blocker.awaitHolding()
                try {
                    val body =
                        """
                        {"id":"${f.providerEventId}","type":"checkout.session.completed","data":{"object":{"id":"${f.providerSessionId}",
                        "payment_intent":"pi_${f.providerEventId}","amount_total":5000,"currency":"eur"}}}
                        """.trimIndent().toByteArray(Charsets.UTF_8)
                    val timestamp = Clock.System.now().epochSeconds
                    val mac = Mac.getInstance("HmacSHA256")
                    mac.init(SecretKeySpec(SCENARIO_WEBHOOK_SECRET.toByteArray(Charsets.UTF_8), "HmacSHA256"))
                    val signature = mac.doFinal("$timestamp.".toByteArray(Charsets.UTF_8) + body).joinToString("") { "%02x".format(it) }
                    val config =
                        PspConfig.load {
                            when (it) {
                                PspConfig.ENV_SECRET_KEY -> "sk_test_psp_idempotency_scenarios"
                                PspConfig.ENV_WEBHOOK_SIGNING_SECRET -> SCENARIO_WEBHOOK_SECRET
                                else -> null
                            }
                        }
                    testApplication {
                        application { routing { registerPspWebhookRoutes(pspConfig = config, rateLimiter = FederationInboxRateLimiter()) } }
                        val response =
                            client.post("/api/webhooks/stripe") {
                                header("Stripe-Signature", "t=$timestamp,v1=$signature")
                                contentType(ContentType.Application.Json)
                                setBody(body)
                            }
                        (response.status.value in 500..599) shouldBe true
                        response.status shouldBe HttpStatusCode.InternalServerError
                    }
                } finally {
                    blocker.finish(commitIt = false)
                }
                contributionStatus(f.contributionId) shouldBe ContributionStatus.OPEN
            }
        }
    })

class PspWebhookIdempotencyTest : PspWebhookIdempotencyScenarios(TestDatabase.H2)

@Tags("Postgres")
@EnabledIf(PostgresConfigured::class)
class PspWebhookIdempotencyPostgresTest :
    PspWebhookIdempotencyScenarios(
        TestDatabase.Postgres(DbSessionTimeouts(lockTimeoutMs = 300, statementTimeoutMs = 20_000, idleInTransactionTimeoutMs = 120_000)),
    )
