package network.lapis.cloud.server.governance.session

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import network.lapis.cloud.server.db.DatabaseConfig
import network.lapis.cloud.server.db.DevSeedData
import network.lapis.cloud.shared.domain.AttendanceStatus
import network.lapis.cloud.shared.domain.MeetingSessionNextStep
import network.lapis.cloud.shared.domain.MeetingSessionPhase
import network.lapis.cloud.shared.domain.MeetingStatus
import network.lapis.cloud.shared.domain.SessionAttendanceInput
import network.lapis.cloud.shared.rpc.ConflictException

/** V1.9.236 -- the derived phase NOT_OPENED -> OPEN -> CLOSED and what each phase allows. */
class MeetingSessionStateMachineTest :
    FunSpec({
        val fx = SessionFixture()

        beforeSpec {
            DatabaseConfig.connect()
            DevSeedData.seedIfEmpty(force = true)
        }
        afterSpec { fx.cleanup() }

        test("the phase is derived from status and opened_at") {
            meetingSessionPhase(status = MeetingStatus.PLANNED, openedAt = null) shouldBe MeetingSessionPhase.NOT_OPENED
            meetingSessionPhase(status = MeetingStatus.PLANNED, openedAt = kotlinx.datetime.LocalDateTime(2030, 1, 1, 0, 0)) shouldBe
                MeetingSessionPhase.OPEN
            meetingSessionPhase(status = MeetingStatus.HELD, openedAt = null) shouldBe MeetingSessionPhase.CLOSED
            meetingSessionPhase(status = MeetingStatus.CANCELLED, openedAt = kotlinx.datetime.LocalDateTime(2030, 1, 1, 0, 0)) shouldBe
                MeetingSessionPhase.CLOSED
        }

        test("a fresh room is NOT_OPENED and the lead is told to open it") {
            val s = fx.scene()
            val state = s.ops.getState(current = s.lead, roomId = s.roomId)!!
            state.phase shouldBe MeetingSessionPhase.NOT_OPENED
            state.openedAt shouldBe null
            state.nextStepHint shouldBe MeetingSessionNextStep.OPEN_MEETING
            state.currentAgendaItemId shouldBe null
        }

        test("opening sets opened_at and the first agenda item; a second opening is a conflict") {
            val s = fx.scene()
            val opened = s.ops.open(current = s.lead, roomId = s.roomId, meetingId = s.meetingId)
            opened.phase shouldBe MeetingSessionPhase.OPEN
            (opened.openedAt != null) shouldBe true
            opened.currentAgendaItemId shouldBe s.agenda.first().toString()
            shouldThrow<ConflictException> { s.ops.open(current = s.lead, roomId = s.roomId, meetingId = s.meetingId) }
        }

        test("a Sitzung without agenda opens with no current item") {
            val s = fx.scene(agenda = emptyList())
            s.ops.open(current = s.lead, roomId = s.roomId, meetingId = s.meetingId).currentAgendaItemId shouldBe null
        }

        test("before the opening: attendance, quorum, adoption and agenda moves are conflicts") {
            val s = fx.scene()
            val target = s.peers.first()
            shouldThrow<ConflictException> {
                s.ops.setAttendance(
                    current = s.lead,
                    roomId = s.roomId,
                    meetingId = s.meetingId,
                    input = SessionAttendanceInput(memberId = target.memberId.toString(), status = AttendanceStatus.PRESENT),
                )
            }
            shouldThrow<ConflictException> {
                s.ops.establishQuorum(current = s.lead, roomId = s.roomId, meetingId = s.meetingId, acknowledgeNotMet = true)
            }
            shouldThrow<ConflictException> { s.ops.adoptConnected(current = s.lead, roomId = s.roomId, meetingId = s.meetingId) }
            shouldThrow<ConflictException> {
                s.ops.moveAgenda(
                    current = s.lead,
                    roomId = s.roomId,
                    meetingId = s.meetingId,
                    fromAgendaItemId = null,
                    toAgendaItemId = s.agenda[1].toString(),
                )
            }
        }

        test("a HELD or CANCELLED Sitzung is closed: every mutation is a conflict, the read still works") {
            listOf(MeetingStatus.HELD, MeetingStatus.CANCELLED).forEach { closed ->
                val s = fx.scene()
                s.ops.open(current = s.lead, roomId = s.roomId, meetingId = s.meetingId)
                fx.setStatus(meetingId = s.meetingId, status = closed)
                val target = s.peers.first()
                s.ops.getState(current = s.lead, roomId = s.roomId)!!.phase shouldBe MeetingSessionPhase.CLOSED
                s.ops.getState(current = s.lead, roomId = s.roomId)!!.nextStepHint shouldBe MeetingSessionNextStep.NONE
                shouldThrow<ConflictException> { s.ops.open(current = s.lead, roomId = s.roomId, meetingId = s.meetingId) }
                shouldThrow<ConflictException> {
                    s.ops.setAttendance(
                        current = s.lead,
                        roomId = s.roomId,
                        meetingId = s.meetingId,
                        input = SessionAttendanceInput(memberId = target.memberId.toString(), status = AttendanceStatus.PRESENT),
                    )
                }
                shouldThrow<ConflictException> { s.ops.adoptConnected(current = s.lead, roomId = s.roomId, meetingId = s.meetingId) }
                shouldThrow<ConflictException> {
                    s.ops.establishQuorum(current = s.lead, roomId = s.roomId, meetingId = s.meetingId, acknowledgeNotMet = true)
                }
                shouldThrow<ConflictException> {
                    s.ops.moveAgenda(
                        current = s.lead,
                        roomId = s.roomId,
                        meetingId = s.meetingId,
                        fromAgendaItemId = s.agenda[0].toString(),
                        toAgendaItemId = s.agenda[1].toString(),
                    )
                }
            }
        }

        test("an ended room is read-only") {
            val s = fx.scene()
            val (meetingId, _) = fx.newMeeting(committee = s.committee, chair = s.lead, agenda = listOf("A"))
            val ended = fx.newRoom(meetingId = meetingId, creator = s.lead, ended = true)
            shouldThrow<ConflictException> { s.ops.open(current = s.lead, roomId = ended, meetingId = meetingId) }
            (s.ops.getState(current = s.lead, roomId = ended) != null) shouldBe true
        }

        test("the lead is guided: adopt first when somebody is connected, then establish, then next agenda item") {
            val s = fx.scene()
            s.ops.open(current = s.lead, roomId = s.roomId, meetingId = s.meetingId)
            s.connected.connect(s.peers[0])
            s.ops.getState(current = s.lead, roomId = s.roomId)!!.nextStepHint shouldBe MeetingSessionNextStep.ADOPT_CONNECTED
            s.ops.adoptConnected(current = s.lead, roomId = s.roomId, meetingId = s.meetingId) shouldBe 1
            s.ops.getState(current = s.lead, roomId = s.roomId)!!.nextStepHint shouldBe MeetingSessionNextStep.ESTABLISH_QUORUM
            s.ops
                .establishQuorum(
                    current = s.lead,
                    roomId = s.roomId,
                    meetingId = s.meetingId,
                    acknowledgeNotMet = true,
                ).nextStepHint shouldBe
                MeetingSessionNextStep.NEXT_AGENDA_ITEM
            s.ops
                .moveAgenda(
                    current = s.lead,
                    roomId = s.roomId,
                    meetingId = s.meetingId,
                    fromAgendaItemId = s.agenda[0].toString(),
                    toAgendaItemId = s.agenda[2].toString(),
                ).nextStepHint shouldBe
                MeetingSessionNextStep.NONE
        }
    })
