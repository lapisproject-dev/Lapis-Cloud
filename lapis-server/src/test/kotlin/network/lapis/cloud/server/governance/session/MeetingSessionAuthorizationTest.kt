package network.lapis.cloud.server.governance.session

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import kotlinx.serialization.json.Json
import network.lapis.cloud.server.db.DatabaseConfig
import network.lapis.cloud.server.db.DevSeedData
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.AttendanceStatus
import network.lapis.cloud.shared.domain.CommitteeType
import network.lapis.cloud.shared.domain.ConferenceMeetingStateDto
import network.lapis.cloud.shared.domain.MeetingSessionRole
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.domain.SessionAttendanceInput
import network.lapis.cloud.shared.rpc.ConflictException
import network.lapis.cloud.shared.rpc.ForbiddenException
import network.lapis.cloud.shared.rpc.NotFoundException
import kotlin.uuid.Uuid

/** V1.9.236 -- who may do what, and that everything else is one and the same not-found. */
class MeetingSessionAuthorizationTest :
    FunSpec({
        val fx = SessionFixture()

        beforeSpec {
            DatabaseConfig.connect()
            DevSeedData.seedIfEmpty(force = true)
        }
        afterSpec { fx.cleanup() }

        fun input(who: network.lapis.cloud.server.security.CurrentMember) =
            SessionAttendanceInput(memberId = who.memberId.toString(), status = AttendanceStatus.PRESENT)

        test("roles: chair is LEAD, the minute taker MINUTES, a seated member PARTICIPANT, BOARD LEAD") {
            val s = fx.scene()
            s.ops.getState(current = s.lead, roomId = s.roomId)!!.callerRole shouldBe MeetingSessionRole.LEAD
            s.ops.getState(current = s.minutes, roomId = s.roomId)!!.callerRole shouldBe MeetingSessionRole.MINUTES
            s.ops.getState(current = s.participant, roomId = s.roomId)!!.callerRole shouldBe MeetingSessionRole.PARTICIPANT
            val board = fx.newMember(role = AccountRole.BOARD)
            s.ops.getState(current = board, roomId = s.roomId)!!.callerRole shouldBe MeetingSessionRole.LEAD
        }

        test("a stranger, a federation guest and a friend read null and every mutation is the same not-found as for an unknown room") {
            val s = fx.scene()
            val outsiders =
                listOf(
                    fx.newMember(),
                    fx.newMember(status = MemberStatus.GUEST),
                    fx.newMember(status = MemberStatus.FRIEND),
                )
            val unknownRoom = Uuid.random()
            outsiders.forEach { who ->
                s.ops.getState(current = who, roomId = s.roomId) shouldBe null
                val real = shouldThrow<NotFoundException> { s.ops.open(current = who, roomId = s.roomId, meetingId = s.meetingId) }
                val unknown = shouldThrow<NotFoundException> { s.ops.open(current = who, roomId = unknownRoom, meetingId = s.meetingId) }
                real.message shouldBe unknown.message
                shouldThrow<NotFoundException> {
                    s.ops.setAttendance(
                        current = who,
                        roomId = s.roomId,
                        meetingId = s.meetingId,
                        input = input(s.peers[0]),
                    )
                }
                shouldThrow<NotFoundException> { s.ops.adoptConnected(current = who, roomId = s.roomId, meetingId = s.meetingId) }
                shouldThrow<NotFoundException> {
                    s.ops.establishQuorum(current = who, roomId = s.roomId, meetingId = s.meetingId, acknowledgeNotMet = true)
                }
                shouldThrow<NotFoundException> {
                    s.ops.moveAgenda(
                        current = who,
                        roomId = s.roomId,
                        meetingId = s.meetingId,
                        fromAgendaItemId = null,
                        toAgendaItemId = s.agenda[0].toString(),
                    )
                }
            }
            s.connected.calls shouldBe 0
        }

        test("a participant may read but every mutation is forbidden, and no LiveKit call is made for them") {
            val s = fx.scene()
            s.ops.open(current = s.lead, roomId = s.roomId, meetingId = s.meetingId)
            val p = s.participant
            val callsBefore = s.connected.calls
            s.ops.getState(current = p, roomId = s.roomId)!!.callerRole shouldBe MeetingSessionRole.PARTICIPANT
            shouldThrow<ForbiddenException> { s.ops.open(current = p, roomId = s.roomId, meetingId = s.meetingId) }
            shouldThrow<ForbiddenException> {
                s.ops.setAttendance(
                    current = p,
                    roomId = s.roomId,
                    meetingId = s.meetingId,
                    input = input(s.peers[0]),
                )
            }
            shouldThrow<ForbiddenException> { s.ops.adoptConnected(current = p, roomId = s.roomId, meetingId = s.meetingId) }
            shouldThrow<ForbiddenException> {
                s.ops.establishQuorum(
                    current = p,
                    roomId = s.roomId,
                    meetingId = s.meetingId,
                    acknowledgeNotMet = true,
                )
            }
            shouldThrow<ForbiddenException> {
                s.ops.moveAgenda(
                    current = p,
                    roomId = s.roomId,
                    meetingId = s.meetingId,
                    fromAgendaItemId = s.agenda[0].toString(),
                    toAgendaItemId = s.agenda[1].toString(),
                )
            }
            s.connected.calls shouldBe callsBefore
        }

        test("the minute taker may keep attendance and adopt, but not open, establish or move") {
            val s = fx.scene()
            s.ops.open(current = s.lead, roomId = s.roomId, meetingId = s.meetingId)
            val m = s.minutes
            s.ops
                .setAttendance(current = m, roomId = s.roomId, meetingId = s.meetingId, input = input(s.peers[0]))
                .roster
                .first {
                    it.memberId == s.peers[0].memberId.toString()
                }.status shouldBe
                AttendanceStatus.PRESENT
            s.connected.connect(s.peers[1])
            s.ops.adoptConnected(current = m, roomId = s.roomId, meetingId = s.meetingId) shouldBe 1
            shouldThrow<ForbiddenException> {
                s.ops.establishQuorum(
                    current = m,
                    roomId = s.roomId,
                    meetingId = s.meetingId,
                    acknowledgeNotMet = true,
                )
            }
            shouldThrow<ForbiddenException> {
                s.ops.moveAgenda(
                    current = m,
                    roomId = s.roomId,
                    meetingId = s.meetingId,
                    fromAgendaItemId = s.agenda[0].toString(),
                    toAgendaItemId = s.agenda[1].toString(),
                )
            }
            val fresh = fx.scene()
            shouldThrow<ForbiddenException> { fresh.ops.open(current = fresh.minutes, roomId = fresh.roomId, meetingId = fresh.meetingId) }
        }

        test("a foreign or deviating meeting id and an agenda item of another Sitzung are not found") {
            val s = fx.scene()
            val other = fx.scene()
            s.ops.open(current = s.lead, roomId = s.roomId, meetingId = s.meetingId)
            shouldThrow<NotFoundException> { s.ops.open(current = s.lead, roomId = s.roomId, meetingId = other.meetingId) }
            shouldThrow<NotFoundException> {
                s.ops.setAttendance(current = s.lead, roomId = s.roomId, meetingId = other.meetingId, input = input(s.peers[0]))
            }
            shouldThrow<NotFoundException> {
                s.ops.moveAgenda(
                    current = s.lead,
                    roomId = s.roomId,
                    meetingId = s.meetingId,
                    fromAgendaItemId = s.agenda[0].toString(),
                    toAgendaItemId = other.agenda[1].toString(),
                )
            }
            shouldThrow<NotFoundException> {
                s.ops.moveAgenda(
                    current = s.lead,
                    roomId = s.roomId,
                    meetingId = s.meetingId,
                    fromAgendaItemId = s.agenda[0].toString(),
                    toAgendaItemId = "not-a-uuid",
                )
            }
        }

        test("a room without a Sitzung answers null, mutations are not found") {
            val s = fx.scene()
            val bare = fx.newRoom(meetingId = null, creator = s.lead)
            s.ops.getState(current = s.lead, roomId = bare) shouldBe null
            s.ops.getState(current = s.lead, roomId = null) shouldBe null
            shouldThrow<NotFoundException> { s.ops.open(current = s.lead, roomId = bare, meetingId = s.meetingId) }
        }

        test("with conferencing switched off the read is null and the mutations are not found") {
            val s = fx.scene()
            val off = fx.operations(enabled = false)
            off.getState(current = s.lead, roomId = s.roomId) shouldBe null
            shouldThrow<NotFoundException> { off.open(current = s.lead, roomId = s.roomId, meetingId = s.meetingId) }
        }

        test("a participant gets no names, no roster, no live quorum -- not even in the serialized DTO") {
            val s = fx.scene()
            s.ops.open(current = s.lead, roomId = s.roomId, meetingId = s.meetingId)
            s.ops.establishQuorum(current = s.lead, roomId = s.roomId, meetingId = s.meetingId, acknowledgeNotMet = true)
            val state = s.ops.getState(current = s.participant, roomId = s.roomId)!!
            state.roster shouldBe emptyList()
            state.guests shouldBe emptyList()
            state.liveQuorum shouldBe null
            state.nextStepHint shouldBe null
            (state.establishedQuorum != null) shouldBe true
            val json = Json.encodeToString(ConferenceMeetingStateDto.serializer(), state)
            (s.everyone).forEach { who ->
                json shouldNotContain who.memberId.toString()
            }
            json shouldNotContain "MSX Peer"
            json shouldNotContain "MSX Chair"
        }

        test("the general assembly: members see it as participants, attendance writes are conflicts") {
            val s = fx.scene(type = CommitteeType.GENERAL_ASSEMBLY)
            val state = s.ops.getState(current = s.participant, roomId = s.roomId)!!
            state.ruleKind shouldBe network.lapis.cloud.shared.domain.AttendanceRuleKind.UNSUPPORTED
            s.ops.open(current = s.lead, roomId = s.roomId, meetingId = s.meetingId)
            s.ops.getState(current = s.lead, roomId = s.roomId)!!.roster shouldBe emptyList()
            shouldThrow<ConflictException> {
                s.ops.setAttendance(current = s.lead, roomId = s.roomId, meetingId = s.meetingId, input = input(s.peers[0]))
            }
            shouldThrow<ConflictException> { s.ops.adoptConnected(current = s.lead, roomId = s.roomId, meetingId = s.meetingId) }
        }
    })
