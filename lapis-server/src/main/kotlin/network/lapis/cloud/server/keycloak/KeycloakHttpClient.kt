package network.lapis.cloud.server.keycloak

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.contentLength
import io.ktor.utils.io.readAvailable

/**
 * A hardened [HttpClient] for talking to the operator's own, pinned Keycloak issuer -- built
 * exactly like `network.lapis.cloud.server.ai.llm.aiHttpClient`:
 *
 * - `followRedirects = false` -- no redirect can carry a request to another host.
 * - `expectSuccess = false` -- status codes are inspected explicitly by the caller.
 * - bounded connect/request/socket timeouts (DoS guard against a slow/hung Keycloak).
 * - Deliberately **no Ktor `Logging` plugin, ever** -- see `AiHttpClient` KDoc for why (it would put
 *   request headers and URLs, here client-secret-bearing token-exchange calls made by sub-wave 1b,
 *   into the application log; Ktor's own internal plugins additionally log `request.url` at TRACE,
 *   which is why `logback.xml` floors `io.ktor.client` at INFO -- do not remove that floor).
 * - No `ContentNegotiation`: response bodies are read as capped byte arrays and parsed by hand
 *   (see [readCappedKeycloakBodyOrNull]/`KeycloakOidcMetadata`), so no deserializer runs on
 *   unbounded/untrusted issuer output.
 *
 * [connectTimeoutMs]/[requestTimeoutMs] default to the same bounds `AiHttpClient`/`oracleHttpClient`
 * use for third-party HTTP calls in this codebase.
 */
internal fun keycloakHttpClient(
    connectTimeoutMs: Long = DEFAULT_CONNECT_TIMEOUT_MS,
    requestTimeoutMs: Long = DEFAULT_REQUEST_TIMEOUT_MS,
): HttpClient =
    HttpClient(CIO) {
        install(HttpTimeout) {
            connectTimeoutMillis = connectTimeoutMs
            requestTimeoutMillis = requestTimeoutMs
            socketTimeoutMillis = requestTimeoutMs
        }
        expectSuccess = false
        followRedirects = false
    }

internal const val DEFAULT_CONNECT_TIMEOUT_MS: Long = 5_000L
internal const val DEFAULT_REQUEST_TIMEOUT_MS: Long = 10_000L

/** Status plus a size-capped body; [body] is `null` iff the response exceeded the cap. */
internal class RawKeycloakResponse(
    val status: Int,
    val body: ByteArray?,
)

/**
 * GETs [url] after re-validating it against [pinnedIssuerUrl] (see
 * [KeycloakIssuerUrlGuard.requireAllowedRequestUrl]) -- the target must always be an endpoint of
 * the configured issuer's own well-known/JWKS surface, never attacker-controlled input, so this
 * client can never become an open SSRF relay even if a future caller builds a URL from a claim or
 * a query parameter.
 */
internal suspend fun HttpClient.getCapped(
    url: String,
    pinnedIssuerUrl: String,
    maxBytes: Int,
): RawKeycloakResponse {
    KeycloakIssuerUrlGuard.requireAllowedRequestUrl(urlString = url, pinnedIssuerUrl = pinnedIssuerUrl)
    val response = get(url)
    return RawKeycloakResponse(status = response.status.value, body = response.readCappedKeycloakBodyOrNull(maxBytes))
}

/**
 * Reads at most [maxBytes] of this response body; `null` if the body is larger (discarded, never
 * partially parsed). A declared `Content-Length` above the cap short-circuits without reading --
 * mirrors `AiHttpClient.readCappedAiBodyOrNull`.
 */
internal suspend fun HttpResponse.readCappedKeycloakBodyOrNull(maxBytes: Int): ByteArray? {
    val declared = contentLength()
    if (declared != null && declared > maxBytes) return null
    val channel = bodyAsChannel()
    val buffer = ByteArray(maxBytes + 1)
    var total = 0
    while (total < buffer.size) {
        val read = channel.readAvailable(buffer, total, buffer.size - total)
        if (read == -1) break
        total += read
    }
    return if (total > maxBytes) null else buffer.copyOf(total)
}

/** Hard cap on discovery-document/JWKS response size -- both are small, fixed-shape documents; anything larger is refused rather than parsed. */
internal const val MAX_KEYCLOAK_RESPONSE_BYTES: Int = 256 * 1024
