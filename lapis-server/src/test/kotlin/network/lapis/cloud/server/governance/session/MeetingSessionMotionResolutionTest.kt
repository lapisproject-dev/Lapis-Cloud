package network.lapis.cloud.server.governance.session

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.server.db.DatabaseConfig
import network.lapis.cloud.server.db.DevSeedData
import network.lapis.cloud.server.db.generated.MotionTable
import network.lapis.cloud.server.db.generated.ResolutionTable
import network.lapis.cloud.server.db.generated.TenderTable
import network.lapis.cloud.server.tender.TenderStore
import network.lapis.cloud.shared.domain.MotionStatus
import network.lapis.cloud.shared.domain.ResolutionMode
import network.lapis.cloud.shared.domain.ResolutionStatus
import network.lapis.cloud.shared.domain.SessionMotionVoteStatus
import network.lapis.cloud.shared.domain.SystemicConsensusBindingness
import network.lapis.cloud.shared.domain.SystemicConsensusStatus
import network.lapis.cloud.shared.domain.TenderAwardCriterion
import network.lapis.cloud.shared.domain.TenderBidVisibility
import network.lapis.cloud.shared.domain.TenderDescriptionFormat
import network.lapis.cloud.shared.domain.VoteStatus
import network.lapis.cloud.shared.rpc.ConflictException
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import kotlin.uuid.Uuid

/** V1.9.238 -- the tally of a Sitzung vote writes the resolution; the Sitzung panel shows it. */
class MeetingSessionMotionResolutionTest :
    FunSpec({
        val fx = SessionFixture()

        beforeSpec {
            DatabaseConfig.connect()
            DevSeedData.seedIfEmpty(force = true)
        }
        afterSpec { fx.cleanup() }

        data class Case(
            val yes: Int,
            val no: Int,
            val abstain: Int,
            val expected: ResolutionStatus,
            val motion: MotionStatus,
        )

        listOf(
            Case(5, 1, 1, ResolutionStatus.ADOPTED, MotionStatus.RESOLVED),
            Case(3, 3, 0, ResolutionStatus.POSTPONED, MotionStatus.POSTPONED),
            Case(2, 1, 4, ResolutionStatus.ADOPTED, MotionStatus.RESOLVED),
            Case(0, 0, 0, ResolutionStatus.POSTPONED, MotionStatus.POSTPONED),
            Case(1, 3, 0, ResolutionStatus.REJECTED, MotionStatus.REJECTED),
        ).forEach { c ->
            test("yes ${c.yes} / no ${c.no} / abstain ${c.abstain} -> ${c.expected}") {
                val s = fx.scene()
                val m = s.motionOps()
                val motion = s.attachedMotion(motions = m, text = "MSX eingefrorener Wortlaut")
                val item = s.agenda[0].toString()
                m.openVote(
                    current = s.lead,
                    roomId = s.roomId,
                    meetingId = s.meetingId,
                    motionId = motion.toString(),
                    expectedAgendaItemId = item,
                    secret = false,
                    boardMemberIds = s.boardIds(),
                )
                val election = s.electionOf(motion)
                s.castBallots(electionId = election, yes = c.yes, no = c.no, abstain = c.abstain)
                s.closeVoting(election)
                s.tally(election)

                val shown =
                    s.ops
                        .getState(current = s.lead, roomId = s.roomId)!!
                        .agendaMotions
                        .single()
                val resolution = shown.resolution!!
                resolution.status shouldBe c.expected
                resolution.votesYes shouldBe c.yes
                resolution.votesNo shouldBe c.no
                resolution.votesAbstain shouldBe c.abstain
                resolution.figuresWithheld shouldBe false
                shown.vote!!.status shouldBe SessionMotionVoteStatus.TALLIED
                shown.status shouldBe c.motion
                s.motionStatus(motion) shouldBe c.motion

                transaction {
                    val row = ResolutionTable.selectAll().where { ResolutionTable.electionId eq election }.single()
                    row[ResolutionTable.resolutionMode] shouldBe ResolutionMode.DEMOCRATIC
                    row[ResolutionTable.agendaItemId] shouldBe s.agenda[0]
                    row[ResolutionTable.text] shouldBe "MSX eingefrorener Wortlaut"
                    row[ResolutionTable.meetingId] shouldBe s.meetingId
                    MotionTable
                        .selectAll()
                        .where { MotionTable.id eq motion }
                        .single()[MotionTable.resolutionId]
                        .toString() shouldBe
                        resolution.id
                }
                // nobody else sees a different resolution
                s.ops
                    .getState(current = s.participant, roomId = s.roomId)!!
                    .agendaMotions
                    .single()
                    .resolution!!
                    .status shouldBe c.expected
            }
        }

        test("the resolution carries the frozen effective text of an amended motion") {
            val s = fx.scene()
            val m = s.motionOps()
            val motion = s.attachedMotion(motions = m, text = "MSX Original")
            transaction { MotionTable.update({ MotionTable.id eq motion }) { it[currentText] = "MSX geaenderter Wortlaut" } }
            m.openVote(
                current = s.lead,
                roomId = s.roomId,
                meetingId = s.meetingId,
                motionId = motion.toString(),
                expectedAgendaItemId = s.agenda[0].toString(),
                secret = false,
                boardMemberIds = s.boardIds(),
            )
            s.ops
                .getState(current = s.lead, roomId = s.roomId)!!
                .agendaMotions
                .single()
                .effectiveText shouldBe "MSX geaenderter Wortlaut"
            val election = s.electionOf(motion)
            s.castBallots(electionId = election, yes = 4, no = 0, abstain = 0)
            s.closeVoting(election)
            s.tally(election)
            transaction {
                ResolutionTable.selectAll().where { ResolutionTable.electionId eq election }.single()[ResolutionTable.text] shouldBe
                    "MSX geaenderter Wortlaut"
            }
        }

        test("a tied motion waits as POSTPONED, shows no resolution and cannot be attached again (it can never get a second election)") {
            val s = fx.scene()
            val m = s.motionOps()
            val motion = s.attachedMotion(motions = m)
            val item = s.agenda[0].toString()
            m.openVote(
                current = s.lead,
                roomId = s.roomId,
                meetingId = s.meetingId,
                motionId = motion.toString(),
                expectedAgendaItemId = item,
                secret = false,
                boardMemberIds = s.boardIds(),
            )
            val election = s.electionOf(motion)
            s.castBallots(electionId = election, yes = 2, no = 2, abstain = 0)
            s.closeVoting(election)
            s.tally(election)
            s.motionStatus(motion) shouldBe MotionStatus.POSTPONED
            s.ops
                .getState(current = s.lead, roomId = s.roomId)!!
                .attachableMotions
                .map { it.id } shouldBe emptyList()
            shouldThrow<ConflictException> {
                m.attach(
                    current = s.lead,
                    roomId = s.roomId,
                    meetingId = s.meetingId,
                    motionId = motion.toString(),
                    expectedAgendaItemId = item,
                )
            }
            s.motionStatus(motion) shouldBe MotionStatus.POSTPONED
        }

        test(
            "a motion POSTPONED by a tied meritocratic Vote, by a BINDING consensus, or a tender award motion is neither listed nor attachable",
        ) {
            val s = fx.scene()
            val m = s.motionOps()
            val item = s.openMeeting()
            val tied = fx.newMotion(committee = s.committee, submitter = s.participant, status = MotionStatus.POSTPONED, title = "MSX tied")
            SessionDecisionRows.meritocraticVote(meetingId = s.meetingId, motionId = tied, opener = s.lead, status = VoteStatus.CLOSED)
            val binding =
                fx.newMotion(
                    committee = s.committee,
                    submitter = s.participant,
                    status = MotionStatus.POSTPONED,
                    title = "MSX binding",
                )
            SessionDecisionRows.systemicConsensus(
                meetingId = s.meetingId,
                motionId = binding,
                opener = s.lead,
                status = SystemicConsensusStatus.EVALUATED,
                bindingness = SystemicConsensusBindingness.BINDING,
            )
            val award = fx.newMotion(committee = s.committee, submitter = s.participant, title = "MSX award")
            val binder = fx.newMotion(committee = s.committee, submitter = s.participant, title = "MSX binder")
            val tenderId = Uuid.random()
            transaction {
                TenderStore.insertTender(
                    id = tenderId,
                    bindingMotionId = binder,
                    title = "MSX Tender",
                    description = "d",
                    descriptionFormat = TenderDescriptionFormat.PLAIN,
                    expectedResult = "r",
                    deadlineWall = LocalDateTime(2026, 3, 1, 12, 0),
                    criterion = TenderAwardCriterion.BEST_OFFER,
                    visibility = TenderBidVisibility.OPEN,
                    createdBy = s.lead.memberId,
                    createdAt = LocalDateTime(2026, 2, 1, 12, 0),
                )
                TenderStore.setAwardMotion(tenderId = tenderId, motionId = award)
            }
            try {
                val listed =
                    s.ops
                        .getState(current = s.lead, roomId = s.roomId)!!
                        .attachableMotions
                        .map { it.id }
                listOf(tied, binding, award).forEach { listed.contains(it.toString()) shouldBe false }
                listed.contains(binder.toString()) shouldBe true
                listOf(tied, binding, award).forEach { id ->
                    shouldThrow<ConflictException> {
                        m.attach(
                            current = s.lead,
                            roomId = s.roomId,
                            meetingId = s.meetingId,
                            motionId = id.toString(),
                            expectedAgendaItemId = item,
                        )
                    }
                }
            } finally {
                transaction { TenderTable.deleteWhere { TenderTable.id eq tenderId } }
            }
        }

        test("only the figures of an open vote's counters reach LEAD and MINUTES -- a participant sees none") {
            val s = fx.scene()
            val m = s.motionOps()
            val motion = s.attachedMotion(motions = m)
            m.openVote(
                current = s.lead,
                roomId = s.roomId,
                meetingId = s.meetingId,
                motionId = motion.toString(),
                expectedAgendaItemId = s.agenda[0].toString(),
                secret = false,
                boardMemberIds = s.boardIds(),
            )
            s.castBallots(electionId = s.electionOf(motion), yes = 3, no = 1, abstain = 0)
            val lead = s.ops.getState(current = s.lead, roomId = s.roomId)!!.runningMotionVote!!
            lead.ballotCount shouldBe 4
            lead.eligibleCount shouldBe 7
            s.ops
                .getState(current = s.minutes, roomId = s.roomId)!!
                .runningMotionVote!!
                .ballotCount shouldBe 4
            val participant = s.ops.getState(current = s.participant, roomId = s.roomId)!!.runningMotionVote!!
            participant.ballotCount shouldBe null
            participant.eligibleCount shouldBe null
            participant.tallyApprovalCount shouldBe null
        }
    })
