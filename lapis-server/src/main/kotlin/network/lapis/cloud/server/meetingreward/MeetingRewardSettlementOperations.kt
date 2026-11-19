package network.lapis.cloud.server.meetingreward

import io.github.oshai.kotlinlogging.KotlinLogging
import network.lapis.cloud.server.db.DbClock
import network.lapis.cloud.server.db.generated.CommitteeTable
import network.lapis.cloud.server.db.generated.MeetingRewardLineTable
import network.lapis.cloud.server.db.generated.MeetingRewardPolicyTable
import network.lapis.cloud.server.db.generated.MeetingRewardSettlementTable
import network.lapis.cloud.server.db.generated.MeetingTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.server.db.isUniqueViolation
import network.lapis.cloud.server.db.withSavepoint
import network.lapis.cloud.server.security.CurrentMember
import network.lapis.cloud.shared.domain.MeetingRewardLineDto
import network.lapis.cloud.shared.domain.MeetingRewardOwnLineDto
import network.lapis.cloud.shared.domain.MeetingRewardSettlementStatus
import network.lapis.cloud.shared.domain.MeetingRewardSettlementViewDto
import network.lapis.cloud.shared.domain.MeetingRewardUnavailableReason
import network.lapis.cloud.shared.rpc.ConflictException
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.selectAll
import java.math.BigDecimal
import kotlin.uuid.Uuid

private val logger = KotlinLogging.logger {}

/** The fixed text of an unavailable settlement (never a name, never an amount). */
internal fun unavailableText(reason: MeetingRewardUnavailableReason): String =
    when (reason) {
        MeetingRewardUnavailableReason.FEATURE_DISABLED -> FEATURE_DISABLED
        MeetingRewardUnavailableReason.COMMITTEE_NOT_ENABLED -> COMMITTEE_NOT_ENABLED
        MeetingRewardUnavailableReason.MEETING_NOT_HELD -> MEETING_NOT_HELD
        MeetingRewardUnavailableReason.PROTOCOL_NOT_CONFIRMED -> PROTOCOL_NOT_CONFIRMED
        MeetingRewardUnavailableReason.ATTENDANCE_CHANGED_AFTER_PROTOCOL -> ATTENDANCE_CHANGED_AFTER_PROTOCOL
    }

/**
 * V1.9.316 -- the preview, the preparation and the discarding of a settlement. The release is `MeetingRewardPayout` (the only ledger writer).
 * Every writing method locks the policy row first, then the settlement row (see the lock order in `MeetingRewardPayout`), and writes NO ledger row.
 * The caller records the audit entry as the last write. Must run inside an open `transaction {}`.
 */
internal object MeetingRewardSettlementOperations {
    /** What a preparation did; the caller audits it. */
    class Prepared(
        val settlementId: Uuid,
        val policyChangeId: Uuid,
        val protocolVersionId: Uuid,
        val previewHash: String,
        val lineCount: Int,
        val paidCount: Int,
        val total: BigDecimal,
        val preparerIsBeneficiary: Boolean,
    )

    /** What a discarding did; the caller audits it. */
    class Discarded(
        val settlementId: Uuid,
        val policyChangeId: Uuid,
        val protocolVersionId: Uuid,
        val previewHash: String,
        val lineCount: Int,
    )

    /** Lines of a live computation (nothing is stored): the preview of a meeting without a settlement row. */
    private class Live(
        val facts: MeetingRewardFacts,
        val policy: MeetingRewardPolicyValues,
        val lines: List<ComputedLine>,
        val changeId: Uuid,
    ) {
        val hash: String get() =
            previewHash(
                meetingId = facts.meetingId,
                policyChangeId = changeId,
                protocolVersionId = facts.protocolVersionId!!,
                lines = lines,
            )
    }

    private fun live(
        meetingId: Uuid,
        policyRow: ResultRow,
    ): Pair<MeetingRewardFacts, Live?> {
        val policy = MeetingRewardStore.valuesOf(policyRow)
        val facts = MeetingRewardFactsReader.load(meetingId = meetingId, policy = policy)
        val changeId = policyRow[MeetingRewardPolicyTable.lastChangeId]
        if (facts.unavailable != null || changeId == null || facts.protocolVersionId == null) return facts to null
        val ids = facts.attendance.map { it.memberId }
        val lines =
            computeLines(
                attendance = facts.attendance,
                year = facts.year,
                rolesByMember = facts.rolesByMember,
                statusByMember = facts.statusByMember,
                alreadyPaidThisYear =
                    MeetingRewardStore.paidSumByMemberForYear(
                        memberIds = ids,
                        year = facts.year,
                        excludingMeetingId = meetingId,
                    ),
                alreadySettled = MeetingRewardStore.releasedMemberIds(meetingId),
                policy = policy,
            )
        return facts to Live(facts = facts, policy = policy, lines = lines, changeId = changeId)
    }

    fun view(
        current: CurrentMember,
        meetingId: Uuid,
    ): MeetingRewardSettlementViewDto {
        val policyRow = MeetingRewardStore.policyRow()
        val settlement = MeetingRewardStore.settlement(meetingId)
        if (settlement != null) return storedView(current = current, settlement = settlement)
        val (facts, live) = live(meetingId = meetingId, policyRow = policyRow)
        if (live == null) {
            return emptyView(
                meetingId = meetingId,
                unavailable = facts.unavailable ?: MeetingRewardUnavailableReason.FEATURE_DISABLED,
                isAdmin = current.isAdmin,
            )
        }
        val total = settlementTotal(live.lines)
        return MeetingRewardSettlementViewDto(
            meetingId = meetingId.toString(),
            unavailable = null,
            status = null,
            preparedByName = null,
            preparedAt = null,
            releasedByName = null,
            releasedAt = null,
            previewHash = live.hash,
            lines = lineDtos(live.lines),
            total = total,
            callerIsBeneficiary = live.lines.any { it.memberId == current.memberId && it.amount.signum() > 0 },
            canPrepare = current.isAdmin,
            canRelease = false,
            canDiscard = false,
            blockedReason = settlementBlockedReason(isAdmin = current.isAdmin, status = null, callerIsPreparer = false),
        )
    }

    private fun emptyView(
        meetingId: Uuid,
        unavailable: MeetingRewardUnavailableReason,
        isAdmin: Boolean,
    ) = MeetingRewardSettlementViewDto(
        meetingId = meetingId.toString(),
        unavailable = unavailable,
        status = null,
        preparedByName = null,
        preparedAt = null,
        releasedByName = null,
        releasedAt = null,
        previewHash = null,
        lines = emptyList(),
        total = BigDecimal("0.00"),
        callerIsBeneficiary = false,
        canPrepare = false,
        canRelease = false,
        canDiscard = false,
        blockedReason = settlementBlockedReason(isAdmin = isAdmin, status = null, callerIsPreparer = false),
    )

    private fun storedView(
        current: CurrentMember,
        settlement: ResultRow,
    ): MeetingRewardSettlementViewDto {
        val rows = MeetingRewardStore.lines(settlement[MeetingRewardSettlementTable.id])
        val status = settlement[MeetingRewardSettlementTable.status]
        val callerIsPreparer = settlement[MeetingRewardSettlementTable.preparedBy] == current.memberId
        val names =
            nameMap(
                rows.map { it[MeetingRewardLineTable.memberId] } +
                    listOfNotNull(settlement[MeetingRewardSettlementTable.preparedBy], settlement[MeetingRewardSettlementTable.releasedBy]),
            )
        val prepared = status == MeetingRewardSettlementStatus.PREPARED
        val releaseBlocked = releaseBlockedReason(isAdmin = current.isAdmin, status = status, callerIsPreparer = callerIsPreparer)
        return MeetingRewardSettlementViewDto(
            meetingId = settlement[MeetingRewardSettlementTable.meetingId].toString(),
            unavailable = null,
            status = status,
            preparedByName = names[settlement[MeetingRewardSettlementTable.preparedBy]],
            preparedAt = settlement[MeetingRewardSettlementTable.preparedAt],
            releasedByName = settlement[MeetingRewardSettlementTable.releasedBy]?.let { names[it] },
            releasedAt = settlement[MeetingRewardSettlementTable.releasedAt],
            previewHash = settlement[MeetingRewardSettlementTable.previewHash],
            lines =
                rows.map {
                    MeetingRewardLineDto(
                        memberId = it[MeetingRewardLineTable.memberId].toString(),
                        displayName = names[it[MeetingRewardLineTable.memberId]].orEmpty(),
                        attendanceStatus = it[MeetingRewardLineTable.attendanceStatus],
                        functionRole = it[MeetingRewardLineTable.functionRole],
                        baseAmount = it[MeetingRewardLineTable.baseAmount],
                        bonusAmount = it[MeetingRewardLineTable.bonusAmount],
                        amount = it[MeetingRewardLineTable.amount],
                        reduced = it[MeetingRewardLineTable.reduced],
                        reason = it[MeetingRewardLineTable.reason],
                    )
                },
            total = settlement[MeetingRewardSettlementTable.totalAmount],
            callerIsBeneficiary =
                rows.any {
                    it[MeetingRewardLineTable.memberId] == current.memberId &&
                        it[MeetingRewardLineTable.amount].signum() > 0
                },
            canPrepare = false,
            canRelease = releaseBlocked == null,
            canDiscard = current.isAdmin && prepared,
            blockedReason =
                if (prepared) {
                    releaseBlocked
                } else {
                    settlementBlockedReason(
                        isAdmin = current.isAdmin,
                        status = status,
                        callerIsPreparer = callerIsPreparer,
                    )
                },
        )
    }

    fun prepare(
        current: CurrentMember,
        meetingId: Uuid,
        expectedHash: String,
    ): Prepared {
        val policyRow = MeetingRewardStore.lockPolicy()
        val existing = MeetingRewardStore.lockSettlement(meetingId)
        if (existing != null) {
            throw ConflictException(
                if (existing[MeetingRewardSettlementTable.status] ==
                    MeetingRewardSettlementStatus.RELEASED
                ) {
                    ALREADY_RELEASED
                } else {
                    NOT_AVAILABLE
                },
            )
        }
        val (facts, live) = live(meetingId = meetingId, policyRow = policyRow)
        facts.unavailable?.let { throw ConflictException(unavailableText(it)) }
        val computation = live ?: throw ConflictException(FEATURE_DISABLED)
        if (computation.hash != expectedHash) throw ConflictException(PREVIEW_STALE)
        val total = settlementTotal(computation.lines)
        requireMeetingCap(total = total, cap = computation.policy.capPerMeeting)

        val id = Uuid.random()
        val protocolVersionId = facts.protocolVersionId!!
        try {
            // A UNIQUE (meeting_id) violation can only mean that somebody else prepared first; the savepoint keeps PostgreSQL's transaction alive.
            withSavepoint(name = "meeting_reward_settlement_insert") {
                MeetingRewardStore.insertSettlement(
                    id = id,
                    meetingId = meetingId,
                    preparedBy = current.memberId,
                    preparedAt = DbClock.nowLocalDateTime(),
                    previewHash = computation.hash,
                    policyChangeId = computation.changeId,
                    protocolVersionId = protocolVersionId,
                    total = total,
                )
                MeetingRewardStore.insertLines(settlementId = id, meetingId = meetingId, lines = computation.lines)
            }
        } catch (e: Exception) {
            if (e.isUniqueViolation()) throw ConflictException(CONCURRENT_CHANGE)
            throw e
        }
        logger.info { "meeting reward settlement $id prepared" }
        return Prepared(
            settlementId = id,
            policyChangeId = computation.changeId,
            protocolVersionId = protocolVersionId,
            previewHash = computation.hash,
            lineCount = computation.lines.size,
            paidCount = computation.lines.count { it.amount.signum() > 0 },
            total = total,
            preparerIsBeneficiary = computation.lines.any { it.memberId == current.memberId && it.amount.signum() > 0 },
        )
    }

    /** Discards a PREPARED settlement (no money was booked). A RELEASED one is final. */
    fun discard(meetingId: Uuid): Discarded {
        MeetingRewardStore.lockPolicy()
        val settlement = MeetingRewardStore.lockSettlement(meetingId) ?: throw ConflictException(NOT_AVAILABLE)
        if (settlement[MeetingRewardSettlementTable.status] !=
            MeetingRewardSettlementStatus.PREPARED
        ) {
            throw ConflictException(ALREADY_RELEASED)
        }
        val id = settlement[MeetingRewardSettlementTable.id]
        val lineCount = MeetingRewardStore.lines(id).size
        if (MeetingRewardStore.deletePrepared(id) != 1) throw ConflictException(CONCURRENT_CHANGE)
        logger.info { "meeting reward settlement $id discarded" }
        return Discarded(
            settlementId = id,
            policyChangeId = settlement[MeetingRewardSettlementTable.policyChangeId],
            protocolVersionId = settlement[MeetingRewardSettlementTable.protocolVersionId],
            previewHash = settlement[MeetingRewardSettlementTable.previewHash],
            lineCount = lineCount,
        )
    }

    /** The caller's OWN recognitions (paid lines). The limit is capped; there is no way to ask for somebody else's. */
    fun ownLines(
        memberId: Uuid,
        limit: Int,
    ): List<MeetingRewardOwnLineDto> {
        val rows = MeetingRewardStore.ownLines(memberId = memberId, limit = limit.coerceIn(1, MAX_LIST_LIMIT))
        if (rows.isEmpty()) return emptyList()
        val meetingIds = rows.map { it[MeetingRewardLineTable.meetingId] }.toSet()
        val meetings = MeetingTable.selectAll().where { MeetingTable.id inList meetingIds }.associateBy { it[MeetingTable.id] }
        val committees =
            CommitteeTable
                .selectAll()
                .where { CommitteeTable.id inList meetings.values.map { it[MeetingTable.committeeId] }.toSet() }
                .associate { it[CommitteeTable.id] to it[CommitteeTable.name] }
        return rows.mapNotNull { row ->
            val meeting = meetings[row[MeetingRewardLineTable.meetingId]] ?: return@mapNotNull null
            MeetingRewardOwnLineDto(
                meetingId = meeting[MeetingTable.id].toString(),
                committeeName = committees[meeting[MeetingTable.committeeId]].orEmpty(),
                meetingDate = meeting[MeetingTable.scheduledAt].date,
                functionRole = row[MeetingRewardLineTable.functionRole],
                amount = row[MeetingRewardLineTable.amount],
                bonusAmount = row[MeetingRewardLineTable.bonusAmount],
                ledgerEntryId = row[MeetingRewardLineTable.ledgerEntryId].toString(),
            )
        }
    }

    private fun lineDtos(lines: List<ComputedLine>): List<MeetingRewardLineDto> {
        val names = nameMap(lines.map { it.memberId })
        return lines.map {
            MeetingRewardLineDto(
                memberId = it.memberId.toString(),
                displayName = names[it.memberId].orEmpty(),
                attendanceStatus = it.attendanceStatus,
                functionRole = it.functionRole,
                baseAmount = it.baseAmount,
                bonusAmount = it.bonusAmount,
                amount = it.amount,
                reduced = it.reduced,
                reason = it.reason,
            )
        }
    }

    private fun nameMap(ids: Collection<Uuid>): Map<Uuid, String> =
        if (ids.isEmpty()) {
            emptyMap()
        } else {
            MemberTable.selectAll().where { MemberTable.id inList ids.toSet() }.associate {
                it[MemberTable.id] to
                    it[MemberTable.displayName]
            }
        }
}
