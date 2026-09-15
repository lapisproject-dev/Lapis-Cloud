package network.lapis.cloud.server.payment.psp

import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import network.lapis.cloud.server.events.EventPolicy
import network.lapis.cloud.shared.domain.PaymentProvider
import java.io.IOException
import java.math.BigDecimal
import java.math.RoundingMode
import java.security.SecureRandom

private val logger = KotlinLogging.logger {}

/** Outcome of [PaypalOrdersClient.captureOrder]. */
internal sealed interface PaypalCaptureResult {
    data class Captured(
        val captureId: String,
        val amount: BigDecimal?,
        val currency: String?,
    ) : PaypalCaptureResult

    /** PayPal antwortete `422` mit `details[].issue == "ORDER_ALREADY_CAPTURED"` -- ein No-op, kein Fehler. */
    data object AlreadyCaptured : PaypalCaptureResult

    data class Failed(
        val statusCode: Int,
        val message: String,
    ) : PaypalCaptureResult
}

/** Outcome of [PaypalOrdersClient.verifyWebhookSignature]. */
internal sealed interface PaypalVerifyResult {
    data object Verified : PaypalVerifyResult

    data object NotVerified : PaypalVerifyResult

    /** Die Verify-API selbst war nicht erreichbar/antwortete mit einem Server-Fehler -- der Aufrufer antwortet `503`, PayPal liefert erneut zu (siehe Entscheidung §1.1 im Implementierungsplan). */
    data class Unavailable(
        val statusCode: Int,
    ) : PaypalVerifyResult
}

/**
 * Welle V1.2.8b "PayPal-Anbindung" (GitHub Issue #6) -- die drei outbound HTTP-Aufrufe dieses
 * Codebases an PayPal: `POST /v2/checkout/orders`, `POST /v2/checkout/orders/{id}/capture`,
 * `POST /v1/notifications/verify-webhook-signature`. Implementiert [PspCheckoutGateway], exakt
 * dieselbe Disziplin wie [StripeCheckoutClient]: niemals das Client-Secret/Access-Token/den
 * `Authorization`-Header/den rohen Antwort-Body loggen -- nur einen Statuscode und, bei einem
 * Fehlschlag, PayPals eigene sanitisierte `message`/`details[].issue`.
 *
 * **Abweichende Asymmetrie (Implementierungsplan §1.3)**: anders als Stripe honoriert
 * [PspConfig.checkoutTtlMinutes]/[PaypalConfig.checkoutTtlMinutes] bei PayPal END-ZU-ENDE, weil
 * PayPals eigene ~3h-Order-Ablaufzeit ([sessionLifetimeCap]) eine OBERGRENZE ist, kein
 * konkurrierender eigener Taktgeber wie bei Stripes ~24h-Session. Siehe [PspCheckoutGateway
 * .sessionLifetimeCap] KDoc.
 */
class PaypalOrdersClient(
    private val config: PaypalConfig,
    private val httpClient: HttpClient = defaultPspHttpClient(),
    private val tokenProvider: PaypalAccessTokenProvider = PaypalAccessTokenProvider(config = config, httpClient = httpClient),
) : PspCheckoutGateway {
    override val provider: PaymentProvider = PaymentProvider.PAYPAL
    override val maxCheckoutAmountEur: BigDecimal get() = config.maxCheckoutAmountEur
    override val checkoutTtlMinutes: Long get() = config.checkoutTtlMinutes
    override val sessionLifetimeCap get() = EventPolicy.PAYPAL_SESSION_LIFETIME_CAP

    /**
     * `POST /v2/checkout/orders`, `intent=CAPTURE`, EIN `purchase_unit`, `custom_id =
     * checkoutSessionId`, `amount.value = amount.setScale(2).toPlainString()` (NIEMALS über
     * [Double]), `currency_code = currency`. `experience_context.return_url`/`cancel_url` =
     * [returnUrls]. Header `PayPal-Request-Id` = frischer zufälliger 16-Byte-Hex-Wert (gleiche
     * Disziplin wie Stripes `Idempotency-Key` -- ein frischer Wert pro logischem Checkout; der
     * AUFRUFER ist für Session-Wiederverwendung verantwortlich).
     */
    override suspend fun createCheckout(
        checkoutSessionId: String,
        amount: BigDecimal,
        currency: String,
        description: String,
        returnUrls: PspReturnUrls,
    ): PspCheckoutResult {
        val token =
            tokenProvider.accessTokenOrNull() ?: return PspCheckoutResult.Failure(statusCode = 0, message = "PayPal-Token nicht verfügbar")
        // Fix (Review round 4, nit): `setScale(2, RoundingMode.UNNECESSARY)` throws an uncaught
        // ArithmeticException for any amount with more than 2 fractional digits. Every current call
        // site pre-validates/pre-scales its amount, so this is unreachable today -- but this PSP
        // client otherwise guards every other malformed-input path with a clean
        // PspCheckoutResult.Failure (see the rest of this function), so a future call site that
        // forgets to pre-scale should get the same discipline instead of a raw 500.
        val amountValue =
            runCatching { amount.setScale(2, RoundingMode.UNNECESSARY).toPlainString() }.getOrNull()
                ?: return PspCheckoutResult.Failure(statusCode = 0, message = "Betrag hat mehr als 2 Nachkommastellen")
        // Fix (Review round 1, MAJOR): generated ONCE here and reused both as the actually-sent
        // `PayPal-Request-Id` header value below AND as the returned idempotencyKey -- previously
        // the header used a freshly generated value that was then discarded and "n/a" was returned
        // instead, breaking the PspCheckoutGateway.idempotencyKey contract (persisted onto
        // payment_checkout_session.provider_idempotency_key for forensic/support reconciliation
        // against PayPal's own request-id, exactly like StripeCheckoutClient already honors it).
        val requestId = randomRequestId()
        val requestBody =
            buildJsonObject {
                put("intent", "CAPTURE")
                put(
                    "purchase_units",
                    buildJsonArray {
                        add(
                            buildJsonObject {
                                put("custom_id", checkoutSessionId)
                                put("description", description)
                                put(
                                    "amount",
                                    buildJsonObject {
                                        put("currency_code", currency)
                                        put("value", amountValue)
                                    },
                                )
                            },
                        )
                    },
                )
                put(
                    "experience_context",
                    buildJsonObject {
                        put("return_url", returnUrls.successUrl)
                        put("cancel_url", returnUrls.cancelUrl)
                    },
                )
            }
        val response =
            try {
                httpClient.post("${config.apiBaseUrl}/v2/checkout/orders") {
                    header("Authorization", "Bearer $token")
                    header("PayPal-Request-Id", requestId)
                    contentType(ContentType.Application.Json)
                    setBody(PAYPAL_JSON.encodeToString(JsonObject.serializer(), requestBody))
                }
            } catch (e: IOException) {
                logger.warn(e) { "PaypalOrdersClient: network failure calling POST /v2/checkout/orders" }
                return PspCheckoutResult.Failure(statusCode = 0, message = "Netzwerkfehler beim Aufruf von PayPal")
            }
        val bodyBytes = response.readCappedPspBody()
        if (response.status.value !in 200..299) {
            logger.warn { "PaypalOrdersClient: non-2xx response from POST /v2/checkout/orders (status=${response.status.value})" }
            return PspCheckoutResult.Failure(
                statusCode = response.status.value,
                message = errorMessageOf(bodyBytes = bodyBytes, statusCode = response.status.value),
            )
        }
        val parsed =
            bodyBytes?.let {
                runCatching { PAYPAL_JSON.decodeFromString(PaypalOrderResponse.serializer(), it.toString(Charsets.UTF_8)) }.getOrNull()
            }
        val redirectUrl =
            parsed?.links?.firstOrNull { it.rel == "payer-action" }?.href ?: parsed?.links?.firstOrNull { it.rel == "approve" }?.href
        if (parsed == null || redirectUrl == null) {
            logger.warn { "PaypalOrdersClient: 2xx response but unparseable body/missing approval link (status=${response.status.value})" }
            return PspCheckoutResult.Failure(statusCode = response.status.value, message = "Unerwartete Antwort von PayPal")
        }
        return PspCheckoutResult.Success(sessionId = parsed.id, redirectUrl = redirectUrl, idempotencyKey = requestId)
    }

    /**
     * `POST /v2/checkout/orders/{orderId}/capture`. `PayPal-Request-Id = "capture-$orderId"`
     * (STABIL, nicht zufällig -- ein Retry DERSELBEN Capture muss absichtlich kollidieren). `422`
     * mit `details[].issue == "ORDER_ALREADY_CAPTURED"` -> [PaypalCaptureResult.AlreadyCaptured].
     */
    internal suspend fun captureOrder(orderId: String): PaypalCaptureResult {
        val token =
            tokenProvider.accessTokenOrNull() ?: return PaypalCaptureResult.Failed(statusCode = 0, message = "PayPal-Token nicht verfügbar")
        val response =
            try {
                httpClient.post("${config.apiBaseUrl}/v2/checkout/orders/$orderId/capture") {
                    header("Authorization", "Bearer $token")
                    header("PayPal-Request-Id", "capture-$orderId")
                    contentType(ContentType.Application.Json)
                    setBody("{}")
                }
            } catch (e: IOException) {
                logger.warn(e) { "PaypalOrdersClient: network failure calling POST /v2/checkout/orders/{id}/capture" }
                return PaypalCaptureResult.Failed(statusCode = 0, message = "Netzwerkfehler beim Aufruf von PayPal")
            }
        val bodyBytes = response.readCappedPspBody()
        if (response.status.value == 422) {
            val alreadyCaptured =
                bodyBytes
                    ?.let {
                        runCatching {
                            PAYPAL_JSON.decodeFromString(
                                PaypalErrorEnvelope.serializer(),
                                it.toString(Charsets.UTF_8),
                            )
                        }.getOrNull()
                    }?.details
                    ?.any { it.issue == "ORDER_ALREADY_CAPTURED" }
                    ?: false
            if (alreadyCaptured) return PaypalCaptureResult.AlreadyCaptured
        }
        if (response.status.value !in 200..299) {
            logger.warn { "PaypalOrdersClient: non-2xx response capturing order $orderId (status=${response.status.value})" }
            return PaypalCaptureResult.Failed(
                statusCode = response.status.value,
                message = errorMessageOf(bodyBytes = bodyBytes, statusCode = response.status.value),
            )
        }
        val parsed =
            bodyBytes?.let {
                runCatching { PAYPAL_JSON.decodeFromString(PaypalCaptureResponse.serializer(), it.toString(Charsets.UTF_8)) }.getOrNull()
            }
        val capture =
            parsed
                ?.purchaseUnits
                ?.firstOrNull()
                ?.payments
                ?.captures
                ?.firstOrNull()
        if (parsed == null || capture == null) {
            logger.warn { "PaypalOrdersClient: 2xx capture response but unparseable body (status=${response.status.value})" }
            return PaypalCaptureResult.Failed(statusCode = response.status.value, message = "Unerwartete Antwort von PayPal")
        }
        return PaypalCaptureResult.Captured(
            captureId = capture.id,
            amount = paypalAmountToDecimal(capture.amount?.value),
            currency = capture.amount?.currencyCode,
        )
    }

    /**
     * `POST /v1/notifications/verify-webhook-signature`. Body trägt die fünf `PAYPAL-*`-
     * Header-Werte + `config.webhookId` + den ROHEN Body als bereits vorab geparstes JSON-Element
     * (siehe Implementierungsplan-Pitfall §6.4: der rohe Body wird EINMAL zu einem [JsonElement]
     * geparst und unverändert als `webhook_event` eingebettet, NIEMALS re-stringifiziert, damit die
     * Bytes, die PayPal signiert hat, exakt erhalten bleiben).
     */
    internal suspend fun verifyWebhookSignature(
        headers: PaypalTransmissionHeaders,
        rawEvent: JsonElement,
    ): PaypalVerifyResult {
        val token = tokenProvider.accessTokenOrNull() ?: return PaypalVerifyResult.Unavailable(statusCode = 0)
        val requestBody =
            buildJsonObject {
                put("transmission_id", headers.transmissionId)
                put("transmission_time", headers.transmissionTime)
                put("cert_url", headers.certUrl)
                put("auth_algo", headers.authAlgo)
                put("transmission_sig", headers.transmissionSig)
                put("webhook_id", config.webhookId)
                put("webhook_event", rawEvent)
            }
        val response =
            try {
                httpClient.post("${config.apiBaseUrl}/v1/notifications/verify-webhook-signature") {
                    header("Authorization", "Bearer $token")
                    contentType(ContentType.Application.Json)
                    setBody(PAYPAL_JSON.encodeToString(JsonObject.serializer(), requestBody))
                }
            } catch (e: IOException) {
                logger.warn(e) { "PaypalOrdersClient: network failure calling POST /v1/notifications/verify-webhook-signature" }
                return PaypalVerifyResult.Unavailable(statusCode = 0)
            }
        val bodyBytes = response.readCappedPspBody()
        if (response.status.value !in 200..299) {
            logger.warn { "PaypalOrdersClient: non-2xx response verifying webhook signature (status=${response.status.value})" }
            return PaypalVerifyResult.Unavailable(statusCode = response.status.value)
        }
        val parsed =
            bodyBytes?.let {
                runCatching { PAYPAL_JSON.decodeFromString(PaypalVerifyResponse.serializer(), it.toString(Charsets.UTF_8)) }.getOrNull()
            }
        return when (parsed?.verificationStatus) {
            "SUCCESS" -> PaypalVerifyResult.Verified
            "FAILURE" -> PaypalVerifyResult.NotVerified
            else -> PaypalVerifyResult.Unavailable(statusCode = response.status.value)
        }
    }

    private fun errorMessageOf(
        bodyBytes: ByteArray?,
        statusCode: Int,
    ): String =
        bodyBytes
            ?.let {
                runCatching {
                    PAYPAL_JSON.decodeFromString(
                        PaypalErrorEnvelope.serializer(),
                        it.toString(Charsets.UTF_8),
                    )
                }.getOrNull()
            }?.let { it.message ?: it.details.firstOrNull()?.issue }
            ?: "PayPal hat die Anfrage abgelehnt (Status $statusCode)"

    companion object {
        private val requestIdRandom = SecureRandom()

        private fun randomRequestId(): String {
            val bytes = ByteArray(16)
            requestIdRandom.nextBytes(bytes)
            return bytes.joinToString(separator = "") { byte -> "%02x".format(byte) }
        }
    }
}
