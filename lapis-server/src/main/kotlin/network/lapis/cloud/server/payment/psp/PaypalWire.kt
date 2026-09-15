package network.lapis.cloud.server.payment.psp

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import network.lapis.cloud.shared.domain.PaymentProvider
import java.math.BigDecimal

/**
 * Welle V1.2.8b "PayPal-Anbindung" (GitHub Issue #6) -- die schmale Draht-Form, die dieser Codebase
 * tatsächlich liest/schreibt. Bewusst KEIN vollständiges PayPal-SDK-Modell -- nur die Felder, die
 * [PaypalOrdersClient]/[PaypalWebhookVerification] tatsächlich konsumieren. `ignoreUnknownKeys =
 * true`, `isLenient = false` -- exakt dieselbe Begründung wie [STRIPE_JSON] KDoc.
 */
internal val PAYPAL_JSON =
    Json {
        ignoreUnknownKeys = true
        isLenient = false
    }

/** `POST /v1/oauth2/token` Antwort. */
@Serializable
internal data class PaypalTokenResponse(
    @SerialName("access_token") val accessToken: String,
    @SerialName("token_type") val tokenType: String? = null,
    @SerialName("expires_in") val expiresIn: Long? = null,
)

/** `POST /v2/checkout/orders` Antwort. */
@Serializable
internal data class PaypalOrderResponse(
    val id: String,
    val status: String? = null,
    val links: List<PaypalLink> = emptyList(),
)

@Serializable
internal data class PaypalLink(
    val href: String,
    val rel: String,
    val method: String? = null,
)

/** `POST /v2/checkout/orders/{id}/capture` Antwort. */
@Serializable
internal data class PaypalCaptureResponse(
    val id: String,
    val status: String? = null,
    @SerialName("purchase_units") val purchaseUnits: List<PaypalCapturePurchaseUnit> = emptyList(),
)

@Serializable
internal data class PaypalCapturePurchaseUnit(
    val payments: PaypalCapturePayments? = null,
)

@Serializable
internal data class PaypalCapturePayments(
    val captures: List<PaypalCaptureDetail> = emptyList(),
)

@Serializable
internal data class PaypalCaptureDetail(
    val id: String,
    val status: String? = null,
    val amount: PaypalAmount? = null,
)

/** Webhook-Umschlag. */
@Serializable
internal data class PaypalWebhookEvent(
    val id: String,
    @SerialName("event_type") val eventType: String,
    @SerialName("create_time") val createTime: String? = null,
    val resource: PaypalWebhookResource,
)

@Serializable
internal data class PaypalWebhookResource(
    /** Order-Id (ORDER.*) oder Capture-Id (PAYMENT.CAPTURE.*). */
    val id: String,
    /** "APPROVED" / "COMPLETED" / "DENIED" / "VOIDED". */
    val status: String? = null,
    /** Trägt unsere `payment_checkout_session.id` (bei Order-Erzeugung gesetzt). */
    @SerialName("custom_id") val customId: String? = null,
    /** Capture-Events. */
    val amount: PaypalAmount? = null,
    /** Order-Events. */
    @SerialName("purchase_units") val purchaseUnits: List<PaypalCapturePurchaseUnit> = emptyList(),
    @SerialName("supplementary_data") val supplementaryData: PaypalSupplementaryData? = null,
    val payer: PaypalPayer? = null,
)

@Serializable
internal data class PaypalSupplementaryData(
    @SerialName("related_ids") val relatedIds: PaypalRelatedIds? = null,
)

@Serializable
internal data class PaypalRelatedIds(
    @SerialName("order_id") val orderId: String? = null,
)

@Serializable
internal data class PaypalAmount(
    @SerialName("currency_code") val currencyCode: String? = null,
    val value: String? = null,
)

@Serializable
internal data class PaypalPayer(
    @SerialName("payer_id") val payerId: String? = null,
)

/** `POST /v1/notifications/verify-webhook-signature` Antwort. */
@Serializable
internal data class PaypalVerifyResponse(
    @SerialName("verification_status") val verificationStatus: String? = null,
)

/** PayPals eigener Fehler-Umschlag. */
@Serializable
internal data class PaypalErrorEnvelope(
    val name: String? = null,
    val message: String? = null,
    val details: List<PaypalErrorDetail> = emptyList(),
)

@Serializable
internal data class PaypalErrorDetail(
    val issue: String? = null,
    val description: String? = null,
)

/** Only a plain, optionally-signed decimal string -- rejects scientific notation (e.g. "1E+2") up front, before scale() is even checked (see [paypalAmountToDecimal] KDoc "rejected if ... not a plain decimal"). */
private val PLAIN_DECIMAL_PATTERN = Regex("^-?\\d+(\\.\\d+)?$")

/**
 * PayPal sendet Dezimal-STRINGS (`"12.34"`), niemals Minor-Units -- das exakte Gegenteil von
 * Stripes ganzzahligem `amount_total`. Geparst via `BigDecimal(String)`, NIEMALS via
 * `Double`/`toDouble()`, und als `null` zurückgewiesen, wenn die Skala > 2 ist oder der String kein
 * reiner Dezimalwert ist.
 */
internal fun paypalAmountToDecimal(value: String?): BigDecimal? {
    if (value == null) return null
    if (!PLAIN_DECIMAL_PATTERN.matches(value)) return null
    val parsed = runCatching { BigDecimal(value) }.getOrNull() ?: return null
    if (parsed.scale() > 2) return null
    return parsed
}

/**
 * Baut das PSP-neutrale [PspPaymentEvent] aus einer bereits erfolgreich verifizierten PayPal-
 * Zustellung -- sowohl `PAYMENT.CAPTURE.*` (resource ist ein Capture) als auch `CHECKOUT.ORDER.*`
 * (resource ist ein Order).
 */
internal fun PaypalWebhookEvent.toPspPaymentEvent(): PspPaymentEvent {
    val resource = this.resource
    // Fix (Review round 4, MAJOR): für ORDER-Events (z.B. CHECKOUT.ORDER.VOIDED) IST `resource.id`
    // bereits die Order-Id -- exakt dieselbe Annahme, die der CHECKOUT.ORDER.APPROVED-Dispatch in
    // PaypalWebhookRoutes.kt trifft, wenn er `event.resource.id` direkt an `captureOrder(orderId)`
    // übergibt. Ein echtes PayPal-Order-Resource trägt weder `custom_id` auf oberster Ebene (das
    // liegt bei einem Order nur verschachtelt in `purchase_units[]`, das PaypalCapturePurchaseUnit
    // gar nicht abbildet) noch `supplementary_data.related_ids` (existiert auf Order-Resources gar
    // nicht -- das ist ausschließlich ein Capture-Feld). Ohne diesen Zweig fällt die Auflösung für
    // jede reale CHECKOUT.ORDER.VOIDED-Zustellung auf den Sentinel zurück, die Session wird nie
    // gefunden und niemals als EXPIRED markiert -- siehe Implementierungsplan §1.3.
    val orderId =
        if (eventType.startsWith("CHECKOUT.ORDER.")) {
            resource.id
        } else {
            resource.supplementaryData?.relatedIds?.orderId ?: resource.customId
        }
    // Fix (Review round 1, MINOR): previously fell back to `resource.id` (the CAPTURE id) when both
    // `supplementary_data.related_ids.order_id` and `custom_id` are absent -- that silently overloads
    // this field with a value from the wrong id-namespace (a capture id is never a
    // `provider_session_id`), relying on the two id formats never coinciding by luck. An explicit,
    // deliberately-never-matching sentinel makes the "session unresolved" case unambiguous instead --
    // the session lookup this feeds (`PspCheckoutSessions`/`PspWebhookIngestion`) already handles "no
    // matching session" as "Unbekannte Checkout-Session" either way, so behavior is unchanged. This
    // fallback now only applies to CAPTURE-shaped events (the ORDER branch above always resolves).
    val unresolvedSessionSentinel = "unresolved-order-id:$id"
    return PspPaymentEvent(
        provider = PaymentProvider.PAYPAL,
        providerEventId = id,
        providerSessionId = orderId ?: unresolvedSessionSentinel,
        providerPaymentId = resource.id,
        amount = paypalAmountToDecimal(resource.amount?.value),
        currency = resource.amount?.currencyCode,
        paymentStatus = if (resource.status == "COMPLETED") "paid" else resource.status,
        payerReference = resource.payer?.payerId,
    )
}
