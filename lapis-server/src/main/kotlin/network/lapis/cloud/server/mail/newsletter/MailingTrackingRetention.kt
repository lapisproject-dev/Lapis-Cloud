package network.lapis.cloud.server.mail.newsletter

import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.minus
import network.lapis.cloud.server.db.generated.MailingDeliveryLogTable
import network.lapis.cloud.server.db.generated.MailingLinkClickTable
import network.lapis.cloud.server.db.generated.MailingMessageTable
import network.lapis.cloud.shared.domain.MailingHtmlPolicy
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.inSubQuery
import org.jetbrains.exposed.v1.core.isNotNull
import org.jetbrains.exposed.v1.core.lessEq
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update

/**
 * Welle V1.9.15 -- erases raw open/click events once a message is older than
 * [MailingHtmlPolicy.RETENTION_DAYS]: click rows are deleted, the delivery's `open_count`/
 * `first_opened_at`/`*_tracked` are reset. `tracking_token_hash` stays so the links in old mails
 * keep redirecting (they simply no longer count). Works in bounded batches (no giant transaction).
 */
internal object MailingTrackingRetention {
    /** @return the number of deliveries cleaned in this batch (== [batchSize] means "call again"). */
    fun purgeDue(
        now: LocalDateTime,
        batchSize: Int = 200,
    ): Int =
        transaction {
            val cutoff = now.date.minus(DatePeriod(days = MailingHtmlPolicy.RETENTION_DAYS))
            val cutoffDateTime = LocalDateTime(cutoff, now.time)
            val dueMessages = MailingMessageTable.select(MailingMessageTable.id).where { MailingMessageTable.sentAt lessEq cutoffDateTime }
            val dueIds =
                MailingDeliveryLogTable
                    .select(MailingDeliveryLogTable.id)
                    .where {
                        (MailingDeliveryLogTable.mailingMessageId inSubQuery dueMessages) and
                            (
                                (MailingDeliveryLogTable.openTracked eq true) or
                                    (MailingDeliveryLogTable.clickTracked eq true) or
                                    (MailingDeliveryLogTable.openCount greater 0) or
                                    MailingDeliveryLogTable.firstOpenedAt.isNotNull()
                            )
                    }.limit(batchSize)
                    .map { it[MailingDeliveryLogTable.id] }
            if (dueIds.isNotEmpty()) {
                MailingLinkClickTable.deleteWhere { mailingDeliveryLogId inList dueIds }
                MailingDeliveryLogTable.update({ MailingDeliveryLogTable.id inList dueIds }) {
                    it[openTracked] = false
                    it[clickTracked] = false
                    it[openCount] = 0
                    it[firstOpenedAt] = null
                }
            }
            dueIds.size
        }
}
