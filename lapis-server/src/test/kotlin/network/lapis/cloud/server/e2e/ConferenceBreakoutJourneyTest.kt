package network.lapis.cloud.server.e2e

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.response.respondText
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import network.lapis.cloud.server.conference.ConferenceConfig
import network.lapis.cloud.server.conference.LiveKitAdminClient
import network.lapis.cloud.server.conference.LiveKitParticipantInfo
import network.lapis.cloud.server.conference.LiveKitRoomInfo
import network.lapis.cloud.server.db.DatabaseConfig
import network.lapis.cloud.server.db.DevSeedData
import network.lapis.cloud.server.db.generated.AuditLogEntryTable
import network.lapis.cloud.server.db.generated.ConferenceBreakoutAssignmentTable
import network.lapis.cloud.server.db.generated.ConferenceBreakoutRoomTable
import network.lapis.cloud.server.db.generated.ConferenceParticipationTable
import network.lapis.cloud.server.db.generated.ConferenceRoomTable
import network.lapis.cloud.server.module
import network.lapis.cloud.server.rpc.ConferenceBreakoutService
import network.lapis.cloud.server.rpc.ConferenceService
import network.lapis.cloud.server.security.LoginRateLimiter
import network.lapis.cloud.shared.domain.ConferenceBreakoutPlanInput
import network.lapis.cloud.shared.domain.ConferenceRoomInput
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import kotlin.uuid.Uuid

/**
 * Hermetic, in-memory stand-in for [LiveKitAdminClient] -- same posture
 * [network.lapis.cloud.server.rpc.ConferenceServiceTest]'s own private `FakeLiveKitAdminClient` and
 * [FederationGuestJourneyTest]'s own `E2eFakeLiveKitAdminClient` establish (no real LiveKit
 * container in this sandbox), duplicated here rather than reused because both are `private` to
 * their own files.
 */
private class BreakoutE2eFakeLiveKitAdminClient : LiveKitAdminClient {
    private val rooms = mutableMapOf<String, LiveKitRoomInfo>()
    private val participantsByRoom = mutableMapOf<String, MutableList<LiveKitParticipantInfo>>()

    override suspend fun createRoom(
        name: String,
        maxParticipants: Int,
        emptyTimeoutSeconds: Int,
    ): LiveKitRoomInfo {
        val info = LiveKitRoomInfo(sid = "RM_$name", name = name, maxParticipants = maxParticipants, numParticipants = 0)
        rooms[name] = info
        participantsByRoom.getOrPut(name) { mutableListOf() }
        return info
    }

    override suspend fun deleteRoom(name: String) {
        rooms.remove(name)
        participantsByRoom.remove(name)
    }

    override suspend fun listRooms(): List<LiveKitRoomInfo> = rooms.values.toList()

    override suspend fun listParticipants(room: String): List<LiveKitParticipantInfo> = participantsByRoom[room].orEmpty()

    override suspend fun removeParticipant(
        room: String,
        identity: String,
    ) {
        participantsByRoom[room]?.removeIf { it.identity == identity }
    }

    /** Test-only seam -- a real WebRTC connect never happens in this JVM sandbox, so a joined member is never automatically "live" in [listParticipants] the way a real LiveKit server would make them; each throwaway join route below calls this explicitly right after [ConferenceService.joinRoom] succeeds. */
    fun seedLiveParticipant(
        room: String,
        identity: String,
    ) {
        participantsByRoom.getOrPut(room) { mutableListOf() }.add(LiveKitParticipantInfo(identity = identity, name = identity))
    }
}

/** [ConferenceConfig] with `enabled=true` -- same injectable-`env`-seam idiom [FederationGuestJourneyTest]'s own `E2E_ENABLED_CONFERENCE_CONFIG` uses. */
private val E2E6_ENABLED_CONFERENCE_CONFIG =
    ConferenceConfig.load { key ->
        when (key) {
            "LAPIS_LIVEKIT_URL" -> "ws://localhost:7880"
            "LAPIS_LIVEKIT_API_KEY" -> "test-livekit-key"
            "LAPIS_LIVEKIT_API_SECRET" -> "test-livekit-secret-at-least-32-bytes-long!!"
            else -> null
        }
    }

/**
 * V1.0 Videokonferenzen, Wave 6 "Breakout-Räume" -- end-to-end journey through the REAL, fully-wired
 * [module] (mounted exactly like every other scenario in this package, see [E2eSupport] KDoc),
 * driven via `X-Member-Id` trusted-header auth (simpler than real login -- this story is about the
 * breakout-room mechanics, not session/login plumbing, matching the header-auth posture
 * [FederationGuestJourneyTest] itself uses for its own scene-partner actors). Because the REAL
 * `IConferenceService`/`IConferenceBreakoutService` registered by `module()` read `ConferenceConfig`
 * from real environment variables (unset in this sandbox, so `enabled=false`), this test -- like
 * [FederationGuestJourneyTest] -- layers small throwaway routes on top that construct the service
 * classes directly with [E2E6_ENABLED_CONFERENCE_CONFIG] and a fake [LiveKitAdminClient], the same
 * "real middleware stack, elided Kilua JSON-RPC envelope" idiom that file's own class KDoc
 * documents.
 *
 * Covers the plan's own required story: create room -> join as moderator + 2 participants ->
 * createBreakoutRooms(count=2) -> each participant can obtain a token for their OWN assigned
 * breakout room but NOT the other one -> recallAll -> both tokens now rejected -> a SEPARATE
 * abandoned batch (created, never explicitly recalled) is still fully cleaned up when its parent
 * room ends -- no orphaned LiveKit room left behind either way.
 */
class ConferenceBreakoutJourneyTest :
    FunSpec({
        val createdMemberIds = mutableListOf<Uuid>()
        val createdConferenceRoomIds = mutableListOf<Uuid>()

        beforeSpec {
            DatabaseConfig.connect()
            DevSeedData.seedIfEmpty(force = true)
        }

        afterSpec {
            transaction {
                if (createdMemberIds.isNotEmpty()) {
                    AuditLogEntryTable.update({ AuditLogEntryTable.actorMemberId inList createdMemberIds }) {
                        it[actorMemberId] = null
                    }
                }
                val breakoutRoomIds =
                    ConferenceBreakoutRoomTable
                        .selectAll()
                        .where { ConferenceBreakoutRoomTable.parentRoomId inList createdConferenceRoomIds }
                        .map { it[ConferenceBreakoutRoomTable.id] }
                ConferenceBreakoutAssignmentTable.deleteWhere {
                    (ConferenceBreakoutAssignmentTable.memberId inList createdMemberIds) or
                        (ConferenceBreakoutAssignmentTable.breakoutRoomId inList breakoutRoomIds)
                }
                ConferenceBreakoutRoomTable.deleteWhere { ConferenceBreakoutRoomTable.parentRoomId inList createdConferenceRoomIds }
                ConferenceParticipationTable.deleteWhere {
                    (ConferenceParticipationTable.memberId inList createdMemberIds) or
                        (ConferenceParticipationTable.roomId inList createdConferenceRoomIds)
                }
                ConferenceRoomTable.deleteWhere { ConferenceRoomTable.id inList createdConferenceRoomIds }
                hardDeleteGovernanceAndMembershipFixtures(emptyList(), createdMemberIds)
            }
        }

        test(
            "moderator creates 2 breakout rooms, each participant obtains a token for their own room " +
                "but not the other's, and recallAll invalidates both",
        ) {
            testApplication {
                val fakeLiveKit = BreakoutE2eFakeLiveKitAdminClient()
                application {
                    module()
                    routing {
                        fun conferenceService(call: ApplicationCall) =
                            ConferenceService(call, fakeLiveKit, LoginRateLimiter(), E2E6_ENABLED_CONFERENCE_CONFIG)

                        fun breakoutService(call: ApplicationCall) =
                            ConferenceBreakoutService(call, fakeLiveKit, config = E2E6_ENABLED_CONFERENCE_CONFIG)

                        post("/e2e6-conf/create-room") {
                            val room = conferenceService(call).createRoom(ConferenceRoomInput(title = "E2E Scenario 6 Konferenzraum"))
                            call.respondText("${room.id}|${room.livekitRoomName}")
                        }
                        post("/e2e6-conf/join-room/{roomId}") {
                            val token = conferenceService(call).joinRoom(call.parameters["roomId"]!!)
                            call.respondText(token.identity)
                        }
                        post("/e2e6-conf/create-breakout-rooms/{roomId}") {
                            val roomCount = call.request.queryParameters["roomCount"]!!.toInt()
                            val dtos =
                                breakoutService(call).createBreakoutRooms(
                                    call.parameters["roomId"]!!,
                                    ConferenceBreakoutPlanInput(roomCount = roomCount),
                                )
                            call.respondText(
                                dtos.joinToString(";") { "${it.id}|${it.assignedMemberIds.joinToString(",")}" },
                            )
                        }
                        post("/e2e6-conf/request-breakout-token/{breakoutRoomId}") {
                            val token = breakoutService(call).requestBreakoutJoinToken(call.parameters["breakoutRoomId"]!!)
                            call.respondText(token.identity)
                        }
                        post("/e2e6-conf/recall-all/{roomId}") {
                            val count = breakoutService(call).recallAll(call.parameters["roomId"]!!)
                            call.respondText(count.toString())
                        }
                    }
                }

                // ── Step 1: an AKTIV moderator creates the room and joins it. ────────────────────────
                val moderatorEmail = "e2e6-conf-moderator-${Uuid.random()}@example.org"
                val moderatorId = createRealMember("E2E Scenario 6 Moderator", moderatorEmail)
                createdMemberIds += moderatorId
                val createResponse =
                    client.post("/e2e6-conf/create-room") { header("X-Member-Id", moderatorId.toString()) }.bodyAsText().split("|")
                val roomId = createResponse[0]
                val livekitRoomName = createResponse[1]
                createdConferenceRoomIds += Uuid.parse(roomId)
                client.post("/e2e6-conf/join-room/$roomId") { header("X-Member-Id", moderatorId.toString()) }
                fakeLiveKit.seedLiveParticipant(livekitRoomName, moderatorId.toString())

                // ── Step 2: two AKTIV participants join the same room. ───────────────────────────────
                val participantAEmail = "e2e6-conf-participant-a-${Uuid.random()}@example.org"
                val participantAId = createRealMember("E2E Scenario 6 Teilnehmer A", participantAEmail)
                createdMemberIds += participantAId
                client.post("/e2e6-conf/join-room/$roomId") { header("X-Member-Id", participantAId.toString()) }
                fakeLiveKit.seedLiveParticipant(livekitRoomName, participantAId.toString())

                val participantBEmail = "e2e6-conf-participant-b-${Uuid.random()}@example.org"
                val participantBId = createRealMember("E2E Scenario 6 Teilnehmer B", participantBEmail)
                createdMemberIds += participantBId
                client.post("/e2e6-conf/join-room/$roomId") { header("X-Member-Id", participantBId.toString()) }
                fakeLiveKit.seedLiveParticipant(livekitRoomName, participantBId.toString())

                // ── Step 3: the moderator creates 2 breakout rooms, auto-distributed. ────────────────
                val createBreakoutResponse =
                    client
                        .post("/e2e6-conf/create-breakout-rooms/$roomId?roomCount=2") {
                            header("X-Member-Id", moderatorId.toString())
                        }.bodyAsText()
                createBreakoutResponse.split(";").size shouldBe 2
                val breakoutRooms = createBreakoutResponse.split(";").map { it.split("|") }
                val breakoutRoomOfA = breakoutRooms.single { participantAId.toString() in it[1].split(",") }
                val breakoutRoomOfB = breakoutRooms.single { participantBId.toString() in it[1].split(",") }
                // The two participants must be distributed into DIFFERENT breakout rooms (roomCount
                // 2, exactly 2 non-moderator participants) -- sanity check on the fixture itself.
                breakoutRoomOfA[0] shouldBe breakoutRoomOfA[0]
                (breakoutRoomOfA[0] == breakoutRoomOfB[0]) shouldBe false

                // ── Step 4: each participant obtains a token for THEIR OWN assigned breakout room. ───
                client
                    .post("/e2e6-conf/request-breakout-token/${breakoutRoomOfA[0]}") {
                        header("X-Member-Id", participantAId.toString())
                    }.status shouldBe HttpStatusCode.OK
                client
                    .post("/e2e6-conf/request-breakout-token/${breakoutRoomOfB[0]}") {
                        header("X-Member-Id", participantBId.toString())
                    }.status shouldBe HttpStatusCode.OK

                // ── Step 5: neither participant can obtain a token for the OTHER's breakout room --  ──
                // ── the core "guessing/enumerating an id" tamper case this wave's own task names. ────
                client
                    .post("/e2e6-conf/request-breakout-token/${breakoutRoomOfB[0]}") {
                        header("X-Member-Id", participantAId.toString())
                    }.status shouldBe HttpStatusCode.Forbidden
                client
                    .post("/e2e6-conf/request-breakout-token/${breakoutRoomOfA[0]}") {
                        header("X-Member-Id", participantBId.toString())
                    }.status shouldBe HttpStatusCode.Forbidden

                // ── Step 6: the moderator recalls everyone -- both tokens now rejected (replay). ─────
                client.post("/e2e6-conf/recall-all/$roomId") { header("X-Member-Id", moderatorId.toString()) }.bodyAsText() shouldBe
                    "2"
                client
                    .post("/e2e6-conf/request-breakout-token/${breakoutRoomOfA[0]}") {
                        header("X-Member-Id", participantAId.toString())
                    }.status shouldBe HttpStatusCode.Forbidden
                client
                    .post("/e2e6-conf/request-breakout-token/${breakoutRoomOfB[0]}") {
                        header("X-Member-Id", participantBId.toString())
                    }.status shouldBe HttpStatusCode.Forbidden

                // ── No orphaned LiveKit room after the recall. ────────────────────────────────────────
                runBlocking { fakeLiveKit.listRooms().map { it.name } } shouldBe listOf(livekitRoomName)
            }
        }

        test(
            "endRoom cleans up an abandoned breakout batch (created, never explicitly recalled) -- " +
                "no orphaned LiveKit room left behind",
        ) {
            testApplication {
                val fakeLiveKit = BreakoutE2eFakeLiveKitAdminClient()
                application {
                    module()
                    routing {
                        fun conferenceService(call: ApplicationCall) =
                            ConferenceService(call, fakeLiveKit, LoginRateLimiter(), E2E6_ENABLED_CONFERENCE_CONFIG)

                        fun breakoutService(call: ApplicationCall) =
                            ConferenceBreakoutService(call, fakeLiveKit, config = E2E6_ENABLED_CONFERENCE_CONFIG)

                        post("/e2e6b-conf/create-room") {
                            val room = conferenceService(call).createRoom(ConferenceRoomInput(title = "E2E Scenario 6b Konferenzraum"))
                            call.respondText("${room.id}|${room.livekitRoomName}")
                        }
                        post("/e2e6b-conf/join-room/{roomId}") {
                            val token = conferenceService(call).joinRoom(call.parameters["roomId"]!!)
                            call.respondText(token.identity)
                        }
                        post("/e2e6b-conf/create-breakout-rooms/{roomId}") {
                            val roomCount = call.request.queryParameters["roomCount"]!!.toInt()
                            val dtos =
                                breakoutService(call).createBreakoutRooms(
                                    call.parameters["roomId"]!!,
                                    ConferenceBreakoutPlanInput(roomCount = roomCount),
                                )
                            call.respondText(dtos.size.toString())
                        }
                        post("/e2e6b-conf/end-room/{roomId}") {
                            val room = conferenceService(call).endRoom(call.parameters["roomId"]!!)
                            call.respondText(room.active.toString())
                        }
                    }
                }

                val moderatorEmail = "e2e6b-conf-moderator-${Uuid.random()}@example.org"
                val moderatorId = createRealMember("E2E Scenario 6b Moderator", moderatorEmail)
                createdMemberIds += moderatorId
                val createResponse =
                    client.post("/e2e6b-conf/create-room") { header("X-Member-Id", moderatorId.toString()) }.bodyAsText().split("|")
                val roomId = createResponse[0]
                val livekitRoomName = createResponse[1]
                createdConferenceRoomIds += Uuid.parse(roomId)
                client.post("/e2e6b-conf/join-room/$roomId") { header("X-Member-Id", moderatorId.toString()) }
                fakeLiveKit.seedLiveParticipant(livekitRoomName, moderatorId.toString())

                client
                    .post("/e2e6b-conf/create-breakout-rooms/$roomId?roomCount=3") {
                        header("X-Member-Id", moderatorId.toString())
                    }.bodyAsText() shouldBe "3"

                // Abandoned -- no explicit recallAll call. Ending the parent room must still clean up
                // every breakout LiveKit room AND stamp their DB rows closed (see
                // ConferenceBreakoutCoordinator KDoc).
                client
                    .post("/e2e6b-conf/end-room/$roomId") {
                        header("X-Member-Id", moderatorId.toString())
                    }.bodyAsText() shouldBe "false"

                runBlocking { fakeLiveKit.listRooms() } shouldBe emptyList()

                val stillOpenBreakoutRooms =
                    transaction {
                        ConferenceBreakoutRoomTable
                            .selectAll()
                            .where {
                                (ConferenceBreakoutRoomTable.parentRoomId eq Uuid.parse(roomId)) and
                                    ConferenceBreakoutRoomTable.closedAt.isNull()
                            }.count()
                    }
                stillOpenBreakoutRooms shouldBe 0L
            }
        }
    })
