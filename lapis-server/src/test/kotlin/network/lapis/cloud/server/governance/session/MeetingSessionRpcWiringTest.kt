package network.lapis.cloud.server.governance.session

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldStartWith
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.response.respondText
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import network.lapis.cloud.server.conference.ConferenceAudioGrantState
import network.lapis.cloud.server.conference.ConferenceNotesState
import network.lapis.cloud.server.conference.ConferenceWhiteboardState
import network.lapis.cloud.server.conference.NoOpSecretBallotStreamGuard
import network.lapis.cloud.server.db.DatabaseConfig
import network.lapis.cloud.server.db.DevSeedData
import network.lapis.cloud.server.db.generated.MeetingTable
import network.lapis.cloud.server.events.EVENT_ONLINE_CONFIG
import network.lapis.cloud.server.events.EventFakeLiveKit
import network.lapis.cloud.server.events.EventOnlineServices
import network.lapis.cloud.server.federation.FederationInboxRateLimiter
import network.lapis.cloud.server.rpc.ElectionService
import network.lapis.cloud.server.rpc.GovernanceService
import network.lapis.cloud.shared.domain.AttendanceStatus
import network.lapis.cloud.shared.domain.SessionAttendanceInput
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.io.File
import kotlin.time.Duration.Companion.minutes
import kotlin.uuid.Uuid

/**
 * V1.9.236 -- the six new methods of `IGovernanceService` really reach [MeetingSessionOperations] through `GovernanceService`, and the
 * old `removeAgendaItem` clears the (foreign-key-less) current-item marker.
 */
private fun readSource(path: String): String {
    val file = listOf(File(path), File("..", path)).firstOrNull { it.exists() } ?: error("no source file $path")
    return file.readText()
}

class MeetingSessionRpcWiringTest :
    FunSpec({
        val fx = SessionFixture()
        val connected = FakeConnectedMembers()
        val enabled =
            EventOnlineServices.create(
                config = EVENT_ONLINE_CONFIG,
                liveKitAdminClient = EventFakeLiveKit(),
                whiteboardState = ConferenceWhiteboardState(),
                notesState = ConferenceNotesState(),
                audioGrantState = ConferenceAudioGrantState(),
                memberJoinRateLimiter = FederationInboxRateLimiter(maxRequests = 1000, window = 1.minutes),
                adminRateLimiter = FederationInboxRateLimiter(maxRequests = 1000, window = 1.minutes),
            )

        beforeSpec {
            DatabaseConfig.connect()
            DevSeedData.seedIfEmpty(force = true)
        }
        afterSpec { fx.cleanup() }

        fun withOps(
            eventOnline: EventOnlineServices,
            block: suspend (suspend (actor: kotlin.uuid.Uuid, name: String, params: String) -> String) -> Unit,
        ) = testApplication {
            application {
                install(StatusPages) { exception<Throwable> { call, cause -> call.respondText("ERR:${cause::class.simpleName}") } }
                routing {
                    post("/op/{name}") {
                        val gs = GovernanceService(call = call, eventOnline = eventOnline, connectedMembers = connected)
                        val q = call.request.queryParameters
                        val out: String =
                            when (call.parameters["name"]) {
                                "state" ->
                                    gs.getConferenceMeetingState(q["r"]!!).state?.let { "${it.phase}|${it.callerRole}|${it.roster.size}" }
                                        ?: "null"
                                "open" -> gs.openConferenceMeeting(roomId = q["r"]!!, meetingId = q["m"]!!).phase.name
                                "attend" ->
                                    gs
                                        .setConferenceAttendance(
                                            roomId = q["r"]!!,
                                            meetingId = q["m"]!!,
                                            input = SessionAttendanceInput(memberId = q["p"]!!, status = AttendanceStatus.PRESENT),
                                        ).roster
                                        .count { it.status == AttendanceStatus.PRESENT }
                                        .toString()
                                "adopt" -> gs.adoptConnectedAttendance(roomId = q["r"]!!, meetingId = q["m"]!!).toString()
                                "establish" ->
                                    gs
                                        .establishConferenceQuorum(roomId = q["r"]!!, meetingId = q["m"]!!, acknowledgeNotMet = true)
                                        .establishedQuorum!!
                                        .present
                                        .toString()
                                "move" ->
                                    gs
                                        .moveConferenceAgenda(
                                            roomId = q["r"]!!,
                                            meetingId = q["m"]!!,
                                            fromAgendaItemId = q["from"],
                                            toAgendaItemId = q["to"]!!,
                                        ).currentAgendaItemId!!
                                "attachMotion" ->
                                    gs
                                        .attachConferenceMotion(
                                            roomId = q["r"]!!,
                                            meetingId = q["m"]!!,
                                            motionId = q["mo"]!!,
                                            expectedAgendaItemId = q["item"]!!,
                                        ).agendaMotions
                                        .single()
                                        .status
                                        .name
                                "openMotionVote" ->
                                    gs
                                        .openConferenceMotionVote(
                                            roomId = q["r"]!!,
                                            meetingId = q["m"]!!,
                                            motionId = q["mo"]!!,
                                            expectedAgendaItemId = q["item"]!!,
                                            secret = q["secret"] == "true",
                                            electionBoardMemberIds = q["board"]!!.split(","),
                                        ).runningMotionVote!!
                                        .electionId
                                "closeVote" ->
                                    ElectionService(
                                        call = call,
                                        streamGuard = NoOpSecretBallotStreamGuard,
                                    ).closeVoting(q["e"]!!).status.name
                                "approve" ->
                                    ElectionService(
                                        call = call,
                                        streamGuard = NoOpSecretBallotStreamGuard,
                                    ).approveTally(q["e"]!!).status.name
                                "tally" -> {
                                    ElectionService(call = call, streamGuard = NoOpSecretBallotStreamGuard).tally(q["e"]!!)
                                    "tallied"
                                }
                                "removeAgenda" -> {
                                    gs.removeAgendaItem(q["id"]!!)
                                    "ok"
                                }
                                else -> error("unknown op")
                            }
                        call.respondText(out)
                    }
                }
            }
            block { actor, name, params -> client.post("/op/$name?$params") { header("X-Member-Id", actor.toString()) }.bodyAsText() }
        }

        test("all six methods reach the operations; the new current item is returned; the old RPC clears the marker of a removed item") {
            val s = fx.scene()
            connected.connect(s.peers[0])
            val r = "r=${s.roomId}&m=${s.meetingId}"
            withOps(enabled) { call ->
                call(s.lead.memberId, "state", "r=${s.roomId}") shouldBe "NOT_OPENED|LEAD|7"
                call(s.participant.memberId, "state", "r=${s.roomId}") shouldBe "NOT_OPENED|PARTICIPANT|0"
                call(s.lead.memberId, "open", r) shouldBe "OPEN"
                call(s.lead.memberId, "attend", "$r&p=${s.peers[1].memberId}") shouldBe "1"
                call(s.lead.memberId, "adopt", r) shouldBe "1"
                call(s.lead.memberId, "establish", r) shouldBe "2"
                call(s.lead.memberId, "move", "$r&from=${s.agenda[0]}&to=${s.agenda[1]}") shouldBe s.agenda[1].toString()
                call(s.participant.memberId, "open", r) shouldBe "ERR:ForbiddenException"
                call(s.lead.memberId, "removeAgenda", "id=${s.agenda[1]}") shouldBe "ok"
                transaction {
                    MeetingTable.selectAll().where { MeetingTable.id eq s.meetingId }.single()[MeetingTable.currentAgendaItemId]
                } shouldBe
                    null
            }
        }

        test("attach, open a vote, close, approve twice, tally: the RPCs reach the operations and the resolution appears in the state") {
            val s = fx.scene()
            val motion = fx.newMotion(committee = s.committee, submitter = s.participant)
            val r = "r=${s.roomId}&m=${s.meetingId}"
            val board = s.boardIds().joinToString(",")
            withOps(enabled) { call ->
                call(s.lead.memberId, "open", r) shouldBe "OPEN"
                val item = s.agenda[0]
                call(s.lead.memberId, "attachMotion", "$r&mo=$motion&item=$item") shouldBe "SCHEDULED"
                call(s.participant.memberId, "attachMotion", "$r&mo=$motion&item=$item") shouldBe "ERR:ForbiddenException"
                val election = call(s.lead.memberId, "openMotionVote", "$r&mo=$motion&item=$item&secret=false&board=$board")
                s.castBallots(electionId = Uuid.parse(election), yes = 4, no = 1, abstain = 0)
                call(s.lead.memberId, "closeVote", "e=$election") shouldBe "CLOSED"
                call(s.lead.memberId, "approve", "e=$election") shouldBe "CLOSED"
                call(s.minutes.memberId, "approve", "e=$election") shouldBe "CLOSED"
                call(s.lead.memberId, "tally", "e=$election") shouldBe "tallied"
                call(s.lead.memberId, "state", "r=${s.roomId}") shouldBe "OPEN|LEAD|7"
            }
            s.ops
                .getState(current = s.lead, roomId = s.roomId)!!
                .agendaMotions
                .single()
                .resolution!!
                .status shouldBe
                network.lapis.cloud.shared.domain.ResolutionStatus.ADOPTED
        }

        test("the two motion RPCs are the last methods of IGovernanceService and Application hands the stream guard to GovernanceService") {
            val iface = readSource("lapis-shared/src/commonMain/kotlin/network/lapis/cloud/shared/rpc/IGovernanceService.kt")
            Regex("""suspend fun (\w+)\(""")
                .findAll(iface)
                .map { it.groupValues[1] }
                .toList()
                .takeLast(2) shouldBe
                listOf("attachConferenceMotion", "openConferenceMotionVote")
            val app = readSource("lapis-server/src/main/kotlin/network/lapis/cloud/server/Application.kt")
            val construction =
                app
                    .substringAfter(
                        "registerService(IGovernanceService::class)",
                    ).substringBefore("registerService(IElectionService")
            construction shouldContain "secretBallotStreamGuard = secretBallotStreamGuard"
        }

        test("with conferencing off: the read is null and a mutation is a not-found") {
            val s = fx.scene()
            withOps(EventOnlineServices.DISABLED) { call ->
                call(s.lead.memberId, "state", "r=${s.roomId}") shouldBe "null"
                call(s.lead.memberId, "open", "r=${s.roomId}&m=${s.meetingId}") shouldStartWith "ERR:NotFoundException"
            }
        }
    })
