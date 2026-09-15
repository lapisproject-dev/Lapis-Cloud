package network.lapis.cloud.server.payment.psp

import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.server.db.generated.ExternalDonorTable
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
import org.jetbrains.exposed.v1.core.isNotNull
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.jdbc.deleteWhere
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
     *
     * **Exactly one payer identity, Welle V1.4.3.1** (widened from the two-way V1.4.1b XOR):
     * exactly one of [memberId]/[externalDonorId]/[eventRegistrationId] must be non-null -- mirrors
     * the `chk_payment_checkout_session_payer_identity` CHECK constraint (`V18__events.sql`). No
     * parameter carries a default value, so every call site must write out
     * `externalDonorId = null`/`eventRegistrationId = null`/`embedOrigin = null` explicitly for
     * whichever paths do not apply -- deliberate, this is a money path where a hidden default is
     * the wrong ergonomics. [embedOrigin] must be `null` unless [externalDonorId] or
     * [eventRegistrationId] is set (`chk_payment_checkout_session_embed_origin_external`).
     */
    fun create(
        id: Uuid,
        provider: PaymentProvider,
        providerSessionId: String,
        intent: PaymentIntent,
        contributionId: Uuid?,
        memberId: Uuid?,
        externalDonorId: Uuid?,
        eventRegistrationId: Uuid?,
        embedOrigin: String?,
        amount: BigDecimal,
        currency: String,
        donorCategory: DonorCategory?,
        purpose: String?,
        createdAt: LocalDateTime,
        expiresAt: LocalDateTime,
        providerIdempotencyKey: String,
        redirectUrl: String?,
    ) {
        require(listOfNotNull(memberId, externalDonorId, eventRegistrationId).size == 1) {
            "exactly one of memberId/externalDonorId/eventRegistrationId must be set (was memberId=$memberId, " +
                "externalDonorId=$externalDonorId, eventRegistrationId=$eventRegistrationId)"
        }
        require(embedOrigin == null || externalDonorId != null || eventRegistrationId != null) {
            "embedOrigin must be null unless externalDonorId or eventRegistrationId is set"
        }
        PaymentCheckoutSessionTable.insert {
            it[PaymentCheckoutSessionTable.id] = id
            it[PaymentCheckoutSessionTable.provider] = provider
            it[PaymentCheckoutSessionTable.providerSessionId] = providerSessionId
            it[status] = PaymentCheckoutSessionStatus.CREATED
            it[PaymentCheckoutSessionTable.intent] = intent
            it[PaymentCheckoutSessionTable.contributionId] = contributionId
            it[PaymentCheckoutSessionTable.memberId] = memberId
            it[PaymentCheckoutSessionTable.externalDonorId] = externalDonorId
            it[PaymentCheckoutSessionTable.eventRegistrationId] = eventRegistrationId
            it[PaymentCheckoutSessionTable.embedOrigin] = embedOrigin
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
     * The most recent non-expired `CREATED` session for [contributionId] under [provider] -- used by
     * `createContributionCheckout` to reuse an existing session instead of minting a second checkout
     * for the same contribution.
     *
     * **Provider-scoped (Review round 2, MAJOR fix, Welle V1.2.8b)**: now that the org's selected
     * gateway is dynamic and two providers can coexist, a session minted under a previously-selected
     * provider must NOT be handed back once the org has since switched to a different one -- the
     * caller's freshly resolved [PspCheckoutGateway] would otherwise never even be consulted, and the
     * returned `redirectUrl` would point at the stale provider's checkout page. Every call site must
     * pass the provider of the [PspCheckoutGateway] it just resolved (`client.provider`).
     */
    fun findReusableForContribution(
        contributionId: Uuid,
        provider: PaymentProvider,
        now: LocalDateTime,
    ): ResultRow? =
        PaymentCheckoutSessionTable
            .selectAll()
            .where {
                (PaymentCheckoutSessionTable.contributionId eq contributionId) and
                    (PaymentCheckoutSessionTable.provider eq provider) and
                    (PaymentCheckoutSessionTable.status eq PaymentCheckoutSessionStatus.CREATED) and
                    (PaymentCheckoutSessionTable.expiresAt greater now)
            }.orderBy(PaymentCheckoutSessionTable.createdAt, SortOrder.DESC)
            .limit(1)
            .singleOrNull()

    /**
     * The most recent non-expired `CREATED` session for [eventRegistrationId] under [provider] --
     * same "reuse instead of minting a second checkout" idiom [findReusableForContribution] already
     * establishes, used by `EventRegistrationSubmission.startStripeCheckout` (Review MINOR fix, Welle
     * events-core-Runde-3): without this, two clicks on the same payment-resume link within one hold
     * window each created their own session, and nothing ever superseded the older one.
     *
     * **Provider-scoped (Review round 2, MAJOR fix, Welle V1.2.8b)** -- same reasoning as
     * [findReusableForContribution]'s KDoc; pass `client.provider` of the freshly resolved gateway.
     */
    fun findReusableForRegistration(
        eventRegistrationId: Uuid,
        provider: PaymentProvider,
        now: LocalDateTime,
    ): ResultRow? =
        PaymentCheckoutSessionTable
            .selectAll()
            .where {
                (PaymentCheckoutSessionTable.eventRegistrationId eq eventRegistrationId) and
                    (PaymentCheckoutSessionTable.provider eq provider) and
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

    /**
     * Welle V1.2.8b "PayPal-Anbindung" (GitHub Issue #6), Implementierungsplan §1.3 -- PayPal
     * sendet KEIN Äquivalent zu `checkout.session.expired`, also ist [markExpiredIfStillCreated]
     * hier keine verlässliche Aufräum-Auslösung. Löscht noch `CREATED`-Sitzungen von [provider],
     * deren `expires_at < now` UND deren `externalDonorId` nicht `null` ist, zusammen mit der
     * gepaarten `external_donor`-Zeile -- exakt dieselbe Zwei-Zeilen-Löschung, die
     * `PspWebhookIngestion.ingestCheckoutExpired` bereits für Stripe durchführt. Anders als bei
     * Stripe ist [PaymentCheckoutSessionTable.expiresAt] hier END-ZU-ENDE vertrauenswürdig
     * (`LAPIS_PSP_CHECKOUT_TTL_MINUTES` ist bei PayPal eine Obergrenze, kein konkurrierender eigener
     * Taktgeber, siehe [PspCheckoutGateway.sessionLifetimeCap] KDoc) -- deshalb ist diese Funktion
     * NUR für PayPal sicher, nicht für Stripe (dessen `expires_at` NICHT Stripes eigene 24h-Session-
     * Ablaufzeit widerspiegelt).
     *
     * Aufgerufen opportunistisch von [AnonymousDonationCheckout.create] Schritt 6 (vor dem Minten
     * einer neuen Sitzung) und vom PayPal-Webhook-Handler, nach [PspWebhookEventLog.record].
     * `limit` deckelt die Anzahl pro Aufruf (DoS-Schutz, kein unbegrenztes Batch-Delete).
     */
    fun sweepExpiredAnonymousSessions(
        provider: PaymentProvider,
        now: LocalDateTime,
        limit: Int = 50,
    ): Int {
        val candidates =
            PaymentCheckoutSessionTable
                .selectAll()
                .where {
                    (PaymentCheckoutSessionTable.provider eq provider) and
                        (PaymentCheckoutSessionTable.status eq PaymentCheckoutSessionStatus.CREATED) and
                        (PaymentCheckoutSessionTable.expiresAt less now) and
                        (PaymentCheckoutSessionTable.externalDonorId.isNotNull())
                }.limit(limit)
                .map { it[PaymentCheckoutSessionTable.id] to it[PaymentCheckoutSessionTable.externalDonorId] }
        var deleted = 0
        for ((sessionId, externalDonorId) in candidates) {
            if (externalDonorId == null) continue
            PaymentCheckoutSessionTable.deleteWhere { PaymentCheckoutSessionTable.id eq sessionId }
            ExternalDonorTable.deleteWhere { ExternalDonorTable.id eq externalDonorId }
            deleted++
        }
        return deleted
    }
}
