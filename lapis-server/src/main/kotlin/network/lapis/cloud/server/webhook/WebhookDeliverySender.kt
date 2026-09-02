package network.lapis.cloud.server.webhook

import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import kotlinx.coroutines.TimeoutCancellationException
import network.lapis.cloud.server.crypto.SecretBox
import network.lapis.cloud.server.crypto.SecretBoxException
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

        return try {
            webhookHttpClient(target).use { client ->
                val response =
                    client.post(endpoint.url) {
                        header(WebhookSigner.SIGNATURE_HEADER, signatureHeader)
                        header(WEBHOOK_ID_HEADER, delivery.eventId.toString())
                        header(WEBHOOK_EVENT_HEADER, delivery.eventType.wireName)
                        header(WEBHOOK_ATTEMPT_HEADER, attempt.toString())
                        header(HttpHeaders.UserAgent, WEBHOOK_USER_AGENT)
                        contentType(ContentType.Application.Json)
                        setBody(bodyBytes)
                    }
                WebhookSendOutcome.Responded(httpStatus = response.status.value)
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
        }
    }
}
