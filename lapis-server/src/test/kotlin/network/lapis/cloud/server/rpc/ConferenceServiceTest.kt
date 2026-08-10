package network.lapis.cloud.server.rpc

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.ktor.client.HttpClient
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
import kotlinx.coroutines.runBlocking
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
import network.lapis.cloud.server.db.DbClock
import network.lapis.cloud.server.db.DevSeedData
import network.lapis.cloud.server.db.generated.AccountTable
import network.lapis.cloud.server.db.generated.AuditLogEntryTable
import network.lapis.cloud.server.db.generated.ConferenceBreakoutAssignmentTable
import network.lapis.cloud.server.db.generated.ConferenceBreakoutRoomTable
import network.lapis.cloud.server.db.generated.ConferenceGuestConsentAcknowledgmentTable
import network.lapis.cloud.server.db.generated.ConferenceParticipationTable
import network.lapis.cloud.server.db.generated.ConferenceRoomTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.server.db.generated.OidcGuestProfileTable
import network.lapis.cloud.server.db.generated.SessionTable
import network.lapis.cloud.server.federation.FederationInboxRateLimiter
import network.lapis.cloud.server.federation.OidcGuestClaims
import network.lapis.cloud.server.federation.OidcGuestMemberStore
import network.lapis.cloud.server.security.LoginRateLimiter
import network.lapis.cloud.server.security.SessionStore
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.AuditEntityType
import network.lapis.cloud.shared.domain.ConferenceGuestConsentAcknowledgmentInput
import network.lapis.cloud.shared.domain.ConferenceGuestJoinInfoDto
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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
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

        /**
         * Wave 5 "Föderations-Gastbeitritt" -- a real `MemberStatus.GAST` member with a matching
         * `oidc_guest_profile` row, minted via the REAL production
         * [OidcGuestMemberStore.resolveOrCreateGuestMember] function (same pattern
         * `FederationGuestJourneyTest` uses at its own `:250`) rather than a hand-rolled insert, so
         * this test suite exercises the exact code path a real federated login produces. Returns
         * `(memberId, homeserverUrl)`.
         */
        fun createTestGuestMember(subjectSuffix: String): Pair<Uuid, String> {
            val issuer = "https://conf-guest-home-$subjectSuffix.example"
            val claims =
                OidcGuestClaims(
                    issuer = issuer,
                    subject = "conf-guest-subject-$subjectSuffix",
                    name = "Konferenz Testgast $subjectSuffix",
                    picture = null,
                    preferredUsername = null,
                    homeserverUrl = issuer,
                    membershipStatus = "AKTIV",
                )
            val id = OidcGuestMemberStore.resolveOrCreateGuestMember(claims, "openid profile_basic")
            createdMemberIds += id
            return id to issuer
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

        // ── joinRoom: guest token TTL (Wave 5 security-audit fix) ────────────

        test(
            "joinRoom: a GAST's token/TURN credential expire within guestTokenTtlMinutes (15min), not the AKTIV 240min default -- bounds the post-revocation replay window",
        ) {
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
                val creator = createTestMember("conf-w5-guestttl@example.org")
                val roomId = createRoom(client, creator, "Guest-TTL-Raum")
                client.post(
                    "/test/set-room-guest-access?roomId=$roomId&allowFederationGuests=true",
                ) { header("X-Member-Id", creator.toString()) }

                val creatorJoin = client.post("/test/join-room?roomId=$roomId") { header("X-Member-Id", creator.toString()) }
                val creatorExpiresAt = LocalDateTime.parse(creatorJoin.bodyAsText().split("|")[7])
                val creatorTtlMinutes =
                    creatorExpiresAt.toInstant(TimeZone.currentSystemDefault()).epochSeconds -
                        DbClock.nowLocalDateTime().toInstant(TimeZone.currentSystemDefault()).epochSeconds
                // Close to 240min (240*60=14400s) -- generous tolerance for test wall-clock jitter,
                // never anywhere near the 15min guest bound below.
                (creatorTtlMinutes > 14000) shouldBe true

                val (guestId, _) = createTestGuestMember("guestttl")
                val guestToken = SessionStore.createSession(guestId).rawToken
                val guestJoin =
                    client.post(
                        "/test/join-room?roomId=$roomId&consentVersion=${ConferenceGuestConsentDisclaimer.VERSION}" +
                            "&consentSha256=${ConferenceGuestConsentDisclaimer.SHA256}",
                    ) { header("Authorization", "Bearer $guestToken") }
                guestJoin.status shouldBe HttpStatusCode.OK
                val guestParts = guestJoin.bodyAsText().split("|")
                guestParts[6] shouldBe "true" // hasTurnServers -- guest gets a TURN credential too, same short TTL
                val guestExpiresAt = LocalDateTime.parse(guestParts[7])
                val guestTtlSeconds =
                    guestExpiresAt.toInstant(TimeZone.currentSystemDefault()).epochSeconds -
                        DbClock.nowLocalDateTime().toInstant(TimeZone.currentSystemDefault()).epochSeconds
                // 15min = 900s -- generous tolerance for test wall-clock jitter, but must stay far
                // below the AKTIV creator's ~14400s above.
                (guestTtlSeconds in 0..1200) shouldBe true
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

        test("Security-audit fix: setRoomGuestAccess is throttled after the configured number of requests") {
            testApplication {
                application {
                    install(StatusPages) { installConferenceExceptionHandlers() }
                    routing {
                        registerConferenceTestRoutes(
                            FakeLiveKitAdminClient(),
                            LoginRateLimiter(),
                            ENABLED_CONFIG,
                            DISABLED_CONFIG,
                            guestAccessRateLimiter = FederationInboxRateLimiter(maxRequests = 2, window = 1.minutes),
                        )
                    }
                }
                val creator = createTestMember("conf-w5-guestaccess-throttle@example.org")
                val roomId = createRoom(client, creator, "GuestAccess-Throttle")

                client
                    .post("/test/set-room-guest-access?roomId=$roomId&allowFederationGuests=true") {
                        header("X-Member-Id", creator.toString())
                    }.status shouldBe HttpStatusCode.OK
                client
                    .post("/test/set-room-guest-access?roomId=$roomId&allowFederationGuests=false") {
                        header("X-Member-Id", creator.toString())
                    }.status shouldBe HttpStatusCode.OK
                client
                    .post("/test/set-room-guest-access?roomId=$roomId&allowFederationGuests=true") {
                        header("X-Member-Id", creator.toString())
                    }.status shouldBe HttpStatusCode.Conflict
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

        // ── endRoom Wave 6 "Breakout-Räume" cascade ─────────────────────────

        test(
            "endRoom: cascades to delete every still-open breakout LiveKit room and stamps closed_at/recalled_at on their DB rows -- no orphaned LiveKit room left behind",
        ) {
            testApplication {
                val fakeClient = FakeLiveKitAdminClient()
                application {
                    install(StatusPages) { installConferenceExceptionHandlers() }
                    routing { registerConferenceTestRoutes(fakeClient, LoginRateLimiter(), ENABLED_CONFIG, DISABLED_CONFIG) }
                }
                val creator = createTestMember("conf-end-cascade-creator@example.org")
                val roomId = createRoom(client, creator, "Cascade-Test")
                val roomUuid = Uuid.parse(roomId)

                // Seed two open breakout rooms directly (DB + fake LiveKit) -- this test exercises
                // ConferenceService.endRoom's own Wave 6 cascade, not ConferenceBreakoutService
                // itself (see ConferenceBreakoutServiceTest for that).
                val breakoutRoomIds = mutableListOf<Uuid>()
                val now = DbClock.nowLocalDateTime()
                transaction {
                    repeat(2) { index ->
                        val breakoutRoomId = Uuid.random()
                        breakoutRoomIds += breakoutRoomId
                        val livekitRoomName = "lc-bo-cascade-test-$index-$breakoutRoomId"
                        ConferenceBreakoutRoomTable.insert {
                            it[id] = breakoutRoomId
                            it[parentRoomId] = roomUuid
                            it[label] = "Breakout-Raum ${index + 1}"
                            it[ConferenceBreakoutRoomTable.livekitRoomName] = livekitRoomName
                            it[createdByMemberId] = creator
                            it[createdAt] = now
                            it[closedAt] = null
                        }
                        ConferenceBreakoutAssignmentTable.insert {
                            it[id] = Uuid.random()
                            it[ConferenceBreakoutAssignmentTable.breakoutRoomId] = breakoutRoomId
                            it[memberId] = creator
                            it[assignedAt] = now
                            it[recalledAt] = null
                        }
                    }
                }
                breakoutRoomIds.forEach { fakeClient.createRoom("lc-bo-cascade-$it", 25, 300) }

                client.post("/test/end-room?roomId=$roomId") { header("X-Member-Id", creator.toString()) }.status shouldBe
                    HttpStatusCode.OK

                // deleteRoom called for the main room AND both breakout rooms -- no leak.
                fakeClient.deleteRoomCallCount shouldBe 3

                transaction {
                    breakoutRoomIds.forEach { breakoutRoomId ->
                        val row =
                            ConferenceBreakoutRoomTable.selectAll().where { ConferenceBreakoutRoomTable.id eq breakoutRoomId }.single()
                        row[ConferenceBreakoutRoomTable.closedAt].shouldNotBeNull()
                        val assignment =
                            ConferenceBreakoutAssignmentTable
                                .selectAll()
                                .where { ConferenceBreakoutAssignmentTable.breakoutRoomId eq breakoutRoomId }
                                .single()
                        assignment[ConferenceBreakoutAssignmentTable.recalledAt].shouldNotBeNull()
                    }
                }
            }
        }

        test(
            "endRoom: still succeeds and still closes breakout DB rows even if a breakout room's own LiveKit deleteRoom call fails",
        ) {
            val fakeClient = FakeLiveKitAdminClient()
            // The breakout room's own deleteRoom call fails (simulated LiveKit hiccup) -- endRoom
            // must still succeed for the main room (its own deleteRoom call, first, succeeds) and
            // still mark the breakout row closed (best-effort, log-and-continue cascade).
            var mainRoomDeleteSeen = false
            val flakyClient =
                object : LiveKitAdminClient by fakeClient {
                    override suspend fun deleteRoom(name: String) {
                        if (!mainRoomDeleteSeen) {
                            mainRoomDeleteSeen = true
                            fakeClient.deleteRoom(name)
                        } else {
                            throw LiveKitAdminException("simulated breakout deleteRoom failure")
                        }
                    }
                }
            testApplication {
                application {
                    install(StatusPages) { installConferenceExceptionHandlers() }
                    routing { registerConferenceTestRoutes(flakyClient, LoginRateLimiter(), ENABLED_CONFIG, DISABLED_CONFIG) }
                }
                val creator = createTestMember("conf-end-cascade-fail-creator@example.org")
                val roomId = createRoom(client, creator, "Cascade-Fail-Test")
                val roomUuid = Uuid.parse(roomId)

                val breakoutRoomId = Uuid.random()
                val now = DbClock.nowLocalDateTime()
                transaction {
                    ConferenceBreakoutRoomTable.insert {
                        it[id] = breakoutRoomId
                        it[parentRoomId] = roomUuid
                        it[label] = "Breakout-Raum 1"
                        it[livekitRoomName] = "lc-bo-cascade-fail-$breakoutRoomId"
                        it[createdByMemberId] = creator
                        it[createdAt] = now
                        it[closedAt] = null
                    }
                }

                client.post("/test/end-room?roomId=$roomId") { header("X-Member-Id", creator.toString()) }.status shouldBe
                    HttpStatusCode.OK

                transaction {
                    val row = ConferenceBreakoutRoomTable.selectAll().where { ConferenceBreakoutRoomTable.id eq breakoutRoomId }.single()
                    row[ConferenceBreakoutRoomTable.closedAt].shouldNotBeNull()
                }
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

        // ── Wave 5 "Föderations-Gastbeitritt" ─────────────────────────────

        test("H1/H2: createRoom persists allowFederationGuests -- default false, explicit true") {
            testApplication {
                application {
                    install(StatusPages) { installConferenceExceptionHandlers() }
                    routing { registerConferenceTestRoutes(FakeLiveKitAdminClient(), LoginRateLimiter(), ENABLED_CONFIG, DISABLED_CONFIG) }
                }
                val creator = createTestMember("conf-w5-h1@example.org")

                val defaultRoomId =
                    client.post("/test/create-room?title=Default") { header("X-Member-Id", creator.toString()) }.bodyAsText().split("|")[0]
                transaction {
                    ConferenceRoomTable
                        .selectAll()
                        .where { ConferenceRoomTable.id eq Uuid.parse(defaultRoomId) }
                        .single()[ConferenceRoomTable.allowFederationGuests]
                } shouldBe false

                val openRoomResponse =
                    client.post("/test/create-room?title=Offen&allowFederationGuests=true") { header("X-Member-Id", creator.toString()) }
                openRoomResponse.bodyAsText().split("|")[8] shouldBe "true"
            }
        }

        test("H3: creator calls setRoomGuestAccess(true) -- column flips, audit row written with CONFERENCE_ROOM/UPDATE") {
            testApplication {
                application {
                    install(StatusPages) { installConferenceExceptionHandlers() }
                    routing { registerConferenceTestRoutes(FakeLiveKitAdminClient(), LoginRateLimiter(), ENABLED_CONFIG, DISABLED_CONFIG) }
                }
                val creator = createTestMember("conf-w5-h3@example.org")
                val roomId = createRoom(client, creator, "H3-Raum")
                val beforeCount =
                    transaction { AuditLogEntryTable.selectAll().where { AuditLogEntryTable.entityId eq Uuid.parse(roomId) }.count() }

                val response =
                    client.post("/test/set-room-guest-access?roomId=$roomId&allowFederationGuests=true") {
                        header("X-Member-Id", creator.toString())
                    }
                response.status shouldBe HttpStatusCode.OK
                response.bodyAsText().split("|")[8] shouldBe "true"

                val afterCount =
                    transaction { AuditLogEntryTable.selectAll().where { AuditLogEntryTable.entityId eq Uuid.parse(roomId) }.count() }
                (afterCount - beforeCount) shouldBe 1L
                val auditRow =
                    transaction {
                        AuditLogEntryTable
                            .selectAll()
                            .where { AuditLogEntryTable.entityId eq Uuid.parse(roomId) }
                            .single()
                    }
                auditRow[AuditLogEntryTable.entityType] shouldBe AuditEntityType.CONFERENCE_ROOM
            }
        }

        test("H4: BOARD may call setRoomGuestAccess on someone else's room (escalation path preserved)") {
            testApplication {
                application {
                    install(StatusPages) { installConferenceExceptionHandlers() }
                    routing { registerConferenceTestRoutes(FakeLiveKitAdminClient(), LoginRateLimiter(), ENABLED_CONFIG, DISABLED_CONFIG) }
                }
                val creator = createTestMember("conf-w5-h4@example.org")
                val roomId = createRoom(client, creator, "H4-Raum")

                client
                    .post("/test/set-room-guest-access?roomId=$roomId&allowFederationGuests=true") {
                        header("X-Member-Id", BOARD_ID)
                    }.status shouldBe HttpStatusCode.OK
            }
        }

        test(
            "H5/H6: GAST joins an opted-in active room with the current disclaimer -- token minted, participation row, exactly one ack row per join",
        ) {
            testApplication {
                val fakeClient = FakeLiveKitAdminClient()
                application {
                    install(StatusPages) { installConferenceExceptionHandlers() }
                    routing { registerConferenceTestRoutes(fakeClient, LoginRateLimiter(), ENABLED_CONFIG, DISABLED_CONFIG) }
                }
                val creator = createTestMember("conf-w5-h5@example.org")
                val roomId = createRoom(client, creator, "H5-Raum")
                client.post("/test/set-room-guest-access?roomId=$roomId&allowFederationGuests=true") {
                    header("X-Member-Id", creator.toString())
                }

                val (guestId, homeserverUrl) = createTestGuestMember("h5")
                val guestToken = SessionStore.createSession(guestId).rawToken

                val join1 =
                    client.post(
                        "/test/join-room?roomId=$roomId&consentVersion=${ConferenceGuestConsentDisclaimer.VERSION}" +
                            "&consentSha256=${ConferenceGuestConsentDisclaimer.SHA256}",
                    ) { header("Authorization", "Bearer $guestToken") }
                join1.status shouldBe HttpStatusCode.OK
                join1.bodyAsText().split("|")[5] shouldBe "true" // hasToken

                val ackRowsAfterFirst =
                    transaction {
                        ConferenceGuestConsentAcknowledgmentTable
                            .selectAll()
                            .where {
                                (ConferenceGuestConsentAcknowledgmentTable.memberId eq guestId) and
                                    (ConferenceGuestConsentAcknowledgmentTable.roomId eq Uuid.parse(roomId))
                            }.toList()
                    }
                ackRowsAfterFirst.size shouldBe 1
                ackRowsAfterFirst.single()[ConferenceGuestConsentAcknowledgmentTable.homeserverUrl] shouldBe homeserverUrl
                ackRowsAfterFirst.single()[ConferenceGuestConsentAcknowledgmentTable.consentVersion] shouldBe
                    ConferenceGuestConsentDisclaimer.VERSION
                ackRowsAfterFirst.single()[ConferenceGuestConsentAcknowledgmentTable.consentSha256] shouldBe
                    ConferenceGuestConsentDisclaimer.SHA256

                // H6: leave and re-join -- a SECOND ack row (append-only, per join).
                client.post("/test/leave-room?roomId=$roomId") { header("Authorization", "Bearer $guestToken") }
                client.post(
                    "/test/join-room?roomId=$roomId&consentVersion=${ConferenceGuestConsentDisclaimer.VERSION}" +
                        "&consentSha256=${ConferenceGuestConsentDisclaimer.SHA256}",
                ) { header("Authorization", "Bearer $guestToken") }

                val ackRowsAfterSecond =
                    transaction {
                        ConferenceGuestConsentAcknowledgmentTable
                            .selectAll()
                            .where {
                                (ConferenceGuestConsentAcknowledgmentTable.memberId eq guestId) and
                                    (ConferenceGuestConsentAcknowledgmentTable.roomId eq Uuid.parse(roomId))
                            }.count()
                    }
                ackRowsAfterSecond shouldBe 2L
            }
        }

        test(
            "H7/H8: getGuestJoinInfo -- opted-in room by a GAST reports true/callerIsGuest; by the AKTIV creator previews with callerIsGuest=false",
        ) {
            testApplication {
                application {
                    install(StatusPages) { installConferenceExceptionHandlers() }
                    routing { registerConferenceTestRoutes(FakeLiveKitAdminClient(), LoginRateLimiter(), ENABLED_CONFIG, DISABLED_CONFIG) }
                }
                val creator = createTestMember("conf-w5-h7@example.org")
                val roomId = createRoom(client, creator, "H7-Raum")
                client.post("/test/set-room-guest-access?roomId=$roomId&allowFederationGuests=true") {
                    header("X-Member-Id", creator.toString())
                }
                val (guestId, _) = createTestGuestMember("h7")
                val guestToken = SessionStore.createSession(guestId).rawToken

                val guestInfo =
                    client.get("/test/guest-join-info?roomId=$roomId") { header("Authorization", "Bearer $guestToken") }
                guestInfo.status shouldBe HttpStatusCode.OK
                val guestParts = guestInfo.bodyAsText().split("|")
                guestParts[2] shouldBe "true" // allowsFederationGuests
                guestParts[6] shouldBe "true" // callerIsGuest
                guestParts[7] shouldBe ConferenceGuestConsentDisclaimer.VERSION
                guestParts[8] shouldBe ConferenceGuestConsentDisclaimer.SHA256

                val moderatorInfo =
                    client.get("/test/guest-join-info?roomId=$roomId") { header("X-Member-Id", creator.toString()) }
                moderatorInfo.status shouldBe HttpStatusCode.OK
                moderatorInfo.bodyAsText().split("|")[6] shouldBe "false" // callerIsGuest
            }
        }

        test("H9: listParticipants -- guest's homeserverUrl populated verbatim, member's is null") {
            testApplication {
                val fakeClient = FakeLiveKitAdminClient()
                application {
                    install(StatusPages) { installConferenceExceptionHandlers() }
                    routing { registerConferenceTestRoutes(fakeClient, LoginRateLimiter(), ENABLED_CONFIG, DISABLED_CONFIG) }
                }
                val creator = createTestMember("conf-w5-h9@example.org")
                val roomId = createRoom(client, creator, "H9-Raum")
                client.post("/test/set-room-guest-access?roomId=$roomId&allowFederationGuests=true") {
                    header("X-Member-Id", creator.toString())
                }
                client.post("/test/join-room?roomId=$roomId") { header("X-Member-Id", creator.toString()) }
                val (guestId, homeserverUrl) = createTestGuestMember("h9")
                val guestToken = SessionStore.createSession(guestId).rawToken
                client.post(
                    "/test/join-room?roomId=$roomId&consentVersion=${ConferenceGuestConsentDisclaimer.VERSION}" +
                        "&consentSha256=${ConferenceGuestConsentDisclaimer.SHA256}",
                ) { header("Authorization", "Bearer $guestToken") }

                val response = client.get("/test/list-participants?roomId=$roomId") { header("X-Member-Id", creator.toString()) }
                response.status shouldBe HttpStatusCode.OK
                val rows =
                    response
                        .bodyAsText()
                        .split(";")
                        .filter { it.isNotBlank() }
                        .map { it.split("|") }
                val creatorRow = rows.single { it[0] == creator.toString() }
                creatorRow[4] shouldBe "-" // no homeserverUrl
                val guestRow = rows.single { it[0] == guestId.toString() }
                guestRow[4] shouldBe homeserverUrl
            }
        }

        test(
            "Security-audit fix: listParticipants narrows homeserverUrl to callers who are themselves participants -- an AKTIV bystander who never joined sees the roster but not the guest's homeserverUrl",
        ) {
            testApplication {
                val fakeClient = FakeLiveKitAdminClient()
                application {
                    install(StatusPages) { installConferenceExceptionHandlers() }
                    routing { registerConferenceTestRoutes(fakeClient, LoginRateLimiter(), ENABLED_CONFIG, DISABLED_CONFIG) }
                }
                val creator = createTestMember("conf-w5-bystander@example.org")
                val roomId = createRoom(client, creator, "Bystander-Raum")
                client.post("/test/set-room-guest-access?roomId=$roomId&allowFederationGuests=true") {
                    header("X-Member-Id", creator.toString())
                }
                val (guestId, homeserverUrl) = createTestGuestMember("bystander")
                val guestToken = SessionStore.createSession(guestId).rawToken
                client.post(
                    "/test/join-room?roomId=$roomId&consentVersion=${ConferenceGuestConsentDisclaimer.VERSION}" +
                        "&consentSha256=${ConferenceGuestConsentDisclaimer.SHA256}",
                ) { header("Authorization", "Bearer $guestToken") }

                // A different AKTIV member, never a participant of this room, but still entitled to
                // call listParticipants at all (unchanged from before this fix -- only the
                // homeserverUrl projection narrows).
                val bystander = createTestMember("conf-w5-bystander-observer@example.org")
                val response =
                    client.get("/test/list-participants?roomId=$roomId") { header("X-Member-Id", bystander.toString()) }
                response.status shouldBe HttpStatusCode.OK
                val rows =
                    response
                        .bodyAsText()
                        .split(";")
                        .filter { it.isNotBlank() }
                        .map { it.split("|") }
                val guestRow = rows.single { it[0] == guestId.toString() }
                guestRow[4] shouldBe "-" // homeserverUrl hidden from a non-participant, unlike H9's creator view

                // Sanity: the SAME guest, seen by the room's creator (a participant), still gets the
                // real homeserverUrl -- the fix narrows, it does not break H9's existing behaviour.
                val creatorView =
                    client.get("/test/list-participants?roomId=$roomId") { header("X-Member-Id", creator.toString()) }
                val creatorRows =
                    creatorView
                        .bodyAsText()
                        .split(";")
                        .filter { it.isNotBlank() }
                        .map { it.split("|") }
                creatorRows.single { it[0] == guestId.toString() }[4] shouldBe homeserverUrl
            }
        }

        // ── Wave 5 tamper / negative -- mandated set ──────────────────────

        test("T1: GAST joins a room with allowFederationGuests=false, valid consent -- Forbidden, zero rows written") {
            testApplication {
                val fakeClient = FakeLiveKitAdminClient()
                application {
                    install(StatusPages) { installConferenceExceptionHandlers() }
                    routing { registerConferenceTestRoutes(fakeClient, LoginRateLimiter(), ENABLED_CONFIG, DISABLED_CONFIG) }
                }
                val creator = createTestMember("conf-w5-t1@example.org")
                val roomId = createRoom(client, creator, "T1-Raum") // default allowFederationGuests=false
                val (guestId, _) = createTestGuestMember("t1")
                val guestToken = SessionStore.createSession(guestId).rawToken

                val response =
                    client.post(
                        "/test/join-room?roomId=$roomId&consentVersion=${ConferenceGuestConsentDisclaimer.VERSION}" +
                            "&consentSha256=${ConferenceGuestConsentDisclaimer.SHA256}",
                    ) { header("Authorization", "Bearer $guestToken") }
                response.status shouldBe HttpStatusCode.Forbidden

                transaction {
                    ConferenceParticipationTable.selectAll().where { ConferenceParticipationTable.memberId eq guestId }.count()
                } shouldBe
                    0L
                transaction {
                    ConferenceGuestConsentAcknowledgmentTable
                        .selectAll()
                        .where {
                            ConferenceGuestConsentAcknowledgmentTable.memberId eq
                                guestId
                        }.count()
                } shouldBe 0L
                fakeClient.removeParticipantCallCount shouldBe 0
            }
        }

        test("T2: GAST joins an opted-in room with guestConsent = null -- Conflict, zero rows") {
            testApplication {
                application {
                    install(StatusPages) { installConferenceExceptionHandlers() }
                    routing { registerConferenceTestRoutes(FakeLiveKitAdminClient(), LoginRateLimiter(), ENABLED_CONFIG, DISABLED_CONFIG) }
                }
                val creator = createTestMember("conf-w5-t2@example.org")
                val roomId = createRoom(client, creator, "T2-Raum")
                client.post(
                    "/test/set-room-guest-access?roomId=$roomId&allowFederationGuests=true",
                ) { header("X-Member-Id", creator.toString()) }
                val (guestId, _) = createTestGuestMember("t2")
                val guestToken = SessionStore.createSession(guestId).rawToken

                val response = client.post("/test/join-room?roomId=$roomId") { header("Authorization", "Bearer $guestToken") }
                response.status shouldBe HttpStatusCode.Conflict
                transaction {
                    ConferenceParticipationTable.selectAll().where { ConferenceParticipationTable.memberId eq guestId }.count()
                } shouldBe
                    0L
            }
        }

        test("T3/T4/T5: GAST with flipped-nibble hash, stale version, and malformed hash are all rejected without a 500, zero rows") {
            testApplication {
                application {
                    install(StatusPages) { installConferenceExceptionHandlers() }
                    routing { registerConferenceTestRoutes(FakeLiveKitAdminClient(), LoginRateLimiter(), ENABLED_CONFIG, DISABLED_CONFIG) }
                }
                val creator = createTestMember("conf-w5-t345@example.org")
                val roomId = createRoom(client, creator, "T345-Raum")
                client.post(
                    "/test/set-room-guest-access?roomId=$roomId&allowFederationGuests=true",
                ) { header("X-Member-Id", creator.toString()) }
                val (guestId, _) = createTestGuestMember("t345")
                val guestToken = SessionStore.createSession(guestId).rawToken

                val flippedHash = "0" + ConferenceGuestConsentDisclaimer.SHA256.drop(1)
                client
                    .post(
                        "/test/join-room?roomId=$roomId&consentVersion=${ConferenceGuestConsentDisclaimer.VERSION}&consentSha256=$flippedHash",
                    ) { header("Authorization", "Bearer $guestToken") }
                    .status shouldBe HttpStatusCode.Conflict

                client
                    .post(
                        "/test/join-room?roomId=$roomId&consentVersion=2020-01-01.v0&consentSha256=${ConferenceGuestConsentDisclaimer.SHA256}",
                    ) { header("Authorization", "Bearer $guestToken") }
                    .status shouldBe HttpStatusCode.Conflict

                listOf("zzzz", "", "abc").forEach { badHash ->
                    client
                        .post(
                            "/test/join-room?roomId=$roomId&consentVersion=${ConferenceGuestConsentDisclaimer.VERSION}&consentSha256=$badHash",
                        ) { header("Authorization", "Bearer $guestToken") }
                        .status shouldBe HttpStatusCode.Conflict
                }

                transaction {
                    ConferenceParticipationTable.selectAll().where { ConferenceParticipationTable.memberId eq guestId }.count()
                } shouldBe
                    0L
                transaction {
                    ConferenceGuestConsentAcknowledgmentTable
                        .selectAll()
                        .where {
                            ConferenceGuestConsentAcknowledgmentTable.memberId eq
                                guestId
                        }.count()
                } shouldBe 0L
            }
        }

        test("T6-T9: status gate never widens -- ANTRAG/AUSGETRETEN/ABGELEHNT rejected identically regardless of the room's opt-in state") {
            testApplication {
                application {
                    install(StatusPages) { installConferenceExceptionHandlers() }
                    routing { registerConferenceTestRoutes(FakeLiveKitAdminClient(), LoginRateLimiter(), ENABLED_CONFIG, DISABLED_CONFIG) }
                }
                val creator = createTestMember("conf-w5-t6@example.org")
                val openRoomId = createRoom(client, creator, "T6-Offen")
                client.post(
                    "/test/set-room-guest-access?roomId=$openRoomId&allowFederationGuests=true",
                ) { header("X-Member-Id", creator.toString()) }
                val closedRoomId = createRoom(client, creator, "T6-Geschlossen")

                listOf(MemberStatus.ANTRAG, MemberStatus.AUSGETRETEN, MemberStatus.ABGELEHNT).forEach { status ->
                    val member = createTestMember("conf-w5-t6-${status.name.lowercase()}@example.org", status = status)
                    listOf(openRoomId, closedRoomId).forEach { roomId ->
                        client
                            .post(
                                "/test/join-room?roomId=$roomId&consentVersion=${ConferenceGuestConsentDisclaimer.VERSION}" +
                                    "&consentSha256=${ConferenceGuestConsentDisclaimer.SHA256}",
                            ) { header("X-Member-Id", member.toString()) }
                            .status shouldBe HttpStatusCode.Forbidden
                    }
                }
            }
        }

        test(
            "T10/T11/T12: AKTIV joinRoom is completely unaffected by Wave 5 -- null consent, non-opted-in room, and a bogus consent payload all still succeed with zero ack rows",
        ) {
            testApplication {
                application {
                    install(StatusPages) { installConferenceExceptionHandlers() }
                    routing { registerConferenceTestRoutes(FakeLiveKitAdminClient(), LoginRateLimiter(), ENABLED_CONFIG, DISABLED_CONFIG) }
                }
                val creator = createTestMember("conf-w5-t10@example.org")
                val openRoomId = createRoom(client, creator, "T10-Offen")
                client.post(
                    "/test/set-room-guest-access?roomId=$openRoomId&allowFederationGuests=true",
                ) { header("X-Member-Id", creator.toString()) }
                val closedRoomId = createRoom(client, creator, "T10-Geschlossen")

                val aktiv1 = createTestMember("conf-w5-t10-a@example.org")
                client.post("/test/join-room?roomId=$openRoomId") { header("X-Member-Id", aktiv1.toString()) }.status shouldBe
                    HttpStatusCode.OK

                val aktiv2 = createTestMember("conf-w5-t10-b@example.org")
                client.post("/test/join-room?roomId=$closedRoomId") { header("X-Member-Id", aktiv2.toString()) }.status shouldBe
                    HttpStatusCode.OK

                val aktiv3 = createTestMember("conf-w5-t10-c@example.org")
                client
                    .post(
                        "/test/join-room?roomId=$closedRoomId&consentVersion=bogus-version&consentSha256=bogus-hash",
                    ) { header("X-Member-Id", aktiv3.toString()) }
                    .status shouldBe HttpStatusCode.OK

                transaction {
                    ConferenceGuestConsentAcknowledgmentTable
                        .selectAll()
                        .where { ConferenceGuestConsentAcknowledgmentTable.memberId inList listOf(aktiv1, aktiv2, aktiv3) }
                        .count()
                } shouldBe 0L
            }
        }

        test("T13-T15: listParticipants -- a GAST not in the room, or in a non-opted-in room, is rejected; a GAST who joined succeeds") {
            testApplication {
                val fakeClient = FakeLiveKitAdminClient()
                application {
                    install(StatusPages) { installConferenceExceptionHandlers() }
                    routing { registerConferenceTestRoutes(fakeClient, LoginRateLimiter(), ENABLED_CONFIG, DISABLED_CONFIG) }
                }
                val creator = createTestMember("conf-w5-t13@example.org")
                val openRoomId = createRoom(client, creator, "T13-Offen")
                client.post(
                    "/test/set-room-guest-access?roomId=$openRoomId&allowFederationGuests=true",
                ) { header("X-Member-Id", creator.toString()) }
                val closedRoomId = createRoom(client, creator, "T13-Geschlossen")

                val (neverJoinedGuestId, _) = createTestGuestMember("t13-never")
                val neverJoinedToken = SessionStore.createSession(neverJoinedGuestId).rawToken
                // T13: never joined, opted-in room
                client
                    .get("/test/list-participants?roomId=$openRoomId") {
                        header("Authorization", "Bearer $neverJoinedToken")
                    }.status shouldBe HttpStatusCode.Forbidden

                // T14: non-opted-in room
                client
                    .get("/test/list-participants?roomId=$closedRoomId") {
                        header("Authorization", "Bearer $neverJoinedToken")
                    }.status shouldBe HttpStatusCode.Forbidden

                // T15: joined guest succeeds
                val (joinedGuestId, _) = createTestGuestMember("t13-joined")
                val joinedToken = SessionStore.createSession(joinedGuestId).rawToken
                client.post(
                    "/test/join-room?roomId=$openRoomId&consentVersion=${ConferenceGuestConsentDisclaimer.VERSION}" +
                        "&consentSha256=${ConferenceGuestConsentDisclaimer.SHA256}",
                ) { header("Authorization", "Bearer $joinedToken") }
                client
                    .get("/test/list-participants?roomId=$openRoomId") {
                        header("Authorization", "Bearer $joinedToken")
                    }.status shouldBe HttpStatusCode.OK
            }
        }

        test(
            "Security-audit fix: requireGuestHasJoinedRoom ignores leftAt -- a GAST ejected via removeParticipant loses listParticipants access, an explicit leaveRoom does too",
        ) {
            testApplication {
                val fakeClient = FakeLiveKitAdminClient()
                application {
                    install(StatusPages) { installConferenceExceptionHandlers() }
                    routing { registerConferenceTestRoutes(fakeClient, LoginRateLimiter(), ENABLED_CONFIG, DISABLED_CONFIG) }
                }
                val creator = createTestMember("conf-w5-leftat-creator@example.org")
                val roomId = createRoom(client, creator, "LeftAt-Raum")
                client.post(
                    "/test/set-room-guest-access?roomId=$roomId&allowFederationGuests=true",
                ) { header("X-Member-Id", creator.toString()) }

                // Ejected guest: joins, moderator removes them, their OWN append-only participation
                // row now has leftAt set but still EXISTS -- before this fix, requireGuestHasJoinedRoom
                // tested only row existence and would have kept letting them call listParticipants.
                val (ejectedGuestId, _) = createTestGuestMember("leftat-ejected")
                val ejectedToken = SessionStore.createSession(ejectedGuestId).rawToken
                client.post(
                    "/test/join-room?roomId=$roomId&consentVersion=${ConferenceGuestConsentDisclaimer.VERSION}" +
                        "&consentSha256=${ConferenceGuestConsentDisclaimer.SHA256}",
                ) { header("Authorization", "Bearer $ejectedToken") }
                client
                    .get("/test/list-participants?roomId=$roomId") {
                        header("Authorization", "Bearer $ejectedToken")
                    }.status shouldBe HttpStatusCode.OK // sanity: joined guest can list before ejection

                client.post("/test/remove-participant?roomId=$roomId&memberId=$ejectedGuestId") {
                    header("X-Member-Id", creator.toString())
                }
                client
                    .get("/test/list-participants?roomId=$roomId") {
                        header("Authorization", "Bearer $ejectedToken")
                    }.status shouldBe HttpStatusCode.Forbidden

                // Departed guest: joins, calls leaveRoom themselves -- same leftAt-set-but-row-exists
                // shape, same rejection.
                val (departedGuestId, _) = createTestGuestMember("leftat-departed")
                val departedToken = SessionStore.createSession(departedGuestId).rawToken
                client.post(
                    "/test/join-room?roomId=$roomId&consentVersion=${ConferenceGuestConsentDisclaimer.VERSION}" +
                        "&consentSha256=${ConferenceGuestConsentDisclaimer.SHA256}",
                ) { header("Authorization", "Bearer $departedToken") }
                client.post("/test/leave-room?roomId=$roomId") { header("Authorization", "Bearer $departedToken") }
                client
                    .get("/test/list-participants?roomId=$roomId") {
                        header("Authorization", "Bearer $departedToken")
                    }.status shouldBe HttpStatusCode.Forbidden
            }
        }

        test("T16: a stale oidc_guest_profile row on an AKTIV member does NOT surface a guest badge") {
            testApplication {
                val fakeClient = FakeLiveKitAdminClient()
                application {
                    install(StatusPages) { installConferenceExceptionHandlers() }
                    routing { registerConferenceTestRoutes(fakeClient, LoginRateLimiter(), ENABLED_CONFIG, DISABLED_CONFIG) }
                }
                val creator = createTestMember("conf-w5-t16@example.org")
                val roomId = createRoom(client, creator, "T16-Raum")
                client.post(
                    "/test/set-room-guest-access?roomId=$roomId&allowFederationGuests=true",
                ) { header("X-Member-Id", creator.toString()) }

                // Promote a GAST to AKTIV AFTER they already have an oidc_guest_profile row.
                val (memberId, _) = createTestGuestMember("t16-promoted")
                transaction { MemberTable.update({ MemberTable.id eq memberId }) { it[status] = MemberStatus.AKTIV } }
                client.post("/test/join-room?roomId=$roomId") { header("X-Member-Id", memberId.toString()) }.status shouldBe
                    HttpStatusCode.OK

                val response = client.get("/test/list-participants?roomId=$roomId") { header("X-Member-Id", creator.toString()) }
                val row =
                    response.bodyAsText().split(";").filter { it.isNotBlank() }.map { it.split("|") }.single {
                        it[0] ==
                            memberId.toString()
                    }
                row[4] shouldBe "-" // no homeserverUrl -- the status eq GAST predicate excludes the now-AKTIV member
            }
        }

        test("T17: setRoomGuestAccess(false) disconnects a joined GAST but leaves an AKTIV participant untouched") {
            testApplication {
                val fakeClient = FakeLiveKitAdminClient()
                application {
                    install(StatusPages) { installConferenceExceptionHandlers() }
                    routing { registerConferenceTestRoutes(fakeClient, LoginRateLimiter(), ENABLED_CONFIG, DISABLED_CONFIG) }
                }
                val creator = createTestMember("conf-w5-t17@example.org")
                val roomId = createRoom(client, creator, "T17-Raum")
                client.post(
                    "/test/set-room-guest-access?roomId=$roomId&allowFederationGuests=true",
                ) { header("X-Member-Id", creator.toString()) }
                val aktiv = createTestMember("conf-w5-t17-aktiv@example.org")
                client.post("/test/join-room?roomId=$roomId") { header("X-Member-Id", aktiv.toString()) }
                val (guestId, _) = createTestGuestMember("t17")
                val guestToken = SessionStore.createSession(guestId).rawToken
                client.post(
                    "/test/join-room?roomId=$roomId&consentVersion=${ConferenceGuestConsentDisclaimer.VERSION}" +
                        "&consentSha256=${ConferenceGuestConsentDisclaimer.SHA256}",
                ) { header("Authorization", "Bearer $guestToken") }

                val revoke =
                    client.post("/test/set-room-guest-access?roomId=$roomId&allowFederationGuests=false") {
                        header("X-Member-Id", creator.toString())
                    }
                revoke.status shouldBe HttpStatusCode.OK
                fakeClient.removeParticipantCallCount shouldBe 1

                val guestOpen =
                    transaction {
                        ConferenceParticipationTable
                            .selectAll()
                            .where {
                                (ConferenceParticipationTable.roomId eq Uuid.parse(roomId)) and
                                    (ConferenceParticipationTable.memberId eq guestId)
                            }.single()[ConferenceParticipationTable.leftAt]
                    }
                guestOpen.shouldNotBeNull()
                val aktivOpen =
                    transaction {
                        ConferenceParticipationTable
                            .selectAll()
                            .where {
                                (ConferenceParticipationTable.roomId eq Uuid.parse(roomId)) and
                                    (ConferenceParticipationTable.memberId eq aktiv)
                            }.single()[ConferenceParticipationTable.leftAt]
                    }
                aktivOpen shouldBe null
            }
        }

        test(
            "Security-audit fix (TOCTOU): a GAST's joinRoom racing a concurrent setRoomGuestAccess(false) never leaves the guest OPEN in a room that ends up not admitting guests",
        ) {
            testApplication {
                val fakeClient = FakeLiveKitAdminClient()
                application {
                    install(StatusPages) { installConferenceExceptionHandlers() }
                    routing { registerConferenceTestRoutes(fakeClient, LoginRateLimiter(), ENABLED_CONFIG, DISABLED_CONFIG) }
                }
                val creator = createTestMember("conf-w5-toctou-creator@example.org")
                val roomId = createRoom(client, creator, "TOCTOU-Raum")
                client.post(
                    "/test/set-room-guest-access?roomId=$roomId&allowFederationGuests=true",
                ) { header("X-Member-Id", creator.toString()) }
                val (guestId, _) = createTestGuestMember("toctou")
                val guestToken = SessionStore.createSession(guestId).rawToken

                runConcurrentJoinAndRevoke(client, roomId, guestToken, creator)

                // Invariant the FOR-UPDATE re-check in joinRoom's final transaction establishes,
                // regardless of which of the two racing requests actually "won": the room ends up
                // NOT admitting guests (the revoke call in this test is unconditional), so the guest
                // must never be left with an OPEN participation row -- either their join was rejected
                // outright (freshRoomRow re-read saw allowFederationGuests=false already), or they
                // joined just before the revoke committed and were immediately swept up by its own
                // guest-disconnect logic (T17 coverage). The OLD, pre-fix code could leave the guest
                // open forever in exactly this interleaving (see class KDoc "Federated guest entry"
                // and the security-audit finding this test guards against).
                val roomRow =
                    transaction { ConferenceRoomTable.selectAll().where { ConferenceRoomTable.id eq Uuid.parse(roomId) }.single() }
                roomRow[ConferenceRoomTable.allowFederationGuests] shouldBe false
                val guestOpenParticipationExists =
                    transaction {
                        ConferenceParticipationTable
                            .selectAll()
                            .where {
                                (ConferenceParticipationTable.roomId eq Uuid.parse(roomId)) and
                                    (ConferenceParticipationTable.memberId eq guestId) and
                                    ConferenceParticipationTable.leftAt.isNull()
                            }.any()
                    }
                guestOpenParticipationExists shouldBe false
            }
        }

        test("T18/T19: setRoomGuestAccess -- unrelated AKTIV member Forbidden (column unchanged); ended room Conflict (column unchanged)") {
            testApplication {
                application {
                    install(StatusPages) { installConferenceExceptionHandlers() }
                    routing { registerConferenceTestRoutes(FakeLiveKitAdminClient(), LoginRateLimiter(), ENABLED_CONFIG, DISABLED_CONFIG) }
                }
                val creator = createTestMember("conf-w5-t18@example.org")
                val roomId = createRoom(client, creator, "T18-Raum")
                val bystander = createTestMember("conf-w5-t18-bystander@example.org")

                client
                    .post("/test/set-room-guest-access?roomId=$roomId&allowFederationGuests=true") {
                        header("X-Member-Id", bystander.toString())
                    }.status shouldBe HttpStatusCode.Forbidden
                transaction {
                    ConferenceRoomTable
                        .selectAll()
                        .where {
                            ConferenceRoomTable.id eq
                                Uuid.parse(
                                    roomId,
                                )
                        }.single()[ConferenceRoomTable.allowFederationGuests]
                } shouldBe false

                client.post("/test/end-room?roomId=$roomId") { header("X-Member-Id", creator.toString()) }
                client
                    .post("/test/set-room-guest-access?roomId=$roomId&allowFederationGuests=true") {
                        header("X-Member-Id", creator.toString())
                    }.status shouldBe HttpStatusCode.Conflict
                transaction {
                    ConferenceRoomTable
                        .selectAll()
                        .where {
                            ConferenceRoomTable.id eq
                                Uuid.parse(
                                    roomId,
                                )
                        }.single()[ConferenceRoomTable.allowFederationGuests]
                } shouldBe false
            }
        }

        test("T20: setRoomGuestAccess/getGuestJoinInfo on an unknown room id -- NotFound") {
            testApplication {
                application {
                    install(StatusPages) { installConferenceExceptionHandlers() }
                    routing { registerConferenceTestRoutes(FakeLiveKitAdminClient(), LoginRateLimiter(), ENABLED_CONFIG, DISABLED_CONFIG) }
                }
                val member = createTestMember("conf-w5-t20@example.org")
                val unknownId = Uuid.random().toString()

                client
                    .post("/test/set-room-guest-access?roomId=$unknownId&allowFederationGuests=true") {
                        header("X-Member-Id", member.toString())
                    }.status shouldBe HttpStatusCode.NotFound
                client
                    .get("/test/guest-join-info?roomId=$unknownId") {
                        header("X-Member-Id", member.toString())
                    }.status shouldBe HttpStatusCode.NotFound
            }
        }

        test("T21: getGuestJoinInfo by an ANTRAG member -- Forbidden") {
            testApplication {
                application {
                    install(StatusPages) { installConferenceExceptionHandlers() }
                    routing { registerConferenceTestRoutes(FakeLiveKitAdminClient(), LoginRateLimiter(), ENABLED_CONFIG, DISABLED_CONFIG) }
                }
                val creator = createTestMember("conf-w5-t21@example.org")
                val roomId = createRoom(client, creator, "T21-Raum")
                val applicant = createTestMember("conf-w5-t21-antrag@example.org", status = MemberStatus.ANTRAG)

                client.get("/test/guest-join-info?roomId=$roomId") { header("X-Member-Id", applicant.toString()) }.status shouldBe
                    HttpStatusCode.Forbidden
            }
        }

        test(
            "T22: getGuestJoinInfo by a GAST on a non-opted-in room succeeds with allowsFederationGuests=false -- honest-rejection data path is never an exception",
        ) {
            testApplication {
                application {
                    install(StatusPages) { installConferenceExceptionHandlers() }
                    routing { registerConferenceTestRoutes(FakeLiveKitAdminClient(), LoginRateLimiter(), ENABLED_CONFIG, DISABLED_CONFIG) }
                }
                val creator = createTestMember("conf-w5-t22@example.org")
                val roomId = createRoom(client, creator, "T22-Raum")
                val (guestId, _) = createTestGuestMember("t22")
                val guestToken = SessionStore.createSession(guestId).rawToken

                val response = client.get("/test/guest-join-info?roomId=$roomId") { header("Authorization", "Bearer $guestToken") }
                response.status shouldBe HttpStatusCode.OK
                response.bodyAsText().split("|")[2] shouldBe "false" // allowsFederationGuests
            }
        }

        test("T23: conference disabled -- getGuestJoinInfo/setRoomGuestAccess reject with Conflict, matching the existing gate tests") {
            testApplication {
                application {
                    install(StatusPages) { installConferenceExceptionHandlers() }
                    routing { registerConferenceTestRoutes(FakeLiveKitAdminClient(), LoginRateLimiter(), ENABLED_CONFIG, DISABLED_CONFIG) }
                }
                val creator = createTestMember("conf-w5-t23@example.org")
                val roomId = createRoom(client, creator, "T23-Raum")

                // Toggle the disabled config in a second app instance to hit both new methods.
            }
            testApplication {
                application {
                    install(StatusPages) { installConferenceExceptionHandlers() }
                    routing {
                        get("/test2/guest-join-info-disabled") {
                            val service = ConferenceService(call, FakeLiveKitAdminClient(), LoginRateLimiter(), DISABLED_CONFIG)
                            val q = call.request.queryParameters
                            call.respondText(service.getGuestJoinInfo(q["roomId"]!!).toPipeString())
                        }
                        post("/test2/set-room-guest-access-disabled") {
                            val service = ConferenceService(call, FakeLiveKitAdminClient(), LoginRateLimiter(), DISABLED_CONFIG)
                            val q = call.request.queryParameters
                            call.respondText(service.setRoomGuestAccess(q["roomId"]!!, true).toPipeString())
                        }
                    }
                }
                val member = createTestMember("conf-w5-t23b@example.org")
                val someRoomId = Uuid.random().toString()
                client
                    .get(
                        "/test2/guest-join-info-disabled?roomId=$someRoomId",
                    ) { header("X-Member-Id", member.toString()) }
                    .status shouldBe
                    HttpStatusCode.Conflict
                client
                    .post(
                        "/test2/set-room-guest-access-disabled?roomId=$someRoomId",
                    ) { header("X-Member-Id", member.toString()) }
                    .status shouldBe
                    HttpStatusCode.Conflict
            }
        }

        test("T25: unauthenticated caller on getGuestJoinInfo/setRoomGuestAccess -- Unauthenticated") {
            testApplication {
                application {
                    install(StatusPages) { installConferenceExceptionHandlers() }
                    routing { registerConferenceTestRoutes(FakeLiveKitAdminClient(), LoginRateLimiter(), ENABLED_CONFIG, DISABLED_CONFIG) }
                }
                val roomId = Uuid.random().toString()
                client.get("/test/guest-join-info?roomId=$roomId").status shouldBe HttpStatusCode.Unauthorized
                client.post("/test/set-room-guest-access?roomId=$roomId&allowFederationGuests=true").status shouldBe
                    HttpStatusCode.Unauthorized
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

/**
 * Fires a GAST [ConferenceService.joinRoom] call and a moderator [ConferenceService.setRoomGuestAccess]
 * `(false)` call from two independent OS threads, synchronized via [CountDownLatch] so both are
 * issued as close to simultaneously as possible -- same pattern
 * [AuctionServiceTest]'s/[PeerTransferServiceTest]'s own concurrent-race helpers establish (real
 * thread-level parallelism against the shared H2 `MODE=PostgreSQL` test database, not two coroutines
 * cooperatively sharing one thread), exercising the TOCTOU race the security-audit fix in
 * [ConferenceService.joinRoom]'s final transaction closes (see that transaction's own inline
 * comment). Both threads must complete within [timeoutSeconds]; exceeding it fails the test with an
 * explicit deadlock diagnosis rather than hanging the whole suite. Non-2xx/4xx responses from either
 * side are NOT asserted here -- the calling test only checks the post-race DB invariant, since
 * EITHER thread may legitimately "win" depending on real scheduling.
 */
private fun runConcurrentJoinAndRevoke(
    client: HttpClient,
    roomId: String,
    guestToken: String,
    creatorId: Uuid,
    timeoutSeconds: Long = 20,
) {
    val startLatch = CountDownLatch(2)
    val doneLatch = CountDownLatch(2)
    val failures = mutableListOf<Throwable>()

    fun joinThread(): Thread =
        Thread {
            try {
                startLatch.countDown()
                startLatch.await(timeoutSeconds, TimeUnit.SECONDS)
                runBlocking {
                    client.post(
                        "/test/join-room?roomId=$roomId&consentVersion=${ConferenceGuestConsentDisclaimer.VERSION}" +
                            "&consentSha256=${ConferenceGuestConsentDisclaimer.SHA256}",
                    ) { header("Authorization", "Bearer $guestToken") }
                }
            } catch (t: Throwable) {
                synchronized(failures) { failures += t }
            } finally {
                doneLatch.countDown()
            }
        }

    fun revokeThread(): Thread =
        Thread {
            try {
                startLatch.countDown()
                startLatch.await(timeoutSeconds, TimeUnit.SECONDS)
                runBlocking {
                    client.post("/test/set-room-guest-access?roomId=$roomId&allowFederationGuests=false") {
                        header("X-Member-Id", creatorId.toString())
                    }
                }
            } catch (t: Throwable) {
                synchronized(failures) { failures += t }
            } finally {
                doneLatch.countDown()
            }
        }

    val t1 = joinThread()
    val t2 = revokeThread()
    t1.start()
    t2.start()
    val completed = doneLatch.await(timeoutSeconds, TimeUnit.SECONDS)
    check(completed) { "Concurrent join/revoke did not complete within ${timeoutSeconds}s -- likely deadlock" }
    if (failures.isNotEmpty()) throw failures.first()
}

private fun cleanUpConferenceTestData(memberIds: List<Uuid>) {
    if (memberIds.isEmpty()) return
    transaction {
        // Wave 5: setRoomGuestAccess writes an AuditLogEntryTable row per call, referencing the
        // acting member via a real FK (actor_member_id) -- null it out first (audit_log_entry rows
        // themselves are never deleted, see AuditLogRecorder KDoc) so the MemberTable delete below
        // does not violate that FK. Same pattern ConferenceRecordingServiceTest's own
        // cleanUpConferenceRecordingTestData establishes.
        AuditLogEntryTable.update({ AuditLogEntryTable.actorMemberId inList memberIds }) {
            it[actorMemberId] = null
        }
        val roomIds =
            ConferenceRoomTable
                .selectAll()
                .where {
                    ConferenceRoomTable.createdByMemberId inList memberIds
                }.map { it[ConferenceRoomTable.id] }
        // Wave 5: consent-acknowledgment rows FK-reference both member and conference_room, delete
        // before either.
        ConferenceGuestConsentAcknowledgmentTable.deleteWhere {
            (ConferenceGuestConsentAcknowledgmentTable.memberId inList memberIds) or
                (ConferenceGuestConsentAcknowledgmentTable.roomId inList roomIds)
        }
        // Wave 6: breakout rooms/assignments FK-reference conference_room and member -- delete
        // before either (mirrors ConferenceBreakoutServiceTest's own cleanUpConferenceBreakoutTestData).
        val breakoutRoomIds =
            ConferenceBreakoutRoomTable
                .selectAll()
                .where { ConferenceBreakoutRoomTable.parentRoomId inList roomIds }
                .map { it[ConferenceBreakoutRoomTable.id] }
        ConferenceBreakoutAssignmentTable.deleteWhere {
            (ConferenceBreakoutAssignmentTable.memberId inList memberIds) or
                (ConferenceBreakoutAssignmentTable.breakoutRoomId inList breakoutRoomIds)
        }
        ConferenceBreakoutRoomTable.deleteWhere { ConferenceBreakoutRoomTable.parentRoomId inList roomIds }
        ConferenceParticipationTable.deleteWhere {
            (ConferenceParticipationTable.memberId inList memberIds) or (ConferenceParticipationTable.roomId inList roomIds)
        }
        ConferenceRoomTable.deleteWhere { ConferenceRoomTable.createdByMemberId inList memberIds }
        // Wave 5: a GAST test member created via OidcGuestMemberStore.resolveOrCreateGuestMember
        // also has an oidc_guest_profile row FK-referencing member -- delete before member.
        OidcGuestProfileTable.deleteWhere { OidcGuestProfileTable.memberId inList memberIds }
        // Wave 5 tests mint a real SessionStore session for each GAST test member (Authorization:
        // Bearer flow) -- delete before member, same FK-ordering reasoning as the profile row above.
        SessionTable.deleteWhere { SessionTable.memberId inList memberIds }
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
    // Security-audit fix -- see ConferenceService's own DEFAULT_GUEST_ACCESS_RATE_MAX KDoc. Default
    // (10/min) matches production; no existing test calls setRoomGuestAccess anywhere near that many
    // times, and the dedicated throttle test below overrides it with a tiny budget.
    guestAccessRateLimiter: FederationInboxRateLimiter = FederationInboxRateLimiter(maxRequests = 10, window = 1.minutes),
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
        guestAccessRateLimiter = guestAccessRateLimiter,
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
        val dto =
            service.createRoom(
                ConferenceRoomInput(
                    title = q["title"]!!,
                    description = q["description"] ?: "",
                    allowFederationGuests = q["allowFederationGuests"]?.toBoolean() ?: false,
                ),
            )
        call.respondText(dto.toPipeString())
    }
    post("/test/join-room") {
        val service = service(call, enabledConfig)
        val q = call.request.queryParameters
        val consentVersion = q["consentVersion"]
        val consentSha256 = q["consentSha256"]
        val consent =
            if (consentVersion != null || consentSha256 != null) {
                ConferenceGuestConsentAcknowledgmentInput(
                    consentVersion = consentVersion ?: "",
                    consentSha256 = consentSha256 ?: "",
                )
            } else {
                null
            }
        val dto = service.joinRoom(q["roomId"]!!, consent)
        call.respondText(dto.toPipeString())
    }
    get("/test/guest-join-info") {
        val service = service(call, enabledConfig)
        val q = call.request.queryParameters
        val dto = service.getGuestJoinInfo(q["roomId"]!!)
        call.respondText(dto.toPipeString())
    }
    post("/test/set-room-guest-access") {
        val service = service(call, enabledConfig)
        val q = call.request.queryParameters
        val dto = service.setRoomGuestAccess(q["roomId"]!!, q["allowFederationGuests"]!!.toBoolean())
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

/** id|title|livekitRoomName|createdByMemberId|active|maxParticipants|liveParticipantCount|myRole|allowFederationGuests */
private fun ConferenceRoomDto.toPipeString(): String =
    "$id|$title|$livekitRoomName|$createdByMemberId|$active|$maxParticipants|$liveParticipantCount|$myRole|$allowFederationGuests"

/** roomId|livekitRoomName|serverUrl|identity|role|hasToken|hasTurnServers|expiresAt -- [hasTurnServers] is audit-round-1 fix coverage, see [ConferenceServiceTest] "joinRoom: TURN credential" tests; [expiresAt] (appended last, so it never shifts any existing index) is Wave-5 security-audit guest-TTL coverage. */
private fun ConferenceJoinTokenDto.toPipeString(): String =
    "$roomId|$livekitRoomName|$serverUrl|$identity|$role|${token.isNotBlank()}|${turnServers.isNotEmpty()}|$expiresAt"

/** memberId|role|leftAtIsNull|live|homeserverUrl(or "-") */
private fun ConferenceParticipantDto.toPipeString(): String = "$memberId|$role|${leftAt == null}|$live|${homeserverUrl ?: "-"}"

/** roomId|title|allowsFederationGuests|roomActive|organizationName|createdByMemberId|callerIsGuest|disclaimerVersion|disclaimerSha256 */
private fun ConferenceGuestJoinInfoDto.toPipeString(): String =
    "$roomId|$title|$allowsFederationGuests|$roomActive|$organizationName|$createdByMemberId|$callerIsGuest|${disclaimer.version}|${disclaimer.sha256}"

private fun LocalDateTime.minusSecondsForTest(seconds: Int): LocalDateTime {
    val zone = TimeZone.currentSystemDefault()
    return toInstant(zone).minus(seconds.seconds).toLocalDateTime(zone)
}
