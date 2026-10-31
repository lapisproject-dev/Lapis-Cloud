package network.lapis.cloud.server.partner

import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentLength
import io.ktor.http.contentType
import io.ktor.network.tls.TLSException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import network.lapis.cloud.server.federation.FederationTimeouts
import network.lapis.cloud.server.federation.SafeFederationTarget
import network.lapis.cloud.server.federation.federationHttpClientWithTimeouts
import network.lapis.cloud.server.federation.readCappedFederationBodyOrNull
import network.lapis.cloud.server.webhook.WebhookUrlCheck
import network.lapis.cloud.server.webhook.checkWebhookUrl
import network.lapis.cloud.shared.domain.PartnerFetchErrorCode
import java.net.ConnectException
import java.net.SocketTimeoutException
import javax.net.ssl.SSLException
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlin.uuid.Uuid

private val logger = KotlinLogging.logger {}

internal sealed interface PartnerFetchOutcome {
    data class Fetched(
        val parsed: PartnerParseResult.Ok,
        val etag: String?,
    ) : PartnerFetchOutcome

    data object NotModified : PartnerFetchOutcome

    data class Failed(
        val error: PartnerFetchErrorCode,
    ) : PartnerFetchOutcome
}

/**
 * V1.9.244 -- THE ONLY place that opens an outgoing connection for the partner feature. Nothing else in the package may build an HTTP client.
 *
 * **SSRF chain** (docs/architecture/partner-server-feed.adoc): the host was normalised by [PartnerHost]; here it is resolved ONCE by
 * `checkWebhookUrl` (all returned addresses must be public -- loopback, link-local, RFC 1918, CGNAT, ULA, IPv4-mapped, 6to4, Teredo, NAT64, 0/8,
 * 240/4 are refused), and the connection is pinned to the first address by `FederationIpPinningPlugin` (no second resolution: DNS rebinding is
 * closed). Redirects are never followed. The response is bounded to [MAX_BODY_BYTES] and the whole call, body included, to [TOTAL_TIMEOUT].
 *
 * Runs OUTSIDE every database transaction. Logs carry the host and a code only -- never a body, never an address.
 *
 * All constructor parameters default to the production wiring; only tests pass anything (pinned by `PartnerFetcherConstructionTripwireTest`).
 */
internal class PartnerFeedFetcher(
    private val resolveTarget: (host: String) -> SafeFederationTarget? = ::productionResolve,
    private val clientFactory: (SafeFederationTarget) -> HttpClient = {
        federationHttpClientWithTimeouts(target = it, timeouts = FederationTimeouts.PARTNER_FEED)
    },
    private val totalTimeout: Duration = TOTAL_TIMEOUT,
) {
    companion object {
        const val MAX_BODY_BYTES = 512 * 1024
        val TOTAL_TIMEOUT = 10.seconds
        private const val MAX_ETAG_LENGTH = 200

        internal fun productionResolve(host: String): SafeFederationTarget? =
            (checkWebhookUrl(raw = "https://$host$PARTNER_FEED_PATH", allowInsecureHttp = false) as? WebhookUrlCheck.Ok)?.target
    }

    @Suppress("TooGenericExceptionCaught")
    suspend fun fetch(
        host: String,
        etag: String?,
        ownServerId: Uuid,
        knownRemoteId: Uuid?,
        now: Instant,
    ): PartnerFetchOutcome =
        try {
            withTimeout(
                totalTimeout,
            ) { fetchOnce(host = host, etag = etag, ownServerId = ownServerId, knownRemoteId = knownRemoteId, now = now) }
        } catch (e: TimeoutCancellationException) {
            failed(host = host, error = PartnerFetchErrorCode.TIMEOUT)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            failed(host = host, error = classify(e))
        }

    private suspend fun fetchOnce(
        host: String,
        etag: String?,
        ownServerId: Uuid,
        knownRemoteId: Uuid?,
        now: Instant,
    ): PartnerFetchOutcome {
        val target =
            withContext(Dispatchers.IO) { resolveTarget(host) }
                ?: return failed(host = host, error = PartnerFetchErrorCode.NOT_PUBLIC_ADDRESS)
        val safeEtag = etag?.takeIf { it.length <= MAX_ETAG_LENGTH && it.all { c -> c.code in 0x20..0x7E } }
        return clientFactory(target).use { client ->
            val response =
                client.get("https://$host$PARTNER_FEED_PATH") {
                    header(HttpHeaders.Accept, ContentType.Application.Json.toString())
                    header(HttpHeaders.UserAgent, "Lapis-Cloud-PartnerFeed/1")
                    if (safeEtag != null) header(HttpHeaders.IfNoneMatch, safeEtag)
                }
            interpret(response = response, host = host, ownServerId = ownServerId, knownRemoteId = knownRemoteId, now = now)
        }
    }

    private suspend fun interpret(
        response: HttpResponse,
        host: String,
        ownServerId: Uuid,
        knownRemoteId: Uuid?,
        now: Instant,
    ): PartnerFetchOutcome {
        val status = response.status
        if (status == HttpStatusCode.NotModified) return PartnerFetchOutcome.NotModified
        if (status.value in 300..399) return failed(host = host, error = PartnerFetchErrorCode.REDIRECT)
        if (status.value !in 200..299) return failed(host = host, error = PartnerFetchErrorCode.HTTP_STATUS)
        val type = response.contentType()
        if (type == null ||
            !type.match(ContentType.Application.Json)
        ) {
            return failed(host = host, error = PartnerFetchErrorCode.WRONG_CONTENT_TYPE)
        }
        val declared = response.contentLength()
        if (declared != null && declared > MAX_BODY_BYTES) return failed(host = host, error = PartnerFetchErrorCode.TOO_LARGE)
        val body =
            response.readCappedFederationBodyOrNull(MAX_BODY_BYTES) ?: return failed(host = host, error = PartnerFetchErrorCode.TOO_LARGE)
        return when (
            val parsed =
                PartnerFeedParser.parse(
                    bytes = body,
                    partnerHost = host,
                    ownServerId = ownServerId,
                    knownRemoteId = knownRemoteId,
                    now = now,
                )
        ) {
            is PartnerParseResult.Invalid -> failed(host = host, error = parsed.code)
            is PartnerParseResult.Ok -> PartnerFetchOutcome.Fetched(parsed = parsed, etag = cleanEtag(response.headers[HttpHeaders.ETag]))
        }
    }

    private fun cleanEtag(raw: String?): String? =
        raw?.takeIf {
            it.isNotEmpty() &&
                it.length <= MAX_ETAG_LENGTH &&
                it.all { c -> c.code in 0x20..0x7E }
        }

    private fun classify(e: Throwable): PartnerFetchErrorCode {
        var cause: Throwable? = e
        var depth = 0
        while (cause != null && depth++ < 8) {
            when (cause) {
                is HttpRequestTimeoutException, is SocketTimeoutException -> return PartnerFetchErrorCode.TIMEOUT
                is TLSException, is SSLException -> return PartnerFetchErrorCode.TLS
                is ConnectException -> return PartnerFetchErrorCode.UNREACHABLE
            }
            if (cause.javaClass.simpleName == "ConnectTimeoutException") return PartnerFetchErrorCode.TIMEOUT
            cause = cause.cause
        }
        return PartnerFetchErrorCode.UNREACHABLE
    }

    private fun failed(
        host: String,
        error: PartnerFetchErrorCode,
    ): PartnerFetchOutcome.Failed {
        logger.info { "PartnerFeedFetcher: host=$host error=$error" }
        return PartnerFetchOutcome.Failed(error)
    }
}
