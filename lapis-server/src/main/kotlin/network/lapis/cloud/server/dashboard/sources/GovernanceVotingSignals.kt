package network.lapis.cloud.server.dashboard.sources

import network.lapis.cloud.server.dashboard.DashboardSignalSource
import network.lapis.cloud.server.dashboard.SignalContext
import network.lapis.cloud.server.dashboard.SignalGates
import network.lapis.cloud.server.dashboard.signal
import network.lapis.cloud.server.db.generated.ElectionTable
import network.lapis.cloud.server.db.generated.PollTable
import network.lapis.cloud.server.db.generated.SystemicConsensusTable
import network.lapis.cloud.server.db.generated.VoteTable
import network.lapis.cloud.server.rpc.ElectionOwnParticipation
import network.lapis.cloud.server.rpc.PollOwnParticipation
import network.lapis.cloud.server.rpc.SystemicConsensusOwnParticipation
import network.lapis.cloud.server.rpc.VoteOwnParticipation
import network.lapis.cloud.server.rpc.effectiveOpenPredicate
import network.lapis.cloud.shared.domain.DashboardModuleKeys
import network.lapis.cloud.shared.domain.DashboardPriority
import network.lapis.cloud.shared.domain.DashboardReasonKeys
import network.lapis.cloud.shared.domain.DashboardSignalDto
import network.lapis.cloud.shared.domain.ElectionStatus
import network.lapis.cloud.shared.domain.SystemicConsensusStatus
import network.lapis.cloud.shared.domain.VoteStatus
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.isNotNull
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.jdbc.selectAll
import kotlin.time.Duration.Companion.hours

private const val MAX_OPEN_VOTINGS = 50

/**
 * V1.9.224 -- "a running election in which I may vote and have not". None of these modules has a scheduled deadline (only the actual
 * moments `voting_closed_at` and the like exist), so the priority is always [DashboardPriority.ACTION] and there is no date. Whether I
 * voted
 * is read ONLY through [ElectionOwnParticipation], which keeps the secret-ballot tables apart: `election_ballot` is never queried for
 * a secret
 * election.
 */
internal object ElectionSignals : DashboardSignalSource {
    override val moduleKey: String = DashboardModuleKeys.ELECTION

    override fun collect(ctx: SignalContext): List<DashboardSignalDto> {
        if (!SignalGates.showsSelfGovernance(ctx.status)) return emptyList()
        val running =
            ElectionTable
                .selectAll()
                .where {
                    (ElectionTable.status eq ElectionStatus.OPEN) and
                        ElectionTable.votingOpenedAt.isNotNull() and
                        ElectionTable.votingClosedAt.isNull()
                }.orderBy(ElectionTable.votingOpenedAt to SortOrder.ASC, ElectionTable.id to SortOrder.ASC)
                .limit(MAX_OPEN_VOTINGS)
                .toList()
        val own = ElectionOwnParticipation.load(electionRows = running, memberId = ctx.memberId)
        val missing = running.filter { own[it[ElectionTable.id]]?.let { f -> f.eligible == true && !f.hasVoted } == true }
        return listOfNotNull(
            signal(
                module = moduleKey,
                reason = DashboardReasonKeys.ELECTION_NOT_VOTED,
                count = missing.size,
                priority = DashboardPriority.ACTION,
                objectId = missing.firstOrNull()?.get(ElectionTable.id),
            ),
        )
    }
}

/** V1.9.224 -- as [ElectionSignals] for a Systemic Consensus in its rating phase (own flags of the CURRENT round only). */
internal object ConsensusSignals : DashboardSignalSource {
    override val moduleKey: String = DashboardModuleKeys.CONSENSUS

    override fun collect(ctx: SignalContext): List<DashboardSignalDto> {
        if (!SignalGates.showsSelfGovernance(ctx.status)) return emptyList()
        val running =
            SystemicConsensusTable
                .selectAll()
                .where {
                    (SystemicConsensusTable.status eq SystemicConsensusStatus.RATING) and
                        SystemicConsensusTable.ratingOpenedAt.isNotNull() and
                        SystemicConsensusTable.ratingClosedAt.isNull()
                }.orderBy(SystemicConsensusTable.ratingOpenedAt to SortOrder.ASC, SystemicConsensusTable.id to SortOrder.ASC)
                .limit(MAX_OPEN_VOTINGS)
                .toList()
        val own = SystemicConsensusOwnParticipation.load(rows = running, memberId = ctx.memberId)
        val missing = running.filter { own[it[SystemicConsensusTable.id]]?.let { f -> f.eligible == true && !f.hasRated } == true }
        return listOfNotNull(
            signal(
                module = moduleKey,
                reason = DashboardReasonKeys.CONSENSUS_NOT_VOTED,
                count = missing.size,
                priority = DashboardPriority.ACTION,
                objectId = missing.firstOrNull()?.get(SystemicConsensusTable.id),
            ),
        )
    }
}

/**
 * V1.9.224 -- a poll I may answer and have not. The only vote-like module with a real deadline (`poll.closes_at`, class B, organization
 * wall-clock): up to 48 hours before it the signal is [DashboardPriority.URGENT] and carries the date of the earliest deadline.
 */
internal object PollSignals : DashboardSignalSource {
    override val moduleKey: String = DashboardModuleKeys.SURVEY

    override fun collect(ctx: SignalContext): List<DashboardSignalDto> {
        if (!SignalGates.showsSelfGovernance(ctx.status)) return emptyList()
        val open =
            PollTable
                .selectAll()
                .where { effectiveOpenPredicate(ctx.nowWall) }
                .orderBy(PollTable.createdAt to SortOrder.DESC, PollTable.id to SortOrder.ASC)
                .limit(MAX_OPEN_VOTINGS)
                .toList()
        // The caller is an active member (gate above), so the "may answer" state of the poll rules is the same for every row.
        val own = PollOwnParticipation.load(pollRows = open, memberId = ctx.memberId, eligible = true, wallNow = ctx.nowWall)
        val missing = open.filter { own[it[PollTable.id]]?.canRespond == true }
        val earliest = missing.mapNotNull { it[PollTable.closesAt] }.minOrNull()
        val soon = ctx.wallPlus(48.hours)
        return listOfNotNull(
            signal(
                module = moduleKey,
                reason = DashboardReasonKeys.SURVEY_NOT_ANSWERED,
                count = missing.size,
                priority = if (earliest != null && earliest <= soon) DashboardPriority.URGENT else DashboardPriority.ACTION,
                params = earliest?.let { mapOf("date" to it.date.toString()) } ?: emptyMap(),
                objectId = missing.firstOrNull()?.get(PollTable.id),
                deadlineWall = earliest,
            ),
        )
    }
}

/**
 * V1.9.224 -- a running meritocratic vote on a motion that I may bid on and have not. The own flags are loaded PER MEETING:
 * [VoteOwnParticipation] resolves the live electorate of old (unfrozen) votes from the first row's meeting, so rows of different meetings
 * must not be mixed.
 */
internal object MotionVoteSignals : DashboardSignalSource {
    override val moduleKey: String = DashboardModuleKeys.MOTION

    override fun collect(ctx: SignalContext): List<DashboardSignalDto> {
        if (!SignalGates.showsSelfGovernance(ctx.status)) return emptyList()
        val open =
            VoteTable
                .selectAll()
                .where { VoteTable.status eq VoteStatus.OPEN }
                .orderBy(VoteTable.openedAt to SortOrder.ASC, VoteTable.id to SortOrder.ASC)
                .limit(MAX_OPEN_VOTINGS)
                .toList()
        val missing =
            open
                .groupBy { it[VoteTable.meetingId] }
                .values
                .flatMap { rows ->
                    val own = VoteOwnParticipation.load(voteRows = rows, memberId = ctx.memberId, memberStatus = ctx.status)
                    rows.filter { own[it[VoteTable.id]]?.let { f -> f.eligible && !f.hasVoted } == true }
                }.sortedBy { it[VoteTable.id] }
        return listOfNotNull(
            signal(
                module = moduleKey,
                reason = DashboardReasonKeys.MOTION_VOTE_OPEN,
                count = missing.size,
                priority = DashboardPriority.ACTION,
                objectId = missing.firstOrNull()?.get(VoteTable.motionId),
            ),
        )
    }
}
