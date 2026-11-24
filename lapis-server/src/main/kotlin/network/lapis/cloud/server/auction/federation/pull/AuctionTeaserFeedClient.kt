package network.lapis.cloud.server.auction.federation.pull

import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentLength
import io.ktor.http.contentType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import network.lapis.cloud.server.auction.federation.feed.AUCTION_TEASER_FEED_PATH
import network.lapis.cloud.server.auction.federation.feed.AuctionTeaserFeedLimits
import network.lapis.cloud.server.carpool.CarpoolOwnSigningKey
import network.lapis.cloud.server.federation.FederationTimeouts
import network.lapis.cloud.server.federation.HttpSignatures
import network.lapis.cloud.server.federation.SafeFederationTarget
import network.lapis.cloud.server.federation.federationHttpClientWithTimeouts
import network.lapis.cloud.server.federation.readCappedFederationBodyOrNull
import network.lapis.cloud.server.partner.classifyFederationFetchFailure
import network.lapis.cloud.server.time.ServerClock
import network.lapis.cloud.server.webhook.WebhookUrlCheck
import network.lapis.cloud.server.webhook.checkWebhookUrl
import network.lapis.cloud.shared.domain.CarpoolPullErrorCode
import network.lapis.cloud.shared.domain.PartnerFetchErrorCode
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

private val logger = KotlinLogging.logger {}

internal sealed interface AuctionPullOutcome {
    class Fetched(
        val body: ByteArray,
        val etag: String?,
    ) : AuctionPullOutcome

    data object NotModified : AuctionPullOutcome

    class Failed(
        val error: CarpoolPullErrorCode,
    ) : AuctionPullOutcome
}

/**
 * V1.9.322 (step c3) -- THE only place that opens an outgoing connection for the AUCTION exchange (pinned by `AuctionFederationTripwireTest` (h)). It
 * pulls `GET /api/federation/auction-feed/v1` of ONE direct partner host with a request signed by this server's own federation key (draft-cavage, empty
 * body, no query). The signed path is the auction path, so a signature made for the carpool feed is never valid here.
 *
 * Deliberate COPY of `CarpoolTeaserFeedClient` (c2 extracted nothing shared; the carpool code is not touched by c3). Same SSRF chain: the host is
 * resolved ONCE by `checkWebhookUrl` (https only, every address public), the connection is pinned to that address, redirects are never followed, the body
 * is capped at [AuctionTeaserFeedLimits.MAX_FEED_BYTES] and the whole call is bounded by [totalTimeout]. Runs OUTSIDE every transaction. Logs carry the
 * host and a code only. All constructor parameters default to the production wiring; only tests pass anything.
 */
internal class AuctionTeaserFeedClient(
    private val resolveTarget: (host: String) -> SafeFederationTarget? = ::productionResolve,
    private val clientFactory: (SafeFederationTarget) -> HttpClient = {
        federationHttpClientWithTimeouts(target = it, timeouts = FederationTimeouts.PARTNER_FEED)
    },
    private val totalTimeout: Duration = TOTAL_TIMEOUT,
    private val clock: () -> Instant = { ServerClock.nowInstant() },
) {
    companion object {
        val TOTAL_TIMEOUT = 10.seconds
        private const val MAX_ETAG_LENGTH = 200

        internal fun productionResolve(host: String): SafeFederationTarget? =
            (checkWebhookUrl(raw = "https://$host$AUCTION_TEASER_FEED_PATH", allowInsecureHttp = false) as? WebhookUrlCheck.Ok)?.target
    }

    @Suppress("TooGenericExceptionCaught")
    suspend fun fetch(
        host: String,
        etag: String?,
        key: CarpoolOwnSigningKey,
    ): AuctionPullOutcome =
        try {
            withTimeout(totalTimeout) { fetchOnce(host = host, etag = etag, key = key) }
        } catch (e: TimeoutCancellationException) {
            failed(host = host, error = CarpoolPullErrorCode.TIMEOUT)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            failed(host = host, error = map(classifyFederationFetchFailure(e)))
        }

    private suspend fun fetchOnce(
        host: String,
        etag: String?,
        key: CarpoolOwnSigningKey,
    ): AuctionPullOutcome {
        val target =
            withContext(Dispatchers.IO) { resolveTarget(host) }
                ?: return failed(host = host, error = CarpoolPullErrorCode.NOT_PUBLIC_ADDRESS)
        val safeEtag = etag?.takeIf { it.length <= MAX_ETAG_LENGTH && it.all { c -> c.code in 0x20..0x7E } }
        val signed =
            HttpSignatures.sign(
                method = "GET",
                path = AUCTION_TEASER_FEED_PATH,
                host = host,
                body = ByteArray(0),
                keyId = key.keyId,
                privateKeyPem = key.privateKeyPem,
                now = clock(),
            )
        return clientFactory(target).use { client ->
            val response =
                client.get("https://$host$AUCTION_TEASER_FEED_PATH") {
                    header(HttpHeaders.Accept, ContentType.Application.Json.toString())
                    header(HttpHeaders.UserAgent, "Lapis-Cloud-AuctionFeed/1")
                    header(HttpHeaders.Date, signed.dateHeader)
                    header("Digest", signed.digestHeader)
                    header("Signature", signed.signatureHeader)
                    if (safeEtag != null) header(HttpHeaders.IfNoneMatch, safeEtag)
                }
            interpret(response = response, host = host)
        }
    }

    private suspend fun interpret(
        response: HttpResponse,
        host: String,
    ): AuctionPullOutcome {
        val status = response.status
        if (status == HttpStatusCode.NotModified) return AuctionPullOutcome.NotModified
        if (status.value in 300..399) return failed(host = host, error = CarpoolPullErrorCode.REDIRECT)
        if (status == HttpStatusCode.NotFound) return failed(host = host, error = CarpoolPullErrorCode.UNAVAILABLE)
        if (status == HttpStatusCode.TooManyRequests) return failed(host = host, error = CarpoolPullErrorCode.RATE_LIMITED)
        if (status.value !in 200..299) return failed(host = host, error = CarpoolPullErrorCode.HTTP_STATUS)
        val type = response.contentType()
        if (type == null ||
            !type.match(ContentType.Application.Json)
        ) {
            return failed(host = host, error = CarpoolPullErrorCode.WRONG_CONTENT_TYPE)
        }
        val declared = response.contentLength()
        if (declared != null &&
            declared > AuctionTeaserFeedLimits.MAX_FEED_BYTES
        ) {
            return failed(host = host, error = CarpoolPullErrorCode.TOO_LARGE)
        }
        val body =
            response.readCappedFederationBodyOrNull(AuctionTeaserFeedLimits.MAX_FEED_BYTES)
                ?: return failed(host = host, error = CarpoolPullErrorCode.TOO_LARGE)
        val etag =
            response.headers[HttpHeaders.ETag]?.takeIf {
                it.isNotEmpty() &&
                    it.length <= MAX_ETAG_LENGTH &&
                    it.all { c -> c.code in 0x20..0x7E }
            }
        return AuctionPullOutcome.Fetched(body = body, etag = etag)
    }

    private fun map(code: PartnerFetchErrorCode): CarpoolPullErrorCode =
        when (code) {
            PartnerFetchErrorCode.TIMEOUT -> CarpoolPullErrorCode.TIMEOUT
            PartnerFetchErrorCode.TLS -> CarpoolPullErrorCode.TLS
            else -> CarpoolPullErrorCode.UNREACHABLE
        }

    private fun failed(
        host: String,
        error: CarpoolPullErrorCode,
    ): AuctionPullOutcome.Failed {
        logger.info { "AuctionTeaserFeedClient: host=$host error=$error" }
        return AuctionPullOutcome.Failed(error)
    }
}
