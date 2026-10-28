package network.lapis.cloud.server.governance.session

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import network.lapis.cloud.server.db.DatabaseConfig
import network.lapis.cloud.server.db.DevSeedData
import network.lapis.cloud.shared.domain.AttendanceStatus
import network.lapis.cloud.shared.domain.SessionAttendanceInput
import network.lapis.cloud.shared.rpc.ConflictException

/** V1.9.236 -- the live calculation, the established line and the "not met" acknowledgement. 12 seated people at 50 % need 6. */
class MeetingSessionQuorumTest :
    FunSpec({
        val fx = SessionFixture()

        beforeSpec {
            DatabaseConfig.connect()
            DevSeedData.seedIfEmpty(force = true)
        }
        afterSpec { fx.cleanup() }

        suspend fun SessionScene.present(
            who: List<network.lapis.cloud.server.security.CurrentMember>,
            status: AttendanceStatus = AttendanceStatus.PRESENT,
            standIn: network.lapis.cloud.server.security.CurrentMember? = null,
        ) = who.forEach {
            ops.setAttendance(
                current = lead,
                roomId = roomId,
                meetingId = meetingId,
                input =
                    SessionAttendanceInput(
                        memberId = it.memberId.toString(),
                        status = status,
                        representedByMemberId = standIn?.memberId?.toString(),
                    ),
            )
        }

        // lead + minutes + participant + 9 peers = 12
        fun twelve() = fx.scene(peers = 9)

        test("6 of 12 at 50 percent is met, 5 of 12 is not") {
            val s = twelve()
            s.ops.open(current = s.lead, roomId = s.roomId, meetingId = s.meetingId)
            s.present(s.peers.take(5))
            s.ops.getState(current = s.lead, roomId = s.roomId)!!.liveQuorum!!.let {
                it.eligibleMemberCount shouldBe 12
                it.requiredCount shouldBe 6
                it.presentCount shouldBe 5
                it.met shouldBe false
            }
            s.present(listOf(s.peers[5]))
            s.ops
                .getState(current = s.lead, roomId = s.roomId)!!
                .liveQuorum!!
                .met shouldBe true
        }

        test("a stand-in counts as present, a guest does not") {
            val s = twelve()
            s.ops.open(current = s.lead, roomId = s.roomId, meetingId = s.meetingId)
            s.present(listOf(s.peers[0]))
            s.present(listOf(s.peers[1]), AttendanceStatus.REPRESENTED, standIn = s.peers[0])
            val outsider = fx.newMember()
            s.ops.setAttendance(
                current = s.lead,
                roomId = s.roomId,
                meetingId = s.meetingId,
                input = SessionAttendanceInput(memberId = outsider.memberId.toString(), status = AttendanceStatus.GUEST),
            )
            val state = s.ops.getState(current = s.lead, roomId = s.roomId)!!
            state.liveQuorum!!.presentCount shouldBe 2
            state.liveQuorum!!.eligibleMemberCount shouldBe 12
            state.guests.map { it.memberId } shouldBe listOf(outsider.memberId.toString())
        }

        test("establishing without the acknowledgement is refused with numbers only; with it the override is stored") {
            val s = twelve()
            s.ops.open(current = s.lead, roomId = s.roomId, meetingId = s.meetingId)
            s.present(s.peers.take(5))
            val refused =
                shouldThrow<ConflictException> {
                    s.ops.establishQuorum(current = s.lead, roomId = s.roomId, meetingId = s.meetingId, acknowledgeNotMet = false)
                }
            refused.message shouldContain "5/6/12"
            refused.message!! shouldNotContain "MSX"
            s.ops.getState(current = s.lead, roomId = s.roomId)!!.establishedQuorum shouldBe null
            val established =
                s.ops
                    .establishQuorum(
                        current = s.lead,
                        roomId = s.roomId,
                        meetingId = s.meetingId,
                        acknowledgeNotMet = true,
                    ).establishedQuorum!!
            established.present shouldBe 5
            established.required shouldBe 6
            established.eligible shouldBe 12
            established.overridden shouldBe true
        }

        test("an established quorum that is met is not overridden; establishing again overwrites the line and audits twice") {
            val s = twelve()
            s.ops.open(current = s.lead, roomId = s.roomId, meetingId = s.meetingId)
            s.present(s.peers.take(6))
            s.ops
                .establishQuorum(current = s.lead, roomId = s.roomId, meetingId = s.meetingId, acknowledgeNotMet = false)
                .establishedQuorum!!
                .overridden shouldBe false
            s.present(listOf(s.peers[6]))
            val again =
                s.ops
                    .establishQuorum(
                        current = s.lead,
                        roomId = s.roomId,
                        meetingId = s.meetingId,
                        acknowledgeNotMet = false,
                    ).establishedQuorum!!
            again.present shouldBe 7
            fx.auditSnapshots(s.meetingId).count { it.contains("QUORUM_ESTABLISHED") } shouldBe 2
        }

        test("what changed since the establishment is visible: the live number moves, the established line stays") {
            val s = twelve()
            s.ops.open(current = s.lead, roomId = s.roomId, meetingId = s.meetingId)
            s.present(s.peers.take(6))
            s.ops.establishQuorum(current = s.lead, roomId = s.roomId, meetingId = s.meetingId, acknowledgeNotMet = false)
            s.present(listOf(s.peers[6]))
            val state = s.ops.getState(current = s.lead, roomId = s.roomId)!!
            state.liveQuorum!!.presentCount shouldBe 7
            state.establishedQuorum!!.present shouldBe 6
        }

        test("the roster is exactly the circle the quorum counts") {
            val s = twelve()
            s.ops.open(current = s.lead, roomId = s.roomId, meetingId = s.meetingId)
            val state = s.ops.getState(current = s.lead, roomId = s.roomId)!!
            state.roster.size shouldBe state.liveQuorum!!.eligibleMemberCount
        }
    })
