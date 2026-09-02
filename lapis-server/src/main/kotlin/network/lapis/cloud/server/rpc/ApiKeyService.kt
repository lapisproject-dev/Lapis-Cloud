package network.lapis.cloud.server.rpc

import io.ktor.server.application.ApplicationCall
import kotlinx.datetime.LocalDateTime
import kotlinx.serialization.json.Json
import network.lapis.cloud.server.audit.AuditLogRecorder
import network.lapis.cloud.server.federation.FederationInboxRateLimiter
import network.lapis.cloud.server.security.ApiKeyStore
import network.lapis.cloud.server.security.requireRole
import network.lapis.cloud.server.security.resolveCurrentMember
import network.lapis.cloud.server.webhook.WebhookEndpointDeactivation
import network.lapis.cloud.server.webhook.WebhookEndpointStore
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.ApiKeyDto
import network.lapis.cloud.shared.domain.ApiKeyIssueResultDto
import network.lapis.cloud.shared.domain.ApiKeySnapshot
import network.lapis.cloud.shared.domain.AuditAction
import network.lapis.cloud.shared.domain.AuditEntityType
import network.lapis.cloud.shared.domain.WebhookDeactivationReason
import network.lapis.cloud.shared.rpc.ConflictException
import network.lapis.cloud.shared.rpc.IApiKeyService
import network.lapis.cloud.shared.rpc.NotFoundException
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.uuid.Uuid

private val API_KEY_ROLES = arrayOf(AccountRole.BOARD, AccountRole.ADMIN)

/**
 * V1.3.1 "API-Fundament, lesend" -- BOARD/ADMIN management of the API keys that authenticate the
 * `/api/v1` REST surface (see `network.lapis.cloud.server.routes.PublicApiRoutes`). Every method
 * resolves the caller and requires [API_KEY_ROLES] first, mirroring every other admin-tier RPC
 * service in this codebase.
 *
 * **Three SEPARATE rate limiters, three SEPARATE budgets** (Design-Team decision, plan §8.2): issue
 * is deliberately the tightest (a genuinely rare, deliberate action), revoke is deliberately
 * generous (an emergency revocation must never be blocked by having just issued several keys), and
 * reissue has its OWN budget independent of BOTH -- "I lost my key, issue me a new one" must never
 * fail merely because [issueRateLimiter] happens to be exhausted from unrelated recent activity.
 */
class ApiKeyService(
    private val call: ApplicationCall,
    private val issueRateLimiter: FederationInboxRateLimiter,
    private val revokeRateLimiter: FederationInboxRateLimiter,
    private val reissueRateLimiter: FederationInboxRateLimiter,
) : IApiKeyService {
    override suspend fun listApiKeys(includeRevoked: Boolean): List<ApiKeyDto> {
        val current = resolveCurrentMember(call)
        current.requireRole(*API_KEY_ROLES)
        return transaction { ApiKeyStore.list(includeRevoked = includeRevoked).map { it.toApiKeyDto() } }
    }

    override suspend fun issueApiKey(
        label: String,
        expiresAt: LocalDateTime?,
    ): ApiKeyIssueResultDto {
        val current = resolveCurrentMember(call)
        current.requireRole(*API_KEY_ROLES)
        requireWithinRate(limiter = issueRateLimiter, memberId = current.memberId)
        val trimmedLabel = requireValidLabel(label)
        return transaction {
            val issued = ApiKeyStore.issue(label = trimmedLabel, createdByMemberId = current.memberId, expiresAt = expiresAt)
            auditApiKeyCreate(
                apiKeyId = issued.id,
                label = issued.label,
                keyPrefix = issued.keyPrefix,
                createdByMemberId = issued.createdByMemberId,
                expiresAt = issued.expiresAt,
                actorMemberId = current.memberId,
                actorRole = current.role,
                occurredAt = issued.createdAt,
            )
            ApiKeyIssueResultDto(
                apiKey =
                    ApiKeyDto(
                        id = issued.id.toString(),
                        label = issued.label,
                        keyPrefix = issued.keyPrefix,
                        createdAt = issued.createdAt,
                        createdByMemberId = issued.createdByMemberId.toString(),
                        expiresAt = issued.expiresAt,
                        revokedAt = null,
                        lastUsedAt = null,
                    ),
                rawKey = issued.rawKey,
            )
        }
    }

    override suspend fun revokeApiKey(id: String): ApiKeyDto {
        val current = resolveCurrentMember(call)
        current.requireRole(*API_KEY_ROLES)
        requireWithinRate(limiter = revokeRateLimiter, memberId = current.memberId)
        val apiKeyId = id.toApiKeyUuid()
        return transaction {
            val revoked =
                ApiKeyStore.revoke(id = apiKeyId, revokedByMemberId = current.memberId)
                    ?: throw NotFoundException("API key $id not found or already revoked")
            auditApiKeyRevoke(row = revoked, actorMemberId = current.memberId, actorRole = current.role)
            // Welle V1.3.2 "Webhooks" (ausgehend), S10 -- a revoked key's webhook endpoint (if any)
            // is deactivated in the SAME transaction, its remaining PENDING deliveries abandoned.
            // No notification mail here (unlike a poller-driven deactivation) -- the ADMIN/BOARD
            // caller already knows they just revoked this key; WebhookEndpointDeactivation.deactivate
            // is a no-op (returns null) when no endpoint exists.
            WebhookEndpointDeactivation.deactivate(
                apiKeyId = apiKeyId,
                reason = WebhookDeactivationReason.KEY_REVOKED,
                deactivatedByMemberId = current.memberId,
            )
            revoked.toApiKeyDto()
        }
    }

    override suspend fun reissueApiKey(id: String): ApiKeyIssueResultDto {
        val current = resolveCurrentMember(call)
        current.requireRole(*API_KEY_ROLES)
        requireWithinRate(limiter = reissueRateLimiter, memberId = current.memberId)
        val apiKeyId = id.toApiKeyUuid()
        return transaction {
            val old = ApiKeyStore.getOrNull(apiKeyId) ?: throw NotFoundException("API key $id not found")
            val revoked =
                ApiKeyStore.revoke(id = apiKeyId, revokedByMemberId = current.memberId)
                    ?: throw NotFoundException("API key $id not found or already revoked")
            auditApiKeyRevoke(row = revoked, actorMemberId = current.memberId, actorRole = current.role)
            val issued = ApiKeyStore.issue(label = old.label, createdByMemberId = current.memberId, expiresAt = old.expiresAt)
            auditApiKeyCreate(
                apiKeyId = issued.id,
                label = issued.label,
                keyPrefix = issued.keyPrefix,
                createdByMemberId = issued.createdByMemberId,
                expiresAt = issued.expiresAt,
                actorMemberId = current.memberId,
                actorRole = current.role,
                occurredAt = issued.createdAt,
            )
            // Welle V1.3.2 "Webhooks" (ausgehend), S10 -- reissue mints a NEW key id; without this,
            // an existing webhook endpoint's api_key_id FK would keep pointing at the just-revoked
            // OLD key (orphaned: invisible in the UI, never polled). Migrated in the SAME
            // transaction, active/secret left untouched -- secret and key are independent
            // credentials (see IWebhookService.setWebhookUrl KDoc). A no-op UPDATE (0 rows) when no
            // endpoint exists for the old key.
            if (WebhookEndpointStore.getByApiKeyId(apiKeyId) != null) {
                WebhookEndpointStore.migrateApiKeyId(oldApiKeyId = apiKeyId, newApiKeyId = issued.id)
            }
            ApiKeyIssueResultDto(
                apiKey =
                    ApiKeyDto(
                        id = issued.id.toString(),
                        label = issued.label,
                        keyPrefix = issued.keyPrefix,
                        createdAt = issued.createdAt,
                        createdByMemberId = issued.createdByMemberId.toString(),
                        expiresAt = issued.expiresAt,
                        revokedAt = null,
                        lastUsedAt = null,
                    ),
                rawKey = issued.rawKey,
            )
        }
    }

    /** Same "member:$memberId" keying + `ConflictException` idiom `SepaService.requireWithinRate`/`PaymentGatewayService` already establish -- RPC-layer rate limiting, not the HTTP-429 shape `PublicApiSupport` uses on the REST surface. */
    private fun requireWithinRate(
        limiter: FederationInboxRateLimiter,
        memberId: Uuid,
    ) {
        if (!limiter.checkAndRecord("member:$memberId")) {
            throw ConflictException("Rate limit exceeded, try again later")
        }
    }
}

private const val API_KEY_LABEL_MAX_LENGTH = 100

private fun requireValidLabel(label: String): String {
    val trimmed = label.trim()
    if (trimmed.isBlank()) throw ConflictException("label must not be blank")
    if (trimmed.length > API_KEY_LABEL_MAX_LENGTH) throw ConflictException("label must be at most $API_KEY_LABEL_MAX_LENGTH characters")
    return trimmed
}

private fun String.toApiKeyUuid(): Uuid = runCatching { Uuid.parse(this) }.getOrElse { throw NotFoundException("Invalid id: $this") }

private fun ApiKeyStore.ApiKeyRow.toApiKeyDto(): ApiKeyDto =
    ApiKeyDto(
        id = id.toString(),
        label = label,
        keyPrefix = keyPrefix,
        createdAt = createdAt,
        createdByMemberId = createdByMemberId.toString(),
        expiresAt = expiresAt,
        revokedAt = revokedAt,
        lastUsedAt = lastUsedAt,
    )

/** CREATE -- see [ApiKeySnapshot] KDoc for why the token hash never appears here. */
private fun auditApiKeyCreate(
    apiKeyId: Uuid,
    label: String,
    keyPrefix: String,
    createdByMemberId: Uuid,
    expiresAt: LocalDateTime?,
    actorMemberId: Uuid,
    actorRole: AccountRole,
    occurredAt: LocalDateTime,
) {
    AuditLogRecorder.record(
        actorMemberId = actorMemberId,
        actorRole = actorRole,
        entityType = AuditEntityType.API_KEY,
        entityId = apiKeyId,
        action = AuditAction.CREATE,
        before = null,
        after =
            Json.encodeToString(
                ApiKeySnapshot.serializer(),
                ApiKeySnapshot(
                    label = label,
                    keyPrefix = keyPrefix,
                    createdByMemberId = createdByMemberId.toString(),
                    expiresAt = expiresAt,
                    revokedAt = null,
                ),
            ),
        occurredAt = occurredAt,
    )
}

/** UPDATE -- see [ApiKeySnapshot] KDoc for why the token hash never appears here. */
private fun auditApiKeyRevoke(
    row: ApiKeyStore.ApiKeyRow,
    actorMemberId: Uuid,
    actorRole: AccountRole,
) {
    AuditLogRecorder.record(
        actorMemberId = actorMemberId,
        actorRole = actorRole,
        entityType = AuditEntityType.API_KEY,
        entityId = row.id,
        action = AuditAction.UPDATE,
        before =
            Json.encodeToString(
                ApiKeySnapshot.serializer(),
                ApiKeySnapshot(
                    label = row.label,
                    keyPrefix = row.keyPrefix,
                    createdByMemberId = row.createdByMemberId.toString(),
                    expiresAt = row.expiresAt,
                    revokedAt = null,
                ),
            ),
        after =
            Json.encodeToString(
                ApiKeySnapshot.serializer(),
                ApiKeySnapshot(
                    label = row.label,
                    keyPrefix = row.keyPrefix,
                    createdByMemberId = row.createdByMemberId.toString(),
                    expiresAt = row.expiresAt,
                    revokedAt = row.revokedAt,
                ),
            ),
        occurredAt = row.revokedAt ?: row.createdAt,
    )
}
