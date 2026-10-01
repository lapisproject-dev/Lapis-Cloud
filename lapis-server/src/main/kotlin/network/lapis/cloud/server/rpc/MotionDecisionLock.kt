package network.lapis.cloud.server.rpc

import network.lapis.cloud.server.db.generated.ElectionTable
import network.lapis.cloud.server.db.generated.MotionTable
import network.lapis.cloud.server.db.generated.SystemicConsensusTable
import network.lapis.cloud.server.db.generated.VoteTable
import network.lapis.cloud.shared.domain.ElectionStatus
import network.lapis.cloud.shared.domain.MotionStatus
import network.lapis.cloud.shared.domain.SystemicConsensusBindingness
import network.lapis.cloud.shared.domain.SystemicConsensusStatus
import network.lapis.cloud.shared.domain.VoteStatus
import network.lapis.cloud.shared.rpc.ConflictException
import network.lapis.cloud.shared.rpc.NotFoundException
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.neq
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.jdbc.selectAll
import kotlin.uuid.Uuid

/**
 * V1.9.23 -- the single place that serializes the four paths able to decide a motion:
 * quorum resolution ([GovernanceService.resolveMotion]/[GovernanceService.recordResolution]),
 * meritocratic vote ([GovernanceService.openVote]/`closeVote`), systemic consensus
 * ([SystemicConsensusService.evaluate]) and election ([ElectionService.tally]).
 *
 * **Lock order, everywhere:** `motion` -> `election | vote | systemic_consensus` ->
 * `conference_room`/stream -> `member` ([requireActiveMembership] with `forUpdate`) -> audit log
 * (always last, see `AuditLogRecorder`). A method that starts from a child id (an election, a vote, a
 * systemic consensus) first reads the child's `motion_id` WITHOUT a lock (the column never changes),
 * then locks the motion, then re-reads the child row `FOR UPDATE` and does every check on that
 * re-read row. A method that never touches the motion (casting a ballot, approving the tally ...) may
 * lock only the child row: it cannot take part in a lock cycle because it never waits for the motion.
 *
 * `forUpdate()` is only ever applied to single-table selects (H2 rejects it on joins).
 * Every function must run inside an already-open `transaction {}`.
 */
internal object MotionDecisionLock {
    /** Locks the motion row `FOR UPDATE` -- always the first lock of a decision-relevant transaction. */
    fun lockMotion(motionId: Uuid): ResultRow =
        MotionTable
            .selectAll()
            .where { MotionTable.id eq motionId }
            .forUpdate()
            .singleOrNull()
            ?: throw NotFoundException("Motion $motionId not found")

    /** Locks every motion of an agenda item, in ascending id order so two callers can never deadlock. */
    fun lockMotionsByAgendaItem(agendaItemId: Uuid): List<ResultRow> {
        val ids =
            MotionTable
                .selectAll()
                .where { MotionTable.agendaItemId eq agendaItemId }
                .map { it[MotionTable.id] }
                .sortedBy { it.toString() }
        return ids.map { lockMotion(it) }
    }

    /**
     * An election that is still running (PREPARATION .. CLOSED) owns this motion. A TALLIED election has already
     * decided it (or postponed it after a tie) and can no longer be aborted, so it must not block the other
     * paths: a tied, POSTPONED and re-scheduled motion has to stay decidable and withdrawable.
     */
    fun requireNoActiveElection(motionId: Uuid) {
        val active =
            ElectionTable
                .selectAll()
                .where { (ElectionTable.activeMotionId eq motionId) and (ElectionTable.status inList RUNNING_ELECTION_STATUSES) }
                .count() > 0
        if (active) throw ConflictException("Motion $motionId has an active Election; abort it first")
    }

    /**
     * Any election that is not ABORTED (running or TALLIED), for [ElectionService.openElection] only: the unique
     * index on `active_motion_id` allows at most one non-aborted election per motion.
     */
    fun requireNoElection(motionId: Uuid) {
        val exists = ElectionTable.selectAll().where { ElectionTable.activeMotionId eq motionId }.count() > 0
        if (exists) throw ConflictException("Motion $motionId has an active Election; abort it first")
    }

    private val RUNNING_CONSENSUS_STATUSES = listOf(SystemicConsensusStatus.COLLECTION, SystemicConsensusStatus.RATING)

    private val RUNNING_ELECTION_STATUSES =
        listOf(
            ElectionStatus.PREPARATION,
            ElectionStatus.CANDIDATE_LIST_RELEASED,
            ElectionStatus.OPEN,
            ElectionStatus.CLOSED,
        )

    fun requireNoOpenVote(motionId: Uuid) {
        val active =
            VoteTable
                .selectAll()
                .where { (VoteTable.motionId eq motionId) and (VoteTable.status inList listOf(VoteStatus.OPEN, VoteStatus.CLOSED)) }
                .count() > 0
        if (active) throw ConflictException("Motion $motionId has an open or resolved Vote")
    }

    /**
     * A systemic consensus that is running, or a BINDING one that was evaluated, owns the motion. An ADVISORY
     * consensus (the default) never decides anything, so once it is EVALUATED or CLOSED it is just an opinion poll.
     */
    fun requireNoActiveSystemicConsensus(motionId: Uuid) {
        val active =
            SystemicConsensusTable
                .selectAll()
                .where {
                    (SystemicConsensusTable.motionId eq motionId) and
                        (SystemicConsensusTable.status neq SystemicConsensusStatus.ABORTED) and
                        (
                            (SystemicConsensusTable.bindingness eq SystemicConsensusBindingness.BINDING) or
                                (SystemicConsensusTable.status inList RUNNING_CONSENSUS_STATUSES)
                        )
                }.count() > 0
        if (active) throw ConflictException("Motion $motionId has an open or resolved SystemicConsensus")
    }

    /**
     * The motion is still waiting for a decision. Judged by the status of the LOCKED row only: every deciding
     * path (resolveMotion, closeVote, evaluate, tally) moves the status from SCHEDULED to a terminal one, and
     * only a POSTPONED motion can be scheduled again. `resolution_id` is deliberately NOT part of the check:
     * a motion that was POSTPONED and re-scheduled legitimately still carries its earlier resolution.
     */
    fun requireUndecided(motionRow: ResultRow) {
        val id = motionRow[MotionTable.id]
        if (motionRow[MotionTable.status] != MotionStatus.SCHEDULED) {
            throw ConflictException("Motion $id is ${motionRow[MotionTable.status]}, expected SCHEDULED")
        }
    }
}

/** Locks the election row `FOR UPDATE`; the caller must do its status checks on the returned (re-read) row. */
internal fun lockElectionRow(electionId: Uuid): ResultRow =
    ElectionTable
        .selectAll()
        .where { ElectionTable.id eq electionId }
        .forUpdate()
        .singleOrNull()
        ?: throw NotFoundException("Election $electionId not found")
