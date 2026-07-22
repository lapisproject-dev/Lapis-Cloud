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

/**
 * Hostnames every [PriceOracleSource] implementation is allowed to contact -- a frozen,
 * code-fixed allowlist, deliberately never derived from [network.lapis.cloud.shared.domain.PriceOracleConfigDto]
 * (that DTO carries no URL/host field at all -- see its own KDoc "SSRF invariant"). An ADMIN can
 * retune quorum/threshold/TTL/peg policy, but can never point a source at an arbitrary host.
 */
internal val ORACLE_ALLOWED_HOSTS: Set<String> = setOf("api.coinbase.com", "api.kraken.com", "www.bitstamp.net")

/** Hard cap on how many bytes of an oracle source's HTTP response body are ever read into memory -- see [readCappedBodyOrNull]. */
internal const val MAX_ORACLE_RESPONSE_BYTES = 64 * 1024

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
    require(url.protocol == URLProtocol.HTTPS) { "Oracle source URL must be HTTPS: $urlString" }
    require(url.host in ORACLE_ALLOWED_HOSTS) { "Oracle source host not allowlisted: ${url.host}" }
}

/**
 * A hardened [HttpClient] shared by every [PriceOracleSource] implementation --
 * [defaultBitcoinOracleSources] constructs the three concrete BTC sources against ONE instance of
 * this (constructed once, held by the [PriceOracleOrchestrator] singleton, never per-request).
 *
 * - `followRedirects = false` -- no redirect can ever carry a request off the allowlisted host,
 *   closing the classic "allowlisted host 302s to an internal address" SSRF bypass.
 * - `expectSuccess = false` -- every call site inspects [HttpResponse.status] itself rather than
 *   relying on Ktor throwing on a non-2xx status, mirroring
 *   [network.lapis.cloud.server.postal.LetterxpressPostalMailProvider]'s own manual status check.
 * - [HttpTimeout] -- bounded connect/request/socket timeouts (3-5s) so one unresponsive source can
 *   never stall [PriceOracleOrchestrator.currentQuote]'s overall deadline indefinitely (DoS guard).
 */
internal fun oracleHttpClient(): HttpClient =
    HttpClient(CIO) {
        install(ContentNegotiation) {
            json(ORACLE_JSON)
        }
        install(HttpTimeout) {
            requestTimeoutMillis = 5_000
            connectTimeoutMillis = 3_000
            socketTimeoutMillis = 5_000
        }
        expectSuccess = false
        followRedirects = false
    }

/**
 * Reads [this] response's body bounded to [MAX_ORACLE_RESPONSE_BYTES] -- never buffers more than
 * that many bytes into memory regardless of how large (or how long-streaming) the actual response
 * is, closing the "oracle host returns gigabytes / never closes the stream" DoS vector. Returns
 * `null` if the cap is hit (the body is discarded, not partially parsed) -- treated by every call
 * site exactly like any other source failure (see [PriceOracleSource.fetchPrice] KDoc).
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
