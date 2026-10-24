package network.lapis.cloud.server.tender

import network.lapis.cloud.server.db.generated.CommitteeTable
import network.lapis.cloud.server.db.generated.MeetingTable
import network.lapis.cloud.server.db.generated.MotionTable
import network.lapis.cloud.server.db.generated.ResolutionTable
import network.lapis.cloud.server.db.generated.TenderTable
import network.lapis.cloud.shared.domain.MotionStatus
import network.lapis.cloud.shared.domain.TenderAwardMotionOptionDto
import network.lapis.cloud.shared.domain.TenderMotionOptionDto
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.selectAll

/*
 * V1.9.202 -- the two option lists behind the "new tender" form and the "set the award motion" step. Display-only: the writing paths
 * (`createTender`, `updateTenderDraft`, `registerAwardMotion`) take the lock and decide again with the very same functions of
 * [TenderMotionBinding], so a stale list can never create a binding the server would refuse. Orders are total (`submitted_at DESC, id`) so
 * PostgreSQL and H2 return the same page; resolutions, meetings and committees are loaded in bulk (no query per motion). Must run inside an
 * open `transaction {}`; never writes, never locks, never logs.
 */
internal object TenderOptions {
    private val OPEN_BINDING_CANDIDATES: Set<MotionStatus> = TenderMotionBinding.AWARD_MOTION_OPEN_STATUSES

    /** Motions that can be bound to a NEW tender: resolved ones first, then the ones that are still open (shown disabled with the reason). */
    fun bindingMotionOptions(limit: Int): List<TenderMotionOptionDto> {
        val resolved = candidates(statuses = setOf(MotionStatus.RESOLVED), limit = limit)
        val open = if (resolved.size < limit) candidates(statuses = OPEN_BINDING_CANDIDATES, limit = limit - resolved.size) else emptyList()
        val motions = resolved + open
        if (motions.isEmpty()) return emptyList()
        val resolutions =
            ResolutionTable
                .selectAll()
                .where { ResolutionTable.id inList motions.mapNotNull { it[MotionTable.resolutionId] }.distinct() }
                .associateBy { it[ResolutionTable.id] }
        val meetingCommittees =
            MeetingTable
                .selectAll()
                .where { MeetingTable.id inList resolutions.values.map { it[ResolutionTable.meetingId] }.distinct() }
                .associate { it[MeetingTable.id] to it[MeetingTable.committeeId] }
        val committeeNames =
            CommitteeTable
                .selectAll()
                .where { CommitteeTable.id inList motions.map { it[MotionTable.targetCommitteeId] }.distinct() }
                .associate { it[CommitteeTable.id] to it[CommitteeTable.name] }
        return motions.map { motion ->
            val reason =
                TenderMotionBinding.bindingIneligibilityOf(
                    motionRow = motion,
                    resolution = motion[MotionTable.resolutionId]?.let { resolutions[it] },
                    committeeOfMeeting = { meetingCommittees[it] },
                )
            TenderMotionOptionDto(
                motionId = motion[MotionTable.id].toString(),
                title = motion[MotionTable.title],
                committeeName = committeeNames[motion[MotionTable.targetCommitteeId]].orEmpty(),
                selectable = reason == null,
                reason = reason,
            )
        }
    }

    /** Candidates for the award motion: the motions of the committee of the binding motion, each with the reason if unusable. */
    fun awardMotionOptions(
        tenderRow: ResultRow,
        limit: Int,
    ): List<TenderAwardMotionOptionDto> {
        val bindingId = tenderRow[TenderTable.bindingMotionId]
        val binding = MotionTable.selectAll().where { MotionTable.id eq bindingId }.singleOrNull() ?: return emptyList()
        return MotionTable
            .selectAll()
            .where { MotionTable.targetCommitteeId eq binding[MotionTable.targetCommitteeId] }
            .orderBy(MotionTable.submittedAt to SortOrder.DESC, MotionTable.id to SortOrder.ASC)
            .limit(limit)
            .map { motion ->
                val reason =
                    TenderMotionBinding.awardIneligibilityWithDecisionPath(
                        motionRow = motion,
                        tenderRow = tenderRow,
                        binding = binding,
                    )
                TenderAwardMotionOptionDto(
                    motionId = motion[MotionTable.id].toString(),
                    title = motion[MotionTable.title],
                    selectable = reason == null,
                    reason = reason,
                )
            }
    }

    private fun candidates(
        statuses: Set<MotionStatus>,
        limit: Int,
    ): List<ResultRow> =
        MotionTable
            .selectAll()
            .where { MotionTable.status inList statuses }
            .orderBy(MotionTable.submittedAt to SortOrder.DESC, MotionTable.id to SortOrder.ASC)
            .limit(limit)
            .toList()
}
