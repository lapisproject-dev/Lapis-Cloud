package network.lapis.cloud.server.governance.session

import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.server.conference.SecretBallotStreamGuard
import network.lapis.cloud.server.db.DbClock
import network.lapis.cloud.server.db.generated.ElectionOptionTable
import network.lapis.cloud.server.db.generated.ElectionTable
import network.lapis.cloud.server.db.generated.ElectionTallyApprovalTable
import network.lapis.cloud.server.db.generated.MotionTable
import network.lapis.cloud.server.db.generated.SystemicConsensusTable
import network.lapis.cloud.server.db.generated.VoteTable
import network.lapis.cloud.server.rpc.MotionDecisionLock
import network.lapis.cloud.server.rpc.lockElectionRow
import network.lapis.cloud.server.rpc.recordElectionBallotLocked
import network.lapis.cloud.server.rpc.tallyElectionLocked
import network.lapis.cloud.server.security.CurrentMember
import network.lapis.cloud.shared.domain.ConferenceMeetingStateDto
import network.lapis.cloud.shared.domain.ElectionAnswer
import network.lapis.cloud.shared.domain.ElectionStatus
import network.lapis.cloud.shared.domain.MotionStatus
import network.lapis.cloud.shared.domain.SystemicConsensusAggregation
import network.lapis.cloud.shared.domain.SystemicConsensusBindingness
import network.lapis.cloud.shared.domain.SystemicConsensusStatus
import network.lapis.cloud.shared.domain.SystemicConsensusTiebreakRule
import network.lapis.cloud.shared.domain.VoteStatus
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import java.math.BigDecimal
import kotlin.uuid.Uuid

/** Records how often the stream guard was asked, per Sitzung -- the fake behind the "secret vote quiesces the streams" checks. */
internal class RecordingStreamGuard : SecretBallotStreamGuard {
    val quiesced = mutableListOf<Uuid>()

    override suspend fun quiesceStreamsForMeeting(meetingId: Uuid) {
        quiesced += meetingId
    }

    override suspend fun resumeStreamsForMeeting(meetingId: Uuid) = Unit
}

internal fun SessionScene.motionOps(guard: SecretBallotStreamGuard? = RecordingStreamGuard()) =
    MeetingSessionMotionOperations(base = ops, streamGuard = guard)

/** The three people of the election board used throughout the motion tests: the lead, the minute taker and one more present person. */
internal val SessionScene.board: List<CurrentMember> get() = listOf(lead, minutes, participant)

internal fun SessionScene.boardIds(): List<String> = board.map { it.memberId.toString() }

internal fun SessionFixture.newMotion(
    committee: Uuid,
    submitter: CurrentMember,
    status: MotionStatus = MotionStatus.REVIEWED,
    title: String = "MSX Antrag",
    text: String = "MSX Wortlaut des Antrags",
    amends: Uuid? = null,
): Uuid {
    val id = Uuid.random()
    transaction {
        MotionTable.insert {
            it[MotionTable.id] = id
            it[targetCommitteeId] = committee
            it[MotionTable.title] = title
            it[rationale] = "MSX Begruendung"
            it[MotionTable.text] = text
            it[submitterMemberId] = submitter.memberId
            it[MotionTable.status] = status
            it[submittedAt] = LocalDateTime(2026, 2, 1, 12, 0)
            it[reviewedBy] = null
            it[reviewedAt] = null
            it[reviewNote] = null
            it[withdrawnAt] = null
            it[meetingId] = null
            it[agendaItemId] = null
            it[resolutionId] = null
            it[amendsMotionId] = amends
            it[currentText] = null
        }
    }
    motionIds += id
    return id
}

/** Opens the Sitzung and makes the first agenda item current; returns the id of that item. */
internal suspend fun SessionScene.openMeeting(): String {
    val state = ops.open(current = lead, roomId = roomId, meetingId = meetingId)
    return state.currentAgendaItemId!!
}

/** Attaches a fresh REVIEWED motion to the current agenda item and returns its id. */
internal suspend fun SessionScene.attachedMotion(
    motions: MeetingSessionMotionOperations,
    title: String = "MSX Antrag",
    text: String = "MSX Wortlaut des Antrags",
): Uuid {
    val item = openMeeting()
    val motion = fx.newMotion(committee = committee, submitter = participant, title = title, text = text)
    motions.attach(
        current = lead,
        roomId = roomId,
        meetingId = meetingId,
        motionId = motion.toString(),
        expectedAgendaItemId = item,
    )
    return motion
}

internal fun ConferenceMeetingStateDto.currentItem(): String = currentAgendaItemId!!

/** Casts [yes]/[no]/[abstain] ballots as that many different present members, through the production ballot writer. */
internal fun SessionScene.castBallots(
    electionId: Uuid,
    yes: Int,
    no: Int,
    abstain: Int,
) {
    val voters = everyone
    require(yes + no + abstain <= voters.size) { "not enough voters" }
    val answers = List(yes) { ElectionAnswer.YES } + List(no) { ElectionAnswer.NO } + List(abstain) { ElectionAnswer.ABSTAIN }
    transaction {
        val row = lockElectionRow(electionId)
        val options =
            ElectionOptionTable
                .selectAll()
                .where { ElectionOptionTable.electionId eq electionId }
                .associate { it[ElectionOptionTable.label] to it[ElectionOptionTable.id] }
        answers.forEachIndexed { index, answer ->
            recordElectionBallotLocked(
                electionRow = row,
                voterMemberId = voters[index].memberId,
                selectedOptionIds = listOf(options.getValue(answer.name)),
                now = DbClock.nowLocalDateTime(),
            )
        }
    }
}

/** What `closeVoting` + two `approveTally` do, written straight into the rows (the RPC route is covered by the wiring test). */
internal fun SessionScene.closeVoting(electionId: Uuid) {
    transaction {
        ElectionTable.update({ ElectionTable.id eq electionId }) {
            it[status] = ElectionStatus.CLOSED
            it[votingClosedAt] = DbClock.nowLocalDateTime()
        }
        board.take(2).forEach { approver ->
            ElectionTallyApprovalTable.insert {
                it[id] = Uuid.random()
                it[ElectionTallyApprovalTable.electionId] = electionId
                it[memberId] = approver.memberId
                it[approvedAt] = DbClock.nowLocalDateTime()
            }
        }
    }
}

/** The production tally (`tallyElectionLocked`) as the lead, in the same lock order as `ElectionService.tally`. */
internal fun SessionScene.tally(electionId: Uuid) {
    transaction {
        val motionId = ElectionTable.selectAll().where { ElectionTable.id eq electionId }.single()[ElectionTable.motionId]
        val motionRow = MotionDecisionLock.lockMotion(motionId)
        tallyElectionLocked(electionMotionRow = motionRow, electionRow = lockElectionRow(electionId), actor = lead)
    }
}

internal fun SessionScene.electionOf(motionId: Uuid): Uuid =
    transaction {
        ElectionTable.selectAll().where { ElectionTable.motionId eq motionId }.single()[ElectionTable.id]
    }

internal fun SessionScene.motionStatus(motionId: Uuid): MotionStatus =
    transaction { MotionTable.selectAll().where { MotionTable.id eq motionId }.single()[MotionTable.status] }

internal fun SessionScene.motionRowCount(motionId: Uuid): Long =
    transaction { MotionTable.selectAll().where { (MotionTable.id eq motionId) and (MotionTable.meetingId eq meetingId) }.count() }

/** A motion that is already attached (its row said REVIEWED -> SCHEDULED) to the current item through the production operation. */
internal suspend fun SessionScene.attachedMotionOn(
    motions: MeetingSessionMotionOperations,
    motion: Uuid,
): Uuid {
    motions.attach(
        current = lead,
        roomId = roomId,
        meetingId = meetingId,
        motionId = motion.toString(),
        expectedAgendaItemId = agenda[0].toString(),
    )
    return motion
}

/** Other decision paths of the same Sitzung, written straight into the rows (only their status matters for the "one decision at a time" rule). */
internal object SessionDecisionRows {
    fun systemicConsensus(
        meetingId: Uuid,
        motionId: Uuid,
        opener: CurrentMember,
        status: SystemicConsensusStatus = SystemicConsensusStatus.COLLECTION,
        bindingness: SystemicConsensusBindingness = SystemicConsensusBindingness.ADVISORY,
    ): Uuid {
        val consensusId = Uuid.random()
        transaction {
            SystemicConsensusTable.insert {
                it[id] = consensusId
                it[title] = "MSX SK"
                it[SystemicConsensusTable.status] = status
                it[secret] = false
                it[scaleMax] = 10
                it[aggregation] = SystemicConsensusAggregation.MEAN
                it[tiebreakRule] = SystemicConsensusTiebreakRule.LOWEST_MAX_RESISTANCE
                it[groupConflictViableThreshold] = BigDecimal("0.500")
                it[groupConflictWarnThreshold] = BigDecimal("0.700")
                it[statusQuoOptionAuto] = false
                it[SystemicConsensusTable.bindingness] = bindingness
                it[maxRounds] = 3
                it[round] = 1
                it[winnerOptionId] = null
                it[openedBy] = opener.memberId
                it[openedAt] = LocalDateTime(2026, 2, 1, 12, 0)
                it[ratingOpenedAt] = null
                it[ratingClosedAt] = null
                it[tallyRunAt] = null
                it[SystemicConsensusTable.motionId] = motionId
                it[SystemicConsensusTable.meetingId] = meetingId
                it[resolutionId] = null
            }
        }
        return consensusId
    }

    fun finishConsensus(consensusId: Uuid) {
        transaction {
            SystemicConsensusTable.update({ SystemicConsensusTable.id eq consensusId }) {
                it[status] = SystemicConsensusStatus.EVALUATED
            }
        }
    }

    fun meritocraticVote(
        meetingId: Uuid,
        motionId: Uuid,
        opener: CurrentMember,
        status: VoteStatus = VoteStatus.OPEN,
    ): Uuid {
        val voteId = Uuid.random()
        transaction {
            VoteTable.insert {
                it[id] = voteId
                it[title] = "MSX Vote"
                it[VoteTable.status] = status
                it[openedBy] = opener.memberId
                it[openedAt] = LocalDateTime(2026, 2, 1, 12, 0)
                it[closedAt] = null
                it[winnerOptionId] = null
                it[secondPriceLtr] = null
                it[electorateFrozenAt] = null
                it[VoteTable.motionId] = motionId
                it[VoteTable.meetingId] = meetingId
                it[resolutionId] = null
            }
        }
        return voteId
    }
}
