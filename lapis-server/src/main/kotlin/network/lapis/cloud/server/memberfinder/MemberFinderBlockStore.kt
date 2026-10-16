package network.lapis.cloud.server.memberfinder

import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.server.db.generated.MemberFinderBlockTable
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import kotlin.uuid.Uuid

/** One block, as the blocker sees it. */
internal data class MemberFinderBlockRow(
    val id: Uuid,
    val blockerMemberId: Uuid,
    val blockedMemberId: Uuid,
    val createdAt: LocalDateTime,
)

/**
 * V1.9.144 -- `member_finder_block`. A block hides both people from each other in the random draw, in both directions. The writers hold the global
 * finder lock, so the pair is checked before the insert (no unique-violation handling needed). Must run inside a `transaction {}`.
 */
internal object MemberFinderBlockStore {
    /** Blocks [blockedMemberId] on behalf of [blockerMemberId]; a repeated block is a silent no-op. */
    fun block(
        blockerMemberId: Uuid,
        blockedMemberId: Uuid,
        now: LocalDateTime,
    ) {
        if (blockerMemberId == blockedMemberId) return
        val exists =
            MemberFinderBlockTable
                .selectAll()
                .where {
                    (MemberFinderBlockTable.blockerMemberId eq blockerMemberId) and
                        (MemberFinderBlockTable.blockedMemberId eq blockedMemberId)
                }.any()
        if (exists) return
        MemberFinderBlockTable.insert {
            it[id] = Uuid.random()
            it[MemberFinderBlockTable.blockerMemberId] = blockerMemberId
            it[MemberFinderBlockTable.blockedMemberId] = blockedMemberId
            it[createdAt] = now
        }
    }

    fun unblock(id: Uuid) {
        MemberFinderBlockTable.deleteWhere { MemberFinderBlockTable.id eq id }
    }

    fun find(id: Uuid): MemberFinderBlockRow? =
        MemberFinderBlockTable
            .selectAll()
            .where { MemberFinderBlockTable.id eq id }
            .singleOrNull()
            ?.let {
                MemberFinderBlockRow(
                    id = it[MemberFinderBlockTable.id],
                    blockerMemberId = it[MemberFinderBlockTable.blockerMemberId],
                    blockedMemberId = it[MemberFinderBlockTable.blockedMemberId],
                    createdAt = it[MemberFinderBlockTable.createdAt],
                )
            }

    fun ownBlocks(
        blockerMemberId: Uuid,
        limit: Int,
    ): List<MemberFinderBlockRow> =
        MemberFinderBlockTable
            .selectAll()
            .where { MemberFinderBlockTable.blockerMemberId eq blockerMemberId }
            .orderBy(MemberFinderBlockTable.createdAt, SortOrder.DESC)
            .limit(limit)
            .map {
                MemberFinderBlockRow(
                    id = it[MemberFinderBlockTable.id],
                    blockerMemberId = it[MemberFinderBlockTable.blockerMemberId],
                    blockedMemberId = it[MemberFinderBlockTable.blockedMemberId],
                    createdAt = it[MemberFinderBlockTable.createdAt],
                )
            }

    /** Everybody [memberId] has blocked or is blocked by. */
    fun blockedEitherWay(memberId: Uuid): Set<Uuid> =
        MemberFinderBlockTable
            .selectAll()
            .where { (MemberFinderBlockTable.blockerMemberId eq memberId) or (MemberFinderBlockTable.blockedMemberId eq memberId) }
            .map {
                if (it[MemberFinderBlockTable.blockerMemberId] == memberId) {
                    it[MemberFinderBlockTable.blockedMemberId]
                } else {
                    it[MemberFinderBlockTable.blockerMemberId]
                }
            }.toSet()
}
