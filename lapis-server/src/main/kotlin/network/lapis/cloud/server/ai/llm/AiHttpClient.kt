package network.lapis.cloud.server.ai.llm

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.network.sockets.ConnectTimeoutException
import io.ktor.client.network.sockets.SocketTimeoutException
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.header
import io.ktor.client.request.preparePost
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentLength
import io.ktor.http.contentType
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.CancellationException
import network.lapis.cloud.server.ai.config.AiBaseUrlGuard
import network.lapis.cloud.server.ai.config.AiConfig

/**
 * A hardened [HttpClient] for the LLM providers, built exactly like
 * `network.lapis.cloud.server.economy.oracle.oracleHttpClient`:
 *
 * - `followRedirects = false` -- no redirect can carry the request (and its API-key header) to
 *   another host.
 * - `expectSuccess = false` -- status codes are inspected explicitly.
 * - bounded connect/request/socket timeouts (DoS guard).
 * - Deliberately **no Ktor `Logging` plugin, ever**: it would put request headers (the API key) and
 *   URLs into the application log. **That alone is not sufficient** -- Ktor's own internal plugins
 *   log `request.url` at TRACE on every request; `logback.xml` therefore floors `io.ktor.client`
 *   at INFO. Do not remove that floor. (`AiSecretsNotLoggedTest` scans this package for a
 *   `Logging` install.)
 * - No `ContentNegotiation`: request bodies are built as JSON strings and responses are parsed by
 *   hand, so no content-type sniffing or deserializer runs on provider output.
 */
internal fun aiHttpClient(config: AiConfig): HttpClient =
    HttpClient(CIO) {
        install(HttpTimeout) {
            connectTimeoutMillis = config.connectTimeoutMs
            requestTimeoutMillis = config.requestTimeoutMs
            socketTimeoutMillis = config.requestTimeoutMs
        }
        expectSuccess = false
        followRedirects = false
    }

/** Status plus a size-capped body; [body] is `null` iff the response exceeded the cap. */
internal class RawAiResponse(
    val status: Int,
    val body: ByteArray?,
)

/**
 * POSTs [jsonBody] to [url] after re-validating it against [pinnedBaseUrl] (see
 * [AiBaseUrlGuard.requireAllowedRequestUrl]) and reads the response through
 * `prepareRequest { }.execute { }` so the body is read straight off the channel and capped at
 * [maxBytes] **before** it is materialized -- unlike the non-streaming `get(url)` form (where
 * Ktor's `SaveBody` plugin buffers everything first, the gap `OracleHttpClient` documents as open).
 */
internal suspend fun HttpClient.postJsonCapped(
    url: String,
    pinnedBaseUrl: String,
    headers: Map<String, String>,
    jsonBody: String,
    maxBytes: Int,
): RawAiResponse {
    AiBaseUrlGuard.requireAllowedRequestUrl(urlString = url, pinnedBaseUrl = pinnedBaseUrl)
    return preparePost(url) {
        headers.forEach { (name, value) -> header(name, value) }
        header(HttpHeaders.Accept, ContentType.Application.Json.toString())
        contentType(ContentType.Application.Json)
        setBody(jsonBody)
    }.execute { response ->
        RawAiResponse(status = response.status.value, body = response.readCappedAiBodyOrNull(maxBytes))
    }
}

/**
 * Reads at most [maxBytes] of this response body; `null` if the body is larger (discarded, never
 * partially parsed). A declared `Content-Length` above the cap short-circuits without reading.
 */
internal suspend fun HttpResponse.readCappedAiBodyOrNull(maxBytes: Int): ByteArray? {
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

/**
 * Runs [block] and maps every failure to an [LlmResult.Failure] -- the [LlmClient.complete] "never
 * throws" contract. Only coroutine cancellation is re-thrown. Exception messages are dropped on
 * purpose (they can embed URLs or bodies).
 */
internal suspend fun guardedLlmCall(block: suspend () -> LlmResult): LlmResult =
    try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (_: HttpRequestTimeoutException) {
        LlmResult.Failure(kind = LlmFailureKind.TIMEOUT)
    } catch (_: ConnectTimeoutException) {
        LlmResult.Failure(kind = LlmFailureKind.TIMEOUT)
    } catch (_: SocketTimeoutException) {
        LlmResult.Failure(kind = LlmFailureKind.TIMEOUT)
    } catch (_: Exception) {
        LlmResult.Failure(kind = LlmFailureKind.TRANSPORT)
    }

/** Maps a non-2xx status to the failure kind; `null` for a 2xx. */
internal fun statusFailureOrNull(status: Int): LlmResult.Failure? =
    when {
        status in 200..299 -> null
        status == 429 -> LlmResult.Failure(kind = LlmFailureKind.UPSTREAM_RATE_LIMITED)
        else -> LlmResult.Failure(kind = LlmFailureKind.UPSTREAM_ERROR)
    }
