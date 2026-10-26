package network.lapis.cloud.server.dashboard.sources

import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.plus
import network.lapis.cloud.server.dashboard.DashboardSignalSource
import network.lapis.cloud.server.dashboard.SignalContext
import network.lapis.cloud.server.dashboard.SignalGates
import network.lapis.cloud.server.dashboard.signal
import network.lapis.cloud.server.db.generated.CommitteeMembershipTable
import network.lapis.cloud.server.db.generated.CommitteeTable
import network.lapis.cloud.server.db.generated.MeetingTable
import network.lapis.cloud.server.rpc.isCommitteeEligible
import network.lapis.cloud.shared.domain.CommitteeMembershipMode
import network.lapis.cloud.shared.domain.CommitteeType
import network.lapis.cloud.shared.domain.DashboardModuleKeys
import network.lapis.cloud.shared.domain.DashboardPriority
import network.lapis.cloud.shared.domain.DashboardReasonKeys
import network.lapis.cloud.shared.domain.DashboardSignalDto
import network.lapis.cloud.shared.domain.MeetingStatus
import network.lapis.cloud.shared.rpc.ConflictException
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greaterEq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.lessEq
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import kotlin.time.Duration.Companion.days

/**
 * V1.9.224 -- "a planned meeting of one of my committees within seven days". `meeting.scheduled_at` is class B (organization wall-clock).
 * Which committees count is decided by the one eligibility function the voting uses ([isCommitteeEligible]): the candidates are the
 * committees
 * where I hold a seat plus the general assembly and the rule committees (tiny table); each candidate meeting is then confirmed by
 * [isCommitteeEligible], so a seat that ended before the meeting day does not count.
 */
internal object MeetingSignals : DashboardSignalSource {
    override val moduleKey: String = DashboardModuleKeys.MEETING

    private const val MAX_MEETINGS = 30

    override fun collect(ctx: SignalContext): List<DashboardSignalDto> {
        if (!SignalGates.showsSelfGovernance(ctx.status)) return emptyList()
        val mySeats =
            CommitteeMembershipTable
                .select(CommitteeMembershipTable.committeeId)
                .where {
                    (CommitteeMembershipTable.memberId eq ctx.memberId) and
                        (CommitteeMembershipTable.since lessEq ctx.today.plus(WINDOW_DAYS, DateTimeUnit.DAY)) and
                        (CommitteeMembershipTable.until.isNull() or (CommitteeMembershipTable.until greaterEq ctx.today))
                }.map { it[CommitteeMembershipTable.committeeId] }
        val derived =
            CommitteeTable
                .select(CommitteeTable.id)
                .where {
                    (CommitteeTable.type eq CommitteeType.GENERAL_ASSEMBLY) or
                        (CommitteeTable.membershipMode eq CommitteeMembershipMode.RULE)
                }.map { it[CommitteeTable.id] }
        val candidates = (mySeats + derived).distinct()
        if (candidates.isEmpty()) return emptyList()
        val committees = CommitteeTable.selectAll().where { CommitteeTable.id inList candidates }.associateBy { it[CommitteeTable.id] }
        val meetings =
            MeetingTable
                .selectAll()
                .where {
                    (MeetingTable.committeeId inList candidates) and
                        (MeetingTable.status eq MeetingStatus.PLANNED) and
                        (MeetingTable.scheduledAt greaterEq ctx.nowWall) and
                        (MeetingTable.scheduledAt lessEq ctx.wallPlus(WINDOW_DAYS.days))
                }.orderBy(MeetingTable.scheduledAt to SortOrder.ASC, MeetingTable.id to SortOrder.ASC)
                .limit(MAX_MEETINGS)
                .toList()
        val mine =
            meetings.filter { meeting ->
                val committee = committees[meeting[MeetingTable.committeeId]] ?: return@filter false
                try {
                    isCommitteeEligible(
                        committeeRow = committee,
                        scheduledDate = meeting[MeetingTable.scheduledAt].date,
                        memberId = ctx.memberId,
                    )
                } catch (e: ConflictException) {
                    // a rule committee whose stored rule no longer parses: that committee contributes nothing, the others still do
                    false
                }
            }
        val next = mine.firstOrNull()?.get(MeetingTable.scheduledAt)
        return listOfNotNull(
            signal(
                module = moduleKey,
                reason = DashboardReasonKeys.MEETING_UPCOMING,
                count = mine.size,
                priority = DashboardPriority.INFO,
                params = next?.let { mapOf("date" to it.date.toString()) } ?: emptyMap(),
                objectId = mine.firstOrNull()?.get(MeetingTable.id),
                deadlineWall = next,
            ),
        )
    }

    private const val WINDOW_DAYS = 7
}
