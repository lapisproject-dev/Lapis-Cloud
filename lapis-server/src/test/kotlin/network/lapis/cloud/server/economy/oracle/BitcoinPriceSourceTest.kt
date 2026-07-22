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
import java.util.concurrent.atomic.AtomicInteger

/**
 * Exercises [CoinbaseBitcoinPriceSource]/[KrakenBitcoinPriceSource]/[BitstampBitcoinPriceSource]
 * against a [MockEngine]-backed [HttpClient] -- **never** the real Coinbase/Kraken/Bitstamp API
 * (unreachable from this sandbox, and a house rule that unit tests never call a real third-party
 * API regardless of reachability). See [CoinbaseBitcoinPriceSource]'s own KDoc for why an
 * injectable `httpClient`/`baseUrl` is used rather than manipulating real endpoints, mirroring
 * [network.lapis.cloud.server.postal.LetterxpressPostalMailProviderTest]'s own idiom.
 */
class BitcoinPriceSourceTest :
    FunSpec({
        fun mockClient(handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData) =
            HttpClient(MockEngine(handler)) {
                install(ContentNegotiation) { json() }
            }

        fun MockRequestHandleScope.jsonResponse(body: String) =
            respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))

        // ── Wire-format parsing (happy path) ──────────────────────────────

        test("CoinbaseBitcoinPriceSource parses a realistic spot response into the correct BigDecimal price") {
            val client = mockClient { jsonResponse("""{"data":{"amount":"58123.45","base":"BTC","currency":"EUR"}}""") }
            val source = CoinbaseBitcoinPriceSource(httpClient = client)

            val result = source.fetchPrice("EUR")

            result?.sourceId shouldBe "coinbase"
            (result?.price?.compareTo(BigDecimal("58123.45")) ?: -1) shouldBe 0
        }

        test(
            "KrakenBitcoinPriceSource parses a realistic ticker response, including the renamed pair key, into the correct BigDecimal price",
        ) {
            val client =
                mockClient {
                    jsonResponse(
                        """{"error":[],"result":{"XXBTZEUR":{"c":["58200.10","0.01234567"],"v":["1000","2000"]}}}""",
                    )
                }
            val source = KrakenBitcoinPriceSource(httpClient = client)

            val result = source.fetchPrice("EUR")

            result?.sourceId shouldBe "kraken"
            (result?.price?.compareTo(BigDecimal("58200.10")) ?: -1) shouldBe 0
        }

        test("BitstampBitcoinPriceSource parses a realistic ticker response into the correct BigDecimal price") {
            val client = mockClient { jsonResponse("""{"last":"58050.00","high":"59000.00","low":"57000.00"}""") }
            val source = BitstampBitcoinPriceSource(httpClient = client)

            val result = source.fetchPrice("EUR")

            result?.sourceId shouldBe "bitstamp"
            (result?.price?.compareTo(BigDecimal("58050.00")) ?: -1) shouldBe 0
        }

        // ── Failure paths never throw, always map to null ────────────────

        test("a non-2xx HTTP status maps to null for every source, never throws") {
            val client = mockClient { respondError(HttpStatusCode.ServiceUnavailable, "down for maintenance") }

            CoinbaseBitcoinPriceSource(httpClient = client).fetchPrice("EUR") shouldBe null
            KrakenBitcoinPriceSource(httpClient = client).fetchPrice("EUR") shouldBe null
            BitstampBitcoinPriceSource(httpClient = client).fetchPrice("EUR") shouldBe null
        }

        test("an unparseable/garbage response body maps to null, no exception propagates") {
            val client = mockClient { jsonResponse("not json at all {{{") }

            CoinbaseBitcoinPriceSource(httpClient = client).fetchPrice("EUR") shouldBe null
            KrakenBitcoinPriceSource(httpClient = client).fetchPrice("EUR") shouldBe null
            BitstampBitcoinPriceSource(httpClient = client).fetchPrice("EUR") shouldBe null
        }

        test("a well-formed response missing the price field maps to null") {
            val coinbaseMissing = mockClient { jsonResponse("""{"data":{"base":"BTC"}}""") }
            CoinbaseBitcoinPriceSource(httpClient = coinbaseMissing).fetchPrice("EUR") shouldBe null

            val krakenEmptyResult = mockClient { jsonResponse("""{"error":[],"result":{}}""") }
            KrakenBitcoinPriceSource(httpClient = krakenEmptyResult).fetchPrice("EUR") shouldBe null

            val krakenApiError = mockClient { jsonResponse("""{"error":["EQuery:Unknown asset pair"],"result":{}}""") }
            KrakenBitcoinPriceSource(httpClient = krakenApiError).fetchPrice("EUR") shouldBe null

            val bitstampMissing = mockClient { jsonResponse("""{"high":"59000.00"}""") }
            BitstampBitcoinPriceSource(httpClient = bitstampMissing).fetchPrice("EUR") shouldBe null
        }

        test("a non-positive or garbage price string maps to null, not a crash or a negative price") {
            val zero = mockClient { jsonResponse("""{"last":"0"}""") }
            BitstampBitcoinPriceSource(httpClient = zero).fetchPrice("EUR") shouldBe null

            val negative = mockClient { jsonResponse("""{"last":"-5.00"}""") }
            BitstampBitcoinPriceSource(httpClient = negative).fetchPrice("EUR") shouldBe null

            val garbage = mockClient { jsonResponse("""{"last":"not-a-number"}""") }
            BitstampBitcoinPriceSource(httpClient = garbage).fetchPrice("EUR") shouldBe null
        }

        // ── SSRF allowlist guard ───────────────────────────────────────────

        test("a non-allowlisted baseUrl host is rejected by the SSRF guard before any HTTP request is made") {
            val callCount = AtomicInteger(0)
            val client =
                mockClient { _ ->
                    callCount.incrementAndGet()
                    jsonResponse("""{"data":{"amount":"1"}}""")
                }
            val source = CoinbaseBitcoinPriceSource(httpClient = client, baseUrl = "https://evil.example.com")

            val result = source.fetchPrice("EUR")

            result shouldBe null
            callCount.get() shouldBe 0
        }

        test("a non-allowlisted internal/link-local baseUrl host is rejected by the SSRF guard before any HTTP request is made") {
            val callCount = AtomicInteger(0)
            val client =
                mockClient { _ ->
                    callCount.incrementAndGet()
                    jsonResponse("""{"last":"1"}""")
                }
            val source = BitstampBitcoinPriceSource(httpClient = client, baseUrl = "https://169.254.169.254")

            val result = source.fetchPrice("EUR")

            result shouldBe null
            callCount.get() shouldBe 0
        }

        test("a plain-HTTP (non-HTTPS) allowlisted host is rejected by the SSRF guard before any HTTP request is made") {
            val callCount = AtomicInteger(0)
            val client =
                mockClient { _ ->
                    callCount.incrementAndGet()
                    jsonResponse("""{"error":[],"result":{"X":{"c":["1"]}}}""")
                }
            val source = KrakenBitcoinPriceSource(httpClient = client, baseUrl = "http://api.kraken.com")

            val result = source.fetchPrice("EUR")

            result shouldBe null
            callCount.get() shouldBe 0
        }

        test("requireAllowlistedHttpsUrl itself accepts every real allowlisted source host and rejects everything else") {
            requireAllowlistedHttpsUrl("https://api.coinbase.com/v2/prices/BTC-EUR/spot")
            requireAllowlistedHttpsUrl("https://api.kraken.com/0/public/Ticker?pair=XBTEUR")
            requireAllowlistedHttpsUrl("https://www.bitstamp.net/api/v2/ticker/btceur/")

            runCatching { requireAllowlistedHttpsUrl("https://evil.example.com/") }.isFailure shouldBe true
            runCatching { requireAllowlistedHttpsUrl("http://api.coinbase.com/") }.isFailure shouldBe true
        }

        // ── Response-size cap ──────────────────────────────────────────────

        test("an oversized response body maps to null instead of being parsed, no OOM") {
            val oversized = "{\"data\":{\"amount\":\"" + "1".repeat(MAX_ORACLE_RESPONSE_BYTES + 1) + "\"}}"
            val client = mockClient { jsonResponse(oversized) }
            val source = CoinbaseBitcoinPriceSource(httpClient = client)

            source.fetchPrice("EUR") shouldBe null
        }

        test("a response body exactly at the cap is still parsed normally") {
            val padding = "1".repeat(MAX_ORACLE_RESPONSE_BYTES - 40)
            // Padding lives in an ignored extra field so the body sits close to (but at/under) the
            // byte cap while still parsing to a valid price.
            val body = """{"last":"58000.00","extra":"$padding"}"""
            (body.toByteArray().size <= MAX_ORACLE_RESPONSE_BYTES) shouldBe true
            val client = mockClient { jsonResponse(body) }
            val source = BitstampBitcoinPriceSource(httpClient = client)

            val result = source.fetchPrice("EUR")

            (result?.price?.compareTo(BigDecimal("58000.00")) ?: -1) shouldBe 0
        }
    })
