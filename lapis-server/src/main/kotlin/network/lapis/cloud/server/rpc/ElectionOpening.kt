package network.lapis.cloud.server.rpc

import network.lapis.cloud.server.db.DbClock
import network.lapis.cloud.server.db.generated.CommitteeMembershipTable
import network.lapis.cloud.server.db.generated.CommitteeTable
import network.lapis.cloud.server.db.generated.ElectionBoardMemberTable
import network.lapis.cloud.server.db.generated.ElectionEligibleVoterTable
import network.lapis.cloud.server.db.generated.ElectionOptionTable
import network.lapis.cloud.server.db.generated.ElectionTable
import network.lapis.cloud.server.db.generated.MeetingTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.server.db.generated.MotionTable
import network.lapis.cloud.server.security.CurrentMember
import network.lapis.cloud.server.security.canManageElection
import network.lapis.cloud.server.security.isElectionBoard
import network.lapis.cloud.server.tender.requireNotTenderAwardMotion
import network.lapis.cloud.shared.domain.CommitteeType
import network.lapis.cloud.shared.domain.ElectionAnswer
import network.lapis.cloud.shared.domain.ElectionOpenInput
import network.lapis.cloud.shared.domain.ElectionStatus
import network.lapis.cloud.shared.domain.ElectionType
import network.lapis.cloud.shared.domain.MotionStatus
import network.lapis.cloud.shared.rpc.ConflictException
import network.lapis.cloud.shared.rpc.ForbiddenException
import network.lapis.cloud.shared.rpc.NotFoundException
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.exceptions.ExposedSQLException
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import kotlin.uuid.Uuid

/*
 * V1.9.238 -- the bodies of `ElectionService.openElection`, `appointElectionBoard` and `openVoting` (their transaction parts), moved here
 * UNCHANGED (same checks, same order, same messages, same locks) so the Sitzung mode in the conference room can run all three in ONE
 * transaction (`MeetingSessionMotionOperations.openVote`). `ElectionService` delegates here; its tests are the safety net.
 */

/**
 * Server-side floor on [ElectionOpenInput.tallyThreshold] -- at least TWO named Vier-Augen approvals must be required: with a floor of one, a
 * single board member could release the tally on their own. Mirrors the database CHECK `ck_election_tally_threshold`. The ONE copy of these
 * limits: `ElectionService` and the Sitzung mode (`MeetingSessionMotionOperations`) use it too.
 */
internal const val MIN_TALLY_THRESHOLD = 2
internal const val MIN_ELECTION_BOARD_SIZE = 3
internal const val MAX_ELECTION_BOARD_SIZE = 25

internal fun String.parseUuidOrNotFound(kind: String): Uuid =
    runCatching { Uuid.parse(this) }.getOrElse { throw NotFoundException("Invalid $kind id: $this") }

/**
 * Everything that must hold before a motion may enter the election path: no election yet (a tie leaves a TALLIED one), no OPEN/CLOSED
 * meritocratic Vote, no running or BINDING evaluated SystemicConsensus, not a tender award motion, and (main motions) no pending amendment.
 * V1.9.54: with a pending amendment the amendment could be adopted while the vote runs and change `currentText` under the voters; together with
 * submitMotion's refusal (no amendment during a running path) a running path of a main motion never has a pending amendment.
 * The ONE copy: [openElectionLocked] and the Sitzung mode's attach (so it never leaves a SCHEDULED motion whose vote button always conflicts).
 */
internal fun requireElectionPathAvailable(
    motionId: Uuid,
    isAmendment: Boolean,
) {
    MotionDecisionLock.requireNoElection(motionId)
    MotionDecisionLock.requireNoOpenVote(motionId)
    MotionDecisionLock.requireNoActiveSystemicConsensus(motionId)
    // V1.9.201: the award motion of a tender is decided by the quorum or the meritocratic vote only (bidders are excluded there).
    requireNotTenderAwardMotion(motionId)
    if (!isAmendment) requireNoPendingAmendments(motionId)
}

/** Body of `openElection` after the motion lock: [motionRow] is the LOCKED motion row. Returns the new election id. */
internal fun openElectionLocked(
    current: CurrentMember,
    motionRow: ResultRow,
    input: ElectionOpenInput,
): Uuid {
    val aId = motionRow[MotionTable.id]
    val committeeId = motionRow[MotionTable.targetCommitteeId]
    if (!current.canManageElection(committeeId)) throw ForbiddenException()
    if (motionRow[MotionTable.status] != MotionStatus.SCHEDULED) {
        throw ConflictException("Motion ${input.motionId} is ${motionRow[MotionTable.status]}, expected SCHEDULED")
    }
    val sId = motionRow[MotionTable.meetingId] ?: throw ConflictException("Motion ${input.motionId} has no scheduled Meeting")

    // V1.9.54 / V1.9.201: the one set of preconditions for entering the election path -- shared with the Sitzung mode's attach.
    requireElectionPathAvailable(motionId = aId, isAmendment = motionRow[MotionTable.amendsMotionId] != null)

    if (input.electionType == ElectionType.LIST_VOTE || input.electionType == ElectionType.RANKED_CHOICE) {
        throw ConflictException("${input.electionType} is reserved for forward compatibility and not supported in V0.2.4")
    }
    if (input.requiredMajorityPercent !in 1..100) {
        throw ConflictException("requiredMajorityPercent must be in 1..100, got ${input.requiredMajorityPercent}")
    }
    val majorityFraction =
        try {
            ElectionMajority.normalize(
                numerator = input.requiredMajorityNumerator,
                denominator = input.requiredMajorityDenominator,
            )
        } catch (e: IllegalArgumentException) {
            throw ConflictException(e.message ?: "invalid required majority")
        }
    if (majorityFraction != null && input.electionType == ElectionType.MULTI_CHOICE) {
        throw ConflictException("A required majority fraction does not apply to ElectionType.MULTI_CHOICE (plurality)")
    }
    if (input.tallyThreshold !in MIN_TALLY_THRESHOLD..MAX_ELECTION_BOARD_SIZE) {
        throw ConflictException(
            "tallyThreshold must be between $MIN_TALLY_THRESHOLD and $MAX_ELECTION_BOARD_SIZE, got ${input.tallyThreshold}",
        )
    }

    val targetCommitteeId = input.targetCommitteeId?.parseUuidOrNotFound("Committee")
    when (input.electionType) {
        ElectionType.YES_NO -> {
            if (targetCommitteeId != null) {
                throw ConflictException("targetCommitteeId must be null for ElectionType.YES_NO, which seats nobody")
            }
            // V1.9.103: a YES_NO election seats nobody, so neither a role nor a policy for previous holders makes sense.
            if (input.incumbentPolicy != null || input.targetRole != null) {
                throw ConflictException("targetRole and incumbentPolicy must be null for ElectionType.YES_NO, which seats nobody")
            }
        }
        ElectionType.SINGLE_CHOICE, ElectionType.MULTI_CHOICE -> {
            if (targetCommitteeId == null) {
                throw ConflictException("targetCommitteeId is required for ElectionType.${input.electionType}")
            }
            // V1.9.112: locked FOR UPDATE (motion row first, then this committee -- the rule switch only ever locks committees),
            // so an election cannot be opened against a committee at the very moment it is being switched to rule mode.
            val targetCommitteeRow =
                CommitteeTable
                    .selectAll()
                    .where { CommitteeTable.id eq targetCommitteeId }
                    .forUpdate()
                    .singleOrNull()
                    ?: throw NotFoundException("Committee ${input.targetCommitteeId} not found")
            // V1.9.112: a rule-based committee has no seats -- nobody can be elected into it.
            if (isRuleCommittee(targetCommitteeRow)) throw ConflictException(RULE_COMMITTEE_NO_ELECTION)
            requireSuccessionInputs(
                input = input,
                targetCommitteeId = targetCommitteeId,
                targetCommitteeType = targetCommitteeRow[CommitteeTable.type],
                motionCommitteeId = committeeId,
            )
            if (input.electionType == ElectionType.SINGLE_CHOICE && input.seatCount != 1) {
                throw ConflictException("seatCount must be 1 for ElectionType.SINGLE_CHOICE, got ${input.seatCount}")
            }
            if (input.seatCount < 1) {
                throw ConflictException("seatCount must be at least 1, got ${input.seatCount}")
            }
        }
        ElectionType.LIST_VOTE, ElectionType.RANKED_CHOICE -> Unit // unreachable, rejected above
    }

    val id = Uuid.random()
    val now = DbClock.nowLocalDateTime()
    // The unique index on active_motion_id is the DB-level backstop behind the motion lock above.
    // A violation is translated right here (inside the transaction) so Exposed does not retry the block.
    try {
        ElectionTable.insert {
            it[ElectionTable.id] = id
            it[ElectionTable.motionId] = aId
            it[ElectionTable.activeMotionId] = aId
            it[ElectionTable.meetingId] = sId
            it[title] = motionRow[MotionTable.title]
            it[electionType] = input.electionType
            it[secret] = input.secret
            it[seatCount] = input.seatCount
            it[ElectionTable.targetCommitteeId] = targetCommitteeId
            it[targetRole] = input.targetRole
            it[incumbentPolicy] = input.incumbentPolicy
            it[requiredMajorityPercent] =
                if (majorityFraction !=
                    null
                ) {
                    ElectionMajority.percentForLegacyDisplay(majorityFraction)
                } else {
                    input.requiredMajorityPercent
                }
            it[requiredMajorityNumerator] = majorityFraction?.numerator
            it[requiredMajorityDenominator] = majorityFraction?.denominator
            it[status] = ElectionStatus.PREPARATION
            it[openedBy] = current.memberId
            it[openedAt] = now
            it[candidateListApprovedAt] = null
            it[votingOpenedAt] = null
            it[votingClosedAt] = null
            it[tallyThreshold] = input.tallyThreshold
            it[tallyRunAt] = null
            it[resolutionId] = null
        }
    } catch (e: ExposedSQLException) {
        throw ConflictException("Motion ${input.motionId} already has an active Election")
    }
    if (input.electionType == ElectionType.YES_NO) {
        listOf(ElectionAnswer.YES, ElectionAnswer.NO, ElectionAnswer.ABSTAIN).forEachIndexed { index, answer ->
            ElectionOptionTable.insert {
                it[ElectionOptionTable.id] = Uuid.random()
                it[ElectionOptionTable.electionId] = id
                it[label] = answer.name
                it[position] = index
                it[candidacyId] = null
            }
        }
    }
    return id
}

/** Body of `appointElectionBoard` after the election lock: [electionRow] is the LOCKED election row. */
internal fun appointElectionBoardLocked(
    current: CurrentMember,
    electionRow: ResultRow,
    memberIds: List<String>,
) {
    val wId = electionRow[ElectionTable.id]
    if (!current.canManageElection(requireMotionCommitteeId(electionRow[ElectionTable.motionId]))) throw ForbiddenException()
    if (electionRow[ElectionTable.status] != ElectionStatus.PREPARATION) {
        throw ConflictException("Election $wId is ${electionRow[ElectionTable.status]}, expected PREPARATION")
    }
    val distinctIds = memberIds.map { it.parseUuidOrNotFound("Member") }.distinct()
    if (distinctIds.size < MIN_ELECTION_BOARD_SIZE || distinctIds.size > MAX_ELECTION_BOARD_SIZE) {
        throw ConflictException(
            "appointElectionBoard requires between $MIN_ELECTION_BOARD_SIZE and " +
                "$MAX_ELECTION_BOARD_SIZE distinct members, got ${distinctIds.size}",
        )
    }
    distinctIds.forEach { mId ->
        MemberTable.selectAll().where { MemberTable.id eq mId }.singleOrNull()
            ?: throw NotFoundException("Member $mId not found")
        // ANTRAG membership-gate audit follow-up (2026-07-30): appointElectionBoard shares
        // the same seating-without-status-recheck gap class as
        // GovernanceService.addCommitteeMember -- an election-board seat grants
        // isElectionBoardMember/isElectionBoard authority (Vier-Augen tally-approval
        // counting via approveTally, and operational control via
        // openVoting/closeVoting/tally for a non-privileged appointee). Checked per
        // appointed member, not the caller (caller's canManageElection is already checked
        // above). forUpdate = true (round-3 audit fix, 2026-07-30): this check gates an
        // ElectionBoardMemberTable insert further down in this SAME transaction -- without
        // the row lock, a concurrent leaveMembership() could commit in between, appointing
        // a board member from an already-stale status read. See requireActiveMembership
        // KDoc "forUpdate".
        requireActiveMembership(memberId = mId, forUpdate = true)
    }
    val targetCommitteeId = electionRow[ElectionTable.targetCommitteeId]
    if (targetCommitteeId != null) {
        val targetCommitteeType =
            CommitteeTable
                .selectAll()
                .where {
                    CommitteeTable.id eq targetCommitteeId
                }.single()[CommitteeTable.type]
        if (targetCommitteeType == CommitteeType.EXECUTIVE_BOARD) {
            val executiveBoardMemberIds =
                CommitteeMembershipTable
                    .selectAll()
                    .where {
                        (CommitteeMembershipTable.committeeId eq targetCommitteeId) and (CommitteeMembershipTable.until.isNull())
                    }.map { it[CommitteeMembershipTable.memberId] }
                    .toSet()
            val conflicting = distinctIds.filter { it in executiveBoardMemberIds }
            if (conflicting.isNotEmpty()) {
                throw ConflictException(
                    "Members $conflicting are active members of target Executive Board Committee $targetCommitteeId and cannot " +
                        "also serve on its election board",
                )
            }
        }
    }
    ElectionBoardMemberTable.deleteWhere { ElectionBoardMemberTable.electionId eq wId }
    val now = DbClock.nowLocalDateTime()
    distinctIds.forEach { mId ->
        ElectionBoardMemberTable.insert {
            it[ElectionBoardMemberTable.id] = Uuid.random()
            it[ElectionBoardMemberTable.electionId] = wId
            it[ElectionBoardMemberTable.memberId] = mId
            it[appointedAt] = now
        }
    }
}

/** Body of `openVoting`'s transaction after the election lock: [electionRow] is the LOCKED election row. */
internal fun openVotingLocked(
    current: CurrentMember,
    electionRow: ResultRow,
): OpenVotingPrep {
    val wId = electionRow[ElectionTable.id]
    if (!current.isElectionBoard(wId)) throw ForbiddenException()
    val expectedStatus =
        if (electionRow[ElectionTable.electionType] ==
            ElectionType.YES_NO
        ) {
            ElectionStatus.PREPARATION
        } else {
            ElectionStatus.CANDIDATE_LIST_RELEASED
        }
    if (electionRow[ElectionTable.status] != expectedStatus) {
        throw ConflictException("Election $wId is ${electionRow[ElectionTable.status]}, expected $expectedStatus")
    }
    // V1.9.22 guard: an election whose appointed board is smaller than the minimum or smaller
    // than the required number of tally approvals could never be tallied. Checked BEFORE the
    // electorate snapshot below so a rejection writes nothing.
    val boardSize = ElectionBoardMemberTable.selectAll().where { ElectionBoardMemberTable.electionId eq wId }.count()
    val requiredApprovals = electionRow[ElectionTable.tallyThreshold]
    if (boardSize < MIN_ELECTION_BOARD_SIZE || boardSize < requiredApprovals) {
        throw ConflictException(
            "Election $wId has an election board of $boardSize, needs at least " +
                "${maxOf(MIN_ELECTION_BOARD_SIZE, requiredApprovals)} before voting can open",
        )
    }
    val committeeId = requireMotionCommitteeId(electionRow[ElectionTable.motionId])
    val meetingId = electionRow[ElectionTable.meetingId]
    val secret = electionRow[ElectionTable.secret]
    val meetingRow = MeetingTable.selectAll().where { MeetingTable.id eq meetingId }.single()
    val scheduledDate = meetingRow[MeetingTable.scheduledAt].date
    val committeeRow = CommitteeTable.selectAll().where { CommitteeTable.id eq committeeId }.single()
    val eligible = eligibleMemberIds(committeeRow = committeeRow, scheduledDate = scheduledDate)
    eligible.forEach { mId ->
        ElectionEligibleVoterTable.insert {
            it[ElectionEligibleVoterTable.id] = Uuid.random()
            it[ElectionEligibleVoterTable.electionId] = wId
            it[ElectionEligibleVoterTable.memberId] = mId
        }
    }
    // Room lock happens HERE -- after the (potentially huge) snapshot insert above, and
    // directly before the status flip below -- see this method's own KDoc addition above
    // (Stolperfalle §9.7). Only acquired for a secret Election at all (D2/D5 -- the whole
    // point of this wave is scoped to Election+SystemicConsensus's SECRET path).
    val affectedRooms =
        if (!secret) {
            emptyList()
        } else {
            SecretBallotStreamLock.lockRooms(SecretBallotStreamLock.roomIdsForMeeting(meetingId))
        }
    ElectionTable.update({ ElectionTable.id eq wId }) {
        it[status] = ElectionStatus.OPEN
        it[votingOpenedAt] = DbClock.nowLocalDateTime()
    }
    // Flips every STARTING/LIVE stream of the affected rooms to PAUSING (DB-only, no
    // network call here). openVoting itself writes no audit-log entry of its own, but
    // markPausingForSecretBallot writes one AuditLogRecorder.record per affected stream
    // internally -- called LAST in this transaction (nothing else takes a lock afterwards)
    // to respect AuditLogRecorder's own deadlock-avoidance contract (Stolperfalle §9.8).
    if (secret) {
        ConferenceStreamPauseCoordinator.markPausingForSecretBallot(
            roomIds = affectedRooms,
            actorMemberId = current.memberId,
            actorRole = current.role,
        )
    }
    return OpenVotingPrep(meetingId = meetingId, secret = secret)
}
