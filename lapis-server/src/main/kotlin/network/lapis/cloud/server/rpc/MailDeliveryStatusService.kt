package network.lapis.cloud.server.rpc

import io.ktor.server.application.ApplicationCall
import network.lapis.cloud.server.db.DbClock
import network.lapis.cloud.server.mail.budget.MailBudgetStore
import network.lapis.cloud.server.mail.outbox.MailOutboxStats
import network.lapis.cloud.server.mail.outbox.MailOutboxStatus
import network.lapis.cloud.server.security.requireRole
import network.lapis.cloud.server.security.resolveCurrentMember
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.MailDeliveryStatusDto
import network.lapis.cloud.shared.rpc.IMailDeliveryStatusService

/**
 * Welle V1.9.81 -- see [IMailDeliveryStatusService]. ADMIN only; the role check is the FIRST statement. Counts and the closed
 * `purpose` vocabulary only: no address, no subject, no payload column, no per-row timestamp.
 */
class MailDeliveryStatusService(
    private val call: ApplicationCall,
    private val budget: MailBudgetStore,
    /** Whether the durable outbox is wired (key + SMTP); without it the in-memory path is active and the queue figures are 0. */
    private val outboxEnabled: Boolean,
) : IMailDeliveryStatusService {
    override suspend fun getMailDeliveryStatus(): MailDeliveryStatusDto {
        val current = resolveCurrentMember(call)
        current.requireRole(AccountRole.ADMIN)
        val now = DbClock.nowLocalDateTime()
        val queue = MailOutboxStats.queueState(now)
        return MailDeliveryStatusDto(
            budgetEnabled = budget.enabled,
            maxPerHour = budget.maxPerHour,
            reservePerHour = budget.reservePerHour,
            usedInWindow = if (budget.enabled) budget.usedInWindow(now) else null,
            outboxEnabled = outboxEnabled,
            queuedCount = queue.queuedCount,
            oldestQueuedAgeSeconds = queue.oldestQueuedAgeSeconds,
            failedLast7DaysByPurpose = MailOutboxStats.finishedLast7DaysByPurpose(status = MailOutboxStatus.FAILED, now = now),
            expiredLast7DaysByPurpose = MailOutboxStats.finishedLast7DaysByPurpose(status = MailOutboxStatus.EXPIRED, now = now),
            bulkPausedUntil = budget.bulkPausedUntil()?.takeIf { it > now },
        )
    }
}
