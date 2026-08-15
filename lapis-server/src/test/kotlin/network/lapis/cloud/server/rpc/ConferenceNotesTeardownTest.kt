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
import network.lapis.cloud.server.conference.ConferenceNotesState
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
import network.lapis.cloud.server.federation.FederationInboxRateLimiter
import network.lapis.cloud.server.security.LoginRateLimiter
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.ConferenceRole
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.domain.NoteBlockDto
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

private val NOTES_TEARDOWN_ENABLED_CONFIG =
    ConferenceConfig.load { key ->
        when (key) {
            "LAPIS_LIVEKIT_URL" -> "ws://localhost:7880"
            "LAPIS_LIVEKIT_API_KEY" -> "test-livekit-key"
            "LAPIS_LIVEKIT_API_SECRET" -> "test-livekit-secret-at-least-32-bytes-long!!"
            "LAPIS_CONFERENCE_MAX_PARTICIPANTS" -> "25"
            else -> null
        }
    }

/** Minimal fake -- only the methods [ConferenceService.endRoom]/[ConferenceService.getRoom] actually call. Mirrors [ConferenceWhiteboardTeardownTest]'s own fake. */
private class NotesTeardownFakeLiveKitAdminClient : LiveKitAdminClient {
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

private fun testBlock(id: String) =
    NoteBlockDto(
        id = id,
        content = "content",
        position = 1,
        version = 1,
        lastEditedByMemberId = Uuid.random().toString(),
        lastEditedByDisplayName = "Test",
        lastEditedAtEpochMs = 0L,
    )

/**
 * V1.0 Videokonferenzen (Kleinsitzung), Wave 8 "Geteilte Notizen" -- proves shared-notes state is
 * ACTUALLY removed on room end, on BOTH teardown paths [ConferenceService] hooks (mirrors
 * [ConferenceWhiteboardTeardownTest]'s own house style and reasoning verbatim, generalized from
 * whiteboard strokes to note blocks): the explicit `endRoom` cascade, AND the LAZY
 * `reconcileRoomIfDue` path.
 */
class ConferenceNotesTeardownTest :
    FunSpec({
        val createdMemberIds = mutableListOf<Uuid>()
        val createdRoomIds = mutableListOf<Uuid>()

        beforeSpec {
            DatabaseConfig.connect()
            DevSeedData.seedIfEmpty(force = true)
        }

        afterSpec { cleanUpNotesTeardownTestData(memberIds = createdMemberIds, roomIds = createdRoomIds) }

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

        fun createTestRoom(creatorId: Uuid): Pair<Uuid, String> {
            val roomId = Uuid.random()
            val livekitRoomName = "lc-notes-teardown-$roomId"
            val now = DbClock.nowLocalDateTime()
            transaction {
                ConferenceRoomTable.insert {
                    it[id] = roomId
                    it[title] = "Notizen-Teardown-Testsitzung"
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

        test("endRoom cascade clears the room's shared-notes state") {
            val liveKit = NotesTeardownFakeLiveKitAdminClient()
            val sharedNotesState = ConferenceNotesState()
            val creator = createTestMember("notes-teardown-endroom@example.org")
            val (roomId, livekitRoomName) = createTestRoom(creator)
            liveKit.createRoom(name = livekitRoomName, maxParticipants = 25, emptyTimeoutSeconds = 300)
            sharedNotesState.tryCreate(roomId = roomId, block = testBlock("b1"))
            sharedNotesState.snapshot(roomId).size shouldBe 1

            testApplication {
                application {
                    install(StatusPages) { installNotesTeardownExceptionHandlers() }
                    routing { registerNotesTeardownTestRoutes(liveKitAdminClient = liveKit, notesState = sharedNotesState) }
                }
                client
                    .post("/test/end-room?roomId=$roomId") { header("X-Member-Id", creator.toString()) }
                    .status shouldBe HttpStatusCode.OK
            }

            sharedNotesState.snapshot(roomId) shouldBe emptyList()
        }

        test(
            "lazy reconcileRoomIfDue (via getRoom) clears shared-notes state for a room LiveKit no longer knows about, " +
                "past the empty-timeout grace",
        ) {
            val liveKit = NotesTeardownFakeLiveKitAdminClient()
            val sharedNotesState = ConferenceNotesState()
            val creator = createTestMember("notes-teardown-lazy@example.org")
            val (roomId, livekitRoomName) = createTestRoom(creator)
            liveKit.createRoom(name = livekitRoomName, maxParticipants = 25, emptyTimeoutSeconds = 300)
            sharedNotesState.tryCreate(roomId = roomId, block = testBlock("b1"))
            sharedNotesState.snapshot(roomId).size shouldBe 1

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
                    install(StatusPages) { installNotesTeardownExceptionHandlers() }
                    routing { registerNotesTeardownTestRoutes(liveKitAdminClient = liveKit, notesState = sharedNotesState) }
                }
                client
                    .get("/test/get-room?roomId=$roomId") { header("X-Member-Id", creator.toString()) }
                    .status shouldBe HttpStatusCode.OK
            }

            sharedNotesState.snapshot(roomId) shouldBe emptyList()
            val endedAt =
                transaction {
                    ConferenceRoomTable.selectAll().where { ConferenceRoomTable.id eq roomId }.single()[ConferenceRoomTable.endedAt]
                }
            (endedAt != null) shouldBe true
        }
    })

private fun cleanUpNotesTeardownTestData(
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

private fun StatusPagesConfig.installNotesTeardownExceptionHandlers() {
    exception<UnauthenticatedException> { call, cause -> call.respondText(cause.message, status = HttpStatusCode.Unauthorized) }
    exception<ForbiddenException> { call, cause -> call.respondText(cause.message, status = HttpStatusCode.Forbidden) }
    exception<NotFoundException> { call, cause -> call.respondText(cause.message, status = HttpStatusCode.NotFound) }
    exception<ConflictException> { call, cause -> call.respondText(cause.message, status = HttpStatusCode.Conflict) }
    exception<BadRequestException> { call, cause -> call.respondText(cause.message, status = HttpStatusCode.BadRequest) }
}

private fun Route.registerNotesTeardownTestRoutes(
    liveKitAdminClient: LiveKitAdminClient,
    notesState: ConferenceNotesState,
) {
    fun service(call: ApplicationCall) =
        ConferenceService(
            call = call,
            liveKitAdminClient = liveKitAdminClient,
            createRoomRateLimiter = LoginRateLimiter(),
            config = NOTES_TEARDOWN_ENABLED_CONFIG,
            notesState = notesState,
            conferenceMeetingBindRateLimiter = FederationInboxRateLimiter(),
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
