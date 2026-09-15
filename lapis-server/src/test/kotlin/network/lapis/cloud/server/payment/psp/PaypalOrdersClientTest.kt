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
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonNull
import java.math.BigDecimal

private fun testPaypalConfig(): PaypalConfig =
    (
        PaypalConfig.load {
            when (it) {
                PaypalConfig.ENV_CLIENT_ID -> "test-paypal-orders-client-client-id-0"
                PaypalConfig.ENV_CLIENT_SECRET -> "test-paypal-orders-client-secret-0"
                PaypalConfig.ENV_WEBHOOK_ID -> "WH-PAYPAL-ORDERS-CLIENT-TEST"
                else -> null
            }
        } as PaypalConfigState.Configured
    ).config

private const val TEST_ACCESS_TOKEN = "test-access-token-do-not-leak-in-any-log-line"

/** A [MockEngine] whose `/v1/oauth2/token` leg always succeeds -- every test below is about the OTHER (Orders API) leg. */
private fun tokenAndThen(handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData) =
    HttpClient(
        MockEngine { request ->
            if (request.url.encodedPath.endsWith("/v1/oauth2/token")) {
                respond(
                    """{"access_token":"$TEST_ACCESS_TOKEN","token_type":"Bearer","expires_in":32400}""",
                    HttpStatusCode.OK,
                    headersOf(HttpHeaders.ContentType, "application/json"),
                )
            } else {
                handler(request)
            }
        },
    )

private fun bodyText(request: HttpRequestData): String = (request.body as TextContent).text

/**
 * Welle V1.2.8b "PayPal-Anbindung" (GitHub Issue #6), Review round 2 (MAJOR test-coverage gap) --
 * exercises [PaypalOrdersClient] directly against a [MockEngine]-backed [HttpClient], same "never
 * the real PSP API" house rule [StripeCheckoutClientTest] already establishes. Covers exactly the
 * plan's own §5.1 test list that this file was missing: `createCheckout`'s outgoing JSON shape and
 * approval-link extraction/fallback, `captureOrder`'s stable request id and 422/
 * `ORDER_ALREADY_CAPTURED` mapping, and `verifyWebhookSignature`'s SUCCESS/FAILURE/5xx mapping.
 * [PaypalAccessTokenProvider]'s own caching/refresh/single-flight/401/oversized-body behavior is
 * covered separately by [PaypalAccessTokenProviderTest] -- every test here just lets the token leg
 * succeed via [tokenAndThen].
 */
class PaypalOrdersClientTest :
    FunSpec({
        test(
            "createCheckout sends Authorization: Bearer <token>, a fresh PayPal-Request-Id, intent=CAPTURE, and the exact decimal amount",
        ) {
            var capturedAuth: String? = null
            var capturedRequestId: String? = null
            var capturedBody = ""
            val client =
                tokenAndThen { request ->
                    capturedAuth = request.headers[HttpHeaders.Authorization]
                    capturedRequestId = request.headers["PayPal-Request-Id"]
                    capturedBody = bodyText(request)
                    respond(
                        """{"id":"ORDER-123","status":"CREATED","links":[
                            {"href":"https://www.paypal.com/checkoutnow?token=ORDER-123","rel":"approve","method":"GET"}
                        ]}""",
                        HttpStatusCode.Created,
                        headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                }
            val ordersClient = PaypalOrdersClient(config = testPaypalConfig(), httpClient = client)

            val result =
                runBlocking {
                    ordersClient.createCheckout(
                        checkoutSessionId = "checkout-session-id",
                        amount = BigDecimal("12.34"),
                        currency = "EUR",
                        description = "Mitgliedsbeitrag",
                        returnUrls = PspReturnUrls.memberSpa(baseUrl = "https://lapis.example", checkoutSessionId = "checkout-session-id"),
                    )
                }

            capturedAuth shouldBe "Bearer $TEST_ACCESS_TOKEN"
            val requestId = capturedRequestId.shouldNotBeNull()
            requestId.isNotBlank().shouldBeTrue()
            capturedBody.contains("\"intent\":\"CAPTURE\"") shouldBe true
            capturedBody.contains("\"value\":\"12.34\"") shouldBe true
            capturedBody.contains("\"custom_id\":\"checkout-session-id\"") shouldBe true
            (result is PspCheckoutResult.Success) shouldBe true
            val success = result as PspCheckoutResult.Success
            success.sessionId shouldBe "ORDER-123"
            success.redirectUrl shouldBe "https://www.paypal.com/checkoutnow?token=ORDER-123"
            // Fix (Review round 1, MAJOR): the returned idempotencyKey must be the SAME value
            // actually sent as PayPal-Request-Id, not a discarded fresh value / the literal "n/a".
            success.idempotencyKey shouldBe requestId
        }

        test("createCheckout prefers the payer-action link over approve when both are present") {
            val client =
                tokenAndThen { _ ->
                    respond(
                        """{"id":"ORDER-456","status":"CREATED","links":[
                            {"href":"https://www.paypal.com/checkoutnow?token=ORDER-456","rel":"approve","method":"GET"},
                            {"href":"https://www.paypal.com/payer-action?token=ORDER-456","rel":"payer-action","method":"GET"}
                        ]}""",
                        HttpStatusCode.Created,
                        headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                }
            val ordersClient = PaypalOrdersClient(config = testPaypalConfig(), httpClient = client)

            val result =
                runBlocking {
                    ordersClient.createCheckout(
                        checkoutSessionId = "checkout-session-payer-action",
                        amount = BigDecimal("5.00"),
                        currency = "EUR",
                        description = "Spende",
                        returnUrls =
                            PspReturnUrls.memberSpa(baseUrl = "https://lapis.example", checkoutSessionId = "checkout-session-payer-action"),
                    )
                }

            (result is PspCheckoutResult.Success) shouldBe true
            (result as PspCheckoutResult.Success).redirectUrl shouldBe "https://www.paypal.com/payer-action?token=ORDER-456"
        }

        test("createCheckout -- 2xx response but no approve/payer-action link -> typed Failure, not an exception") {
            val client =
                tokenAndThen { _ ->
                    respond(
                        """{"id":"ORDER-789","status":"CREATED","links":[]}""",
                        HttpStatusCode.Created,
                        headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                }
            val ordersClient = PaypalOrdersClient(config = testPaypalConfig(), httpClient = client)

            val result =
                runBlocking {
                    ordersClient.createCheckout(
                        checkoutSessionId = "checkout-session-no-link",
                        amount = BigDecimal("5.00"),
                        currency = "EUR",
                        description = "Spende",
                        returnUrls =
                            PspReturnUrls.memberSpa(baseUrl = "https://lapis.example", checkoutSessionId = "checkout-session-no-link"),
                    )
                }

            (result is PspCheckoutResult.Failure) shouldBe true
        }

        test("createCheckout -- PayPal 400 -> typed Failure carrying PayPal's own sanitized message, never the token") {
            val client =
                tokenAndThen { _ ->
                    respond(
                        """{"name":"INVALID_REQUEST","message":"Amount is invalid","details":[]}""",
                        HttpStatusCode.BadRequest,
                        headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                }
            val ordersClient = PaypalOrdersClient(config = testPaypalConfig(), httpClient = client)

            val result =
                runBlocking {
                    ordersClient.createCheckout(
                        checkoutSessionId = "checkout-session-400",
                        amount = BigDecimal("1.00"),
                        currency = "EUR",
                        description = "Test",
                        returnUrls = PspReturnUrls.memberSpa(baseUrl = "https://lapis.example", checkoutSessionId = "checkout-session-400"),
                    )
                }

            (result is PspCheckoutResult.Failure) shouldBe true
            val failure = result as PspCheckoutResult.Failure
            failure.statusCode shouldBe 400
            failure.message shouldBe "Amount is invalid"
            failure.message.contains(TEST_ACCESS_TOKEN) shouldBe false
        }

        test("captureOrder sends a STABLE PayPal-Request-Id derived from the order id, not a fresh random one") {
            var capturedRequestId: String? = null
            val client =
                tokenAndThen { request ->
                    capturedRequestId = request.headers["PayPal-Request-Id"]
                    respond(
                        """{"id":"ORDER-abc","status":"COMPLETED","purchase_units":[{"payments":{"captures":[
                            {"id":"CAP-1","status":"COMPLETED","amount":{"currency_code":"EUR","value":"50.00"}}
                        ]}}]}""",
                        HttpStatusCode.OK,
                        headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                }
            val ordersClient = PaypalOrdersClient(config = testPaypalConfig(), httpClient = client)

            val result = runBlocking { ordersClient.captureOrder("ORDER-abc") }

            capturedRequestId shouldBe "capture-ORDER-abc"
            (result is PaypalCaptureResult.Captured) shouldBe true
            val captured = result as PaypalCaptureResult.Captured
            captured.captureId shouldBe "CAP-1"
            captured.amount shouldBe BigDecimal("50.00")
            captured.currency shouldBe "EUR"
        }

        test("captureOrder -- 422 with details[].issue == ORDER_ALREADY_CAPTURED -> AlreadyCaptured, not Failed") {
            val client =
                tokenAndThen { _ ->
                    respond(
                        """{"name":"UNPROCESSABLE_ENTITY","details":[{"issue":"ORDER_ALREADY_CAPTURED"}]}""",
                        HttpStatusCode.UnprocessableEntity,
                        headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                }
            val ordersClient = PaypalOrdersClient(config = testPaypalConfig(), httpClient = client)

            val result = runBlocking { ordersClient.captureOrder("ORDER-already-captured") }

            result shouldBe PaypalCaptureResult.AlreadyCaptured
        }

        test("captureOrder -- 422 with an UNRELATED issue -> typed Failed, not silently treated as AlreadyCaptured") {
            val client =
                tokenAndThen { _ ->
                    respond(
                        """{"name":"UNPROCESSABLE_ENTITY","details":[{"issue":"ORDER_NOT_APPROVED"}]}""",
                        HttpStatusCode.UnprocessableEntity,
                        headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                }
            val ordersClient = PaypalOrdersClient(config = testPaypalConfig(), httpClient = client)

            val result = runBlocking { ordersClient.captureOrder("ORDER-wrong-issue") }

            (result is PaypalCaptureResult.Failed) shouldBe true
            (result as PaypalCaptureResult.Failed).statusCode shouldBe 422
        }

        test("captureOrder -- 500 -> typed Failed") {
            val client = tokenAndThen { _ -> respond("Internal Server Error", HttpStatusCode.InternalServerError) }
            val ordersClient = PaypalOrdersClient(config = testPaypalConfig(), httpClient = client)

            val result = runBlocking { ordersClient.captureOrder("ORDER-500") }

            (result is PaypalCaptureResult.Failed) shouldBe true
            (result as PaypalCaptureResult.Failed).statusCode shouldBe 500
        }

        test("verifyWebhookSignature -- verification_status SUCCESS -> Verified") {
            val client =
                tokenAndThen { _ ->
                    respond(
                        """{"verification_status":"SUCCESS"}""",
                        HttpStatusCode.OK,
                        headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                }
            val ordersClient = PaypalOrdersClient(config = testPaypalConfig(), httpClient = client)

            val result =
                runBlocking {
                    ordersClient.verifyWebhookSignature(
                        headers = testHeaders(),
                        rawEvent = JsonNull,
                    )
                }

            result shouldBe PaypalVerifyResult.Verified
        }

        test("verifyWebhookSignature -- verification_status FAILURE -> NotVerified") {
            val client =
                tokenAndThen { _ ->
                    respond(
                        """{"verification_status":"FAILURE"}""",
                        HttpStatusCode.OK,
                        headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                }
            val ordersClient = PaypalOrdersClient(config = testPaypalConfig(), httpClient = client)

            val result = runBlocking { ordersClient.verifyWebhookSignature(headers = testHeaders(), rawEvent = JsonNull) }

            result shouldBe PaypalVerifyResult.NotVerified
        }

        test("verifyWebhookSignature -- PayPal 503 -> Unavailable, never treated as a signature failure") {
            val client = tokenAndThen { _ -> respond("Service Unavailable", HttpStatusCode.ServiceUnavailable) }
            val ordersClient = PaypalOrdersClient(config = testPaypalConfig(), httpClient = client)

            val result = runBlocking { ordersClient.verifyWebhookSignature(headers = testHeaders(), rawEvent = JsonNull) }

            (result is PaypalVerifyResult.Unavailable) shouldBe true
            (result as PaypalVerifyResult.Unavailable).statusCode shouldBe 503
        }

        test("verifyWebhookSignature -- unexpected verification_status value -> Unavailable, not silently NotVerified") {
            val client =
                tokenAndThen { _ ->
                    respond(
                        """{"verification_status":"SOMETHING_ELSE"}""",
                        HttpStatusCode.OK,
                        headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                }
            val ordersClient = PaypalOrdersClient(config = testPaypalConfig(), httpClient = client)

            val result = runBlocking { ordersClient.verifyWebhookSignature(headers = testHeaders(), rawEvent = JsonNull) }

            (result is PaypalVerifyResult.Unavailable) shouldBe true
        }
    })

private fun testHeaders(): PaypalTransmissionHeaders =
    requireNotNull(
        PaypalTransmissionHeaders.parseOrNull {
            when (it) {
                "PAYPAL-TRANSMISSION-ID" -> "txn-id-1"
                "PAYPAL-TRANSMISSION-TIME" -> "2026-09-15T10:00:00Z"
                "PAYPAL-TRANSMISSION-SIG" -> "sig-value"
                "PAYPAL-CERT-URL" -> "https://api-m.paypal.com/cert"
                "PAYPAL-AUTH-ALGO" -> "SHA256withRSA"
                else -> null
            }
        },
    )
