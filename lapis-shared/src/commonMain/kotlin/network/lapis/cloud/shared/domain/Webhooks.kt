package network.lapis.cloud.shared.domain

import kotlinx.datetime.LocalDateTime
import kotlinx.serialization.Serializable

/**
 * Welle V1.3.2 "Webhooks" (ausgehend) -- one outbound webhook endpoint, keyed 1:1 to an [ApiKeyDto]
 * (`network.lapis.cloud.server.security.WebhookEndpointStore`'s own `uq_webhook_endpoint_api_key`
 * unique index enforces this at the DB layer). Never carries the signature secret in full -- see
 * [secretPrefix] and [WebhookEndpointSetResultDto.rawSecret]'s own KDoc for the one exception.
 */
@Serializable
data class WebhookEndpointDto(
    val id: String,
    val apiKeyId: String,
    val apiKeyLabel: String,
    val url: String,
    val secretPrefix: String,
    val active: Boolean,
    val createdAt: LocalDateTime,
    val updatedAt: LocalDateTime?,
    val deactivatedAt: LocalDateTime?,
    val deactivationReason: WebhookDeactivationReason?,
    val lastHttpStatus: Int?,
    val lastAttemptAt: LocalDateTime?,
)

/**
 * Result of `setWebhookUrl`/`rotateWebhookSecret` -- [rawSecret] is populated ONLY on those two
 * calls (a brand-new endpoint, or a freshly rotated secret) and is never retrievable again
 * afterwards -- same "shown exactly once" discipline as [ApiKeyIssueResultDto.rawKey], see
 * `WebhookDeliveryLogPanel.kt`/`ApiKeysScreen.kt`'s reveal-card handling (Design-Team decision D3).
 */
@Serializable
data class WebhookEndpointSetResultDto(
    val endpoint: WebhookEndpointDto,
    val rawSecret: String?,
)

/** One outbound delivery attempt record -- see `network.lapis.cloud.server.webhook.WebhookDeliveryPoller` KDoc for the retry/backoff mechanics this reflects. */
@Serializable
data class WebhookDeliveryDto(
    val id: String,
    val eventType: WebhookEventType,
    val entityId: String,
    val occurredAt: LocalDateTime,
    val status: WebhookDeliveryStatus,
    val attemptCount: Int,
    val maxAttempts: Int,
    val lastHttpStatus: Int?,
    val lastErrorCode: WebhookFailureReason?,
    val lastAttemptAt: LocalDateTime?,
    val nextAttemptAt: LocalDateTime?,
    val createdAt: LocalDateTime,
    val deliveredAt: LocalDateTime?,
)

@Serializable
data class WebhookDeliveryPageDto(
    val items: List<WebhookDeliveryDto>,
    val totalCount: Int,
    val limit: Int,
    val offset: Int,
)

/** Persisted (`webhook_delivery.status`) lifecycle of one delivery attempt row -- see the poller's own status-semantics table. */
@Serializable
enum class WebhookDeliveryStatus { PENDING, DELIVERING, DELIVERED, FAILED, ABANDONED }

/** Why an endpoint was deactivated -- shown in the UI's red banner (Design-Team decision D4). */
@Serializable
enum class WebhookDeactivationReason { DELIVERY_FAILURES, MANUAL, KEY_REVOKED, RECEIVER_GONE }

/**
 * The four (and only four) reasons `setWebhookUrl`/`rotateWebhookSecret` can reject a URL --
 * Design-Team decision D6: exactly four fixed, non-leaking messages, never an IP address, hostname,
 * or DNS-resolution detail (see `network.lapis.cloud.server.webhook.OutboundUrlGuard` KDoc).
 */
@Serializable
enum class WebhookUrlRejectionReason { NOT_HTTPS, MALFORMED, NOT_PUBLICLY_ROUTABLE, TOO_LONG }

/**
 * Why one delivery attempt failed -- persisted verbatim (as the enum name) in
 * `webhook_delivery.last_error`, NEVER an exception message/hostname/IP (S in the plan's
 * Stolperfallen list) -- translated to a German sentence only at the UI layer.
 */
@Serializable
enum class WebhookFailureReason {
    TIMEOUT,
    CONNECTION_REFUSED,
    DNS_OR_TLS,
    HTTP_ERROR,
    URL_REJECTED,
    ENDPOINT_DEACTIVATED,
    RETRIES_EXHAUSTED,
}

/**
 * The full, closed catalogue of events this webhook subsystem can ever publish -- see
 * `network.lapis.cloud.server.webhook.WebhookEventPublisher` KDoc for the Thin-vs-Fat payload
 * distinction (`CONTRIBUTION_PAID`/`DONATION_RECEIVED` are the only two Fat events) and
 * `network.lapis.cloud.server.rpc.GovernanceService`/`MemberService`/`RegistrationService`/
 * `ContributionPaymentEvents`/`PspWebhookIngestion` for the concrete call sites. [wireName] is what
 * actually travels on the wire as the JSON payload's `"eventType"` field and the
 * `Lapis-Webhook-Event` header -- deliberately dotted-lowercase (Stripe-like), NOT this enum's own
 * Kotlin `.name` (which is what `webhook_delivery.event_type`/[WebhookDeliveryDto.eventType]
 * actually persist/serialize as -- a DIFFERENT, internal representation of the same fact).
 */
@Serializable
enum class WebhookEventType(
    val wireName: String,
) {
    COMMITTEE_CREATED("committee.created"),
    COMMITTEE_UPDATED("committee.updated"),
    MEETING_CREATED("meeting.created"),
    MEETING_HELD("meeting.held"),
    RESOLUTION_ADOPTED("resolution.adopted"),
    MOTION_SCHEDULED("motion.scheduled"),
    MEMBER_CREATED("member.created"),

    /** Fat event -- see class KDoc. */
    CONTRIBUTION_PAID("contribution.paid"),

    /** Fat event -- see class KDoc. */
    DONATION_RECEIVED("donation.received"),

    /** Synchronous-only, never queued/retried -- see `IWebhookService.sendWebhookTestEvent` KDoc. */
    WEBHOOK_TEST("webhook.test"),
    ;

    companion object {
        fun fromWireNameOrNull(wireName: String): WebhookEventType? = entries.firstOrNull { it.wireName == wireName }
    }
}
