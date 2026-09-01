package network.lapis.cloud.server.payment.psp

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Welle V1.2.8 "PSP-Checkout (Stripe)" (GitHub Issue #6) -- the narrow slice of Stripe's own wire
 * shapes this codebase actually reads/writes. Deliberately NOT a full Stripe SDK model -- only the
 * fields `PspWebhookIngestion`/`StripeCheckoutClient` actually consume. `ignoreUnknownKeys = true`
 * so a future Stripe payload field never breaks decoding; `isLenient = false` -- a webhook body is
 * attacker-reachable input and must be decoded strictly (no unquoted keys/loose numbers).
 */
internal val STRIPE_JSON =
    Json {
        ignoreUnknownKeys = true
        isLenient = false
    }

/** The top-level Stripe Event envelope a webhook delivery carries. */
@Serializable
internal data class StripeWebhookEvent(
    val id: String,
    val type: String,
    val data: StripeEventData,
)

@Serializable
internal data class StripeEventData(
    @SerialName("object") val eventObject: StripeCheckoutSessionObject,
)

/**
 * The `checkout.session.*` object embedded in a webhook event. `amountTotal` is Stripe's own
 * integer MINOR-UNITS total (e.g. `1234` = 12.34) -- converted to a scale-2 [java.math.BigDecimal]
 * by the caller, NEVER via `Double`. `clientReferenceId` carries this server's own
 * `payment_checkout_session.id` (set at session-creation time), the join key back to the
 * server-authoritative row.
 */
@Serializable
internal data class StripeCheckoutSessionObject(
    val id: String,
    @SerialName("client_reference_id") val clientReferenceId: String? = null,
    @SerialName("payment_intent") val paymentIntent: String? = null,
    @SerialName("amount_total") val amountTotal: Long? = null,
    val currency: String? = null,
    @SerialName("customer") val customer: String? = null,
    @SerialName("customer_details") val customerDetails: StripeCustomerDetails? = null,
)

@Serializable
internal data class StripeCustomerDetails(
    val email: String? = null,
)

/** The response body of a successful `POST /v1/checkout/sessions` call. */
@Serializable
internal data class StripeCheckoutSessionResponse(
    val id: String,
    val url: String? = null,
)

/** Stripe's own error envelope (`{"error": {"message": ..., "type": ...}}`) on a 4xx/5xx response. */
@Serializable
internal data class StripeErrorEnvelope(
    val error: StripeErrorDetail? = null,
)

@Serializable
internal data class StripeErrorDetail(
    val message: String? = null,
    val type: String? = null,
    val code: String? = null,
)
