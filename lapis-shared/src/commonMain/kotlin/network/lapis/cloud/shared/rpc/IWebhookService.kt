package network.lapis.cloud.shared.rpc

import dev.kilua.rpc.annotations.RpcService
import network.lapis.cloud.shared.domain.WebhookDeliveryDto
import network.lapis.cloud.shared.domain.WebhookDeliveryPageDto
import network.lapis.cloud.shared.domain.WebhookEndpointDto
import network.lapis.cloud.shared.domain.WebhookEndpointSetResultDto

/**
 * Welle V1.3.2 "Webhooks" (ausgehend) -- BOARD/ADMIN-only management surface for the outbound
 * webhook that fires on a fixed catalogue of governance/membership/payment events (see
 * `network.lapis.cloud.shared.domain.WebhookEventType`). Deliberately its OWN interface, not an
 * extension of [IApiKeyService] -- different error vocabulary (URL rejection, four fixed reasons),
 * different rate-limiter shapes per method, and a different (secondary) rotation credential --
 * every method still keys on `apiKeyId` (an endpoint is 1:1 with the API key whose bearer is
 * allowed to configure it).
 */
@RpcService
interface IWebhookService {
    /** Role: BOARD/ADMIN. One row per API key that currently has (or ever had) a webhook endpoint configured. */
    suspend fun listWebhookEndpoints(): List<WebhookEndpointDto>

    /**
     * Role: BOARD/ADMIN. Creates the endpoint for [apiKeyId] if none exists yet, or updates its
     * [url] if one does (secret stays unchanged on an update -- see [rotateWebhookSecret] for
     * secret rotation). [url] is validated server-side regardless of any client-side pre-check
     * (Design-Team decision D6) -- see `WebhookUrlRejectedException` for the four possible
     * rejections. [WebhookEndpointSetResultDto.rawSecret] is populated ONLY when this call actually
     * created a brand-new endpoint (a `null` `rawSecret` on an update means the existing secret is
     * unchanged, not that it was cleared).
     */
    suspend fun setWebhookUrl(
        apiKeyId: String,
        url: String,
    ): WebhookEndpointSetResultDto

    /** Role: BOARD/ADMIN. Deletes the endpoint for [apiKeyId] entirely (not merely deactivates it) -- idempotent, a call against a key with no endpoint is a silent no-op. */
    suspend fun removeWebhookUrl(apiKeyId: String)

    /** Role: BOARD/ADMIN. Mints a brand-new signature secret for [apiKeyId]'s endpoint, invalidating the old one immediately (no dual-secret grace window -- Scope-Cut, see `network.lapis.cloud.server.webhook.WebhookSigner` KDoc). [WebhookEndpointSetResultDto.rawSecret] is always populated here. */
    suspend fun rotateWebhookSecret(apiKeyId: String): WebhookEndpointSetResultDto

    /** Role: BOARD/ADMIN. Re-activates an endpoint the poller auto-deactivated (or that was manually removed... no -- re-activates one still configured but `active = false`). Does NOT require a confirmation dialog client-side (Design-Team decision D4 -- reactivation is not destructive). */
    suspend fun reactivateWebhookEndpoint(apiKeyId: String): WebhookEndpointDto

    /**
     * Role: BOARD/ADMIN. Sends exactly ONE synchronous test delivery to [apiKeyId]'s endpoint and
     * waits for the real outcome (own, tight rate limit -- 5/min, see `Application.kt` wiring). The
     * resulting [WebhookDeliveryDto] NEVER has `status = PENDING` -- a failed test does not enter
     * the retry queue and does not deactivate the endpoint (Design-Team decision D2: a test is a
     * diagnosis, not a delivery).
     */
    suspend fun sendWebhookTestEvent(apiKeyId: String): WebhookDeliveryDto

    /** Role: BOARD/ADMIN. Newest-first delivery history for [apiKeyId]'s endpoint, 25/page by default (Design-Team decision D5). */
    suspend fun listWebhookDeliveries(
        apiKeyId: String,
        limit: Int = 25,
        offset: Int = 0,
    ): WebhookDeliveryPageDto
}
