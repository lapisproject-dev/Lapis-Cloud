package network.lapis.cloud.server.payment.psp

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldNotContain
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import java.math.BigDecimal
import kotlin.uuid.Uuid

/** Welle V1.9.55 -- the provider idempotency key is derived from the checkout session id, so an HTTP retry is deduplicated. */
class PspCheckoutIdempotencyHeaderTest :
    FunSpec({
        val stripeConfig =
            (
                PspConfig.load {
                    when (it) {
                        PspConfig.ENV_SECRET_KEY -> "sk_test_idempotency_header_secret"
                        PspConfig.ENV_WEBHOOK_SIGNING_SECRET -> "whsec_test_idempotency"
                        else -> null
                    }
                } as PspConfigState.Configured
            ).config
        val paypalConfig =
            (
                PaypalConfig.load {
                    when (it) {
                        PaypalConfig.ENV_CLIENT_ID -> "test-paypal-idempotency-client-id-0"
                        PaypalConfig.ENV_CLIENT_SECRET -> "test-paypal-idempotency-client-secret"
                        PaypalConfig.ENV_WEBHOOK_ID -> "WH-IDEMPOTENCY"
                        else -> null
                    }
                } as PaypalConfigState.Configured
            ).config

        fun stripeCall(sessionId: String): Pair<String?, PspCheckoutResult> {
            var header: String? = null
            val http =
                HttpClient(
                    MockEngine { request ->
                        header = request.headers["Idempotency-Key"]
                        respond(
                            """{"id":"cs_test_1","url":"https://checkout.stripe.com/c/pay/cs_test_1"}""",
                            HttpStatusCode.OK,
                            headersOf(HttpHeaders.ContentType, "application/json"),
                        )
                    },
                )
            val result =
                runBlocking {
                    StripeCheckoutClient(pspConfig = stripeConfig, httpClient = http).createCheckout(
                        checkoutSessionId = sessionId,
                        amount = BigDecimal("10.00"),
                        currency = "EUR",
                        description = "Beitrag",
                        returnUrls = PspReturnUrls.memberSpa(baseUrl = "https://lapis.example", checkoutSessionId = sessionId),
                    )
                }
            return header to result
        }

        fun paypalCall(sessionId: String): Pair<String?, PspCheckoutResult> {
            var header: String? = null
            val http =
                HttpClient(
                    MockEngine { request ->
                        if (request.url.encodedPath.endsWith("/v1/oauth2/token")) {
                            respond(
                                """{"access_token":"tok","token_type":"Bearer","expires_in":32400}""",
                                HttpStatusCode.OK,
                                headersOf(HttpHeaders.ContentType, "application/json"),
                            )
                        } else {
                            header = request.headers["PayPal-Request-Id"]
                            respond(
                                """{"id":"ORDER-1","status":"CREATED","links":[
                                {"href":"https://www.paypal.com/checkoutnow?token=ORDER-1","rel":"approve","method":"GET"}]}""",
                                HttpStatusCode.Created,
                                headersOf(HttpHeaders.ContentType, "application/json"),
                            )
                        }
                    },
                )
            val result =
                runBlocking {
                    PaypalOrdersClient(config = paypalConfig, httpClient = http).createCheckout(
                        checkoutSessionId = sessionId,
                        amount = BigDecimal("10.00"),
                        currency = "EUR",
                        description = "Beitrag",
                        returnUrls = PspReturnUrls.memberSpa(baseUrl = "https://lapis.example", checkoutSessionId = sessionId),
                    )
                }
            return header to result
        }

        test("Stripe: the same checkout session id sends the same Idempotency-Key, another id another one") {
            val id = Uuid.random().toString()
            val (first, firstResult) = stripeCall(id)
            val (second, _) = stripeCall(id)
            val (other, _) = stripeCall(Uuid.random().toString())
            first shouldBe second
            first shouldNotBe other
            first shouldBe PspIdempotencyKeys.checkout(id)
            // The persisted value equals the header actually sent.
            (firstResult as PspCheckoutResult.Success).idempotencyKey shouldBe first
            first!! shouldNotContain "sk_test"
        }

        test("PayPal: the same checkout session id sends the same PayPal-Request-Id, another id another one") {
            val id = Uuid.random().toString()
            val (first, firstResult) = paypalCall(id)
            val (second, _) = paypalCall(id)
            val (other, _) = paypalCall(Uuid.random().toString())
            first shouldBe second
            first shouldNotBe other
            first shouldBe PspIdempotencyKeys.checkout(id)
            (firstResult as PspCheckoutResult.Success).idempotencyKey shouldBe first
            first!! shouldNotContain "secret"
        }
    })
