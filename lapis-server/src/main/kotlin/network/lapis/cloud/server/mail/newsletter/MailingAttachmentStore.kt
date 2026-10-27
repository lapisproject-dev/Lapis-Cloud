package network.lapis.cloud.server.mail.newsletter

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.server.db.generated.MailingMessageAttachmentTable
import network.lapis.cloud.server.db.generated.MailingMessageTable
import network.lapis.cloud.shared.domain.MailingAttachmentDto
import network.lapis.cloud.shared.domain.MailingMessageStatus
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.inSubQuery
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.lessEq
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import java.io.File
import kotlin.uuid.Uuid

private val logger = KotlinLogging.logger {}

/** One stored attachment as the sender needs it: where the bytes are and what they must look like. */
internal data class StoredMailingAttachment(
    val id: Uuid,
    val mailingMessageId: Uuid,
    val storageKey: String,
    val fileName: String,
    val mimeType: String,
    val textCharset: String?,
    val sizeBytes: Long,
    val sha256: String,
    val fileDeletedAt: LocalDateTime?,
)

/**
 * V1.9.232 -- the ONLY reader and writer of `mailing_message_attachment` (exceptions: the DSGVO contributor and the backup catalog; a
 * tripwire enforces it). Every function except [deleteFilesAfterCommit] must run inside the caller's transaction. Rows are always
 * ordered `uploaded_at, id`, so the order (list, mail) is deterministic on PostgreSQL as well.
 */
internal object MailingAttachmentStore {
    /** Attachments of [messageIds] in ONE query (no N+1), upload order per message. */
    fun listForMessages(messageIds: Collection<Uuid>): Map<Uuid, List<MailingAttachmentDto>> {
        if (messageIds.isEmpty()) return emptyMap()
        return MailingMessageAttachmentTable
            .selectAll()
            .where { MailingMessageAttachmentTable.mailingMessageId inList messageIds.toList() }
            .orderBy(
                MailingMessageAttachmentTable.mailingMessageId to SortOrder.ASC,
                MailingMessageAttachmentTable.uploadedAt to SortOrder.ASC,
                MailingMessageAttachmentTable.id to SortOrder.ASC,
            ).groupBy({ it[MailingMessageAttachmentTable.mailingMessageId] }) { row ->
                MailingAttachmentDto(
                    id = row[MailingMessageAttachmentTable.id].toString(),
                    fileName = row[MailingMessageAttachmentTable.originalFilename],
                    mimeType = row[MailingMessageAttachmentTable.mimeType],
                    sizeBytes = row[MailingMessageAttachmentTable.sizeBytes],
                    fileAvailable = row[MailingMessageAttachmentTable.fileDeletedAt] == null,
                )
            }
    }

    fun listForMessage(messageId: Uuid): List<MailingAttachmentDto> = listForMessages(listOf(messageId))[messageId].orEmpty()

    /** Number of attachments and the sum of their sizes of [messageId]. */
    fun countAndTotalBytes(messageId: Uuid): Pair<Long, Long> {
        val rows =
            MailingMessageAttachmentTable
                .select(MailingMessageAttachmentTable.sizeBytes)
                .where { MailingMessageAttachmentTable.mailingMessageId eq messageId }
                .map { it[MailingMessageAttachmentTable.sizeBytes] }
        return rows.size.toLong() to rows.sum()
    }

    fun nameKeyExists(
        messageId: Uuid,
        nameKey: String,
    ): Boolean =
        MailingMessageAttachmentTable
            .select(MailingMessageAttachmentTable.id)
            .where {
                (MailingMessageAttachmentTable.mailingMessageId eq messageId) and (MailingMessageAttachmentTable.nameKey eq nameKey)
            }.limit(1)
            .any()

    fun find(attachmentId: Uuid): StoredMailingAttachment? =
        MailingMessageAttachmentTable
            .selectAll()
            .where { MailingMessageAttachmentTable.id eq attachmentId }
            .singleOrNull()
            ?.toStored()

    @Suppress("LongParameterList")
    fun insertInTx(
        attachmentId: Uuid,
        messageId: Uuid,
        storageKey: String,
        fileName: String,
        nameKey: String,
        mimeType: String,
        textCharset: String?,
        sizeBytes: Long,
        sha256: String,
        uploadedBy: Uuid,
        uploadedAt: LocalDateTime,
    ) {
        MailingMessageAttachmentTable.insert {
            it[id] = attachmentId
            it[mailingMessageId] = messageId
            it[MailingMessageAttachmentTable.storageKey] = storageKey
            it[originalFilename] = fileName
            it[MailingMessageAttachmentTable.nameKey] = nameKey
            it[MailingMessageAttachmentTable.mimeType] = mimeType
            it[MailingMessageAttachmentTable.textCharset] = textCharset
            it[MailingMessageAttachmentTable.sizeBytes] = sizeBytes
            it[MailingMessageAttachmentTable.sha256] = sha256
            it[MailingMessageAttachmentTable.uploadedBy] = uploadedBy
            it[MailingMessageAttachmentTable.uploadedAt] = uploadedAt
        }
    }

    /** Deletes one row; returns its storage key (the file is removed AFTER the commit), or `null` if there was no such row. */
    fun deleteOneInTx(attachmentId: Uuid): String? {
        val key = find(attachmentId)?.storageKey ?: return null
        MailingMessageAttachmentTable.deleteWhere { MailingMessageAttachmentTable.id eq attachmentId }
        return key
    }

    /**
     * Deletes every row of [messageId] and returns the storage keys. The hook for any (future) deletion of a mailing message: the
     * `mailing_message_attachment.mailing_message_id` FK would otherwise block it, and the files would be orphaned.
     */
    fun deleteAllForMessageInTx(messageId: Uuid): List<String> {
        val keys =
            MailingMessageAttachmentTable
                .select(MailingMessageAttachmentTable.storageKey)
                .where { MailingMessageAttachmentTable.mailingMessageId eq messageId }
                .map { it[MailingMessageAttachmentTable.storageKey] }
        MailingMessageAttachmentTable.deleteWhere { mailingMessageId eq messageId }
        return keys
    }

    /** Removes the files of [keys] under [root]; a failure is only counted (never names a file). Call AFTER the commit. */
    fun deleteFilesAfterCommit(
        root: File,
        keys: Collection<String>,
    ) {
        var failed = 0
        for (key in keys) {
            val file = root.resolve(key)
            if (file.exists() && !file.delete()) failed++
        }
        if (failed > 0) logger.warn { "mailing attachment cleanup: $failed file(s) could not be deleted" }
    }

    /** The attachments of [messageId] the worker must put into every mail, in upload order. */
    fun sendableForMessage(messageId: Uuid): List<StoredMailingAttachment> =
        MailingMessageAttachmentTable
            .selectAll()
            .where { MailingMessageAttachmentTable.mailingMessageId eq messageId }
            .orderBy(
                MailingMessageAttachmentTable.uploadedAt to SortOrder.ASC,
                MailingMessageAttachmentTable.id to SortOrder.ASC,
            ).map { it.toStored() }

    /**
     * Retention (inside a transaction): marks up to [batch] attachments of messages that are `SENT`/`FAILED` and were sent at or before
     * [cutoff] as file-deleted and returns their storage keys; the caller deletes the files after the commit. The metadata row stays.
     */
    fun purgeFilesDueInTx(
        cutoff: LocalDateTime,
        now: LocalDateTime,
        batch: Int,
    ): List<String> {
        val dueMessages =
            MailingMessageTable
                .select(MailingMessageTable.id)
                .where {
                    (MailingMessageTable.status inList listOf(MailingMessageStatus.SENT, MailingMessageStatus.FAILED)) and
                        (MailingMessageTable.sentAt lessEq cutoff)
                }
        val due =
            MailingMessageAttachmentTable
                .select(MailingMessageAttachmentTable.id, MailingMessageAttachmentTable.storageKey)
                .where {
                    (MailingMessageAttachmentTable.mailingMessageId inSubQuery dueMessages) and
                        MailingMessageAttachmentTable.fileDeletedAt.isNull()
                }.orderBy(MailingMessageAttachmentTable.id to SortOrder.ASC)
                .limit(batch)
                .map { it[MailingMessageAttachmentTable.id] to it[MailingMessageAttachmentTable.storageKey] }
        if (due.isEmpty()) return emptyList()
        MailingMessageAttachmentTable.update({ MailingMessageAttachmentTable.id inList due.map { it.first } }) {
            it[fileDeletedAt] = now
        }
        return due.map { it.second }
    }

    private fun org.jetbrains.exposed.v1.core.ResultRow.toStored() =
        StoredMailingAttachment(
            id = this[MailingMessageAttachmentTable.id],
            mailingMessageId = this[MailingMessageAttachmentTable.mailingMessageId],
            storageKey = this[MailingMessageAttachmentTable.storageKey],
            fileName = this[MailingMessageAttachmentTable.originalFilename],
            mimeType = this[MailingMessageAttachmentTable.mimeType],
            textCharset = this[MailingMessageAttachmentTable.textCharset],
            sizeBytes = this[MailingMessageAttachmentTable.sizeBytes],
            sha256 = this[MailingMessageAttachmentTable.sha256],
            fileDeletedAt = this[MailingMessageAttachmentTable.fileDeletedAt],
        )
}
