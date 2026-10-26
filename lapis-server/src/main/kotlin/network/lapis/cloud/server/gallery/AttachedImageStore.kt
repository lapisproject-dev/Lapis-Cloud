package network.lapis.cloud.server.gallery

import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.server.db.generated.AttachedImageTable
import network.lapis.cloud.shared.domain.AttachedImageOwnerType
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import kotlin.uuid.Uuid

/** One `attached_image` row. */
internal data class AttachedImageRow(
    val id: Uuid,
    val ownerType: AttachedImageOwnerType,
    val ownerId: Uuid,
    val position: Int,
    val caption: String?,
    val altText: String?,
    val storageKey: Uuid,
    val mimeType: String,
    val byteSize: Long,
    val width: Int,
    val height: Int,
    val uploadedBy: Uuid,
    val createdAt: LocalDateTime,
)

/** The cover picture of an owner: just what the delivery URL needs. */
internal data class GalleryCoverRef(
    val imageId: Uuid,
    val storageKey: Uuid,
)

/** First picture and picture count of one owner (feed preview). */
internal class GalleryPreview(
    val first: AttachedImageRow,
    val count: Int,
)

/**
 * V1.9.222 -- THE ONLY place `attached_image` is read or written (the DSGVO export of
 * [network.lapis.cloud.server.dsgvo.AttachedImagePersonalData]
 * is the one documented exception, see `GalleryTripwireTest`). Every function is `...InTx`: it runs inside the CALLER's
 * transaction and
 * never opens its own. The caller holds the OWNER row lock ([GalleryOwnerPolicy.lockForWriteInTx]) before it writes here, which is what
 * makes
 * "position = count" and the limits race-free; the unique index on (owner, position) is only the safety net.
 *
 * Lock order of every writer: owner row -> `attached_image` rows -> audit entry (last). No member lock.
 *
 * A re-order and the closing of a gap are TWO-PHASE ([renumberInTx]): all affected rows move to `1000 + newIndex` first, then to
 * `newIndex`. A unique
 * index is not deferrable on PostgreSQL, so a direct swap would collide per statement.
 */
internal object AttachedImageStore {
    /** Rows of one owner in gallery order. Every query that returns rows has an ORDER BY. */
    fun listInTx(
        type: AttachedImageOwnerType,
        ownerId: Uuid,
    ): List<AttachedImageRow> =
        AttachedImageTable
            .selectAll()
            .where { (AttachedImageTable.ownerType eq type) and (AttachedImageTable.ownerId eq ownerId) }
            .orderBy(AttachedImageTable.position to SortOrder.ASC, AttachedImageTable.id to SortOrder.ASC)
            .map { it.toRow() }

    fun findInTx(imageId: Uuid): AttachedImageRow? =
        AttachedImageTable
            .selectAll()
            .where { AttachedImageTable.id eq imageId }
            .singleOrNull()
            ?.toRow()

    /** (number of pictures, their total byte size) of one owner. */
    fun countAndBytesInTx(
        type: AttachedImageOwnerType,
        ownerId: Uuid,
    ): Pair<Int, Long> {
        val rows = listInTx(type = type, ownerId = ownerId)
        return rows.size to rows.sumOf { it.byteSize }
    }

    /** Appends a picture at position `count` (the owner row is locked by the caller). */
    fun insertInTx(
        id: Uuid,
        type: AttachedImageOwnerType,
        ownerId: Uuid,
        storageKey: Uuid,
        mimeType: String,
        byteSize: Long,
        width: Int,
        height: Int,
        uploadedBy: Uuid,
        createdAt: LocalDateTime,
    ): AttachedImageRow {
        val position = listInTx(type = type, ownerId = ownerId).size
        AttachedImageTable.insert {
            it[AttachedImageTable.id] = id
            it[AttachedImageTable.ownerType] = type
            it[AttachedImageTable.ownerId] = ownerId
            it[AttachedImageTable.position] = position
            it[caption] = null
            it[altText] = null
            it[AttachedImageTable.storageKey] = storageKey
            it[AttachedImageTable.mimeType] = mimeType
            it[AttachedImageTable.byteSize] = byteSize
            it[AttachedImageTable.width] = width
            it[AttachedImageTable.height] = height
            it[AttachedImageTable.uploadedBy] = uploadedBy
            it[AttachedImageTable.createdAt] = createdAt
        }
        return findInTx(id) ?: error("attached_image row vanished after insert")
    }

    /** Deletes one picture and closes the gap (two-phase). Returns the deleted row, or `null` when it did not exist. */
    fun deleteInTx(imageId: Uuid): AttachedImageRow? {
        val row = findInTx(imageId) ?: return null
        AttachedImageTable.deleteWhere { AttachedImageTable.id eq imageId }
        renumberInTx(
            type = row.ownerType,
            ownerId = row.ownerId,
            orderedIds = listInTx(type = row.ownerType, ownerId = row.ownerId).map { it.id },
        )
        return row
    }

    /** Re-orders to exactly [orderedIds] (the caller verified it is a permutation of all the owner's pictures). Two-phase. */
    fun renumberInTx(
        type: AttachedImageOwnerType,
        ownerId: Uuid,
        orderedIds: List<Uuid>,
    ) {
        val current = listInTx(type = type, ownerId = ownerId).associate { it.id to it.position }
        val moves = orderedIds.withIndex().filter { (index, id) -> current[id] != index }
        if (moves.isEmpty()) return
        moves.forEach { (index, id) -> setPosition(id = id, position = SHIFT_BASE + index) }
        moves.forEach { (index, id) -> setPosition(id = id, position = index) }
    }

    fun updateCaptionInTx(
        imageId: Uuid,
        caption: String?,
        altText: String?,
    ) {
        AttachedImageTable.update({ AttachedImageTable.id eq imageId }) {
            it[AttachedImageTable.caption] = caption
            it[AttachedImageTable.altText] = altText
        }
    }

    /** The cover (position 0) of every owner in [ownerIds] with ONE query per chunk of 500 (no N+1 on a list). */
    fun coverRefsFor(
        type: AttachedImageOwnerType,
        ownerIds: Collection<Uuid>,
    ): Map<Uuid, GalleryCoverRef> {
        if (ownerIds.isEmpty()) return emptyMap()
        val result = HashMap<Uuid, GalleryCoverRef>()
        ownerIds.distinct().chunked(CHUNK).forEach { chunk ->
            AttachedImageTable
                .selectAll()
                .where {
                    (AttachedImageTable.ownerType eq type) and (AttachedImageTable.position eq 0) and
                        (AttachedImageTable.ownerId inList chunk)
                }.orderBy(AttachedImageTable.ownerId to SortOrder.ASC)
                .forEach { row ->
                    result[row[AttachedImageTable.ownerId]] =
                        GalleryCoverRef(imageId = row[AttachedImageTable.id], storageKey = row[AttachedImageTable.storageKey])
                }
        }
        return result
    }

    /** First picture + count per owner with ONE query per chunk of 500; owners without pictures are absent. ORDER BY owner, position. */
    fun previewsFor(
        type: AttachedImageOwnerType,
        ownerIds: Collection<Uuid>,
    ): Map<Uuid, GalleryPreview> {
        if (ownerIds.isEmpty()) return emptyMap()
        val result = LinkedHashMap<Uuid, GalleryPreview>()
        ownerIds.distinct().chunked(CHUNK).forEach { chunk ->
            AttachedImageTable
                .selectAll()
                .where { (AttachedImageTable.ownerType eq type) and (AttachedImageTable.ownerId inList chunk) }
                .orderBy(AttachedImageTable.ownerId to SortOrder.ASC, AttachedImageTable.position to SortOrder.ASC)
                .map { it.toRow() }
                .groupBy { it.ownerId }
                .forEach { (ownerId, rows) -> result[ownerId] = GalleryPreview(first = rows.first(), count = rows.size) }
        }
        return result
    }

    /**
     * The orphan hook: removes every picture of an owner that is being deleted and returns (storage key, MIME type) of each, for the
     * caller
     * to
     * delete the files AFTER the commit. Every code path that deletes a crowdfunding project or an auction MUST call this in the same
     * transaction (`GalleryTripwireTest`). Today there is no such path. For a social post the "delete" is the content tombstone of
     * `executeContentErasure` (a post row is never hard-deleted), and that path calls this hook.
     */
    fun deleteAllForOwnerInTx(
        type: AttachedImageOwnerType,
        ownerId: Uuid,
    ): List<Pair<Uuid, String>> {
        val rows = listInTx(type = type, ownerId = ownerId)
        if (rows.isEmpty()) return emptyList()
        AttachedImageTable.deleteWhere { (AttachedImageTable.ownerType eq type) and (AttachedImageTable.ownerId eq ownerId) }
        return rows.map { it.storageKey to it.mimeType }
    }

    /** Everything a member uploaded, oldest first -- for the DSGVO export and erasure. */
    fun selectByUploaderInTx(memberId: Uuid): List<AttachedImageRow> =
        AttachedImageTable
            .selectAll()
            .where { AttachedImageTable.uploadedBy eq memberId }
            .orderBy(AttachedImageTable.createdAt to SortOrder.ASC, AttachedImageTable.id to SortOrder.ASC)
            .map { it.toRow() }

    /** Hard-deletes everything [memberId] uploaded (DSGVO erasure) and returns the number of rows. The caller closes the gaps
     * afterwards.
     * */
    fun deleteByUploaderInTx(memberId: Uuid): Int = AttachedImageTable.deleteWhere { AttachedImageTable.uploadedBy eq memberId }

    /** Every storage key in use -- for the start sweep only. */
    fun allStorageKeysInTx(): Set<Uuid> =
        AttachedImageTable
            .selectAll()
            .orderBy(AttachedImageTable.id to SortOrder.ASC)
            .map { it[AttachedImageTable.storageKey] }
            .toSet()

    private fun setPosition(
        id: Uuid,
        position: Int,
    ) {
        AttachedImageTable.update({ AttachedImageTable.id eq id }) { it[AttachedImageTable.position] = position }
    }

    private fun ResultRow.toRow() =
        AttachedImageRow(
            id = this[AttachedImageTable.id],
            ownerType = this[AttachedImageTable.ownerType],
            ownerId = this[AttachedImageTable.ownerId],
            position = this[AttachedImageTable.position],
            caption = this[AttachedImageTable.caption],
            altText = this[AttachedImageTable.altText],
            storageKey = this[AttachedImageTable.storageKey],
            mimeType = this[AttachedImageTable.mimeType],
            byteSize = this[AttachedImageTable.byteSize],
            width = this[AttachedImageTable.width],
            height = this[AttachedImageTable.height],
            uploadedBy = this[AttachedImageTable.uploadedBy],
            createdAt = this[AttachedImageTable.createdAt],
        )

    private const val SHIFT_BASE = 1000
    private const val CHUNK = 500
}
