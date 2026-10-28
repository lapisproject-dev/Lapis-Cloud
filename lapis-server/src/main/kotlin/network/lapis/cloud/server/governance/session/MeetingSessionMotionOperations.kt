package network.lapis.cloud.server.governance.session

import network.lapis.cloud.server.conference.SecretBallotStreamGuard
import network.lapis.cloud.server.db.DbClock
import network.lapis.cloud.server.db.generated.ElectionEligibleVoterTable
import network.lapis.cloud.server.db.generated.ElectionTable
import network.lapis.cloud.server.db.generated.ElectionTallyApprovalTable
import network.lapis.cloud.server.db.generated.MeetingTable
import network.lapis.cloud.server.db.generated.MotionTable
import network.lapis.cloud.server.db.generated.ResolutionTable
import network.lapis.cloud.server.db.generated.SystemicConsensusTable
import network.lapis.cloud.server.db.generated.VoteTable
import network.lapis.cloud.server.rpc.MAX_ELECTION_BOARD_SIZE
import network.lapis.cloud.server.rpc.MIN_ELECTION_BOARD_SIZE
import network.lapis.cloud.server.rpc.MIN_TALLY_THRESHOLD
import network.lapis.cloud.server.rpc.MotionDecisionLock
import network.lapis.cloud.server.rpc.NON_TERMINAL_MOTION_STATUSES
import network.lapis.cloud.server.rpc.appointElectionBoardLocked
import network.lapis.cloud.server.rpc.electionBallotCount
import network.lapis.cloud.server.rpc.lockElectionRow
import network.lapis.cloud.server.rpc.openElectionLocked
import network.lapis.cloud.server.rpc.openVotingLocked
import network.lapis.cloud.server.rpc.requireElectionPathAvailable
import network.lapis.cloud.server.rpc.toResolutionDtos
import network.lapis.cloud.server.security.CurrentMember
import network.lapis.cloud.server.security.canRecordForMeeting
import network.lapis.cloud.server.security.isElectionBoard
import network.lapis.cloud.server.security.isPrivileged
import network.lapis.cloud.server.tender.isTenderAwardMotion
import network.lapis.cloud.server.webhook.WebhookEventPublisher
import network.lapis.cloud.shared.domain.ConferenceMeetingStateDto
import network.lapis.cloud.shared.domain.ElectionOpenInput
import network.lapis.cloud.shared.domain.ElectionStatus
import network.lapis.cloud.shared.domain.ElectionType
import network.lapis.cloud.shared.domain.MeetingSessionAuditKind
import network.lapis.cloud.shared.domain.MeetingSessionAuditSnapshot
import network.lapis.cloud.shared.domain.MeetingSessionRole
import network.lapis.cloud.shared.domain.MotionStatus
import network.lapis.cloud.shared.domain.SessionAgendaItemDto
import network.lapis.cloud.shared.domain.SessionMotionDto
import network.lapis.cloud.shared.domain.SessionMotionRefDto
import network.lapis.cloud.shared.domain.SessionMotionVoteDto
import network.lapis.cloud.shared.domain.SessionMotionVoteStatus
import network.lapis.cloud.shared.domain.SessionResolutionDto
import network.lapis.cloud.shared.domain.SystemicConsensusBindingness
import network.lapis.cloud.shared.domain.SystemicConsensusStatus
import network.lapis.cloud.shared.domain.VoteStatus
import network.lapis.cloud.shared.domain.WebhookEventType
import network.lapis.cloud.shared.rpc.AgendaMovedMeanwhileException
import network.lapis.cloud.shared.rpc.ConflictException
import network.lapis.cloud.shared.rpc.ForbiddenException
import network.lapis.cloud.shared.rpc.NotFoundException
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.neq
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import kotlin.uuid.Uuid

private const val ATTACHABLE_CAP = 50

/**
 * V1.9.238 "Beschluesse aus Abstimmungen im Konferenzraum" -- the two mutations behind `IGovernanceService.attachConferenceMotion` and
 * `openConferenceMotionVote`. `GovernanceService` only delegates here.
 *
 * **Access.** Both resolve `(roomId, meetingId)` through [MeetingSessionAccess] (one and the same not-found for whatever the caller must not
 * see) and then need the role LEAD **and** `canRecordForMeeting(committee)` -- exactly what `scheduleMotion` / `openElection` require, so no new
 * right appears. A motion that is not of this Sitzung's committee is as unknown as a random id (never a Conflict that would reveal it).
 *
 * **Locks.** Sitzung row, then motion, then election (and its electorate snapshot), then `conference_room` (secret vote only), then the audit
 * entry as the last step. A search of `MeetingTable ... forUpdate` shows that only this package and the meeting-event operations lock the
 * Sitzung row, and neither locks a motion afterwards, so no path takes motion -> Sitzung. LiveKit is only asked after the commit.
 *
 * **Audit.** `MOTION_VOTE_OPENED` is written after `ConferenceStreamPauseCoordinator.markPausingForSecretBallot` (inside `openVotingLocked`),
 * which writes audit entries of its own: both are pure audit steps at the end, nothing locks afterwards.
 *
 * **Privacy / fail closed.** A secret vote needs the [streamGuard]; without one it is refused rather than started unprotected. No log line or
 * message carries a title, a text, a name or a ballot.
 */
internal class MeetingSessionMotionOperations(
    private val base: MeetingSessionOperations,
    private val streamGuard: SecretBallotStreamGuard?,
) {
    suspend fun attach(
        current: CurrentMember,
        roomId: Uuid,
        meetingId: Uuid,
        motionId: String,
        expectedAgendaItemId: String,
    ): ConferenceMeetingStateDto {
        base.beginMutation(current)
        val motion = base.parseId(motionId)
        val expected = base.parseId(expectedAgendaItemId)
        transaction {
            val ctx = MeetingSessionAccess.require(current = current, roomId = roomId, expectedMeetingId = meetingId, lock = true)
            requireManager(ctx = ctx, current = current)
            base.requireRoomLive(ctx)
            base.requireOpen(ctx = ctx)
            if (base.effectiveCurrentItem(ctx) != expected) throw AgendaMovedMeanwhileException()
            val row = lockMotionOfCommittee(ctx = ctx, motionId = motion)
            if (row[MotionTable.amendsMotionId] != null) throw ConflictException("an amendment follows its main motion")
            val status = row[MotionTable.status]
            if (status != MotionStatus.REVIEWED && status != MotionStatus.POSTPONED) {
                throw ConflictException("motion is $status, expected REVIEWED or POSTPONED")
            }
            // Exactly what openElectionLocked requires (pending amendments, an earlier election -- a tie leaves one --, an OPEN/CLOSED Vote,
            // a BINDING or running SystemicConsensus, a tender award motion): otherwise the motion would be stranded as SCHEDULED with a
            // vote button that always conflicts.
            requireElectionPathAvailable(motionId = motion, isAmendment = false)
            MotionTable.update({ MotionTable.id eq motion }) {
                it[MotionTable.status] = MotionStatus.SCHEDULED
                it[MotionTable.meetingId] = ctx.meetingId
                it[MotionTable.agendaItemId] = expected
            }
            WebhookEventPublisher.publish(
                eventType = WebhookEventType.MOTION_SCHEDULED,
                entityId = motion,
                occurredAt = DbClock.nowLocalDateTime(),
            )
            MeetingSessionAudit.record(
                actor = current,
                snapshot =
                    MeetingSessionAuditSnapshot(
                        kind = MeetingSessionAuditKind.MOTION_ATTACHED,
                        meetingId = ctx.meetingId.toString(),
                        toAgendaItemId = expected.toString(),
                        motionId = motion.toString(),
                    ),
            )
        }
        return base.requireState(current = current, roomId = roomId)
    }

    @Suppress("LongParameterList")
    suspend fun openVote(
        current: CurrentMember,
        roomId: Uuid,
        meetingId: Uuid,
        motionId: String,
        expectedAgendaItemId: String,
        secret: Boolean,
        boardMemberIds: List<String>,
    ): ConferenceMeetingStateDto {
        base.beginMutation(current)
        val motion = base.parseId(motionId)
        val expected = base.parseId(expectedAgendaItemId)
        val board = boardMemberIds.map(base::parseId).distinct()
        if (board.size < MIN_ELECTION_BOARD_SIZE || board.size > MAX_ELECTION_BOARD_SIZE) {
            throw ConflictException(
                "the election board needs between $MIN_ELECTION_BOARD_SIZE and $MAX_ELECTION_BOARD_SIZE members, got ${board.size}",
            )
        }
        val guard = streamGuard
        if (secret && guard == null) throw ConflictException("secret votes are not available")
        transaction {
            val ctx = MeetingSessionAccess.require(current = current, roomId = roomId, expectedMeetingId = meetingId, lock = true)
            requireManager(ctx = ctx, current = current)
            base.requireRoomLive(ctx)
            base.requireOpen(ctx = ctx)
            if (base.effectiveCurrentItem(ctx) != expected) throw AgendaMovedMeanwhileException()
            val row = lockMotionOfCommittee(ctx = ctx, motionId = motion)
            if (row[MotionTable.meetingId] != ctx.meetingId) throw NotFoundException(MeetingSessionAccess.NOT_FOUND_MESSAGE)
            if (row[MotionTable.agendaItemId] != expected) throw ConflictException("motion belongs to another agenda item")
            if (row[MotionTable.status] != MotionStatus.SCHEDULED) {
                throw ConflictException("motion is ${row[MotionTable.status]}, expected SCHEDULED")
            }
            MeetingSessionMotionReads.requireNoOpenDecisionInMeeting(meetingId = ctx.meetingId, openOnly = false)
            val roster =
                CommitteeAttendanceRules
                    .forType(ctx.committeeRow[network.lapis.cloud.server.db.generated.CommitteeTable.type])
                    .roster(
                        meetingId = ctx.meetingId,
                        committeeRow = ctx.committeeRow,
                        scheduledDate = ctx.meetingRow[MeetingTable.scheduledAt].date,
                    ).map { it.memberId }
                    .toSet()
            // An unknown person and a person outside the roster look the same (no member enumeration).
            if (board.any { it !in roster }) throw NotFoundException(MeetingSessionAccess.NOT_FOUND_MESSAGE)
            // The caller must close the vote later: BOARD/ADMIN may always, everybody else only as a board member.
            if (!current.isPrivileged && current.memberId !in board) throw ConflictException("the lead must be part of the election board")
            val electionId =
                openElectionLocked(
                    current = current,
                    motionRow = row,
                    input =
                        ElectionOpenInput(
                            motionId = motion.toString(),
                            electionType = ElectionType.YES_NO,
                            secret = secret,
                            tallyThreshold = MIN_TALLY_THRESHOLD,
                            requiredMajorityPercent = 50,
                        ),
                )
            appointElectionBoardLocked(
                current = current,
                electionRow = lockElectionRow(electionId),
                memberIds = board.map { it.toString() },
            )
            openVotingLocked(current = current, electionRow = lockElectionRow(electionId))
            MeetingSessionAudit.record(
                actor = current,
                snapshot =
                    MeetingSessionAuditSnapshot(
                        kind = MeetingSessionAuditKind.MOTION_VOTE_OPENED,
                        meetingId = ctx.meetingId.toString(),
                        toAgendaItemId = expected.toString(),
                        motionId = motion.toString(),
                        electionId = electionId.toString(),
                        secret = secret,
                        electionBoardSize = board.size,
                    ),
            )
        }
        // OUTSIDE the transaction, like ElectionService.openVoting: never a LiveKit call inside an open transaction.
        if (secret) guard?.quiesceStreamsForMeeting(meetingId)
        return base.requireState(current = current, roomId = roomId)
    }

    private fun requireManager(
        ctx: SessionContext,
        current: CurrentMember,
    ) {
        if (ctx.role != MeetingSessionRole.LEAD || !current.canRecordForMeeting(ctx.committeeId)) throw ForbiddenException()
    }

    /** Locks the motion; one that is unknown or of another committee is the same not-found (never a hint that it exists). */
    private fun lockMotionOfCommittee(
        ctx: SessionContext,
        motionId: Uuid,
    ): ResultRow {
        val row =
            try {
                MotionDecisionLock.lockMotion(motionId)
            } catch (_: NotFoundException) {
                throw NotFoundException(MeetingSessionAccess.NOT_FOUND_MESSAGE)
            }
        if (row[MotionTable.targetCommitteeId] != ctx.committeeId) throw NotFoundException(MeetingSessionAccess.NOT_FOUND_MESSAGE)
        return row
    }
}

/** The read side of the motion block of [ConferenceMeetingStateDto] (all inside the caller's transaction) and the "one decision at a time" rule. */
internal object MeetingSessionMotionReads {
    private val SHOWN_STATUSES = listOf(MotionStatus.SCHEDULED, MotionStatus.RESOLVED, MotionStatus.REJECTED, MotionStatus.POSTPONED)
    private val RESOLVED_STATUSES = setOf(MotionStatus.RESOLVED, MotionStatus.REJECTED, MotionStatus.POSTPONED)
    private val RUNNING_VOTE_STATUSES = listOf(ElectionStatus.OPEN, ElectionStatus.CLOSED)

    /**
     * The motions per agenda item, in the order of the agenda, then by submission time, then id (a fixed order, also for equal timestamps).
     * Counters of an OPEN vote go to LEAD/MINUTES only and NEVER for a secret vote; the figures of a resolution come from
     * `toResolutionDtos`, which withholds those of a secret vote with too few ballots.
     */
    fun agendaMotions(
        ctx: SessionContext,
        agenda: List<SessionAgendaItemDto>,
    ): List<SessionMotionDto> {
        if (agenda.isEmpty()) return emptyList()
        val agendaIds = agenda.map { Uuid.parse(it.id) }
        val order = agendaIds.withIndex().associate { it.value to it.index }
        val rows =
            MotionTable
                .selectAll()
                .where {
                    (MotionTable.meetingId eq ctx.meetingId) and
                        (MotionTable.agendaItemId inList agendaIds) and
                        MotionTable.amendsMotionId.isNull() and
                        (MotionTable.status inList SHOWN_STATUSES)
                }.toList()
                .sortedWith(
                    compareBy<ResultRow>(
                        { order[it[MotionTable.agendaItemId]] ?: Int.MAX_VALUE },
                        { it[MotionTable.submittedAt] },
                        { it[MotionTable.id] },
                    ),
                )
        val resolutionRows =
            rows
                .filter { it[MotionTable.status] in RESOLVED_STATUSES }
                .mapNotNull { it[MotionTable.resolutionId] }
                .let { ids ->
                    if (ids.isEmpty()) emptyList() else ResolutionTable.selectAll().where { ResolutionTable.id inList ids }.toList()
                }
        val resolutions = resolutionRows.toResolutionDtos().associateBy { it.id }
        val privileged = ctx.role != MeetingSessionRole.PARTICIPANT
        return rows.map { row ->
            val vote = latestVote(ctx = ctx, motionRow = row, withCounters = privileged)
            val resolution =
                row[MotionTable.resolutionId]
                    ?.takeIf { row[MotionTable.status] in RESOLVED_STATUSES }
                    ?.let { resolutions[it.toString()] }
                    ?.let {
                        SessionResolutionDto(
                            id = it.id,
                            number = it.number,
                            status = it.status,
                            votesYes = it.votesYes,
                            votesNo = it.votesNo,
                            votesAbstain = it.votesAbstain,
                            figuresWithheld = it.figuresWithheld,
                            quorumMet = it.quorumMet,
                            decidedAt = it.decidedAt,
                        )
                    }
            SessionMotionDto(
                id = row[MotionTable.id].toString(),
                agendaItemId = row[MotionTable.agendaItemId].toString(),
                title = row[MotionTable.title],
                effectiveText = row[MotionTable.currentText] ?: row[MotionTable.text],
                status = row[MotionTable.status],
                vote = vote,
                resolution = resolution,
            )
        }
    }

    /** The vote that holds the Sitzung right now (OPEN, or CLOSED and waiting for its tally); visible to every role as a banner. */
    fun runningVote(
        ctx: SessionContext,
        current: CurrentMember,
    ): SessionMotionVoteDto? {
        val election =
            ElectionTable
                .selectAll()
                .where {
                    (ElectionTable.meetingId eq ctx.meetingId) and
                        (ElectionTable.electionType eq ElectionType.YES_NO) and
                        (ElectionTable.status inList RUNNING_VOTE_STATUSES)
                }.orderBy(ElectionTable.openedAt to SortOrder.DESC, ElectionTable.id to SortOrder.DESC)
                .limit(1)
                .singleOrNull() ?: return null
        val motion = MotionTable.selectAll().where { MotionTable.id eq election[ElectionTable.motionId] }.singleOrNull() ?: return null
        return toVoteDto(election = election, motionRow = motion, withCounters = ctx.role != MeetingSessionRole.PARTICIPANT)
            ?.copy(canClose = current.isElectionBoard(election[ElectionTable.id]))
    }

    fun attachable(ctx: SessionContext): List<SessionMotionRefDto> {
        val candidates =
            MotionTable
                .selectAll()
                .where {
                    (MotionTable.targetCommitteeId eq ctx.committeeId) and
                        (MotionTable.status inList listOf(MotionStatus.REVIEWED, MotionStatus.POSTPONED)) and
                        MotionTable.amendsMotionId.isNull()
                }.orderBy(MotionTable.submittedAt to SortOrder.ASC, MotionTable.id to SortOrder.ASC)
                .toList()
        if (candidates.isEmpty()) return emptyList()
        // a main motion with a pending amendment cannot be attached (see attach) -- leave it out of the list
        val withPendingAmendment =
            MotionTable
                .selectAll()
                .where {
                    (MotionTable.amendsMotionId inList candidates.map { it[MotionTable.id] }) and
                        (MotionTable.status inList NON_TERMINAL_MOTION_STATUSES.toList())
                }.mapNotNull { it[MotionTable.amendsMotionId] }
                .toSet()
        val ids = candidates.map { it[MotionTable.id] }
        // ... nor a motion that already had a (non-aborted) election, owns an OPEN/CLOSED Vote or a BINDING/running SystemicConsensus
        val blocked = HashSet<Uuid>()
        ElectionTable.selectAll().where { ElectionTable.activeMotionId inList ids }.forEach { r ->
            r[ElectionTable.activeMotionId]?.let(blocked::add)
        }
        VoteTable
            .selectAll()
            .where { (VoteTable.motionId inList ids) and (VoteTable.status inList listOf(VoteStatus.OPEN, VoteStatus.CLOSED)) }
            .forEach { blocked.add(it[VoteTable.motionId]) }
        SystemicConsensusTable
            .selectAll()
            .where {
                (SystemicConsensusTable.motionId inList ids) and
                    (SystemicConsensusTable.status neq SystemicConsensusStatus.ABORTED) and
                    (
                        (SystemicConsensusTable.bindingness eq SystemicConsensusBindingness.BINDING) or
                            (
                                SystemicConsensusTable.status inList
                                    listOf(SystemicConsensusStatus.COLLECTION, SystemicConsensusStatus.RATING)
                            )
                    )
            }.forEach { blocked.add(it[SystemicConsensusTable.motionId]) }
        return candidates
            .asSequence()
            .filter { it[MotionTable.id] !in withPendingAmendment && it[MotionTable.id] !in blocked }
            // ... and a tender award motion (decided by quorum / meritocratic vote only); checked last: one query each, only up to the cap
            .filter { !isTenderAwardMotion(it[MotionTable.id]) }
            .take(ATTACHABLE_CAP)
            .toList()
            .map {
                SessionMotionRefDto(id = it[MotionTable.id].toString(), title = it[MotionTable.title], status = it[MotionTable.status])
            }
    }

    /**
     * Only ONE decision at a time in a Sitzung. With [openOnly] only a vote that is open right now counts (hand-over of the agenda); otherwise
     * every decision that is not finished yet does (opening a vote): an election in PREPARATION..CLOSED (a closed one must be tallied first), an
     * OPEN meritocratic vote, a systemic consensus in COLLECTION/RATING/CLOSED. Must run in the transaction that holds the Sitzung lock.
     */
    fun requireNoOpenDecisionInMeeting(
        meetingId: Uuid,
        openOnly: Boolean,
    ) {
        val electionStatuses =
            if (openOnly) {
                listOf(ElectionStatus.OPEN)
            } else {
                listOf(ElectionStatus.PREPARATION, ElectionStatus.CANDIDATE_LIST_RELEASED, ElectionStatus.OPEN, ElectionStatus.CLOSED)
            }
        val consensusStatuses =
            if (openOnly) {
                listOf(SystemicConsensusStatus.COLLECTION, SystemicConsensusStatus.RATING)
            } else {
                listOf(SystemicConsensusStatus.COLLECTION, SystemicConsensusStatus.RATING, SystemicConsensusStatus.CLOSED)
            }
        val busy =
            ElectionTable
                .selectAll()
                .where {
                    (ElectionTable.meetingId eq meetingId) and (ElectionTable.status inList electionStatuses)
                }.count() >
                0 ||
                VoteTable.selectAll().where { (VoteTable.meetingId eq meetingId) and (VoteTable.status eq VoteStatus.OPEN) }.count() > 0 ||
                SystemicConsensusTable
                    .selectAll()
                    .where { (SystemicConsensusTable.meetingId eq meetingId) and (SystemicConsensusTable.status inList consensusStatuses) }
                    .count() > 0
        if (busy) {
            throw ConflictException(
                if (openOnly) {
                    "Während einer laufenden Abstimmung kann nicht weitergeschaltet werden."
                } else {
                    "Es läuft bereits eine Abstimmung in dieser Sitzung."
                },
            )
        }
    }

    private fun latestVote(
        ctx: SessionContext,
        motionRow: ResultRow,
        withCounters: Boolean,
    ): SessionMotionVoteDto? {
        val election =
            ElectionTable
                .selectAll()
                .where {
                    (ElectionTable.motionId eq motionRow[MotionTable.id]) and
                        (ElectionTable.meetingId eq ctx.meetingId) and
                        (ElectionTable.electionType eq ElectionType.YES_NO)
                }.orderBy(ElectionTable.openedAt to SortOrder.DESC, ElectionTable.id to SortOrder.DESC)
                .limit(1)
                .singleOrNull() ?: return null
        return toVoteDto(election = election, motionRow = motionRow, withCounters = withCounters)
    }

    private fun toVoteDto(
        election: ResultRow,
        motionRow: ResultRow,
        withCounters: Boolean,
    ): SessionMotionVoteDto? {
        val status =
            when (election[ElectionTable.status]) {
                ElectionStatus.OPEN -> SessionMotionVoteStatus.OPEN
                ElectionStatus.CLOSED -> SessionMotionVoteStatus.CLOSED_AWAITING_TALLY
                ElectionStatus.TALLIED -> SessionMotionVoteStatus.TALLIED
                ElectionStatus.ABORTED -> SessionMotionVoteStatus.ABORTED
                ElectionStatus.PREPARATION, ElectionStatus.CANDIDATE_LIST_RELEASED -> return null
            }
        val electionId = election[ElectionTable.id]
        val secret = election[ElectionTable.secret]
        // A secret vote is not even counted: no live figure, for nobody.
        val live = withCounters && !secret && status == SessionMotionVoteStatus.OPEN
        val awaiting = withCounters && status == SessionMotionVoteStatus.CLOSED_AWAITING_TALLY
        return SessionMotionVoteDto(
            electionId = electionId.toString(),
            motionId = motionRow[MotionTable.id].toString(),
            motionTitle = motionRow[MotionTable.title],
            status = status,
            secret = secret,
            ballotCount = if (live) electionBallotCount(electionId) else null,
            eligibleCount =
                if (live) {
                    ElectionEligibleVoterTable
                        .selectAll()
                        .where { ElectionEligibleVoterTable.electionId eq electionId }
                        .count()
                        .toInt()
                } else {
                    null
                },
            tallyApprovalCount =
                if (awaiting) {
                    ElectionTallyApprovalTable
                        .selectAll()
                        .where { ElectionTallyApprovalTable.electionId eq electionId }
                        .count()
                        .toInt()
                } else {
                    null
                },
            tallyThreshold = if (awaiting) election[ElectionTable.tallyThreshold] else null,
        )
    }
}
