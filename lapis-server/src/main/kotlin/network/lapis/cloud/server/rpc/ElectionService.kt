package network.lapis.cloud.server.rpc

import io.ktor.server.application.ApplicationCall
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import network.lapis.cloud.server.db.generated.CommitteeMembershipTable
import network.lapis.cloud.server.db.generated.CommitteeTable
import network.lapis.cloud.server.db.generated.ElectionBallotSelectionTable
import network.lapis.cloud.server.db.generated.ElectionBallotTable
import network.lapis.cloud.server.db.generated.ElectionBoardMemberTable
import network.lapis.cloud.server.db.generated.ElectionCandidacyTable
import network.lapis.cloud.server.db.generated.ElectionEligibleVoterTable
import network.lapis.cloud.server.db.generated.ElectionOptionTable
import network.lapis.cloud.server.db.generated.ElectionParticipationTable
import network.lapis.cloud.server.db.generated.ElectionTable
import network.lapis.cloud.server.db.generated.ElectionTallyApprovalTable
import network.lapis.cloud.server.db.generated.MeetingTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.server.db.generated.MotionTable
import network.lapis.cloud.server.security.canManageElection
import network.lapis.cloud.server.security.canStandAsCandidate
import network.lapis.cloud.server.security.isElectionBoard
import network.lapis.cloud.server.security.isElectionBoardMember
import network.lapis.cloud.server.security.resolveCurrentMember
import network.lapis.cloud.shared.domain.CandidacyDto
import network.lapis.cloud.shared.domain.CandidacyInput
import network.lapis.cloud.shared.domain.CommitteeRole
import network.lapis.cloud.shared.domain.CommitteeType
import network.lapis.cloud.shared.domain.ElectionAnswer
import network.lapis.cloud.shared.domain.ElectionBallotCastResultDto
import network.lapis.cloud.shared.domain.ElectionBallotDto
import network.lapis.cloud.shared.domain.ElectionBallotInput
import network.lapis.cloud.shared.domain.ElectionBoardMemberDto
import network.lapis.cloud.shared.domain.ElectionDto
import network.lapis.cloud.shared.domain.ElectionOpenInput
import network.lapis.cloud.shared.domain.ElectionOptionDto
import network.lapis.cloud.shared.domain.ElectionResultDto
import network.lapis.cloud.shared.domain.ElectionStatus
import network.lapis.cloud.shared.domain.ElectionType
import network.lapis.cloud.shared.domain.MotionStatus
import network.lapis.cloud.shared.domain.ReceiptVerificationDto
import network.lapis.cloud.shared.domain.ResolutionInput
import network.lapis.cloud.shared.domain.ResolutionMode
import network.lapis.cloud.shared.domain.ResolutionStatus
import network.lapis.cloud.shared.rpc.ConflictException
import network.lapis.cloud.shared.rpc.ForbiddenException
import network.lapis.cloud.shared.rpc.IElectionService
import network.lapis.cloud.shared.rpc.NotFoundException
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.neq
import org.jetbrains.exposed.v1.exceptions.ExposedSQLException
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import java.security.SecureRandom
import java.util.Base64
import kotlin.time.Clock
import kotlin.uuid.Uuid

/** Server-side floor on [ElectionOpenInput.tallyThreshold] -- at least one named Vier-Augen approval must be required. */
private const val MIN_TALLY_THRESHOLD = 1
private const val MIN_ELECTION_BOARD_SIZE = 3
private const val MAX_ELECTION_BOARD_SIZE = 25
private const val RECEIPT_CODE_BYTES = 20 // 160 bits, comfortably above the >=128-bit KDoc floor.
private const val RECEIPT_CODE_MAX_ATTEMPTS = 5

private val secureRandom = SecureRandom()

/**
 * One [BoardMembershipEvents.recordBoardJoin] result from [ElectionService.tally]'s `EXECUTIVE_BOARD`
 * winner-seating branch, collected during the seating loop and audited only once, right at the end
 * of the transaction (after [network.lapis.cloud.server.db.generated.MotionTable]/
 * [network.lapis.cloud.server.db.generated.ElectionTable] are updated) -- see [auditBoardMembershipCreate]
 * KDoc for why the audit call itself cannot happen inside the loop.
 */
private data class SeatedBoardMembership(
    val id: Uuid,
    val memberId: Uuid,
    val role: CommitteeRole,
    val startedAt: LocalDate,
)

/**
 * Demokratische Electionen (V0.2.4): one-person-one-vote elections/ballots. Implements [IElectionService]
 * -- see that interface's KDoc for the full lifecycle
 * (`openElection` -> `appointElectionBoard`/`submitCandidacy` -> `releaseCandidateList` ->
 * `openVoting` -> `castElectionBallot` -> `closeVoting` -> `approveTally` -> `tally`) and
 * `03 Bereiche/Lapis Cloud/Demokratische Electionen.md` for the concept document. Reuses
 * [insertResolutionRow]/[computeQuorum] (extracted to `ResolutionBook.kt` in this same wave) and
 * [eligibleMemberIds] (extracted to `CommitteeEligibility.kt`) so a Election's tally lands in the same
 * resolution book [GovernanceService] writes to, tagged [ResolutionMode.DEMOCRATIC].
 *
 * Same "simple-transaction" style as [GovernanceService]: follow-up queries per row
 * ([memberDisplayName], option vote counts) rather than aliased multi-joins.
 */
class ElectionService(
    private val call: ApplicationCall,
) : IElectionService {
    override suspend fun openElection(input: ElectionOpenInput): ElectionDto {
        val current = resolveCurrentMember(call)
        val aId = input.motionId.toUuidOrNotFound("Motion")
        return transaction {
            val motionRow =
                MotionTable.selectAll().where { MotionTable.id eq aId }.singleOrNull()
                    ?: throw NotFoundException("Motion ${input.motionId} not found")
            val committeeId = motionRow[MotionTable.targetCommitteeId]
            if (!current.canManageElection(committeeId)) throw ForbiddenException()
            if (motionRow[MotionTable.status] != MotionStatus.SCHEDULED) {
                throw ConflictException("Motion ${input.motionId} is ${motionRow[MotionTable.status]}, expected SCHEDULED")
            }
            val sId = motionRow[MotionTable.meetingId] ?: throw ConflictException("Motion ${input.motionId} has no scheduled Meeting")

            val hasActiveElection =
                ElectionTable
                    .selectAll()
                    .where { (ElectionTable.motionId eq aId) and (ElectionTable.status neq ElectionStatus.ABORTED) }
                    .count() > 0
            if (hasActiveElection) {
                throw ConflictException("Motion ${input.motionId} already has an open or resolved Election")
            }

            if (input.electionType == ElectionType.LIST_VOTE || input.electionType == ElectionType.RANKED_CHOICE) {
                throw ConflictException("${input.electionType} is reserved for forward compatibility and not supported in V0.2.4")
            }
            if (input.requiredMajorityPercent !in 1..100) {
                throw ConflictException("requiredMajorityPercent must be in 1..100, got ${input.requiredMajorityPercent}")
            }
            if (input.tallyThreshold < MIN_TALLY_THRESHOLD) {
                throw ConflictException("tallyThreshold must be at least $MIN_TALLY_THRESHOLD")
            }

            val targetCommitteeId = input.targetCommitteeId?.toUuidOrNotFound("Committee")
            when (input.electionType) {
                ElectionType.YES_NO -> {
                    if (targetCommitteeId != null) {
                        throw ConflictException("targetCommitteeId must be null for ElectionType.YES_NO, which seats nobody")
                    }
                }
                ElectionType.SINGLE_CHOICE, ElectionType.MULTI_CHOICE -> {
                    if (targetCommitteeId == null) {
                        throw ConflictException("targetCommitteeId is required for ElectionType.${input.electionType}")
                    }
                    CommitteeTable.selectAll().where { CommitteeTable.id eq targetCommitteeId }.singleOrNull()
                        ?: throw NotFoundException("Committee ${input.targetCommitteeId} not found")
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
            val now = nowLocalDateTime()
            ElectionTable.insert {
                it[ElectionTable.id] = id
                it[ElectionTable.motionId] = aId
                it[ElectionTable.meetingId] = sId
                it[title] = motionRow[MotionTable.title]
                it[electionType] = input.electionType
                it[secret] = input.secret
                it[seatCount] = input.seatCount
                it[ElectionTable.targetCommitteeId] = targetCommitteeId
                it[targetRole] = input.targetRole
                it[requiredMajorityPercent] = input.requiredMajorityPercent
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
            loadElection(id)
        }
    }

    override suspend fun appointElectionBoard(
        electionId: String,
        memberIds: List<String>,
    ): List<ElectionBoardMemberDto> {
        val current = resolveCurrentMember(call)
        val wId = electionId.toUuidOrNotFound("Election")
        return transaction {
            val electionRow = requireElectionRow(wId)
            if (!current.canManageElection(requireMotionCommitteeId(electionRow[ElectionTable.motionId]))) throw ForbiddenException()
            if (electionRow[ElectionTable.status] != ElectionStatus.PREPARATION) {
                throw ConflictException("Election $electionId is ${electionRow[ElectionTable.status]}, expected PREPARATION")
            }
            val distinctIds = memberIds.map { it.toUuidOrNotFound("Member") }.distinct()
            if (distinctIds.size < MIN_ELECTION_BOARD_SIZE || distinctIds.size > MAX_ELECTION_BOARD_SIZE) {
                throw ConflictException(
                    "appointElectionBoard requires between $MIN_ELECTION_BOARD_SIZE and " +
                        "$MAX_ELECTION_BOARD_SIZE distinct members, got ${distinctIds.size}",
                )
            }
            distinctIds.forEach { mId ->
                MemberTable.selectAll().where { MemberTable.id eq mId }.singleOrNull()
                    ?: throw NotFoundException("Member $mId not found")
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
            val now = nowLocalDateTime()
            distinctIds.forEach { mId ->
                ElectionBoardMemberTable.insert {
                    it[ElectionBoardMemberTable.id] = Uuid.random()
                    it[ElectionBoardMemberTable.electionId] = wId
                    it[ElectionBoardMemberTable.memberId] = mId
                    it[appointedAt] = now
                }
            }
            loadElectionBoard(wId)
        }
    }

    override suspend fun listElectionBoard(electionId: String): List<ElectionBoardMemberDto> {
        resolveCurrentMember(call)
        val wId = electionId.toUuidOrNotFound("Election")
        return transaction {
            requireElectionRow(wId)
            loadElectionBoard(wId)
        }
    }

    override suspend fun submitCandidacy(
        electionId: String,
        input: CandidacyInput,
    ): CandidacyDto {
        val current = resolveCurrentMember(call)
        if (!current.canStandAsCandidate()) throw ForbiddenException()
        val wId = electionId.toUuidOrNotFound("Election")
        return transaction {
            val electionRow = requireElectionRow(wId)
            if (electionRow[ElectionTable.status] != ElectionStatus.PREPARATION) {
                throw ConflictException("Election $electionId is ${electionRow[ElectionTable.status]}, expected PREPARATION")
            }
            val electionType = electionRow[ElectionTable.electionType]
            if (electionType != ElectionType.SINGLE_CHOICE && electionType != ElectionType.MULTI_CHOICE) {
                throw ConflictException("Election $electionId is $electionType, Candidacies only apply to SINGLE_CHOICE/MULTI_CHOICE")
            }
            val hasActiveCandidacy =
                ElectionCandidacyTable
                    .selectAll()
                    .where {
                        (ElectionCandidacyTable.electionId eq wId) and
                            (ElectionCandidacyTable.memberId eq current.memberId) and
                            (ElectionCandidacyTable.withdrawnAt.isNull())
                    }.count() > 0
            if (hasActiveCandidacy) {
                throw ConflictException(
                    "Member ${current.memberId} already has an active Candidacy for Election $electionId",
                )
            }
            val id = Uuid.random()
            ElectionCandidacyTable.insert {
                it[ElectionCandidacyTable.id] = id
                it[ElectionCandidacyTable.electionId] = wId
                it[ElectionCandidacyTable.memberId] = current.memberId
                it[motivationText] = input.motivationText
                it[submittedAt] = nowLocalDateTime()
                it[withdrawnAt] = null
            }
            loadCandidacy(id)
        }
    }

    override suspend fun withdrawCandidacy(id: String): CandidacyDto {
        val current = resolveCurrentMember(call)
        val kId = id.toUuidOrNotFound("Candidacy")
        return transaction {
            val row =
                ElectionCandidacyTable.selectAll().where { ElectionCandidacyTable.id eq kId }.singleOrNull()
                    ?: throw NotFoundException("Candidacy $id not found")
            val wId = row[ElectionCandidacyTable.electionId]
            val electionRow = requireElectionRow(wId)
            val candidateId = row[ElectionCandidacyTable.memberId]
            val selfWhileVorbereitung = current.memberId == candidateId && electionRow[ElectionTable.status] == ElectionStatus.PREPARATION
            val committeeId = requireMotionCommitteeId(electionRow[ElectionTable.motionId])
            if (!selfWhileVorbereitung && !current.canManageElection(committeeId)) throw ForbiddenException()
            if (row[ElectionCandidacyTable.withdrawnAt] != null) throw ConflictException("Candidacy $id already withdrawn")
            ElectionCandidacyTable.update({ ElectionCandidacyTable.id eq kId }) {
                it[withdrawnAt] = nowLocalDateTime()
            }
            loadCandidacy(kId)
        }
    }

    override suspend fun listCandidacies(electionId: String): List<CandidacyDto> {
        resolveCurrentMember(call)
        val wId = electionId.toUuidOrNotFound("Election")
        return transaction {
            requireElectionRow(wId)
            ElectionCandidacyTable
                .selectAll()
                .where { ElectionCandidacyTable.electionId eq wId }
                .map { it.toCandidacyDto() }
        }
    }

    override suspend fun releaseCandidateList(electionId: String): ElectionDto {
        val current = resolveCurrentMember(call)
        val wId = electionId.toUuidOrNotFound("Election")
        return transaction {
            val electionRow = requireElectionRow(wId)
            if (!current.canManageElection(requireMotionCommitteeId(electionRow[ElectionTable.motionId]))) throw ForbiddenException()
            if (electionRow[ElectionTable.status] != ElectionStatus.PREPARATION) {
                throw ConflictException("Election $electionId is ${electionRow[ElectionTable.status]}, expected PREPARATION")
            }
            val candidacies =
                ElectionCandidacyTable
                    .selectAll()
                    .where { (ElectionCandidacyTable.electionId eq wId) and (ElectionCandidacyTable.withdrawnAt.isNull()) }
                    .orderBy(ElectionCandidacyTable.submittedAt, SortOrder.ASC)
                    .toList()
            if (candidacies.isEmpty()) throw ConflictException("Election $electionId has no non-withdrawn Candidacy to release")
            candidacies.forEachIndexed { index, row ->
                val label = memberDisplayName(row[ElectionCandidacyTable.memberId]).orEmpty()
                ElectionOptionTable.insert {
                    it[ElectionOptionTable.id] = Uuid.random()
                    it[ElectionOptionTable.electionId] = wId
                    it[ElectionOptionTable.label] = label
                    it[position] = index
                    it[candidacyId] = row[ElectionCandidacyTable.id]
                }
            }
            ElectionTable.update({ ElectionTable.id eq wId }) {
                it[status] = ElectionStatus.CANDIDATE_LIST_RELEASED
                it[candidateListApprovedAt] = nowLocalDateTime()
            }
            loadElection(wId)
        }
    }

    override suspend fun openVoting(electionId: String): ElectionDto {
        val current = resolveCurrentMember(call)
        val wId = electionId.toUuidOrNotFound("Election")
        return transaction {
            val electionRow = requireElectionRow(wId)
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
                throw ConflictException("Election $electionId is ${electionRow[ElectionTable.status]}, expected $expectedStatus")
            }
            val committeeId = requireMotionCommitteeId(electionRow[ElectionTable.motionId])
            val meetingRow = MeetingTable.selectAll().where { MeetingTable.id eq electionRow[ElectionTable.meetingId] }.single()
            val scheduledDate = meetingRow[MeetingTable.scheduledAt].date
            val committeeRow = CommitteeTable.selectAll().where { CommitteeTable.id eq committeeId }.single()
            val eligible = eligibleMemberIds(committeeRow, scheduledDate)
            eligible.forEach { mId ->
                ElectionEligibleVoterTable.insert {
                    it[ElectionEligibleVoterTable.id] = Uuid.random()
                    it[ElectionEligibleVoterTable.electionId] = wId
                    it[ElectionEligibleVoterTable.memberId] = mId
                }
            }
            ElectionTable.update({ ElectionTable.id eq wId }) {
                it[status] = ElectionStatus.OPEN
                it[votingOpenedAt] = nowLocalDateTime()
            }
            loadElection(wId)
        }
    }

    override suspend fun castElectionBallot(input: ElectionBallotInput): ElectionBallotCastResultDto {
        val current = resolveCurrentMember(call)
        val wId = input.electionId.toUuidOrNotFound("Election")
        return transaction {
            val electionRow = requireElectionRow(wId)
            if (electionRow[ElectionTable.status] != ElectionStatus.OPEN) {
                throw ConflictException("Election ${input.electionId} is ${electionRow[ElectionTable.status]}, expected OPEN")
            }
            val eligible =
                ElectionEligibleVoterTable
                    .selectAll()
                    .where { (ElectionEligibleVoterTable.electionId eq wId) and (ElectionEligibleVoterTable.memberId eq current.memberId) }
                    .count() > 0
            if (!eligible) throw ForbiddenException()

            val electionType = electionRow[ElectionTable.electionType]
            val selectedOptionIds: List<Uuid> =
                if (electionType == ElectionType.YES_NO) {
                    val answer = input.answer
                    if (answer == null || input.selectedOptionIds.isNotEmpty()) {
                        throw ConflictException("A YES_NO ballot must set answer and leave selectedOptionIds empty")
                    }
                    val optionRow =
                        ElectionOptionTable
                            .selectAll()
                            .where { (ElectionOptionTable.electionId eq wId) and (ElectionOptionTable.label eq answer.name) }
                            .single()
                    listOf(optionRow[ElectionOptionTable.id])
                } else {
                    if (input.answer != null) {
                        throw ConflictException("A personnel ballot must leave answer null")
                    }
                    val distinctSelections = input.selectedOptionIds.map { it.toUuidOrNotFound("ElectionOption") }.distinct()
                    if (distinctSelections.size != input.selectedOptionIds.size) {
                        throw ConflictException("selectedOptionIds must not contain duplicates")
                    }
                    if (distinctSelections.isEmpty() || distinctSelections.size > electionRow[ElectionTable.seatCount]) {
                        throw ConflictException(
                            "selectedOptionIds must contain between 1 and ${electionRow[ElectionTable.seatCount]} distinct options, " +
                                "got ${distinctSelections.size}",
                        )
                    }
                    val validOptionIds =
                        ElectionOptionTable
                            .selectAll()
                            .where { ElectionOptionTable.electionId eq wId }
                            .map { it[ElectionOptionTable.id] }
                            .toSet()
                    if (!validOptionIds.containsAll(distinctSelections)) {
                        throw ConflictException("selectedOptionIds must all belong to Election ${input.electionId}")
                    }
                    distinctSelections
                }

            val secret = electionRow[ElectionTable.secret]
            val now = nowLocalDateTime()
            // De-anonymization guard: election_participation.voted_at (carries member_id) and
            // election_ballot.cast_at (the anonymous ballot) must NOT be derived from the same
            // instant for a secret Election -- ballots are serialized, so a bit-identical timestamp on
            // both rows would let anyone join `voted_at = cast_at` and re-link every "secret"
            // ballot back to its voter, defeating the whole point of ElectionParticipationTable being the
            // only member-bearing row on this path (see ElectionTables KDoc). Coarsening the ballot's
            // own timestamp down to the day (calendar date, no time-of-day) breaks that 1:1 join --
            // many ballots cast on the same day for the same Election now share an identical cast_at,
            // while voted_at keeps full precision for legitimate "did this member vote" audit
            // purposes. Non-secret ballots have no anonymity to protect (member_id is stored
            // in the clear on election_ballot itself), so they keep full timestamp precision.
            val castAt = if (secret) LocalDateTime(now.date, LocalTime(0, 0)) else now
            try {
                if (secret) {
                    val alreadyVoted =
                        ElectionParticipationTable
                            .selectAll()
                            .where {
                                (ElectionParticipationTable.electionId eq wId) and
                                    (ElectionParticipationTable.memberId eq current.memberId)
                            }.count() > 0
                    if (alreadyVoted) throw ConflictException("Member ${current.memberId} already voted in Election ${input.electionId}")
                    ElectionParticipationTable.insert {
                        it[ElectionParticipationTable.id] = Uuid.random()
                        it[ElectionParticipationTable.electionId] = wId
                        it[ElectionParticipationTable.memberId] = current.memberId
                        it[votedAt] = now
                    }
                } else {
                    val alreadyVoted =
                        ElectionBallotTable
                            .selectAll()
                            .where { (ElectionBallotTable.electionId eq wId) and (ElectionBallotTable.memberId eq current.memberId) }
                            .count() > 0
                    if (alreadyVoted) throw ConflictException("Member ${current.memberId} already voted in Election ${input.electionId}")
                }

                val ballotId = Uuid.random()
                val receiptCode = generateUniqueReceiptCode(wId)
                ElectionBallotTable.insert {
                    it[ElectionBallotTable.id] = ballotId
                    it[ElectionBallotTable.electionId] = wId
                    it[ElectionBallotTable.memberId] = if (secret) null else current.memberId
                    it[ElectionBallotTable.receiptCode] = receiptCode
                    it[ElectionBallotTable.castAt] = castAt
                }
                selectedOptionIds.forEach { optionId ->
                    ElectionBallotSelectionTable.insert {
                        it[ElectionBallotSelectionTable.id] = Uuid.random()
                        it[ElectionBallotSelectionTable.ballotId] = ballotId
                        it[ElectionBallotSelectionTable.optionId] = optionId
                    }
                }
                ElectionBallotCastResultDto(
                    id = ballotId.toString(),
                    castAt = castAt,
                    receiptCode = if (secret) receiptCode else null,
                )
            } catch (e: ExposedSQLException) {
                // The application-level pre-checks above are racy under concurrency on their own;
                // the DB-level UNIQUE(election_id, member_id) constraint (on election_participation for the
                // secret path, on election_ballot for the non-secret path) is the real backstop.
                // A caught violation here always means "someone already voted" -- surface it as a
                // ConflictException, letting the whole transaction roll back.
                throw ConflictException("Member ${current.memberId} already voted in Election ${input.electionId}")
            }
        }
    }

    override suspend fun closeVoting(electionId: String): ElectionDto {
        val current = resolveCurrentMember(call)
        val wId = electionId.toUuidOrNotFound("Election")
        return transaction {
            requireElectionRow(wId)
            if (!current.isElectionBoard(wId)) throw ForbiddenException()
            val statusNow = ElectionTable.selectAll().where { ElectionTable.id eq wId }.single()[ElectionTable.status]
            if (statusNow != ElectionStatus.OPEN) throw ConflictException("Election $electionId is $statusNow, expected OPEN")
            ElectionTable.update({ ElectionTable.id eq wId }) {
                it[status] = ElectionStatus.CLOSED
                it[votingClosedAt] = nowLocalDateTime()
            }
            loadElection(wId)
        }
    }

    override suspend fun approveTally(electionId: String): ElectionDto {
        val current = resolveCurrentMember(call)
        val wId = electionId.toUuidOrNotFound("Election")
        return transaction {
            val electionRow = requireElectionRow(wId)
            if (!current.isElectionBoardMember(wId)) throw ForbiddenException()
            if (electionRow[ElectionTable.status] != ElectionStatus.CLOSED) {
                throw ConflictException("Election $electionId is ${electionRow[ElectionTable.status]}, expected CLOSED")
            }
            val alreadyApproved =
                ElectionTallyApprovalTable
                    .selectAll()
                    .where { (ElectionTallyApprovalTable.electionId eq wId) and (ElectionTallyApprovalTable.memberId eq current.memberId) }
                    .count() > 0
            if (alreadyApproved) throw ConflictException("Member ${current.memberId} already approved Tally for Election $electionId")
            ElectionTallyApprovalTable.insert {
                it[ElectionTallyApprovalTable.id] = Uuid.random()
                it[ElectionTallyApprovalTable.electionId] = wId
                it[ElectionTallyApprovalTable.memberId] = current.memberId
                it[approvedAt] = nowLocalDateTime()
            }
            loadElection(wId)
        }
    }

    override suspend fun tally(electionId: String): ElectionResultDto {
        val current = resolveCurrentMember(call)
        val wId = electionId.toUuidOrNotFound("Election")
        return transaction {
            val electionRow = requireElectionRow(wId)
            if (!current.isElectionBoard(wId)) throw ForbiddenException()
            if (electionRow[ElectionTable.status] != ElectionStatus.CLOSED) {
                throw ConflictException("Election $electionId is ${electionRow[ElectionTable.status]}, expected CLOSED")
            }
            val approvalCount = ElectionTallyApprovalTable.selectAll().where { ElectionTallyApprovalTable.electionId eq wId }.count()
            val threshold = electionRow[ElectionTable.tallyThreshold]
            if (approvalCount < threshold) {
                throw ConflictException("Election $electionId has $approvalCount/$threshold required Tally approvals")
            }

            val optionRows =
                ElectionOptionTable
                    .selectAll()
                    .where { ElectionOptionTable.electionId eq wId }
                    .orderBy(ElectionOptionTable.position)
                    .toList()
            val optionIds = optionRows.map { it[ElectionOptionTable.id] }
            val labelByOptionId = optionRows.associate { it[ElectionOptionTable.id] to it[ElectionOptionTable.label] }
            val auselectionRows =
                (ElectionBallotSelectionTable innerJoin ElectionBallotTable)
                    .selectAll()
                    .where { ElectionBallotTable.electionId eq wId }
                    .toList()
            val selectionsByBallot =
                auselectionRows
                    .groupBy({ it[ElectionBallotSelectionTable.ballotId] }, { it[ElectionBallotSelectionTable.optionId] })

            val electionType = electionRow[ElectionTable.electionType]
            val ergebnis: ElectionResultDto
            val resolutionStatus: ResolutionStatus
            val votesYes: Int
            val votesNo: Int
            val votesAbstain: Int
            // V0.5.3 GoBD audit log: collected here, audited only at the very end of the
            // transaction -- see SeatedBoardMembership KDoc.
            val seatedBoardMemberships = mutableListOf<SeatedBoardMembership>()

            if (electionType == ElectionType.YES_NO) {
                val ballots =
                    selectionsByBallot.values.map { selectedIds ->
                        ElectionAnswer.valueOf(labelByOptionId.getValue(selectedIds.single()))
                    }
                val jaNein = computeJaNeinErgebnis(ballots, electionRow[ElectionTable.requiredMajorityPercent])
                val jaOptionId = optionRows.single { it[ElectionOptionTable.label] == ElectionAnswer.YES.name }[ElectionOptionTable.id]
                val neinOptionId = optionRows.single { it[ElectionOptionTable.label] == ElectionAnswer.NO.name }[ElectionOptionTable.id]
                val enthaltungOptionId =
                    optionRows.single {
                        it[ElectionOptionTable.label] == ElectionAnswer.ABSTAIN.name
                    }[ElectionOptionTable.id]
                val winnerOptionIds =
                    if (jaNein.tie) {
                        emptyList()
                    } else if (jaNein.majorityMet) {
                        listOf(jaOptionId.toString())
                    } else {
                        listOf(neinOptionId.toString())
                    }
                ergebnis =
                    ElectionResultDto(
                        electionId = electionId,
                        winnerOptionIds = winnerOptionIds,
                        tie = jaNein.tie,
                        majorityMet = jaNein.majorityMet,
                        perOptionVotes =
                            mapOf(
                                jaOptionId.toString() to jaNein.ja,
                                neinOptionId.toString() to jaNein.nein,
                                enthaltungOptionId.toString() to jaNein.enthaltung,
                            ),
                    )
                resolutionStatus =
                    if (jaNein.tie) {
                        ResolutionStatus.POSTPONED
                    } else if (jaNein.majorityMet) {
                        ResolutionStatus.ADOPTED
                    } else {
                        ResolutionStatus.REJECTED
                    }
                votesYes = jaNein.ja
                votesNo = jaNein.nein
                votesAbstain = jaNein.enthaltung
            } else {
                val ballots = selectionsByBallot.values.map { ElectionBallotData(optionIds = it) }
                val personenelection = computePersonnelElectionErgebnis(ballots, optionIds, electionRow[ElectionTable.seatCount])
                // SINGLE_CHOICE requires an absolute majority of the votes cast, not merely a
                // plurality -- see `03 Bereiche/Lapis Cloud/Demokratische Electionen.md` Electiontypen
                // table ("Absolute Mehrheit, ggf. Stichelection"). computePersonnelElectionErgebnis alone
                // implements top-n-by-plurality (correct for MULTI_CHOICE, insufficient for
                // SINGLE_CHOICE on its own), so the majority check is layered on top here, reusing
                // the same requiredMajorityPercent field the YES_NO branch above already applies.
                // Only meaningful in the genuinely *contested* case (more candidates than seats) --
                // the undersubscribed single-candidate case is left to computePersonnelElectionErgebnis's
                // own documented "uncontested seat needs no ballot" convention. A winner who fails
                // the majority requirement resolves the whole Election to POSTPONED (no winner seated),
                // signalling that a Stichelection (runoff) is required -- same "tie is the safe,
                // non-manipulable default" philosophy as a seat-cutoff tie.
                val contested = optionIds.size > electionRow[ElectionTable.seatCount]
                val einzelelectionMajorityMet =
                    if (electionType == ElectionType.SINGLE_CHOICE &&
                        contested &&
                        !personenelection.tie &&
                        personenelection.winnerOptionIds.isNotEmpty()
                    ) {
                        val totalVotes = personenelection.voteCounts.values.sum()
                        val winnerVotes = personenelection.voteCounts.getValue(personenelection.winnerOptionIds.single())
                        val requiredPercent = electionRow[ElectionTable.requiredMajorityPercent]
                        totalVotes > 0 && winnerVotes.toLong() * 100 >= requiredPercent.toLong() * totalVotes
                    } else {
                        true
                    }
                val effectiveTie = personenelection.tie || !einzelelectionMajorityMet
                val effectiveWinnerOptionIds = if (effectiveTie) emptyList() else personenelection.winnerOptionIds
                ergebnis =
                    ElectionResultDto(
                        electionId = electionId,
                        winnerOptionIds = effectiveWinnerOptionIds.map { it.toString() },
                        tie = effectiveTie,
                        majorityMet = null,
                        perOptionVotes = personenelection.voteCounts.mapKeys { (optionId, _) -> optionId.toString() },
                    )
                resolutionStatus = if (effectiveTie) ResolutionStatus.POSTPONED else ResolutionStatus.ADOPTED
                votesYes = 0
                votesNo = 0
                votesAbstain = 0

                if (!effectiveTie && effectiveWinnerOptionIds.isNotEmpty()) {
                    val targetCommitteeId =
                        electionRow[ElectionTable.targetCommitteeId]
                            ?: throw ConflictException("Election $electionId has no targetCommitteeId to seat winners into")
                    val targetRole = electionRow[ElectionTable.targetRole] ?: CommitteeRole.MEMBER
                    // V0.5.2 Transparenzregister (§20 GwG): this Election is the ONLY path that
                    // seats winners into a targetCommittee (the YES_NO branch above seats nobody),
                    // so it is also the only tally-time hook needed to keep the Transparenzregister
                    // beneficial-owner roster (BoardMembershipEvents.recordBoardJoin, see that
                    // object's KDoc) in step with the real Vorstand. Gated on the targetCommittee's
                    // type, NOT on isPoliticalParty -- §20 GwG applies to every Verein/Partei.
                    val targetCommitteeType =
                        CommitteeTable.selectAll().where { CommitteeTable.id eq targetCommitteeId }.single()[CommitteeTable.type]
                    val candidacyIdByOptionId = optionRows.associate { it[ElectionOptionTable.id] to it[ElectionOptionTable.candidacyId] }
                    val today =
                        Clock.System
                            .now()
                            .toLocalDateTime(TimeZone.currentSystemDefault())
                            .date
                    effectiveWinnerOptionIds.forEach { winnerOptionId ->
                        val candidacyId =
                            candidacyIdByOptionId[winnerOptionId]
                                ?: throw ConflictException("Winning option $winnerOptionId has no Candidacy")
                        val winnerMemberId =
                            ElectionCandidacyTable
                                .selectAll()
                                .where { ElectionCandidacyTable.id eq candidacyId }
                                .single()[ElectionCandidacyTable.memberId]
                        // Guarded seat, mirroring the single-active-membership invariant
                        // GovernanceService.addCommitteeMember enforces (GovernanceService.kt
                        // ~200-212): an incumbent who wins re-election (or is elected into a new
                        // Role, e.g. a sitting MEMBER elected CHAIR) already has an active
                        // (until == null) row for this Committee. Closing it before inserting the
                        // fresh term -- instead of an unconditional insert -- prevents a second
                        // concurrent active membership row for the same member+Committee, which
                        // would silently corrupt membership history in a legally-relevant
                        // Vereins-/Parteiverwaltung (a later removeCommitteeMitglied/
                        // endCommitteeMembership closes only one row, leaving a phantom active
                        // membership behind).
                        CommitteeMembershipTable.update({
                            (CommitteeMembershipTable.committeeId eq targetCommitteeId) and
                                (CommitteeMembershipTable.memberId eq winnerMemberId) and
                                (CommitteeMembershipTable.until.isNull())
                        }) {
                            it[until] = today
                        }
                        CommitteeMembershipTable.insert {
                            it[CommitteeMembershipTable.id] = Uuid.random()
                            it[CommitteeMembershipTable.committeeId] = targetCommitteeId
                            it[CommitteeMembershipTable.memberId] = winnerMemberId
                            it[role] = targetRole
                            it[since] = today
                            it[until] = null
                        }
                        if (targetCommitteeType == CommitteeType.EXECUTIVE_BOARD) {
                            val boardMembershipId =
                                BoardMembershipEvents.recordBoardJoin(winnerMemberId, targetRole, today, nowLocalDateTime())
                            seatedBoardMemberships += SeatedBoardMembership(boardMembershipId, winnerMemberId, targetRole, today)
                        }
                    }
                }
            }

            val meeting = MeetingTable.selectAll().where { MeetingTable.id eq electionRow[ElectionTable.meetingId] }.single()
            val committeeId = requireMotionCommitteeId(electionRow[ElectionTable.motionId])
            val motionRow = MotionTable.selectAll().where { MotionTable.id eq electionRow[ElectionTable.motionId] }.single()
            val resolutionInput =
                ResolutionInput(
                    agendaItemId = motionRow[MotionTable.agendaItemId]?.toString(),
                    title = motionRow[MotionTable.title],
                    text = motionRow[MotionTable.text],
                    votesYes = votesYes,
                    votesNo = votesNo,
                    votesAbstain = votesAbstain,
                    status = resolutionStatus,
                )
            val resolution =
                insertResolutionRow(
                    electionRow[ElectionTable.meetingId],
                    committeeId,
                    meeting[MeetingTable.scheduledAt].date,
                    resolutionInput,
                    current,
                    resolutionMode = ResolutionMode.DEMOCRATIC,
                    electionId = wId,
                )

            val newMotionStatus =
                when (resolutionStatus) {
                    ResolutionStatus.ADOPTED -> MotionStatus.RESOLVED
                    ResolutionStatus.REJECTED -> MotionStatus.REJECTED
                    ResolutionStatus.POSTPONED -> MotionStatus.POSTPONED
                }
            MotionTable.update({ MotionTable.id eq motionRow[MotionTable.id] }) {
                it[MotionTable.status] = newMotionStatus
                it[MotionTable.resolutionId] = Uuid.parse(resolution.id)
            }

            ElectionTable.update({ ElectionTable.id eq wId }) {
                it[status] = ElectionStatus.TALLIED
                it[tallyRunAt] = nowLocalDateTime()
                it[ElectionTable.resolutionId] = Uuid.parse(resolution.id)
            }
            // V0.5.3 GoBD audit log: called last, after every other row-locking write in this
            // transaction (CommitteeMembershipTable/MotionTable/ElectionTable), so this satisfies
            // AuditLogRecorder's deadlock-avoidance contract -- see auditBoardMembershipCreate/
            // auditResolutionCreate KDoc for why these calls cannot happen earlier (inside the
            // seating loop / inside insertResolutionRow).
            seatedBoardMemberships.forEach {
                auditBoardMembershipCreate(it.id, it.memberId, it.role, it.startedAt, current)
            }
            auditResolutionCreate(resolution, current)
            ergebnis
        }
    }

    override suspend fun abortElection(electionId: String): ElectionDto {
        val current = resolveCurrentMember(call)
        val wId = electionId.toUuidOrNotFound("Election")
        return transaction {
            val electionRow = requireElectionRow(wId)
            if (!current.canManageElection(requireMotionCommitteeId(electionRow[ElectionTable.motionId]))) throw ForbiddenException()
            if (electionRow[ElectionTable.status] == ElectionStatus.TALLIED ||
                electionRow[ElectionTable.status] == ElectionStatus.ABORTED
            ) {
                throw ConflictException("Election $electionId is already ${electionRow[ElectionTable.status]}")
            }
            ElectionTable.update({ ElectionTable.id eq wId }) { it[status] = ElectionStatus.ABORTED }
            loadElection(wId)
        }
    }

    override suspend fun getElection(electionId: String): ElectionDto {
        resolveCurrentMember(call)
        val wId = electionId.toUuidOrNotFound("Election")
        return transaction { loadElection(wId) }
    }

    override suspend fun listElections(
        motionId: String?,
        status: ElectionStatus?,
    ): List<ElectionDto> {
        resolveCurrentMember(call)
        return transaction {
            val conditions = mutableListOf<Op<Boolean>>()
            if (motionId != null) conditions += (ElectionTable.motionId eq motionId.toUuidOrNotFound("Motion"))
            if (status != null) conditions += (ElectionTable.status eq status)
            val baseQuery = ElectionTable.selectAll()
            val query = if (conditions.isEmpty()) baseQuery else baseQuery.where { conditions.reduce { a, b -> a and b } }
            query.map { it.toElectionDto() }
        }
    }

    override suspend fun listElectionBallots(electionId: String): List<ElectionBallotDto> {
        resolveCurrentMember(call)
        val wId = electionId.toUuidOrNotFound("Election")
        return transaction {
            val electionRow = requireElectionRow(wId)
            // Pre-tally secrecy gate: same invariant as ElectionOptionDto.voteCount (held at 0 until
            // TALLIED) and verifyReceipt (optionLabel null until TALLIED). Without this,
            // any authenticated member could enumerate every anonymized ballot's plaintext choice
            // while a secret Election is still OPEN/CLOSED and tally it themselves, learning a
            // partial result mid-vote -- see ElectionBallotDto KDoc.
            val revealLabels = !electionRow[ElectionTable.secret] || electionRow[ElectionTable.status] == ElectionStatus.TALLIED
            ElectionBallotTable
                .selectAll()
                .where { ElectionBallotTable.electionId eq wId }
                .map { it.toElectionBallotDto(revealLabels) }
        }
    }

    override suspend fun verifyReceipt(
        electionId: String,
        receiptCode: String,
    ): ReceiptVerificationDto {
        resolveCurrentMember(call)
        val wId = electionId.toUuidOrNotFound("Election")
        return transaction {
            val electionRow = requireElectionRow(wId)
            val ballotRow =
                ElectionBallotTable
                    .selectAll()
                    .where { (ElectionBallotTable.electionId eq wId) and (ElectionBallotTable.receiptCode eq receiptCode) }
                    .singleOrNull()
                    ?: return@transaction ReceiptVerificationDto(found = false, optionLabel = null)
            val optionLabel =
                if (electionRow[ElectionTable.status] == ElectionStatus.TALLIED) {
                    (ElectionBallotSelectionTable innerJoin ElectionOptionTable)
                        .selectAll()
                        .where { ElectionBallotSelectionTable.ballotId eq ballotRow[ElectionBallotTable.id] }
                        .map { it[ElectionOptionTable.label] }
                        .joinToString(", ")
                        .ifBlank { null }
                } else {
                    null
                }
            ReceiptVerificationDto(found = true, optionLabel = optionLabel)
        }
    }

    private fun requireElectionRow(electionId: Uuid): ResultRow =
        ElectionTable.selectAll().where { ElectionTable.id eq electionId }.singleOrNull()
            ?: throw NotFoundException("Election $electionId not found")

    private fun requireMotionCommitteeId(motionId: Uuid): Uuid =
        MotionTable.selectAll().where { MotionTable.id eq motionId }.single()[MotionTable.targetCommitteeId]

    private fun loadElection(id: Uuid): ElectionDto =
        ElectionTable
            .selectAll()
            .where { ElectionTable.id eq id }
            .singleOrNull()
            ?.toElectionDto() ?: throw NotFoundException("Election $id not found")

    private fun loadElectionBoard(electionId: Uuid): List<ElectionBoardMemberDto> =
        ElectionBoardMemberTable
            .selectAll()
            .where { ElectionBoardMemberTable.electionId eq electionId }
            .map { it.toElectionBoardMemberDto() }

    private fun loadCandidacy(id: Uuid): CandidacyDto =
        ElectionCandidacyTable
            .selectAll()
            .where { ElectionCandidacyTable.id eq id }
            .single()
            .toCandidacyDto()

    private fun memberDisplayName(memberId: Uuid?): String? =
        memberId?.let { mId ->
            MemberTable
                .selectAll()
                .where { MemberTable.id eq mId }
                .singleOrNull()
                ?.get(MemberTable.displayName)
        }

    private fun nowLocalDateTime(): LocalDateTime = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault())

    /**
     * Retries a small, bounded number of times on a receipt-code collision (astronomically
     * unlikely at [RECEIPT_CODE_BYTES] bytes of [SecureRandom] entropy, but checked rather than
     * assumed) -- see [ElectionBallotTable] KDoc.
     */
    private fun generateUniqueReceiptCode(electionId: Uuid): String {
        repeat(RECEIPT_CODE_MAX_ATTEMPTS) {
            val bytes = ByteArray(RECEIPT_CODE_BYTES)
            secureRandom.nextBytes(bytes)
            val candidate = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
            val exists =
                ElectionBallotTable
                    .selectAll()
                    .where { (ElectionBallotTable.electionId eq electionId) and (ElectionBallotTable.receiptCode eq candidate) }
                    .count() > 0
            if (!exists) return candidate
        }
        throw ConflictException(
            "Failed to generate a unique receipt code for Election $electionId after $RECEIPT_CODE_MAX_ATTEMPTS attempts",
        )
    }

    private fun ResultRow.toElectionDto(): ElectionDto {
        val electionId = this[ElectionTable.id]
        val status = this[ElectionTable.status]
        val optionRows =
            ElectionOptionTable
                .selectAll()
                .where { ElectionOptionTable.electionId eq electionId }
                .orderBy(ElectionOptionTable.position)
                .toList()
        val voteCountByOptionId =
            if (status == ElectionStatus.TALLIED) {
                val optionIds = optionRows.map { it[ElectionOptionTable.id] }
                ElectionBallotSelectionTable
                    .selectAll()
                    .where { ElectionBallotSelectionTable.optionId inList optionIds }
                    .groupingBy { it[ElectionBallotSelectionTable.optionId] }
                    .eachCount()
            } else {
                emptyMap()
            }
        val options =
            optionRows.map { optRow ->
                val optionId = optRow[ElectionOptionTable.id]
                ElectionOptionDto(
                    id = optionId.toString(),
                    electionId = electionId.toString(),
                    label = optRow[ElectionOptionTable.label],
                    position = optRow[ElectionOptionTable.position],
                    candidacyId = optRow[ElectionOptionTable.candidacyId]?.toString(),
                    voteCount = voteCountByOptionId[optionId] ?: 0,
                )
            }
        val targetCommitteeId = this[ElectionTable.targetCommitteeId]
        return ElectionDto(
            id = electionId.toString(),
            motionId = this[ElectionTable.motionId].toString(),
            meetingId = this[ElectionTable.meetingId].toString(),
            title = this[ElectionTable.title],
            electionType = this[ElectionTable.electionType],
            secret = this[ElectionTable.secret],
            seatCount = this[ElectionTable.seatCount],
            targetCommitteeId = targetCommitteeId?.toString(),
            targetCommitteeName =
                targetCommitteeId?.let { gId ->
                    CommitteeTable
                        .selectAll()
                        .where { CommitteeTable.id eq gId }
                        .singleOrNull()
                        ?.get(CommitteeTable.name)
                },
            targetRole = this[ElectionTable.targetRole],
            requiredMajorityPercent = this[ElectionTable.requiredMajorityPercent],
            status = status,
            openedById = this[ElectionTable.openedBy].toString(),
            openedByDisplayName = memberDisplayName(this[ElectionTable.openedBy]).orEmpty(),
            openedAt = this[ElectionTable.openedAt],
            candidateListApprovedAt = this[ElectionTable.candidateListApprovedAt],
            votingOpenedAt = this[ElectionTable.votingOpenedAt],
            votingClosedAt = this[ElectionTable.votingClosedAt],
            tallyThreshold = this[ElectionTable.tallyThreshold],
            tallyRunAt = this[ElectionTable.tallyRunAt],
            resolutionId = this[ElectionTable.resolutionId]?.toString(),
            options = options,
        )
    }

    private fun ResultRow.toElectionBoardMemberDto(): ElectionBoardMemberDto =
        ElectionBoardMemberDto(
            id = this[ElectionBoardMemberTable.id].toString(),
            electionId = this[ElectionBoardMemberTable.electionId].toString(),
            memberId = this[ElectionBoardMemberTable.memberId].toString(),
            memberDisplayName = memberDisplayName(this[ElectionBoardMemberTable.memberId]).orEmpty(),
            appointedAt = this[ElectionBoardMemberTable.appointedAt],
        )

    private fun ResultRow.toCandidacyDto(): CandidacyDto =
        CandidacyDto(
            id = this[ElectionCandidacyTable.id].toString(),
            electionId = this[ElectionCandidacyTable.electionId].toString(),
            memberId = this[ElectionCandidacyTable.memberId].toString(),
            memberDisplayName = memberDisplayName(this[ElectionCandidacyTable.memberId]).orEmpty(),
            motivationText = this[ElectionCandidacyTable.motivationText],
            submittedAt = this[ElectionCandidacyTable.submittedAt],
            withdrawnAt = this[ElectionCandidacyTable.withdrawnAt],
        )

    private fun ResultRow.toElectionBallotDto(revealLabels: Boolean): ElectionBallotDto {
        val ballotId = this[ElectionBallotTable.id]
        val memberId = this[ElectionBallotTable.memberId]
        val labels =
            if (revealLabels) {
                (ElectionBallotSelectionTable innerJoin ElectionOptionTable)
                    .selectAll()
                    .where { ElectionBallotSelectionTable.ballotId eq ballotId }
                    .orderBy(ElectionOptionTable.position, SortOrder.ASC)
                    .map { it[ElectionOptionTable.label] }
            } else {
                emptyList()
            }
        return ElectionBallotDto(
            id = ballotId.toString(),
            electionId = this[ElectionBallotTable.electionId].toString(),
            memberId = memberId?.toString(),
            memberDisplayName = memberDisplayName(memberId),
            selectedOptionLabels = labels,
            castAt = this[ElectionBallotTable.castAt],
        )
    }

    private fun String.toUuidOrNotFound(kind: String): Uuid =
        runCatching { Uuid.parse(this) }.getOrElse { throw NotFoundException("Invalid $kind id: $this") }
}
