package network.lapis.cloud.server.governance.session

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import network.lapis.cloud.server.db.DatabaseConfig
import network.lapis.cloud.server.db.DevSeedData
import network.lapis.cloud.server.db.generated.MotionTable
import network.lapis.cloud.server.db.generated.SystemicConsensusTable
import network.lapis.cloud.shared.domain.MeetingStatus
import network.lapis.cloud.shared.domain.MotionStatus
import network.lapis.cloud.shared.domain.SessionMotionVoteStatus
import network.lapis.cloud.shared.rpc.AgendaMovedMeanwhileException
import network.lapis.cloud.shared.rpc.ConflictException
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update

/** V1.9.238 -- attach and open-vote follow the Sitzung state machine, and only ONE decision runs at a time. */
class MeetingSessionMotionStateMachineTest :
    FunSpec({
        val fx = SessionFixture()

        beforeSpec {
            DatabaseConfig.connect()
            DevSeedData.seedIfEmpty(force = true)
        }
        afterSpec { fx.cleanup() }

        test("attaching and opening a vote are conflicts before the opening and after the end") {
            val s = fx.scene()
            val m = s.motionOps()
            val motion = fx.newMotion(committee = s.committee, submitter = s.participant)
            shouldThrow<ConflictException> {
                m.attach(
                    current = s.lead,
                    roomId = s.roomId,
                    meetingId = s.meetingId,
                    motionId = motion.toString(),
                    expectedAgendaItemId = s.agenda[0].toString(),
                )
            }
            shouldThrow<ConflictException> {
                m.openVote(
                    current = s.lead,
                    roomId = s.roomId,
                    meetingId = s.meetingId,
                    motionId = motion.toString(),
                    expectedAgendaItemId = s.agenda[0].toString(),
                    secret = false,
                    boardMemberIds = s.boardIds(),
                )
            }
            val item = s.openMeeting()
            fx.setStatus(meetingId = s.meetingId, status = MeetingStatus.HELD)
            shouldThrow<ConflictException> {
                m.attach(
                    current = s.lead,
                    roomId = s.roomId,
                    meetingId = s.meetingId,
                    motionId = motion.toString(),
                    expectedAgendaItemId = item,
                )
            }
            shouldThrow<ConflictException> {
                m.openVote(
                    current = s.lead,
                    roomId = s.roomId,
                    meetingId = s.meetingId,
                    motionId = motion.toString(),
                    expectedAgendaItemId = item,
                    secret = false,
                    boardMemberIds = s.boardIds(),
                )
            }
        }

        test("an ended room is read-only") {
            val s = fx.scene()
            val m = s.motionOps()
            val item = s.openMeeting()
            val motion = fx.newMotion(committee = s.committee, submitter = s.participant)
            val ended = fx.newRoom(meetingId = s.meetingId, creator = s.lead, ended = true)
            // a second, ended room is bound to the same Sitzung (a re-bound room) -- the ended one must refuse
            shouldThrow<ConflictException> {
                m.attach(
                    current = s.lead,
                    roomId = ended,
                    meetingId = s.meetingId,
                    motionId = motion.toString(),
                    expectedAgendaItemId = item,
                )
            }
        }

        test("a stale agenda item is AgendaMovedMeanwhile for both operations") {
            val s = fx.scene()
            val m = s.motionOps()
            val item = s.openMeeting()
            val motion = fx.newMotion(committee = s.committee, submitter = s.participant)
            val stale = s.agenda[1].toString()
            (item == stale) shouldBe false
            shouldThrow<AgendaMovedMeanwhileException> {
                m.attach(
                    current = s.lead,
                    roomId = s.roomId,
                    meetingId = s.meetingId,
                    motionId = motion.toString(),
                    expectedAgendaItemId = stale,
                )
            }
            shouldThrow<AgendaMovedMeanwhileException> {
                m.openVote(
                    current = s.lead,
                    roomId = s.roomId,
                    meetingId = s.meetingId,
                    motionId = motion.toString(),
                    expectedAgendaItemId = stale,
                    secret = false,
                    boardMemberIds = s.boardIds(),
                )
            }
        }

        test("attaching sets SCHEDULED, the Sitzung and the item; REVIEWED and POSTPONED work, the rest is a conflict") {
            val s = fx.scene()
            val m = s.motionOps()
            val item = s.openMeeting()
            val reviewed = fx.newMotion(committee = s.committee, submitter = s.participant)
            val postponed = fx.newMotion(committee = s.committee, submitter = s.participant, status = MotionStatus.POSTPONED)
            val state =
                m.attach(
                    current = s.lead,
                    roomId = s.roomId,
                    meetingId = s.meetingId,
                    motionId = reviewed.toString(),
                    expectedAgendaItemId = item,
                )
            state.agendaMotions.map { it.id } shouldContainExactly listOf(reviewed.toString())
            state.agendaMotions.single().status shouldBe MotionStatus.SCHEDULED
            m
                .attach(
                    current = s.lead,
                    roomId = s.roomId,
                    meetingId = s.meetingId,
                    motionId = postponed.toString(),
                    expectedAgendaItemId = item,
                ).agendaMotions.size shouldBe
                2
            s.motionStatus(postponed) shouldBe MotionStatus.SCHEDULED
            // already scheduled (also to the very same item), withdrawn, resolved and merely submitted motions are refused
            shouldThrow<ConflictException> {
                m.attach(
                    current = s.lead,
                    roomId = s.roomId,
                    meetingId = s.meetingId,
                    motionId = reviewed.toString(),
                    expectedAgendaItemId = item,
                )
            }
            listOf(MotionStatus.WITHDRAWN, MotionStatus.RESOLVED, MotionStatus.SUBMITTED).forEach { status ->
                val other = fx.newMotion(committee = s.committee, submitter = s.participant, status = status)
                shouldThrow<ConflictException> {
                    m.attach(
                        current = s.lead,
                        roomId = s.roomId,
                        meetingId = s.meetingId,
                        motionId = other.toString(),
                        expectedAgendaItemId = item,
                    )
                }
            }
        }

        test("an amendment cannot be attached; it follows its main motion") {
            val s = fx.scene()
            val m = s.motionOps()
            val item = s.openMeeting()
            val main = fx.newMotion(committee = s.committee, submitter = s.participant)
            val amendment = fx.newMotion(committee = s.committee, submitter = s.participant, amends = main)
            shouldThrow<ConflictException> {
                m.attach(
                    current = s.lead,
                    roomId = s.roomId,
                    meetingId = s.meetingId,
                    motionId = amendment.toString(),
                    expectedAgendaItemId = item,
                )
            }
        }

        test("a main motion with a pending amendment can neither be attached nor listed") {
            val s = fx.scene()
            val m = s.motionOps()
            val item = s.openMeeting()
            val main = fx.newMotion(committee = s.committee, submitter = s.participant)
            val amendment = fx.newMotion(committee = s.committee, submitter = s.participant, amends = main)
            s.ops
                .getState(current = s.lead, roomId = s.roomId)!!
                .attachableMotions
                .map { it.id } shouldBe emptyList()
            shouldThrow<ConflictException> {
                m.attach(
                    current = s.lead,
                    roomId = s.roomId,
                    meetingId = s.meetingId,
                    motionId = main.toString(),
                    expectedAgendaItemId = item,
                )
            }
            s.motionStatus(main) shouldBe MotionStatus.REVIEWED
            // once the amendment is decided the main motion is available again
            transaction { MotionTable.update({ MotionTable.id eq amendment }) { it[status] = MotionStatus.WITHDRAWN } }
            s.ops
                .getState(current = s.lead, roomId = s.roomId)!!
                .attachableMotions
                .map { it.id } shouldBe listOf(main.toString())
        }

        test("opening a vote needs a motion of the current item that is SCHEDULED") {
            val s = fx.scene()
            val m = s.motionOps()
            val item = s.openMeeting()
            val loose = fx.newMotion(committee = s.committee, submitter = s.participant)
            // not attached at all: as unknown as a stranger's motion (not found), not a conflict that would confirm it exists
            shouldThrow<network.lapis.cloud.shared.rpc.NotFoundException> {
                m.openVote(
                    current = s.lead,
                    roomId = s.roomId,
                    meetingId = s.meetingId,
                    motionId = loose.toString(),
                    expectedAgendaItemId = item,
                    secret = false,
                    boardMemberIds = s.boardIds(),
                )
            }
            val attached = s.attachedMotionOn(motions = m, motion = loose)
            val otherItem = s.agenda[1]
            transaction { MotionTable.update({ MotionTable.id eq attached }) { it[agendaItemId] = otherItem } }
            shouldThrow<ConflictException> {
                m.openVote(
                    current = s.lead,
                    roomId = s.roomId,
                    meetingId = s.meetingId,
                    motionId = attached.toString(),
                    expectedAgendaItemId = item,
                    secret = false,
                    boardMemberIds = s.boardIds(),
                )
            }
        }

        test("opening a vote shows the running vote to everybody and holds the agenda") {
            val s = fx.scene()
            val m = s.motionOps()
            val motion = s.attachedMotion(motions = m)
            val state =
                m.openVote(
                    current = s.lead,
                    roomId = s.roomId,
                    meetingId = s.meetingId,
                    motionId = motion.toString(),
                    expectedAgendaItemId = s.agenda[0].toString(),
                    secret = false,
                    boardMemberIds = s.boardIds(),
                )
            state.runningMotionVote!!.status shouldBe SessionMotionVoteStatus.OPEN
            s.ops
                .getState(current = s.participant, roomId = s.roomId)!!
                .runningMotionVote!!
                .status shouldBe SessionMotionVoteStatus.OPEN
            shouldThrow<ConflictException> {
                s.ops.moveAgenda(
                    current = s.lead,
                    roomId = s.roomId,
                    meetingId = s.meetingId,
                    fromAgendaItemId = s.agenda[0].toString(),
                    toAgendaItemId = s.agenda[1].toString(),
                )
            }
            // after closing, the agenda may move again but a new vote still waits for the tally
            s.closeVoting(s.electionOf(motion))
            s.ops
                .getState(current = s.lead, roomId = s.roomId)!!
                .runningMotionVote!!
                .status shouldBe SessionMotionVoteStatus.CLOSED_AWAITING_TALLY
            s.ops.moveAgenda(
                current = s.lead,
                roomId = s.roomId,
                meetingId = s.meetingId,
                fromAgendaItemId = s.agenda[0].toString(),
                toAgendaItemId = s.agenda[1].toString(),
            )
        }

        test("a second vote is refused while one runs or waits for its tally -- also next to other decision paths") {
            val s = fx.scene()
            val m = s.motionOps()
            val first = s.attachedMotion(motions = m)
            val item = s.agenda[0].toString()
            val second = fx.newMotion(committee = s.committee, submitter = s.participant)
            m.attach(
                current = s.lead,
                roomId = s.roomId,
                meetingId = s.meetingId,
                motionId = second.toString(),
                expectedAgendaItemId = item,
            )
            m.openVote(
                current = s.lead,
                roomId = s.roomId,
                meetingId = s.meetingId,
                motionId = first.toString(),
                expectedAgendaItemId = item,
                secret = false,
                boardMemberIds = s.boardIds(),
            )
            shouldThrow<ConflictException> {
                m.openVote(
                    current = s.lead,
                    roomId = s.roomId,
                    meetingId = s.meetingId,
                    motionId = second.toString(),
                    expectedAgendaItemId = item,
                    secret = false,
                    boardMemberIds = s.boardIds(),
                )
            }
            s.closeVoting(s.electionOf(first))
            shouldThrow<ConflictException> {
                m.openVote(
                    current = s.lead,
                    roomId = s.roomId,
                    meetingId = s.meetingId,
                    motionId = second.toString(),
                    expectedAgendaItemId = item,
                    secret = false,
                    boardMemberIds = s.boardIds(),
                )
            }
            s.tally(s.electionOf(first))
            m
                .openVote(
                    current = s.lead,
                    roomId = s.roomId,
                    meetingId = s.meetingId,
                    motionId = second.toString(),
                    expectedAgendaItemId = item,
                    secret = false,
                    boardMemberIds = s.boardIds(),
                ).runningMotionVote!!
                .motionId shouldBe
                second.toString()
        }

        test("a running meritocratic vote or systemic consensus of the Sitzung blocks opening a vote") {
            val s = fx.scene()
            val m = s.motionOps()
            val motion = s.attachedMotion(motions = m)
            val item = s.agenda[0].toString()
            val other = fx.newMotion(committee = s.committee, submitter = s.participant)
            m.attach(current = s.lead, roomId = s.roomId, meetingId = s.meetingId, motionId = other.toString(), expectedAgendaItemId = item)
            val consensus = SessionDecisionRows.systemicConsensus(meetingId = s.meetingId, motionId = other, opener = s.lead)
            shouldThrow<ConflictException> {
                m.openVote(
                    current = s.lead,
                    roomId = s.roomId,
                    meetingId = s.meetingId,
                    motionId = motion.toString(),
                    expectedAgendaItemId = item,
                    secret = false,
                    boardMemberIds = s.boardIds(),
                )
            }
            SessionDecisionRows.finishConsensus(consensus)
            transaction { SystemicConsensusTable.selectAll().where { SystemicConsensusTable.id eq consensus }.count() } shouldBe 1L
            SessionDecisionRows.meritocraticVote(meetingId = s.meetingId, motionId = other, opener = s.lead)
            shouldThrow<ConflictException> {
                m.openVote(
                    current = s.lead,
                    roomId = s.roomId,
                    meetingId = s.meetingId,
                    motionId = motion.toString(),
                    expectedAgendaItemId = item,
                    secret = false,
                    boardMemberIds = s.boardIds(),
                )
            }
        }

        test("an amendment cannot be submitted while a vote on its main motion runs") {
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
            // the production guard behind submitMotion: a running decision path of the main motion
            shouldThrow<ConflictException> {
                transaction {
                    network.lapis.cloud.server.rpc.MotionDecisionLock
                        .requireNoRunningDecisionPath(motion)
                }
            }
            transaction {
                MotionTable.selectAll().where { MotionTable.id eq motion }.single()[MotionTable.currentText]
            } shouldBe null
        }
    })
