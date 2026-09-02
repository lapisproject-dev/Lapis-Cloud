package network.lapis.cloud.server.webhook

import kotlinx.datetime.LocalDateTime
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import network.lapis.cloud.shared.domain.WebhookEventType
import java.math.BigDecimal
import kotlin.uuid.Uuid

/**
 * Welle V1.3.2 "Webhooks" (ausgehend) -- builds the exact JSON body [WebhookSigner.sign] signs and
 * [WebhookDeliveryQueue.insert] persists into `webhook_delivery.payload` -- built EXACTLY ONCE, at
 * publish time (see [WebhookEventPublisher.publish] KDoc "S4" for why this must never be rebuilt on
 * retry).
 *
 * **Thin** (every event except [PaymentEventDetails]-bearing ones): `id`/`eventType`/`entityId`/
 * `occurredAt` only -- a receiver must fetch the current state via `/api/v1` itself (Design-Team
 * decision D8, see [WebhookEventPublisher] class KDoc). `id` is the ADDITION over a bare Stripe-
 * style thin payload: `webhook_delivery.event_id`, stable across every retry attempt (the receiver-
 * visible idempotency key, ALSO carried as the `Lapis-Webhook-Id` header).
 *
 * **Fat** (payments only, the one documented exception to D8 -- see [WebhookEventPublisher] KDoc):
 * adds `amount`/`currency`/`transactionId`. `amount` is a DECIMAL STRING, scale 2 (`"42.00"`),
 * NEVER a bare JSON number -- floating-point JSON numbers are not exact for currency, and this
 * matches [network.lapis.cloud.shared.domain.PaymentTransactionSnapshot.amount]'s own `Decimal`
 * (string-backed) wire convention. Structurally excludes `memberId`/`payerReference`/
 * `donorCategory`/name/e-mail/address/IBAN -- no field exists for Kotlin to accidentally populate.
 */
internal object WebhookPayloads {
    /** Payment-specific fields for the two Fat events -- see class KDoc. */
    data class PaymentEventDetails(
        val amount: BigDecimal,
        val currency: String,
        val transactionId: String,
    )

    fun build(
        eventId: Uuid,
        eventType: WebhookEventType,
        entityId: Uuid,
        occurredAt: LocalDateTime,
        payment: PaymentEventDetails?,
    ): String {
        val json =
            buildJsonObject {
                put("id", eventId.toString())
                put("eventType", eventType.wireName)
                put("entityId", entityId.toString())
                put("occurredAt", occurredAt.toString())
                // A Thin event (payment == null) simply omits amount/currency/transactionId
                // entirely -- not even as a JSON null -- so a Thin payload's field set stays the
                // living documentation of Design-Team decision D8 for every consumer parsing the
                // JSON generically.
                if (payment != null) {
                    put("amount", payment.amount.setScale(2, java.math.RoundingMode.HALF_UP).toPlainString())
                    put("currency", payment.currency)
                    put("transactionId", payment.transactionId)
                }
            }
        return json.toString()
    }
}
