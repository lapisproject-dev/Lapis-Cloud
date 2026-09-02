package network.lapis.cloud.server.webhook

import io.ktor.client.request.header
import io.ktor.client.request.preparePost
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentLength
import io.ktor.http.contentType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import network.lapis.cloud.server.crypto.SecretBox
import network.lapis.cloud.server.crypto.SecretBoxException
import network.lapis.cloud.server.federation.SafeFederationTarget
import network.lapis.cloud.shared.domain.WebhookFailureReason
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import javax.net.ssl.SSLException
import kotlin.time.Clock

/** Outcome of ONE [WebhookDeliverySender.sendOnce] attempt. */
internal sealed interface WebhookSendOutcome {
    data class Responded(
        val httpStatus: Int,
    ) : WebhookSendOutcome

    data class TransportFailure(
        val reason: WebhookFailureReason,
    ) : WebhookSendOutcome

    data class Rejected(
        val reason: WebhookUrlRejectionReason,
    ) : WebhookSendOutcome
}

/** `Content-Type`/`User-Agent`/`Lapis-Webhook-Event` -- see [WebhookSigner] KDoc "Ausgehende Header" for the full five-header list, `Lapis-Signature`/`Lapis-Webhook-Id`/`Lapis-Delivery-Attempt` are set per-call below. */
private const val WEBHOOK_USER_AGENT = "LapisCloud-Webhook/1"
private const val WEBHOOK_ID_HEADER = "Lapis-Webhook-Id"
private const val WEBHOOK_EVENT_HEADER = "Lapis-Webhook-Event"
private const val WEBHOOK_ATTEMPT_HEADER = "Lapis-Delivery-Attempt"

/**
 * Security-Audit-Fund F1 (Runde 1, 2026-09-02) -- hard cap on a webhook receiver's response body.
 * [sendOnce] never actually needs the body (only [io.ktor.client.statement.HttpResponse.status]),
 * so the primary fix is the streaming `HttpStatement.execute { ... }` path below, which never
 * materializes the body into the heap at all regardless of size. This constant additionally aborts
 * EARLY, before any body byte is read, whenever the receiver is honest enough to announce an
 * oversized body via `Content-Length` -- same magnitude as
 * [network.lapis.cloud.server.federation.MAX_FEDERATION_RESPONSE_BYTES], this codebase's existing
 * precedent for "how much of an untrusted remote response is worth ever holding in memory".
 */
private const val MAX_WEBHOOK_RESPONSE_BYTES = 64 * 1024L

/**
 * Welle V1.3.2 "Webhooks" (ausgehend) -- Design-Team decision D2's resolution of conflict K2: THE
 * single place an outbound webhook HTTP request is ever built and sent. Both
 * [WebhookDeliveryPoller] (queued, retried deliveries) AND `WebhookService.sendWebhookTestEvent`
 * (one synchronous diagnostic attempt) call [sendOnce] -- same guard ([checkWebhookUrl]), same
 * client ([webhookHttpClient]), same signer ([WebhookSigner]), only NOT through the queue for the
 * test-event path. A second, independent code path that builds an outbound webhook request is
 * explicitly NOT permitted.
 */
internal class WebhookDeliverySender(
    private val secretBox: SecretBox,
    private val allowInsecureHttp: Boolean,
) {
    /**
     * EXACTLY one delivery attempt -- no retry, no DB write, no logging of the secret or the
     * payload body. Re-validates the endpoint's URL via [checkWebhookUrl] EVERY call (the DNS
     * answer can have changed since the URL was saved, or since the previous attempt) -- a
     * [WebhookSendOutcome.Rejected] here is treated as a Fehlversuch by the poller (see that
     * class's own KDoc "Klassifikation"), NEVER as an immediate abandon, so a transient DNS
     * misconfiguration gets the same backoff chance as a transient network failure.
     */
    suspend fun sendOnce(
        endpoint: WebhookEndpointStore.EndpointRow,
        delivery: WebhookDeliveryQueue.DeliveryRow,
        attempt: Int,
    ): WebhookSendOutcome {
        val check = checkWebhookUrl(raw = endpoint.url, allowInsecureHttp = allowInsecureHttp)
        val target =
            when (check) {
                is WebhookUrlCheck.Rejected -> return WebhookSendOutcome.Rejected(check.reason)
                is WebhookUrlCheck.Ok -> check.target
            }

        val secret =
            try {
                endpoint.revealSecret(secretBox)
            } catch (e: SecretBoxException) {
                // SecretBoxException carries a fixed, streaming-specific German message; translated
                // here into a neutral outcome instead of leaking that unrelated wording (or the
                // exception at all) into the webhook delivery path. The message string itself
                // stays untouched -- see SecretBox KDoc.
                return WebhookSendOutcome.TransportFailure(WebhookFailureReason.DNS_OR_TLS)
            }

        val bodyBytes = delivery.payload.toByteArray(Charsets.UTF_8)
        val timestampSeconds = Clock.System.now().epochSeconds
        val signatureHeader = WebhookSigner.sign(payload = bodyBytes, secret = secret, timestampSeconds = timestampSeconds)

        return sendSignedRequest(
            target = target,
            url = endpoint.url,
            eventId = delivery.eventId.toString(),
            eventTypeWireName = delivery.eventType.wireName,
            attempt = attempt,
            signatureHeader = signatureHeader,
            bodyBytes = bodyBytes,
        )
    }

    /**
     * The actual wire-level POST + response handling, factored out of [sendOnce] so it can be
     * exercised directly (with a hand-built [target]) in tests without ALSO having to satisfy
     * [checkWebhookUrl]'s SSRF/public-routability gate first -- that gate, by design, refuses every
     * address a test process could actually bind a local listener to (loopback included), so no
     * test could otherwise drive a real socket through this code. [WebhookDeliverySenderTest]
     * exercises this function directly against a local test server for exactly that reason. Not
     * meant to be called from production code except by [sendOnce].
     */
    internal suspend fun sendSignedRequest(
        target: SafeFederationTarget,
        url: String,
        eventId: String,
        eventTypeWireName: String,
        attempt: Int,
        signatureHeader: String,
        bodyBytes: ByteArray,
    ): WebhookSendOutcome =
        try {
            webhookHttpClient(target).use { client ->
                client
                    .preparePost(url) {
                        header(WebhookSigner.SIGNATURE_HEADER, signatureHeader)
                        header(WEBHOOK_ID_HEADER, eventId)
                        header(WEBHOOK_EVENT_HEADER, eventTypeWireName)
                        header(WEBHOOK_ATTEMPT_HEADER, attempt.toString())
                        header(HttpHeaders.UserAgent, WEBHOOK_USER_AGENT)
                        contentType(ContentType.Application.Json)
                        setBody(bodyBytes)
                    }.execute { response ->
                        // Streaming response -- the body is NEVER materialized into the heap here
                        // (see MAX_WEBHOOK_RESPONSE_BYTES KDoc "F1"); it is discarded once this
                        // block returns. The Content-Length pre-check below is an early-abort
                        // optimization for an HONEST oversized announcement, not the actual OOM
                        // fix -- a receiver that lies (chunked encoding, no Content-Length, or an
                        // understated one) is still safe because nothing here ever reads the body.
                        val announcedLength = response.contentLength()
                        if (announcedLength != null && announcedLength > MAX_WEBHOOK_RESPONSE_BYTES) {
                            WebhookSendOutcome.TransportFailure(WebhookFailureReason.PROTOCOL_ERROR)
                        } else {
                            WebhookSendOutcome.Responded(httpStatus = response.status.value)
                        }
                    }
            }
        } catch (e: SocketTimeoutException) {
            WebhookSendOutcome.TransportFailure(WebhookFailureReason.TIMEOUT)
        } catch (e: TimeoutCancellationException) {
            WebhookSendOutcome.TransportFailure(WebhookFailureReason.TIMEOUT)
        } catch (e: ConnectException) {
            WebhookSendOutcome.TransportFailure(WebhookFailureReason.CONNECTION_REFUSED)
        } catch (e: SSLException) {
            WebhookSendOutcome.TransportFailure(WebhookFailureReason.DNS_OR_TLS)
        } catch (e: IOException) {
            WebhookSendOutcome.TransportFailure(WebhookFailureReason.DNS_OR_TLS)
        } catch (e: CancellationException) {
            // Security-Audit-Fund F2 -- MUST rethrow, never swallow: this is coroutine
            // cancellation/shutdown (e.g. the poller's scope being torn down), not a delivery
            // failure. Placed AFTER TimeoutCancellationException (a CancellationException
            // subtype) so THAT specific case keeps mapping to WebhookFailureReason.TIMEOUT above
            // -- Kotlin picks the first matching catch clause in source order.
            throw e
        } catch (e: Throwable) {
            // Security-Audit-Fund F2 (Runde 1, 2026-09-02, MAJOR) -- catch-all so an unanticipated
            // exception (observed in practice: io.ktor.http.cio.ParserException on a malformed
            // HTTP response, e.g. an invalid status line or an oversized header -- it inherits
            // from IllegalStateException, NOT IOException, so none of the clauses above ever
            // caught it) can never escape sendOnce. Before this clause, such an exception
            // propagated out of sendOnce, past WebhookDeliveryPoller.deliverOne, and was only
            // logged (never routed through handleFailure) by deliverOneSafely's own try/catch --
            // the delivery row stayed DELIVERING forever, endlessly reset to PENDING by the
            // stale-claim reaper every 5 minutes, NEVER reaching the retry/backoff ladder or
            // auto-deactivation. Mapping it to a TransportFailure instead keeps every unexpected
            // failure inside the ordinary Fehlversuch/backoff/MAX_ATTEMPTS machinery.
            WebhookSendOutcome.TransportFailure(WebhookFailureReason.PROTOCOL_ERROR)
        }
}
