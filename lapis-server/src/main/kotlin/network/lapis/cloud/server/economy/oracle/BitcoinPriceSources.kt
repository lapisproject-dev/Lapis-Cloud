package network.lapis.cloud.server.economy.oracle

import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.http.isSuccess
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import network.lapis.cloud.shared.domain.AnchorAsset
import java.math.BigDecimal
import kotlin.time.Clock

private val logger = KotlinLogging.logger {}

/** Logs an oracle source failure -- sanitized (source id + exception class name only, never the response body or raw exception message). See [CoinbaseBitcoinPriceSource] KDoc "Error handling / SSRF / DoS". */
private fun logSourceFailure(
    sourceId: String,
    cause: Throwable,
) {
    logger.warn { "Oracle source '$sourceId' failed (${cause::class.simpleName ?: "unknown error"})" }
}

/**
 * `GET https://api.coinbase.com/v2/prices/BTC-{currency}/spot` -- Coinbase's public, no-auth
 * spot-price endpoint. One of three real, independent [AnchorAsset.BITCOIN_BTC] sources --
 * see [KrakenBitcoinPriceSource]/[BitstampBitcoinPriceSource] siblings and
 * [defaultBitcoinOracleSources].
 *
 * ## !! WIRE FORMAT NOT VERIFIED AGAINST LIVE/CURRENT COINBASE/KRAKEN/BITSTAMP DOCUMENTATION !!
 *
 * This sandbox has no network egress to `api.coinbase.com`/`api.kraken.com`/`www.bitstamp.net`
 * (egress is allow-listed to a small set of package registries only) -- the three response JSON
 * shapes in this file ([CoinbaseSpotResponse]/[KrakenTickerResponse]/[BitstampTickerResponse]) are
 * a best-effort reconstruction from general knowledge of how these public, no-API-key spot/ticker
 * endpoints are documented to work, **not** something fetched or checked against their current
 * live API reference. Same disclosed-uncertainty discipline as
 * [network.lapis.cloud.server.postal.LetterxpressPostalMailProvider]'s own KDoc -- ship the shape,
 * flag it loudly, let a human check it against live docs before production use.
 *
 * A human MUST verify before this feeds a real [PriceOracleOrchestrator]: exact endpoint paths,
 * exact JSON field names/casing, whether Kraken's pair-renaming convention (e.g. `XBTEUR` ->
 * `XXBTZEUR` in the response) still holds, and whether any of the three now requires
 * authentication/rate-limiting headers this implementation does not send.
 *
 * ## Independence
 *
 * All three sources are free, public, no-API-key endpoints operated by three different exchanges
 * -- chosen specifically so [PriceOracleOrchestrator]'s >=2-of-3 quorum/outlier-rejection/spread-
 * cap design has genuinely independent data points to work with, not three mirrors of the same
 * upstream feed.
 *
 * ## Error handling / SSRF / DoS
 *
 * Every source in this file calls [requireAllowlistedHttpsUrl] before issuing its request, reads
 * the response body via [readCappedBodyOrNull] (bounded to [MAX_ORACLE_RESPONSE_BYTES]), and
 * catches every exception -- never propagating out of [PriceOracleSource.fetchPrice] (see that
 * interface's KDoc). On any failure, only the source id and the exception's class name are logged
 * (via [logSourceFailure]) -- never the response body or the raw exception message (same
 * discipline [network.lapis.cloud.server.postal.LetterxpressPostalMailProvider] already
 * establishes for its own outbound HTTP call).
 */
class CoinbaseBitcoinPriceSource(
    private val httpClient: HttpClient,
    private val baseUrl: String = "https://api.coinbase.com",
    override val id: String = "coinbase",
) : PriceOracleSource {
    override val anchor: AnchorAsset = AnchorAsset.BITCOIN_BTC

    override suspend fun fetchPrice(donationCurrency: String): SourcePriceResult? {
        val url = "$baseUrl/v2/prices/BTC-${donationCurrency.uppercase()}/spot"
        return try {
            requireAllowlistedHttpsUrl(url)
            val response = httpClient.get(url)
            if (!response.status.isSuccess()) return null
            val bytes = response.readCappedBodyOrNull() ?: return null
            val parsed = ORACLE_JSON.decodeFromString<CoinbaseSpotResponse>(bytes.decodeToString())
            val amount = parsed.data?.amount ?: return null
            val price = runCatching { BigDecimal(amount) }.getOrNull() ?: return null
            if (price.signum() <= 0) return null
            SourcePriceResult(sourceId = id, price = price, observedAt = Clock.System.now())
        } catch (e: Exception) {
            logSourceFailure(id, e)
            null
        }
    }
}

/**
 * `GET https://api.kraken.com/0/public/Ticker?pair=XBT{currency}` -- Kraken's public, no-auth
 * ticker endpoint. Kraken renames the pair in its response (e.g. `XBTEUR` -> `XXBTZEUR`), so the
 * single entry of `result` is read positionally rather than by a hardcoded key. See
 * [CoinbaseBitcoinPriceSource] KDoc for the shared wire-format disclosure and error-handling
 * discipline this sibling class follows identically.
 */
class KrakenBitcoinPriceSource(
    private val httpClient: HttpClient,
    private val baseUrl: String = "https://api.kraken.com",
    override val id: String = "kraken",
) : PriceOracleSource {
    override val anchor: AnchorAsset = AnchorAsset.BITCOIN_BTC

    override suspend fun fetchPrice(donationCurrency: String): SourcePriceResult? {
        val url = "$baseUrl/0/public/Ticker?pair=XBT${donationCurrency.uppercase()}"
        return try {
            requireAllowlistedHttpsUrl(url)
            val response = httpClient.get(url)
            if (!response.status.isSuccess()) return null
            val bytes = response.readCappedBodyOrNull() ?: return null
            val parsed = ORACLE_JSON.decodeFromString<KrakenTickerResponse>(bytes.decodeToString())
            if (parsed.error.isNotEmpty()) return null
            val lastTradeClose =
                parsed.result.values
                    .firstOrNull()
                    ?.c
                    ?.firstOrNull() ?: return null
            val price = runCatching { BigDecimal(lastTradeClose) }.getOrNull() ?: return null
            if (price.signum() <= 0) return null
            SourcePriceResult(sourceId = id, price = price, observedAt = Clock.System.now())
        } catch (e: Exception) {
            logSourceFailure(id, e)
            null
        }
    }
}

/**
 * `GET https://www.bitstamp.net/api/v2/ticker/btc{currency}/` -- Bitstamp's public, no-auth
 * ticker endpoint. See [CoinbaseBitcoinPriceSource] KDoc for the shared wire-format disclosure and
 * error-handling discipline this sibling class follows identically.
 */
class BitstampBitcoinPriceSource(
    private val httpClient: HttpClient,
    private val baseUrl: String = "https://www.bitstamp.net",
    override val id: String = "bitstamp",
) : PriceOracleSource {
    override val anchor: AnchorAsset = AnchorAsset.BITCOIN_BTC

    override suspend fun fetchPrice(donationCurrency: String): SourcePriceResult? {
        val url = "$baseUrl/api/v2/ticker/btc${donationCurrency.lowercase()}/"
        return try {
            requireAllowlistedHttpsUrl(url)
            val response = httpClient.get(url)
            if (!response.status.isSuccess()) return null
            val bytes = response.readCappedBodyOrNull() ?: return null
            val parsed = ORACLE_JSON.decodeFromString<BitstampTickerResponse>(bytes.decodeToString())
            val last = parsed.last ?: return null
            val price = runCatching { BigDecimal(last) }.getOrNull() ?: return null
            if (price.signum() <= 0) return null
            SourcePriceResult(sourceId = id, price = price, observedAt = Clock.System.now())
        } catch (e: Exception) {
            logSourceFailure(id, e)
            null
        }
    }
}

/** The three real, independent BTC sources sharing one [oracleHttpClient] -- constructed once by `Application.module`, never per-request. */
fun defaultBitcoinOracleSources(httpClient: HttpClient = oracleHttpClient()): List<PriceOracleSource> =
    listOf(
        CoinbaseBitcoinPriceSource(httpClient),
        KrakenBitcoinPriceSource(httpClient),
        BitstampBitcoinPriceSource(httpClient),
    )

// ── Wire shapes -- see CoinbaseBitcoinPriceSource class KDoc, NOT verified against live Coinbase/Kraken/Bitstamp docs ──

@Serializable
private data class CoinbaseSpotData(
    val amount: String? = null,
)

@Serializable
private data class CoinbaseSpotResponse(
    val data: CoinbaseSpotData? = null,
)

@Serializable
private data class KrakenTickerEntry(
    val c: List<String> = emptyList(),
)

@Serializable
private data class KrakenTickerResponse(
    val error: List<String> = emptyList(),
    val result: Map<String, KrakenTickerEntry> = emptyMap(),
)

@Serializable
private data class BitstampTickerResponse(
    @SerialName("last") val last: String? = null,
)
