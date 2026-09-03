package network.lapis.cloud.server.payment.psp

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.content.TextContent
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import java.math.BigDecimal

private const val TEST_SECRET_KEY = "sk_test_do_not_leak_in_any_log_line"

private fun testPspConfig(): PspConfig =
    (
        PspConfig.load {
            when (it) {
                PspConfig.ENV_SECRET_KEY -> TEST_SECRET_KEY
                PspConfig.ENV_WEBHOOK_SIGNING_SECRET -> "whsec_test_do_not_leak"
                else -> null
            }
        } as PspConfigState.Configured
    ).config

/**
 * Exercises [StripeCheckoutClient] against a [MockEngine]-backed [HttpClient] -- **never** the real
 * Stripe API, same house rule [network.lapis.cloud.server.postal.LetterxpressPostalMailProviderTest]
 * already establishes for an outbound PSP-shaped client.
 */
class StripeCheckoutClientTest :
    FunSpec({
        fun mockClient(handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData) = HttpClient(MockEngine(handler))

        fun bodyText(request: HttpRequestData): String = (request.body as TextContent).text

        test("request carries Authorization: Bearer <key>, a non-blank Idempotency-Key, and integer-minor-units unit_amount") {
            var capturedAuth: String? = null
            var capturedIdempotencyKey: String? = null
            var capturedBody = ""
            val client =
                mockClient { request ->
                    capturedAuth = request.headers[HttpHeaders.Authorization]
                    capturedIdempotencyKey = request.headers["Idempotency-Key"]
                    capturedBody = bodyText(request)
                    respond(
                        """{"id":"cs_test_123","url":"https://checkout.stripe.com/c/pay/cs_test_123"}""",
                        HttpStatusCode.OK,
                        headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                }
            val checkoutClient = StripeCheckoutClient(pspConfig = testPspConfig(), httpClient = client)

            val result =
                checkoutClient.createCheckoutSession(
                    checkoutSessionId = "checkout-session-id",
                    amount = BigDecimal("12.34"),
                    currency = "EUR",
                    description = "Mitgliedsbeitrag",
                    returnUrls = StripeReturnUrls.memberSpa(baseUrl = "https://lapis.example", checkoutSessionId = "checkout-session-id"),
                )

            capturedAuth shouldBe "Bearer $TEST_SECRET_KEY"
            // Test-quality fix (code review, Welle V1.2.8): `capturedIdempotencyKey?.isNotBlank()
            // ?.shouldBeTrue()` silently asserted nothing if the header was never captured (the
            // safe-call short-circuits to null, and a null result of a `shouldBe`-style assertion
            // chain is not itself a failure) -- despite the test explicitly being about checking for
            // exactly that. shouldNotBeNull() first makes a missing header an actual failure.
            val idempotencyKey = capturedIdempotencyKey.shouldNotBeNull()
            idempotencyKey.isNotBlank().shouldBeTrue()
            // The form KEY itself is URL-encoded too (`[`/`]` -> `%5B`/`%5D`), so the literal
            // substring is `unit_amount%5D=1234`, not `unit_amount=1234`.
            capturedBody.contains("unit_amount%5D=1234") shouldBe true
            (result is StripeCheckoutResult.Success) shouldBe true
        }

        test("success_url/cancel_url carry the session id in the hash fragment") {
            var capturedBody = ""
            val client =
                mockClient { request ->
                    capturedBody = bodyText(request)
                    respond(
                        """{"id":"cs_test_456","url":"https://checkout.stripe.com/c/pay/cs_test_456"}""",
                        HttpStatusCode.OK,
                        headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                }
            val checkoutClient = StripeCheckoutClient(pspConfig = testPspConfig(), httpClient = client)

            checkoutClient.createCheckoutSession(
                checkoutSessionId = "checkout-session-hash-test",
                amount = BigDecimal("5.00"),
                currency = "EUR",
                description = "Spende",
                returnUrls =
                    StripeReturnUrls.memberSpa(baseUrl = "https://lapis.example", checkoutSessionId = "checkout-session-hash-test"),
            )

            capturedBody.contains("success_url=") shouldBe true
            // URL-encoded '#' is %23 -- confirms the hash fragment (not a query parameter) is part
            // of the encoded success_url/cancel_url value.
            capturedBody.contains("%23%2Fpayment-return") shouldBe true
        }

        test("Stripe 400 -> typed Failure, not an exception, and never carries the key") {
            val client =
                mockClient { _ ->
                    respond(
                        """{"error":{"message":"Invalid amount","type":"invalid_request_error"}}""",
                        HttpStatusCode.BadRequest,
                        headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                }
            val checkoutClient = StripeCheckoutClient(pspConfig = testPspConfig(), httpClient = client)

            val result =
                checkoutClient.createCheckoutSession(
                    checkoutSessionId = "checkout-session-400",
                    amount = BigDecimal("1.00"),
                    currency = "EUR",
                    description = "Test",
                    returnUrls = StripeReturnUrls.memberSpa(baseUrl = "https://lapis.example", checkoutSessionId = "checkout-session-400"),
                )

            (result is StripeCheckoutResult.Failure) shouldBe true
            val failure = result as StripeCheckoutResult.Failure
            failure.statusCode shouldBe 400
            failure.message.contains(TEST_SECRET_KEY) shouldBe false
        }

        test("Stripe 500 -> typed Failure") {
            val client = mockClient { _ -> respond("Internal Server Error", HttpStatusCode.InternalServerError) }
            val checkoutClient = StripeCheckoutClient(pspConfig = testPspConfig(), httpClient = client)

            val result =
                checkoutClient.createCheckoutSession(
                    checkoutSessionId = "checkout-session-500",
                    amount = BigDecimal("1.00"),
                    currency = "EUR",
                    description = "Test",
                    returnUrls = StripeReturnUrls.memberSpa(baseUrl = "https://lapis.example", checkoutSessionId = "checkout-session-500"),
                )

            (result is StripeCheckoutResult.Failure) shouldBe true
            (result as StripeCheckoutResult.Failure).statusCode shouldBe 500
        }
    })
