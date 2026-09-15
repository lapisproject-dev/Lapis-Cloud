package network.lapis.cloud.server.payment.psp

import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import network.lapis.cloud.shared.domain.PaymentProvider
import java.io.IOException
import java.math.BigDecimal
import java.net.URLEncoder
import java.security.SecureRandom

private val logger = KotlinLogging.logger {}

/**
 * Welle V1.2.8 "PSP-Checkout (Stripe)" (GitHub Issue #6) -- the ONLY outbound HTTP this codebase
 * makes to Stripe: `POST /v1/checkout/sessions`. Hard timeouts + bounded response read, same
 * `oracleHttpClient()` shape `network.lapis.cloud.server.economy.oracle.OracleHttpClient` already
 * establishes -- `followRedirects = false`, `expectSuccess = false` (every call site inspects the
 * status itself). **Never logs the key, the `Authorization` header, or the raw response body** --
 * only a status code and, on failure, Stripe's own sanitized `error.message` field (which never
 * echoes the request).
 *
 * Constructor default [httpClient] exists for tests only -- `Application.module` MUST pass one
 * shared instance (same "constructed once, held by the caller, never per-request" discipline
 * `oracleHttpClient()`'s own callers establish), never construct a fresh [HttpClient] per RPC call.
 *
 * Welle V1.2.8b "PayPal-Anbindung" (GitHub Issue #6) -- implements [PspCheckoutGateway] so every
 * checkout-creation call site can depend on the provider-neutral abstraction instead of this
 * concrete class; `createCheckoutSession` was renamed to [createCheckout] as part of that move
 * (pure rename, no behaviour change) and `StripeReturnUrls`/`StripeCheckoutResult` moved to
 * `PspCheckoutGateway.kt` as [PspReturnUrls]/[PspCheckoutResult].
 */
class StripeCheckoutClient(
    private val pspConfig: PspConfig,
    private val httpClient: HttpClient = defaultStripeHttpClient(),
) : PspCheckoutGateway {
    override val provider: PaymentProvider = PaymentProvider.STRIPE
    override val maxCheckoutAmountEur: BigDecimal get() = pspConfig.maxCheckoutAmountEur
    override val checkoutTtlMinutes: Long get() = pspConfig.checkoutTtlMinutes

    /**
     * Creates a Stripe Checkout Session for [amount] (EUR, exact decimal, converted to Stripe's own
     * integer MINOR-UNITS `unit_amount` -- e.g. `12.34` -> `1234`, NEVER via [Double]).
     * [checkoutSessionId] is this server's own `payment_checkout_session.id`, sent as Stripe's
     * `client_reference_id` (the join key a webhook delivery carries back). [returnUrls] carries
     * `success_url`/`cancel_url` -- **no default value, Welle V1.4.1b**: every caller must state
     * explicitly where its own donor/payer returns to (a money path -- a hidden default is the
     * wrong ergonomics here). The member path's own [PspReturnUrls.memberSpa] embeds
     * [checkoutSessionId] in the HASH FRAGMENT (never a query parameter -- see `hashQueryParam`
     * precedent, `01-contribution.kuml.kts`/client `Routing.kt`: a hash fragment never reaches a
     * server log or `Referer` header); the embed-widget path's [PspReturnUrls.embedDonation]
     * carries no session identifier at all. `Idempotency-Key` is a fresh random value per call --
     * the CALLER (`PaymentGatewayService`/`AnonymousDonationCheckout`) is responsible for not
     * calling this twice for the same logical checkout (see `PaymentGatewayService`'s own
     * session-reuse guard). Deliberately does NOT send Stripe an `expires_at` form parameter --
     * [PspConfig.checkoutTtlMinutes]'s 10-minute floor sits below Stripe's own 30-minute minimum
     * for that field, so this server's `payment_checkout_session.expires_at` and Stripe's own
     * session expiry are two independent clocks; see [PspConfig.checkoutTtlMinutes] KDoc.
     */
    override suspend fun createCheckout(
        checkoutSessionId: String,
        amount: BigDecimal,
        currency: String,
        description: String,
        returnUrls: PspReturnUrls,
    ): PspCheckoutResult {
        val unitAmountMinorUnits = amount.movePointRight(2).longValueExact()
        val successUrl = returnUrls.successUrl
        val cancelUrl = returnUrls.cancelUrl
        val idempotencyKey = randomIdempotencyKey()

        val formBody =
            listOf(
                "mode" to "payment",
                "client_reference_id" to checkoutSessionId,
                "success_url" to successUrl,
                "cancel_url" to cancelUrl,
                "payment_method_types[0]" to "card",
                "line_items[0][quantity]" to "1",
                "line_items[0][price_data][currency]" to currency.lowercase(),
                "line_items[0][price_data][unit_amount]" to unitAmountMinorUnits.toString(),
                "line_items[0][price_data][product_data][name]" to description,
            ).joinToString(separator = "&") { (key, value) -> "${urlEncode(key)}=${urlEncode(value)}" }

        val response =
            try {
                httpClient.post("${pspConfig.apiBaseUrl}/v1/checkout/sessions") {
                    header("Authorization", "Bearer ${pspConfig.secretKey}")
                    header("Idempotency-Key", idempotencyKey)
                    contentType(ContentType.Application.FormUrlEncoded)
                    setBody(formBody)
                }
            } catch (e: IOException) {
                logger.warn(e) { "StripeCheckoutClient: network failure calling POST /v1/checkout/sessions" }
                return PspCheckoutResult.Failure(statusCode = 0, message = "Netzwerkfehler beim Aufruf von Stripe")
            }

        val bodyBytes = response.readCappedPspBody()
        if (response.status.value in 200..299) {
            val parsed =
                bodyBytes?.let {
                    runCatching { STRIPE_JSON.decodeFromString(StripeCheckoutSessionResponse.serializer(), it.toString(Charsets.UTF_8)) }
                        .getOrNull()
                }
            val redirectUrl = parsed?.url
            if (parsed == null || redirectUrl == null) {
                logger.warn { "StripeCheckoutClient: 2xx response but unparseable body/missing url (status=${response.status.value})" }
                return PspCheckoutResult.Failure(statusCode = response.status.value, message = "Unerwartete Antwort von Stripe")
            }
            return PspCheckoutResult.Success(sessionId = parsed.id, redirectUrl = redirectUrl, idempotencyKey = idempotencyKey)
        }

        val errorMessage =
            bodyBytes
                ?.let {
                    runCatching {
                        STRIPE_JSON.decodeFromString(
                            StripeErrorEnvelope.serializer(),
                            it.toString(Charsets.UTF_8),
                        )
                    }.getOrNull()
                }?.error
                ?.message
                ?: "Stripe hat die Checkout-Erstellung abgelehnt (Status ${response.status.value})"
        logger.warn { "StripeCheckoutClient: non-2xx response (status=${response.status.value})" }
        return PspCheckoutResult.Failure(statusCode = response.status.value, message = errorMessage)
    }

    companion object {
        private val idempotencyRandom = SecureRandom()

        private fun randomIdempotencyKey(): String {
            val bytes = ByteArray(16)
            idempotencyRandom.nextBytes(bytes)
            return bytes.joinToString(separator = "") { byte -> "%02x".format(byte) }
        }

        private fun urlEncode(value: String): String = URLEncoder.encode(value, Charsets.UTF_8)
    }
}
