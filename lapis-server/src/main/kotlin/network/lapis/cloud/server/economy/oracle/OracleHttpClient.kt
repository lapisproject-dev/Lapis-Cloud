package network.lapis.cloud.server.economy.oracle

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.URLProtocol
import io.ktor.http.Url
import io.ktor.serialization.kotlinx.json.json
import io.ktor.utils.io.readAvailable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.math.BigDecimal

/**
 * Hostnames every [PriceOracleSource] implementation is allowed to contact -- a frozen,
 * code-fixed allowlist, deliberately never derived from [network.lapis.cloud.shared.domain.PriceOracleConfigDto]
 * (that DTO carries no URL/host field at all -- see its own KDoc "SSRF invariant"). An ADMIN can
 * retune quorum/threshold/TTL/peg policy, but can never point a source at an arbitrary host.
 *
 * V0.6.6 extends the set from three [network.lapis.cloud.shared.domain.AnchorAsset.BITCOIN_BTC]
 * hosts to nine, spanning all three anchors: `www.goldapi.io`/`api.metalpriceapi.com`/
 * `www.alphavantage.co` for [network.lapis.cloud.shared.domain.AnchorAsset.GOLD_XAU], and
 * `www.ecb.europa.eu` for [network.lapis.cloud.shared.domain.AnchorAsset.FIAT] -- the latter is
 * reached from BOTH `EcbFiatPriceSource` directly AND `AlphaVantageGoldPriceSource`'s currency-
 * conversion leg (`EcbReferenceRateClient`, shared by both).
 */
internal val ORACLE_ALLOWED_HOSTS: Set<String> =
    setOf(
        "api.coinbase.com",
        "api.kraken.com",
        "www.bitstamp.net",
        "www.goldapi.io",
        "api.metalpriceapi.com",
        "www.alphavantage.co",
        "www.ecb.europa.eu",
    )

/** Hard cap on how many bytes of an oracle source's HTTP response body are ever read into memory -- see [readCappedBodyOrNull]. */
internal const val MAX_ORACLE_RESPONSE_BYTES = 64 * 1024

/** Scale used for every median/deviation/currency-conversion `BigDecimal` computation across the oracle package -- generous headroom above the DECIMAL(38,18) column precision, never `Double`, so no floating-point drift ever enters an amount that ends up minted as LTR. Shared by [network.lapis.cloud.server.economy.oracle.PriceOracleOrchestrator] and `AlphaVantageGoldPriceSource`'s currency-conversion leg. */
internal const val ORACLE_MATH_SCALE = 20

/** Shared, lenient JSON parser for oracle source response bodies (`ignoreUnknownKeys` -- every wire shape here is a best-effort subset of a real, larger third-party API response). */
internal val ORACLE_JSON: Json = Json { ignoreUnknownKeys = true }

/**
 * SSRF guard: every [PriceOracleSource] implementation must call this immediately before issuing
 * its HTTP request, with the exact URL it is about to fetch. Requires HTTPS and a host in
 * [ORACLE_ALLOWED_HOSTS] -- throws [IllegalArgumentException] otherwise (caught by the caller's
 * own catch-all, per [PriceOracleSource.fetchPrice] KDoc "must NEVER throw", and mapped to `null`
 * exactly like any other source failure).
 *
 * Belt-and-suspenders: every real source URL is a compile-time constant already pointing at an
 * allowlisted HTTPS host, so this guard never fires in production. It exists (a) as defense in
 * depth against a future edit accidentally deriving a URL from untrusted input, and (b) so
 * [BitcoinPriceSourceTest] can inject a non-allowlisted `baseUrl` and directly exercise the guard.
 */
internal fun requireAllowlistedHttpsUrl(urlString: String) {
    val url = Url(urlString)
    // Messages name the protocol/host ONLY -- never the full URL. Two of this wave's sources
    // (MetalpriceAPI, Alpha Vantage) carry their API key in the query string, and an
    // IllegalArgumentException message can end up in a stack trace, a test report, or a future log
    // statement.
    require(url.protocol == URLProtocol.HTTPS) { "Oracle source URL must be HTTPS (was ${url.protocol.name})" }
    require(url.host in ORACLE_ALLOWED_HOSTS) { "Oracle source host not allowlisted: ${url.host}" }
}

/**
 * A hardened [HttpClient] shared by every [PriceOracleSource] implementation --
 * [defaultBitcoinOracleSources]/`defaultGoldOracleSources`/`defaultFiatOracleSources` construct
 * every concrete source against ONE instance of this (constructed once, held by the
 * [PriceOracleOrchestrator] singleton, never per-request).
 *
 * - `followRedirects = false` -- no redirect can ever carry a request off the allowlisted host,
 *   closing the classic "allowlisted host 302s to an internal address" SSRF bypass.
 * - `expectSuccess = false` -- every call site inspects [HttpResponse.status] itself rather than
 *   relying on Ktor throwing on a non-2xx status, mirroring
 *   [network.lapis.cloud.server.postal.LetterxpressPostalMailProvider]'s own manual status check.
 * - [HttpTimeout] -- bounded connect/request/socket timeouts so one unresponsive source can never
 *   stall [PriceOracleOrchestrator.currentQuote]'s overall deadline indefinitely (DoS guard).
 *   `requestTimeoutMillis` is 8s (not 5s) as of V0.6.6 -- the ECB XML feed (`EcbReferenceRateClient`)
 *   is larger and slower than a JSON ticker; `connectTimeoutMillis` stays at 3s.
 * - Deliberately **no Ktor `Logging` plugin installed**, ever -- two of this wave's new sources
 *   (MetalpriceAPI, Alpha Vantage) carry their API key in the request URL's query string, and a
 *   request-logging plugin would put it straight into the application log. Every source instead
 *   logs only via [logSourceFailure] (source id + exception class name, never the URL/body).
 *
 *   **Avoiding the `Logging` plugin is NOT sufficient on its own** (Security-Audit-Runde 1 / S2):
 *   Ktor 3.5.1's OWN internal client plugins -- `SaveBody`, `HttpTimeout`, `HttpCallValidator` --
 *   independently log `request.url` verbatim at TRACE level on code paths every request through this
 *   client always executes (e.g. `SaveBody` logs on every response, `Logging`-plugin-free or not).
 *   An operator who raises the root or `io.ktor` logger level to DEBUG/TRACE to diagnose a failing
 *   gold source -- a normal troubleshooting step -- would leak the query-string API keys unless
 *   something else floors that namespace. `lapis-server/src/main/resources/logback.xml` carries an
 *   explicit `<logger name="io.ktor.client" level="INFO"/>` floor for exactly this reason; do not
 *   remove it, and do not assume this function's own plugin choice is a complete mitigation without
 *   that floor also being in place.
 */
internal fun oracleHttpClient(): HttpClient =
    HttpClient(CIO) {
        install(ContentNegotiation) {
            json(ORACLE_JSON)
        }
        install(HttpTimeout) {
            requestTimeoutMillis = 8_000
            connectTimeoutMillis = 3_000
            socketTimeoutMillis = 8_000
        }
        expectSuccess = false
        followRedirects = false
    }

/**
 * Reads [this] response's body bounded to [MAX_ORACLE_RESPONSE_BYTES] into a fresh array -- this
 * function's OWN read loop never buffers more than that many bytes. Returns `null` if the cap is hit
 * (the body is discarded, not partially parsed) -- treated by every call site exactly like any other
 * source failure (see [PriceOracleSource.fetchPrice] KDoc).
 *
 * **Scope of the guarantee (corrected, Security-Audit-Runde 1 / S3)**: every current call site uses
 * the non-streaming `httpClient.get(url)`/`httpClient.get(url) { ... }` request form, under which
 * Ktor 3.5.1's internal `SaveBody` plugin has already buffered the ENTIRE response body into memory
 * before this function -- or any of this codebase's code -- ever runs. This cap therefore bounds the
 * cost of the PARSE/PROCESSING step that follows (and this function's own extra copy), but it does
 * **NOT** bound how much a single peer response can make the JVM buffer -- a malicious or buggy
 * allowlisted host streaming at line rate for the full request timeout (8s as of V0.6.6, across 4
 * newly-allowlisted hosts, with gold fanning out to 3 sources in parallel) could still make `SaveBody`
 * buffer hundreds of MB to roughly 1GB per source before this function is ever reached. Genuinely
 * closing that gap requires switching every source to Ktor's streaming
 * `prepareRequest(url) { ... }.execute { response -> ... }` idiom (reading/capping directly off
 * [HttpResponse.bodyAsChannel] before the body is materialized) -- evaluated this round and deferred
 * as a larger, call-site-shape-changing restructuring across all 7 current call sites (3x
 * `BitcoinPriceSources.kt`, 3x `GoldPriceSources.kt`, 1x `EcbReferenceRateClient.kt`) rather than a
 * localized fix; revisit if the allowlisted hosts or timeout window grow further.
 */
internal suspend fun HttpResponse.readCappedBodyOrNull(): ByteArray? {
    val channel = bodyAsChannel()
    val buffer = ByteArray(MAX_ORACLE_RESPONSE_BYTES + 1)
    var total = 0
    while (total < buffer.size) {
        val read = channel.readAvailable(buffer, total, buffer.size - total)
        if (read == -1) break
        total += read
    }
    return if (total > MAX_ORACLE_RESPONSE_BYTES) null else buffer.copyOf(total)
}

/**
 * Reads [key] from this JSON object as an exact [BigDecimal] built from its LITERAL text --
 * handles a JSON number (GoldAPI.io, MetalpriceAPI) and a JSON string (the Bitcoin sources' quoted
 * amounts) uniformly, and never round-trips through [Double] (no floating-point drift into a price
 * that mints real LTR -- the same reasoning `PriceOracleOrchestrator`'s `ORACLE_MATH_SCALE` KDoc
 * gives). Returns `null` on a missing key, a non-primitive value, or an unparseable literal.
 */
internal fun JsonObject.decimalOrNull(key: String): BigDecimal? =
    (this[key] as? JsonPrimitive)?.content?.let { runCatching { BigDecimal(it) }.getOrNull() }
