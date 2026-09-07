package network.lapis.cloud.server.accounting.export.lexoffice

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.network.sockets.ConnectTimeoutException
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.content.TextContent
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.LocalDate
import network.lapis.cloud.server.accounting.export.CategoryListOutcome
import network.lapis.cloud.server.accounting.export.ConnectionTestOutcome
import network.lapis.cloud.server.accounting.export.ExternalCategory
import network.lapis.cloud.server.accounting.export.OutboundVoucher
import network.lapis.cloud.server.accounting.export.VoucherPushOutcome
import network.lapis.cloud.shared.domain.AccountingExportDirection
import java.io.IOException
import java.math.BigDecimal
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

private const val TEST_TOKEN = "test-token-do-not-leak-in-any-log-line-1234567890"

/**
 * Exercises [LexofficeApiClient] against a [MockEngine]-backed [HttpClient] -- **never** the real
 * lexoffice API, same house rule [network.lapis.cloud.server.payment.psp.StripeCheckoutClientTest]
 * already establishes for an outbound PSP/provider-shaped client. Kotest's `test("...") { }` body is
 * itself a suspend lambda (see [StripeCheckoutClientTest]'s own precedent) -- no `runBlocking`/
 * `runTest` wrapper needed for the ordinary request/response tests below; the one exception
 * ([LexofficeRateLimiter]'s own timing test) is called out at its own site.
 */
class LexofficeApiClientTest :
    FunSpec({
        fun mockClient(handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData) = HttpClient(MockEngine(handler))

        fun bodyText(request: HttpRequestData): String = (request.body as TextContent).text

        fun fastLimiter() = LexofficeRateLimiter(minGap = 1.milliseconds)

        fun testVoucher() =
            OutboundVoucher(
                voucherDate = LocalDate(2026, 1, 31),
                voucherNumber = "LAPIS-20260131-1a2b3c4d",
                direction = AccountingExportDirection.INCOME,
                grossAmount = BigDecimal("119.00"),
                externalCategoryId = "cat-1",
                remark = "Lapis Cloud Export LAPIS-20260131-1a2b3c4d",
            )

        test("createVoucher: 2xx with an id is Succeeded, request carries Bearer auth and the expected 0%-tax body shape") {
            var capturedAuth: String? = null
            var capturedBody = ""
            val client =
                mockClient { request ->
                    capturedAuth = request.headers[HttpHeaders.Authorization]
                    capturedBody = bodyText(request)
                    respond(
                        """{"id":"66196c43-baf3-4335-bfee-d610367059db","resourceUri":"x","createdDate":"x","updatedDate":"x","version":1}""",
                        HttpStatusCode.Created,
                        headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                }
            val apiClient = LexofficeApiClient(rateLimiter = fastLimiter(), httpClient = client)
            val outcome = apiClient.createVoucher(token = TEST_TOKEN, voucher = testVoucher())
            capturedAuth shouldBe "Bearer $TEST_TOKEN"
            capturedBody.shouldContain("\"voucherDate\":\"2026-01-31\"")
            capturedBody.shouldContain("\"taxType\":\"gross\"")
            capturedBody.shouldContain("\"totalTaxAmount\":\"0.00\"")
            (outcome is VoucherPushOutcome.Succeeded) shouldBe true
            (outcome as VoucherPushOutcome.Succeeded).externalVoucherId shouldBe "66196c43-baf3-4335-bfee-d610367059db"
        }

        test("createVoucher: 2xx WITHOUT an id is Indeterminate, never Succeeded") {
            val client =
                mockClient {
                    respond(
                        """{"resourceUri":"x","createdDate":"x","updatedDate":"x","version":1}""",
                        HttpStatusCode.Created,
                        headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                }
            val apiClient = LexofficeApiClient(rateLimiter = fastLimiter(), httpClient = client)
            val outcome = apiClient.createVoucher(token = TEST_TOKEN, voucher = testVoucher())
            (outcome is VoucherPushOutcome.Indeterminate) shouldBe true
        }

        test("createVoucher: 429 is Retryable and reads Retry-After when present") {
            val client = mockClient { respond("", HttpStatusCode.TooManyRequests, headersOf(HttpHeaders.RetryAfter, "5")) }
            val apiClient = LexofficeApiClient(rateLimiter = fastLimiter(), httpClient = client)
            val outcome = apiClient.createVoucher(token = TEST_TOKEN, voucher = testVoucher())
            (outcome is VoucherPushOutcome.Retryable) shouldBe true
            (outcome as VoucherPushOutcome.Retryable).retryAfter shouldBe 5.seconds
        }

        test("createVoucher: 401 is Rejected with errorCode UNAUTHORIZED") {
            val client =
                mockClient {
                    respond(
                        """{"message":"Unauthorized"}""",
                        HttpStatusCode.Unauthorized,
                        headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                }
            val apiClient = LexofficeApiClient(rateLimiter = fastLimiter(), httpClient = client)
            val outcome = apiClient.createVoucher(token = TEST_TOKEN, voucher = testVoucher())
            (outcome is VoucherPushOutcome.Rejected) shouldBe true
            (outcome as VoucherPushOutcome.Rejected).errorCode shouldBe "UNAUTHORIZED"
            outcome.message shouldBe "Unauthorized"
        }

        test("createVoucher: 422 with a legacy IssueList envelope is Rejected with a readable message, not the raw body") {
            val client =
                mockClient {
                    respond(
                        """{"IssueList":[{"i18nKey":"missing_entity","source":"voucherItems[0].categoryId","type":"validation_failure"}]}""",
                        HttpStatusCode(422, "Unprocessable Entity"),
                        headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                }
            val apiClient = LexofficeApiClient(rateLimiter = fastLimiter(), httpClient = client)
            val outcome = apiClient.createVoucher(token = TEST_TOKEN, voucher = testVoucher())
            (outcome is VoucherPushOutcome.Rejected) shouldBe true
            (outcome as VoucherPushOutcome.Rejected).message.shouldContain("categoryId")
            outcome.message.shouldContain("missing_entity")
        }

        // Security review Fund 2026-09-07 (Runde 5, MAJOR): the following two tests replace the
        // previous single "createVoucher: 5xx is Retryable" test, which pinned a blanket 5xx ->
        // Retryable mapping that directly contradicted the pre-send-IOException doctrine
        // [createVoucher]'s own KDoc states ("only a failure GUARANTEED to have happened before any
        // byte of the request left this process is safe to classify as Retryable") -- a 5xx status
        // is by definition post-send. Only 503 (alongside 429, already covered above) genuinely
        // means "not processed"; every other 5xx must be Indeterminate.
        test("createVoucher: 503 Service Unavailable is Retryable") {
            val client = mockClient { respond("", HttpStatusCode.ServiceUnavailable) }
            val apiClient = LexofficeApiClient(rateLimiter = fastLimiter(), httpClient = client)
            val outcome = apiClient.createVoucher(token = TEST_TOKEN, voucher = testVoucher())
            (outcome is VoucherPushOutcome.Retryable) shouldBe true
        }

        test("createVoucher: 500/502/504 are Indeterminate with errorCode RESPONSE_LOST, never Retryable") {
            listOf(HttpStatusCode.InternalServerError, HttpStatusCode.BadGateway, HttpStatusCode.GatewayTimeout).forEach { status ->
                val client = mockClient { respond("", status) }
                val apiClient = LexofficeApiClient(rateLimiter = fastLimiter(), httpClient = client)
                val outcome = apiClient.createVoucher(token = TEST_TOKEN, voucher = testVoucher())
                (outcome is VoucherPushOutcome.Indeterminate) shouldBe true
                (outcome as VoucherPushOutcome.Indeterminate).errorCode shouldBe "RESPONSE_LOST"
            }
        }

        test("createVoucher: a response body larger than the 64 KiB cap is discarded, never partially parsed") {
            val hugeBody = "{" + "\"id\":\"" + "x".repeat(80 * 1024) + "\"}"
            val client = mockClient { respond(hugeBody, HttpStatusCode.Created, headersOf(HttpHeaders.ContentType, "application/json")) }
            val apiClient = LexofficeApiClient(rateLimiter = fastLimiter(), httpClient = client)
            val outcome = apiClient.createVoucher(token = TEST_TOKEN, voucher = testVoucher())
            // Oversized 2xx body -> unparseable (treated as null) -> missing id -> Indeterminate.
            (outcome is VoucherPushOutcome.Indeterminate) shouldBe true
        }

        // Security review Fund 2026-09-07 (Runde 4, Befund 1): these four tests pin the
        // pre-send-vs-ambiguous classification `createVoucher`'s own catch block now applies to
        // every `IOException` from `httpClient.post` -- see that method's KDoc.
        test("createVoucher: ConnectException (connection setup, before any byte sent) is Retryable") {
            val client = mockClient { throw ConnectException("Connection refused") }
            val apiClient = LexofficeApiClient(rateLimiter = fastLimiter(), httpClient = client)
            val outcome = apiClient.createVoucher(token = TEST_TOKEN, voucher = testVoucher())
            (outcome is VoucherPushOutcome.Retryable) shouldBe true
            (outcome as VoucherPushOutcome.Retryable).errorCode shouldBe "NETWORK_ERROR"
        }

        test("createVoucher: ConnectTimeoutException (a ConnectException subtype) is Retryable") {
            val client = mockClient { throw ConnectTimeoutException("Timed out connecting", IOException("timeout")) }
            val apiClient = LexofficeApiClient(rateLimiter = fastLimiter(), httpClient = client)
            val outcome = apiClient.createVoucher(token = TEST_TOKEN, voucher = testVoucher())
            (outcome is VoucherPushOutcome.Retryable) shouldBe true
        }

        test("createVoucher: UnknownHostException (DNS resolution, before any byte sent) is Retryable") {
            val client = mockClient { throw UnknownHostException("api.lexware.io") }
            val apiClient = LexofficeApiClient(rateLimiter = fastLimiter(), httpClient = client)
            val outcome = apiClient.createVoucher(token = TEST_TOKEN, voucher = testVoucher())
            (outcome is VoucherPushOutcome.Retryable) shouldBe true
        }

        test(
            "createVoucher: HttpRequestTimeoutException (may occur AFTER the request body was fully sent) " +
                "is Indeterminate, never Retryable",
        ) {
            val client = mockClient { request -> throw HttpRequestTimeoutException(request) }
            val apiClient = LexofficeApiClient(rateLimiter = fastLimiter(), httpClient = client)
            val outcome = apiClient.createVoucher(token = TEST_TOKEN, voucher = testVoucher())
            (outcome is VoucherPushOutcome.Indeterminate) shouldBe true
            (outcome as VoucherPushOutcome.Indeterminate).errorCode shouldBe "RESPONSE_LOST"
        }

        test(
            "createVoucher: SocketTimeoutException (may occur AFTER the request body was fully sent) " +
                "is Indeterminate, never Retryable",
        ) {
            val client = mockClient { throw SocketTimeoutException("Read timed out") }
            val apiClient = LexofficeApiClient(rateLimiter = fastLimiter(), httpClient = client)
            val outcome = apiClient.createVoucher(token = TEST_TOKEN, voucher = testVoucher())
            (outcome is VoucherPushOutcome.Indeterminate) shouldBe true
            (outcome as VoucherPushOutcome.Indeterminate).errorCode shouldBe "RESPONSE_LOST"
        }

        test("getProfile: 2xx with companyName is Success") {
            val client =
                mockClient {
                    respond(
                        """{"organizationId":"aa93e8a8-2aa3-470b-b914-caad8a255dd8","companyName":"Musterverein e. V."}""",
                        HttpStatusCode.OK,
                        headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                }
            val apiClient = LexofficeApiClient(rateLimiter = fastLimiter(), httpClient = client)
            val outcome = apiClient.getProfile(TEST_TOKEN)
            (outcome is ConnectionTestOutcome.Success) shouldBe true
            (outcome as ConnectionTestOutcome.Success).companyName shouldBe "Musterverein e. V."
        }

        test("getProfile: 401 is Failure") {
            val client = mockClient { respond("""{"message":"Unauthorized"}""", HttpStatusCode.Unauthorized) }
            val apiClient = LexofficeApiClient(rateLimiter = fastLimiter(), httpClient = client)
            val outcome = apiClient.getProfile(TEST_TOKEN)
            (outcome is ConnectionTestOutcome.Failure) shouldBe true
        }

        test("listPostingCategories: maps income/outgo to INCOME/EXPENSE") {
            val client =
                mockClient {
                    respond(
                        """[
                          {"id":"a","name":"Einnahmen","type":"income","contactRequired":false,"splitAllowed":true,"groupName":"Einnahmen"},
                          {"id":"b","name":"Reisekosten","type":"outgo","contactRequired":false,"splitAllowed":true,"groupName":"Reisen"}
                        ]""",
                        HttpStatusCode.OK,
                        headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                }
            val apiClient = LexofficeApiClient(rateLimiter = fastLimiter(), httpClient = client)
            val outcome = apiClient.listPostingCategories(TEST_TOKEN)
            (outcome is CategoryListOutcome.Success) shouldBe true
            val categories = (outcome as CategoryListOutcome.Success).categories
            categories shouldBe
                listOf(
                    ExternalCategory(id = "a", name = "Einnahmen", groupName = "Einnahmen", direction = AccountingExportDirection.INCOME),
                    ExternalCategory(id = "b", name = "Reisekosten", groupName = "Reisen", direction = AccountingExportDirection.EXPENSE),
                )
        }

        test("LexofficeRateLimiter: five consecutive acquire() calls take at least 4 * minGap of real wall-clock time") {
            // Deliberately kotlinx.coroutines.runBlocking -- LexofficeRateLimiter measures elapsed
            // time via TimeSource.Monotonic (real wall-clock nanoTime), so this needs an actual
            // suspension/measurement, unlike every other test in this file which needs no explicit
            // coroutine builder at all (Kotest's test body is already a suspend lambda).
            runBlocking {
                val minGap = 30.milliseconds
                val limiter = LexofficeRateLimiter(minGap = minGap)
                val start = TimeSource.Monotonic.markNow()
                repeat(5) { limiter.acquire() }
                val elapsed = start.elapsedNow()
                (elapsed >= minGap * 4) shouldBe true
            }
        }
    })
