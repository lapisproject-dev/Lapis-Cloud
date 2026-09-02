package network.lapis.cloud.server.webhook

import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.http.Url
import network.lapis.cloud.server.db.generated.AccountTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.server.mail.MailBranding
import network.lapis.cloud.server.mail.MailDispatcher
import network.lapis.cloud.server.mail.MailTemplates
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.MemberStatus
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.transactions.transaction

private val logger = KotlinLogging.logger {}

/** Design-Team decision D4d -- hard cap on how many BOARD/ADMIN members are mailed per deactivation event. */
internal const val WEBHOOK_NOTIFICATION_MAX_RECIPIENTS = 20

/**
 * `AccountTable.role in (BOARD, ADMIN)` × `MemberTable.status == ACTIVE`, ordered by `member.id`
 * for determinism -- top-level (not a method) so [WebhookEndpointDeactivation.deactivate] can call
 * it too, BEFORE deactivating, to compute the [totalRecipients] count that goes into the SAME
 * audit entry the deactivation itself writes (D4d -- see that class's own call site; the count
 * must be baked into the immutable audit row, which can no longer be edited once
 * [WebhookDeactivationNotifier.notify] runs after commit). No pre-existing BOARD/ADMIN mail-
 * recipient helper was found elsewhere in this codebase (`MailingService`'s own `BOARD_ROLES`
 * constant is an RPC role-gate, not a recipient query) -- this is the first one.
 */
internal fun boardAndAdminMemberEmails(): List<String> =
    transaction {
        (AccountTable innerJoin MemberTable)
            .select(MemberTable.email)
            .where {
                (AccountTable.role inList listOf(AccountRole.BOARD, AccountRole.ADMIN)) and
                    (MemberTable.status eq MemberStatus.ACTIVE)
            }.orderBy(MemberTable.id, SortOrder.ASC)
            .map { it[MemberTable.email] }
            .distinct()
    }

/**
 * Welle V1.3.2 "Webhooks" (ausgehend) -- sends the "your webhook was deactivated" mail to every
 * BOARD/ADMIN member (capped at [WEBHOOK_NOTIFICATION_MAX_RECIPIENTS]) after
 * [WebhookEndpointDeactivation.deactivate] actually deactivated an endpoint (never for a no-op --
 * see that function's own return contract). Deliberately a plain, stateless class (no DB writes of
 * its own) called AFTER the deactivation's own transaction has committed --
 * `network.lapis.cloud.server.mail.MailDispatcher.enqueue` is itself fire-and-forget and must never
 * be inside the SAME transaction as the audit entry it describes (a slow/failed enqueue must never
 * roll back the deactivation itself).
 */
internal class WebhookDeactivationNotifier(
    private val mailDispatcher: MailDispatcher,
    private val branding: MailBranding,
) {
    /**
     * [recipients] is the SAME list [WebhookEndpointDeactivation.deactivate] already computed
     * (via [boardAndAdminMemberEmails]) and recorded the size of into the audit entry (D4d) --
     * re-querying here would risk a between-commit-and-send membership change producing a
     * DIFFERENT count than what the audit entry says was notified, so the caller passes the exact
     * same list through instead.
     */
    fun notify(
        endpoint: WebhookEndpointStore.EndpointRow,
        delivery: WebhookDeliveryQueue.DeliveryRow,
        httpStatus: Int?,
        recipients: List<String>,
    ) {
        try {
            val urlHost = runCatching { Url(endpoint.url).host }.getOrDefault("(unbekannt)")
            val rendered =
                MailTemplates.webhookEndpointDeactivated(
                    apiKeyLabel = endpoint.apiKeyLabel,
                    urlHost = urlHost,
                    eventTypeLabel = delivery.eventType.wireName,
                    attemptCount = delivery.attemptCount,
                    lastHttpStatus = httpStatus,
                    branding = branding,
                )
            val notified = recipients.take(WEBHOOK_NOTIFICATION_MAX_RECIPIENTS)
            notified.forEach { email ->
                mailDispatcher.enqueue(
                    to = email,
                    subject = rendered.subject,
                    plainTextBody = rendered.plainText,
                    htmlBody = rendered.html,
                    purpose = "webhook-endpoint-deactivated",
                )
            }
            // D4d -- see WebhookEndpointSnapshot.notifiedRecipients/totalRecipients for the
            // audit-entry-level record of this same fact. If MailDispatcher's own queue is ALSO
            // saturated (MailDispatcher KDoc "DoS deckel"), an individual enqueue can still be
            // silently dropped -- this notifier cannot see that, by MailDispatcher's own design.
            if (recipients.size > WEBHOOK_NOTIFICATION_MAX_RECIPIENTS) {
                logger.warn {
                    "WebhookDeactivationNotifier: ${recipients.size} BOARD/ADMIN recipients found, only " +
                        "$WEBHOOK_NOTIFICATION_MAX_RECIPIENTS notified (endpoint=${endpoint.id})"
                }
            }
        } catch (e: Throwable) {
            logger.warn(e) { "WebhookDeactivationNotifier: notify failed for endpoint=${endpoint.id}" }
        }
    }
}
