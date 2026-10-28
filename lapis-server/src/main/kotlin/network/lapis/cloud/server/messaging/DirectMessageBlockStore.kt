package network.lapis.cloud.server.messaging

import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.server.db.generated.DirectMessageBlockTable
import network.lapis.cloud.server.db.isUniqueViolation
import network.lapis.cloud.server.db.withSavepoint
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import kotlin.uuid.Uuid

/** One direct-message block, as its blocker sees it. */
internal data class DirectMessageBlockRow(
    val id: Uuid,
    val blockerMemberId: Uuid,
    val blockedMemberId: Uuid,
    val createdAt: LocalDateTime,
)

/**
 * V1.9.237 -- `direct_message_block`: "[DirectMessageBlockRow.blockedMemberId] must not write to [DirectMessageBlockRow.blockerMemberId]". The ONLY writer
 * of that table for direct messages (a tripwire pins it). Independent of the member finder: not emptied when the finder switch goes off.
 * Must run inside a `transaction {}`.
 */
internal object DirectMessageBlockStore {
    /**
     * Blocks [blocked] on behalf of [blocker]; idempotent, a self-block is a no-op. A concurrent double block hits the unique constraint -- the
     * insert runs under a savepoint so PostgreSQL's aborted-transaction state (25P02) cannot poison the caller's transaction.
     */
    fun block(
        blocker: Uuid,
        blocked: Uuid,
        now: LocalDateTime,
    ) {
        if (blocker == blocked) return
        val exists =
            DirectMessageBlockTable
                .selectAll()
                .where { (DirectMessageBlockTable.blockerMemberId eq blocker) and (DirectMessageBlockTable.blockedMemberId eq blocked) }
                .any()
        if (exists) return
        try {
            withSavepoint(name = "dm_block_insert") {
                DirectMessageBlockTable.insert {
                    it[id] = Uuid.random()
                    it[blockerMemberId] = blocker
                    it[blockedMemberId] = blocked
                    it[createdAt] = now
                }
            }
        } catch (e: Exception) {
            if (!e.isUniqueViolation()) throw e
        }
    }

    /** Lifts one of [blocker]'s own blocks; `false` for an unknown or foreign id. */
    fun unblock(
        id: Uuid,
        blocker: Uuid,
    ): Boolean =
        DirectMessageBlockTable.deleteWhere {
            (DirectMessageBlockTable.id eq id) and (DirectMessageBlockTable.blockerMemberId eq blocker)
        } > 0

    /** Has either of the two blocked the other? */
    fun isBlockedEitherWay(
        a: Uuid,
        b: Uuid,
    ): Boolean =
        DirectMessageBlockTable
            .selectAll()
            .where {
                ((DirectMessageBlockTable.blockerMemberId eq a) and (DirectMessageBlockTable.blockedMemberId eq b)) or
                    ((DirectMessageBlockTable.blockerMemberId eq b) and (DirectMessageBlockTable.blockedMemberId eq a))
            }.any()

    /** Everybody [memberId] has blocked or is blocked by. */
    fun blockedEitherWay(memberId: Uuid): Set<Uuid> =
        DirectMessageBlockTable
            .selectAll()
            .where { (DirectMessageBlockTable.blockerMemberId eq memberId) or (DirectMessageBlockTable.blockedMemberId eq memberId) }
            .map {
                if (it[DirectMessageBlockTable.blockerMemberId] == memberId) {
                    it[DirectMessageBlockTable.blockedMemberId]
                } else {
                    it[DirectMessageBlockTable.blockerMemberId]
                }
            }.toSet()

    /** [blocker]'s own blocks, newest first (`created_at DESC, id ASC`). */
    fun ownBlocks(
        blocker: Uuid,
        limit: Int,
    ): List<DirectMessageBlockRow> =
        DirectMessageBlockTable
            .selectAll()
            .where { DirectMessageBlockTable.blockerMemberId eq blocker }
            .orderBy(DirectMessageBlockTable.createdAt to SortOrder.DESC, DirectMessageBlockTable.id to SortOrder.ASC)
            .limit(limit)
            .map {
                DirectMessageBlockRow(
                    id = it[DirectMessageBlockTable.id],
                    blockerMemberId = it[DirectMessageBlockTable.blockerMemberId],
                    blockedMemberId = it[DirectMessageBlockTable.blockedMemberId],
                    createdAt = it[DirectMessageBlockTable.createdAt],
                )
            }
}
