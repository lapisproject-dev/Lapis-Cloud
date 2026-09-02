package network.lapis.cloud.server.rpc

import io.ktor.server.application.ApplicationCall
import kotlinx.serialization.json.Json
import network.lapis.cloud.server.audit.AuditLogRecorder
import network.lapis.cloud.server.crypto.SecretBox
import network.lapis.cloud.server.federation.FederationInboxRateLimiter
import network.lapis.cloud.server.security.ApiKeyStore
import network.lapis.cloud.server.security.requireRole
import network.lapis.cloud.server.security.resolveCurrentMember
import network.lapis.cloud.server.webhook.WebhookConfig
import network.lapis.cloud.server.webhook.WebhookDeliveryQueue
import network.lapis.cloud.server.webhook.WebhookDeliverySender
import network.lapis.cloud.server.webhook.WebhookEndpointStore
import network.lapis.cloud.server.webhook.WebhookPayloads
import network.lapis.cloud.server.webhook.WebhookSendOutcome
import network.lapis.cloud.server.webhook.WebhookUrlCheck
import network.lapis.cloud.server.webhook.WebhookUrlRejectionReason
import network.lapis.cloud.server.webhook.checkWebhookUrl
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.AuditAction
import network.lapis.cloud.shared.domain.AuditEntityType
import network.lapis.cloud.shared.domain.WebhookDeliveryDto
import network.lapis.cloud.shared.domain.WebhookDeliveryPageDto
import network.lapis.cloud.shared.domain.WebhookDeliveryStatus
import network.lapis.cloud.shared.domain.WebhookEndpointDto
import network.lapis.cloud.shared.domain.WebhookEndpointSetResultDto
import network.lapis.cloud.shared.domain.WebhookEndpointSnapshot
import network.lapis.cloud.shared.domain.WebhookEventType
import network.lapis.cloud.shared.domain.WebhookFailureReason
import network.lapis.cloud.shared.rpc.ConflictException
import network.lapis.cloud.shared.rpc.IWebhookService
import network.lapis.cloud.shared.rpc.NotFoundException
import network.lapis.cloud.shared.rpc.WebhookUrlMalformedException
import network.lapis.cloud.shared.rpc.WebhookUrlNotHttpsException
import network.lapis.cloud.shared.rpc.WebhookUrlNotPubliclyRoutableException
import network.lapis.cloud.shared.rpc.WebhookUrlTooLongException
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.uuid.Uuid

private val WEBHOOK_ROLES = arrayOf(AccountRole.BOARD, AccountRole.ADMIN)

/**
 * Welle V1.3.2 "Webhooks" (ausgehend) -- BOARD/ADMIN management of the outbound webhook (see
 * `network.lapis.cloud.shared.domain.WebhookEventType`). Mirrors `ApiKeyService`'s own shape
 * (role gate + own rate limiter per method, resolved once at the top of every method).
 */
class WebhookService(
    private val call: ApplicationCall,
    private val config: WebhookConfig,
    private val secretBox: SecretBox?,
    private val configureRateLimiter: FederationInboxRateLimiter,
    private val secretRotateRateLimiter: FederationInboxRateLimiter,
    private val testRateLimiter: FederationInboxRateLimiter,
    private val deliveryLogRateLimiter: FederationInboxRateLimiter,
) : IWebhookService {
    override suspend fun listWebhookEndpoints(): List<WebhookEndpointDto> {
        val current = resolveCurrentMember(call)
        current.requireRole(*WEBHOOK_ROLES)
        return transaction { WebhookEndpointStore.list().map { it.toDto() } }
    }

    override suspend fun setWebhookUrl(
        apiKeyId: String,
        url: String,
    ): WebhookEndpointSetResultDto {
        val current = resolveCurrentMember(call)
        current.requireRole(*WEBHOOK_ROLES)
        requireWithinRate(limiter = configureRateLimiter, memberId = current.memberId)
        val keyId = apiKeyId.toWebhookApiKeyUuid()
        requireApiKeyExists(keyId)
        requireValidUrl(url)
        val box = requireSecretBox()

        return transaction {
            val existing = WebhookEndpointStore.getByApiKeyId(keyId)
            if (existing == null) {
                val (row, rawSecret) =
                    WebhookEndpointStore.create(
                        apiKeyId = keyId,
                        url = url,
                        createdByMemberId = current.memberId,
                        secretBox = box,
                    )
                auditWebhookEndpoint(
                    action = AuditAction.CREATE,
                    endpoint = row,
                    actorMemberId = current.memberId,
                    actorRole = current.role,
                )
                WebhookEndpointSetResultDto(endpoint = row.toDto(), rawSecret = rawSecret)
            } else {
                val updated =
                    WebhookEndpointStore.updateUrl(apiKeyId = keyId, url = url, updatedByMemberId = current.memberId)
                        ?: throw ConflictException("Webhook endpoint for $apiKeyId disappeared concurrently")
                auditWebhookEndpoint(
                    action = AuditAction.UPDATE,
                    endpoint = updated,
                    actorMemberId = current.memberId,
                    actorRole = current.role,
                )
                WebhookEndpointSetResultDto(endpoint = updated.toDto(), rawSecret = null)
            }
        }
    }

    override suspend fun removeWebhookUrl(apiKeyId: String) {
        val current = resolveCurrentMember(call)
        current.requireRole(*WEBHOOK_ROLES)
        requireWithinRate(limiter = configureRateLimiter, memberId = current.memberId)
        val keyId = apiKeyId.toWebhookApiKeyUuid()
        transaction {
            val existing = WebhookEndpointStore.getByApiKeyId(keyId) ?: return@transaction
            WebhookEndpointStore.remove(keyId)
            auditWebhookEndpoint(
                action = AuditAction.UPDATE,
                endpoint = existing.copy(active = false),
                actorMemberId = current.memberId,
                actorRole = current.role,
            )
        }
    }

    override suspend fun rotateWebhookSecret(apiKeyId: String): WebhookEndpointSetResultDto {
        val current = resolveCurrentMember(call)
        current.requireRole(*WEBHOOK_ROLES)
        requireWithinRate(limiter = secretRotateRateLimiter, memberId = current.memberId)
        val keyId = apiKeyId.toWebhookApiKeyUuid()
        val box = requireSecretBox()
        return transaction {
            val (row, rawSecret) =
                WebhookEndpointStore.rotateSecret(apiKeyId = keyId, updatedByMemberId = current.memberId, secretBox = box)
                    ?: throw NotFoundException("No webhook endpoint configured for API key $apiKeyId")
            auditWebhookEndpoint(action = AuditAction.UPDATE, endpoint = row, actorMemberId = current.memberId, actorRole = current.role)
            WebhookEndpointSetResultDto(endpoint = row.toDto(), rawSecret = rawSecret)
        }
    }

    override suspend fun reactivateWebhookEndpoint(apiKeyId: String): WebhookEndpointDto {
        val current = resolveCurrentMember(call)
        current.requireRole(*WEBHOOK_ROLES)
        requireWithinRate(limiter = configureRateLimiter, memberId = current.memberId)
        val keyId = apiKeyId.toWebhookApiKeyUuid()
        return transaction {
            val row =
                WebhookEndpointStore.reactivate(apiKeyId = keyId, updatedByMemberId = current.memberId)
                    ?: throw NotFoundException("No inactive webhook endpoint to reactivate for API key $apiKeyId")
            auditWebhookEndpoint(action = AuditAction.UPDATE, endpoint = row, actorMemberId = current.memberId, actorRole = current.role)
            row.toDto()
        }
    }

    /**
     * D2 -- synchronous, exactly one attempt, timeout 5s, NEVER enters the retry queue and NEVER
     * deactivates the endpoint on failure (a test is a diagnosis, not a delivery). The delivery row
     * this creates starts (and, on failure, stays) `DELIVERING`/`FAILED` -- never `PENDING` (S22 in
     * the plan's Stolperfallen list; a `PENDING` row would be picked up by the very next poller
     * tick and receive six unwanted retries plus auto-deactivation).
     */
    override suspend fun sendWebhookTestEvent(apiKeyId: String): WebhookDeliveryDto {
        val current = resolveCurrentMember(call)
        current.requireRole(*WEBHOOK_ROLES)
        requireWithinRate(limiter = testRateLimiter, memberId = current.memberId)
        val keyId = apiKeyId.toWebhookApiKeyUuid()
        val box = requireSecretBox()
        val endpoint =
            WebhookEndpointStore.getByApiKeyId(keyId) ?: throw NotFoundException("No webhook endpoint configured for API key $apiKeyId")
        if (!endpoint.active) throw ConflictException("Webhook endpoint for $apiKeyId is deactivated")

        val urlCheck = checkWebhookUrl(raw = endpoint.url, allowInsecureHttp = config.allowInsecureHttp)
        if (urlCheck is WebhookUrlCheck.Rejected) throw urlCheck.reason.toException()

        val now = WebhookDeliveryQueue.nowLocalDateTime()
        val eventId = Uuid.random()
        val payload =
            WebhookPayloads.build(
                eventId = eventId,
                eventType = WebhookEventType.WEBHOOK_TEST,
                entityId = endpoint.id,
                occurredAt = now,
                payment = null,
            )
        val deliveryId =
            transaction {
                WebhookDeliveryQueue.insert(
                    endpointId = endpoint.id,
                    eventId = eventId,
                    eventType = WebhookEventType.WEBHOOK_TEST,
                    entityId = endpoint.id,
                    occurredAt = now,
                    payload = payload,
                    now = now,
                    initialStatus = WebhookDeliveryStatus.DELIVERING,
                )
            }
        var delivery = requireNotNull(WebhookDeliveryQueue.getById(deliveryId))

        val sender = WebhookDeliverySender(secretBox = box, allowInsecureHttp = config.allowInsecureHttp)
        val outcome = sender.sendOnce(endpoint = endpoint, delivery = delivery, attempt = 1)
        when (outcome) {
            is WebhookSendOutcome.Responded ->
                if (outcome.httpStatus in 200..299) {
                    WebhookDeliveryQueue.markTestDelivered(
                        id = deliveryId,
                        httpStatus = outcome.httpStatus,
                        now = WebhookDeliveryQueue.nowLocalDateTime(),
                    )
                } else {
                    WebhookDeliveryQueue.markTestFailed(
                        id = deliveryId,
                        httpStatus = outcome.httpStatus,
                        errorCode = WebhookFailureReason.HTTP_ERROR.name,
                    )
                }
            is WebhookSendOutcome.TransportFailure ->
                WebhookDeliveryQueue.markTestFailed(id = deliveryId, httpStatus = null, errorCode = outcome.reason.name)
            is WebhookSendOutcome.Rejected ->
                WebhookDeliveryQueue.markTestFailed(id = deliveryId, httpStatus = null, errorCode = WebhookFailureReason.URL_REJECTED.name)
        }
        delivery = requireNotNull(WebhookDeliveryQueue.getById(deliveryId))
        return delivery.toDto()
    }

    override suspend fun listWebhookDeliveries(
        apiKeyId: String,
        limit: Int,
        offset: Int,
    ): WebhookDeliveryPageDto {
        val current = resolveCurrentMember(call)
        current.requireRole(*WEBHOOK_ROLES)
        requireWithinRate(limiter = deliveryLogRateLimiter, memberId = current.memberId)
        val keyId = apiKeyId.toWebhookApiKeyUuid()
        val endpoint =
            WebhookEndpointStore.getByApiKeyId(keyId) ?: throw NotFoundException("No webhook endpoint configured for API key $apiKeyId")
        val effectiveLimit = limit.coerceIn(1, MAX_DELIVERY_LOG_PAGE_SIZE)
        val effectiveOffset = offset.coerceAtLeast(0)
        val items = WebhookDeliveryQueue.listByEndpoint(endpointId = endpoint.id, limit = effectiveLimit, offset = effectiveOffset)
        val total = WebhookDeliveryQueue.countByEndpoint(endpoint.id)
        return WebhookDeliveryPageDto(
            items = items.map { it.toDto() },
            totalCount = total.toInt(),
            limit = effectiveLimit,
            offset = effectiveOffset,
        )
    }

    private fun requireWithinRate(
        limiter: FederationInboxRateLimiter,
        memberId: Uuid,
    ) {
        if (!limiter.checkAndRecord("member:$memberId")) {
            throw ConflictException("Rate limit exceeded, try again later")
        }
    }

    private fun requireSecretBox(): SecretBox = secretBox ?: throw ConflictException("Webhooks are not configured on this server")

    private fun requireApiKeyExists(apiKeyId: Uuid) {
        ApiKeyStore.getOrNull(apiKeyId) ?: throw NotFoundException("API key $apiKeyId not found")
    }

    private fun requireValidUrl(url: String) {
        when (val check = checkWebhookUrl(raw = url, allowInsecureHttp = config.allowInsecureHttp)) {
            is WebhookUrlCheck.Rejected -> throw check.reason.toException()
            is WebhookUrlCheck.Ok -> Unit
        }
    }
}

private const val MAX_DELIVERY_LOG_PAGE_SIZE = 100

private fun WebhookUrlRejectionReason.toException(): Throwable =
    when (this) {
        WebhookUrlRejectionReason.NOT_HTTPS -> WebhookUrlNotHttpsException()
        WebhookUrlRejectionReason.MALFORMED -> WebhookUrlMalformedException()
        WebhookUrlRejectionReason.NOT_PUBLICLY_ROUTABLE -> WebhookUrlNotPubliclyRoutableException()
        WebhookUrlRejectionReason.TOO_LONG -> WebhookUrlTooLongException()
    }

private fun String.toWebhookApiKeyUuid(): Uuid = runCatching { Uuid.parse(this) }.getOrElse { throw NotFoundException("Invalid id: $this") }

/**
 * [WebhookDeliveryQueue.latestByEndpoint] lookup folded in here -- see that function's own KDoc.
 * One extra query per endpoint row is acceptable at this scale (BOARD/ADMIN-only, one endpoint per
 * API key, same "small N, no batching needed" reasoning `ApiKeyService`'s own per-row queries
 * already rely on).
 */
private fun WebhookEndpointStore.EndpointRow.toDto(): WebhookEndpointDto {
    val latestDelivery = WebhookDeliveryQueue.latestByEndpoint(id)
    return WebhookEndpointDto(
        id = id.toString(),
        apiKeyId = apiKeyId.toString(),
        apiKeyLabel = apiKeyLabel,
        url = url,
        secretPrefix = secretPrefix,
        active = active,
        createdAt = createdAt,
        updatedAt = updatedAt,
        deactivatedAt = deactivatedAt,
        deactivationReason = deactivationReason,
        lastHttpStatus = latestDelivery?.lastHttpStatus,
        lastAttemptAt = latestDelivery?.lastAttemptAt,
    )
}

private fun WebhookDeliveryQueue.DeliveryRow.toDto(): WebhookDeliveryDto =
    WebhookDeliveryDto(
        id = id.toString(),
        eventType = eventType,
        entityId = entityId.toString(),
        occurredAt = occurredAt,
        status = status,
        attemptCount = attemptCount,
        maxAttempts = 6,
        lastHttpStatus = lastHttpStatus,
        lastErrorCode = lastError?.let { code -> runCatching { WebhookFailureReason.valueOf(code) }.getOrNull() },
        lastAttemptAt = lastAttemptAt,
        nextAttemptAt = nextAttemptAt,
        createdAt = createdAt,
        deliveredAt = deliveredAt,
    )

/** CREATE/UPDATE -- see [WebhookEndpointSnapshot] KDoc for why it never carries the signature secret. */
private fun auditWebhookEndpoint(
    action: AuditAction,
    endpoint: WebhookEndpointStore.EndpointRow,
    actorMemberId: Uuid,
    actorRole: AccountRole,
) {
    AuditLogRecorder.record(
        actorMemberId = actorMemberId,
        actorRole = actorRole,
        entityType = AuditEntityType.WEBHOOK_ENDPOINT,
        entityId = endpoint.id,
        action = action,
        before = null,
        after =
            Json.encodeToString(
                WebhookEndpointSnapshot.serializer(),
                WebhookEndpointSnapshot(
                    apiKeyId = endpoint.apiKeyId.toString(),
                    url = endpoint.url,
                    active = endpoint.active,
                    deactivationReason = endpoint.deactivationReason,
                ),
            ),
    )
}
