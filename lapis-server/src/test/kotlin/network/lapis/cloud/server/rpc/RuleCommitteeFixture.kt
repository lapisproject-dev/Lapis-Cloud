package network.lapis.cloud.server.rpc

import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.server.db.DbClock
import network.lapis.cloud.server.db.generated.AttendanceTable
import network.lapis.cloud.server.db.generated.AuditLogEntryTable
import network.lapis.cloud.server.db.generated.CommitteeTable
import network.lapis.cloud.server.db.generated.ElectionEligibleVoterTable
import network.lapis.cloud.server.db.generated.ElectionTable
import network.lapis.cloud.server.db.generated.LtrLedgerEntryTable
import network.lapis.cloud.server.db.generated.MeetingTable
import network.lapis.cloud.server.db.generated.MotionTable
import network.lapis.cloud.server.db.generated.ResolutionTable
import network.lapis.cloud.server.db.generated.SystemicConsensusEligibleVoterTable
import network.lapis.cloud.server.db.generated.SystemicConsensusTable
import network.lapis.cloud.server.db.generated.VoteBallotTable
import network.lapis.cloud.server.db.generated.VoteEligibleVoterTable
import network.lapis.cloud.server.db.generated.VoteOptionTable
import network.lapis.cloud.server.db.generated.VoteTable
import network.lapis.cloud.server.time.OrganizationTimeZone
import network.lapis.cloud.shared.domain.AttendanceStatus
import network.lapis.cloud.shared.domain.CommitteeMembershipMode
import network.lapis.cloud.shared.domain.CommitteeType
import network.lapis.cloud.shared.domain.ElectionStatus
import network.lapis.cloud.shared.domain.ElectionType
import network.lapis.cloud.shared.domain.LtrLedgerEntryType
import network.lapis.cloud.shared.domain.MeetingFormat
import network.lapis.cloud.shared.domain.MeetingStatus
import network.lapis.cloud.shared.domain.MotionStatus
import network.lapis.cloud.shared.domain.SystemicConsensusAggregation
import network.lapis.cloud.shared.domain.SystemicConsensusBindingness
import network.lapis.cloud.shared.domain.SystemicConsensusStatus
import network.lapis.cloud.shared.domain.SystemicConsensusTiebreakRule
import network.lapis.cloud.shared.domain.VoteStatus
import network.lapis.cloud.shared.domain.rules.MemberRule
import network.lapis.cloud.shared.domain.rules.MemberRuleCodec
import network.lapis.cloud.shared.domain.rules.RuleCondition
import network.lapis.cloud.shared.domain.rules.RuleOp
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import java.math.BigDecimal
import kotlin.uuid.Uuid

/**
 * Welle V1.9.112 -- rule-based committees, meetings, motions and decisions seeded directly into the database for the rule-committee specs.
 * Builds on [CommitteeSeatFixture] (members, manual committees, seats, audit reads); [cleanUp] must run BEFORE [CommitteeSeatFixture.cleanUp].
 * Every rule a test builds is isolated from the dev seed's members by an `ALL` over a MANUAL test committee, so only the test's own members
 * can ever match.
 */
internal class RuleCommitteeFixture(
    val fx: CommitteeSeatFixture,
) {
    private val meetingIds = mutableListOf<Uuid>()
    private val motionIds = mutableListOf<Uuid>()
    private val voteIds = mutableListOf<Uuid>()
    private val electionIds = mutableListOf<Uuid>()
    private val consensusIds = mutableListOf<Uuid>()
    private val ledgerMemberIds = mutableListOf<Uuid>()

    val today: LocalDate get() = OrganizationTimeZone.today()

    /** The canonical rule JSON of [op] over [conditions]. */
    fun rule(
        op: RuleOp = RuleOp.ALL,
        vararg conditions: RuleCondition,
    ): String = MemberRuleCodec.encode(MemberRule(op = op, conditions = conditions.toList()))

    /** "has an active seat in the MANUAL committee [manual]" -- the isolating base of every test rule. */
    fun seatedIn(manual: Uuid): String = rule(op = RuleOp.ALL, RuleCondition.Committee(setOf(manual.toString())))

    /** A committee in rule mode, inserted directly (the service path is covered by its own spec). */
    fun ruleCommittee(
        ruleJson: String,
        type: CommitteeType = CommitteeType.WORKING_GROUP,
        active: Boolean = true,
    ): Uuid {
        val id = fx.committee(type = CommitteeType.OTHER, active = active)
        transaction {
            CommitteeTable.update({ CommitteeTable.id eq id }) {
                it[CommitteeTable.type] = type
                it[membershipMode] = CommitteeMembershipMode.RULE
                it[CommitteeTable.ruleJson] = ruleJson
            }
        }
        return id
    }

    fun committeeRow(id: Uuid) = fx.committeeRow(id)

    fun meeting(
        committee: Uuid,
        status: MeetingStatus = MeetingStatus.PLANNED,
        scheduledAt: LocalDateTime = LocalDateTime(2999, 1, 1, 18, 0),
    ): Uuid {
        val id = Uuid.random()
        transaction {
            MeetingTable.insert {
                it[MeetingTable.id] = id
                it[committeeId] = committee
                it[title] = "Regel-Sitzung"
                it[MeetingTable.scheduledAt] = scheduledAt
                it[location] = "Vereinsheim"
                it[format] = MeetingFormat.IN_PERSON
                it[MeetingTable.status] = status
                it[calledBy] = null
                it[calledAt] = null
                it[chairMemberId] = null
                it[minuteTakerMemberId] = null
                it[protocolDocumentId] = null
                it[createdAt] = LocalDateTime(2026, 1, 1, 0, 0)
            }
        }
        meetingIds += id
        return id
    }

    /** A SCHEDULED motion of [committee] bound to [meeting]. */
    fun motion(
        committee: Uuid,
        meeting: Uuid,
        submitter: Uuid,
    ): Uuid {
        val id = Uuid.random()
        transaction {
            MotionTable.insert {
                it[MotionTable.id] = id
                it[targetCommitteeId] = committee
                it[title] = "Regel-Antrag"
                it[rationale] = "R"
                it[text] = "T"
                it[submitterMemberId] = submitter
                it[status] = MotionStatus.SCHEDULED
                it[submittedAt] = LocalDateTime(2026, 1, 1, 0, 0)
                it[reviewedBy] = submitter
                it[reviewedAt] = LocalDateTime(2026, 1, 1, 0, 0)
                it[reviewNote] = null
                it[meetingId] = meeting
                it[agendaItemId] = null
                it[resolutionId] = null
                it[withdrawnAt] = null
            }
        }
        motionIds += id
        return id
    }

    /** A vote row seeded directly (no frozen electorate, i.e. a "pre-V1.9.112" vote). */
    fun voteRow(
        meeting: Uuid,
        motion: Uuid,
        opener: Uuid,
        status: VoteStatus = VoteStatus.OPEN,
    ): Uuid {
        val id = Uuid.random()
        transaction {
            VoteTable.insert {
                it[VoteTable.id] = id
                it[title] = "Regel-Abstimmung"
                it[VoteTable.status] = status
                it[openedBy] = opener
                it[openedAt] = DbClock.nowLocalDateTime()
                it[closedAt] = null
                it[winnerOptionId] = null
                it[secondPriceLtr] = null
                it[motionId] = motion
                it[meetingId] = meeting
                it[resolutionId] = null
            }
        }
        voteIds += id
        return id
    }

    fun electionRow(
        meeting: Uuid,
        motion: Uuid,
        opener: Uuid,
        target: Uuid?,
        status: ElectionStatus,
    ): Uuid {
        val id = Uuid.random()
        val at = DbClock.nowLocalDateTime()
        transaction {
            ElectionTable.insert {
                it[ElectionTable.id] = id
                it[title] = "Regel-Wahl"
                it[electionType] = if (target == null) ElectionType.YES_NO else ElectionType.SINGLE_CHOICE
                it[secret] = false
                it[seatCount] = 1
                it[targetCommitteeId] = target
                it[targetRole] = null
                it[incumbentPolicy] = null
                it[requiredMajorityPercent] = 50
                it[ElectionTable.status] = status
                it[openedBy] = opener
                it[openedAt] = at
                it[votingOpenedAt] = null
                it[votingClosedAt] = null
                it[tallyThreshold] = 2
                it[tallyRunAt] = null
                it[motionId] = motion
                it[meetingId] = meeting
                it[activeMotionId] = if (status == ElectionStatus.ABORTED) null else motion
            }
        }
        electionIds += id
        return id
    }

    fun consensusRow(
        meeting: Uuid,
        motion: Uuid,
        opener: Uuid,
        status: SystemicConsensusStatus,
    ): Uuid {
        val id = Uuid.random()
        transaction {
            SystemicConsensusTable.insert {
                it[SystemicConsensusTable.id] = id
                it[title] = "Regel-Konsensieren"
                it[SystemicConsensusTable.status] = status
                it[secret] = false
                it[scaleMax] = 10
                it[aggregation] = SystemicConsensusAggregation.MEAN
                it[tiebreakRule] = SystemicConsensusTiebreakRule.LOWEST_MAX_RESISTANCE
                it[groupConflictViableThreshold] = BigDecimal("0.500")
                it[groupConflictWarnThreshold] = BigDecimal("0.800")
                it[statusQuoOptionAuto] = true
                it[bindingness] = SystemicConsensusBindingness.ADVISORY
                it[maxRounds] = 3
                it[round] = 1
                it[winnerOptionId] = null
                it[openedBy] = opener
                it[openedAt] = DbClock.nowLocalDateTime()
                it[ratingOpenedAt] = null
                it[ratingClosedAt] = null
                it[tallyRunAt] = null
                it[motionId] = motion
                it[meetingId] = meeting
                it[resolutionId] = null
            }
        }
        consensusIds += id
        return id
    }

    /** A free LTR balance so [member] can stake on a vote. */
    fun mintLtr(
        member: Uuid,
        amount: String = "100.00",
    ) {
        transaction {
            LtrLedgerEntryTable.insert {
                it[id] = Uuid.random()
                it[memberId] = member
                it[entryType] = LtrLedgerEntryType.MINT
                it[amountLtr] = BigDecimal(amount)
                it[referenceType] = null
                it[referenceId] = null
                it[note] = "RuleCommitteeFixture"
                it[createdBy] = null
                it[createdAt] = LocalDateTime(2026, 1, 1, 0, 0)
            }
        }
        ledgerMemberIds += member
    }

    fun attend(
        meeting: Uuid,
        member: Uuid,
    ) {
        transaction {
            AttendanceTable.insert {
                it[id] = Uuid.random()
                it[meetingId] = meeting
                it[memberId] = member
                it[status] = AttendanceStatus.PRESENT
                it[representedByMemberId] = null
                it[note] = null
                it[recordedAt] = LocalDateTime(2026, 1, 1, 0, 0)
            }
        }
    }

    fun frozenElectorate(vote: Uuid): Set<Uuid> =
        transaction {
            VoteEligibleVoterTable
                .selectAll()
                .where { VoteEligibleVoterTable.voteId eq vote }
                .map { it[VoteEligibleVoterTable.memberId] }
                .toSet()
        }

    fun cleanUp() {
        transaction {
            // votes opened through the service are found through their meeting
            val viaMeeting =
                if (meetingIds.isEmpty()) {
                    emptyList()
                } else {
                    VoteTable.selectAll().where { VoteTable.meetingId inList meetingIds }.map { it[VoteTable.id] }
                }
            val allVotes = (voteIds + viaMeeting).distinct()
            if (allVotes.isNotEmpty()) {
                VoteTable.update({ VoteTable.id inList allVotes }) { it[resolutionId] = null }
                ResolutionTable.update({ ResolutionTable.voteId inList allVotes }) { it[ResolutionTable.voteId] = null }
                VoteEligibleVoterTable.deleteWhere { VoteEligibleVoterTable.voteId inList allVotes }
                VoteBallotTable.deleteWhere { VoteBallotTable.voteId inList allVotes }
                VoteOptionTable.deleteWhere { VoteOptionTable.voteId inList allVotes }
                VoteTable.deleteWhere { VoteTable.id inList allVotes }
            }
            if (electionIds.isNotEmpty()) {
                ElectionEligibleVoterTable.deleteWhere { ElectionEligibleVoterTable.electionId inList electionIds }
                ElectionTable.deleteWhere { ElectionTable.id inList electionIds }
            }
            if (consensusIds.isNotEmpty()) {
                SystemicConsensusEligibleVoterTable.deleteWhere {
                    SystemicConsensusEligibleVoterTable.systemicConsensusId inList
                        consensusIds
                }
                SystemicConsensusTable.deleteWhere { SystemicConsensusTable.id inList consensusIds }
            }
            if (motionIds.isNotEmpty()) MotionTable.update({ MotionTable.id inList motionIds }) { it[resolutionId] = null }
            if (meetingIds.isNotEmpty()) {
                ResolutionTable.deleteWhere { ResolutionTable.meetingId inList meetingIds }
                AttendanceTable.deleteWhere { AttendanceTable.meetingId inList meetingIds }
            }
            if (motionIds.isNotEmpty()) MotionTable.deleteWhere { MotionTable.id inList motionIds }
            if (meetingIds.isNotEmpty()) MeetingTable.deleteWhere { MeetingTable.id inList meetingIds }
            if (ledgerMemberIds.isNotEmpty()) LtrLedgerEntryTable.deleteWhere { LtrLedgerEntryTable.memberId inList ledgerMemberIds }
            AuditLogEntryTable.update({ AuditLogEntryTable.actorMemberId inList fx.memberIds }) { it[actorMemberId] = null }
        }
    }
}
