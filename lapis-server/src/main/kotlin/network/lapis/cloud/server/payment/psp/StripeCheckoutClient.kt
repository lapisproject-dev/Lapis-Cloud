package network.lapis.cloud.server.payment.psp

import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.utils.io.readAvailable
import java.io.IOException
import java.math.BigDecimal
import java.net.URLEncoder
import java.security.SecureRandom

private val logger = KotlinLogging.logger {}

/** Hard cap on how many bytes of a Stripe response body are ever read into memory -- see [readCappedStripeBody]. */
private const val MAX_STRIPE_RESPONSE_BYTES = 64 * 1024

/**
 * Wohin Stripe den Zahlenden nach Erfolg/Abbruch zurückschickt -- a value class instead of two loose
 * `String` parameters so the two can never be transposed at a call site (Welle V1.4.1b). See the two
 * factories for the two shapes this codebase actually produces.
 */
internal data class StripeReturnUrls(
    val successUrl: String,
    val cancelUrl: String,
) {
    companion object {
        /**
         * The unchanged V1.2.8 behaviour for the member path: the session id travels in the HASH
         * FRAGMENT, never a query parameter -- see [StripeCheckoutClient.createCheckoutSession]
         * KDoc "`success_url`/`cancel_url`".
         */
        fun memberSpa(
            baseUrl: String,
            checkoutSessionId: String,
        ): StripeReturnUrls =
            StripeReturnUrls(
                successUrl = "$baseUrl/#/payment-return?session=$checkoutSessionId",
                cancelUrl = "$baseUrl/#/payment-return?session=$checkoutSessionId&cancelled=true",
            )

        /**
         * Welle V1.4.1b -- two fixed, public paths WITHOUT a session identifier: no new token-/
         * polling surface for an anonymous donor. [canonicalOrigin] is ALWAYS the
         * [network.lapis.cloud.server.embed.EmbedOriginAllowlist]-resolved canonical entry, never a
         * raw request-supplied value.
         */
        fun embedDonation(
            baseUrl: String,
            canonicalOrigin: String,
        ): StripeReturnUrls {
            val encodedOrigin = URLEncoder.encode(canonicalOrigin, Charsets.UTF_8)
            return StripeReturnUrls(
                successUrl = "$baseUrl/embed/v1/spende/danke?origin=$encodedOrigin",
                cancelUrl = "$baseUrl/embed/v1/spende/abgebrochen?origin=$encodedOrigin",
            )
        }
    }
}

/** Outcome of [StripeCheckoutClient.createCheckoutSession]. */
sealed interface StripeCheckoutResult {
    data class Success(
        val sessionId: String,
        val redirectUrl: String,
        /** The `Idempotency-Key` value actually sent -- persisted by the caller onto `payment_checkout_session.provider_idempotency_key` for forensics. */
        val idempotencyKey: String,
    ) : StripeCheckoutResult

    /** [statusCode] is the raw HTTP status; [message] is Stripe's own error message when present -- NEVER the request headers/key. */
    data class Failure(
        val statusCode: Int,
        val message: String,
    ) : StripeCheckoutResult
}

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
 */
class StripeCheckoutClient(
    private val pspConfig: PspConfig,
    private val httpClient: HttpClient = defaultStripeHttpClient(),
) {
    /**
     * Creates a Stripe Checkout Session for [amount] (EUR, exact decimal, converted to Stripe's own
     * integer MINOR-UNITS `unit_amount` -- e.g. `12.34` -> `1234`, NEVER via [Double]).
     * [checkoutSessionId] is this server's own `payment_checkout_session.id`, sent as Stripe's
     * `client_reference_id` (the join key a webhook delivery carries back). [returnUrls] carries
     * `success_url`/`cancel_url` -- **no default value, Welle V1.4.1b**: every caller must state
     * explicitly where its own donor/payer returns to (a money path -- a hidden default is the
     * wrong ergonomics here). The member path's own [StripeReturnUrls.memberSpa] embeds
     * [checkoutSessionId] in the HASH FRAGMENT (never a query parameter -- see `hashQueryParam`
     * precedent, `01-contribution.kuml.kts`/client `Routing.kt`: a hash fragment never reaches a
     * server log or `Referer` header); the embed-widget path's [StripeReturnUrls.embedDonation]
     * carries no session identifier at all. `Idempotency-Key` is a fresh random value per call --
     * the CALLER (`PaymentGatewayService`/`AnonymousDonationCheckout`) is responsible for not
     * calling this twice for the same logical checkout (see `PaymentGatewayService`'s own
     * session-reuse guard). Deliberately does NOT send Stripe an `expires_at` form parameter --
     * [PspConfig.checkoutTtlMinutes]'s 10-minute floor sits below Stripe's own 30-minute minimum
     * for that field, so this server's `payment_checkout_session.expires_at` and Stripe's own
     * session expiry are two independent clocks; see [PspConfig.checkoutTtlMinutes] KDoc.
     */
    internal suspend fun createCheckoutSession(
        checkoutSessionId: String,
        amount: BigDecimal,
        currency: String,
        description: String,
        returnUrls: StripeReturnUrls,
    ): StripeCheckoutResult {
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
                return StripeCheckoutResult.Failure(statusCode = 0, message = "Netzwerkfehler beim Aufruf von Stripe")
            }

        val bodyBytes = response.readCappedStripeBody()
        if (response.status.value in 200..299) {
            val parsed =
                bodyBytes?.let {
                    runCatching { STRIPE_JSON.decodeFromString(StripeCheckoutSessionResponse.serializer(), it.toString(Charsets.UTF_8)) }
                        .getOrNull()
                }
            val redirectUrl = parsed?.url
            if (parsed == null || redirectUrl == null) {
                logger.warn { "StripeCheckoutClient: 2xx response but unparseable body/missing url (status=${response.status.value})" }
                return StripeCheckoutResult.Failure(statusCode = response.status.value, message = "Unerwartete Antwort von Stripe")
            }
            return StripeCheckoutResult.Success(sessionId = parsed.id, redirectUrl = redirectUrl, idempotencyKey = idempotencyKey)
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
        return StripeCheckoutResult.Failure(statusCode = response.status.value, message = errorMessage)
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

/**
 * A hardened [HttpClient] for [StripeCheckoutClient] -- same `followRedirects = false`/
 * `expectSuccess = false`/[HttpTimeout] shape `oracleHttpClient()` establishes. Deliberately no
 * `ContentNegotiation`/`Logging` plugin -- responses are decoded manually via [STRIPE_JSON] after a
 * bounded read (see [readCappedStripeBody]), and a request-logging plugin would risk the
 * `Authorization: Bearer <key>` header reaching a log line.
 */
internal fun defaultStripeHttpClient(): HttpClient =
    HttpClient(CIO) {
        install(HttpTimeout) {
            requestTimeoutMillis = 10_000
            connectTimeoutMillis = 5_000
            socketTimeoutMillis = 10_000
        }
        expectSuccess = false
        followRedirects = false
    }

/** Bounded read, same [network.lapis.cloud.server.economy.oracle.readCappedBodyOrNull] idiom -- `null` if [MAX_STRIPE_RESPONSE_BYTES] is exceeded, the body discarded rather than partially parsed. */
private suspend fun HttpResponse.readCappedStripeBody(): ByteArray? {
    val channel = bodyAsChannel()
    val buffer = ByteArray(MAX_STRIPE_RESPONSE_BYTES + 1)
    var total = 0
    while (total < buffer.size) {
        val read = channel.readAvailable(buffer, total, buffer.size - total)
        if (read == -1) break
        total += read
    }
    return if (total > MAX_STRIPE_RESPONSE_BYTES) null else buffer.copyOf(total)
}
