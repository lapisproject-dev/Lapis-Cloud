package network.lapis.cloud.server.postal

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.shouldBe
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.content.TextContent
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger

private const val TEST_USERNAME = "test-user"
private const val TEST_API_KEY = "s3cr3t-test-api-key-do-not-leak"

/**
 * Exercises [LetterxpressPostalMailProvider] against a [MockEngine]-backed [HttpClient] --
 * **never** the real Letterxpress API (unreachable from this sandbox, and a house rule that unit
 * tests never call a real third-party API regardless of reachability). See that class's KDoc for
 * why [MockEngine] is injected via the constructor rather than `System.getenv` manipulation.
 *
 * Kotest [FunSpec] `test { ... }` bodies are themselves `suspend` -- no `runTest`/`runBlocking`
 * wrapper needed to call the `suspend fun dispatchLetter`.
 */
class LetterxpressPostalMailProviderTest :
    FunSpec({
        fun mockClient(handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData) =
            HttpClient(MockEngine(handler)) {
                install(ContentNegotiation) { json() }
            }

        fun bodyText(request: HttpRequestData): String = (request.body as TextContent).text

        test("blank/missing credentials short-circuit to Failed without any HTTP call") {
            val callCount = AtomicInteger(0)
            val client =
                mockClient { _ ->
                    callCount.incrementAndGet()
                    respond("{}", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
                }

            val blankUsername = LetterxpressPostalMailProvider(username = "", apiKey = TEST_API_KEY, httpClient = client)
            val blankApiKey = LetterxpressPostalMailProvider(username = TEST_USERNAME, apiKey = "  ", httpClient = client)
            val missingBoth = LetterxpressPostalMailProvider(username = null, apiKey = null, httpClient = client)

            for (provider in listOf(blankUsername, blankApiKey, missingBoth)) {
                val outcome =
                    provider.dispatchLetter(
                        pdfBytes = byteArrayOf(1, 2, 3),
                        recipientName = "Recipient",
                        recipientStreet = "Street 1",
                        recipientPostalCode = "12345",
                        recipientCity = "City",
                        recipientCountry = "Country",
                    )
                (outcome is PostalDispatchOutcome.Failed) shouldBe true
            }
            callCount.get() shouldBe 0
        }

        test("liveMode=false (default) sends final:false in the request body") {
            var capturedBody = ""
            val client =
                mockClient { request ->
                    capturedBody = bodyText(request)
                    respond(
                        """{"status":"success","data":{"job_id":"job-123"}}""",
                        HttpStatusCode.OK,
                        headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                }
            val provider =
                LetterxpressPostalMailProvider(username = TEST_USERNAME, apiKey = TEST_API_KEY, liveMode = false, httpClient = client)

            val outcome =
                provider.dispatchLetter(
                    pdfBytes = byteArrayOf(1, 2, 3),
                    recipientName = "Recipient",
                    recipientStreet = "Street 1",
                    recipientPostalCode = "12345",
                    recipientCity = "City",
                    recipientCountry = "Country",
                )

            (outcome is PostalDispatchOutcome.Dispatched) shouldBe true
            (outcome as PostalDispatchOutcome.Dispatched).providerReference shouldBe "job-123"
            capturedBody.contains("\"final\":false") shouldBe true
        }

        test("liveMode=true sends final:true in the request body") {
            var capturedBody = ""
            val client =
                mockClient { request ->
                    capturedBody = bodyText(request)
                    respond(
                        """{"status":"success","data":{"job_id":"job-456"}}""",
                        HttpStatusCode.OK,
                        headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                }
            val provider =
                LetterxpressPostalMailProvider(username = TEST_USERNAME, apiKey = TEST_API_KEY, liveMode = true, httpClient = client)

            provider.dispatchLetter(
                pdfBytes = byteArrayOf(1, 2, 3),
                recipientName = "Recipient",
                recipientStreet = "Street 1",
                recipientPostalCode = "12345",
                recipientCity = "City",
                recipientCountry = "Country",
            )

            capturedBody.contains("\"final\":true") shouldBe true
        }

        test("a 200 success response maps to Dispatched with the provider's job id") {
            val client =
                mockClient { _ ->
                    respond(
                        """{"status":"success","data":{"job_id":"job-789"}}""",
                        HttpStatusCode.OK,
                        headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                }
            val provider = LetterxpressPostalMailProvider(username = TEST_USERNAME, apiKey = TEST_API_KEY, httpClient = client)

            val outcome =
                provider.dispatchLetter(
                    pdfBytes = byteArrayOf(1, 2, 3),
                    recipientName = "Recipient",
                    recipientStreet = "Street 1",
                    recipientPostalCode = "12345",
                    recipientCity = "City",
                    recipientCountry = "Country",
                )

            outcome shouldBe PostalDispatchOutcome.Dispatched("job-789")
        }

        test("a 4xx/5xx HTTP response maps to Failed without leaking the apiKey used in the request") {
            val client = mockClient { _ -> respondError(HttpStatusCode.Unauthorized, "invalid credentials") }
            val provider = LetterxpressPostalMailProvider(username = TEST_USERNAME, apiKey = TEST_API_KEY, httpClient = client)

            val outcome =
                provider.dispatchLetter(
                    pdfBytes = byteArrayOf(1, 2, 3),
                    recipientName = "Recipient",
                    recipientStreet = "Street 1",
                    recipientPostalCode = "12345",
                    recipientCity = "City",
                    recipientCountry = "Country",
                )

            (outcome is PostalDispatchOutcome.Failed) shouldBe true
            val message = (outcome as PostalDispatchOutcome.Failed).sanitizedErrorMessage
            message.contains("401") shouldBe true
            message.contains(TEST_API_KEY) shouldBe false
        }

        test("a 200 response with an unparseable/garbage body maps to Failed, no exception propagates") {
            val client =
                mockClient { _ ->
                    respond("not json at all {{{", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
                }
            val provider = LetterxpressPostalMailProvider(username = TEST_USERNAME, apiKey = TEST_API_KEY, httpClient = client)

            val outcome =
                provider.dispatchLetter(
                    pdfBytes = byteArrayOf(1, 2, 3),
                    recipientName = "Recipient",
                    recipientStreet = "Street 1",
                    recipientPostalCode = "12345",
                    recipientCity = "City",
                    recipientCountry = "Country",
                )

            (outcome is PostalDispatchOutcome.Failed) shouldBe true
            (outcome as PostalDispatchOutcome.Failed).sanitizedErrorMessage.contains(TEST_API_KEY) shouldBe false
        }

        test("a network-level exception maps to Failed, no exception propagates, apiKey never leaks") {
            val client =
                HttpClient(MockEngine { _ -> throw IOException("connection reset") }) {
                    install(ContentNegotiation) { json() }
                }
            val provider = LetterxpressPostalMailProvider(username = TEST_USERNAME, apiKey = TEST_API_KEY, httpClient = client)

            val outcome =
                provider.dispatchLetter(
                    pdfBytes = byteArrayOf(1, 2, 3),
                    recipientName = "Recipient",
                    recipientStreet = "Street 1",
                    recipientPostalCode = "12345",
                    recipientCity = "City",
                    recipientCountry = "Country",
                )

            (outcome is PostalDispatchOutcome.Failed) shouldBe true
            val message = (outcome as PostalDispatchOutcome.Failed).sanitizedErrorMessage
            message.contains(TEST_API_KEY) shouldBe false
            message.contains("connection reset").shouldBeFalse()
        }
    })
