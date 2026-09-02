package network.lapis.cloud.server.webhook

import kotlinx.serialization.json.Json
import network.lapis.cloud.server.audit.AuditLogRecorder
import network.lapis.cloud.shared.domain.AuditAction
import network.lapis.cloud.shared.domain.AuditEntityType
import network.lapis.cloud.shared.domain.WebhookDeactivationReason
import network.lapis.cloud.shared.domain.WebhookEndpointSnapshot
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.uuid.Uuid

/** Result of [WebhookEndpointDeactivation.deactivate] -- `null` iff no endpoint existed for the given `apiKeyId` or it was already inactive (a no-op the caller must NOT send a notification mail for). */
internal data class WebhookDeactivationResult(
    val endpoint: WebhookEndpointStore.EndpointRow,
    val recipients: List<String>,
)

/**
 * Welle V1.3.2 "Webhooks" (ausgehend) -- the ONE shared deactivation path, plan §5.6: every caller
 * that deactivates a webhook endpoint (the poller's own delivery-failure/410-Gone paths, AND
 * `ApiKeyService.revokeApiKey`'s `KEY_REVOKED` cascade) goes through [deactivate] so these steps
 * always happen TOGETHER, atomically, in this exact order:
 *
 * 1. `webhook_endpoint.active = false` (idempotent -- a no-op, returning `null`, if already
 *    inactive).
 * 2. Every remaining `PENDING` delivery of that endpoint -> `ABANDONED`/`ENDPOINT_DEACTIVATED`.
 * 3. The BOARD/ADMIN recipient list is computed HERE (not later, inside
 *    [WebhookDeactivationNotifier]) specifically so its SIZE can be baked into the very audit entry
 *    below -- D4d: once this transaction commits, the audit row is immutable, so this is the only
 *    chance to record "N recipients were eligible" alongside the deactivation fact itself.
 * 4. A `WEBHOOK_ENDPOINT`/`UPDATE` audit entry, `actorMemberId = null` for a poller-driven
 *    deactivation (the SYSTEM actor) or the caller-supplied member for `KEY_REVOKED` -- as the
 *    LAST write of this step (see `AuditLogRecorder`'s own deadlock-avoidance contract).
 *
 * The notification mail itself ([WebhookDeactivationNotifier.notify]) is deliberately called by
 * the CALLER, AFTER this function returns (i.e. after the transaction has committed) -- passing
 * along the exact [WebhookDeactivationResult.recipients] list computed in step 3, so the audit
 * entry's recorded count and the actually-notified count can never drift apart.
 *
 * Exposed's `transaction {}` reuses an already-open transaction on the SAME thread rather than
 * nesting a second one (verified against this project's other multi-step writes, e.g.
 * `ContributionPostingBridge`'s own callers) -- so whether [deactivate] is invoked from inside an
 * already-open `transaction {}` (as `ApiKeyService.revokeApiKey` does) or stand-alone (as
 * [WebhookDeliveryPoller] does), every write below commits or rolls back together either way.
 */
internal object WebhookEndpointDeactivation {
    fun deactivate(
        apiKeyId: Uuid,
        reason: WebhookDeactivationReason,
        deactivatedByMemberId: Uuid? = null,
    ): WebhookDeactivationResult? =
        transaction {
            val endpoint =
                WebhookEndpointStore.deactivate(
                    apiKeyId = apiKeyId,
                    reason = reason,
                    deactivatedByMemberId = deactivatedByMemberId,
                ) ?: return@transaction null
            WebhookDeliveryQueue.abandonAllPendingForEndpoint(endpointId = endpoint.id)
            val recipients = boardAndAdminMemberEmails()
            AuditLogRecorder.record(
                actorMemberId = deactivatedByMemberId,
                actorRole = null,
                entityType = AuditEntityType.WEBHOOK_ENDPOINT,
                entityId = endpoint.id,
                action = AuditAction.UPDATE,
                before = null,
                after =
                    Json.encodeToString(
                        WebhookEndpointSnapshot.serializer(),
                        WebhookEndpointSnapshot(
                            apiKeyId = apiKeyId.toString(),
                            url = endpoint.url,
                            active = false,
                            deactivationReason = reason,
                            notifiedRecipients = recipients.size.coerceAtMost(WEBHOOK_NOTIFICATION_MAX_RECIPIENTS),
                            totalRecipients = recipients.size,
                        ),
                    ),
            )
            WebhookDeactivationResult(endpoint = endpoint, recipients = recipients)
        }
}
