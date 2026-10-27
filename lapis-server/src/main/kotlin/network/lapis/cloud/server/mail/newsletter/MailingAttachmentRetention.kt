package network.lapis.cloud.server.mail.newsletter

import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.minus
import network.lapis.cloud.shared.domain.MailingHtmlPolicy
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.io.File

/**
 * V1.9.232 -- deletes the FILES of mailing attachments once their message is `SENT`/`FAILED` and older than
 * [MailingHtmlPolicy.RETENTION_DAYS] (the same period as the tracking retention; the same daily job, see
 * [MailingTrackingRetentionPoller]). The metadata row (name, size, hash) stays and gets `file_deleted_at`, so the board view shows
 * "Datei gelöscht". A `DRAFT`/`QUEUED` message is never touched. Bounded batches, idempotent; the files go AFTER the commit.
 */
internal object MailingAttachmentRetention {
    /** @return the number of attachments handled in this batch (== [batchSize] means "call again"). */
    fun purgeDue(
        now: LocalDateTime,
        storageRoot: File,
        batchSize: Int = 200,
    ): Int {
        val cutoff = LocalDateTime(now.date.minus(DatePeriod(days = MailingHtmlPolicy.RETENTION_DAYS)), now.time)
        val keys = transaction { MailingAttachmentStore.purgeFilesDueInTx(cutoff = cutoff, now = now, batch = batchSize) }
        MailingAttachmentStore.deleteFilesAfterCommit(root = storageRoot, keys = keys)
        return keys.size
    }
}
