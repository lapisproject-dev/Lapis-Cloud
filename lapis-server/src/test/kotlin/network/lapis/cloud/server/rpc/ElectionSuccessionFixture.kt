package network.lapis.cloud.server.rpc

import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.server.db.DbClock
import network.lapis.cloud.server.db.generated.CommitteeMembershipTable
import network.lapis.cloud.server.db.generated.ElectionBallotSelectionTable
import network.lapis.cloud.server.db.generated.ElectionBallotTable
import network.lapis.cloud.server.db.generated.ElectionBoardMemberTable
import network.lapis.cloud.server.db.generated.ElectionCandidacyTable
import network.lapis.cloud.server.db.generated.ElectionOptionTable
import network.lapis.cloud.server.db.generated.ElectionSeatOutcomeTable
import network.lapis.cloud.server.db.generated.ElectionTable
import network.lapis.cloud.server.db.generated.ElectionTallyApprovalTable
import network.lapis.cloud.server.db.generated.MeetingTable
import network.lapis.cloud.server.db.generated.MotionTable
import network.lapis.cloud.server.db.generated.ResolutionTable
import network.lapis.cloud.server.security.CurrentMember
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.CommitteeRole
import network.lapis.cloud.shared.domain.ElectionResultDto
import network.lapis.cloud.shared.domain.ElectionStatus
import network.lapis.cloud.shared.domain.ElectionType
import network.lapis.cloud.shared.domain.IncumbentPolicy
import network.lapis.cloud.shared.domain.MeetingFormat
import network.lapis.cloud.shared.domain.MeetingStatus
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.domain.MotionStatus
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import kotlin.uuid.Uuid

/** One seeded CLOSED election, ready to be tallied. */
internal class SeededSuccessionElection(
    val electionId: Uuid,
    val motionId: Uuid,
    val committeeId: Uuid,
    val optionIdByMember: Map<Uuid, Uuid>,
)

/**
 * Welle V1.9.103 -- seeds CLOSED personnel elections directly into the database (every ballot count is exact) and tallies them through the
 * live core [tallyElectionLocked]. Creates its own rows only; [cleanUp] must run BEFORE [CommitteeSeatFixture.cleanUp].
 */
internal class ElectionSuccessionFixture(
    private val fx: CommitteeSeatFixture,
) {
    private val electionIds = mutableListOf<Uuid>()
    private val motionIds = mutableListOf<Uuid>()
    private val meetingIds = mutableListOf<Uuid>()

    /** Tallies as this ADMIN account (a privileged actor: the board-membership/audit paths treat it like the real tallier). */
    val admin: Uuid by lazy { fx.member(role = AccountRole.ADMIN, displayName = "Wahl Administrator") }
    private val approvers: List<Uuid> by lazy { (1..2).map { fx.member(displayName = "Wahlvorstand $it") } }

    val actor: CurrentMember get() = CurrentMember(memberId = admin, role = AccountRole.ADMIN, status = MemberStatus.ACTIVE)

    /** A SCHEDULED motion (with its meeting) of [committee], no election yet; returns (motionId, meetingId). */
    fun motion(committee: Uuid): Pair<Uuid, Uuid> {
        val motionId = Uuid.random()
        val meetingId = Uuid.random()
        transaction {
            MeetingTable.insert {
                it[id] = meetingId
                it[MeetingTable.committeeId] = committee
                it[title] = "Nachfolge-Sitzung"
                it[scheduledAt] = LocalDateTime(2026, 3, 1, 18, 0)
                it[location] = "Vereinsheim"
                it[format] = MeetingFormat.IN_PERSON
                it[status] = MeetingStatus.PLANNED
                it[calledBy] = null
                it[calledAt] = null
                it[chairMemberId] = null
                it[minuteTakerMemberId] = null
                it[protocolDocumentId] = null
                it[createdAt] = LocalDateTime(2026, 3, 1, 18, 0)
            }
            MotionTable.insert {
                it[id] = motionId
                it[targetCommitteeId] = committee
                it[title] = "Nachfolge-Antrag"
                it[rationale] = "R"
                it[text] = "T"
                it[submitterMemberId] = admin
                it[MotionTable.status] = MotionStatus.SCHEDULED
                it[submittedAt] = LocalDateTime(2026, 1, 1, 0, 0)
                it[reviewedBy] = admin
                it[reviewedAt] = LocalDateTime(2026, 1, 1, 0, 0)
                it[reviewNote] = null
                it[MotionTable.meetingId] = meetingId
                it[agendaItemId] = null
                it[resolutionId] = null
                it[withdrawnAt] = null
            }
        }
        meetingIds += meetingId
        motionIds += motionId
        return motionId to meetingId
    }

    /**
     * @param candidates one entry per candidate: the member and the number of ballots that select exactly that candidate. The first
     *   ranks are the winners; a tie is produced by giving the candidates around the cut-off the same count.
     */
    @Suppress("LongParameterList")
    fun seed(
        committee: Uuid,
        role: CommitteeRole?,
        policy: IncumbentPolicy?,
        candidates: List<Pair<Uuid, Int>>,
        type: ElectionType = ElectionType.SINGLE_CHOICE,
        seatCount: Int = 1,
    ): SeededSuccessionElection {
        val (motionId, meetingId) = motion(committee)
        val electionId = Uuid.random()
        val at = DbClock.nowLocalDateTime()
        val optionIdByMember = candidates.associate { it.first to Uuid.random() }
        val candidacyIdByMember = candidates.associate { it.first to Uuid.random() }
        transaction {
            ElectionTable.insert {
                it[id] = electionId
                it[title] = "Nachfolge-Wahl"
                it[electionType] = type
                it[secret] = true
                it[ElectionTable.seatCount] = seatCount
                it[targetCommitteeId] = committee
                it[targetRole] = role
                it[incumbentPolicy] = policy
                it[requiredMajorityPercent] = 50
                it[ElectionTable.status] = ElectionStatus.CLOSED
                it[openedBy] = admin
                it[openedAt] = at
                it[votingOpenedAt] = at
                it[votingClosedAt] = at
                it[tallyThreshold] = 2
                it[tallyRunAt] = null
                it[ElectionTable.motionId] = motionId
                it[ElectionTable.meetingId] = meetingId
                it[activeMotionId] = motionId
            }
            candidates.forEachIndexed { position, (member, _) ->
                ElectionCandidacyTable.insert {
                    it[id] = candidacyIdByMember.getValue(member)
                    it[ElectionCandidacyTable.electionId] = electionId
                    it[memberId] = member
                    it[motivationText] = null
                    it[submittedAt] = at
                    it[withdrawnAt] = null
                }
                ElectionOptionTable.insert {
                    it[id] = optionIdByMember.getValue(member)
                    it[label] = "Kandidat $position"
                    it[ElectionOptionTable.position] = position
                    it[candidacyId] = candidacyIdByMember.getValue(member)
                    it[ElectionOptionTable.electionId] = electionId
                }
            }
            candidates.forEach { (member, ballots) ->
                repeat(ballots) {
                    val ballotId = Uuid.random()
                    ElectionBallotTable.insert {
                        it[id] = ballotId
                        it[receiptCode] =
                            Uuid
                                .random()
                                .toString()
                                .replace("-", "")
                                .take(27)
                        it[castAt] = at
                        it[ElectionBallotTable.electionId] = electionId
                        it[memberId] = null
                    }
                    ElectionBallotSelectionTable.insert {
                        it[id] = Uuid.random()
                        it[ElectionBallotSelectionTable.ballotId] = ballotId
                        it[optionId] = optionIdByMember.getValue(member)
                    }
                }
            }
            approvers.forEach { approver ->
                ElectionBoardMemberTable.insert {
                    it[id] = Uuid.random()
                    it[appointedAt] = at
                    it[ElectionBoardMemberTable.electionId] = electionId
                    it[memberId] = approver
                }
                ElectionTallyApprovalTable.insert {
                    it[id] = Uuid.random()
                    it[ElectionTallyApprovalTable.electionId] = electionId
                    it[memberId] = approver
                    it[approvedAt] = at
                }
            }
        }
        electionIds += electionId
        return SeededSuccessionElection(
            electionId = electionId,
            motionId = motionId,
            committeeId = committee,
            optionIdByMember = optionIdByMember,
        )
    }

    /** Tallies in its own transaction with the lock order of `ElectionService.tally` (motion, then election). */
    fun tally(election: SeededSuccessionElection): ElectionResultDto =
        transaction {
            val motionRow = MotionDecisionLock.lockMotion(election.motionId)
            val electionRow = lockElectionRow(election.electionId)
            tallyElectionLocked(electionMotionRow = motionRow, electionRow = electionRow, actor = actor)
        }

    fun electionRow(id: Uuid): ResultRow = transaction { ElectionTable.selectAll().where { ElectionTable.id eq id }.single() }

    fun outcomes(electionId: Uuid): List<ResultRow> =
        transaction { ElectionSeatOutcomeTable.selectAll().where { ElectionSeatOutcomeTable.electionId eq electionId }.toList() }

    /** Seats of [committee] started by [electionId], as `memberId` to row. */
    fun seatsStartedBy(electionId: Uuid): List<ResultRow> =
        transaction {
            CommitteeMembershipTable.selectAll().where { CommitteeMembershipTable.startedByElectionId eq electionId }.toList()
        }

    /** Registers an election created by a service call for [cleanUp]. */
    fun track(electionId: Uuid) {
        electionIds += electionId
    }

    fun cleanUp() {
        if (electionIds.isEmpty()) return
        transaction {
            ElectionSeatOutcomeTable.deleteWhere { ElectionSeatOutcomeTable.electionId inList electionIds }
            CommitteeMembershipTable.update({ CommitteeMembershipTable.startedByElectionId inList electionIds }) {
                it[startedByElectionId] = null
            }
            CommitteeMembershipTable.update({ CommitteeMembershipTable.endedByElectionId inList electionIds }) {
                it[endedByElectionId] = null
            }
            MotionTable.update({ MotionTable.id inList motionIds }) { it[resolutionId] = null }
            ElectionTable.update({ ElectionTable.id inList electionIds }) { it[resolutionId] = null }
            ResolutionTable.deleteWhere { ResolutionTable.electionId inList electionIds }
            val ballotIds =
                ElectionBallotTable
                    .selectAll()
                    .where { ElectionBallotTable.electionId inList electionIds }
                    .map { it[ElectionBallotTable.id] }
            ElectionBallotSelectionTable.deleteWhere { ElectionBallotSelectionTable.ballotId inList ballotIds }
            ElectionBallotTable.deleteWhere { ElectionBallotTable.electionId inList electionIds }
            ElectionTallyApprovalTable.deleteWhere { ElectionTallyApprovalTable.electionId inList electionIds }
            ElectionBoardMemberTable.deleteWhere { ElectionBoardMemberTable.electionId inList electionIds }
            ElectionOptionTable.deleteWhere { ElectionOptionTable.electionId inList electionIds }
            ElectionCandidacyTable.deleteWhere { ElectionCandidacyTable.electionId inList electionIds }
            ElectionTable.deleteWhere { ElectionTable.id inList electionIds }
            MotionTable.deleteWhere { MotionTable.id inList motionIds }
            MeetingTable.deleteWhere { MeetingTable.id inList meetingIds }
        }
    }
}

internal val SUCCESSION_OLD_SINCE: LocalDate = LocalDate(2025, 1, 1)
