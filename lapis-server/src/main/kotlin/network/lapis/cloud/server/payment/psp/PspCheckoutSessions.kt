package network.lapis.cloud.server.payment.psp

import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.server.db.generated.PaymentCheckoutSessionTable
import network.lapis.cloud.shared.domain.DonorCategory
import network.lapis.cloud.shared.domain.PaymentCheckoutSessionStatus
import network.lapis.cloud.shared.domain.PaymentIntent
import network.lapis.cloud.shared.domain.PaymentProvider
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import java.math.BigDecimal
import kotlin.uuid.Uuid

/**
 * Welle V1.2.8 "PSP-Checkout (Stripe)" (GitHub Issue #6) -- the `payment_checkout_session` store.
 * **`transaction`-free by contract** -- every function here must be called from inside the caller's
 * already-open `transaction {}`, same idiom [network.lapis.cloud.server.audit.AuditLogRecorder]/
 * `ContributionPostingBridge` already establish.
 */
object PspCheckoutSessions {
    /**
     * Inserts a new `CREATED` [PaymentCheckoutSessionTable] row. [id] is caller-supplied
     * (`Uuid.random()`) so the caller can reference it (e.g. as Stripe's `client_reference_id`)
     * before this function returns.
     */
    fun create(
        id: Uuid,
        provider: PaymentProvider,
        providerSessionId: String,
        intent: PaymentIntent,
        contributionId: Uuid?,
        memberId: Uuid,
        amount: BigDecimal,
        currency: String,
        donorCategory: DonorCategory?,
        purpose: String?,
        createdAt: LocalDateTime,
        expiresAt: LocalDateTime,
        providerIdempotencyKey: String,
        redirectUrl: String?,
    ) {
        PaymentCheckoutSessionTable.insert {
            it[PaymentCheckoutSessionTable.id] = id
            it[PaymentCheckoutSessionTable.provider] = provider
            it[PaymentCheckoutSessionTable.providerSessionId] = providerSessionId
            it[status] = PaymentCheckoutSessionStatus.CREATED
            it[PaymentCheckoutSessionTable.intent] = intent
            it[PaymentCheckoutSessionTable.contributionId] = contributionId
            it[PaymentCheckoutSessionTable.memberId] = memberId
            it[PaymentCheckoutSessionTable.amount] = amount
            it[PaymentCheckoutSessionTable.currency] = currency
            it[PaymentCheckoutSessionTable.donorCategory] = donorCategory
            it[PaymentCheckoutSessionTable.purpose] = purpose
            it[PaymentCheckoutSessionTable.createdAt] = createdAt
            it[PaymentCheckoutSessionTable.expiresAt] = expiresAt
            it[completedAt] = null
            it[PaymentCheckoutSessionTable.providerIdempotencyKey] = providerIdempotencyKey
            it[PaymentCheckoutSessionTable.redirectUrl] = redirectUrl
        }
    }

    fun findById(id: Uuid): ResultRow? =
        PaymentCheckoutSessionTable.selectAll().where { PaymentCheckoutSessionTable.id eq id }.singleOrNull()

    /** `forUpdate()` row lock -- see `PspWebhookIngestion` KDoc step 2 for why the webhook path locks this row before reconciling. */
    fun findByProviderSessionForUpdate(
        provider: PaymentProvider,
        providerSessionId: String,
    ): ResultRow? =
        PaymentCheckoutSessionTable
            .selectAll()
            .where {
                (PaymentCheckoutSessionTable.provider eq provider) and
                    (PaymentCheckoutSessionTable.providerSessionId eq providerSessionId)
            }.forUpdate()
            .singleOrNull()

    /**
     * The most recent non-expired `CREATED` session for [contributionId] -- used by
     * `createContributionCheckout` to reuse an existing session instead of minting a second Stripe
     * checkout for the same contribution.
     */
    fun findReusableForContribution(
        contributionId: Uuid,
        now: LocalDateTime,
    ): ResultRow? =
        PaymentCheckoutSessionTable
            .selectAll()
            .where {
                (PaymentCheckoutSessionTable.contributionId eq contributionId) and
                    (PaymentCheckoutSessionTable.status eq PaymentCheckoutSessionStatus.CREATED) and
                    (PaymentCheckoutSessionTable.expiresAt greater now)
            }.orderBy(PaymentCheckoutSessionTable.createdAt, SortOrder.DESC)
            .limit(1)
            .singleOrNull()

    fun markCompleted(
        id: Uuid,
        completedAt: LocalDateTime,
    ) {
        PaymentCheckoutSessionTable.update({ PaymentCheckoutSessionTable.id eq id }) {
            it[status] = PaymentCheckoutSessionStatus.COMPLETED
            it[PaymentCheckoutSessionTable.completedAt] = completedAt
            // CheckoutSessionDto.redirectUrl KDoc: null once the session is no longer CREATED.
            it[redirectUrl] = null
        }
    }

    /** Only flips a still-`CREATED` session -- a session already `COMPLETED` must never be downgraded to `EXPIRED` by a late/out-of-order `checkout.session.expired` delivery. */
    fun markExpiredIfStillCreated(
        provider: PaymentProvider,
        providerSessionId: String,
    ): Int =
        PaymentCheckoutSessionTable.update({
            (PaymentCheckoutSessionTable.provider eq provider) and
                (PaymentCheckoutSessionTable.providerSessionId eq providerSessionId) and
                (PaymentCheckoutSessionTable.status eq PaymentCheckoutSessionStatus.CREATED)
        }) {
            it[status] = PaymentCheckoutSessionStatus.EXPIRED
            it[redirectUrl] = null
        }
}
