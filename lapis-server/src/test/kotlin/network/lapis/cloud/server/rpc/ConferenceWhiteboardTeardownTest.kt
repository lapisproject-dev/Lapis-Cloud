package network.lapis.cloud.server.rpc

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.install
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.plugins.statuspages.StatusPagesConfig
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import network.lapis.cloud.server.conference.ConferenceConfig
import network.lapis.cloud.server.conference.ConferenceWhiteboardState
import network.lapis.cloud.server.conference.LiveKitAdminClient
import network.lapis.cloud.server.conference.LiveKitParticipantInfo
import network.lapis.cloud.server.conference.LiveKitRoomInfo
import network.lapis.cloud.server.db.DatabaseConfig
import network.lapis.cloud.server.db.DbClock
import network.lapis.cloud.server.db.DevSeedData
import network.lapis.cloud.server.db.generated.AccountTable
import network.lapis.cloud.server.db.generated.ConferenceParticipationTable
import network.lapis.cloud.server.db.generated.ConferenceRoomTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.server.security.LoginRateLimiter
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.ConferenceRole
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.domain.WhiteboardPointDto
import network.lapis.cloud.shared.domain.WhiteboardStrokeDto
import network.lapis.cloud.shared.domain.WhiteboardTool
import network.lapis.cloud.shared.rpc.BadRequestException
import network.lapis.cloud.shared.rpc.ConflictException
import network.lapis.cloud.shared.rpc.ForbiddenException
import network.lapis.cloud.shared.rpc.NotFoundException
import network.lapis.cloud.shared.rpc.UnauthenticatedException
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid

private val TEARDOWN_ENABLED_CONFIG =
    ConferenceConfig.load { key ->
        when (key) {
            "LAPIS_LIVEKIT_URL" -> "ws://localhost:7880"
            "LAPIS_LIVEKIT_API_KEY" -> "test-livekit-key"
            "LAPIS_LIVEKIT_API_SECRET" -> "test-livekit-secret-at-least-32-bytes-long!!"
            "LAPIS_CONFERENCE_MAX_PARTICIPANTS" -> "25"
            else -> null
        }
    }

/** Minimal fake -- only the methods [ConferenceService.endRoom]/[ConferenceService.listActiveRooms]/[ConferenceService.getRoom] actually call. */
private class TeardownFakeLiveKitAdminClient : LiveKitAdminClient {
    private val rooms = mutableMapOf<String, LiveKitRoomInfo>()

    override suspend fun createRoom(
        name: String,
        maxParticipants: Int,
        emptyTimeoutSeconds: Int,
    ): LiveKitRoomInfo {
        val info = LiveKitRoomInfo(sid = "RM_$name", name = name, maxParticipants = maxParticipants, numParticipants = 0)
        rooms[name] = info
        return info
    }

    override suspend fun deleteRoom(name: String) {
        rooms.remove(name)
    }

    override suspend fun listRooms(): List<LiveKitRoomInfo> = rooms.values.toList()

    override suspend fun listParticipants(room: String): List<LiveKitParticipantInfo> = emptyList()

    override suspend fun removeParticipant(
        room: String,
        identity: String,
    ) {
    }

    fun forgetRoom(name: String) {
        rooms.remove(name)
    }
}

private fun testStroke(strokeId: String) =
    WhiteboardStrokeDto(
        strokeId = strokeId,
        authorMemberId = Uuid.random().toString(),
        authorDisplayName = "Test",
        tool = WhiteboardTool.PEN,
        color = "#1a1a1a",
        strokeWidth = 4.0,
        points = listOf(WhiteboardPointDto(1.0, 1.0), WhiteboardPointDto(2.0, 2.0)),
        committedAtEpochMs = 0L,
    )

/**
 * V1.0 Videokonferenzen (Kleinsitzung), Wave 7 "Whiteboard" -- proves whiteboard state is ACTUALLY
 * removed on room end, on BOTH teardown paths [ConferenceService] hooks (per this wave's plan §8):
 * the explicit `endRoom` cascade, AND the LAZY `reconcileRoomIfDue` path (a room that is only ever
 * closed lazily, never via an explicit `endRoom` call) -- the concrete proof the deliberate
 * deviation from the breakout/recording precedent (documented in `ConferenceService.reconcileRoomIfDue`
 * KDoc) is actually safe, not merely asserted in a comment.
 */
class ConferenceWhiteboardTeardownTest :
    FunSpec({
        val createdMemberIds = mutableListOf<Uuid>()
        val createdRoomIds = mutableListOf<Uuid>()

        beforeSpec {
            DatabaseConfig.connect()
            DevSeedData.seedIfEmpty(force = true)
        }

        afterSpec { cleanUpTeardownTestData(createdMemberIds, createdRoomIds) }

        fun createTestMember(email: String): Uuid {
            val id = Uuid.random()
            transaction {
                MemberTable.insert {
                    it[MemberTable.id] = id
                    it[displayName] = email.substringBefore("@")
                    it[MemberTable.email] = email
                    it[MemberTable.status] = MemberStatus.AKTIV
                    it[joinedAt] = LocalDate(2026, 1, 1)
                    it[membershipTierId] = null
                }
                AccountTable.insert {
                    it[AccountTable.id] = Uuid.random()
                    it[memberId] = id
                    it[AccountTable.role] = AccountRole.MEMBER
                }
            }
            createdMemberIds += id
            return id
        }

        fun createTestRoom(
            liveKit: TeardownFakeLiveKitAdminClient,
            creatorId: Uuid,
        ): Pair<Uuid, String> {
            val roomId = Uuid.random()
            val livekitRoomName = "lc-whiteboard-teardown-$roomId"
            val now = DbClock.nowLocalDateTime()
            transaction {
                ConferenceRoomTable.insert {
                    it[id] = roomId
                    it[title] = "Whiteboard-Teardown-Testsitzung"
                    it[description] = ""
                    it[ConferenceRoomTable.livekitRoomName] = livekitRoomName
                    it[createdByMemberId] = creatorId
                    it[createdAt] = now
                    it[endedAt] = null
                    it[maxParticipants] = 25
                    it[allowFederationGuests] = false
                }
                ConferenceParticipationTable.insert {
                    it[id] = Uuid.random()
                    it[ConferenceParticipationTable.roomId] = roomId
                    it[ConferenceParticipationTable.memberId] = creatorId
                    it[role] = ConferenceRole.MODERATOR
                    it[joinedAt] = now
                    it[leftAt] = null
                }
            }
            createdRoomIds += roomId
            return roomId to livekitRoomName
        }

        test("endRoom cascade clears the room's whiteboard state") {
            val liveKit = TeardownFakeLiveKitAdminClient()
            val sharedWhiteboardState = ConferenceWhiteboardState()
            val creator = createTestMember("wb-teardown-endroom@example.org")
            val (roomId, livekitRoomName) = createTestRoom(liveKit, creator)
            liveKit.createRoom(livekitRoomName, 25, 300)
            sharedWhiteboardState.tryCommit(roomId, testStroke("s1"))
            sharedWhiteboardState.snapshot(roomId).size shouldBe 1

            testApplication {
                application {
                    install(StatusPages) { installTeardownExceptionHandlers() }
                    routing { registerTeardownTestRoutes(liveKit, sharedWhiteboardState) }
                }
                client
                    .post("/test/end-room?roomId=$roomId") { header("X-Member-Id", creator.toString()) }
                    .status shouldBe HttpStatusCode.OK
            }

            sharedWhiteboardState.snapshot(roomId) shouldBe emptyList()
        }

        test(
            "lazy reconcileRoomIfDue (via getRoom) clears whiteboard state for a room LiveKit no longer knows about, " +
                "past the empty-timeout grace",
        ) {
            val liveKit = TeardownFakeLiveKitAdminClient()
            val sharedWhiteboardState = ConferenceWhiteboardState()
            val creator = createTestMember("wb-teardown-lazy@example.org")
            val (roomId, livekitRoomName) = createTestRoom(liveKit, creator)
            liveKit.createRoom(livekitRoomName, 25, 300)
            sharedWhiteboardState.tryCommit(roomId, testStroke("s1"))
            sharedWhiteboardState.snapshot(roomId).size shouldBe 1

            // Backdate createdAt well past ROOM_EMPTY_TIMEOUT_SECONDS (300s) and remove the room from
            // the fake LiveKit's own registry -- simulates a room that expired server-side without
            // this server ever hearing about it, same fixture shape as ConferenceServiceTest's own
            // "listActiveRooms: ... lazily closes a room LiveKit no longer knows about" test.
            transaction {
                val row = ConferenceRoomTable.selectAll().where { ConferenceRoomTable.id eq roomId }.single()
                val backdated =
                    row[ConferenceRoomTable.createdAt]
                        .toInstant(TimeZone.currentSystemDefault())
                        .minus(400.seconds)
                        .toLocalDateTime(TimeZone.currentSystemDefault())
                ConferenceRoomTable.update({ ConferenceRoomTable.id eq roomId }) { it[createdAt] = backdated }
            }
            liveKit.forgetRoom(livekitRoomName)

            testApplication {
                application {
                    install(StatusPages) { installTeardownExceptionHandlers() }
                    routing { registerTeardownTestRoutes(liveKit, sharedWhiteboardState) }
                }
                // getRoom (NOT endRoom) drives the LAZY reconcileRoomIfDue path -- see class KDoc.
                client
                    .get("/test/get-room?roomId=$roomId") { header("X-Member-Id", creator.toString()) }
                    .status shouldBe HttpStatusCode.OK
            }

            sharedWhiteboardState.snapshot(roomId) shouldBe emptyList()
            val endedAt =
                transaction {
                    ConferenceRoomTable.selectAll().where { ConferenceRoomTable.id eq roomId }.single()[ConferenceRoomTable.endedAt]
                }
            (endedAt != null) shouldBe true
        }
    })

private fun cleanUpTeardownTestData(
    memberIds: List<Uuid>,
    roomIds: List<Uuid>,
) {
    if (memberIds.isEmpty() && roomIds.isEmpty()) return
    transaction {
        ConferenceParticipationTable.deleteWhere {
            (ConferenceParticipationTable.memberId inList memberIds) or (ConferenceParticipationTable.roomId inList roomIds)
        }
        ConferenceRoomTable.deleteWhere { ConferenceRoomTable.id inList roomIds }
        AccountTable.deleteWhere { AccountTable.memberId inList memberIds }
        MemberTable.deleteWhere { MemberTable.id inList memberIds }
    }
}

private fun StatusPagesConfig.installTeardownExceptionHandlers() {
    exception<UnauthenticatedException> { call, cause -> call.respondText(cause.message, status = HttpStatusCode.Unauthorized) }
    exception<ForbiddenException> { call, cause -> call.respondText(cause.message, status = HttpStatusCode.Forbidden) }
    exception<NotFoundException> { call, cause -> call.respondText(cause.message, status = HttpStatusCode.NotFound) }
    exception<ConflictException> { call, cause -> call.respondText(cause.message, status = HttpStatusCode.Conflict) }
    exception<BadRequestException> { call, cause -> call.respondText(cause.message, status = HttpStatusCode.BadRequest) }
}

private fun Route.registerTeardownTestRoutes(
    liveKitAdminClient: LiveKitAdminClient,
    whiteboardState: ConferenceWhiteboardState,
) {
    fun service(call: ApplicationCall) =
        ConferenceService(
            call,
            liveKitAdminClient,
            LoginRateLimiter(),
            TEARDOWN_ENABLED_CONFIG,
            whiteboardState = whiteboardState,
        )
    post("/test/end-room") {
        val service = service(call)
        val q = call.request.queryParameters
        service.endRoom(q["roomId"]!!)
        call.respondText("ok")
    }
    get("/test/get-room") {
        val service = service(call)
        val q = call.request.queryParameters
        val dto = service.getRoom(q["roomId"]!!)
        call.respondText(dto.id)
    }
}
