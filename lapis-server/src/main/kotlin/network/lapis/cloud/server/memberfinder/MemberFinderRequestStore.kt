package network.lapis.cloud.server.memberfinder

import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.server.db.generated.MemberFinderRequestTable
import network.lapis.cloud.shared.domain.MemberFinderOutgoingState
import network.lapis.cloud.shared.domain.MemberFinderPurpose
import network.lapis.cloud.shared.domain.MemberFinderRules
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.isNotNull
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import kotlin.uuid.Uuid

/** One stored request. [toMemberId] is `null` for a phantom request and after the counterpart link was detached. */
internal data class MemberFinderRequestRow(
    val id: Uuid,
    val fromMemberId: Uuid,
    val toMemberId: Uuid?,
    val purpose: MemberFinderPurpose,
    val note: String?,
    val status: MemberFinderRequestStatus,
    val createdAt: LocalDateTime,
    val expiresAt: LocalDateTime,
    val decidedAt: LocalDateTime?,
    /** When the sender withdrew it. Evaluated first by [MemberFinderRequestStore.outgoingStateOf]; the stored [status] keeps the addressee's decision. */
    val senderWithdrawnAt: LocalDateTime? = null,
) {
    val senderWithdrawn: Boolean get() = senderWithdrawnAt != null
}

/**
 * V1.9.144 -- reads and writes `member_finder_request`. **The sender's view is derived, never stored** ([outgoingStateOf]): an open, a declined, a
 * phantom and an expired-early request all read PENDING until `expires_at`, so neither the answer to `sendRequest`, nor the list, nor the limit of
 * pending requests, nor the export tells a sender what happened on the other side (decision E6). Request ids are random v4 UUIDs ([Uuid.random]),
 * never v7: no time information in an id. Every writer holds the global finder lock. Must run inside a `transaction {}`.
 */
internal object MemberFinderRequestStore {
    /** The statuses a sender still sees as PENDING (while `now < expires_at`). */
    private val PENDING_LOOKING =
        listOf(MemberFinderRequestStatus.OPEN, MemberFinderRequestStatus.DECLINED, MemberFinderRequestStatus.EXPIRED)

    /** The ONLY mapping from stored state to what a sender sees. */
    fun outgoingStateOf(
        row: MemberFinderRequestRow,
        now: LocalDateTime,
    ): MemberFinderOutgoingState =
        when {
            // A withdrawal looks the same whatever the stored status is (declined, expired, open, phantom).
            row.senderWithdrawn -> MemberFinderOutgoingState.WITHDRAWN
            row.status == MemberFinderRequestStatus.ACCEPTED -> MemberFinderOutgoingState.CONNECTED
            row.status == MemberFinderRequestStatus.WITHDRAWN -> MemberFinderOutgoingState.WITHDRAWN
            now < row.expiresAt -> MemberFinderOutgoingState.PENDING
            else -> MemberFinderOutgoingState.ENDED
        }

    fun find(id: Uuid): MemberFinderRequestRow? =
        MemberFinderRequestTable
            .selectAll()
            .where { MemberFinderRequestTable.id eq id }
            .singleOrNull()
            ?.toRow()

    fun insert(
        fromMemberId: Uuid,
        toMemberId: Uuid?,
        purpose: MemberFinderPurpose,
        note: String?,
        now: LocalDateTime,
    ): MemberFinderRequestRow {
        val id = Uuid.random()
        val expiresAt = now.plusDays(MemberFinderRules.OPEN_DAYS)
        MemberFinderRequestTable.insert {
            it[MemberFinderRequestTable.id] = id
            it[MemberFinderRequestTable.fromMemberId] = fromMemberId
            it[MemberFinderRequestTable.toMemberId] = toMemberId
            it[MemberFinderRequestTable.purpose] = purpose
            it[MemberFinderRequestTable.note] = note
            it[status] = MemberFinderRequestStatus.OPEN
            it[createdAt] = now
            it[MemberFinderRequestTable.expiresAt] = expiresAt
            it[decidedAt] = null
        }
        return MemberFinderRequestRow(
            id = id,
            fromMemberId = fromMemberId,
            toMemberId = toMemberId,
            purpose = purpose,
            note = note,
            status = MemberFinderRequestStatus.OPEN,
            createdAt = now,
            expiresAt = expiresAt,
            decidedAt = null,
        )
    }

    fun setStatus(
        id: Uuid,
        status: MemberFinderRequestStatus,
        decidedAt: LocalDateTime,
    ): Int =
        MemberFinderRequestTable.update({ MemberFinderRequestTable.id eq id }) {
            it[MemberFinderRequestTable.status] = status
            it[MemberFinderRequestTable.decidedAt] = decidedAt
        }

    /**
     * Accepts an OPEN request addressed to [toMemberId]. The UPDATE is conditional on status and counterpart: a concurrent detach (lifecycle hooks run
     * without the finder lock) that cleared `to_member_id` must not be re-matched into an ACCEPTED row without a counterpart. Returns the rows changed.
     */
    fun acceptOpen(
        id: Uuid,
        toMemberId: Uuid,
        decidedAt: LocalDateTime,
    ): Int =
        MemberFinderRequestTable.update({
            (MemberFinderRequestTable.id eq id) and
                (MemberFinderRequestTable.status eq MemberFinderRequestStatus.OPEN) and
                (MemberFinderRequestTable.toMemberId eq toMemberId)
        }) {
            it[status] = MemberFinderRequestStatus.ACCEPTED
            it[MemberFinderRequestTable.decidedAt] = decidedAt
        }

    /**
     * The sender withdraws. An OPEN request becomes WITHDRAWN (as before); a stored DECLINED / EXPIRED one keeps the addressee's decision and only gets
     * the sender flag, so the sender's view is identical in every case.
     */
    fun withdraw(
        row: MemberFinderRequestRow,
        now: LocalDateTime,
    ) {
        MemberFinderRequestTable.update({ MemberFinderRequestTable.id eq row.id }) {
            it[senderWithdrawnAt] = now
            if (row.status == MemberFinderRequestStatus.OPEN) {
                it[status] = MemberFinderRequestStatus.WITHDRAWN
                it[decidedAt] = now
            }
        }
    }

    fun delete(id: Uuid) {
        MemberFinderRequestTable.deleteWhere { MemberFinderRequestTable.id eq id }
    }

    /** What the pending limit counts: every own request a sender still sees as PENDING -- phantom and declined ones count like open ones. */
    fun countPending(
        fromMemberId: Uuid,
        now: LocalDateTime,
    ): Int =
        MemberFinderRequestTable
            .selectAll()
            .where {
                (MemberFinderRequestTable.fromMemberId eq fromMemberId) and
                    (MemberFinderRequestTable.status inList PENDING_LOOKING) and
                    MemberFinderRequestTable.senderWithdrawnAt.isNull() and
                    (MemberFinderRequestTable.expiresAt greater now)
            }.count()
            .toInt()

    fun outgoing(
        fromMemberId: Uuid,
        limit: Int,
    ): List<MemberFinderRequestRow> =
        MemberFinderRequestTable
            .selectAll()
            .where { MemberFinderRequestTable.fromMemberId eq fromMemberId }
            .orderBy(MemberFinderRequestTable.createdAt, SortOrder.DESC)
            .limit(limit)
            .map { it.toRow() }

    /** Open, not yet expired requests addressed to [toMemberId]. */
    fun incomingOpen(
        toMemberId: Uuid,
        now: LocalDateTime,
        limit: Int,
    ): List<MemberFinderRequestRow> =
        MemberFinderRequestTable
            .selectAll()
            .where {
                (MemberFinderRequestTable.toMemberId eq toMemberId) and
                    (MemberFinderRequestTable.status eq MemberFinderRequestStatus.OPEN) and
                    (MemberFinderRequestTable.expiresAt greater now)
            }.orderBy(MemberFinderRequestTable.createdAt, SortOrder.DESC)
            .limit(limit)
            .map { it.toRow() }

    /** ACCEPTED requests the member takes part in on either side. */
    fun connections(
        memberId: Uuid,
        limit: Int,
    ): List<MemberFinderRequestRow> =
        MemberFinderRequestTable
            .selectAll()
            .where {
                (MemberFinderRequestTable.status eq MemberFinderRequestStatus.ACCEPTED) and
                    ((MemberFinderRequestTable.fromMemberId eq memberId) or (MemberFinderRequestTable.toMemberId eq memberId))
            }.orderBy(MemberFinderRequestTable.decidedAt, SortOrder.DESC)
            .limit(limit)
            .map { it.toRow() }

    /** Number of open, unexpired inbox requests per addressee among [toMemberIds]. */
    fun openInboxCounts(
        toMemberIds: Collection<Uuid>,
        now: LocalDateTime,
    ): Map<Uuid, Int> {
        if (toMemberIds.isEmpty()) return emptyMap()
        return toMemberIds
            .distinct()
            .chunked(500)
            .flatMap { chunk ->
                MemberFinderRequestTable
                    .selectAll()
                    .where {
                        (MemberFinderRequestTable.toMemberId inList chunk) and
                            (MemberFinderRequestTable.status eq MemberFinderRequestStatus.OPEN) and
                            (MemberFinderRequestTable.expiresAt greater now)
                    }.mapNotNull { it[MemberFinderRequestTable.toMemberId] }
            }.groupingBy { it }
            .eachCount()
    }

    /**
     * The other members [memberId] must not be paired with right now, in BOTH directions: an open request, a connection, and -- for 7 days after
     * the decision -- a declined, expired or withdrawn pair (only while the counterpart link still exists).
     */
    fun pairExclusions(
        memberId: Uuid,
        now: LocalDateTime,
    ): Set<Uuid> {
        val cutoff = now.minusDays(MemberFinderRules.CLOSED_RETENTION_DAYS)
        return MemberFinderRequestTable
            .selectAll()
            .where {
                ((MemberFinderRequestTable.fromMemberId eq memberId) and MemberFinderRequestTable.toMemberId.isNotNull()) or
                    (MemberFinderRequestTable.toMemberId eq memberId)
            }.mapNotNull { row ->
                val request = row.toRow()
                val other =
                    if (request.fromMemberId == memberId) {
                        request.toMemberId
                    } else {
                        request.fromMemberId
                    } ?: return@mapNotNull null
                val excluded =
                    when (request.status) {
                        MemberFinderRequestStatus.OPEN, MemberFinderRequestStatus.ACCEPTED -> true
                        else -> request.decidedAt?.let { it > cutoff } ?: true
                    }
                if (excluded) other else null
            }.toSet()
    }

    private fun ResultRow.toRow(): MemberFinderRequestRow =
        MemberFinderRequestRow(
            id = this[MemberFinderRequestTable.id],
            fromMemberId = this[MemberFinderRequestTable.fromMemberId],
            toMemberId = this[MemberFinderRequestTable.toMemberId],
            purpose = this[MemberFinderRequestTable.purpose],
            note = this[MemberFinderRequestTable.note],
            status = this[MemberFinderRequestTable.status],
            createdAt = this[MemberFinderRequestTable.createdAt],
            expiresAt = this[MemberFinderRequestTable.expiresAt],
            decidedAt = this[MemberFinderRequestTable.decidedAt],
            senderWithdrawnAt = this[MemberFinderRequestTable.senderWithdrawnAt],
        )
}
