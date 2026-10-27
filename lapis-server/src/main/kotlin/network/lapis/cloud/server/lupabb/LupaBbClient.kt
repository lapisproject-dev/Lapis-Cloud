package network.lapis.cloud.server.lupabb

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.auth.Auth
import io.ktor.client.plugins.auth.providers.DigestAuthCredentials
import io.ktor.client.plugins.auth.providers.digest
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpHeaders
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.CancellationException

/**
 * V1.9.226 -- the read-only client of the LupaBB API (HTTP Digest plus API key). One instance per run, closed with `use {}`.
 *
 * Hardening, all of it pinned by `LupaBbClientTest`:
 *  - the host is fixed ([LupaBbEndpoint.PRODUCTION]); redirects are never followed (a 3xx is an unexpected response); only GET;
 *  - connect / request / socket timeouts, a hard cap per response body (checked against `Content-Length` and while reading), no
 *    decompression (`Accept-Encoding: identity`, no ContentEncoding plugin);
 *  - NO Ktor `Logging` plugin, and every Ktor or IO exception is translated into a [LupaBbFetchException] WITHOUT its cause: such an exception
 *    carries the request URL, and with [ApiKeyTransport.QUERY] that URL contains the API key. `logback.xml` additionally floors `io.ktor.client`
 *    at INFO (Ktor's own plugins log the URL at TRACE).
 */
internal class LupaBbClient(
    private val endpoint: LupaBbEndpoint,
    credentials: LupaBbCredentials,
    private val transport: ApiKeyTransport,
    private val limits: LupaBbLimits = LupaBbLimits.DEFAULT,
) : AutoCloseable {
    private val apiKey: String = credentials.apiKey

    private val client: HttpClient =
        HttpClient(CIO) {
            install(HttpTimeout) {
                connectTimeoutMillis = limits.connectTimeoutMillis
                requestTimeoutMillis = limits.requestTimeoutMillis
                socketTimeoutMillis = limits.socketTimeoutMillis
            }
            install(Auth) {
                digest {
                    credentials { DigestAuthCredentials(username = credentials.username, password = credentials.password) }
                    realm = null
                }
            }
            followRedirects = false
            expectSuccess = false
        }

    suspend fun fetchStatuses(): List<LupaBbRef> = LupaBbWire.parseRefs(get(path = "mitgliederstatus"))

    suspend fun fetchCountries(): List<LupaBbRef> = LupaBbWire.parseRefs(get(path = "land"))

    suspend fun fetchRegionalChapters(): List<LupaBbRef> = LupaBbWire.parseRefs(get(path = "landesverband"))

    suspend fun fetchMembersPage(page: Int): LupaBbMemberPage = LupaBbWire.parseMemberPage(get(path = "mitglieder", page = page))

    /** One cheap request that proves user, password and API key. */
    suspend fun probe() {
        fetchStatuses()
    }

    private suspend fun get(
        path: String,
        page: Int? = null,
    ): String =
        try {
            client
                .prepareGet(endpoint.baseUrl + path) {
                    header(HttpHeaders.AcceptEncoding, "identity")
                    header(HttpHeaders.Accept, "application/json")
                    if (page != null) {
                        parameter("modus", "full")
                        parameter("page", page)
                    }
                    when (transport) {
                        ApiKeyTransport.HEADER -> header(API_KEY_NAME, apiKey)
                        ApiKeyTransport.QUERY -> parameter(API_KEY_NAME, apiKey)
                    }
                }.execute { response -> readChecked(response) }
        } catch (e: LupaBbFetchException) {
            throw e
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // NEVER attach [e]: its message and cause chain carry the URL (and, for the query transport, the API key)
            throw LupaBbFetchException(if (isTimeout(e)) LupaBbErrorCode.TIMEOUT else LupaBbErrorCode.UNAVAILABLE)
        }

    private suspend fun readChecked(response: HttpResponse): String {
        val status = response.status.value
        when {
            status == 401 || status == 403 -> throw LupaBbFetchException(LupaBbErrorCode.AUTH_REJECTED)
            status >= 500 -> throw LupaBbFetchException(LupaBbErrorCode.UNAVAILABLE)
            status !in 200..299 -> throw LupaBbFetchException(LupaBbErrorCode.UNEXPECTED_RESPONSE)
        }
        val declared = response.headers[HttpHeaders.ContentLength]?.toLongOrNull()
        if (declared != null && declared > limits.maxResponseBytes) throw LupaBbFetchException(LupaBbErrorCode.LIMIT_EXCEEDED)
        val channel = response.bodyAsChannel()
        val buffer = ByteArray(limits.maxResponseBytes + 1)
        var total = 0
        while (total < buffer.size) {
            val read = channel.readAvailable(buffer, total, buffer.size - total)
            if (read == -1) break
            total += read
        }
        if (total > limits.maxResponseBytes) throw LupaBbFetchException(LupaBbErrorCode.LIMIT_EXCEEDED)
        return String(buffer, 0, total, Charsets.UTF_8)
    }

    private fun isTimeout(e: Throwable): Boolean {
        var current: Throwable? = e
        var depth = 0
        while (current != null && depth < MAX_CAUSE_DEPTH) {
            if (current::class.simpleName?.contains("Timeout", ignoreCase = true) == true) return true
            current = current.cause
            depth++
        }
        return false
    }

    override fun close() {
        client.close()
    }

    private companion object {
        const val API_KEY_NAME = "X-API-KEY"
        const val MAX_CAUSE_DEPTH = 8
    }
}
