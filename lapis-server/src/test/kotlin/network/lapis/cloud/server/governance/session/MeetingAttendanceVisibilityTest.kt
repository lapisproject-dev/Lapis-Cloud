package network.lapis.cloud.server.governance.session

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.server.application.call
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import network.lapis.cloud.server.db.DatabaseConfig
import network.lapis.cloud.server.db.DevSeedData
import network.lapis.cloud.server.rpc.GovernanceService
import network.lapis.cloud.server.security.CurrentMember
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.AgendaItemDto
import network.lapis.cloud.shared.domain.AttendanceStatus
import network.lapis.cloud.shared.domain.CommitteeType
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.domain.ResolutionDto
import network.lapis.cloud.shared.rpc.NotFoundException
import kotlin.uuid.Uuid

/**
 * V1.9.241 -- attendance, quorum and the Protokoll draft of a Sitzung are visible only to people with a role in it. Everyone else gets the same
 * not-found as for an unknown id; `getMeetingDetail` returns an empty attendance, `quorum == null` and `attendanceVisible == false`.
 */
class MeetingAttendanceVisibilityTest :
    FunSpec({
        val fx = SessionFixture()

        beforeSpec {
            DatabaseConfig.connect()
            DevSeedData.seedIfEmpty(force = true)
        }
        afterSpec { fx.cleanup() }

        fun Route.visibilityRoutes() {
            get("/t/detail/{id}") {
                val d = GovernanceService(call = call).getMeetingDetail(call.parameters["id"]!!)
                val agenda = Json.encodeToString(ListSerializer(AgendaItemDto.serializer()), d.agenda)
                val res = Json.encodeToString(ListSerializer(ResolutionDto.serializer()), d.resolutions)
                val head = "vis=${d.attendanceVisible};att=${d.attendance.size};quorumNull=${d.quorum == null}"
                call.respondText("$head;agenda=$agenda;res=$res")
            }
            for (name in listOf("att", "quorum", "draft")) {
                get("/t/$name/{id}") {
                    val s = GovernanceService(call = call)
                    val id = call.parameters["id"]!!
                    val text =
                        try {
                            when (name) {
                                "att" -> "OK:${s.getAttendance(id).size}"
                                "quorum" -> "OK:${s.checkQuorum(id).presentCount}"
                                else -> "OK:${s.generateProtocolDraft(id).attendance.size}"
                            }
                        } catch (e: NotFoundException) {
                            "NF:${e.message}"
                        }
                    call.respondText(text)
                }
            }
        }

        suspend fun ApplicationTestBuilder.read(
            path: String,
            who: CurrentMember,
            id: String,
        ): String = client.get("/t/$path/$id") { header("X-Member-Id", who.memberId.toString()) }.bodyAsText()

        fun withApp(block: suspend ApplicationTestBuilder.() -> Unit) =
            testApplication {
                application { routing { visibilityRoutes() } }
                block()
            }

        // ---- the role matrix --------------------------------------------------------------------------------------------------------

        test("role matrix: people with a role see attendance, quorum and the draft; everybody else sees nothing") {
            val s = fx.scene()
            s.peers.forEach { fx.putAttendance(meetingId = s.meetingId, member = it, status = AttendanceStatus.PRESENT) }
            // chair and minute taker WITHOUT a seat in the committee
            val c2 = fx.newCommittee()
            val chairNoSeat = fx.newMember(name = "MSX ChairNoSeat")
            val takerNoSeat = fx.newMember(name = "MSX TakerNoSeat")
            val (m2, _) = fx.newMeeting(committee = c2, chair = chairNoSeat, minuteTaker = takerNoSeat)
            fx.putAttendance(meetingId = m2, member = chairNoSeat, status = AttendanceStatus.PRESENT)

            val board = fx.newMember(role = AccountRole.BOARD)
            val admin = fx.newMember(role = AccountRole.ADMIN)
            val otherCommitteeMember = fx.newMember(name = "MSX Other").also { fx.seat(member = it, committee = c2) }
            val plain = fx.newMember(name = "MSX Plain")
            val guest = fx.newMember(status = MemberStatus.GUEST)
            val friend = fx.newMember(status = MemberStatus.FRIEND)

            val withRoleOnS = listOf(s.lead, s.minutes, s.participant, s.peers[0], board, admin)
            val withoutRoleOnS = listOf(otherCommitteeMember, plain, guest, friend)
            val withRoleOnM2 = listOf(chairNoSeat, takerNoSeat, board, admin)

            withApp {
                val reference = read("detail", s.lead, s.meetingId.toString()).substringAfter(";agenda=")
                (withRoleOnS).forEach { who ->
                    val d = read("detail", who, s.meetingId.toString())
                    d.substringBefore(";agenda=") shouldBe "vis=true;att=${s.peers.size};quorumNull=false"
                    read("att", who, s.meetingId.toString()) shouldBe "OK:${s.peers.size}"
                    read("quorum", who, s.meetingId.toString()).startsWith("OK:") shouldBe true
                    read("draft", who, s.meetingId.toString()) shouldBe "OK:${s.peers.size}"
                }
                withoutRoleOnS.forEach { who ->
                    val d = read("detail", who, s.meetingId.toString())
                    d.substringBefore(";agenda=") shouldBe "vis=false;att=0;quorumNull=true"
                    // agenda and resolutions are unchanged for everybody
                    d.substringAfter(";agenda=") shouldBe reference
                    val unknown = Uuid.random().toString()
                    listOf("att", "quorum", "draft").forEach { path ->
                        val denied = read(path, who, s.meetingId.toString())
                        val missing = read(path, who, unknown)
                        denied.startsWith("NF:") shouldBe true
                        // no existence oracle: the same text for the same id form
                        denied.replace(s.meetingId.toString(), "<id>") shouldBe missing.replace(unknown, "<id>")
                    }
                }
                withRoleOnM2.forEach { who ->
                    read("detail", who, m2.toString()).substringBefore(";agenda=") shouldBe "vis=true;att=1;quorumNull=false"
                    read("att", who, m2.toString()) shouldBe "OK:1"
                    read("quorum", who, m2.toString()).startsWith("OK:") shouldBe true
                    read("draft", who, m2.toString()) shouldBe "OK:1"
                }
                // the invariant: quorum == null exactly when attendanceVisible == false
                (withRoleOnS + withoutRoleOnS).forEach { who ->
                    val head = read("detail", who, s.meetingId.toString()).substringBefore(";agenda=")
                    (head.contains("vis=false")) shouldBe head.contains("quorumNull=true")
                }
            }
        }

        test("a malformed id is the same invalid-id not-found for everybody (no oracle)") {
            val s = fx.scene()
            val plain = fx.newMember()
            withApp {
                listOf("att", "quorum", "draft").forEach { path ->
                    read(path, plain, "nope") shouldBe read(path, s.lead, "nope")
                    read(path, plain, "nope") shouldContain "Invalid id"
                }
            }
        }

        test("general assembly: an ACTIVE member sees the attendance, a withdrawn one does not") {
            val s = fx.scene(type = CommitteeType.GENERAL_ASSEMBLY, peers = 2)
            fx.putAttendance(meetingId = s.meetingId, member = s.peers[0], status = AttendanceStatus.PRESENT)
            val active = fx.newMember(name = "MSX Active")
            val withdrawn = fx.newMember(name = "MSX Withdrawn", status = MemberStatus.WITHDRAWN)
            withApp {
                read("detail", active, s.meetingId.toString()).substringBefore(";agenda=") shouldBe "vis=true;att=1;quorumNull=false"
                read("att", active, s.meetingId.toString()) shouldBe "OK:1"
                read("detail", withdrawn, s.meetingId.toString()).substringBefore(";agenda=") shouldBe "vis=false;att=0;quorumNull=true"
                read("att", withdrawn, s.meetingId.toString()).startsWith("NF:") shouldBe true
            }
        }

        test("an unknown id used to be an empty list for getAttendance -- now it is not-found") {
            val s = fx.scene()
            withApp {
                read("att", s.lead, Uuid.random().toString()).startsWith("NF:") shouldBe true
            }
        }
    })
