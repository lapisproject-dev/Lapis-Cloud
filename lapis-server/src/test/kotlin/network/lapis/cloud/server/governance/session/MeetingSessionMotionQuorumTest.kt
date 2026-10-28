package network.lapis.cloud.server.governance.session

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import network.lapis.cloud.server.db.DatabaseConfig
import network.lapis.cloud.server.db.DevSeedData
import network.lapis.cloud.server.db.generated.ElectionEligibleVoterTable
import network.lapis.cloud.shared.domain.AttendanceStatus
import network.lapis.cloud.shared.domain.CommitteeRole
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction

/**
 * V1.9.238 -- the quorum of a Sitzung resolution is computed at the TALLY from the electorate frozen when the vote opened (N) and the live
 * attendance (F7 of the plan). Eleven peers + lead + minutes + participant = 12 eligible, 50 percent.
 */
class MeetingSessionMotionQuorumTest :
    FunSpec({
        val fx = SessionFixture()

        beforeSpec {
            DatabaseConfig.connect()
            DevSeedData.seedIfEmpty(force = true)
        }
        afterSpec { fx.cleanup() }

        fun SessionScene.present(count: Int) {
            everyone.take(count).forEach { fx.putAttendance(meetingId = meetingId, member = it, status = AttendanceStatus.PRESENT) }
        }

        suspend fun SessionScene.runVote(): Boolean {
            val m = motionOps()
            val motion = attachedMotion(motions = m)
            m.openVote(
                current = lead,
                roomId = roomId,
                meetingId = meetingId,
                motionId = motion.toString(),
                expectedAgendaItemId = agenda[0].toString(),
                secret = false,
                boardMemberIds = boardIds(),
            )
            val election = electionOf(motion)
            castBallots(electionId = election, yes = 3, no = 0, abstain = 0)
            closeVoting(election)
            return election.let {
                tally(it)
                ops
                    .getState(current = lead, roomId = roomId)!!
                    .agendaMotions
                    .single()
                    .resolution!!
                    .quorumMet
            }
        }

        test("12 eligible at 50 percent: 6 present is a quorum") {
            val s = fx.scene(peers = 9)
            s.present(6)
            s.runVote() shouldBe true
        }

        test("12 eligible at 50 percent: 5 present is none -- the resolution says so") {
            val s = fx.scene(peers = 9)
            s.present(5)
            s.runVote() shouldBe false
        }

        test("somebody who joins the committee after the vote opened does not change N") {
            val s = fx.scene(peers = 9)
            s.present(6)
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
            val election = s.electionOf(motion)
            transaction {
                ElectionEligibleVoterTable.selectAll().where { ElectionEligibleVoterTable.electionId eq election }.count()
            } shouldBe
                12L
            // a 13th person arrives: with a live N of 13 six present would be too few, with the frozen 12 it is enough
            fx.seat(member = fx.newMember(name = "MSX Latecomer"), committee = s.committee, role = CommitteeRole.MEMBER)
            s.castBallots(electionId = election, yes = 3, no = 0, abstain = 0)
            s.closeVoting(election)
            s.tally(election)
            s.ops
                .getState(current = s.lead, roomId = s.roomId)!!
                .agendaMotions
                .single()
                .resolution!!
                .quorumMet shouldBe true
        }

        test("opening a vote without a quorum, or before one was established, is allowed -- the resolution carries false") {
            val s = fx.scene(peers = 9)
            s.ops.getState(current = s.lead, roomId = s.roomId)!!.establishedQuorum shouldBe null
            s.runVote() shouldBe false
        }
    })
