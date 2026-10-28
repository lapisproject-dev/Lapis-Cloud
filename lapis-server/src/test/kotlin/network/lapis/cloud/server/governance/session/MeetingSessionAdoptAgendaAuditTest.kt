package network.lapis.cloud.server.governance.session

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import network.lapis.cloud.server.db.DatabaseConfig
import network.lapis.cloud.server.db.DevSeedData
import network.lapis.cloud.server.db.generated.AgendaItemTable
import network.lapis.cloud.shared.domain.AttendanceStatus
import network.lapis.cloud.shared.domain.SessionAttendanceInput
import network.lapis.cloud.shared.rpc.AgendaMovedMeanwhileException
import network.lapis.cloud.shared.rpc.ConflictException
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.time.Duration.Companion.minutes

/** V1.9.236 -- adopting the connected participants, moving the agenda, and the audit trail. */
class MeetingSessionAdoptAgendaAuditTest :
    FunSpec({
        val fx = SessionFixture()

        beforeSpec {
            DatabaseConfig.connect()
            DevSeedData.seedIfEmpty(force = true)
        }
        afterSpec { fx.cleanup() }

        test("adopt never overwrites an existing entry and returns the number of new ones") {
            val s = fx.scene()
            s.ops.open(current = s.lead, roomId = s.roomId, meetingId = s.meetingId)
            s.ops.setAttendance(
                current = s.lead,
                roomId = s.roomId,
                meetingId = s.meetingId,
                input = SessionAttendanceInput(memberId = s.peers[0].memberId.toString(), status = AttendanceStatus.EXCUSED),
            )
            s.connected.connect(s.peers[0], s.peers[1], s.peers[2])
            s.ops.adoptConnected(current = s.lead, roomId = s.roomId, meetingId = s.meetingId) shouldBe 2
            val att = fx.attendance(s.meetingId)
            att[s.peers[0].memberId] shouldBe AttendanceStatus.EXCUSED
            att[s.peers[1].memberId] shouldBe AttendanceStatus.PRESENT
            att[s.peers[2].memberId] shouldBe AttendanceStatus.PRESENT
            s.ops.adoptConnected(current = s.lead, roomId = s.roomId, meetingId = s.meetingId) shouldBe 0
        }

        test("non-UUID identities are ignored; connected people outside the roster are only counted") {
            val s = fx.scene()
            s.ops.open(current = s.lead, roomId = s.roomId, meetingId = s.meetingId)
            val outsider = fx.newMember()
            s.connected.connect(s.peers[0], outsider)
            s.connected.identities += "egress-bot"
            s.connected.identities += "not-a-uuid-at-all"
            val state = s.ops.getState(current = s.lead, roomId = s.roomId)!!
            state.adoptableCount shouldBe 1
            state.extraConnectedCount shouldBe 1
            state.roster.first { it.memberId == s.peers[0].memberId.toString() }.connected shouldBe true
            state.roster.first { it.memberId == s.peers[1].memberId.toString() }.connected shouldBe false
            s.ops.adoptConnected(current = s.lead, roomId = s.roomId, meetingId = s.meetingId) shouldBe 1
            fx.attendance(s.meetingId).containsKey(outsider.memberId) shouldBe false
        }

        test("a LiveKit failure is an empty set: nothing is adopted, nothing breaks") {
            val s = fx.scene()
            s.ops.open(current = s.lead, roomId = s.roomId, meetingId = s.meetingId)
            s.connected.connect(s.peers[0])
            s.connected.failing = true
            s.ops.adoptConnected(current = s.lead, roomId = s.roomId, meetingId = s.meetingId) shouldBe 0
            s.ops.getState(current = s.lead, roomId = s.roomId)!!.adoptableCount shouldBe 0
        }

        test("moving the agenda: a stale from is a conflict, jumping straight to a later item works, deleting the current item clears it") {
            val s = fx.scene()
            s.ops.open(current = s.lead, roomId = s.roomId, meetingId = s.meetingId)
            shouldThrow<AgendaMovedMeanwhileException> {
                s.ops.moveAgenda(
                    current = s.lead,
                    roomId = s.roomId,
                    meetingId = s.meetingId,
                    fromAgendaItemId = s.agenda[1].toString(),
                    toAgendaItemId = s.agenda[2].toString(),
                )
            }
            shouldThrow<AgendaMovedMeanwhileException> {
                s.ops.moveAgenda(
                    current = s.lead,
                    roomId = s.roomId,
                    meetingId = s.meetingId,
                    fromAgendaItemId = null,
                    toAgendaItemId = s.agenda[2].toString(),
                )
            }
            s.ops
                .moveAgenda(
                    current = s.lead,
                    roomId = s.roomId,
                    meetingId = s.meetingId,
                    fromAgendaItemId = s.agenda[0].toString(),
                    toAgendaItemId = s.agenda[2].toString(),
                ).currentAgendaItemId shouldBe
                s.agenda[2].toString()
            s.ops
                .moveAgenda(
                    current = s.lead,
                    roomId = s.roomId,
                    meetingId = s.meetingId,
                    fromAgendaItemId = s.agenda[2].toString(),
                    toAgendaItemId = s.agenda[0].toString(),
                ).currentAgendaItemId shouldBe
                s.agenda[0].toString()
            transaction { AgendaItemTable.deleteWhere { AgendaItemTable.id eq s.agenda[0] } }
            val state = s.ops.getState(current = s.lead, roomId = s.roomId)!!
            state.currentAgendaItemId shouldBe null
            state.agenda shouldHaveSize 2
        }

        test("audit: four kinds of entries, ids and counts only, never a name") {
            val s = fx.scene()
            s.ops.open(current = s.lead, roomId = s.roomId, meetingId = s.meetingId)
            s.connected.connect(s.peers[0])
            s.ops.adoptConnected(current = s.lead, roomId = s.roomId, meetingId = s.meetingId)
            s.ops.establishQuorum(current = s.lead, roomId = s.roomId, meetingId = s.meetingId, acknowledgeNotMet = true)
            s.ops.moveAgenda(
                current = s.lead,
                roomId = s.roomId,
                meetingId = s.meetingId,
                fromAgendaItemId = s.agenda[0].toString(),
                toAgendaItemId = s.agenda[1].toString(),
            )
            s.ops.setAttendance(
                current = s.lead,
                roomId = s.roomId,
                meetingId = s.meetingId,
                input = SessionAttendanceInput(memberId = s.peers[1].memberId.toString(), status = AttendanceStatus.PRESENT),
            )
            val entries = fx.auditSnapshots(s.meetingId)
            entries shouldHaveSize 4
            listOf("OPENED", "ATTENDANCE_ADOPTED", "QUORUM_ESTABLISHED", "AGENDA_MOVED").forEachIndexed { i, kind ->
                entries[i] shouldContain
                    kind
            }
            entries.forEach { e ->
                e shouldNotContain "MSX"
                s.everyone.forEach { who -> e shouldNotContain who.memberId.toString() }
            }
            entries[1] shouldContain "\"adoptedCount\":1"
        }

        test("a rate-limited caller gets a conflict") {
            val s = fx.scene()
            val tight =
                MeetingSessionOperations(
                    conferencesEnabled = { true },
                    connectedMembers = s.connected,
                    readLimiter =
                        network.lapis.cloud.server.federation
                            .FederationInboxRateLimiter(maxRequests = 2, window = 1.minutes),
                    mutateLimiter = fx.bigLimiter(),
                )
            tight.getState(current = s.lead, roomId = s.roomId)
            tight.getState(current = s.lead, roomId = s.roomId)
            shouldThrow<ConflictException> { tight.getState(current = s.lead, roomId = s.roomId) }
        }
    })
