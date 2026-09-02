package network.lapis.cloud.server.rpc

import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.server.webhook.WebhookEventPublisher
import network.lapis.cloud.server.webhook.WebhookPayloads
import network.lapis.cloud.shared.domain.WebhookEventType
import java.math.BigDecimal
import kotlin.uuid.Uuid

/**
 * Welle V1.3.2 "Webhooks" (ausgehend) -- the ONE shared `contribution.paid` publish call site,
 * used by [ContributionService.markContributionPaid] (manual settlement),
 * [network.lapis.cloud.server.rpc.SepaService]'s own SEPA-collection settlement path, and
 * [network.lapis.cloud.server.payment.psp.PspWebhookIngestion]'s Stripe CONTRIBUTION branch.
 * Deliberately NOT called from inside [ContributionPostingBridge] itself -- that bridge can
 * legitimately return without posting (unconfigured account mapping) while the contribution's own
 * `status` column was already flipped to `PAID` in the SAME transaction; the webhook event
 * represents "this contribution is now PAID" (a fact about `contribution.status`, mirrored by
 * `/api/v1` nowhere directly but implied by the `contribution.paid` event existing at all), not
 * "the accounting posting succeeded" -- see [WebhookEventPublisher] class KDoc "Fat event" for why
 * this event carries its own self-contained payload instead of pointing at a resource.
 *
 * Must be called from inside the caller's own already-open `transaction {}`, immediately after the
 * `ContributionTable.update` that actually flips the status (same transactional-outbox contract
 * [WebhookEventPublisher.publish] itself documents).
 */
internal object ContributionPaymentEvents {
    fun publishPaid(
        contributionId: Uuid,
        paidAt: LocalDateTime,
        amount: BigDecimal,
        transactionId: String,
    ) {
        WebhookEventPublisher.publish(
            eventType = WebhookEventType.CONTRIBUTION_PAID,
            entityId = contributionId,
            occurredAt = paidAt,
            payment =
                WebhookPayloads.PaymentEventDetails(
                    amount = amount,
                    currency = "EUR",
                    transactionId = transactionId,
                ),
        )
    }
}
