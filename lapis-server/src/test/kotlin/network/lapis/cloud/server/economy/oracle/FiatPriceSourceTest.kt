package network.lapis.cloud.server.economy.oracle

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import java.math.BigDecimal
import java.util.concurrent.atomic.AtomicInteger

/**
 * Exercises both [EcbReferenceRateClient] (the shared ECB XML fetch/parse, also used by
 * `AlphaVantageGoldPriceSource`'s conversion leg) and [EcbFiatPriceSource] (the thin
 * [network.lapis.cloud.shared.domain.AnchorAsset.FIAT] wrapper over it) against a
 * [MockEngine]-backed [HttpClient] -- **never** the real `www.ecb.europa.eu`, same house rule
 * [BitcoinPriceSourceTest]/[GoldPriceSourceTest] follow.
 */
class FiatPriceSourceTest :
    FunSpec({
        fun mockClient(handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData) = HttpClient(MockEngine(handler))

        fun MockRequestHandleScope.xmlResponse(body: String) =
            respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "text/xml"))

        // A realistic, namespaced, single-quoted ECB daily-reference envelope -- shape verified
        // live against https://www.ecb.europa.eu/stats/eurofxref/eurofxref-daily.xml (2026-08-20).
        val realisticEnvelope =
            """
            <?xml version="1.0" encoding="UTF-8"?>
            <gesmes:Envelope xmlns:gesmes="http://www.gesmes.org/xml/2002-08-01" xmlns="http://www.ecb.int/vocabulary/2002-08-01/eurofxref">
                <gesmes:subject>Reference rates</gesmes:subject>
                <gesmes:Sender>
                    <gesmes:name>European Central Bank</gesmes:name>
                </gesmes:Sender>
                <Cube>
                    <Cube time='2026-08-19'>
                        <Cube currency='USD' rate='1.1605'/>
                        <Cube currency='JPY' rate='184.62'/>
                        <Cube currency='GBP' rate='0.85608'/>
                    </Cube>
                </Cube>
            </gesmes:Envelope>
            """.trimIndent()

        // ── EcbReferenceRateClient -- the shared fetch/parse ──────────────────────────────────────

        test(
            "EcbReferenceRateClient.ratePerEur parses a realistic namespaced envelope into the rate attribute verbatim, NOT its reciprocal",
        ) {
            val client = mockClient { xmlResponse(realisticEnvelope) }
            val rates = EcbReferenceRateClient(httpClient = client)

            val usdRate = rates.ratePerEur("USD")

            (usdRate?.compareTo(BigDecimal("1.1605")) ?: -1) shouldBe 0
            // The reciprocal (0.8617...) would be the classic base/quote inversion bug -- never this.
            val reciprocal = BigDecimal.ONE.divide(BigDecimal("1.1605"), 10, java.math.RoundingMode.HALF_UP)
            (usdRate?.compareTo(reciprocal) ?: 0) shouldBe 1
        }

        test("EcbReferenceRateClient.ratePerEur returns ONE for EUR without making any HTTP call at all") {
            val callCount = AtomicInteger(0)
            val client =
                mockClient {
                    callCount.incrementAndGet()
                    xmlResponse(realisticEnvelope)
                }
            val rates = EcbReferenceRateClient(httpClient = client)

            val eurRate = rates.ratePerEur("EUR")

            (eurRate?.compareTo(BigDecimal.ONE) ?: -1) shouldBe 0
            callCount.get() shouldBe 0
        }

        test("EcbReferenceRateClient.ratePerEur returns null for a currency absent from the feed") {
            val client = mockClient { xmlResponse(realisticEnvelope) }
            val rates = EcbReferenceRateClient(httpClient = client)

            rates.ratePerEur("XYZ") shouldBe null
        }

        test("EcbReferenceRateClient.ratePerEur is XXE-safe: a DOCTYPE with an external entity maps to null, never throws, never expands") {
            val maliciousBody =
                """
                <?xml version="1.0"?>
                <!DOCTYPE gesmes:Envelope [<!ENTITY xxe SYSTEM "file:///etc/passwd">]>
                <gesmes:Envelope xmlns:gesmes="http://www.gesmes.org/xml/2002-08-01" xmlns="http://www.ecb.int/vocabulary/2002-08-01/eurofxref">
                    <Cube><Cube time='2026-08-19'><Cube currency='USD' rate='&xxe;'/></Cube></Cube>
                </gesmes:Envelope>
                """.trimIndent()
            val client = mockClient { xmlResponse(maliciousBody) }
            val rates = EcbReferenceRateClient(httpClient = client)

            rates.ratePerEur("USD") shouldBe null
        }

        test("EcbReferenceRateClient.ratePerEur maps a non-2xx status, non-XML garbage, and an oversized body all to null") {
            val down = mockClient { respondError(HttpStatusCode.ServiceUnavailable, "down") }
            EcbReferenceRateClient(httpClient = down).ratePerEur("USD") shouldBe null

            val garbage = mockClient { xmlResponse("not xml at all {{{") }
            EcbReferenceRateClient(httpClient = garbage).ratePerEur("USD") shouldBe null

            val oversized = mockClient { xmlResponse("<Cube>" + "x".repeat(MAX_ORACLE_RESPONSE_BYTES + 1) + "</Cube>") }
            EcbReferenceRateClient(httpClient = oversized).ratePerEur("USD") shouldBe null
        }

        // ── EcbFiatPriceSource -- the thin FIAT wrapper ───────────────────────────────────────────

        test(
            "EcbFiatPriceSource with donationCurrency == EUR returns exactly 1 and makes ZERO HTTP calls (the FIAT anchor unit IS one EUR)",
        ) {
            val callCount = AtomicInteger(0)
            val client =
                mockClient {
                    callCount.incrementAndGet()
                    xmlResponse(realisticEnvelope)
                }
            val source = EcbFiatPriceSource(ecbRates = EcbReferenceRateClient(httpClient = client))

            val result = source.fetchPrice("EUR")

            result?.sourceId shouldBe "ecb"
            (result?.price?.compareTo(BigDecimal.ONE) ?: -1) shouldBe 0
            callCount.get() shouldBe 0
        }

        test("EcbFiatPriceSource with donationCurrency == USD returns the ECB rate verbatim (1.1605)") {
            val client = mockClient { xmlResponse(realisticEnvelope) }
            val source = EcbFiatPriceSource(ecbRates = EcbReferenceRateClient(httpClient = client))

            val result = source.fetchPrice("USD")

            (result?.price?.compareTo(BigDecimal("1.1605")) ?: -1) shouldBe 0
        }

        test("EcbFiatPriceSource returns null for a currency absent from the feed") {
            val client = mockClient { xmlResponse(realisticEnvelope) }
            val source = EcbFiatPriceSource(ecbRates = EcbReferenceRateClient(httpClient = client))

            source.fetchPrice("XYZ") shouldBe null
        }

        test("requireAllowlistedHttpsUrl accepts the real ECB host") {
            requireAllowlistedHttpsUrl("https://www.ecb.europa.eu/stats/eurofxref/eurofxref-daily.xml")
        }
    })
