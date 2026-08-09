package network.lapis.cloud.server.rpc

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
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
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import network.lapis.cloud.server.conference.ConferenceConfig
import network.lapis.cloud.server.conference.LiveKitAdminClient
import network.lapis.cloud.server.conference.LiveKitAdminException
import network.lapis.cloud.server.conference.LiveKitParticipantInfo
import network.lapis.cloud.server.conference.LiveKitRoomInfo
import network.lapis.cloud.server.db.DatabaseConfig
import network.lapis.cloud.server.db.DevSeedData
import network.lapis.cloud.server.db.generated.AccountTable
import network.lapis.cloud.server.db.generated.ConferenceParticipationTable
import network.lapis.cloud.server.db.generated.ConferenceRoomTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.server.federation.FederationInboxRateLimiter
import network.lapis.cloud.server.security.LoginRateLimiter
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.ConferenceJoinTokenDto
import network.lapis.cloud.shared.domain.ConferenceParticipantDto
import network.lapis.cloud.shared.domain.ConferenceRoomDto
import network.lapis.cloud.shared.domain.ConferenceRoomInput
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.rpc.BadRequestException
import network.lapis.cloud.shared.rpc.ConflictException
import network.lapis.cloud.shared.rpc.ForbiddenException
import network.lapis.cloud.shared.rpc.NotFoundException
import network.lapis.cloud.shared.rpc.UnauthenticatedException
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid

private const val ADMIN_ID = "00000000-0000-0000-0000-000000000001"
private const val BOARD_ID = "00000000-0000-0000-0000-000000000002"

/** A [ConferenceConfig] with `enabled=true`, matching [FakeLiveKitAdminClient]'s own in-memory expectations -- built via [ConferenceConfig.load]'s injectable `env` seam (see that class's own KDoc) so no real environment variables are ever touched. */
private val ENABLED_CONFIG =
    ConferenceConfig.load { key ->
        when (key) {
            "LAPIS_LIVEKIT_URL" -> "ws://localhost:7880"
            "LAPIS_LIVEKIT_API_KEY" -> "test-livekit-key"
            "LAPIS_LIVEKIT_API_SECRET" -> "test-livekit-secret-at-least-32-bytes-long!!"
            "LAPIS_LIVEKIT_TOKEN_TTL_MINUTES" -> "240"
            "LAPIS_CONFERENCE_MAX_PARTICIPANTS" -> "25"
            else -> null
        }
    }

/** `enabled=false` -- every field unset, see [ConferenceConfig.load] KDoc "Startup behaviour" case 1. */
private val DISABLED_CONFIG = ConferenceConfig.load { null }

/** Same as [ENABLED_CONFIG] plus `turnEnabled=true` -- audit-round-1 fix coverage, see [ConferenceConfig] KDoc "TURN is independently optional". */
private val ENABLED_CONFIG_WITH_TURN =
    ConferenceConfig.load { key ->
        when (key) {
            "LAPIS_LIVEKIT_URL" -> "ws://localhost:7880"
            "LAPIS_LIVEKIT_API_KEY" -> "test-livekit-key"
            "LAPIS_LIVEKIT_API_SECRET" -> "test-livekit-secret-at-least-32-bytes-long!!"
            "LAPIS_LIVEKIT_TOKEN_TTL_MINUTES" -> "240"
            "LAPIS_CONFERENCE_MAX_PARTICIPANTS" -> "25"
            "LAPIS_TURN_URLS" -> "turn:127.0.0.1:3478?transport=udp"
            "LAPIS_TURN_SHARED_SECRET" -> "test-turn-shared-secret-at-least-32-bytes!!"
            else -> null
        }
    }

/**
 * Hermetic, in-memory stand-in for [LiveKitAdminClient] -- no real LiveKit container involved, per
 * this wave's own testPlan ("Testcontainers is not introduced ... the full authorization matrix via
 * a fake LiveKitAdminClient"). Tracks every call for assertions (e.g. [deleteRoomCallCount] proves
 * [ConferenceService.endRoom]'s idempotency: LiveKit's `DeleteRoom` must be called exactly once even
 * if `endRoom` is invoked twice on an already-ended room).
 */
private class FakeLiveKitAdminClient : LiveKitAdminClient {
    private val rooms = mutableMapOf<String, LiveKitRoomInfo>()
    private val participantsByRoom = mutableMapOf<String, MutableList<LiveKitParticipantInfo>>()
    var deleteRoomCallCount = 0
        private set
    var removeParticipantCallCount = 0
        private set

    /** When `true`, the NEXT call (any method) throws [LiveKitAdminException] once, then resets. */
    var failNextCall = false

    override suspend fun createRoom(
        name: String,
        maxParticipants: Int,
        emptyTimeoutSeconds: Int,
    ): LiveKitRoomInfo {
        maybeFail()
        val info = LiveKitRoomInfo(sid = "RM_$name", name = name, maxParticipants = maxParticipants, numParticipants = 0)
        rooms[name] = info
        participantsByRoom.getOrPut(name) { mutableListOf() }
        return info
    }

    override suspend fun deleteRoom(name: String) {
        maybeFail()
        deleteRoomCallCount++
        rooms.remove(name)
        participantsByRoom.remove(name)
    }

    override suspend fun listRooms(): List<LiveKitRoomInfo> {
        maybeFail()
        return rooms.values.toList()
    }

    override suspend fun listParticipants(room: String): List<LiveKitParticipantInfo> {
        maybeFail()
        return participantsByRoom[room].orEmpty()
    }

    override suspend fun removeParticipant(
        room: String,
        identity: String,
    ) {
        maybeFail()
        removeParticipantCallCount++
        participantsByRoom[room]?.removeIf { it.identity == identity }
    }

    /** Test-only seam: simulates a participant being live-connected in LiveKit's own roster, independent of this server's own `conference_participation` rows. */
    fun seedLiveParticipant(
        room: String,
        identity: String,
    ) {
        participantsByRoom.getOrPut(room) { mutableListOf() }.add(LiveKitParticipantInfo(identity = identity, name = identity))
    }

    /** Test-only seam: simulates LiveKit no longer knowing about a room at all (e.g. it expired server-side) -- used by the reconciliation tests. */
    fun forgetRoom(name: String) {
        rooms.remove(name)
        participantsByRoom.remove(name)
    }

    private fun maybeFail() {
        if (failNextCall) {
            failNextCall = false
            throw LiveKitAdminException("simulated LiveKit failure")
        }
    }
}

/**
 * Exercises [ConferenceService] end to end, mirroring [AuctionServiceTest]/[PeerTransferServiceTest]'s
 * house style (throwaway routes calling the service class directly, fields pipe-separated in the
 * response body, no wire format to reverse-engineer). Every LiveKit-touching call goes through
 * [FakeLiveKitAdminClient] -- no Docker/Colima/real LiveKit container is involved (see this wave's
 * own testPlan for why: a real-container run is a separate, env-gated integration test, not this
 * hermetic suite). [afterSpec] hard-deletes every room/participation/member row this file created.
 */
class ConferenceServiceTest :
    FunSpec({
        val createdMemberIds = mutableListOf<Uuid>()

        beforeSpec {
            DatabaseConfig.connect()
            DevSeedData.seedIfEmpty(force = true)
        }

        afterSpec { cleanUpConferenceTestData(createdMemberIds) }

        fun createTestMember(
            email: String,
            status: MemberStatus = MemberStatus.AKTIV,
        ): Uuid {
            val id = Uuid.random()
            transaction {
                MemberTable.insert {
                    it[MemberTable.id] = id
                    it[displayName] = "Konferenz Testmitglied"
                    it[MemberTable.email] = email
                    it[MemberTable.status] = status
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

        // ── getAvailability ───────────────────────────────────────────────

        test(
            "getAvailability: enabled config reports enabled+serverUrl, disabled config reports enabled=false and no serverUrl, never throws either way",
        ) {
            testApplication {
                application {
                    install(StatusPages) { installConferenceExceptionHandlers() }
                    routing { registerConferenceTestRoutes(FakeLiveKitAdminClient(), LoginRateLimiter(), ENABLED_CONFIG, DISABLED_CONFIG) }
                }
                val member = createTestMember("conf-availability@example.org")

                val enabled = client.get("/test/availability-enabled") { header("X-Member-Id", member.toString()) }
                enabled.status shouldBe HttpStatusCode.OK
                enabled.bodyAsText() shouldBe "true|ws://localhost:7880|25"

                val disabled = client.get("/test/availability-disabled") { header("X-Member-Id", member.toString()) }
                disabled.status shouldBe HttpStatusCode.OK
                disabled.bodyAsText() shouldBe "false|null|25"
            }
        }

        test("getAvailability: unauthenticated caller is rejected") {
            testApplication {
                application {
                    install(StatusPages) { installConferenceExceptionHandlers() }
                    routing { registerConferenceTestRoutes(FakeLiveKitAdminClient(), LoginRateLimiter(), ENABLED_CONFIG, DISABLED_CONFIG) }
                }
                client.get("/test/availability-enabled").status shouldBe HttpStatusCode.Unauthorized
            }
        }

        // ── createRoom ─────────────────────────────────────────────────────

        test("createRoom: happy path creates a MODERATOR room with a fresh lc-<uuid> LiveKit name and the configured maxParticipants") {
            testApplication {
                application {
                    install(StatusPages) { installConferenceExceptionHandlers() }
                    routing { registerConferenceTestRoutes(FakeLiveKitAdminClient(), LoginRateLimiter(), ENABLED_CONFIG, DISABLED_CONFIG) }
                }
                val creator = createTestMember("conf-create-happy@example.org")

                val response =
                    client.post("/test/create-room?title=Vorstandssitzung") { header("X-Member-Id", creator.toString()) }
                response.status shouldBe HttpStatusCode.OK
                val parts = response.bodyAsText().split("|")
                val (id, title, livekitRoomName, createdBy, active) = parts
                val (maxParticipants, liveCount, myRole) = listOf(parts[5], parts[6], parts[7])
                title shouldBe "Vorstandssitzung"
                livekitRoomName.startsWith("lc-") shouldBe true
                createdBy shouldBe creator.toString()
                active shouldBe "true"
                maxParticipants shouldBe "25"
                liveCount shouldBe "0"
                myRole shouldBe "MODERATOR"

                val row = transaction { ConferenceRoomTable.selectAll().where { ConferenceRoomTable.id eq Uuid.parse(id) }.single() }
                row[ConferenceRoomTable.livekitRoomName] shouldBe livekitRoomName
                row[ConferenceRoomTable.endedAt] shouldBe null
            }
        }

        test("createRoom: blank title, too-long title, and too-long description are all rejected with BadRequest and no row is written") {
            testApplication {
                application {
                    install(StatusPages) { installConferenceExceptionHandlers() }
                    routing { registerConferenceTestRoutes(FakeLiveKitAdminClient(), LoginRateLimiter(), ENABLED_CONFIG, DISABLED_CONFIG) }
                }
                val creator = createTestMember("conf-create-validation@example.org")

                client.post("/test/create-room?title=") { header("X-Member-Id", creator.toString()) }.status shouldBe
                    HttpStatusCode.BadRequest
                client
                    .post("/test/create-room?title=${"x".repeat(201)}") {
                        header("X-Member-Id", creator.toString())
                    }.status shouldBe HttpStatusCode.BadRequest
                client
                    .post("/test/create-room?title=OK&description=${"x".repeat(1001)}") {
                        header("X-Member-Id", creator.toString())
                    }.status shouldBe HttpStatusCode.BadRequest

                transaction { ConferenceRoomTable.selectAll().where { ConferenceRoomTable.createdByMemberId eq creator }.count() } shouldBe
                    0L
            }
        }

        test("createRoom: rejected for a non-AKTIV (ANTRAG) member") {
            testApplication {
                application {
                    install(StatusPages) { installConferenceExceptionHandlers() }
                    routing { registerConferenceTestRoutes(FakeLiveKitAdminClient(), LoginRateLimiter(), ENABLED_CONFIG, DISABLED_CONFIG) }
                }
                val applicant = createTestMember("conf-create-antrag@example.org", status = MemberStatus.ANTRAG)

                client.post("/test/create-room?title=Sitzung") { header("X-Member-Id", applicant.toString()) }.status shouldBe
                    HttpStatusCode.Forbidden
            }
        }

        test("createRoom: rejected with Conflict once the (disabled) feature gate is unconfigured") {
            testApplication {
                application {
                    install(StatusPages) { installConferenceExceptionHandlers() }
                    routing { registerConferenceTestRoutes(FakeLiveKitAdminClient(), LoginRateLimiter(), DISABLED_CONFIG, DISABLED_CONFIG) }
                }
                val creator = createTestMember("conf-create-disabled@example.org")

                client.post("/test/create-room?title=Sitzung") { header("X-Member-Id", creator.toString()) }.status shouldBe
                    HttpStatusCode.Conflict
            }
        }

        test("createRoom: throttled after the configured number of attempts, regardless of success/failure") {
            testApplication {
                application {
                    install(StatusPages) { installConferenceExceptionHandlers() }
                    routing {
                        registerConferenceTestRoutes(
                            FakeLiveKitAdminClient(),
                            LoginRateLimiter(maxFailures = 2),
                            ENABLED_CONFIG,
                            DISABLED_CONFIG,
                        )
                    }
                }
                val creator = createTestMember("conf-create-throttle@example.org")

                client.post("/test/create-room?title=One") { header("X-Member-Id", creator.toString()) }.status shouldBe HttpStatusCode.OK
                client.post("/test/create-room?title=Two") { header("X-Member-Id", creator.toString()) }.status shouldBe HttpStatusCode.OK
                client.post("/test/create-room?title=Three") { header("X-Member-Id", creator.toString()) }.status shouldBe
                    HttpStatusCode.Conflict
            }
        }

        // ── joinRoom ───────────────────────────────────────────────────────

        test(
            "joinRoom: creator receives a MODERATOR token, a second member receives a PARTICIPANT token, both create open participation rows",
        ) {
            testApplication {
                application {
                    install(StatusPages) { installConferenceExceptionHandlers() }
                    routing { registerConferenceTestRoutes(FakeLiveKitAdminClient(), LoginRateLimiter(), ENABLED_CONFIG, DISABLED_CONFIG) }
                }
                val creator = createTestMember("conf-join-creator@example.org")
                val other = createTestMember("conf-join-other@example.org")
                val roomId = createRoom(client, creator, "Kleinsitzung")

                val creatorJoin = client.post("/test/join-room?roomId=$roomId") { header("X-Member-Id", creator.toString()) }
                creatorJoin.status shouldBe HttpStatusCode.OK
                val crParts = creatorJoin.bodyAsText().split("|")
                crParts[0] shouldBe roomId
                crParts[1].startsWith("lc-") shouldBe true
                crParts[2] shouldBe "ws://localhost:7880"
                crParts[3] shouldBe creator.toString()
                crParts[4] shouldBe "MODERATOR"
                crParts[5] shouldBe "true"

                val otherJoin = client.post("/test/join-room?roomId=$roomId") { header("X-Member-Id", other.toString()) }
                otherJoin.bodyAsText().split("|")[4] shouldBe "PARTICIPANT"

                val openCount =
                    transaction {
                        ConferenceParticipationTable
                            .selectAll()
                            .where { ConferenceParticipationTable.roomId eq Uuid.parse(roomId) }
                            .count()
                    }
                openCount shouldBe 2L
            }
        }

        test("joinRoom: nonexistent room is NotFound, an already-ended room is Conflict") {
            testApplication {
                application {
                    install(StatusPages) { installConferenceExceptionHandlers() }
                    routing { registerConferenceTestRoutes(FakeLiveKitAdminClient(), LoginRateLimiter(), ENABLED_CONFIG, DISABLED_CONFIG) }
                }
                val creator = createTestMember("conf-join-missing@example.org")
                val roomId = createRoom(client, creator, "Wird beendet")

                client
                    .post("/test/join-room?roomId=${Uuid.random()}") {
                        header("X-Member-Id", creator.toString())
                    }.status shouldBe HttpStatusCode.NotFound

                client.post("/test/end-room?roomId=$roomId") { header("X-Member-Id", creator.toString()) }
                client
                    .post("/test/join-room?roomId=$roomId") {
                        header("X-Member-Id", creator.toString())
                    }.status shouldBe HttpStatusCode.Conflict
            }
        }

        // ── joinRoom: TURN credential (audit-round-1 fix) ─────────────────────

        test("joinRoom: no TURN servers in the response when LAPIS_TURN_URLS/_SHARED_SECRET are unconfigured") {
            testApplication {
                application {
                    install(StatusPages) { installConferenceExceptionHandlers() }
                    routing { registerConferenceTestRoutes(FakeLiveKitAdminClient(), LoginRateLimiter(), ENABLED_CONFIG, DISABLED_CONFIG) }
                }
                val creator = createTestMember("conf-join-no-turn@example.org")
                val roomId = createRoom(client, creator, "No-Turn")

                val join = client.post("/test/join-room?roomId=$roomId") { header("X-Member-Id", creator.toString()) }
                join.bodyAsText().split("|")[6] shouldBe "false"
            }
        }

        test("joinRoom: a fresh, short-lived TURN credential is minted when TURN is configured") {
            testApplication {
                application {
                    install(StatusPages) { installConferenceExceptionHandlers() }
                    routing {
                        registerConferenceTestRoutes(
                            FakeLiveKitAdminClient(),
                            LoginRateLimiter(),
                            ENABLED_CONFIG_WITH_TURN,
                            DISABLED_CONFIG,
                        )
                    }
                }
                val creator = createTestMember("conf-join-turn@example.org")
                val roomId = createRoom(client, creator, "With-Turn")

                val join = client.post("/test/join-room?roomId=$roomId") { header("X-Member-Id", creator.toString()) }
                join.bodyAsText().split("|")[6] shouldBe "true"
            }
        }

        // ── Request-rate throttling beyond createRoom (audit-round-1 fix) ────

        test("joinRoom: throttled after the configured number of requests, independently of leaveRoom's own budget") {
            testApplication {
                application {
                    install(StatusPages) { installConferenceExceptionHandlers() }
                    routing {
                        registerConferenceTestRoutes(
                            FakeLiveKitAdminClient(),
                            LoginRateLimiter(),
                            ENABLED_CONFIG,
                            DISABLED_CONFIG,
                            joinRateLimiter = FederationInboxRateLimiter(maxRequests = 2, window = 1.minutes),
                        )
                    }
                }
                val creator = createTestMember("conf-join-throttle@example.org")
                val roomId = createRoom(client, creator, "Join-Throttle")

                client.post("/test/join-room?roomId=$roomId") { header("X-Member-Id", creator.toString()) }.status shouldBe
                    HttpStatusCode.OK
                client.post("/test/join-room?roomId=$roomId") { header("X-Member-Id", creator.toString()) }.status shouldBe
                    HttpStatusCode.OK
                client.post("/test/join-room?roomId=$roomId") { header("X-Member-Id", creator.toString()) }.status shouldBe
                    HttpStatusCode.Conflict
                // leaveRoom's own, independent budget is untouched by joinRoom's throttle above.
                client.post("/test/leave-room?roomId=$roomId") { header("X-Member-Id", creator.toString()) }.status shouldBe
                    HttpStatusCode.OK
            }
        }

        test("leaveRoom: throttled after the configured number of requests") {
            testApplication {
                application {
                    install(StatusPages) { installConferenceExceptionHandlers() }
                    routing {
                        registerConferenceTestRoutes(
                            FakeLiveKitAdminClient(),
                            LoginRateLimiter(),
                            ENABLED_CONFIG,
                            DISABLED_CONFIG,
                            leaveRateLimiter = FederationInboxRateLimiter(maxRequests = 2, window = 1.minutes),
                        )
                    }
                }
                val creator = createTestMember("conf-leave-throttle@example.org")
                val roomId = createRoom(client, creator, "Leave-Throttle")

                client.post("/test/leave-room?roomId=$roomId") { header("X-Member-Id", creator.toString()) }.status shouldBe
                    HttpStatusCode.OK
                client.post("/test/leave-room?roomId=$roomId") { header("X-Member-Id", creator.toString()) }.status shouldBe
                    HttpStatusCode.OK
                client.post("/test/leave-room?roomId=$roomId") { header("X-Member-Id", creator.toString()) }.status shouldBe
                    HttpStatusCode.Conflict
            }
        }

        test(
            "listActiveRooms/getRoom/listParticipants: throttled after the configured number of requests, sharing ONE budget",
        ) {
            testApplication {
                application {
                    install(StatusPages) { installConferenceExceptionHandlers() }
                    routing {
                        registerConferenceTestRoutes(
                            FakeLiveKitAdminClient(),
                            LoginRateLimiter(),
                            ENABLED_CONFIG,
                            DISABLED_CONFIG,
                            listRateLimiter = FederationInboxRateLimiter(maxRequests = 3, window = 1.minutes),
                        )
                    }
                }
                val creator = createTestMember("conf-list-throttle@example.org")
                val roomId = createRoom(client, creator, "List-Throttle")

                // Three calls SPREAD ACROSS all three list-shaped methods, not three calls to the
                // same one -- proves the budget is genuinely shared, not per-method.
                client.get("/test/list-active-rooms") { header("X-Member-Id", creator.toString()) }.status shouldBe
                    HttpStatusCode.OK
                client.get("/test/get-room?roomId=$roomId") { header("X-Member-Id", creator.toString()) }.status shouldBe
                    HttpStatusCode.OK
                client.get("/test/list-participants?roomId=$roomId") { header("X-Member-Id", creator.toString()) }.status shouldBe
                    HttpStatusCode.OK
                client.get("/test/list-active-rooms") { header("X-Member-Id", creator.toString()) }.status shouldBe
                    HttpStatusCode.Conflict
            }
        }

        // ── leaveRoom ──────────────────────────────────────────────────────

        test("leaveRoom: closes only the caller's own open participation, is a no-op when the caller never joined") {
            testApplication {
                application {
                    install(StatusPages) { installConferenceExceptionHandlers() }
                    routing { registerConferenceTestRoutes(FakeLiveKitAdminClient(), LoginRateLimiter(), ENABLED_CONFIG, DISABLED_CONFIG) }
                }
                val creator = createTestMember("conf-leave-creator@example.org")
                val other = createTestMember("conf-leave-other@example.org")
                val bystander = createTestMember("conf-leave-bystander@example.org")
                val roomId = createRoom(client, creator, "Leave-Test")
                client.post("/test/join-room?roomId=$roomId") { header("X-Member-Id", creator.toString()) }
                client.post("/test/join-room?roomId=$roomId") { header("X-Member-Id", other.toString()) }

                client.post("/test/leave-room?roomId=$roomId") { header("X-Member-Id", other.toString()) }.status shouldBe HttpStatusCode.OK
                // No-op: bystander never joined.
                client.post("/test/leave-room?roomId=$roomId") { header("X-Member-Id", bystander.toString()) }.status shouldBe
                    HttpStatusCode.OK

                val creatorOpen =
                    transaction {
                        ConferenceParticipationTable
                            .selectAll()
                            .where {
                                (ConferenceParticipationTable.roomId eq Uuid.parse(roomId)) and
                                    (ConferenceParticipationTable.memberId eq creator) and
                                    ConferenceParticipationTable.leftAt.isNull()
                            }.count()
                    }
                creatorOpen shouldBe 1L
                val otherOpen =
                    transaction {
                        ConferenceParticipationTable
                            .selectAll()
                            .where {
                                (ConferenceParticipationTable.roomId eq Uuid.parse(roomId)) and
                                    (ConferenceParticipationTable.memberId eq other) and
                                    ConferenceParticipationTable.leftAt.isNull()
                            }.count()
                    }
                otherOpen shouldBe 0L
            }
        }

        // ── endRoom ────────────────────────────────────────────────────────

        test(
            "endRoom: creator ends the room, LiveKit DeleteRoom is called exactly once even on a repeated call (idempotent), all open participations close",
        ) {
            testApplication {
                val fakeClient = FakeLiveKitAdminClient()
                application {
                    install(StatusPages) { installConferenceExceptionHandlers() }
                    routing { registerConferenceTestRoutes(fakeClient, LoginRateLimiter(), ENABLED_CONFIG, DISABLED_CONFIG) }
                }
                val creator = createTestMember("conf-end-creator@example.org")
                val other = createTestMember("conf-end-other@example.org")
                val roomId = createRoom(client, creator, "End-Test")
                client.post("/test/join-room?roomId=$roomId") { header("X-Member-Id", creator.toString()) }
                client.post("/test/join-room?roomId=$roomId") { header("X-Member-Id", other.toString()) }

                val first = client.post("/test/end-room?roomId=$roomId") { header("X-Member-Id", creator.toString()) }
                first.status shouldBe HttpStatusCode.OK
                first.bodyAsText().split("|")[4] shouldBe "false" // active

                val second = client.post("/test/end-room?roomId=$roomId") { header("X-Member-Id", creator.toString()) }
                second.status shouldBe HttpStatusCode.OK
                fakeClient.deleteRoomCallCount shouldBe 1

                val stillOpen =
                    transaction {
                        ConferenceParticipationTable
                            .selectAll()
                            .where {
                                (ConferenceParticipationTable.roomId eq Uuid.parse(roomId)) and
                                    ConferenceParticipationTable.leftAt.isNull()
                            }.count()
                    }
                stillOpen shouldBe 0L
            }
        }

        test("endRoom: a non-creator, non-privileged caller is forbidden; a global BOARD member may end any room") {
            testApplication {
                application {
                    install(StatusPages) { installConferenceExceptionHandlers() }
                    routing { registerConferenceTestRoutes(FakeLiveKitAdminClient(), LoginRateLimiter(), ENABLED_CONFIG, DISABLED_CONFIG) }
                }
                val creator = createTestMember("conf-end-forbidden-creator@example.org")
                val bystander = createTestMember("conf-end-forbidden-bystander@example.org")
                val roomId = createRoom(client, creator, "Forbidden-Test")

                client
                    .post("/test/end-room?roomId=$roomId") {
                        header("X-Member-Id", bystander.toString())
                    }.status shouldBe HttpStatusCode.Forbidden

                client.post("/test/end-room?roomId=$roomId") { header("X-Member-Id", BOARD_ID) }.status shouldBe HttpStatusCode.OK
            }
        }

        // ── removeParticipant ──────────────────────────────────────────────

        test(
            "removeParticipant: moderator removes a participant (LiveKit RemoveParticipant called, participation closed); refuses to remove the moderator",
        ) {
            testApplication {
                val fakeClient = FakeLiveKitAdminClient()
                application {
                    install(StatusPages) { installConferenceExceptionHandlers() }
                    routing { registerConferenceTestRoutes(fakeClient, LoginRateLimiter(), ENABLED_CONFIG, DISABLED_CONFIG) }
                }
                val creator = createTestMember("conf-remove-creator@example.org")
                val other = createTestMember("conf-remove-other@example.org")
                val roomId = createRoom(client, creator, "Remove-Test")
                client.post("/test/join-room?roomId=$roomId") { header("X-Member-Id", creator.toString()) }
                client.post("/test/join-room?roomId=$roomId") { header("X-Member-Id", other.toString()) }

                val selfRemoval =
                    client.post("/test/remove-participant?roomId=$roomId&memberId=$creator") { header("X-Member-Id", creator.toString()) }
                selfRemoval.status shouldBe HttpStatusCode.Conflict

                val removal =
                    client.post("/test/remove-participant?roomId=$roomId&memberId=$other") { header("X-Member-Id", creator.toString()) }
                removal.status shouldBe HttpStatusCode.OK
                fakeClient.removeParticipantCallCount shouldBe 1

                val otherOpen =
                    transaction {
                        ConferenceParticipationTable
                            .selectAll()
                            .where {
                                (ConferenceParticipationTable.roomId eq Uuid.parse(roomId)) and
                                    (ConferenceParticipationTable.memberId eq other) and
                                    ConferenceParticipationTable.leftAt.isNull()
                            }.count()
                    }
                otherOpen shouldBe 0L
            }
        }

        test("removeParticipant: a non-moderator, non-privileged caller is forbidden") {
            testApplication {
                application {
                    install(StatusPages) { installConferenceExceptionHandlers() }
                    routing { registerConferenceTestRoutes(FakeLiveKitAdminClient(), LoginRateLimiter(), ENABLED_CONFIG, DISABLED_CONFIG) }
                }
                val creator = createTestMember("conf-remove-forbidden-creator@example.org")
                val other = createTestMember("conf-remove-forbidden-other@example.org")
                val bystander = createTestMember("conf-remove-forbidden-bystander@example.org")
                val roomId = createRoom(client, creator, "Forbidden-Remove")
                client.post("/test/join-room?roomId=$roomId") { header("X-Member-Id", other.toString()) }

                client
                    .post("/test/remove-participant?roomId=$roomId&memberId=$other") {
                        header("X-Member-Id", bystander.toString())
                    }.status shouldBe HttpStatusCode.Forbidden
            }
        }

        // ── renameRoom (V1.0 Videokonferenzen Wave 4 "Politur", D1) ──────────

        test("renameRoom: the creator can rename an active room") {
            testApplication {
                application {
                    install(StatusPages) { installConferenceExceptionHandlers() }
                    routing { registerConferenceTestRoutes(FakeLiveKitAdminClient(), LoginRateLimiter(), ENABLED_CONFIG, DISABLED_CONFIG) }
                }
                val creator = createTestMember("conf-rename-creator@example.org")
                val roomId = createRoom(client, creator, "Alter-Titel")

                val response =
                    client.post("/test/rename-room?roomId=$roomId&title=Neuer-Titel") {
                        header("X-Member-Id", creator.toString())
                    }
                response.status shouldBe HttpStatusCode.OK
                response.bodyAsText().split("|")[1] shouldBe "Neuer-Titel"

                val persisted =
                    transaction { ConferenceRoomTable.selectAll().where { ConferenceRoomTable.id eq Uuid.parse(roomId) }.single() }
                persisted[ConferenceRoomTable.title] shouldBe "Neuer-Titel"
            }
        }

        test("renameRoom: a non-creator BOARD/ADMIN member can also rename (privileged escalation)") {
            testApplication {
                application {
                    install(StatusPages) { installConferenceExceptionHandlers() }
                    routing { registerConferenceTestRoutes(FakeLiveKitAdminClient(), LoginRateLimiter(), ENABLED_CONFIG, DISABLED_CONFIG) }
                }
                val creator = createTestMember("conf-rename-privileged-creator@example.org")
                val roomId = createRoom(client, creator, "Vorstandssitzung")

                client
                    .post("/test/rename-room?roomId=$roomId&title=Umbenannt-Von-Board") {
                        header("X-Member-Id", BOARD_ID)
                    }.status shouldBe HttpStatusCode.OK
            }
        }

        test("renameRoom: a plain, non-privileged participant is forbidden") {
            testApplication {
                application {
                    install(StatusPages) { installConferenceExceptionHandlers() }
                    routing { registerConferenceTestRoutes(FakeLiveKitAdminClient(), LoginRateLimiter(), ENABLED_CONFIG, DISABLED_CONFIG) }
                }
                val creator = createTestMember("conf-rename-forbidden-creator@example.org")
                val other = createTestMember("conf-rename-forbidden-other@example.org")
                val roomId = createRoom(client, creator, "Fremde-Besprechung")
                client.post("/test/join-room?roomId=$roomId") { header("X-Member-Id", other.toString()) }

                client
                    .post("/test/rename-room?roomId=$roomId&title=Uebernommen") {
                        header("X-Member-Id", other.toString())
                    }.status shouldBe HttpStatusCode.Forbidden
            }
        }

        test("renameRoom: blank or over-length titles are rejected as BadRequest") {
            testApplication {
                application {
                    install(StatusPages) { installConferenceExceptionHandlers() }
                    routing { registerConferenceTestRoutes(FakeLiveKitAdminClient(), LoginRateLimiter(), ENABLED_CONFIG, DISABLED_CONFIG) }
                }
                val creator = createTestMember("conf-rename-invalid@example.org")
                val roomId = createRoom(client, creator, "Gueltiger-Titel")

                client
                    .post("/test/rename-room?roomId=$roomId&title=%20%20") {
                        header("X-Member-Id", creator.toString())
                    }.status shouldBe HttpStatusCode.BadRequest

                val tooLong = "x".repeat(201)
                client
                    .post("/test/rename-room?roomId=$roomId&title=$tooLong") {
                        header("X-Member-Id", creator.toString())
                    }.status shouldBe HttpStatusCode.BadRequest
            }
        }

        test("renameRoom: an already-ended room is rejected as Conflict") {
            testApplication {
                application {
                    install(StatusPages) { installConferenceExceptionHandlers() }
                    routing { registerConferenceTestRoutes(FakeLiveKitAdminClient(), LoginRateLimiter(), ENABLED_CONFIG, DISABLED_CONFIG) }
                }
                val creator = createTestMember("conf-rename-ended@example.org")
                val roomId = createRoom(client, creator, "Wird-Beendet")
                client.post("/test/end-room?roomId=$roomId") { header("X-Member-Id", creator.toString()) }

                client
                    .post("/test/rename-room?roomId=$roomId&title=Zu-Spaet") {
                        header("X-Member-Id", creator.toString())
                    }.status shouldBe HttpStatusCode.Conflict
            }
        }

        test("renameRoom: a nonexistent room is rejected as NotFound") {
            testApplication {
                application {
                    install(StatusPages) { installConferenceExceptionHandlers() }
                    routing { registerConferenceTestRoutes(FakeLiveKitAdminClient(), LoginRateLimiter(), ENABLED_CONFIG, DISABLED_CONFIG) }
                }
                val creator = createTestMember("conf-rename-missing@example.org")

                client
                    .post("/test/rename-room?roomId=${Uuid.random()}&title=Egal") {
                        header("X-Member-Id", creator.toString())
                    }.status shouldBe HttpStatusCode.NotFound
            }
        }

        test("renameRoom: rejected with Conflict once the (disabled) feature gate is unconfigured") {
            testApplication {
                application {
                    install(StatusPages) { installConferenceExceptionHandlers() }
                    routing { registerConferenceTestRoutes(FakeLiveKitAdminClient(), LoginRateLimiter(), DISABLED_CONFIG, DISABLED_CONFIG) }
                }
                val creator = createTestMember("conf-rename-unconfigured@example.org")

                client
                    .post("/test/rename-room?roomId=${Uuid.random()}&title=Egal") {
                        header("X-Member-Id", creator.toString())
                    }.status shouldBe HttpStatusCode.Conflict
            }
        }

        // ── listActiveRooms / getRoom reconciliation ─────────────────────────

        test(
            "listActiveRooms: excludes ended rooms and lazily closes a room LiveKit no longer knows about once past the empty-timeout grace",
        ) {
            testApplication {
                val fakeClient = FakeLiveKitAdminClient()
                application {
                    install(StatusPages) { installConferenceExceptionHandlers() }
                    routing { registerConferenceTestRoutes(fakeClient, LoginRateLimiter(), ENABLED_CONFIG, DISABLED_CONFIG) }
                }
                val creator = createTestMember("conf-list-reconcile@example.org")
                val staleRoomId = createRoom(client, creator, "Stale-Room")
                val freshRoomId = createRoom(client, creator, "Fresh-Room")
                client.post("/test/join-room?roomId=$staleRoomId") { header("X-Member-Id", creator.toString()) }

                // Backdate the stale room's createdAt well past the empty-timeout grace, and remove
                // it from the fake LiveKit's own room registry (simulating a room that expired
                // server-side without this server ever hearing about it -- see IConferenceService
                // KDoc "Lazy reconciliation").
                val staleLivekitName =
                    transaction {
                        val row = ConferenceRoomTable.selectAll().where { ConferenceRoomTable.id eq Uuid.parse(staleRoomId) }.single()
                        val backdated = row[ConferenceRoomTable.createdAt].minusSecondsForTest(400)
                        ConferenceRoomTable.update({ ConferenceRoomTable.id eq Uuid.parse(staleRoomId) }) { it[createdAt] = backdated }
                        row[ConferenceRoomTable.livekitRoomName]
                    }
                fakeClient.forgetRoom(staleLivekitName)

                val response = client.get("/test/list-active-rooms") { header("X-Member-Id", creator.toString()) }
                response.status shouldBe HttpStatusCode.OK
                val ids =
                    response
                        .bodyAsText()
                        .split(";")
                        .filter { it.isNotBlank() }
                        .map { it.split("|")[0] }
                (freshRoomId in ids) shouldBe true
                (staleRoomId in ids) shouldBe false

                val staleRow =
                    transaction { ConferenceRoomTable.selectAll().where { ConferenceRoomTable.id eq Uuid.parse(staleRoomId) }.single() }
                staleRow[ConferenceRoomTable.endedAt].shouldNotBeNull()
                val staleParticipationOpen =
                    transaction {
                        ConferenceParticipationTable
                            .selectAll()
                            .where {
                                (ConferenceParticipationTable.roomId eq Uuid.parse(staleRoomId)) and
                                    ConferenceParticipationTable.leftAt.isNull()
                            }.count()
                    }
                staleParticipationOpen shouldBe 0L
            }
        }

        test("getRoom: not found is rejected, and reconciles the SAME single stale room independently of listActiveRooms") {
            testApplication {
                val fakeClient = FakeLiveKitAdminClient()
                application {
                    install(StatusPages) { installConferenceExceptionHandlers() }
                    routing { registerConferenceTestRoutes(fakeClient, LoginRateLimiter(), ENABLED_CONFIG, DISABLED_CONFIG) }
                }
                val creator = createTestMember("conf-getroom-reconcile@example.org")

                client
                    .get("/test/get-room?roomId=${Uuid.random()}") {
                        header("X-Member-Id", creator.toString())
                    }.status shouldBe HttpStatusCode.NotFound

                val roomId = createRoom(client, creator, "Getroom-Stale")
                val livekitName =
                    transaction {
                        val row = ConferenceRoomTable.selectAll().where { ConferenceRoomTable.id eq Uuid.parse(roomId) }.single()
                        val backdated = row[ConferenceRoomTable.createdAt].minusSecondsForTest(400)
                        ConferenceRoomTable.update({ ConferenceRoomTable.id eq Uuid.parse(roomId) }) { it[createdAt] = backdated }
                        row[ConferenceRoomTable.livekitRoomName]
                    }
                fakeClient.forgetRoom(livekitName)

                val response = client.get("/test/get-room?roomId=$roomId") { header("X-Member-Id", creator.toString()) }
                response.status shouldBe HttpStatusCode.OK
                response.bodyAsText().split("|")[4] shouldBe "false" // active
            }
        }

        // ── listParticipants ───────────────────────────────────────────────

        test("listParticipants: reflects this server's own join history combined with LiveKit's live roster") {
            testApplication {
                val fakeClient = FakeLiveKitAdminClient()
                application {
                    install(StatusPages) { installConferenceExceptionHandlers() }
                    routing { registerConferenceTestRoutes(fakeClient, LoginRateLimiter(), ENABLED_CONFIG, DISABLED_CONFIG) }
                }
                val creator = createTestMember("conf-listp-creator@example.org")
                val other = createTestMember("conf-listp-other@example.org")
                val roomId = createRoom(client, creator, "Listp-Test")
                client.post("/test/join-room?roomId=$roomId") { header("X-Member-Id", creator.toString()) }
                client.post("/test/join-room?roomId=$roomId") { header("X-Member-Id", other.toString()) }

                val livekitName =
                    transaction {
                        ConferenceRoomTable
                            .selectAll()
                            .where {
                                ConferenceRoomTable.id eq
                                    Uuid.parse(
                                        roomId,
                                    )
                            }.single()[ConferenceRoomTable.livekitRoomName]
                    }
                fakeClient.seedLiveParticipant(livekitName, creator.toString())
                // `other` joined via this server (a conference_participation row exists) but is NOT
                // seeded as live in LiveKit's own roster -- simulates a hard browser-tab crash that
                // never called leaveRoom, see ConferenceParticipantDto KDoc.

                val response = client.get("/test/list-participants?roomId=$roomId") { header("X-Member-Id", creator.toString()) }
                response.status shouldBe HttpStatusCode.OK
                val rows =
                    response
                        .bodyAsText()
                        .split(";")
                        .filter { it.isNotBlank() }
                        .map { it.split("|") }
                rows.size shouldBe 2
                val creatorRow = rows.single { it[0] == creator.toString() }
                creatorRow[1] shouldBe "MODERATOR"
                creatorRow[3] shouldBe "true" // live
                val otherRow = rows.single { it[0] == other.toString() }
                otherRow[1] shouldBe "PARTICIPANT"
                otherRow[3] shouldBe "false" // not live
            }
        }

        // ── LiveKit failure propagation ────────────────────────────────────

        test("a LiveKit admin-call failure surfaces as Conflict, not a raw 500") {
            testApplication {
                val fakeClient = FakeLiveKitAdminClient()
                application {
                    install(StatusPages) { installConferenceExceptionHandlers() }
                    routing { registerConferenceTestRoutes(fakeClient, LoginRateLimiter(), ENABLED_CONFIG, DISABLED_CONFIG) }
                }
                val creator = createTestMember("conf-livekit-failure@example.org")
                fakeClient.failNextCall = true

                client
                    .post("/test/create-room?title=Wird-fehlschlagen") {
                        header("X-Member-Id", creator.toString())
                    }.status shouldBe HttpStatusCode.Conflict
            }
        }
    })

private suspend fun createRoom(
    client: io.ktor.client.HttpClient,
    creatorId: Uuid,
    title: String,
): String {
    val response = client.post("/test/create-room?title=$title") { header("X-Member-Id", creatorId.toString()) }
    check(response.status == HttpStatusCode.OK) { "createRoom failed: ${response.status} ${response.bodyAsText()}" }
    return response.bodyAsText().split("|")[0]
}

private fun cleanUpConferenceTestData(memberIds: List<Uuid>) {
    if (memberIds.isEmpty()) return
    transaction {
        val roomIds =
            ConferenceRoomTable
                .selectAll()
                .where {
                    ConferenceRoomTable.createdByMemberId inList memberIds
                }.map { it[ConferenceRoomTable.id] }
        ConferenceParticipationTable.deleteWhere {
            (ConferenceParticipationTable.memberId inList memberIds) or (ConferenceParticipationTable.roomId inList roomIds)
        }
        ConferenceRoomTable.deleteWhere { ConferenceRoomTable.createdByMemberId inList memberIds }
        AccountTable.deleteWhere { AccountTable.memberId inList memberIds }
        MemberTable.deleteWhere { MemberTable.id inList memberIds }
    }
}

private fun StatusPagesConfig.installConferenceExceptionHandlers() {
    exception<UnauthenticatedException> { call, cause ->
        call.respondText(cause.message, status = HttpStatusCode.Unauthorized)
    }
    exception<ForbiddenException> { call, cause ->
        call.respondText(cause.message, status = HttpStatusCode.Forbidden)
    }
    exception<NotFoundException> { call, cause ->
        call.respondText(cause.message, status = HttpStatusCode.NotFound)
    }
    exception<ConflictException> { call, cause ->
        call.respondText(cause.message, status = HttpStatusCode.Conflict)
    }
    exception<BadRequestException> { call, cause ->
        call.respondText(cause.message, status = HttpStatusCode.BadRequest)
    }
}

/**
 * Shared throwaway routes for [ConferenceService] -- mirrors [AuctionServiceTest]'s
 * `registerAuctionTestRoutes` style. Fields are pipe-separated (`|`), lists of DTOs
 * semicolon-separated (`;`) -- room/member ids never contain either.
 *
 * [joinRateLimiter]/[leaveRateLimiter]/[listRateLimiter] (audit-round-1 fix) default to generous
 * production-matching budgets so ordinary tests (a handful of calls each) never trip them --
 * **constructed ONCE here, outside every route handler**, and passed EXPLICITLY into every
 * [ConferenceService] construction below, mirroring how [rateLimiter] itself is already a
 * caller-supplied, shared-across-calls instance. Relying on [ConferenceService]'s own constructor
 * DEFAULT for these three params instead would silently defeat throttling in tests (and would have
 * been an equally silent bug in production, if `Application.kt` had not been careful to construct
 * its own shared singletons the same way) -- a fresh default-argument instance gets minted on EVERY
 * `ConferenceService(...)` call, so nothing would ever accumulate across requests.
 */
private fun Route.registerConferenceTestRoutes(
    liveKitAdminClient: LiveKitAdminClient,
    rateLimiter: LoginRateLimiter,
    enabledConfig: ConferenceConfig,
    disabledConfig: ConferenceConfig,
    joinRateLimiter: FederationInboxRateLimiter = FederationInboxRateLimiter(maxRequests = 30, window = 1.minutes),
    leaveRateLimiter: FederationInboxRateLimiter = FederationInboxRateLimiter(maxRequests = 30, window = 1.minutes),
    listRateLimiter: FederationInboxRateLimiter = FederationInboxRateLimiter(maxRequests = 60, window = 1.minutes),
) {
    fun service(
        call: ApplicationCall,
        config: ConferenceConfig,
    ) = ConferenceService(
        call,
        liveKitAdminClient,
        rateLimiter,
        config,
        joinRoomRateLimiter = joinRateLimiter,
        leaveRoomRateLimiter = leaveRateLimiter,
        listRateLimiter = listRateLimiter,
    )
    get("/test/availability-enabled") {
        val service = service(call, enabledConfig)
        val dto = service.getAvailability()
        call.respondText("${dto.enabled}|${dto.serverUrl}|${dto.maxParticipants}")
    }
    get("/test/availability-disabled") {
        val service = service(call, disabledConfig)
        val dto = service.getAvailability()
        call.respondText("${dto.enabled}|${dto.serverUrl}|${dto.maxParticipants}")
    }
    post("/test/create-room") {
        val service = service(call, enabledConfig)
        val q = call.request.queryParameters
        val dto = service.createRoom(ConferenceRoomInput(title = q["title"]!!, description = q["description"] ?: ""))
        call.respondText(dto.toPipeString())
    }
    post("/test/join-room") {
        val service = service(call, enabledConfig)
        val q = call.request.queryParameters
        val dto = service.joinRoom(q["roomId"]!!)
        call.respondText(dto.toPipeString())
    }
    post("/test/leave-room") {
        val service = service(call, enabledConfig)
        val q = call.request.queryParameters
        service.leaveRoom(q["roomId"]!!)
        call.respondText("ok")
    }
    post("/test/end-room") {
        val service = service(call, enabledConfig)
        val q = call.request.queryParameters
        val dto = service.endRoom(q["roomId"]!!)
        call.respondText(dto.toPipeString())
    }
    get("/test/get-room") {
        val service = service(call, enabledConfig)
        val q = call.request.queryParameters
        val dto = service.getRoom(q["roomId"]!!)
        call.respondText(dto.toPipeString())
    }
    get("/test/list-active-rooms") {
        val service = service(call, enabledConfig)
        val dtos = service.listActiveRooms()
        call.respondText(dtos.joinToString(";") { it.toPipeString() })
    }
    get("/test/list-participants") {
        val service = service(call, enabledConfig)
        val q = call.request.queryParameters
        val dtos = service.listParticipants(q["roomId"]!!)
        call.respondText(dtos.joinToString(";") { it.toPipeString() })
    }
    post("/test/remove-participant") {
        val service = service(call, enabledConfig)
        val q = call.request.queryParameters
        service.removeParticipant(q["roomId"]!!, q["memberId"]!!)
        call.respondText("ok")
    }
    post("/test/rename-room") {
        val service = service(call, enabledConfig)
        val q = call.request.queryParameters
        val dto = service.renameRoom(q["roomId"]!!, q["title"]!!)
        call.respondText(dto.toPipeString())
    }
}

/** id|title|livekitRoomName|createdByMemberId|active|maxParticipants|liveParticipantCount|myRole */
private fun ConferenceRoomDto.toPipeString(): String =
    "$id|$title|$livekitRoomName|$createdByMemberId|$active|$maxParticipants|$liveParticipantCount|$myRole"

/** roomId|livekitRoomName|serverUrl|identity|role|hasToken|hasTurnServers -- [hasTurnServers] is audit-round-1 fix coverage, see [ConferenceServiceTest] "joinRoom: TURN credential" tests. */
private fun ConferenceJoinTokenDto.toPipeString(): String =
    "$roomId|$livekitRoomName|$serverUrl|$identity|$role|${token.isNotBlank()}|${turnServers.isNotEmpty()}"

/** memberId|role|leftAtIsNull|live */
private fun ConferenceParticipantDto.toPipeString(): String = "$memberId|$role|${leftAt == null}|$live"

private fun LocalDateTime.minusSecondsForTest(seconds: Int): LocalDateTime {
    val zone = TimeZone.currentSystemDefault()
    return toInstant(zone).minus(seconds.seconds).toLocalDateTime(zone)
}
