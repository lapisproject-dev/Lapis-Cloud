package network.lapis.cloud.server.governance.session

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.datetime.LocalDate
import network.lapis.cloud.server.db.DatabaseConfig
import network.lapis.cloud.server.db.DevSeedData
import network.lapis.cloud.server.security.CurrentMember
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.CommitteeType
import network.lapis.cloud.shared.domain.MeetingSessionRole
import network.lapis.cloud.shared.domain.MeetingStatus
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.domain.ProtocolTaskInput
import network.lapis.cloud.shared.rpc.BadRequestException
import network.lapis.cloud.shared.rpc.ConflictException
import network.lapis.cloud.shared.rpc.ForbiddenException
import network.lapis.cloud.shared.rpc.NotFoundException
import kotlin.uuid.Uuid

/** V1.9.239 -- who may read and write the Protokoll draft, that everything else is ONE not-found, and the state machine of the draft. */
class MeetingProtocolDraftAccessTest :
    FunSpec({
        val fx = SessionFixture()

        beforeSpec {
            DatabaseConfig.connect()
            DevSeedData.seedIfEmpty(force = true)
        }
        afterSpec { fx.cleanup() }

        fun task(
            s: SessionScene,
            assignee: CurrentMember = s.peers[0],
        ) = ProtocolTaskInput(
            agendaItemId = s.agenda[0].toString(),
            assigneeMemberId = assignee.memberId.toString(),
            description = "MSX Aufgabe",
            dueDate = null,
        )

        test("roles of the draft: chair LEAD, minute taker MINUTES, seated member PARTICIPANT, BOARD LEAD") {
            val s = fx.scene()
            val p = fx.protocolOps()
            p.get(current = s.lead, meetingId = s.meetingId.toString()).role shouldBe MeetingSessionRole.LEAD
            p.get(current = s.minutes, meetingId = s.meetingId.toString()).role shouldBe MeetingSessionRole.MINUTES
            p.get(current = s.participant, meetingId = s.meetingId.toString()).role shouldBe MeetingSessionRole.PARTICIPANT
            val board = fx.newMember(role = AccountRole.BOARD)
            p.get(current = board, meetingId = s.meetingId.toString()).role shouldBe MeetingSessionRole.LEAD
        }

        test("every kind of outsider gets the SAME not-found from all four methods, before anything is read or written") {
            val s = fx.scene()
            val p = s.openedProtocolBlocking()
            val unseated = fx.newMember(name = "MSX Unseated")
            val guest = fx.newMember(status = MemberStatus.GUEST)
            val friend = fx.newMember(status = MemberStatus.FRIEND)
            val meeting = s.meetingId.toString()
            val item = s.agenda[0].toString()
            val reference = shouldThrow<NotFoundException> { p.get(current = unseated, meetingId = Uuid.random().toString()) }.message
            listOf(unseated, guest, friend).forEach { who ->
                shouldThrow<NotFoundException> { p.get(current = who, meetingId = meeting) }.message shouldBe reference
                shouldThrow<NotFoundException> {
                    p.saveBlock(current = who, meetingId = meeting, agendaItemId = item, expectedVersion = 0, body = "x")
                }.message shouldBe reference
                shouldThrow<NotFoundException> { p.addTask(current = who, meetingId = meeting, input = task(s)) }.message shouldBe reference
                shouldThrow<NotFoundException> {
                    p.removeTask(current = who, meetingId = meeting, taskId = Uuid.random().toString())
                }.message shouldBe reference
            }
            // unknown and malformed ids look exactly alike
            shouldThrow<NotFoundException> { p.get(current = s.lead, meetingId = "not-a-uuid") }.message shouldBe reference
            shouldThrow<NotFoundException> { p.get(current = s.lead, meetingId = Uuid.random().toString()) }.message shouldBe reference
            protocolDraftCount(s.meetingId) shouldBe 0
        }

        test("a participant reads, but every write is forbidden and leaves no row and no audit entry") {
            val s = fx.scene()
            val p = s.openedProtocolBlocking()
            val before = auditCount(s.meetingId)
            val meeting = s.meetingId.toString()
            p.get(current = s.participant, meetingId = meeting).editable shouldBe false
            shouldThrow<ForbiddenException> {
                p.saveBlock(
                    current = s.participant,
                    meetingId = meeting,
                    agendaItemId = s.agenda[0].toString(),
                    expectedVersion = 0,
                    body = "x",
                )
            }
            shouldThrow<ForbiddenException> { p.addTask(current = s.participant, meetingId = meeting, input = task(s)) }
            shouldThrow<ForbiddenException> {
                p.removeTask(
                    current = s.participant,
                    meetingId = meeting,
                    taskId = Uuid.random().toString(),
                )
            }
            protocolDraftCount(s.meetingId) shouldBe 0
            auditCount(s.meetingId) shouldBe before
        }

        test("the chair, the minute taker and BOARD may write") {
            val s = fx.scene()
            val p = s.openedProtocolBlocking()
            val board = fx.newMember(role = AccountRole.BOARD)
            val meeting = s.meetingId.toString()
            listOf(s.lead, s.minutes, board).forEachIndexed { index, who ->
                val saved =
                    p.saveBlock(
                        current = who,
                        meetingId = meeting,
                        agendaItemId = s.agenda[index].toString(),
                        expectedVersion = 0,
                        body = "MSX Text $index",
                    )
                saved.saved shouldBe true
            }
        }

        test("IDOR: an agenda item or a task of another Sitzung is as unknown as a random id") {
            val a = fx.scene()
            val b = fx.scene()
            val pa = a.openedProtocolBlocking()
            b.openedProtocolBlocking()
            val reference = shouldThrow<NotFoundException> { pa.get(current = a.lead, meetingId = "x") }.message
            shouldThrow<NotFoundException> {
                pa.saveBlock(
                    current = a.lead,
                    meetingId = a.meetingId.toString(),
                    agendaItemId = b.agenda[0].toString(),
                    expectedVersion = 0,
                    body = "x",
                )
            }.message shouldBe reference
            shouldThrow<NotFoundException> {
                pa.addTask(
                    current = a.lead,
                    meetingId = a.meetingId.toString(),
                    input = task(a).copy(agendaItemId = b.agenda[0].toString()),
                )
            }.message shouldBe reference
            val foreignTask = fx.protocolOps().addTask(current = b.lead, meetingId = b.meetingId.toString(), input = task(b))
            pa.addTask(current = a.lead, meetingId = a.meetingId.toString(), input = task(a))
            shouldThrow<NotFoundException> {
                pa.removeTask(current = a.lead, meetingId = a.meetingId.toString(), taskId = foreignTask.taskId)
            }.message shouldBe reference
            protocolTaskCount(b.meetingId) shouldBe 1
        }

        test("a task goes to a person of the committee only; in a general assembly only to a person recorded as present") {
            val s = fx.scene()
            val p = s.openedProtocolBlocking()
            val outsider = fx.newMember()
            shouldThrow<BadRequestException> { p.addTask(current = s.lead, meetingId = s.meetingId.toString(), input = task(s, outsider)) }
            p.addTask(current = s.lead, meetingId = s.meetingId.toString(), input = task(s, s.peers[0])).assigneeName shouldBe "MSX Peer 01"

            val mv = fx.scene(type = CommitteeType.GENERAL_ASSEMBLY, peers = 2)
            val pm = mv.openedProtocolBlocking()
            val present = fx.newMember(name = "MSX Anwesender")
            val absent = fx.newMember(name = "MSX Fehlender")
            fx.putAttendance(
                meetingId = mv.meetingId,
                member = present,
                status = network.lapis.cloud.shared.domain.AttendanceStatus.PRESENT,
            )
            val input = task(mv)
            shouldThrow<BadRequestException> {
                pm.addTask(
                    current = mv.lead,
                    meetingId = mv.meetingId.toString(),
                    input = input.copy(assigneeMemberId = absent.memberId.toString()),
                )
            }
            pm.addTask(
                current = mv.lead,
                meetingId = mv.meetingId.toString(),
                input = input.copy(assigneeMemberId = present.memberId.toString()),
            )
            pm.get(current = mv.lead, meetingId = mv.meetingId.toString()).assignableMembers.map { it.memberId } shouldBe
                listOf(present.memberId.toString())
        }

        test(
            "state machine: not opened is read-only, open and held are writable, cancelled is read-only again, a locked draft refuses everything",
        ) {
            val s = fx.scene()
            val p = fx.protocolOps()
            val meeting = s.meetingId.toString()
            val item = s.agenda[0].toString()
            p.get(current = s.lead, meetingId = meeting).editable shouldBe false
            shouldThrow<ConflictException> {
                p.saveBlock(
                    current = s.lead,
                    meetingId = meeting,
                    agendaItemId = item,
                    expectedVersion = 0,
                    body = "x",
                )
            }
            shouldThrow<ConflictException> { p.addTask(current = s.lead, meetingId = meeting, input = task(s)) }

            s.ops.open(current = s.lead, roomId = s.roomId, meetingId = s.meetingId)
            p.get(current = s.lead, meetingId = meeting).editable shouldBe true
            p.saveBlock(current = s.lead, meetingId = meeting, agendaItemId = item, expectedVersion = 0, body = "offen").saved shouldBe true

            fx.setStatus(meetingId = s.meetingId, status = MeetingStatus.HELD)
            p.get(current = s.lead, meetingId = meeting).editable shouldBe true
            p.saveBlock(current = s.lead, meetingId = meeting, agendaItemId = item, expectedVersion = 1, body = "gehalten").saved shouldBe
                true
            p.addTask(current = s.lead, meetingId = meeting, input = task(s))

            lockProtocolDraft(s.meetingId)
            p.get(current = s.lead, meetingId = meeting).editable shouldBe false
            p.get(current = s.lead, meetingId = meeting).assignableMembers shouldBe emptyList()
            shouldThrow<ConflictException> {
                p.saveBlock(
                    current = s.lead,
                    meetingId = meeting,
                    agendaItemId = item,
                    expectedVersion = 2,
                    body = "x",
                )
            }
            shouldThrow<ConflictException> { p.addTask(current = s.lead, meetingId = meeting, input = task(s)) }
            val taskId =
                p
                    .get(current = s.lead, meetingId = meeting)
                    .items[0]
                    .tasks
                    .single()
                    .taskId
            shouldThrow<ConflictException> { p.removeTask(current = s.lead, meetingId = meeting, taskId = taskId) }
            // reading still works for everybody
            p.get(current = s.participant, meetingId = meeting).items[0].body shouldBe "gehalten"

            val cancelled = fx.scene()
            val pc = cancelled.openedProtocolBlocking()
            fx.setStatus(meetingId = cancelled.meetingId, status = MeetingStatus.CANCELLED)
            pc.get(current = cancelled.lead, meetingId = cancelled.meetingId.toString()).editable shouldBe false
            shouldThrow<ConflictException> {
                pc.saveBlock(
                    current = cancelled.lead,
                    meetingId = cancelled.meetingId.toString(),
                    agendaItemId = cancelled.agenda[0].toString(),
                    expectedVersion = 0,
                    body = "x",
                )
            }
        }

        test("rate limits: the 61st read and the 31st write within a minute are refused") {
            val s = fx.scene()
            s.openedProtocolBlocking()
            val limited = MeetingProtocolDraftOperations()
            val meeting = s.meetingId.toString()
            // unique member and meeting per run: the process-wide limiters key on member and meeting
            val caller = fx.newMember(name = "MSX Rate", role = AccountRole.BOARD)
            repeat(60) { limited.get(current = caller, meetingId = meeting) }
            shouldThrow<ConflictException> { limited.get(current = caller, meetingId = meeting) }
            repeat(30) {
                limited.saveBlock(
                    current = caller,
                    meetingId = meeting,
                    agendaItemId = s.agenda[0].toString(),
                    expectedVersion = 0,
                    body = "",
                )
            }
            shouldThrow<ConflictException> {
                limited.saveBlock(
                    current = caller,
                    meetingId = meeting,
                    agendaItemId = s.agenda[0].toString(),
                    expectedVersion = 0,
                    body = "",
                )
            }
        }

        test("rate limit is per member: switching to a random meetingId does not reset the bucket") {
            val s = fx.scene()
            val limited = MeetingProtocolDraftOperations()
            val caller = fx.newMember(name = "MSX Rate Rotate", role = AccountRole.BOARD)
            repeat(60) {
                // unknown meetings are refused with NotFound but still count against the member
                runCatching { limited.get(current = caller, meetingId = randomMeetingId()) }
            }
            shouldThrow<ConflictException> {
                limited.get(current = caller, meetingId = randomMeetingId())
            }
            shouldThrow<ConflictException> { limited.get(current = caller, meetingId = s.meetingId.toString()) }
        }

        test("a due date can be set, and one before the day of the Sitzung is refused") {
            val s = fx.scene()
            val p = s.openedProtocolBlocking()
            val meeting = s.meetingId.toString()
            val onTheDay = task(s).copy(dueDate = LocalDate(2030, 5, 4))
            p.addTask(current = s.lead, meetingId = meeting, input = onTheDay).dueDate shouldBe LocalDate(2030, 5, 4)
            shouldThrow<BadRequestException> {
                p.addTask(current = s.lead, meetingId = meeting, input = onTheDay.copy(dueDate = LocalDate(2030, 5, 3)))
            }
        }
    })

private fun SessionScene.openedProtocolBlocking(): MeetingProtocolDraftOperations = kotlinx.coroutines.runBlocking { openedProtocol() }

private fun randomMeetingId(): String =
    kotlin.uuid.Uuid
        .random()
        .toString()
