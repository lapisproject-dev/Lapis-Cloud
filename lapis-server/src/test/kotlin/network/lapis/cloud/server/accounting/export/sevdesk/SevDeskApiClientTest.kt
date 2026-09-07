package network.lapis.cloud.server.accounting.export.sevdesk

import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
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
import kotlinx.datetime.LocalDate
import network.lapis.cloud.server.accounting.export.CategoryListOutcome
import network.lapis.cloud.server.accounting.export.ConnectionTestOutcome
import network.lapis.cloud.server.accounting.export.OutboundVoucher
import network.lapis.cloud.server.accounting.export.VoucherPushOutcome
import network.lapis.cloud.shared.domain.AccountingExportDirection
import org.slf4j.Logger.ROOT_LOGGER_NAME
import org.slf4j.LoggerFactory
import java.io.IOException
import java.math.BigDecimal
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

private const val TEST_TOKEN = "sevdesk-test-token-do-not-leak-in-any-log-line-1234567890"

/**
 * Welle V1.4.5.4 "sevDesk-Live-Anbindung" -- exercises [SevDeskApiClient] against a
 * [MockEngine]-backed [HttpClient] -- **never** the real sevDesk API, same house rule
 * [network.lapis.cloud.server.accounting.export.lexoffice.LexofficeApiClientTest] already
 * establishes.
 */
class SevDeskApiClientTest :
    FunSpec({
        fun mockClient(handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData) = HttpClient(MockEngine(handler))

        fun bodyText(request: HttpRequestData): String = (request.body as TextContent).text

        fun fastLimiter() = SevDeskRateLimiter(minGap = 1.milliseconds)

        fun testVoucher() =
            OutboundVoucher(
                voucherDate = LocalDate(2026, 1, 5),
                voucherNumber = "LAPIS-20260105-1a2b3c4d",
                direction = AccountingExportDirection.INCOME,
                grossAmount = BigDecimal("119.00"),
                externalCategoryId = "1234:4",
                remark = "Lapis Cloud Export LAPIS-20260105-1a2b3c4d",
            )

        /** Attaches a logback [ListAppender] to the ROOT logger for the duration of [block]. */
        suspend fun captureRootLogEvents(block: suspend () -> Unit): List<ILoggingEvent> {
            val root = LoggerFactory.getLogger(ROOT_LOGGER_NAME) as ch.qos.logback.classic.Logger
            val appender = ListAppender<ILoggingEvent>()
            appender.start()
            root.addAppender(appender)
            return try {
                block()
                appender.list.toList()
            } finally {
                root.detachAppender(appender)
            }
        }

        // ── createVoucher ────────────────────────────────────────────────────────────────

        test("createVoucher: 2xx wrapped in objects envelope is Succeeded, auth header is the RAW token (no Bearer prefix)") {
            var capturedAuth: String? = null
            var capturedBody = ""
            val client =
                mockClient { request ->
                    capturedAuth = request.headers[HttpHeaders.Authorization]
                    capturedBody = bodyText(request)
                    respond(
                        """{"objects":{"voucher":{"id":"42"},"voucherPos":[],"filename":null}}""",
                        HttpStatusCode.Created,
                        headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                }
            val apiClient = SevDeskApiClient(rateLimiter = fastLimiter(), httpClient = client)
            val outcome = apiClient.createVoucher(token = TEST_TOKEN, voucher = testVoucher())
            capturedAuth shouldBe TEST_TOKEN
            (outcome is VoucherPushOutcome.Succeeded) shouldBe true
            (outcome as VoucherPushOutcome.Succeeded).externalVoucherId shouldBe "42"
        }

        test("createVoucher: 2xx UNWRAPPED (spec inconsistency) is also tolerated and Succeeded") {
            val client =
                mockClient {
                    respond(
                        """{"voucher":{"id":"77"},"voucherPos":[],"filename":null}""",
                        HttpStatusCode.Created,
                        headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                }
            val apiClient = SevDeskApiClient(rateLimiter = fastLimiter(), httpClient = client)
            val outcome = apiClient.createVoucher(token = TEST_TOKEN, voucher = testVoucher())
            (outcome is VoucherPushOutcome.Succeeded) shouldBe true
            (outcome as VoucherPushOutcome.Succeeded).externalVoucherId shouldBe "77"
        }

        test("createVoucher: 2xx WITHOUT an id (either shape) is Indeterminate, never Succeeded") {
            val client =
                mockClient {
                    respond(
                        """{"objects":{"voucher":{},"voucherPos":[],"filename":null}}""",
                        HttpStatusCode.Created,
                        headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                }
            val apiClient = SevDeskApiClient(rateLimiter = fastLimiter(), httpClient = client)
            val outcome = apiClient.createVoucher(token = TEST_TOKEN, voucher = testVoucher())
            (outcome is VoucherPushOutcome.Indeterminate) shouldBe true
            (outcome as VoucherPushOutcome.Indeterminate).errorCode shouldBe "MISSING_ID"
        }

        test("createVoucher: request body shape -- creditDebit D for INCOME") {
            var capturedBody = ""
            val client =
                mockClient { request ->
                    capturedBody = bodyText(request)
                    respond(
                        """{"objects":{"voucher":{"id":"1"}}}""",
                        HttpStatusCode.Created,
                        headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                }
            val apiClient = SevDeskApiClient(rateLimiter = fastLimiter(), httpClient = client)
            apiClient.createVoucher(token = TEST_TOKEN, voucher = testVoucher())
            capturedBody.shouldContain("\"creditDebit\":\"D\"")
        }

        test("createVoucher: request body shape -- creditDebit C for EXPENSE") {
            var capturedBody = ""
            val client =
                mockClient { request ->
                    capturedBody = bodyText(request)
                    respond(
                        """{"objects":{"voucher":{"id":"1"}}}""",
                        HttpStatusCode.Created,
                        headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                }
            val apiClient = SevDeskApiClient(rateLimiter = fastLimiter(), httpClient = client)
            apiClient.createVoucher(token = TEST_TOKEN, voucher = testVoucher().copy(direction = AccountingExportDirection.EXPENSE))
            capturedBody.shouldContain("\"creditDebit\":\"C\"")
        }

        test("createVoucher: request body shape -- date, status, voucherType, supplier, net, taxRate") {
            var capturedBody = ""
            val client =
                mockClient { request ->
                    capturedBody = bodyText(request)
                    respond(
                        """{"objects":{"voucher":{"id":"1"}}}""",
                        HttpStatusCode.Created,
                        headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                }
            val apiClient = SevDeskApiClient(rateLimiter = fastLimiter(), httpClient = client)
            apiClient.createVoucher(token = TEST_TOKEN, voucher = testVoucher())
            capturedBody.shouldContain("\"voucherDate\":\"05.01.2026\"")
            capturedBody.shouldContain("\"status\":50")
            capturedBody.shouldContain("\"voucherType\":\"VOU\"")
            capturedBody.shouldContain("\"supplier\":null")
            capturedBody.shouldContain("\"net\":false")
            capturedBody.shouldContain("\"taxRate\":0")
        }

        test("createVoucher: request body shape -- sumGross is unquoted with two fractional digits, never a JSON string") {
            var capturedBody = ""
            val client =
                mockClient { request ->
                    capturedBody = bodyText(request)
                    respond(
                        """{"objects":{"voucher":{"id":"1"}}}""",
                        HttpStatusCode.Created,
                        headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                }
            val apiClient = SevDeskApiClient(rateLimiter = fastLimiter(), httpClient = client)
            apiClient.createVoucher(token = TEST_TOKEN, voucher = testVoucher())
            capturedBody.shouldContain("\"sumGross\":119.00")
            capturedBody.shouldNotContain("\"sumGross\":\"")
        }

        test("createVoucher: request body property is voucherPosSave, not voucherPos") {
            var capturedBody = ""
            val client =
                mockClient { request ->
                    capturedBody = bodyText(request)
                    respond(
                        """{"objects":{"voucher":{"id":"1"}}}""",
                        HttpStatusCode.Created,
                        headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                }
            val apiClient = SevDeskApiClient(rateLimiter = fastLimiter(), httpClient = client)
            apiClient.createVoucher(token = TEST_TOKEN, voucher = testVoucher())
            capturedBody.shouldContain("\"voucherPosSave\":[")
        }

        test("createVoucher: 429 is Retryable and reads Retry-After when present, null otherwise") {
            val client = mockClient { respond("", HttpStatusCode.TooManyRequests, headersOf(HttpHeaders.RetryAfter, "30")) }
            val apiClient = SevDeskApiClient(rateLimiter = fastLimiter(), httpClient = client)
            val outcome = apiClient.createVoucher(token = TEST_TOKEN, voucher = testVoucher())
            (outcome is VoucherPushOutcome.Retryable) shouldBe true
            (outcome as VoucherPushOutcome.Retryable).retryAfter shouldBe 30.seconds

            val clientNoHeader = mockClient { respond("", HttpStatusCode.TooManyRequests) }
            val apiClientNoHeader = SevDeskApiClient(rateLimiter = fastLimiter(), httpClient = clientNoHeader)
            val outcomeNoHeader = apiClientNoHeader.createVoucher(token = TEST_TOKEN, voucher = testVoucher())
            (outcomeNoHeader as VoucherPushOutcome.Retryable).retryAfter shouldBe null
        }

        test("createVoucher: 503 is Retryable") {
            val client = mockClient { respond("", HttpStatusCode.ServiceUnavailable) }
            val apiClient = SevDeskApiClient(rateLimiter = fastLimiter(), httpClient = client)
            val outcome = apiClient.createVoucher(token = TEST_TOKEN, voucher = testVoucher())
            (outcome is VoucherPushOutcome.Retryable) shouldBe true
        }

        test("createVoucher: 500/502/504 are Indeterminate with errorCode RESPONSE_LOST, never Retryable") {
            listOf(HttpStatusCode.InternalServerError, HttpStatusCode.BadGateway, HttpStatusCode.GatewayTimeout).forEach { status ->
                val client = mockClient { respond("", status) }
                val apiClient = SevDeskApiClient(rateLimiter = fastLimiter(), httpClient = client)
                val outcome = apiClient.createVoucher(token = TEST_TOKEN, voucher = testVoucher())
                (outcome is VoucherPushOutcome.Indeterminate) shouldBe true
                (outcome as VoucherPushOutcome.Indeterminate).errorCode shouldBe "RESPONSE_LOST"
            }
        }

        test("createVoucher: 401 is Rejected with errorCode UNAUTHORIZED") {
            val client =
                mockClient {
                    respond(
                        """{"error":{"message":"Unauthorized"}}""",
                        HttpStatusCode.Unauthorized,
                        headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                }
            val apiClient = SevDeskApiClient(rateLimiter = fastLimiter(), httpClient = client)
            val outcome = apiClient.createVoucher(token = TEST_TOKEN, voucher = testVoucher())
            (outcome is VoucherPushOutcome.Rejected) shouldBe true
            (outcome as VoucherPushOutcome.Rejected).errorCode shouldBe "UNAUTHORIZED"
        }

        test("createVoucher: 422 with a validationError envelope is Rejected with the parsed message") {
            val client =
                mockClient {
                    respond(
                        """{"error":{"message":"Invalid taxRule","exceptionUUID":"abc-123"}}""",
                        HttpStatusCode(422, "Unprocessable Entity"),
                        headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                }
            val apiClient = SevDeskApiClient(rateLimiter = fastLimiter(), httpClient = client)
            val outcome = apiClient.createVoucher(token = TEST_TOKEN, voucher = testVoucher())
            (outcome is VoucherPushOutcome.Rejected) shouldBe true
            (outcome as VoucherPushOutcome.Rejected).message shouldBe "Invalid taxRule"
        }

        test("createVoucher: an unmappable externalCategoryId is Rejected WITHOUT any HTTP request") {
            val requestCount = AtomicInteger(0)
            val client =
                mockClient {
                    requestCount.incrementAndGet()
                    respond("", HttpStatusCode.OK)
                }
            val apiClient = SevDeskApiClient(rateLimiter = fastLimiter(), httpClient = client)
            val outcome = apiClient.createVoucher(token = TEST_TOKEN, voucher = testVoucher().copy(externalCategoryId = "not-a-valid-id"))
            (outcome is VoucherPushOutcome.Rejected) shouldBe true
            (outcome as VoucherPushOutcome.Rejected).errorCode shouldBe "INVALID_CATEGORY_MAPPING"
            requestCount.get() shouldBe 0
        }

        test("createVoucher: ConnectException (pre-send) is Retryable") {
            val client = mockClient { throw ConnectException("Connection refused") }
            val apiClient = SevDeskApiClient(rateLimiter = fastLimiter(), httpClient = client)
            val outcome = apiClient.createVoucher(token = TEST_TOKEN, voucher = testVoucher())
            (outcome is VoucherPushOutcome.Retryable) shouldBe true
            (outcome as VoucherPushOutcome.Retryable).errorCode shouldBe "NETWORK_ERROR"
        }

        test("createVoucher: ConnectTimeoutException (a ConnectException subtype) is Retryable") {
            val client = mockClient { throw ConnectTimeoutException("Timed out connecting", IOException("timeout")) }
            val apiClient = SevDeskApiClient(rateLimiter = fastLimiter(), httpClient = client)
            val outcome = apiClient.createVoucher(token = TEST_TOKEN, voucher = testVoucher())
            (outcome is VoucherPushOutcome.Retryable) shouldBe true
        }

        test("createVoucher: UnknownHostException (pre-send) is Retryable") {
            val client = mockClient { throw UnknownHostException("my.sevdesk.de") }
            val apiClient = SevDeskApiClient(rateLimiter = fastLimiter(), httpClient = client)
            val outcome = apiClient.createVoucher(token = TEST_TOKEN, voucher = testVoucher())
            (outcome is VoucherPushOutcome.Retryable) shouldBe true
        }

        test("createVoucher: HttpRequestTimeoutException is Indeterminate, never Retryable") {
            val client = mockClient { request -> throw HttpRequestTimeoutException(request) }
            val apiClient = SevDeskApiClient(rateLimiter = fastLimiter(), httpClient = client)
            val outcome = apiClient.createVoucher(token = TEST_TOKEN, voucher = testVoucher())
            (outcome is VoucherPushOutcome.Indeterminate) shouldBe true
            (outcome as VoucherPushOutcome.Indeterminate).errorCode shouldBe "RESPONSE_LOST"
        }

        test("createVoucher: SocketTimeoutException is Indeterminate, never Retryable") {
            val client = mockClient { throw SocketTimeoutException("Read timed out") }
            val apiClient = SevDeskApiClient(rateLimiter = fastLimiter(), httpClient = client)
            val outcome = apiClient.createVoucher(token = TEST_TOKEN, voucher = testVoucher())
            (outcome is VoucherPushOutcome.Indeterminate) shouldBe true
            (outcome as VoucherPushOutcome.Indeterminate).errorCode shouldBe "RESPONSE_LOST"
        }

        test("createVoucher: a response body larger than the 64 KiB cap is discarded, never partially parsed") {
            val hugeBody = "{\"objects\":{\"voucher\":{\"id\":\"" + "x".repeat(80 * 1024) + "\"}}}"
            val client = mockClient { respond(hugeBody, HttpStatusCode.Created, headersOf(HttpHeaders.ContentType, "application/json")) }
            val apiClient = SevDeskApiClient(rateLimiter = fastLimiter(), httpClient = client)
            val outcome = apiClient.createVoucher(token = TEST_TOKEN, voucher = testVoucher())
            (outcome is VoucherPushOutcome.Indeterminate) shouldBe true
        }

        // ── getBookkeepingSystemVersion ──────────────────────────────────────────────────

        test("getBookkeepingSystemVersion: version 2.0 is Success with companyName null") {
            val client =
                mockClient {
                    respond(
                        """{"objects":{"version":"2.0"}}""",
                        HttpStatusCode.OK,
                        headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                }
            val apiClient = SevDeskApiClient(rateLimiter = fastLimiter(), httpClient = client)
            val outcome = apiClient.getBookkeepingSystemVersion(TEST_TOKEN)
            (outcome is ConnectionTestOutcome.Success) shouldBe true
            (outcome as ConnectionTestOutcome.Success).companyName shouldBe null
        }

        test("getBookkeepingSystemVersion: version 1.0 is Failure with errorCode UNSUPPORTED_BOOKKEEPING_VERSION") {
            val client =
                mockClient {
                    respond(
                        """{"objects":{"version":"1.0"}}""",
                        HttpStatusCode.OK,
                        headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                }
            val apiClient = SevDeskApiClient(rateLimiter = fastLimiter(), httpClient = client)
            val outcome = apiClient.getBookkeepingSystemVersion(TEST_TOKEN)
            (outcome is ConnectionTestOutcome.Failure) shouldBe true
            (outcome as ConnectionTestOutcome.Failure).errorCode shouldBe "UNSUPPORTED_BOOKKEEPING_VERSION"
        }

        // Regression guard for the class KDoc "401 vs UNAUTHORIZED" doctrine -- see
        // SevDeskApiClient class KDoc. AccountingExportService.testConnection compares against the
        // LITERAL "401", not "UNAUTHORIZED" -- a wrong errorCode here silently breaks the
        // markTestDue re-authentication prompt after a revoked token, with no other test to catch
        // it.
        test("getBookkeepingSystemVersion: 401 is Failure with errorCode exactly \"401\", NOT \"UNAUTHORIZED\"") {
            val client = mockClient { respond("""{"error":{"message":"invalid token"}}""", HttpStatusCode.Unauthorized) }
            val apiClient = SevDeskApiClient(rateLimiter = fastLimiter(), httpClient = client)
            val outcome = apiClient.getBookkeepingSystemVersion(TEST_TOKEN)
            (outcome is ConnectionTestOutcome.Failure) shouldBe true
            (outcome as ConnectionTestOutcome.Failure).errorCode shouldBe "401"
        }

        // ── listReceiptGuidance ──────────────────────────────────────────────────────────

        test("listReceiptGuidance: version-gate fires FIRST -- 1.0 fails WITHOUT ever calling ReceiptGuidance") {
            val requestCount = AtomicInteger(0)
            val client =
                mockClient {
                    requestCount.incrementAndGet()
                    respond("""{"objects":{"version":"1.0"}}""", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
                }
            val apiClient = SevDeskApiClient(rateLimiter = fastLimiter(), httpClient = client)
            val outcome = apiClient.listReceiptGuidance(TEST_TOKEN)
            (outcome is CategoryListOutcome.Failure) shouldBe true
            (outcome as CategoryListOutcome.Failure).errorCode shouldBe "UNSUPPORTED_BOOKKEEPING_VERSION"
            requestCount.get() shouldBe 1
        }

        test("listReceiptGuidance: only ZERO-taxRate-capable accounts are surfaced") {
            val client =
                mockClient { request ->
                    when {
                        request.url.encodedPath.endsWith("bookkeepingSystemVersion") ->
                            respond(
                                """{"objects":{"version":"2.0"}}""",
                                HttpStatusCode.OK,
                                headersOf(HttpHeaders.ContentType, "application/json"),
                            )
                        request.url.encodedPath.endsWith("forRevenue") ->
                            respond(
                                """{"objects":[
                                  {"accountDatevId":1234,"accountNumber":"4000","accountName":"Umsatzerlöse",
                                   "allowedTaxRules":[{"id":4,"name":"r4","description":"Steuerfrei","taxRates":["ZERO"]}]},
                                  {"accountDatevId":5678,"accountNumber":"4001","accountName":"Sonstige",
                                   "allowedTaxRules":[{"id":1,"name":"r1","description":"USt pflichtig","taxRates":["FULL"]}]}
                                ]}""",
                                HttpStatusCode.OK,
                                headersOf(HttpHeaders.ContentType, "application/json"),
                            )
                        request.url.encodedPath.endsWith("forExpense") ->
                            respond("""{"objects":[]}""", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
                        else -> error("unexpected path ${request.url.encodedPath}")
                    }
                }
            val apiClient = SevDeskApiClient(rateLimiter = fastLimiter(), httpClient = client)
            val outcome = apiClient.listReceiptGuidance(TEST_TOKEN)
            (outcome is CategoryListOutcome.Success) shouldBe true
            val categories = (outcome as CategoryListOutcome.Success).categories
            categories.map { it.id } shouldBe listOf("1234:4")
        }

        test("listReceiptGuidance: an account with two ZERO-capable rules yields two ExternalCategory entries") {
            val client =
                mockClient { request ->
                    when {
                        request.url.encodedPath.endsWith("bookkeepingSystemVersion") ->
                            respond(
                                """{"objects":{"version":"2.0"}}""",
                                HttpStatusCode.OK,
                                headersOf(HttpHeaders.ContentType, "application/json"),
                            )
                        request.url.encodedPath.endsWith("forRevenue") ->
                            respond(
                                """{"objects":[
                                  {"accountDatevId":1234,"accountNumber":"4000","accountName":"Umsatzerlöse",
                                   "allowedTaxRules":[
                                     {"id":4,"name":"r4","description":"Steuerfrei §4","taxRates":["ZERO"]},
                                     {"id":11,"name":"r11","description":"Kleinunternehmer","taxRates":["ZERO"]}
                                   ]}
                                ]}""",
                                HttpStatusCode.OK,
                                headersOf(HttpHeaders.ContentType, "application/json"),
                            )
                        request.url.encodedPath.endsWith("forExpense") ->
                            respond("""{"objects":[]}""", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
                        else -> error("unexpected path ${request.url.encodedPath}")
                    }
                }
            val apiClient = SevDeskApiClient(rateLimiter = fastLimiter(), httpClient = client)
            val outcome = apiClient.listReceiptGuidance(TEST_TOKEN)
            val categories = (outcome as CategoryListOutcome.Success).categories
            categories.map { it.id }.toSet() shouldBe setOf("1234:4", "1234:11")
        }

        test("listReceiptGuidance: forRevenue -> INCOME, forExpense -> EXPENSE") {
            val client =
                mockClient { request ->
                    when {
                        request.url.encodedPath.endsWith("bookkeepingSystemVersion") ->
                            respond(
                                """{"objects":{"version":"2.0"}}""",
                                HttpStatusCode.OK,
                                headersOf(HttpHeaders.ContentType, "application/json"),
                            )
                        request.url.encodedPath.endsWith("forRevenue") ->
                            respond(
                                """{"objects":[{"accountDatevId":1,"accountNumber":"a","accountName":"Rev",
                                    "allowedTaxRules":[{"id":4,"description":"d","taxRates":["ZERO"]}]}]}""",
                                HttpStatusCode.OK,
                                headersOf(HttpHeaders.ContentType, "application/json"),
                            )
                        request.url.encodedPath.endsWith("forExpense") ->
                            respond(
                                """{"objects":[{"accountDatevId":2,"accountNumber":"b","accountName":"Exp",
                                    "allowedTaxRules":[{"id":4,"description":"d","taxRates":["ZERO"]}]}]}""",
                                HttpStatusCode.OK,
                                headersOf(HttpHeaders.ContentType, "application/json"),
                            )
                        else -> error("unexpected path ${request.url.encodedPath}")
                    }
                }
            val apiClient = SevDeskApiClient(rateLimiter = fastLimiter(), httpClient = client)
            val outcome = apiClient.listReceiptGuidance(TEST_TOKEN)
            val categories = (outcome as CategoryListOutcome.Success).categories
            categories.first { it.id == "1:4" }.direction shouldBe AccountingExportDirection.INCOME
            categories.first { it.id == "2:4" }.direction shouldBe AccountingExportDirection.EXPENSE
        }

        // ── Logging hygiene ──────────────────────────────────────────────────────────────

        test("the token never appears in any log line, across success AND every failure path") {
            val events =
                captureRootLogEvents {
                    val successClient =
                        mockClient {
                            respond(
                                """{"objects":{"voucher":{"id":"1"}}}""",
                                HttpStatusCode.Created,
                                headersOf(HttpHeaders.ContentType, "application/json"),
                            )
                        }
                    SevDeskApiClient(rateLimiter = fastLimiter(), httpClient = successClient)
                        .createVoucher(token = TEST_TOKEN, voucher = testVoucher())

                    val failClient = mockClient { throw ConnectException("boom") }
                    SevDeskApiClient(rateLimiter = fastLimiter(), httpClient = failClient)
                        .createVoucher(token = TEST_TOKEN, voucher = testVoucher())

                    val timeoutClient = mockClient { throw SocketTimeoutException("boom") }
                    SevDeskApiClient(rateLimiter = fastLimiter(), httpClient = timeoutClient)
                        .createVoucher(token = TEST_TOKEN, voucher = testVoucher())

                    val unauthorizedClient = mockClient { respond("""{"error":{"message":"x"}}""", HttpStatusCode.Unauthorized) }
                    SevDeskApiClient(rateLimiter = fastLimiter(), httpClient = unauthorizedClient)
                        .getBookkeepingSystemVersion(TEST_TOKEN)
                }
            val tokenSuffix = TEST_TOKEN.takeLast(4)
            events.forEach { event ->
                event.formattedMessage.shouldNotContain(TEST_TOKEN)
                event.formattedMessage.shouldNotContain(tokenSuffix)
            }
        }
    })
