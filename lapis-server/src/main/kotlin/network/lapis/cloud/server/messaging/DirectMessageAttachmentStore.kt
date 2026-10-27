package network.lapis.cloud.server.messaging

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.server.attachments.resolveContainedAttachmentFile
import network.lapis.cloud.server.db.generated.DirectMessageAttachmentTable
import network.lapis.cloud.server.db.generated.DirectMessageTable
import network.lapis.cloud.shared.domain.AttachmentRules
import network.lapis.cloud.shared.domain.DirectMessageAttachmentDto
import org.jetbrains.exposed.v1.core.JoinType
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greaterEq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.isNotNull
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import java.io.File
import kotlin.uuid.Uuid

private val logger = KotlinLogging.logger {}

/** V1.9.233 -- limits of PENDING (not yet sent) direct-message uploads, per uploader across all recipients. */
internal object DirectMessagePendingLimits {
    const val MAX_FILES_PER_UPLOADER = 20
    const val MAX_BYTES_PER_UPLOADER: Long = 80L * 1024 * 1024
    const val PENDING_TTL_HOURS = 24L

    /**
     * Rolling-window budget over ALL uploads of a member (pending AND sent -- sent files are permanent and never purged), so a
     * member cannot fill the shared storage root by looping upload -> send. Checked under the member-row lock.
     */
    const val ROLLING_WINDOW_HOURS = 24L
    const val MAX_FILES_PER_WINDOW = 100
    const val MAX_BYTES_PER_WINDOW: Long = 200L * 1024 * 1024
}

/** One stored direct-message attachment. [messageId] is `null` while the upload is pending. */
internal data class StoredDirectMessageAttachment(
    val id: Uuid,
    val messageId: Uuid?,
    val uploaderId: Uuid,
    val recipientId: Uuid,
    val storageKey: String,
    val fileName: String,
    val nameKey: String,
    val mimeType: String,
    val textCharset: String?,
    val sizeBytes: Long,
    val createdAt: LocalDateTime,
)

/**
 * V1.9.233 -- the ONLY reader and writer of `direct_message_attachment` (exceptions: the DSGVO contributor and the backup catalog; a
 * tripwire enforces it). Every function except [deleteFiles] runs inside the caller's transaction. Rows are always ordered
 * `created_at, id`, so the order is deterministic on PostgreSQL as well. There is deliberately NO role in any predicate: access is
 * "uploader (while pending) / sender or recipient of the message", nothing else -- no BOARD/ADMIN bypass.
 */
internal object DirectMessageAttachmentStore {
    /** Ids per `IN (...)` of [listForMessages]; far below any driver's bind-parameter limit. */
    private const val LIST_CHUNK_SIZE = 1_000

    fun find(id: Uuid): StoredDirectMessageAttachment? =
        DirectMessageAttachmentTable
            .selectAll()
            .where { DirectMessageAttachmentTable.id eq id }
            .singleOrNull()
            ?.toStored()

    /**
     * The attachment [id] if [viewer] may download it, else `null` -- in ONE query so there is no gap between "exists" and "allowed":
     * a pending file only for its uploader, a sent file only for the sender or the recipient of its message.
     */
    fun findDownloadableFor(
        id: Uuid,
        viewer: Uuid,
    ): StoredDirectMessageAttachment? =
        DirectMessageAttachmentTable
            .join(DirectMessageTable, JoinType.LEFT, DirectMessageAttachmentTable.messageId, DirectMessageTable.id)
            .selectAll()
            .where {
                (DirectMessageAttachmentTable.id eq id) and
                    (
                        (DirectMessageAttachmentTable.messageId.isNull() and (DirectMessageAttachmentTable.uploaderId eq viewer)) or
                            (
                                DirectMessageAttachmentTable.messageId.isNotNull() and
                                    ((DirectMessageTable.senderId eq viewer) or (DirectMessageTable.recipientId eq viewer))
                            )
                    )
            }.singleOrNull()
            ?.toStored()

    /** Number and total size of the pending uploads of [uploader] addressed to [recipient]. */
    fun pendingForPair(
        uploader: Uuid,
        recipient: Uuid,
    ): Pair<Long, Long> =
        pendingSizes { (DirectMessageAttachmentTable.uploaderId eq uploader) and (DirectMessageAttachmentTable.recipientId eq recipient) }

    /** Number and total size of all pending uploads of [uploader]. */
    fun pendingForUploader(uploader: Uuid): Pair<Long, Long> = pendingSizes { DirectMessageAttachmentTable.uploaderId eq uploader }

    /** Number and total size of ALL uploads (pending and sent) of [uploader] created at or after [since]. */
    fun uploadedSince(
        uploader: Uuid,
        since: LocalDateTime,
    ): Pair<Long, Long> {
        val sizes =
            DirectMessageAttachmentTable
                .select(DirectMessageAttachmentTable.sizeBytes)
                .where {
                    (DirectMessageAttachmentTable.uploaderId eq uploader) and (DirectMessageAttachmentTable.createdAt greaterEq since)
                }.map { it[DirectMessageAttachmentTable.sizeBytes] }
        return sizes.size.toLong() to sizes.sum()
    }

    private fun pendingSizes(extra: () -> org.jetbrains.exposed.v1.core.Op<Boolean>): Pair<Long, Long> {
        val sizes =
            DirectMessageAttachmentTable
                .select(DirectMessageAttachmentTable.sizeBytes)
                .where { DirectMessageAttachmentTable.messageId.isNull() and extra() }
                .map { it[DirectMessageAttachmentTable.sizeBytes] }
        return sizes.size.toLong() to sizes.sum()
    }

    /** The pending uploads of [uploader] addressed to [recipient], upload order (so a reloaded composer can show them again). */
    fun listPendingFor(
        uploader: Uuid,
        recipient: Uuid,
    ): List<StoredDirectMessageAttachment> =
        DirectMessageAttachmentTable
            .selectAll()
            .where {
                DirectMessageAttachmentTable.messageId.isNull() and
                    (DirectMessageAttachmentTable.uploaderId eq uploader) and
                    (DirectMessageAttachmentTable.recipientId eq recipient)
            }.orderBy(
                DirectMessageAttachmentTable.createdAt to SortOrder.ASC,
                DirectMessageAttachmentTable.id to SortOrder.ASC,
            ).map { it.toStored() }

    fun pendingNameKeyExists(
        uploader: Uuid,
        recipient: Uuid,
        nameKey: String,
    ): Boolean =
        DirectMessageAttachmentTable
            .select(DirectMessageAttachmentTable.id)
            .where {
                DirectMessageAttachmentTable.messageId.isNull() and
                    (DirectMessageAttachmentTable.uploaderId eq uploader) and
                    (DirectMessageAttachmentTable.recipientId eq recipient) and
                    (DirectMessageAttachmentTable.nameKey eq nameKey)
            }.limit(1)
            .any()

    @Suppress("LongParameterList")
    fun insertPendingInTx(
        attachmentId: Uuid,
        uploader: Uuid,
        recipient: Uuid,
        storageKey: String,
        fileName: String,
        nameKey: String,
        mimeType: String,
        textCharset: String?,
        sizeBytes: Long,
        sha256: String,
        createdAt: LocalDateTime,
    ) {
        DirectMessageAttachmentTable.insert {
            it[id] = attachmentId
            it[messageId] = null
            it[uploaderId] = uploader
            it[recipientId] = recipient
            it[DirectMessageAttachmentTable.storageKey] = storageKey
            it[originalFilename] = fileName
            it[DirectMessageAttachmentTable.nameKey] = nameKey
            it[DirectMessageAttachmentTable.mimeType] = mimeType
            it[DirectMessageAttachmentTable.textCharset] = textCharset
            it[DirectMessageAttachmentTable.sizeBytes] = sizeBytes
            it[DirectMessageAttachmentTable.sha256] = sha256
            it[DirectMessageAttachmentTable.createdAt] = createdAt
        }
    }

    /**
     * Deletes the PENDING upload [id] of [uploader] and returns its storage key (the file is removed AFTER the commit). `null` for an
     * unknown, foreign or already-sent id -- the caller answers all of them identically.
     */
    fun deletePendingInTx(
        id: Uuid,
        uploader: Uuid,
    ): String? {
        val row =
            DirectMessageAttachmentTable
                .select(DirectMessageAttachmentTable.storageKey)
                .where {
                    (DirectMessageAttachmentTable.id eq id) and
                        DirectMessageAttachmentTable.messageId.isNull() and
                        (DirectMessageAttachmentTable.uploaderId eq uploader)
                }.forUpdate()
                .singleOrNull() ?: return null
        DirectMessageAttachmentTable.deleteWhere {
            (DirectMessageAttachmentTable.id eq id) and DirectMessageAttachmentTable.messageId.isNull()
        }
        return row[DirectMessageAttachmentTable.storageKey]
    }

    /**
     * Assigns the pending uploads [ids] of [uploader] for [recipient] to [messageId], atomically with the message insert (the caller's
     * transaction). Returns the attached rows in upload order, or `null` if ANYTHING is off: an id that is unknown, foreign, already
     * sent, addressed to someone else, a duplicate id, more than [AttachmentRules.MAX_FILES] files, more than
     * [AttachmentRules.MAX_TOTAL_BYTES], or a duplicate file name. The caller turns `null` into ONE uniform error (no oracle).
     */
    fun attachPendingInTx(
        ids: List<Uuid>,
        uploader: Uuid,
        recipient: Uuid,
        messageId: Uuid,
    ): List<StoredDirectMessageAttachment>? {
        if (ids.isEmpty() || ids.size != ids.toSet().size || ids.size > AttachmentRules.MAX_FILES) return null
        val rows =
            DirectMessageAttachmentTable
                .selectAll()
                .where {
                    (DirectMessageAttachmentTable.id inList ids) and
                        DirectMessageAttachmentTable.messageId.isNull() and
                        (DirectMessageAttachmentTable.uploaderId eq uploader) and
                        (DirectMessageAttachmentTable.recipientId eq recipient)
                }.orderBy(
                    DirectMessageAttachmentTable.createdAt to SortOrder.ASC,
                    DirectMessageAttachmentTable.id to SortOrder.ASC,
                ).forUpdate()
                .map { it.toStored() }
        if (rows.size != ids.size) return null
        if (rows.sumOf { it.sizeBytes } > AttachmentRules.MAX_TOTAL_BYTES) return null
        if (rows.map { it.nameKey }.toSet().size != rows.size) return null
        DirectMessageAttachmentTable.update({
            (DirectMessageAttachmentTable.id inList ids) and DirectMessageAttachmentTable.messageId.isNull()
        }) {
            it[DirectMessageAttachmentTable.messageId] = messageId
        }
        return rows.map { it.copy(messageId = messageId) }
    }

    /** The attachments of [messageIds] in ONE query (no N+1), upload order per message. */
    fun listForMessages(messageIds: Collection<Uuid>): Map<Uuid, List<DirectMessageAttachmentDto>> {
        if (messageIds.isEmpty()) return emptyMap()
        val result = LinkedHashMap<Uuid, MutableList<DirectMessageAttachmentDto>>()
        // Chunked: one `IN (...)` with every id of a very large inbox would exceed the JDBC / PostgreSQL bind-parameter limit.
        messageIds.distinct().chunked(LIST_CHUNK_SIZE).forEach { chunk ->
            DirectMessageAttachmentTable
                .selectAll()
                .where { DirectMessageAttachmentTable.messageId inList chunk }
                .orderBy(
                    DirectMessageAttachmentTable.createdAt to SortOrder.ASC,
                    DirectMessageAttachmentTable.id to SortOrder.ASC,
                ).forEach { row ->
                    val owner = row[DirectMessageAttachmentTable.messageId] ?: return@forEach
                    result.getOrPut(owner) { mutableListOf() } +=
                        DirectMessageAttachmentDto(
                            id = row[DirectMessageAttachmentTable.id].toString(),
                            fileName = row[DirectMessageAttachmentTable.originalFilename],
                            mimeType = row[DirectMessageAttachmentTable.mimeType],
                            sizeBytes = row[DirectMessageAttachmentTable.sizeBytes],
                        )
                }
        }
        return result
    }

    /** DSGVO hard delete: every attachment [memberId] uploaded (sent AND pending). Returns the storage keys; the caller removes the files. */
    fun deleteAllUploadedByInTx(memberId: Uuid): List<String> {
        val keys =
            DirectMessageAttachmentTable
                .select(DirectMessageAttachmentTable.storageKey)
                .where { DirectMessageAttachmentTable.uploaderId eq memberId }
                .map { it[DirectMessageAttachmentTable.storageKey] }
        DirectMessageAttachmentTable.deleteWhere { uploaderId eq memberId }
        return keys
    }

    /** DSGVO (every mode): the pending uploads of [memberId] have no value for anybody else. */
    fun deletePendingForUploaderInTx(memberId: Uuid): List<String> {
        val keys =
            DirectMessageAttachmentTable
                .select(DirectMessageAttachmentTable.storageKey)
                .where { DirectMessageAttachmentTable.uploaderId eq memberId and DirectMessageAttachmentTable.messageId.isNull() }
                .map { it[DirectMessageAttachmentTable.storageKey] }
        DirectMessageAttachmentTable.deleteWhere { (uploaderId eq memberId) and messageId.isNull() }
        return keys
    }

    /**
     * Retention: deletes up to [batchSize] PENDING rows created before [cutoff] and returns their storage keys. The rows are locked
     * (`FOR UPDATE`) first, so a send that assigns one of them at the same moment either wins (the row no longer matches) or loses
     * (the row is gone and the send fails uniformly) -- a file is never deleted behind a surviving row.
     */
    fun purgePendingDueInTx(
        cutoff: LocalDateTime,
        batchSize: Int,
    ): List<String> {
        val due =
            DirectMessageAttachmentTable
                .select(DirectMessageAttachmentTable.id, DirectMessageAttachmentTable.storageKey)
                .where { DirectMessageAttachmentTable.messageId.isNull() and (DirectMessageAttachmentTable.createdAt less cutoff) }
                .orderBy(DirectMessageAttachmentTable.id to SortOrder.ASC)
                .limit(batchSize)
                .forUpdate()
                .map { it[DirectMessageAttachmentTable.id] to it[DirectMessageAttachmentTable.storageKey] }
        if (due.isEmpty()) return emptyList()
        val ids = due.map { it.first }
        DirectMessageAttachmentTable.deleteWhere { (id inList ids) and messageId.isNull() }
        return due.map { it.second }
    }

    /** Removes the files of [keys] under [root] (each contained in the root); a failure is only counted, never names a file. */
    fun deleteFiles(
        root: File,
        keys: Collection<String>,
    ) {
        var failed = 0
        for (key in keys) {
            val file = resolveContainedAttachmentFile(root = root, storageKey = key) ?: continue
            if (file.exists() && !runCatching { file.delete() }.getOrDefault(false)) failed++
        }
        if (failed > 0) logger.warn { "direct message attachment cleanup: $failed file(s) could not be deleted" }
    }

    private fun ResultRow.toStored() =
        StoredDirectMessageAttachment(
            id = this[DirectMessageAttachmentTable.id],
            messageId = this[DirectMessageAttachmentTable.messageId],
            uploaderId = this[DirectMessageAttachmentTable.uploaderId],
            recipientId = this[DirectMessageAttachmentTable.recipientId],
            storageKey = this[DirectMessageAttachmentTable.storageKey],
            fileName = this[DirectMessageAttachmentTable.originalFilename],
            nameKey = this[DirectMessageAttachmentTable.nameKey],
            mimeType = this[DirectMessageAttachmentTable.mimeType],
            textCharset = this[DirectMessageAttachmentTable.textCharset],
            sizeBytes = this[DirectMessageAttachmentTable.sizeBytes],
            createdAt = this[DirectMessageAttachmentTable.createdAt],
        )
}
