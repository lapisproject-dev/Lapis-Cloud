package network.lapis.cloud.server.bulletin

import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import network.lapis.cloud.server.db.generated.BulletinNoticeBoostTable
import network.lapis.cloud.server.db.generated.BulletinNoticeTable
import network.lapis.cloud.server.gallery.AttachedImageStore
import network.lapis.cloud.server.time.ServerClock
import network.lapis.cloud.shared.domain.AttachedImageOwnerType
import network.lapis.cloud.shared.domain.NoticeRules
import network.lapis.cloud.shared.domain.NoticeStatus
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.lessEq
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import kotlin.time.Duration.Companion.days
import kotlin.uuid.Uuid

/**
 * V1.9.297 -- housekeeping of the bulletin board.
 *
 * [expireDue] only flips `ACTIVE` to `EXPIRED` for notices whose `expires_at` has passed. It is COSMETIC bookkeeping: every read decides on
 * `expires_at`, never on the status (a notice is gone from the board at the same moment whether or not this has run).
 *
 * [deleteDue] removes a closed notice for good [NoticeRules.RETENTION_DAYS_AFTER_CLOSE] days after it closed: expired (or active with a passed
 * `expires_at`) counts from `expires_at`, withdrawn from `withdrawn_at`, removed from `removed_at`. Lock order per notice: the notice row
 * (`FOR UPDATE`), then its picture rows, then its boost rows; no member lock. The LTR ledger rows stay (they are booking history). The picture
 * FILES are returned for the caller to delete AFTER the commit (Exposed may repeat a transaction block, a file delete must not be repeated into one).
 */
internal object NoticeRetention {
    private const val CHUNK = 500
    private const val MAX_CHUNKS_PER_RUN = 20

    /** @return number of notices flipped to `EXPIRED`. */
    fun expireDue(now: LocalDateTime): Int =
        transaction {
            BulletinNoticeTable.update({
                (BulletinNoticeTable.status eq NoticeStatus.ACTIVE) and (BulletinNoticeTable.expiresAt lessEq now)
            }) {
                it[status] = NoticeStatus.EXPIRED
                it[pinnedAt] = null
                it[pinnedBy] = null
            }
        }

    /** Deletes the notices that are due and returns the storage keys of their picture files (to be deleted after the commit). */
    fun deleteDue(now: LocalDateTime): List<Uuid> {
        val cutoff = (now.toInstant(ServerClock.zone) - NoticeRules.RETENTION_DAYS_AFTER_CLOSE.days).toLocalDateTime(ServerClock.zone)
        val keys = ArrayList<Uuid>()
        repeat(MAX_CHUNKS_PER_RUN) {
            val chunkKeys: List<Uuid>? =
                transaction {
                    val due =
                        BulletinNoticeTable
                            .select(BulletinNoticeTable.id)
                            .where {
                                (
                                    (BulletinNoticeTable.status inList listOf(NoticeStatus.ACTIVE, NoticeStatus.EXPIRED)) and
                                        (BulletinNoticeTable.expiresAt lessEq cutoff)
                                ) or
                                    (
                                        (BulletinNoticeTable.status eq NoticeStatus.WITHDRAWN) and
                                            (BulletinNoticeTable.withdrawnAt lessEq cutoff)
                                    ) or
                                    (
                                        (BulletinNoticeTable.status eq NoticeStatus.REMOVED) and
                                            (BulletinNoticeTable.removedAt lessEq cutoff)
                                    )
                            }.orderBy(BulletinNoticeTable.id to SortOrder.ASC)
                            .limit(CHUNK)
                            .forUpdate()
                            .map { it[BulletinNoticeTable.id] }
                    if (due.isEmpty()) null else due.flatMap { noticeId -> deleteNoticeInTx(noticeId) }
                }
            if (chunkKeys == null) return keys
            keys += chunkKeys
        }
        return keys
    }

    /**
     * Deletes one notice with its pictures and boost rows inside the caller's transaction (notice row already locked by the caller, or
     * deleted here by id). Returns the storage keys of the picture files. Used by the poller and by the data-subject erasure.
     */
    fun deleteNoticeInTx(noticeId: Uuid): List<Uuid> {
        val keys = AttachedImageStore.deleteAllForOwnerInTx(type = AttachedImageOwnerType.NOTICE, ownerId = noticeId).map { it.first }
        BulletinNoticeBoostTable.deleteWhere { BulletinNoticeBoostTable.noticeId eq noticeId }
        BulletinNoticeTable.deleteWhere { BulletinNoticeTable.id eq noticeId }
        return keys
    }
}
