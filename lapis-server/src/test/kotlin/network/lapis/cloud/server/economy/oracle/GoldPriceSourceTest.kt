package network.lapis.cloud.server.economy.oracle

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.concurrent.atomic.AtomicInteger

/**
 * Exercises [GoldApiIoGoldPriceSource]/[MetalPriceApiGoldPriceSource]/[AlphaVantageGoldPriceSource]
 * against a [MockEngine]-backed [HttpClient] -- **never** a real third-party API, same house rule
 * [BitcoinPriceSourceTest] follows. [AlphaVantageGoldPriceSource] additionally takes a
 * [FakeEcbRateSource] test double (implementing [EcbRateSource], never the real
 * [EcbReferenceRateClient], which would need real network I/O) so its currency-conversion leg is
 * exercised deterministically against the REAL class -- no parallel reimplementation of its logic.
 */
class GoldPriceSourceTest :
    FunSpec({
        fun mockClient(handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData) =
            HttpClient(MockEngine(handler)) {
                install(ContentNegotiation) { json() }
            }

        fun MockRequestHandleScope.jsonResponse(body: String) =
            respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))

        // ── GoldApiIoGoldPriceSource -- happy path uses the VERBATIM captured PZB fixture ────────

        test("GoldApiIoGoldPriceSource parses the verbatim captured PZB response into an exact BigDecimal price, no Double round-trip") {
            // Verbatim capture from the old PZB sister-project's own test suite
            // (.../PDV/PZB/Common/src/test/kotlin/de/pdv/pzb/common/SerializationTest.kt, test()).
            val body =
                """
                {
                  "timestamp": 1655124336,
                  "metal": "XAU",
                  "currency": "EUR",
                  "exchange": "FOREXCOM",
                  "symbol": "FOREXCOM:XAUEUR",
                  "prev_close_price": 1779.52,
                  "open_price": 1779.52,
                  "low_price": 1763.74,
                  "high_price": 1789.57,
                  "open_time": 1655078400,
                  "price": 1763.64,
                  "ch": -15.88,
                  "chp": -0.88,
                  "ask": 1764.15,
                  "bid": 1763.13,
                  "price_gram_24k": 56.7023,
                  "price_gram_22k": 51.9771,
                  "price_gram_21k": 49.6145,
                  "price_gram_20k": 47.252,
                  "price_gram_18k": 42.5268,
                  "price_gram_16k": 42.5263,
                  "price_gram_14k": 42.4223,
                  "price_gram_10k": 41.4223
                }
                """.trimIndent()
            val client = mockClient { jsonResponse(body) }
            val source = GoldApiIoGoldPriceSource(httpClient = client, apiKey = "test-key")

            val result = source.fetchPrice("EUR")

            result?.sourceId shouldBe "goldapi"
            (result?.price?.compareTo(BigDecimal("1763.64")) ?: -1) shouldBe 0
        }

        test("GoldApiIoGoldPriceSource sends the api key in the x-access-token header, never in the URL") {
            var seenRequest: HttpRequestData? = null
            val client =
                mockClient { request ->
                    seenRequest = request
                    jsonResponse("""{"metal":"XAU","currency":"EUR","price":1763.64}""")
                }
            val source = GoldApiIoGoldPriceSource(httpClient = client, apiKey = "super-secret-key")

            source.fetchPrice("EUR")

            seenRequest?.headers?.get("x-access-token") shouldBe "super-secret-key"
            // (equivalent to seenRequest?.headers?.get("x-access-token") -- see LiveKitEgressClientTest's
            // established `request.headers[HttpHeaders.X]` idiom for header inspection in this codebase)
            (seenRequest?.url?.toString()?.contains("super-secret-key") ?: true) shouldBe false
        }

        test("GoldApiIoGoldPriceSource rejects a metal/currency mismatch") {
            val wrongMetal = mockClient { jsonResponse("""{"metal":"XAG","currency":"EUR","price":25.00}""") }
            GoldApiIoGoldPriceSource(httpClient = wrongMetal, apiKey = "k").fetchPrice("EUR") shouldBe null

            val wrongCurrency = mockClient { jsonResponse("""{"metal":"XAU","currency":"USD","price":1900.00}""") }
            GoldApiIoGoldPriceSource(httpClient = wrongCurrency, apiKey = "k").fetchPrice("EUR") shouldBe null
        }

        test(
            "GoldApiIoGoldPriceSource maps a 403 auth failure with a bare error body to null, never throws " +
                "(the live shape, not the spec's aspirational 401)",
        ) {
            val client = mockClient { respond("""{"error":"Invalid API Key"}""", HttpStatusCode.Forbidden) }
            GoldApiIoGoldPriceSource(httpClient = client, apiKey = "bad-key").fetchPrice("EUR") shouldBe null
        }

        // ── MetalPriceApiGoldPriceSource ──────────────────────────────────────────────────────────

        test("MetalPriceApiGoldPriceSource parses a realistic /latest response into the correct BigDecimal price") {
            val client = mockClient { jsonResponse("""{"success":true,"base":"XAU","timestamp":1,"rates":{"EUR":2145.67}}""") }
            val source = MetalPriceApiGoldPriceSource(httpClient = client, apiKey = "k")

            val result = source.fetchPrice("EUR")

            result?.sourceId shouldBe "metalpriceapi"
            (result?.price?.compareTo(BigDecimal("2145.67")) ?: -1) shouldBe 0
        }

        test(
            "MetalPriceApiGoldPriceSource maps the LIVE error shape (statusCode/message at HTTP 200, not the documented code/info) to null",
        ) {
            val client = mockClient { jsonResponse("""{"success":false,"error":{"statusCode":101,"message":"invalid api_key"}}""") }
            MetalPriceApiGoldPriceSource(httpClient = client, apiKey = "bad").fetchPrice("EUR") shouldBe null
        }

        test("TRAP 1 regression: reads rates[currency], NEVER the reciprocal rates[XAU+currency] key, even when both are present") {
            val client =
                mockClient {
                    jsonResponse("""{"success":true,"base":"XAU","rates":{"EUR":2145.67,"XAUEUR":0.000466}}""")
                }
            val result = MetalPriceApiGoldPriceSource(httpClient = client, apiKey = "k").fetchPrice("EUR")

            (result?.price?.compareTo(BigDecimal("2145.67")) ?: -1) shouldBe 0
        }

        test("an implausibly small (inverted-signature) rate is dropped and logged, never auto-inverted") {
            val client = mockClient { jsonResponse("""{"success":true,"base":"XAU","rates":{"EUR":0.00046}}""") }
            MetalPriceApiGoldPriceSource(httpClient = client, apiKey = "k").fetchPrice("EUR") shouldBe null
        }

        // ── AlphaVantageGoldPriceSource ────────────────────────────────────────────────────────────

        test("AlphaVantageGoldPriceSource parses a realistic GOLD_SILVER_SPOT response (verified live wire shape, see class KDoc)") {
            val client = mockClient { jsonResponse("""{"nominal":"XAUUSD","timestamp":"2026-08-20 12:22:12","price":"4000.00"}""") }
            val source = AlphaVantageGoldPriceSource(httpClient = client, ecbRates = FakeEcbRateSource(), apiKey = "k")

            val result = source.fetchPrice("USD")

            result?.sourceId shouldBe "alphavantage"
            (result?.price?.compareTo(BigDecimal("4000.00")) ?: -1) shouldBe 0
        }

        test("donationCurrency == USD returns the spot price verbatim and never calls the ECB conversion leg") {
            val client = mockClient { jsonResponse("""{"nominal":"XAUUSD","timestamp":"2026-08-20 12:22:12","price":"4000.00"}""") }
            val fakeEcb = FakeEcbRateSource()
            val source = AlphaVantageGoldPriceSource(httpClient = client, ecbRates = fakeEcb, apiKey = "k")

            val result = source.fetchPrice("USD")

            (result?.price?.compareTo(BigDecimal("4000.00")) ?: -1) shouldBe 0
            fakeEcb.callCount.get() shouldBe 0
        }

        test("EUR conversion divides USD by the ECB USD-per-EUR rate (not multiplies) -- guards the ~4642 inverted-result trap") {
            val client = mockClient { jsonResponse("""{"nominal":"XAUUSD","timestamp":"2026-08-20 12:22:12","price":"4000.00"}""") }
            val fakeEcb = FakeEcbRateSource(rates = mapOf("USD" to BigDecimal("1.1605")))
            val source = AlphaVantageGoldPriceSource(httpClient = client, ecbRates = fakeEcb, apiKey = "k")

            val result = source.fetchPrice("EUR")

            // 4000.00 / 1.1605 -- the CORRECT direction. A multiplication bug would yield ~4642.00.
            val expected = BigDecimal("4000.00").divide(BigDecimal("1.1605"), ORACLE_MATH_SCALE, RoundingMode.HALF_UP)
            (result?.price?.compareTo(expected) ?: BigDecimal("-1")) shouldBe 0
            val invertedWrongResult = BigDecimal("4000.00").multiply(BigDecimal("1.1605"))
            (result?.price?.compareTo(invertedWrongResult) ?: -1) shouldBe -1
            // Only the "USD" leg actually consults [rates] -- the "EUR" leg (donationPerEur) short-circuits
            // to BigDecimal.ONE without a lookup, mirroring EcbReferenceRateClient's own "no HTTP call at
            // all for EUR" contract (see FakeEcbRateSource KDoc).
            fakeEcb.callCount.get() shouldBe 1
        }

        test("ECB leg unavailable -> the source returns null, NEVER the raw unconverted USD figure") {
            val client = mockClient { jsonResponse("""{"nominal":"XAUUSD","timestamp":"2026-08-20 12:22:12","price":"4000.00"}""") }
            val fakeEcb = FakeEcbRateSource(rates = emptyMap())
            val source = AlphaVantageGoldPriceSource(httpClient = client, ecbRates = fakeEcb, apiKey = "k")

            source.fetchPrice("EUR") shouldBe null
        }

        test("ECB returns a zero/negative rate -> null, no ArithmeticException escapes") {
            val client = mockClient { jsonResponse("""{"nominal":"XAUUSD","timestamp":"2026-08-20 12:22:12","price":"4000.00"}""") }
            val fakeEcb = FakeEcbRateSource(rates = mapOf("USD" to BigDecimal.ZERO))
            val source = AlphaVantageGoldPriceSource(httpClient = client, ecbRates = fakeEcb, apiKey = "k")

            source.fetchPrice("EUR") shouldBe null
        }

        test("Alpha Vantage rate-limit/error bodies at HTTP 200 (Information/Note/Error Message) map to null, never throw") {
            val information = mockClient { jsonResponse("""{"Information":"demo key rejection"}""") }
            AlphaVantageGoldPriceSource(httpClient = information, ecbRates = FakeEcbRateSource(), apiKey = "demo")
                .fetchPrice("USD") shouldBe null

            val note = mockClient { jsonResponse("""{"Note":"rate limit hit"}""") }
            AlphaVantageGoldPriceSource(httpClient = note, ecbRates = FakeEcbRateSource(), apiKey = "k")
                .fetchPrice("USD") shouldBe null

            val errorMessage = mockClient { jsonResponse("""{"Error Message":"invalid symbol"}""") }
            AlphaVantageGoldPriceSource(httpClient = errorMessage, ecbRates = FakeEcbRateSource(), apiKey = "k")
                .fetchPrice("USD") shouldBe null
        }

        test("an unexpected/renamed Alpha Vantage response shape maps to null (the defensive-parser contract)") {
            val client = mockClient { jsonResponse("""{"symbol":"XAU","spotPrice":"4000.00"}""") }
            AlphaVantageGoldPriceSource(httpClient = client, ecbRates = FakeEcbRateSource(), apiKey = "k")
                .fetchPrice("USD") shouldBe null
        }

        test("Alpha Vantage: a nominal for a different symbol pair is rejected, not blindly trusted") {
            val client = mockClient { jsonResponse("""{"nominal":"XAGUSD","timestamp":"2026-08-20 12:22:12","price":"66.03"}""") }
            AlphaVantageGoldPriceSource(httpClient = client, ecbRates = FakeEcbRateSource(), apiKey = "k")
                .fetchPrice("USD") shouldBe null
        }

        // ── Shared failure-path / SSRF / size-cap coverage across the three gold sources ─────────

        test("a non-2xx status and a garbage body map to null for every gold source, never throw") {
            val down = mockClient { respondError(HttpStatusCode.ServiceUnavailable, "down") }
            GoldApiIoGoldPriceSource(httpClient = down, apiKey = "k").fetchPrice("EUR") shouldBe null
            MetalPriceApiGoldPriceSource(httpClient = down, apiKey = "k").fetchPrice("EUR") shouldBe null
            AlphaVantageGoldPriceSource(httpClient = down, ecbRates = FakeEcbRateSource(), apiKey = "k").fetchPrice("USD") shouldBe null

            val garbage = mockClient { jsonResponse("not json {{{") }
            GoldApiIoGoldPriceSource(httpClient = garbage, apiKey = "k").fetchPrice("EUR") shouldBe null
            MetalPriceApiGoldPriceSource(httpClient = garbage, apiKey = "k").fetchPrice("EUR") shouldBe null
        }

        test("an oversized response body maps to null instead of being parsed, no OOM") {
            val oversized = "{\"metal\":\"XAU\",\"currency\":\"EUR\",\"price\":\"" + "1".repeat(MAX_ORACLE_RESPONSE_BYTES + 1) + "\"}"
            val client = mockClient { jsonResponse(oversized) }
            GoldApiIoGoldPriceSource(httpClient = client, apiKey = "k").fetchPrice("EUR") shouldBe null
        }

        test("a non-allowlisted baseUrl host is rejected by the SSRF guard before any HTTP request is made, for every gold source") {
            val callCount = AtomicInteger(0)
            val client =
                mockClient { _ ->
                    callCount.incrementAndGet()
                    jsonResponse("""{"metal":"XAU","currency":"EUR","price":1763.64}""")
                }

            GoldApiIoGoldPriceSource(httpClient = client, apiKey = "k", baseUrl = "https://evil.example.com").fetchPrice("EUR") shouldBe
                null
            MetalPriceApiGoldPriceSource(httpClient = client, apiKey = "k", baseUrl = "https://evil.example.com").fetchPrice("EUR") shouldBe
                null
            AlphaVantageGoldPriceSource(
                httpClient = client,
                ecbRates = FakeEcbRateSource(),
                apiKey = "k",
                baseUrl = "https://evil.example.com",
            ).fetchPrice("USD") shouldBe null
            callCount.get() shouldBe 0
        }

        test("requireAllowlistedHttpsUrl accepts the three real gold hosts") {
            requireAllowlistedHttpsUrl("https://www.goldapi.io/api/XAU/EUR")
            requireAllowlistedHttpsUrl("https://api.metalpriceapi.com/v1/latest?api_key=k&base=XAU&currencies=EUR")
            requireAllowlistedHttpsUrl("https://www.alphavantage.co/query?function=GOLD_SILVER_SPOT&symbol=XAU&apikey=k")
        }

        test("requireAllowlistedHttpsUrl's failure message names neither an api_key value nor the query string") {
            val ex =
                runCatching {
                    requireAllowlistedHttpsUrl("https://evil.example.com/v1/latest?api_key=SUPER-SECRET-VALUE")
                }.exceptionOrNull()
            (ex?.message?.contains("SUPER-SECRET-VALUE") ?: true) shouldBe false
        }
    })

/**
 * A deterministic [EcbRateSource] test double -- never performs real network I/O. [rates] maps an
 * uppercase currency code to its ECB "units per EUR" rate; `"EUR"` always resolves to
 * [BigDecimal.ONE] without consulting [rates] or incrementing [callCount], mirroring
 * [EcbReferenceRateClient.ratePerEur]'s own "no HTTP call at all for EUR" contract.
 */
private class FakeEcbRateSource(
    private val rates: Map<String, BigDecimal?> = emptyMap(),
) : EcbRateSource {
    val callCount = AtomicInteger(0)

    override suspend fun ratePerEur(currency: String): BigDecimal? {
        val cur = currency.uppercase()
        if (cur == "EUR") return BigDecimal.ONE
        callCount.incrementAndGet()
        return rates[cur]
    }
}
