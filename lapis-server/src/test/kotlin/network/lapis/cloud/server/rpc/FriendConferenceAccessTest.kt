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
import network.lapis.cloud.shared.domain.ConferenceGuestConsentAcknowledgmentInput
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.rpc.BadRequestException
import network.lapis.cloud.shared.rpc.ConflictException
import network.lapis.cloud.shared.rpc.ForbiddenException
import network.lapis.cloud.shared.rpc.NotFoundException
import network.lapis.cloud.shared.rpc.UnauthenticatedException
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import kotlin.time.Duration.Companion.minutes
import kotlin.uuid.Uuid

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

/**
 * Exercises [MemberStatus.FRIEND]'s conference-domain access -- the ONE capability the wave
 * actually grants a self-registered, unverified account. Mirrors [ConferenceServiceTest]'s house
 * style (own throwaway routes + a private in-file [FakeFriendConferenceLiveKitAdminClient], not a
 * shared fixture -- [ConferenceServiceTest]'s own fake is file-private). [afterSpec] hard-deletes
 * every row this file created.
 */
class FriendConferenceAccessTest :
    FunSpec({
        val createdMemberIds = mutableListOf<Uuid>()
        val createdRoomIds = mutableListOf<Uuid>()

        beforeSpec {
            DatabaseConfig.connect()
            DevSeedData.seedIfEmpty(force = true)
        }

        afterSpec {
            transaction {
                // setRoomGuestAccess writes an AuditLogEntryTable row per call, referencing the
                // acting member via a real FK -- null it out first (rows are never deleted, see
                // AuditLogRecorder KDoc), same pattern ConferenceServiceTest's own
                // cleanUpConferenceTestData establishes.
                network.lapis.cloud.server.db.generated.AuditLogEntryTable.update({
                    network.lapis.cloud.server.db.generated.AuditLogEntryTable.actorMemberId inList createdMemberIds
                }) { it[actorMemberId] = null }
                network.lapis.cloud.server.db.generated.ConferenceGuestConsentAcknowledgmentTable.deleteWhere {
                    (network.lapis.cloud.server.db.generated.ConferenceGuestConsentAcknowledgmentTable.roomId inList createdRoomIds) or
                        (network.lapis.cloud.server.db.generated.ConferenceGuestConsentAcknowledgmentTable.memberId inList createdMemberIds)
                }
                ConferenceParticipationTable.deleteWhere { ConferenceParticipationTable.roomId inList createdRoomIds }
                ConferenceRoomTable.deleteWhere { ConferenceRoomTable.id inList createdRoomIds }
                AccountTable.deleteWhere { AccountTable.memberId inList createdMemberIds }
                MemberTable.deleteWhere { MemberTable.id inList createdMemberIds }
            }
        }

        fun createTestMember(
            email: String,
            status: MemberStatus = MemberStatus.ACTIVE,
        ): Uuid {
            val id = Uuid.random()
            transaction {
                MemberTable.insert {
                    it[MemberTable.id] = id
                    it[displayName] = "Friend-Conference Testmitglied"
                    it[MemberTable.email] = email
                    it[MemberTable.status] = status
                    it[joinedAt] = LocalDate(2026, 1, 1)
                    it[membershipTierId] = null
                    if (status == MemberStatus.FRIEND) it[friendSince] = LocalDate(2026, 1, 1)
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
            creatorId: Uuid,
            allowFederationGuests: Boolean,
        ): Uuid {
            val id = Uuid.random()
            transaction {
                ConferenceRoomTable.insert {
                    it[ConferenceRoomTable.id] = id
                    it[title] = "Friend-Conference-Raum"
                    it[description] = ""
                    it[livekitRoomName] = "lc-friend-$id"
                    it[createdByMemberId] = creatorId
                    it[createdAt] =
                        network.lapis.cloud.server.db.DbClock
                            .nowLocalDateTime()
                    it[endedAt] = null
                    it[maxParticipants] = 25
                    it[ConferenceRoomTable.allowFederationGuests] = allowFederationGuests
                }
            }
            createdRoomIds += id
            return id
        }

        test("FRIEND joins an opted-in room with valid consent -- succeeds, an open participation row is written") {
            testApplication {
                application {
                    install(StatusPages) { installFriendConferenceExceptionHandlers() }
                    routing { registerFriendConferenceTestRoutes() }
                }
                val creator = createTestMember("friend-conf-t1-creator@example.org")
                val roomId = createTestRoom(creatorId = creator, allowFederationGuests = true)
                val friend = createTestMember("friend-conf-t1-friend@example.org", status = MemberStatus.FRIEND)

                val response =
                    client.post(
                        "/test/join-room?roomId=$roomId&consentVersion=${ConferenceGuestConsentDisclaimer.VERSION}" +
                            "&consentSha256=${ConferenceGuestConsentDisclaimer.SHA256}",
                    ) { header("X-Member-Id", friend.toString()) }
                response.status shouldBe HttpStatusCode.OK

                val openCount =
                    transaction {
                        ConferenceParticipationTable
                            .selectAll()
                            .where { (ConferenceParticipationTable.roomId eq roomId) and (ConferenceParticipationTable.memberId eq friend) }
                            .count()
                    }
                openCount shouldBe 1L
            }
        }

        test("FRIEND joins a NON-opted-in room with valid consent -- Forbidden, zero rows written (allowFederationGuests defaults false)") {
            testApplication {
                application {
                    install(StatusPages) { installFriendConferenceExceptionHandlers() }
                    routing { registerFriendConferenceTestRoutes() }
                }
                val creator = createTestMember("friend-conf-t2-creator@example.org")
                val roomId = createTestRoom(creatorId = creator, allowFederationGuests = false)
                val friend = createTestMember("friend-conf-t2-friend@example.org", status = MemberStatus.FRIEND)

                val response =
                    client.post(
                        "/test/join-room?roomId=$roomId&consentVersion=${ConferenceGuestConsentDisclaimer.VERSION}" +
                            "&consentSha256=${ConferenceGuestConsentDisclaimer.SHA256}",
                    ) { header("X-Member-Id", friend.toString()) }
                response.status shouldBe HttpStatusCode.Forbidden

                transaction {
                    ConferenceParticipationTable.selectAll().where { ConferenceParticipationTable.memberId eq friend }.count()
                } shouldBe 0L
            }
        }

        test("FRIEND joins an opted-in room WITHOUT a consent payload -- Conflict, zero rows written") {
            testApplication {
                application {
                    install(StatusPages) { installFriendConferenceExceptionHandlers() }
                    routing { registerFriendConferenceTestRoutes() }
                }
                val creator = createTestMember("friend-conf-t3-creator@example.org")
                val roomId = createTestRoom(creatorId = creator, allowFederationGuests = true)
                val friend = createTestMember("friend-conf-t3-friend@example.org", status = MemberStatus.FRIEND)

                val response = client.post("/test/join-room?roomId=$roomId") { header("X-Member-Id", friend.toString()) }
                response.status shouldBe HttpStatusCode.Conflict
                transaction {
                    ConferenceParticipationTable.selectAll().where { ConferenceParticipationTable.memberId eq friend }.count()
                } shouldBe 0L
            }
        }

        test("FRIEND joins with a stale version, a flipped-nibble hash, and a malformed hash -- all Conflict, never a 500, zero rows") {
            testApplication {
                application {
                    install(StatusPages) { installFriendConferenceExceptionHandlers() }
                    routing { registerFriendConferenceTestRoutes() }
                }
                val creator = createTestMember("friend-conf-t4-creator@example.org")
                val roomId = createTestRoom(creatorId = creator, allowFederationGuests = true)
                val friend = createTestMember("friend-conf-t4-friend@example.org", status = MemberStatus.FRIEND)

                client
                    .post(
                        "/test/join-room?roomId=$roomId&consentVersion=2020-01-01.v0&consentSha256=${ConferenceGuestConsentDisclaimer.SHA256}",
                    ) { header("X-Member-Id", friend.toString()) }
                    .status shouldBe HttpStatusCode.Conflict

                val flippedHash = "0" + ConferenceGuestConsentDisclaimer.SHA256.drop(1)
                client
                    .post(
                        "/test/join-room?roomId=$roomId&consentVersion=${ConferenceGuestConsentDisclaimer.VERSION}&consentSha256=$flippedHash",
                    ) { header("X-Member-Id", friend.toString()) }
                    .status shouldBe HttpStatusCode.Conflict

                listOf("zzzz", "", "abc").forEach { badHash ->
                    client
                        .post(
                            "/test/join-room?roomId=$roomId&consentVersion=${ConferenceGuestConsentDisclaimer.VERSION}&consentSha256=$badHash",
                        ) { header("X-Member-Id", friend.toString()) }
                        .status shouldBe HttpStatusCode.Conflict
                }

                transaction {
                    ConferenceParticipationTable.selectAll().where { ConferenceParticipationTable.memberId eq friend }.count()
                } shouldBe 0L
            }
        }

        test(
            "A departed FRIEND (left the room) can no longer call listParticipants -- append-only participation history does not keep the gate open forever",
        ) {
            testApplication {
                application {
                    install(StatusPages) { installFriendConferenceExceptionHandlers() }
                    routing { registerFriendConferenceTestRoutes() }
                }
                val creator = createTestMember("friend-conf-t5-creator@example.org")
                val roomId = createTestRoom(creatorId = creator, allowFederationGuests = true)
                val friend = createTestMember("friend-conf-t5-friend@example.org", status = MemberStatus.FRIEND)

                client
                    .post(
                        "/test/join-room?roomId=$roomId&consentVersion=${ConferenceGuestConsentDisclaimer.VERSION}" +
                            "&consentSha256=${ConferenceGuestConsentDisclaimer.SHA256}",
                    ) { header("X-Member-Id", friend.toString()) }
                    .status shouldBe HttpStatusCode.OK

                // Still in the room -- listParticipants succeeds.
                client.get("/test/list-participants?roomId=$roomId") { header("X-Member-Id", friend.toString()) }.status shouldBe
                    HttpStatusCode.OK

                client.post("/test/leave-room?roomId=$roomId") { header("X-Member-Id", friend.toString()) }.status shouldBe
                    HttpStatusCode.OK

                // Departed -- must now be rejected, not silently allowed via the stale open row.
                client.get("/test/list-participants?roomId=$roomId") { header("X-Member-Id", friend.toString()) }.status shouldBe
                    HttpStatusCode.Forbidden
            }
        }

        test(
            "setRoomGuestAccess(false) disconnects a connected FRIEND from the room's participant view -- the moderator kill-switch also covers FRIEND, not just GUEST",
        ) {
            testApplication {
                application {
                    install(StatusPages) { installFriendConferenceExceptionHandlers() }
                    routing { registerFriendConferenceTestRoutes() }
                }
                val creator = createTestMember("friend-conf-t6-creator@example.org")
                val roomId = createTestRoom(creatorId = creator, allowFederationGuests = true)
                val friend = createTestMember("friend-conf-t6-friend@example.org", status = MemberStatus.FRIEND)

                client
                    .post(
                        "/test/join-room?roomId=$roomId&consentVersion=${ConferenceGuestConsentDisclaimer.VERSION}" +
                            "&consentSha256=${ConferenceGuestConsentDisclaimer.SHA256}",
                    ) { header("X-Member-Id", friend.toString()) }
                    .status shouldBe HttpStatusCode.OK

                client.get("/test/list-participants?roomId=$roomId") { header("X-Member-Id", friend.toString()) }.status shouldBe
                    HttpStatusCode.OK

                client
                    .post("/test/set-room-guest-access?roomId=$roomId&allowFederationGuests=false") {
                        header("X-Member-Id", creator.toString())
                    }.status shouldBe HttpStatusCode.OK

                // The revoke query filters `status inList NON_MEMBER`, which must include FRIEND --
                // otherwise the moderator kill-switch silently fails to disconnect a FRIEND
                // (Stolperfalle #13's FRIEND-widened counterpart).
                client.get("/test/list-participants?roomId=$roomId") { header("X-Member-Id", friend.toString()) }.status shouldBe
                    HttpStatusCode.Forbidden
            }
        }

        test("An ACTIVE member's joinRoom is completely unaffected by FRIEND eligibility -- null consent still succeeds, zero ack rows") {
            testApplication {
                application {
                    install(StatusPages) { installFriendConferenceExceptionHandlers() }
                    routing { registerFriendConferenceTestRoutes() }
                }
                val creator = createTestMember("friend-conf-t7-creator@example.org")
                val roomId = createTestRoom(creatorId = creator, allowFederationGuests = true)
                val active = createTestMember("friend-conf-t7-active@example.org")

                client.post("/test/join-room?roomId=$roomId") { header("X-Member-Id", active.toString()) }.status shouldBe
                    HttpStatusCode.OK
            }
        }

        test(
            "security-audit F4: a per-room maxNonMemberParticipants cap rejects a THIRD concurrently-open FRIEND once reached, an ACTIVE member is unaffected",
        ) {
            testApplication {
                val cappedConfig =
                    ConferenceConfig.load { key ->
                        when (key) {
                            "LAPIS_LIVEKIT_URL" -> "ws://localhost:7880"
                            "LAPIS_LIVEKIT_API_KEY" -> "test-livekit-key"
                            "LAPIS_LIVEKIT_API_SECRET" -> "test-livekit-secret-at-least-32-bytes-long!!"
                            "LAPIS_LIVEKIT_TOKEN_TTL_MINUTES" -> "240"
                            "LAPIS_CONFERENCE_MAX_PARTICIPANTS" -> "25"
                            "LAPIS_CONFERENCE_MAX_NON_MEMBER_PARTICIPANTS" -> "2"
                            else -> null
                        }
                    }
                application {
                    install(StatusPages) { installFriendConferenceExceptionHandlers() }
                    routing { registerFriendConferenceTestRoutes(config = cappedConfig) }
                }
                val creator = createTestMember("friend-conf-t8-creator@example.org")
                val roomId = createTestRoom(creatorId = creator, allowFederationGuests = true)
                val friend1 = createTestMember("friend-conf-t8-friend1@example.org", status = MemberStatus.FRIEND)
                val friend2 = createTestMember("friend-conf-t8-friend2@example.org", status = MemberStatus.FRIEND)
                val friend3 = createTestMember("friend-conf-t8-friend3@example.org", status = MemberStatus.FRIEND)
                val active = createTestMember("friend-conf-t8-active@example.org")

                suspend fun joinAsFriend(memberId: Uuid) =
                    client.post(
                        "/test/join-room?roomId=$roomId&consentVersion=${ConferenceGuestConsentDisclaimer.VERSION}" +
                            "&consentSha256=${ConferenceGuestConsentDisclaimer.SHA256}",
                    ) { header("X-Member-Id", memberId.toString()) }

                joinAsFriend(friend1).status shouldBe HttpStatusCode.OK
                joinAsFriend(friend2).status shouldBe HttpStatusCode.OK

                // Cap (2) reached -- a THIRD non-member join is rejected, no row written.
                joinAsFriend(friend3).status shouldBe HttpStatusCode.Conflict
                transaction {
                    ConferenceParticipationTable.selectAll().where { ConferenceParticipationTable.memberId eq friend3 }.count()
                } shouldBe 0L

                // The non-member cap does not apply to an ACTIVE member -- the room's own
                // maxParticipants (25) is the only ceiling for them.
                client.post("/test/join-room?roomId=$roomId") { header("X-Member-Id", active.toString()) }.status shouldBe
                    HttpStatusCode.OK

                // A friend that already LEFT frees its slot for a new non-member join.
                client.post("/test/leave-room?roomId=$roomId") { header("X-Member-Id", friend1.toString()) }.status shouldBe
                    HttpStatusCode.OK
                joinAsFriend(friend3).status shouldBe HttpStatusCode.OK
            }
        }
    })

private class FakeFriendConferenceLiveKitAdminClient : LiveKitAdminClient {
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
}

private fun StatusPagesConfig.installFriendConferenceExceptionHandlers() {
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
    exception<LiveKitAdminException> { call, cause ->
        call.respondText(cause.message ?: "livekit error", status = HttpStatusCode.BadGateway)
    }
}

private fun Route.registerFriendConferenceTestRoutes(config: ConferenceConfig = ENABLED_CONFIG) {
    val liveKitAdminClient = FakeFriendConferenceLiveKitAdminClient()

    fun service(call: ApplicationCall) =
        ConferenceService(
            call = call,
            liveKitAdminClient = liveKitAdminClient,
            createRoomRateLimiter = LoginRateLimiter(),
            config = config,
            joinRoomRateLimiter = FederationInboxRateLimiter(maxRequests = 60, window = 1.minutes),
            leaveRoomRateLimiter = FederationInboxRateLimiter(maxRequests = 60, window = 1.minutes),
            listRateLimiter = FederationInboxRateLimiter(maxRequests = 120, window = 1.minutes),
            guestAccessRateLimiter = FederationInboxRateLimiter(maxRequests = 60, window = 1.minutes),
            conferenceMeetingBindRateLimiter = FederationInboxRateLimiter(maxRequests = 10, window = 1.minutes),
        )
    post("/test/join-room") {
        val q = call.request.queryParameters
        val consentVersion = q["consentVersion"]
        val consentSha256 = q["consentSha256"]
        val consent =
            if (consentVersion != null || consentSha256 != null) {
                ConferenceGuestConsentAcknowledgmentInput(consentVersion = consentVersion ?: "", consentSha256 = consentSha256 ?: "")
            } else {
                null
            }
        val dto = service(call).joinRoom(roomId = q["roomId"]!!, guestConsent = consent)
        call.respondText(dto.roomId)
    }
    post("/test/leave-room") {
        val q = call.request.queryParameters
        service(call).leaveRoom(q["roomId"]!!)
        call.respondText("ok")
    }
    get("/test/list-participants") {
        val q = call.request.queryParameters
        val dtos = service(call).listParticipants(q["roomId"]!!)
        call.respondText(dtos.joinToString(";") { it.memberId })
    }
    post("/test/set-room-guest-access") {
        val q = call.request.queryParameters
        val dto = service(call).setRoomGuestAccess(roomId = q["roomId"]!!, allowFederationGuests = q["allowFederationGuests"]!!.toBoolean())
        call.respondText(dto.id)
    }
}
