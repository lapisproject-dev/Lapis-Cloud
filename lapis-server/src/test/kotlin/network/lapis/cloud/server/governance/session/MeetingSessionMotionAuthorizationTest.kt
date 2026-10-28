package network.lapis.cloud.server.governance.session

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import network.lapis.cloud.server.db.DatabaseConfig
import network.lapis.cloud.server.db.DevSeedData
import network.lapis.cloud.server.db.generated.MeetingTable
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.MotionStatus
import network.lapis.cloud.shared.rpc.ConflictException
import network.lapis.cloud.shared.rpc.ForbiddenException
import network.lapis.cloud.shared.rpc.NotFoundException
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import kotlin.uuid.Uuid

/** V1.9.238 -- who may attach a motion and open a vote, and that everything a caller must not see is one and the same not-found. */
class MeetingSessionMotionAuthorizationTest :
    FunSpec({
        val fx = SessionFixture()

        beforeSpec {
            DatabaseConfig.connect()
            DevSeedData.seedIfEmpty(force = true)
        }
        afterSpec { fx.cleanup() }

        test("a participant and the minute taker may neither attach nor open a vote") {
            val s = fx.scene()
            val m = s.motionOps()
            val motion = s.attachedMotion(motions = m)
            val item = s.agenda[0].toString()
            val loose = fx.newMotion(committee = s.committee, submitter = s.participant)
            listOf(s.participant, s.minutes).forEach { caller ->
                shouldThrow<ForbiddenException> {
                    m.attach(
                        current = caller,
                        roomId = s.roomId,
                        meetingId = s.meetingId,
                        motionId = loose.toString(),
                        expectedAgendaItemId = item,
                    )
                }
                shouldThrow<ForbiddenException> {
                    m.openVote(
                        current = caller,
                        roomId = s.roomId,
                        meetingId = s.meetingId,
                        motionId = motion.toString(),
                        expectedAgendaItemId = item,
                        secret = false,
                        boardMemberIds = s.boardIds(),
                    )
                }
            }
            s.ops.getState(current = s.participant, roomId = s.roomId)!!.canManageMotions shouldBe false
            s.ops.getState(current = s.participant, roomId = s.roomId)!!.attachableMotions shouldBe emptyList()
        }

        test("a chair without a recording right is LEAD for the panel but may not attach or open a vote (no new right appears)") {
            val s = fx.scene()
            val m = s.motionOps()
            val motion = s.attachedMotion(motions = m)
            val chairOnly = s.peers.first()
            transaction { MeetingTable.update({ MeetingTable.id eq s.meetingId }) { it[chairMemberId] = chairOnly.memberId } }
            val item = s.agenda[0].toString()
            val loose = fx.newMotion(committee = s.committee, submitter = s.participant)
            shouldThrow<ForbiddenException> {
                m.attach(
                    current = chairOnly,
                    roomId = s.roomId,
                    meetingId = s.meetingId,
                    motionId = loose.toString(),
                    expectedAgendaItemId = item,
                )
            }
            shouldThrow<ForbiddenException> {
                m.openVote(
                    current = chairOnly,
                    roomId = s.roomId,
                    meetingId = s.meetingId,
                    motionId = motion.toString(),
                    expectedAgendaItemId = item,
                    secret = false,
                    boardMemberIds = s.boardIds(),
                )
            }
            val state = s.ops.getState(current = chairOnly, roomId = s.roomId)!!
            state.canManageMotions shouldBe false
            state.attachableMotions shouldBe emptyList()
        }

        test("the lead sees the buttons' data: canManageMotions and the attachable motions") {
            val s = fx.scene()
            val loose = fx.newMotion(committee = s.committee, submitter = s.participant, title = "MSX Lose")
            fx.newMotion(committee = s.committee, submitter = s.participant, status = MotionStatus.WITHDRAWN)
            s.openMeeting()
            val state = s.ops.getState(current = s.lead, roomId = s.roomId)!!
            state.canManageMotions shouldBe true
            state.attachableMotions.map { it.id } shouldBe listOf(loose.toString())
        }

        test("strangers get the same not-found as an unknown motion: no member, no room guest, no foreign room") {
            val s = fx.scene()
            val m = s.motionOps()
            val item = s.openMeeting()
            val loose = fx.newMotion(committee = s.committee, submitter = s.participant)
            val stranger = fx.newMember(name = "MSX Stranger")
            val other = fx.scene()
            val messages =
                listOf<suspend () -> Any>(
                    {
                        m.attach(
                            current = stranger,
                            roomId = s.roomId,
                            meetingId = s.meetingId,
                            motionId = loose.toString(),
                            expectedAgendaItemId = item,
                        )
                    },
                    {
                        m.attach(
                            current = s.lead,
                            roomId = s.roomId,
                            meetingId = other.meetingId,
                            motionId = loose.toString(),
                            expectedAgendaItemId = item,
                        )
                    },
                    {
                        m.attach(
                            current = s.lead,
                            roomId = other.roomId,
                            meetingId = s.meetingId,
                            motionId = loose.toString(),
                            expectedAgendaItemId = item,
                        )
                    },
                    {
                        m.attach(
                            current = s.lead,
                            roomId = s.roomId,
                            meetingId = s.meetingId,
                            motionId = Uuid.random().toString(),
                            expectedAgendaItemId = item,
                        )
                    },
                    {
                        m.attach(
                            current = s.lead,
                            roomId = s.roomId,
                            meetingId = s.meetingId,
                            motionId = "no-uuid",
                            expectedAgendaItemId = item,
                        )
                    },
                ).map { call -> shouldThrow<NotFoundException> { call() }.message }
            messages.toSet().size shouldBe 1
            messages.first() shouldBe MeetingSessionAccess.NOT_FOUND_MESSAGE
        }

        test("a motion of another committee or another Sitzung is not found, never a conflict that would confirm it exists") {
            val s = fx.scene()
            val m = s.motionOps()
            val item = s.openMeeting()
            val other = fx.scene()
            val foreign = fx.newMotion(committee = other.committee, submitter = other.participant)
            shouldThrow<NotFoundException> {
                m.attach(
                    current = s.lead,
                    roomId = s.roomId,
                    meetingId = s.meetingId,
                    motionId = foreign.toString(),
                    expectedAgendaItemId = item,
                )
            }
            shouldThrow<NotFoundException> {
                m.openVote(
                    current = s.lead,
                    roomId = s.roomId,
                    meetingId = s.meetingId,
                    motionId = foreign.toString(),
                    expectedAgendaItemId = item,
                    secret = false,
                    boardMemberIds = s.boardIds(),
                )
            }
            // a motion of the SAME committee that hangs at another Sitzung
            val otherMeetingMotion = other.attachedMotion(motions = other.motionOps())
            val sameCommitteeElsewhere = fx.newMotion(committee = s.committee, submitter = s.participant)
            transaction {
                network.lapis.cloud.server.db.generated.MotionTable.update({
                    network.lapis.cloud.server.db.generated.MotionTable.id eq sameCommitteeElsewhere
                }) {
                    it[status] = MotionStatus.SCHEDULED
                    it[meetingId] = other.meetingId
                    it[agendaItemId] = other.agenda[0]
                }
            }
            shouldThrow<NotFoundException> {
                m.openVote(
                    current = s.lead,
                    roomId = s.roomId,
                    meetingId = s.meetingId,
                    motionId = sameCommitteeElsewhere.toString(),
                    expectedAgendaItemId = item,
                    secret = false,
                    boardMemberIds = s.boardIds(),
                )
            }
            (otherMeetingMotion != sameCommitteeElsewhere) shouldBe true
        }

        test("the election board: outside the roster is not found, fewer than three or without the lead is a conflict") {
            val s = fx.scene()
            val m = s.motionOps()
            val motion = s.attachedMotion(motions = m)
            val item = s.agenda[0].toString()
            val outsider = fx.newMember(name = "MSX Outsider")
            val unknown = Uuid.random().toString()
            shouldThrow<NotFoundException> {
                m.openVote(
                    current = s.lead,
                    roomId = s.roomId,
                    meetingId = s.meetingId,
                    motionId = motion.toString(),
                    expectedAgendaItemId = item,
                    secret = false,
                    boardMemberIds = s.boardIds().dropLast(1) + outsider.memberId.toString(),
                )
            }
            shouldThrow<NotFoundException> {
                m.openVote(
                    current = s.lead,
                    roomId = s.roomId,
                    meetingId = s.meetingId,
                    motionId = motion.toString(),
                    expectedAgendaItemId = item,
                    secret = false,
                    boardMemberIds =
                        s.boardIds().dropLast(1) + unknown,
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
                    boardMemberIds = s.boardIds().take(2),
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
                    boardMemberIds =
                        listOf(s.minutes, s.participant, s.peers[0]).map {
                            it.memberId.toString()
                        },
                )
            }
            // nothing was written by the refused attempts
            s.ops.getState(current = s.lead, roomId = s.roomId)!!.runningMotionVote shouldBe null
            m
                .openVote(
                    current = s.lead,
                    roomId = s.roomId,
                    meetingId = s.meetingId,
                    motionId = motion.toString(),
                    expectedAgendaItemId = item,
                    secret = false,
                    boardMemberIds = s.boardIds(),
                ).runningMotionVote!!
                .secret shouldBe
                false
        }

        test("a secret vote is refused without a stream guard (fail closed) and nothing is written") {
            val s = fx.scene()
            val m = s.motionOps(guard = null)
            val motion = s.attachedMotion(motions = m)
            shouldThrow<ConflictException> {
                m.openVote(
                    current = s.lead,
                    roomId = s.roomId,
                    meetingId = s.meetingId,
                    motionId = motion.toString(),
                    expectedAgendaItemId = s.agenda[0].toString(),
                    secret = true,
                    boardMemberIds = s.boardIds(),
                )
            }
            s.ops.getState(current = s.lead, roomId = s.roomId)!!.runningMotionVote shouldBe null
            // the open vote works without a guard
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

        test("an administrator who is not on the roster may open a vote with a board of others") {
            val s = fx.scene()
            val m = s.motionOps()
            val motion = s.attachedMotion(motions = m)
            val admin = fx.newMember(name = "MSX Admin", role = AccountRole.ADMIN)
            val state =
                m.openVote(
                    current = admin,
                    roomId = s.roomId,
                    meetingId = s.meetingId,
                    motionId = motion.toString(),
                    expectedAgendaItemId = s.agenda[0].toString(),
                    secret = false,
                    boardMemberIds = s.boardIds(),
                )
            state.runningMotionVote!!.status shouldBe network.lapis.cloud.shared.domain.SessionMotionVoteStatus.OPEN
        }

        test("the running vote tells a board member that they may close it") {
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
            s.ops
                .getState(current = s.lead, roomId = s.roomId)!!
                .runningMotionVote!!
                .canClose shouldBe true
            s.ops
                .getState(current = s.participant, roomId = s.roomId)!!
                .runningMotionVote!!
                .canClose shouldBe true
            // a present person who was not named to the election board may not close it (ElectionService.closeVoting would refuse)
            s.ops
                .getState(current = s.peers[0], roomId = s.roomId)!!
                .runningMotionVote!!
                .canClose shouldBe false
        }
    })
