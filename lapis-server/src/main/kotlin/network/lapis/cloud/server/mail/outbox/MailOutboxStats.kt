package network.lapis.cloud.server.mail.outbox

import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.server.db.generated.MailOutboxTable
import network.lapis.cloud.server.mail.minusDuration
import network.lapis.cloud.server.mail.secondsUntil
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.count
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greaterEq
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.time.Duration.Companion.days

/**
 * Welle V1.9.81 -- aggregate reads over `mail_outbox` for the ADMIN health card. Only counts, an age in seconds, and the closed
 * `purpose` vocabulary leave this object -- never an address, a subject, a payload column or a per-row instant.
 */
object MailOutboxStats {
    data class QueueState(
        val queuedCount: Int,
        val oldestQueuedAgeSeconds: Long?,
    )

    fun queueState(now: LocalDateTime): QueueState =
        transaction {
            val queued =
                MailOutboxTable
                    .selectAll()
                    .where { MailOutboxTable.status eq MailOutboxStatus.QUEUED }
                    .count()
                    .toInt()
            val oldest =
                MailOutboxTable
                    .select(MailOutboxTable.createdAt)
                    .where { MailOutboxTable.status eq MailOutboxStatus.QUEUED }
                    .orderBy(MailOutboxTable.createdAt, SortOrder.ASC)
                    .limit(1)
                    .singleOrNull()
                    ?.get(MailOutboxTable.createdAt)
            QueueState(queuedCount = queued, oldestQueuedAgeSeconds = oldest?.secondsUntil(now)?.coerceAtLeast(0))
        }

    /** Rows that ended in [status] during the last 7 days, per purpose. */
    fun finishedLast7DaysByPurpose(
        status: String,
        now: LocalDateTime,
    ): Map<String, Int> =
        transaction {
            val since = now.minusDuration(7.days)
            val purposeCount = MailOutboxTable.purpose.count()
            MailOutboxTable
                .select(MailOutboxTable.purpose, purposeCount)
                .where { (MailOutboxTable.status eq status) and (MailOutboxTable.finishedAt greaterEq since) }
                .groupBy(MailOutboxTable.purpose)
                .associate { it[MailOutboxTable.purpose] to it[purposeCount].toInt() }
        }
}
