package network.lapis.cloud.server.economy.oracle

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.http.isSuccess
import java.io.ByteArrayInputStream
import java.math.BigDecimal
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory

/**
 * The abstraction [AlphaVantageGoldPriceSource]/[EcbFiatPriceSource] depend on -- lets tests
 * substitute a deterministic fake (see `GoldPriceSourceTest`/`FiatPriceSourceTest`) without
 * duplicating [EcbReferenceRateClient]'s conversion-consuming logic in a parallel test-only
 * reimplementation. [EcbReferenceRateClient] is the one real, network-backed implementation.
 */
interface EcbRateSource {
    /**
     * How many units of [currency] one EUR buys -- e.g. `1.1605` for `"USD"`. Returns
     * [BigDecimal.ONE] for `"EUR"`. Returns `null` on any failure -- never throws. See
     * [EcbReferenceRateClient.ratePerEur] for the real implementation's full contract.
     */
    suspend fun ratePerEur(currency: String): BigDecimal?
}

/**
 * The ECB daily reference-rate fetch, extracted so BOTH the FIAT anchor's own `EcbFiatPriceSource`
 * AND the GOLD anchor's `AlphaVantageGoldPriceSource` currency-conversion leg share ONE hardened,
 * XXE-safe implementation instead of two copies of the same XML parse.
 *
 * Deliberately NOT a [PriceOracleSource]: it has no [PriceOracleSource.anchor], never participates
 * in a quorum, and never appears in `price_oracle_conversion.sources_used`. It is an implementation
 * detail of the two things that use it.
 *
 * `GET https://www.ecb.europa.eu/stats/eurofxref/eurofxref-daily.xml` -- keyless, no rate limit
 * encountered, published ~16:00 CET on TARGET working days (concertation ~14:10 CET); on weekends/
 * holidays the feed holds the last working day's rates, absorbed by the 12h refresh + the
 * ADMIN-configured cache TTL. Response envelope uses two namespaces (`gesmes` for the envelope, an
 * unprefixed default namespace for the `Cube` elements) and single-quoted attributes -- looked up
 * by the `currency` attribute, never by position, and matched by bare tag name (`Cube`) rather than
 * a namespace-qualified lookup, which is tolerant of both.
 */
class EcbReferenceRateClient(
    private val httpClient: HttpClient,
    private val baseUrl: String = "https://www.ecb.europa.eu",
) : EcbRateSource {
    /**
     * How many units of [currency] one EUR buys, per the ECB daily reference feed -- e.g. `1.1605`
     * for `"USD"`. The feed is EUR-based (`rate='1.1605'` for USD means *1 EUR = 1.1605 USD*), so
     * this is the `rate` attribute read verbatim, **never inverted** -- a generic "ECB rates are
     * EUR-based, so invert them" reflex is wrong here. Returns [BigDecimal.ONE] for `"EUR"` with
     * **no HTTP call at all**. Returns `null` on any failure (non-2xx, unparseable/oversized body,
     * currency absent from the feed, XXE/DOCTYPE in the body) -- never throws.
     */
    override suspend fun ratePerEur(currency: String): BigDecimal? {
        val cur = currency.uppercase()
        if (cur == "EUR") return BigDecimal.ONE
        val url = "$baseUrl/stats/eurofxref/eurofxref-daily.xml"
        return try {
            requireAllowlistedHttpsUrl(url)
            val response = httpClient.get(url)
            if (!response.status.isSuccess()) return null
            val bytes = response.readCappedBodyOrNull() ?: return null
            parseRatePerEur(bytes = bytes, currency = cur)
        } catch (e: Exception) {
            logSourceFailure(sourceId = "ecb", cause = e)
            null
        }
    }

    /**
     * The XXE-hardened XML parse itself, isolated in its own `try`/`catch` so a `SAXParseException`
     * on a DOCTYPE (or any other parse failure) maps to `null` exactly like every other failure
     * path, without ever escaping to the caller.
     */
    private fun parseRatePerEur(
        bytes: ByteArray,
        currency: String,
    ): BigDecimal? =
        try {
            val factory = DocumentBuilderFactory.newInstance()
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false)
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false)
            factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "")
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "")
            factory.isXIncludeAware = false
            factory.isExpandEntityReferences = false
            val document = factory.newDocumentBuilder().parse(ByteArrayInputStream(bytes))
            val cubes = document.getElementsByTagName("Cube")
            var rate: BigDecimal? = null
            for (i in 0 until cubes.length) {
                val attributes = cubes.item(i).attributes ?: continue
                val cubeCurrency = attributes.getNamedItem("currency")?.nodeValue ?: continue
                if (!cubeCurrency.equals(currency, ignoreCase = true)) continue
                val rateText = attributes.getNamedItem("rate")?.nodeValue ?: continue
                rate = runCatching { BigDecimal(rateText) }.getOrNull()
                break
            }
            rate
        } catch (e: Exception) {
            logSourceFailure(sourceId = "ecb", cause = e)
            null
        }
}
