package network.lapis.cloud.server.meetingreward

import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.server.db.generated.MeetingRewardCommitteeTable
import network.lapis.cloud.server.db.generated.MeetingRewardLineTable
import network.lapis.cloud.server.db.generated.MeetingRewardPolicyChangeTable
import network.lapis.cloud.server.db.generated.MeetingRewardPolicyTable
import network.lapis.cloud.server.db.generated.MeetingRewardSettlementTable
import network.lapis.cloud.shared.domain.MeetingRewardPolicyChangeKind
import network.lapis.cloud.shared.domain.MeetingRewardPolicyChangeStatus
import network.lapis.cloud.shared.domain.MeetingRewardSettlementStatus
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.isNotNull
import org.jetbrains.exposed.v1.core.neq
import org.jetbrains.exposed.v1.core.sum
import org.jetbrains.exposed.v1.jdbc.batchInsert
import org.jetbrains.exposed.v1.jdbc.deleteAll
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import java.math.BigDecimal
import kotlin.uuid.Uuid

/** The fixed id of the one `meeting_reward_policy` row (seeded by V164; restore-aware, see `OrganizationRestoreService`). */
internal val MEETING_REWARD_POLICY_ID: Uuid = Uuid.parse("00000000-0000-0000-0000-0000000000f9")

/**
 * V1.9.316 -- THE ONLY reader and writer of the five `meeting_reward*` tables (`MeetingRewardAccessTripwireTest` pins this; the DSGVO contributor
 * `MeetingRewardPersonalData` is the one other file that may read them). Every function must run inside an open `transaction {}`; none opens one.
 *
 * Lock order of every writing path: **policy row -> settlement row -> member rows (`forMemberUpdate`, ascending id) -> ledger -> audit (last)**.
 * The policy row is locked FIRST by every path so that a policy confirmation and a release serialise against each other.
 */
internal object MeetingRewardStore {
    // ── the rules ─────────────────────────────────────────────────────────────────────────────────────

    /** The singleton policy row locked `FOR UPDATE` -- call FIRST. */
    fun lockPolicy(): ResultRow =
        MeetingRewardPolicyTable
            .selectAll()
            .where { MeetingRewardPolicyTable.id eq MEETING_REWARD_POLICY_ID }
            .forUpdate()
            .single()

    fun policyRow(): ResultRow =
        MeetingRewardPolicyTable.selectAll().where { MeetingRewardPolicyTable.id eq MEETING_REWARD_POLICY_ID }.single()

    fun committeeIds(): Set<Uuid> = MeetingRewardCommitteeTable.selectAll().map { it[MeetingRewardCommitteeTable.committeeId] }.toSet()

    fun valuesOf(row: ResultRow): MeetingRewardPolicyValues =
        MeetingRewardPolicyValues(
            enabled = row[MeetingRewardPolicyTable.enabled],
            attendanceAmount = row[MeetingRewardPolicyTable.attendanceAmount],
            bonusChair = row[MeetingRewardPolicyTable.bonusChair],
            bonusDeputyChair = row[MeetingRewardPolicyTable.bonusDeputyChair],
            bonusSecretary = row[MeetingRewardPolicyTable.bonusSecretary],
            bonusManagingDirector = row[MeetingRewardPolicyTable.bonusManagingDirector],
            capPerPersonPerYear = row[MeetingRewardPolicyTable.capPerPersonPerYear],
            capPerMeeting = row[MeetingRewardPolicyTable.capPerMeeting],
            committeeIds = committeeIds(),
        )

    fun changeRow(
        id: Uuid,
        forUpdate: Boolean = false,
    ): ResultRow? {
        val query = MeetingRewardPolicyChangeTable.selectAll().where { MeetingRewardPolicyChangeTable.id eq id }
        return (if (forUpdate) query.forUpdate() else query).singleOrNull()
    }

    fun oldValuesOf(row: ResultRow): MeetingRewardPolicyValues =
        MeetingRewardPolicyValues(
            enabled = row[MeetingRewardPolicyChangeTable.oldEnabled],
            attendanceAmount = row[MeetingRewardPolicyChangeTable.oldAttendanceAmount],
            bonusChair = row[MeetingRewardPolicyChangeTable.oldBonusChair],
            bonusDeputyChair = row[MeetingRewardPolicyChangeTable.oldBonusDeputyChair],
            bonusSecretary = row[MeetingRewardPolicyChangeTable.oldBonusSecretary],
            bonusManagingDirector = row[MeetingRewardPolicyChangeTable.oldBonusManagingDirector],
            capPerPersonPerYear = row[MeetingRewardPolicyChangeTable.oldCapPerPersonPerYear],
            capPerMeeting = row[MeetingRewardPolicyChangeTable.oldCapPerMeeting],
            committeeIds = parseIds(row[MeetingRewardPolicyChangeTable.oldCommitteeIds]),
        )

    fun newValuesOf(row: ResultRow): MeetingRewardPolicyValues =
        MeetingRewardPolicyValues(
            enabled = row[MeetingRewardPolicyChangeTable.newEnabled],
            attendanceAmount = row[MeetingRewardPolicyChangeTable.newAttendanceAmount],
            bonusChair = row[MeetingRewardPolicyChangeTable.newBonusChair],
            bonusDeputyChair = row[MeetingRewardPolicyChangeTable.newBonusDeputyChair],
            bonusSecretary = row[MeetingRewardPolicyChangeTable.newBonusSecretary],
            bonusManagingDirector = row[MeetingRewardPolicyChangeTable.newBonusManagingDirector],
            capPerPersonPerYear = row[MeetingRewardPolicyChangeTable.newCapPerPersonPerYear],
            capPerMeeting = row[MeetingRewardPolicyChangeTable.newCapPerMeeting],
            committeeIds = parseIds(row[MeetingRewardPolicyChangeTable.newCommitteeIds]),
        )

    /** The sorted, comma-separated snapshot form of a committee set (UUIDs only, so the column cannot overflow: 200 x 37 < 4000). */
    fun joinIds(ids: Set<Uuid>): String = ids.map { it.toString() }.sorted().joinToString(",")

    private fun parseIds(raw: String): Set<Uuid> = if (raw.isBlank()) emptySet() else raw.split(',').map { Uuid.parse(it) }.toSet()

    fun insertChange(
        id: Uuid,
        kind: MeetingRewardPolicyChangeKind,
        status: MeetingRewardPolicyChangeStatus,
        proposedBy: Uuid,
        proposedAt: LocalDateTime,
        decidedBy: Uuid?,
        decidedAt: LocalDateTime?,
        resolutionId: Uuid?,
        resolutionReference: String?,
        old: MeetingRewardPolicyValues,
        new: MeetingRewardPolicyValues,
    ) {
        MeetingRewardPolicyChangeTable.insert {
            it[MeetingRewardPolicyChangeTable.id] = id
            it[MeetingRewardPolicyChangeTable.kind] = kind
            it[MeetingRewardPolicyChangeTable.status] = status
            it[MeetingRewardPolicyChangeTable.proposedBy] = proposedBy
            it[MeetingRewardPolicyChangeTable.proposedAt] = proposedAt
            it[MeetingRewardPolicyChangeTable.decidedBy] = decidedBy
            it[MeetingRewardPolicyChangeTable.decidedAt] = decidedAt
            it[MeetingRewardPolicyChangeTable.resolutionId] = resolutionId
            it[MeetingRewardPolicyChangeTable.resolutionReference] = resolutionReference
            it[oldEnabled] = old.enabled
            it[oldAttendanceAmount] = old.attendanceAmount
            it[oldBonusChair] = old.bonusChair
            it[oldBonusDeputyChair] = old.bonusDeputyChair
            it[oldBonusSecretary] = old.bonusSecretary
            it[oldBonusManagingDirector] = old.bonusManagingDirector
            it[oldCapPerPersonPerYear] = old.capPerPersonPerYear
            it[oldCapPerMeeting] = old.capPerMeeting
            it[newEnabled] = new.enabled
            it[newAttendanceAmount] = new.attendanceAmount
            it[newBonusChair] = new.bonusChair
            it[newBonusDeputyChair] = new.bonusDeputyChair
            it[newBonusSecretary] = new.bonusSecretary
            it[newBonusManagingDirector] = new.bonusManagingDirector
            it[newCapPerPersonPerYear] = new.capPerPersonPerYear
            it[newCapPerMeeting] = new.capPerMeeting
            it[oldCommitteeIds] = joinIds(old.committeeIds)
            it[newCommitteeIds] = joinIds(new.committeeIds)
        }
    }

    /** PROPOSED -> CONFIRMED, conditional. Returns the number of changed rows (0 = somebody else was first). */
    fun markChangeConfirmed(
        id: Uuid,
        by: Uuid,
        at: LocalDateTime,
    ): Int =
        MeetingRewardPolicyChangeTable.update({
            (MeetingRewardPolicyChangeTable.id eq id) and
                (MeetingRewardPolicyChangeTable.status eq MeetingRewardPolicyChangeStatus.PROPOSED)
        }) {
            it[status] = MeetingRewardPolicyChangeStatus.CONFIRMED
            it[decidedBy] = by
            it[decidedAt] = at
        }

    /** PROPOSED -> WITHDRAWN, conditional. */
    fun markChangeWithdrawn(
        id: Uuid,
        by: Uuid,
        at: LocalDateTime,
    ): Int =
        MeetingRewardPolicyChangeTable.update({
            (MeetingRewardPolicyChangeTable.id eq id) and
                (MeetingRewardPolicyChangeTable.status eq MeetingRewardPolicyChangeStatus.PROPOSED)
        }) {
            it[status] = MeetingRewardPolicyChangeStatus.WITHDRAWN
            it[decidedBy] = by
            it[decidedAt] = at
        }

    /** Writes the figures of a CONFIRMED change into the singleton and points `last_change_id` at it. [resolutionId] `null` keeps the previous one. */
    fun applyPolicy(
        values: MeetingRewardPolicyValues,
        changeId: Uuid,
        resolutionId: Uuid?,
        resolutionReference: String?,
        keepResolution: Boolean,
        at: LocalDateTime,
    ) {
        MeetingRewardPolicyTable.update({ MeetingRewardPolicyTable.id eq MEETING_REWARD_POLICY_ID }) {
            it[enabled] = values.enabled
            it[attendanceAmount] = values.attendanceAmount
            it[bonusChair] = values.bonusChair
            it[bonusDeputyChair] = values.bonusDeputyChair
            it[bonusSecretary] = values.bonusSecretary
            it[bonusManagingDirector] = values.bonusManagingDirector
            it[capPerPersonPerYear] = values.capPerPersonPerYear
            it[capPerMeeting] = values.capPerMeeting
            if (!keepResolution) {
                it[MeetingRewardPolicyTable.resolutionId] = resolutionId
                it[MeetingRewardPolicyTable.resolutionReference] = resolutionReference
            }
            it[lastChangeId] = changeId
            it[updatedAt] = at
        }
    }

    fun setOpenChange(id: Uuid?) {
        MeetingRewardPolicyTable.update({ MeetingRewardPolicyTable.id eq MEETING_REWARD_POLICY_ID }) { it[openChangeId] = id }
    }

    /** Replaces the committee snapshot (a confirmed CHANGE). */
    fun replaceCommittees(
        ids: Set<Uuid>,
        changeId: Uuid,
        at: LocalDateTime,
    ) {
        MeetingRewardCommitteeTable.deleteAll()
        if (ids.isEmpty()) return
        MeetingRewardCommitteeTable.batchInsert(ids.sortedBy { it.toString() }) { committee ->
            this[MeetingRewardCommitteeTable.committeeId] = committee
            this[MeetingRewardCommitteeTable.enabledByChangeId] = changeId
            this[MeetingRewardCommitteeTable.enabledAt] = at
        }
    }

    fun history(limit: Int): List<ResultRow> =
        MeetingRewardPolicyChangeTable
            .selectAll()
            .orderBy(MeetingRewardPolicyChangeTable.proposedAt to SortOrder.DESC, MeetingRewardPolicyChangeTable.id to SortOrder.DESC)
            .limit(limit)
            .toList()

    // ── the settlement ───────────────────────────────────────────────────────────────────────────────

    fun settlement(meetingId: Uuid): ResultRow? =
        MeetingRewardSettlementTable.selectAll().where { MeetingRewardSettlementTable.meetingId eq meetingId }.singleOrNull()

    fun lockSettlement(meetingId: Uuid): ResultRow? =
        MeetingRewardSettlementTable
            .selectAll()
            .where { MeetingRewardSettlementTable.meetingId eq meetingId }
            .forUpdate()
            .singleOrNull()

    fun insertSettlement(
        id: Uuid,
        meetingId: Uuid,
        preparedBy: Uuid,
        preparedAt: LocalDateTime,
        previewHash: String,
        policyChangeId: Uuid,
        protocolVersionId: Uuid,
        total: BigDecimal,
    ) {
        MeetingRewardSettlementTable.insert {
            it[MeetingRewardSettlementTable.id] = id
            it[MeetingRewardSettlementTable.meetingId] = meetingId
            it[status] = MeetingRewardSettlementStatus.PREPARED
            it[MeetingRewardSettlementTable.preparedBy] = preparedBy
            it[MeetingRewardSettlementTable.preparedAt] = preparedAt
            it[releasedBy] = null
            it[releasedAt] = null
            it[MeetingRewardSettlementTable.previewHash] = previewHash
            it[MeetingRewardSettlementTable.policyChangeId] = policyChangeId
            it[MeetingRewardSettlementTable.protocolVersionId] = protocolVersionId
            it[totalAmount] = total
        }
    }

    fun insertLines(
        settlementId: Uuid,
        meetingId: Uuid,
        lines: List<ComputedLine>,
    ) {
        if (lines.isEmpty()) return
        MeetingRewardLineTable.batchInsert(lines) { line ->
            this[MeetingRewardLineTable.id] = Uuid.random()
            this[MeetingRewardLineTable.settlementId] = settlementId
            this[MeetingRewardLineTable.meetingId] = meetingId
            this[MeetingRewardLineTable.memberId] = line.memberId
            this[MeetingRewardLineTable.attendanceStatus] = line.attendanceStatus
            this[MeetingRewardLineTable.reason] = line.reason
            this[MeetingRewardLineTable.functionRole] = line.functionRole
            this[MeetingRewardLineTable.baseAmount] = line.baseAmount
            this[MeetingRewardLineTable.bonusAmount] = line.bonusAmount
            this[MeetingRewardLineTable.amount] = line.amount
            this[MeetingRewardLineTable.reduced] = line.reduced
            this[MeetingRewardLineTable.periodYear] = line.periodYear
            this[MeetingRewardLineTable.idempotencyKey] = "meeting-reward:$meetingId:${line.memberId}"
            this[MeetingRewardLineTable.ledgerEntryId] = null
        }
    }

    fun lines(settlementId: Uuid): List<ResultRow> =
        MeetingRewardLineTable
            .selectAll()
            .where { MeetingRewardLineTable.settlementId eq settlementId }
            .orderBy(MeetingRewardLineTable.memberId to SortOrder.ASC)
            .toList()

    /** Deletes a PREPARED settlement with its lines (lines first). Returns 1 when the settlement row was deleted. */
    fun deletePrepared(settlementId: Uuid): Int {
        MeetingRewardLineTable.deleteWhere { MeetingRewardLineTable.settlementId eq settlementId }
        return MeetingRewardSettlementTable.deleteWhere {
            (MeetingRewardSettlementTable.id eq settlementId) and
                (MeetingRewardSettlementTable.status eq MeetingRewardSettlementStatus.PREPARED)
        }
    }

    /** PREPARED -> RELEASED, conditional. Returns the number of changed rows (0 = somebody else was first). */
    fun markReleased(
        settlementId: Uuid,
        by: Uuid,
        at: LocalDateTime,
    ): Int =
        MeetingRewardSettlementTable.update({
            (MeetingRewardSettlementTable.id eq settlementId) and
                (MeetingRewardSettlementTable.status eq MeetingRewardSettlementStatus.PREPARED)
        }) {
            it[status] = MeetingRewardSettlementStatus.RELEASED
            it[releasedBy] = by
            it[releasedAt] = at
        }

    fun setLineLedgerEntry(
        lineId: Uuid,
        ledgerEntryId: Uuid,
    ) {
        MeetingRewardLineTable.update({ MeetingRewardLineTable.id eq lineId }) { it[MeetingRewardLineTable.ledgerEntryId] = ledgerEntryId }
    }

    /**
     * What each of [memberIds] got in [year] from OTHER meetings: the sum of the lines that reached the ledger (`ledger_entry_id` set = released).
     * Read AFTER the member rows are locked (see `MeetingRewardPayout`).
     */
    fun paidSumByMemberForYear(
        memberIds: Collection<Uuid>,
        year: Int,
        excludingMeetingId: Uuid,
    ): Map<Uuid, BigDecimal> {
        if (memberIds.isEmpty()) return emptyMap()
        val total = MeetingRewardLineTable.amount.sum()
        return MeetingRewardLineTable
            .select(MeetingRewardLineTable.memberId, total)
            .where {
                (MeetingRewardLineTable.memberId inList memberIds.toSet()) and
                    (MeetingRewardLineTable.periodYear eq year) and
                    MeetingRewardLineTable.ledgerEntryId.isNotNull() and
                    (MeetingRewardLineTable.meetingId neq excludingMeetingId)
            }.groupBy(MeetingRewardLineTable.memberId)
            .associate { it[MeetingRewardLineTable.memberId] to (it[total] ?: BigDecimal.ZERO) }
    }

    /** The members that already have a RELEASED line of [meetingId] (one more guard against a double recognition). */
    fun releasedMemberIds(meetingId: Uuid): Set<Uuid> =
        MeetingRewardLineTable
            .selectAll()
            .where { (MeetingRewardLineTable.meetingId eq meetingId) and MeetingRewardLineTable.ledgerEntryId.isNotNull() }
            .map { it[MeetingRewardLineTable.memberId] }
            .toSet()

    /** The caller's OWN paid lines, newest settlement first. There is deliberately no member parameter for anybody else. */
    fun ownLines(
        memberId: Uuid,
        limit: Int,
    ): List<ResultRow> =
        MeetingRewardLineTable
            .selectAll()
            .where { (MeetingRewardLineTable.memberId eq memberId) and MeetingRewardLineTable.ledgerEntryId.isNotNull() }
            .orderBy(MeetingRewardLineTable.periodYear to SortOrder.DESC, MeetingRewardLineTable.id to SortOrder.DESC)
            .limit(limit)
            .toList()
}
