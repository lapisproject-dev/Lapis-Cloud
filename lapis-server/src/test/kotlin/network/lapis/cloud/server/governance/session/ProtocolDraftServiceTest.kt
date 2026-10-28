package network.lapis.cloud.server.governance.session

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
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
import network.lapis.cloud.server.db.DatabaseConfig
import network.lapis.cloud.server.db.DevSeedData
import network.lapis.cloud.server.db.generated.MotionTable
import network.lapis.cloud.server.events.EVENT_ONLINE_CONFIG
import network.lapis.cloud.server.events.EventFakeLiveKit
import network.lapis.cloud.server.events.EventOnlineServices
import network.lapis.cloud.server.federation.FederationInboxRateLimiter
import network.lapis.cloud.server.rpc.GovernanceService
import network.lapis.cloud.server.rpc.ProtocolDraftService
import network.lapis.cloud.shared.domain.MeetingStatus
import network.lapis.cloud.shared.domain.ProtocolTaskInput
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import kotlin.time.Duration.Companion.minutes
import kotlin.uuid.Uuid

/**
 * V1.9.239 -- the four methods of `IProtocolDraftService` reach the operations through `ProtocolDraftService`, a caller without a session is
 * refused, and the old `removeAgendaItem` keeps an item that carries Protokoll text or a task (an empty block goes with the item).
 */
class ProtocolDraftServiceTest :
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

        fun withService(block: suspend (suspend (actor: Uuid?, name: String, params: String) -> String) -> Unit) =
            testApplication {
                application {
                    install(StatusPages) { exception<Throwable> { call, cause -> call.respondText("ERR:${cause::class.simpleName}") } }
                    routing {
                        post("/op/{name}") {
                            val service = ProtocolDraftService(call = call, operations = fx.protocolOps())
                            val gs = GovernanceService(call = call, eventOnline = enabled, connectedMembers = connected)
                            val q = call.request.queryParameters
                            val out: String =
                                when (call.parameters["name"]) {
                                    "get" -> service.getMeetingProtocolDraft(q["m"]!!).let { "${it.role}|${it.editable}|${it.items.size}" }
                                    "save" ->
                                        service
                                            .saveMeetingProtocolBlock(
                                                meetingId = q["m"]!!,
                                                agendaItemId = q["i"]!!,
                                                expectedVersion = q["v"]!!.toLong(),
                                                body = q["b"]!!,
                                            ).let { "${it.saved}|${it.version}" }
                                    "addTask" ->
                                        service
                                            .addMeetingProtocolTask(
                                                meetingId = q["m"]!!,
                                                input =
                                                    ProtocolTaskInput(
                                                        agendaItemId = q["i"]!!,
                                                        assigneeMemberId = q["p"]!!,
                                                        description = q["d"]!!,
                                                        dueDate = null,
                                                    ),
                                            ).taskId
                                    "removeTask" -> {
                                        service.removeMeetingProtocolTask(meetingId = q["m"]!!, taskId = q["t"]!!)
                                        "removed"
                                    }
                                    "removeAgenda" -> {
                                        gs.removeAgendaItem(q["id"]!!)
                                        "ok"
                                    }
                                    "setStatus" ->
                                        gs
                                            .updateMeetingStatus(meetingId = q["m"]!!, status = MeetingStatus.valueOf(q["st"]!!))
                                            .status
                                            .name
                                    else -> error("unknown op")
                                }
                            call.respondText(out)
                        }
                    }
                }
                block { actor, name, params ->
                    client.post("/op/$name?$params") { if (actor != null) header("X-Member-Id", actor.toString()) }.bodyAsText()
                }
            }

        test("the four methods reach the operations; no session is refused; the draft of a stranger is a not-found") {
            val s = fx.scene()
            kotlinx.coroutines.runBlocking { s.openedProtocol() }
            val m = "m=${s.meetingId}"
            val item = s.agenda[0]
            withService { call ->
                call(s.lead.memberId, "get", m) shouldBe "LEAD|true|3"
                call(s.participant.memberId, "get", m) shouldBe "PARTICIPANT|false|3"
                call(s.lead.memberId, "save", "$m&i=$item&v=0&b=Hallo") shouldBe "true|1"
                call(s.participant.memberId, "save", "$m&i=$item&v=1&b=x") shouldBe "ERR:ForbiddenException"
                val task = call(s.minutes.memberId, "addTask", "$m&i=$item&p=${s.peers[0].memberId}&d=MSX%20Aufgabe")
                Uuid.parse(task).toString() shouldBe task
                call(s.minutes.memberId, "removeTask", "$m&t=$task") shouldBe "removed"
                call(null, "get", m) shouldBe "ERR:UnauthenticatedException"
                val stranger = fx.newMember()
                call(stranger.memberId, "get", m) shouldBe "ERR:NotFoundException"
                call(stranger.memberId, "save", "$m&i=$item&v=0&b=x") shouldBe "ERR:NotFoundException"
            }
        }

        test("removeAgendaItem keeps an item with Protokoll text or a task and removes an item with an empty block") {
            val s = fx.scene()
            val p = kotlinx.coroutines.runBlocking { s.openedProtocol() }
            val meeting = s.meetingId.toString()
            p.saveBlock(current = s.lead, meetingId = meeting, agendaItemId = s.agenda[0].toString(), expectedVersion = 0, body = "Text")
            p.addTask(
                current = s.lead,
                meetingId = meeting,
                input =
                    ProtocolTaskInput(
                        agendaItemId = s.agenda[1].toString(),
                        assigneeMemberId = s.peers[0].memberId.toString(),
                        description = "MSX Aufgabe",
                        dueDate = null,
                    ),
            )
            p.saveBlock(current = s.lead, meetingId = meeting, agendaItemId = s.agenda[2].toString(), expectedVersion = 0, body = "kurz")
            p.saveBlock(current = s.lead, meetingId = meeting, agendaItemId = s.agenda[2].toString(), expectedVersion = 1, body = "")
            withService { call ->
                call(s.lead.memberId, "removeAgenda", "id=${s.agenda[0]}") shouldBe "ERR:ConflictException"
                call(s.lead.memberId, "removeAgenda", "id=${s.agenda[1]}") shouldBe "ERR:ConflictException"
                call(s.lead.memberId, "removeAgenda", "id=${s.agenda[2]}") shouldBe "ok"
            }
            p.get(current = s.lead, meetingId = meeting).items.map { it.title } shouldBe listOf("TOP 1", "TOP 2")
            p.get(current = s.lead, meetingId = meeting).items[0].body shouldBe "Text"
        }

        test("removeAgendaItem: refused after the Protokoll is finalised, and for an approval item that was opened") {
            val storage = newStorageRoot()
            try {
                val s = fx.scene()
                val f = fx.finalOps(storage)
                val (next, nextItems) =
                    fx.newMeeting(committee = s.committee, chair = s.lead, minuteTaker = s.minutes, agenda = listOf("Z"))
                reschedule(meetingId = next, at = kotlinx.datetime.LocalDateTime(2031, 8, 1, 19, 0))
                s.openAndWrite(final = f)
                val v = s.finaliseAs(final = f, who = s.lead).version!!
                withService { call ->
                    // LOCKED draft: even an empty, unwritten item stays (the snapshot names the items).
                    call(s.lead.memberId, "removeAgenda", "id=${s.agenda[2]}") shouldBe "ERR:ConflictException"
                }
                f.confirm(current = s.minutes, meetingId = s.meetingId.toString(), versionId = v.versionId)
                val approvalItem = approvalItemsOf(next).single().first
                val room = fx.newRoom(meetingId = next, creator = s.lead)
                kotlinx.coroutines.runBlocking { s.ops.open(current = s.lead, roomId = room, meetingId = next) }
                withService { call ->
                    // The Sitzung was opened: the approval item is a record now.
                    call(s.lead.memberId, "removeAgenda", "id=$approvalItem") shouldBe "ERR:ConflictException"
                    call(s.lead.memberId, "removeAgenda", "id=${nextItems[0]}") shouldBe "ok"
                }
                approvalItemsOf(next).size shouldBe 1
            } finally {
                storage.deleteRecursively()
            }
        }

        test("updateMeetingStatus(CANCELLED) releases an unused approval item through the service; one that carries a motion stays") {
            val storage = newStorageRoot()
            try {
                val s = fx.scene()
                val f = fx.finalOps(storage)
                val (first, _) = fx.newMeeting(committee = s.committee, chair = s.lead, minuteTaker = s.minutes, agenda = listOf("Z"))
                reschedule(meetingId = first, at = kotlinx.datetime.LocalDateTime(2031, 8, 1, 19, 0))
                val (second, _) = fx.newMeeting(committee = s.committee, chair = s.lead, minuteTaker = s.minutes, agenda = listOf("Y"))
                reschedule(meetingId = second, at = kotlinx.datetime.LocalDateTime(2031, 9, 1, 19, 0))
                s.openAndWrite(final = f)
                val v = s.finaliseAs(final = f, who = s.lead).version!!
                f.confirm(current = s.minutes, meetingId = s.meetingId.toString(), versionId = v.versionId)
                approvalItemsOf(first) shouldHaveSize 1
                withService { call ->
                    call(s.lead.memberId, "setStatus", "m=$first&st=CANCELLED") shouldBe "CANCELLED"
                }
                // Released from the cancelled Sitzung and offered to the next one -- through the service wiring, not a direct call.
                approvalItemsOf(first) shouldHaveSize 0
                approvalItemsOf(second) shouldHaveSize 1
                // A motion hangs on the item by foreign key: the item stays and the status change still succeeds.
                val motion = fx.newMotion(committee = s.committee, submitter = s.lead)
                val item = approvalItemsOf(second).single().first
                transaction { MotionTable.update({ MotionTable.id eq motion }) { it[agendaItemId] = item } }
                withService { call ->
                    call(s.lead.memberId, "setStatus", "m=$second&st=CANCELLED") shouldBe "CANCELLED"
                }
                approvalItemsOf(second) shouldHaveSize 1
            } finally {
                storage.deleteRecursively()
            }
        }

        test("a task is not part of anything the dashboard shows: nothing in the dashboard code reads the Protokoll tables") {
            val root =
                listOf(
                    java.io.File("lapis-server/src/main/kotlin"),
                    java.io.File("src/main/kotlin"),
                    java.io.File("../lapis-server/src/main/kotlin"),
                ).first { it.exists() }
            val dashboard =
                root
                    .walkTopDown()
                    .filter { it.isFile && it.extension == "kt" && it.path.contains("dashboard", ignoreCase = true) }
                    .joinToString("\n") { it.readText() }
            (dashboard.length > 0) shouldBe true
            dashboard shouldNotContain "ProtocolDraft"
        }
    })
