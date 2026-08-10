package network.lapis.cloud.server.rpc

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
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
import network.lapis.cloud.server.db.DbClock
import network.lapis.cloud.server.db.DevSeedData
import network.lapis.cloud.server.db.generated.AccountTable
import network.lapis.cloud.server.db.generated.ConferenceBreakoutAssignmentTable
import network.lapis.cloud.server.db.generated.ConferenceBreakoutRoomTable
import network.lapis.cloud.server.db.generated.ConferenceParticipationTable
import network.lapis.cloud.server.db.generated.ConferenceRoomTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.server.db.generated.OidcGuestProfileTable
import network.lapis.cloud.server.db.generated.SessionTable
import network.lapis.cloud.server.federation.FederationInboxRateLimiter
import network.lapis.cloud.server.federation.OidcGuestClaims
import network.lapis.cloud.server.federation.OidcGuestMemberStore
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.ConferenceBreakoutAssignmentDto
import network.lapis.cloud.shared.domain.ConferenceBreakoutAssignmentInput
import network.lapis.cloud.shared.domain.ConferenceBreakoutPlanInput
import network.lapis.cloud.shared.domain.ConferenceBreakoutRoomDto
import network.lapis.cloud.shared.domain.ConferenceJoinTokenDto
import network.lapis.cloud.shared.domain.ConferenceRole
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
import kotlin.time.Duration.Companion.minutes
import kotlin.uuid.Uuid

/** A [ConferenceConfig] with `enabled=true`, matching [FakeBreakoutLiveKitAdminClient]'s own in-memory expectations -- see [ConferenceServiceTest]'s own `ENABLED_CONFIG` for the identical shape. */
private val BREAKOUT_ENABLED_CONFIG =
    ConferenceConfig.load { key ->
        when (key) {
            "LAPIS_LIVEKIT_URL" -> "ws://localhost:7880"
            "LAPIS_LIVEKIT_API_KEY" -> "test-livekit-key"
            "LAPIS_LIVEKIT_API_SECRET" -> "test-livekit-secret-at-least-32-bytes-long!!"
            "LAPIS_LIVEKIT_TOKEN_TTL_MINUTES" -> "240"
            "LAPIS_LIVEKIT_GUEST_TOKEN_TTL_MINUTES" -> "15"
            "LAPIS_CONFERENCE_MAX_PARTICIPANTS" -> "25"
            else -> null
        }
    }

/**
 * Hermetic, in-memory stand-in for [LiveKitAdminClient] -- mirrors [ConferenceServiceTest]'s own
 * `FakeLiveKitAdminClient` (Kotlin top-level `private` declarations are file-scoped, so this is a
 * deliberate duplicate, not a copy-paste oversight -- see this wave's own plan §9 "FakeLiveKitAdminClient
 * visibility").
 */
private class FakeBreakoutLiveKitAdminClient : LiveKitAdminClient {
    private val rooms = mutableMapOf<String, LiveKitRoomInfo>()
    private val participantsByRoom = mutableMapOf<String, MutableList<LiveKitParticipantInfo>>()
    var createRoomCallCount = 0
        private set
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
        createRoomCallCount++
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

    fun seedLiveParticipant(
        room: String,
        identity: String,
    ) {
        participantsByRoom.getOrPut(room) { mutableListOf() }.add(LiveKitParticipantInfo(identity = identity, name = identity))
    }

    private fun maybeFail() {
        if (failNextCall) {
            failNextCall = false
            throw LiveKitAdminException("simulated LiveKit failure")
        }
    }
}

/**
 * Exercises [ConferenceBreakoutService] end to end -- mirrors [ConferenceServiceTest]'s own house
 * style (throwaway routes calling the service class directly, pipe-separated response bodies, no
 * wire format to reverse-engineer). Every LiveKit-touching call goes through
 * [FakeBreakoutLiveKitAdminClient] -- no Docker/real LiveKit container involved. [afterSpec]
 * hard-deletes every row this file created.
 */
class ConferenceBreakoutServiceTest :
    FunSpec({
        val createdMemberIds = mutableListOf<Uuid>()

        beforeSpec {
            DatabaseConfig.connect()
            DevSeedData.seedIfEmpty(force = true)
        }

        afterSpec { cleanUpConferenceBreakoutTestData(createdMemberIds) }

        fun createTestMember(
            email: String,
            status: MemberStatus = MemberStatus.AKTIV,
        ): Uuid {
            val id = Uuid.random()
            transaction {
                MemberTable.insert {
                    it[MemberTable.id] = id
                    it[displayName] = email.substringBefore("@")
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

        /** Wave 5-style real GAST member, matching [ConferenceServiceTest]'s own `createTestGuestMember` helper. */
        fun createTestGuestMember(subjectSuffix: String): Uuid {
            val issuer = "https://breakout-guest-home-$subjectSuffix.example"
            val claims =
                OidcGuestClaims(
                    issuer = issuer,
                    subject = "breakout-guest-subject-$subjectSuffix",
                    name = "Breakout Testgast $subjectSuffix",
                    picture = null,
                    preferredUsername = null,
                    homeserverUrl = issuer,
                    membershipStatus = "AKTIV",
                )
            val id = OidcGuestMemberStore.resolveOrCreateGuestMember(claims, "openid profile_basic")
            createdMemberIds += id
            return id
        }

        /** Row count for [ConferenceBreakoutAssignmentTable], any status -- used by the MAX_ASSIGNMENTS_PER_CALL/dedup tests below. */
        fun assignmentRowCountFor(memberId: Uuid): Long =
            transaction {
                ConferenceBreakoutAssignmentTable.selectAll().where { ConferenceBreakoutAssignmentTable.memberId eq memberId }.count()
            }

        /**
         * Sets up a parent `conference_room` row (active, `allowFederationGuests` per parameter)
         * plus an open `conference_participation` row for every member in [liveMemberIds] (creator
         * included automatically) and a matching live LiveKit roster entry on [liveKit] -- the
         * minimum fixture [ConferenceBreakoutService] needs without going through the full
         * [ConferenceService.createRoom]/[ConferenceService.joinRoom] HTTP round-trip.
         */
        suspend fun createTestParentRoom(
            liveKit: FakeBreakoutLiveKitAdminClient,
            creatorId: Uuid,
            liveMemberIds: List<Uuid> = emptyList(),
            allowFederationGuests: Boolean = false,
        ): Pair<Uuid, String> {
            val roomId = Uuid.random()
            val livekitRoomName = "lc-breakout-test-$roomId"
            val now = DbClock.nowLocalDateTime()
            val allMembers = (liveMemberIds + creatorId).distinct()
            transaction {
                ConferenceRoomTable.insert {
                    it[id] = roomId
                    it[title] = "Breakout-Testsitzung"
                    it[description] = ""
                    it[ConferenceRoomTable.livekitRoomName] = livekitRoomName
                    it[createdByMemberId] = creatorId
                    it[createdAt] = now
                    it[endedAt] = null
                    it[maxParticipants] = 25
                    it[ConferenceRoomTable.allowFederationGuests] = allowFederationGuests
                }
                allMembers.forEach { memberId ->
                    ConferenceParticipationTable.insert {
                        it[id] = Uuid.random()
                        it[ConferenceParticipationTable.roomId] = roomId
                        it[ConferenceParticipationTable.memberId] = memberId
                        it[role] = if (memberId == creatorId) ConferenceRole.MODERATOR else ConferenceRole.PARTICIPANT
                        it[joinedAt] = now
                        it[leftAt] = null
                    }
                }
            }
            liveKit.createRoom(livekitRoomName, 25, 300)
            allMembers.forEach { liveKit.seedLiveParticipant(livekitRoomName, it.toString()) }
            return roomId to livekitRoomName
        }

        // ── createBreakoutRooms ────────────────────────────────────────────

        test(
            "createBreakoutRooms: happy path excludes the moderator, distributes the rest across N rooms, and calls createRoom exactly N times",
        ) {
            val fake = FakeBreakoutLiveKitAdminClient()
            val moderator = createTestMember("bo-happy-mod@example.org")
            val participants = (1..5).map { createTestMember("bo-happy-p$it@example.org") }
            val (roomId, _) = createTestParentRoom(fake, moderator, participants)

            testApplication {
                application {
                    install(StatusPages) { installConferenceBreakoutExceptionHandlers() }
                    routing { registerConferenceBreakoutTestRoutes(fake) }
                }
                val response =
                    client.post("/test/create-breakout-rooms?roomId=$roomId&roomCount=3") {
                        header("X-Member-Id", moderator.toString())
                    }
                response.status shouldBe HttpStatusCode.OK
                val rooms = response.bodyAsText().split(";").map { it.split("|") }
                rooms.size shouldBe 3
                // +1 for the parent room's own createRoom call inside createTestParentRoom.
                fake.createRoomCallCount shouldBe 4

                val allAssigned = rooms.flatMap { it[4].split(",").filter { entry -> entry.isNotBlank() } }
                allAssigned.toSet() shouldBe participants.map { it.toString() }.toSet()
                (moderator.toString() in allAssigned) shouldBe false
            }
        }

        test("createBreakoutRooms: manualAssignments pin a specific member to a specific room") {
            val fake = FakeBreakoutLiveKitAdminClient()
            val moderator = createTestMember("bo-manual-mod@example.org")
            val pinned = createTestMember("bo-manual-pinned@example.org")
            val other = createTestMember("bo-manual-other@example.org")
            val (roomId, _) = createTestParentRoom(fake, moderator, listOf(pinned, other))

            testApplication {
                application {
                    install(StatusPages) { installConferenceBreakoutExceptionHandlers() }
                    routing { registerConferenceBreakoutTestRoutes(fake) }
                }
                val response =
                    client.post("/test/create-breakout-rooms?roomId=$roomId&roomCount=2&manualMemberId=$pinned&manualIndex=1") {
                        header("X-Member-Id", moderator.toString())
                    }
                response.status shouldBe HttpStatusCode.OK
                val rooms = response.bodyAsText().split(";").map { it.split("|") }
                rooms[1][4].split(",") shouldContain pinned.toString()
            }
        }

        test("createBreakoutRooms: rejected with Conflict while a batch is already open, no second batch is created") {
            val fake = FakeBreakoutLiveKitAdminClient()
            val moderator = createTestMember("bo-tamper-doublebatch-mod@example.org")
            val (roomId, _) = createTestParentRoom(fake, moderator)

            testApplication {
                application {
                    install(StatusPages) { installConferenceBreakoutExceptionHandlers() }
                    routing { registerConferenceBreakoutTestRoutes(fake) }
                }
                client.post("/test/create-breakout-rooms?roomId=$roomId&roomCount=2") { header("X-Member-Id", moderator.toString()) }
                // +1 for the parent room's own createRoom call inside createTestParentRoom.
                fake.createRoomCallCount shouldBe 3

                client
                    .post("/test/create-breakout-rooms?roomId=$roomId&roomCount=2") {
                        header("X-Member-Id", moderator.toString())
                    }.status shouldBe HttpStatusCode.Conflict
                fake.createRoomCallCount shouldBe 3
            }
        }

        test(
            "createBreakoutRooms: a manualAssignments member not currently live in the parent room is rejected with BadRequest, zero createRoom calls",
        ) {
            val fake = FakeBreakoutLiveKitAdminClient()
            val moderator = createTestMember("bo-tamper-notlive-mod@example.org")
            val notLive = createTestMember("bo-tamper-notlive-member@example.org")
            val roomId = Uuid.random()
            val livekitRoomName = "lc-breakout-test-$roomId"
            val now = DbClock.nowLocalDateTime()
            transaction {
                ConferenceRoomTable.insert {
                    it[id] = roomId
                    it[title] = "NotLive"
                    it[description] = ""
                    it[ConferenceRoomTable.livekitRoomName] = livekitRoomName
                    it[createdByMemberId] = moderator
                    it[createdAt] = now
                    it[endedAt] = null
                    it[maxParticipants] = 25
                    it[allowFederationGuests] = false
                }
                listOf(moderator, notLive).forEach { memberId ->
                    ConferenceParticipationTable.insert {
                        it[id] = Uuid.random()
                        it[ConferenceParticipationTable.roomId] = roomId
                        it[ConferenceParticipationTable.memberId] = memberId
                        it[role] = if (memberId == moderator) ConferenceRole.MODERATOR else ConferenceRole.PARTICIPANT
                        it[joinedAt] = now
                        it[leftAt] = null
                    }
                }
            }
            fake.createRoom(livekitRoomName, 25, 300)
            fake.seedLiveParticipant(livekitRoomName, moderator.toString()) // notLive deliberately NOT seeded as live

            testApplication {
                application {
                    install(StatusPages) { installConferenceBreakoutExceptionHandlers() }
                    routing { registerConferenceBreakoutTestRoutes(fake) }
                }
                client
                    .post("/test/create-breakout-rooms?roomId=$roomId&roomCount=1&manualMemberId=$notLive&manualIndex=0") {
                        header("X-Member-Id", moderator.toString())
                    }.status shouldBe HttpStatusCode.BadRequest
                // Only the fixture's own direct createRoom call for the parent room -- zero breakout rooms.
                fake.createRoomCallCount shouldBe 1
            }
        }

        test("createBreakoutRooms: a createRoom failure partway through cleans up the already-created rooms and writes zero DB rows") {
            val fake = FakeBreakoutLiveKitAdminClient()
            val moderator = createTestMember("bo-tamper-orphan-mod@example.org")
            val (roomId, _) = createTestParentRoom(fake, moderator)

            var breakoutCreateCalls = 0
            val flakyClient =
                object : LiveKitAdminClient by fake {
                    override suspend fun createRoom(
                        name: String,
                        maxParticipants: Int,
                        emptyTimeoutSeconds: Int,
                    ): LiveKitRoomInfo {
                        breakoutCreateCalls++
                        if (breakoutCreateCalls == 2) throw LiveKitAdminException("simulated failure on room 2 of 3")
                        return fake.createRoom(name, maxParticipants, emptyTimeoutSeconds)
                    }
                }

            testApplication {
                application {
                    install(StatusPages) { installConferenceBreakoutExceptionHandlers() }
                    routing { registerConferenceBreakoutTestRoutes(flakyClient) }
                }
                client
                    .post("/test/create-breakout-rooms?roomId=$roomId&roomCount=3") {
                        header("X-Member-Id", moderator.toString())
                    }.status shouldBe HttpStatusCode.Conflict
            }
            fake.deleteRoomCallCount shouldBe 1
            transaction {
                ConferenceBreakoutRoomTable.selectAll().where { ConferenceBreakoutRoomTable.parentRoomId eq roomId }.count()
            } shouldBe 0L
        }

        test(
            "createBreakoutRooms: manualAssignments longer than the security-audit MAX_MANUAL_ASSIGNMENTS cap is rejected with " +
                "BadRequest, zero breakout LiveKit rooms created",
        ) {
            val fake = FakeBreakoutLiveKitAdminClient()
            val moderator = createTestMember("bo-tamper-manualcap-mod@example.org")
            val member = createTestMember("bo-tamper-manualcap-member@example.org")
            val (roomId, _) = createTestParentRoom(fake, moderator, listOf(member))

            testApplication {
                application {
                    install(StatusPages) { installConferenceBreakoutExceptionHandlers() }
                    routing { registerConferenceBreakoutTestRoutes(fake) }
                }
                client
                    .post(
                        "/test/create-breakout-rooms?roomId=$roomId&roomCount=1&manualMemberId=$member&manualIndex=0&manualRepeatCount=101",
                    ) { header("X-Member-Id", moderator.toString()) }
                    .status shouldBe HttpStatusCode.BadRequest
                // Only the fixture's own direct createRoom call for the parent room -- zero breakout rooms.
                fake.createRoomCallCount shouldBe 1
                transaction {
                    ConferenceBreakoutRoomTable.selectAll().where { ConferenceBreakoutRoomTable.parentRoomId eq roomId }.count()
                } shouldBe 0L
            }
        }

        // ── assignParticipants ─────────────────────────────────────────────

        test("assignParticipants: moves a member from breakout room A to B, closing the old assignment and opening a new one") {
            val fake = FakeBreakoutLiveKitAdminClient()
            val moderator = createTestMember("bo-move-mod@example.org")
            val member = createTestMember("bo-move-member@example.org")
            val (roomId, _) = createTestParentRoom(fake, moderator, listOf(member))

            testApplication {
                application {
                    install(StatusPages) { installConferenceBreakoutExceptionHandlers() }
                    routing { registerConferenceBreakoutTestRoutes(fake) }
                }
                val created =
                    client.post("/test/create-breakout-rooms?roomId=$roomId&roomCount=2&manualMemberId=$member&manualIndex=0") {
                        header("X-Member-Id", moderator.toString())
                    }
                val roomsAfterCreate = created.bodyAsText().split(";").map { it.split("|") }
                val roomBId = roomsAfterCreate[1][0]

                val moveResponse =
                    client.post("/test/assign-participants?roomId=$roomId&memberId=$member&breakoutIndex=1") {
                        header("X-Member-Id", moderator.toString())
                    }
                moveResponse.status shouldBe HttpStatusCode.OK
                val roomsAfterMove = moveResponse.bodyAsText().split(";").map { it.split("|") }
                val roomB = roomsAfterMove.single { it[0] == roomBId }
                roomB[4].split(",") shouldContain member.toString()
                val roomA = roomsAfterMove.first { it[0] != roomBId }
                (member.toString() in roomA[4].split(",")) shouldBe false
            }
        }

        test("assignParticipants: a memberId with no open participation in the parent room is rejected with BadRequest") {
            val fake = FakeBreakoutLiveKitAdminClient()
            val moderator = createTestMember("bo-assign-nonpart-mod@example.org")
            val member = createTestMember("bo-assign-nonpart-member@example.org")
            val outsider = createTestMember("bo-assign-nonpart-outsider@example.org")
            val (roomId, _) = createTestParentRoom(fake, moderator, listOf(member))

            testApplication {
                application {
                    install(StatusPages) { installConferenceBreakoutExceptionHandlers() }
                    routing { registerConferenceBreakoutTestRoutes(fake) }
                }
                client.post("/test/create-breakout-rooms?roomId=$roomId&roomCount=1") { header("X-Member-Id", moderator.toString()) }

                client
                    .post("/test/assign-participants?roomId=$roomId&memberId=$outsider&breakoutIndex=0") {
                        header("X-Member-Id", moderator.toString())
                    }.status shouldBe HttpStatusCode.BadRequest
            }
        }

        test(
            "assignParticipants: an assignments array longer than the security-audit MAX_ASSIGNMENTS_PER_CALL cap is rejected " +
                "with BadRequest and writes zero new assignment rows",
        ) {
            val fake = FakeBreakoutLiveKitAdminClient()
            val moderator = createTestMember("bo-tamper-assigncap-mod@example.org")
            val member = createTestMember("bo-tamper-assigncap-member@example.org")
            val (roomId, _) = createTestParentRoom(fake, moderator, listOf(member))

            testApplication {
                application {
                    install(StatusPages) { installConferenceBreakoutExceptionHandlers() }
                    routing { registerConferenceBreakoutTestRoutes(fake) }
                }
                client.post("/test/create-breakout-rooms?roomId=$roomId&roomCount=2&manualMemberId=$member&manualIndex=0") {
                    header("X-Member-Id", moderator.toString())
                }
                val countBefore = assignmentRowCountFor(member)

                client
                    .post("/test/assign-participants?roomId=$roomId&memberId=$member&breakoutIndex=1&repeatCount=101") {
                        header("X-Member-Id", moderator.toString())
                    }.status shouldBe HttpStatusCode.BadRequest

                assignmentRowCountFor(member) shouldBe countBefore
                fake.removeParticipantCallCount shouldBe 1 // only createBreakoutRooms' own initial force-disconnect
            }
        }

        test(
            "assignParticipants: duplicate memberId entries in one call are de-duplicated (last entry wins) -- exactly one new " +
                "assignment row and exactly one outbound LiveKit removeParticipant call, not one per duplicate",
        ) {
            val fake = FakeBreakoutLiveKitAdminClient()
            val moderator = createTestMember("bo-dedup-mod@example.org")
            val member = createTestMember("bo-dedup-member@example.org")
            val (roomId, _) = createTestParentRoom(fake, moderator, listOf(member))

            testApplication {
                application {
                    install(StatusPages) { installConferenceBreakoutExceptionHandlers() }
                    routing { registerConferenceBreakoutTestRoutes(fake) }
                }
                val created =
                    client.post("/test/create-breakout-rooms?roomId=$roomId&roomCount=2&manualMemberId=$member&manualIndex=0") {
                        header("X-Member-Id", moderator.toString())
                    }
                val roomBId = created.bodyAsText().split(";").map { it.split("|") }[1][0]

                val countBefore = assignmentRowCountFor(member)
                val removeCallsBefore = fake.removeParticipantCallCount

                val moveResponse =
                    client.post("/test/assign-participants?roomId=$roomId&memberId=$member&breakoutIndex=1&repeatCount=5") {
                        header("X-Member-Id", moderator.toString())
                    }
                moveResponse.status shouldBe HttpStatusCode.OK

                assignmentRowCountFor(member) shouldBe countBefore + 1L // exactly one new row, not five
                fake.removeParticipantCallCount shouldBe removeCallsBefore + 1 // exactly one relocation call, not five

                val roomsAfterMove = moveResponse.bodyAsText().split(";").map { it.split("|") }
                val roomB = roomsAfterMove.single { it[0] == roomBId }
                roomB[4].split(",") shouldContain member.toString()
            }
        }

        // ── recallAll ──────────────────────────────────────────────────────

        test("recallAll: closes every open breakout room, deletes each LiveKit room once, and is idempotent (returns 0 the second time)") {
            val fake = FakeBreakoutLiveKitAdminClient()
            val moderator = createTestMember("bo-recall-mod@example.org")
            val member = createTestMember("bo-recall-member@example.org")
            val (roomId, _) = createTestParentRoom(fake, moderator, listOf(member))

            testApplication {
                application {
                    install(StatusPages) { installConferenceBreakoutExceptionHandlers() }
                    routing { registerConferenceBreakoutTestRoutes(fake) }
                }
                client.post("/test/create-breakout-rooms?roomId=$roomId&roomCount=2") { header("X-Member-Id", moderator.toString()) }

                val firstRecall = client.post("/test/recall-all?roomId=$roomId") { header("X-Member-Id", moderator.toString()) }
                firstRecall.status shouldBe HttpStatusCode.OK
                firstRecall.bodyAsText() shouldBe "2"
                fake.deleteRoomCallCount shouldBe 2

                val secondRecall = client.post("/test/recall-all?roomId=$roomId") { header("X-Member-Id", moderator.toString()) }
                secondRecall.bodyAsText() shouldBe "0"
                fake.deleteRoomCallCount shouldBe 2
            }
            val stillOpen =
                transaction {
                    ConferenceBreakoutRoomTable
                        .selectAll()
                        .where { (ConferenceBreakoutRoomTable.parentRoomId eq roomId) and ConferenceBreakoutRoomTable.closedAt.isNull() }
                        .count()
                }
            stillOpen shouldBe 0L
        }

        test("recallAll: tolerates a LiveKit deleteRoom failure (e.g. the room already vanished) without failing the whole call") {
            val fake = FakeBreakoutLiveKitAdminClient()
            val moderator = createTestMember("bo-recall-tolerant-mod@example.org")
            val (roomId, _) = createTestParentRoom(fake, moderator)

            testApplication {
                application {
                    install(StatusPages) { installConferenceBreakoutExceptionHandlers() }
                    routing { registerConferenceBreakoutTestRoutes(fake) }
                }
                client.post("/test/create-breakout-rooms?roomId=$roomId&roomCount=1") { header("X-Member-Id", moderator.toString()) }

                fake.failNextCall = true
                val recall = client.post("/test/recall-all?roomId=$roomId") { header("X-Member-Id", moderator.toString()) }
                recall.status shouldBe HttpStatusCode.OK
                recall.bodyAsText() shouldBe "1"
            }
            val stillOpen =
                transaction {
                    ConferenceBreakoutRoomTable
                        .selectAll()
                        .where { (ConferenceBreakoutRoomTable.parentRoomId eq roomId) and ConferenceBreakoutRoomTable.closedAt.isNull() }
                        .count()
                }
            stillOpen shouldBe 0L
        }

        // ── getMyBreakoutAssignment / requestBreakoutJoinToken / returnToMainRoom / rejoinMainRoomToken ──

        test("getMyBreakoutAssignment: returns the caller's own open assignment, and null after recall") {
            val fake = FakeBreakoutLiveKitAdminClient()
            val moderator = createTestMember("bo-myassign-mod@example.org")
            val member = createTestMember("bo-myassign-member@example.org")
            val (roomId, _) = createTestParentRoom(fake, moderator, listOf(member))

            testApplication {
                application {
                    install(StatusPages) { installConferenceBreakoutExceptionHandlers() }
                    routing { registerConferenceBreakoutTestRoutes(fake) }
                }
                client.post("/test/create-breakout-rooms?roomId=$roomId&roomCount=1&manualMemberId=$member&manualIndex=0") {
                    header("X-Member-Id", moderator.toString())
                }

                val before = client.get("/test/my-breakout-assignment?roomId=$roomId") { header("X-Member-Id", member.toString()) }
                before.bodyAsText() shouldNotBe "none"

                client.post("/test/recall-all?roomId=$roomId") { header("X-Member-Id", moderator.toString()) }
                val after = client.get("/test/my-breakout-assignment?roomId=$roomId") { header("X-Member-Id", member.toString()) }
                after.bodyAsText() shouldBe "none"
            }
        }

        test(
            "requestBreakoutJoinToken: returns a valid PARTICIPANT token for an assigned member, including a GAST caller (short guest TTL)",
        ) {
            val fake = FakeBreakoutLiveKitAdminClient()
            val moderator = createTestMember("bo-token-mod@example.org")
            val guest = createTestGuestMember("bo-token")
            val (roomId, _) = createTestParentRoom(fake, moderator, listOf(guest), allowFederationGuests = true)

            testApplication {
                application {
                    install(StatusPages) { installConferenceBreakoutExceptionHandlers() }
                    routing { registerConferenceBreakoutTestRoutes(fake) }
                }
                val created =
                    client.post("/test/create-breakout-rooms?roomId=$roomId&roomCount=1&manualMemberId=$guest&manualIndex=0") {
                        header("X-Member-Id", moderator.toString())
                    }
                val breakoutRoomId =
                    created
                        .bodyAsText()
                        .split(";")
                        .first()
                        .split("|")[0]

                val tokenResponse =
                    client.post("/test/request-breakout-join-token?breakoutRoomId=$breakoutRoomId") {
                        header("X-Member-Id", guest.toString())
                    }
                tokenResponse.status shouldBe HttpStatusCode.OK
                val parts = tokenResponse.bodyAsText().split("|")
                parts[3] shouldBe "PARTICIPANT"
                parts[4] shouldBe "true"
            }
        }

        test("requestBreakoutJoinToken: a participant never assigned to any breakout room is rejected with Forbidden") {
            val fake = FakeBreakoutLiveKitAdminClient()
            val moderator = createTestMember("bo-tamper-neverassigned-mod@example.org")
            val outsider = createTestMember("bo-tamper-neverassigned-outsider@example.org")
            val (roomId, _) = createTestParentRoom(fake, moderator)

            testApplication {
                application {
                    install(StatusPages) { installConferenceBreakoutExceptionHandlers() }
                    routing { registerConferenceBreakoutTestRoutes(fake) }
                }
                val created =
                    client.post("/test/create-breakout-rooms?roomId=$roomId&roomCount=1") { header("X-Member-Id", moderator.toString()) }
                val breakoutRoomId =
                    created
                        .bodyAsText()
                        .split(";")
                        .first()
                        .split("|")[0]

                client
                    .post("/test/request-breakout-join-token?breakoutRoomId=$breakoutRoomId") {
                        header("X-Member-Id", outsider.toString())
                    }.status shouldBe HttpStatusCode.Forbidden
            }
        }

        test(
            "requestBreakoutJoinToken: a member assigned to breakout room A cannot obtain a token for breakout room B of the same batch",
        ) {
            val fake = FakeBreakoutLiveKitAdminClient()
            val moderator = createTestMember("bo-tamper-wrongroom-mod@example.org")
            val member = createTestMember("bo-tamper-wrongroom-member@example.org")
            val (roomId, _) = createTestParentRoom(fake, moderator, listOf(member))

            testApplication {
                application {
                    install(StatusPages) { installConferenceBreakoutExceptionHandlers() }
                    routing { registerConferenceBreakoutTestRoutes(fake) }
                }
                val created =
                    client.post("/test/create-breakout-rooms?roomId=$roomId&roomCount=2&manualMemberId=$member&manualIndex=0") {
                        header("X-Member-Id", moderator.toString())
                    }
                val rooms = created.bodyAsText().split(";").map { it.split("|") }
                val roomAId = rooms[0][0]
                val roomBId = rooms[1][0]
                rooms[0][4].split(",") shouldContain member.toString()

                client
                    .post("/test/request-breakout-join-token?breakoutRoomId=$roomAId") {
                        header("X-Member-Id", member.toString())
                    }.status shouldBe HttpStatusCode.OK
                client
                    .post("/test/request-breakout-join-token?breakoutRoomId=$roomBId") {
                        header("X-Member-Id", member.toString())
                    }.status shouldBe HttpStatusCode.Forbidden
            }
        }

        test("requestBreakoutJoinToken: a member whose assignment was already recalled cannot replay it for a fresh token") {
            val fake = FakeBreakoutLiveKitAdminClient()
            val moderator = createTestMember("bo-tamper-replay-mod@example.org")
            val member = createTestMember("bo-tamper-replay-member@example.org")
            val (roomId, _) = createTestParentRoom(fake, moderator, listOf(member))

            testApplication {
                application {
                    install(StatusPages) { installConferenceBreakoutExceptionHandlers() }
                    routing { registerConferenceBreakoutTestRoutes(fake) }
                }
                val created =
                    client.post("/test/create-breakout-rooms?roomId=$roomId&roomCount=1&manualMemberId=$member&manualIndex=0") {
                        header("X-Member-Id", moderator.toString())
                    }
                val breakoutRoomId =
                    created
                        .bodyAsText()
                        .split(";")
                        .first()
                        .split("|")[0]
                client
                    .post("/test/request-breakout-join-token?breakoutRoomId=$breakoutRoomId") {
                        header("X-Member-Id", member.toString())
                    }.status shouldBe HttpStatusCode.OK

                client.post("/test/recall-all?roomId=$roomId") { header("X-Member-Id", moderator.toString()) }

                client
                    .post("/test/request-breakout-join-token?breakoutRoomId=$breakoutRoomId") {
                        header("X-Member-Id", member.toString())
                    }.status shouldBe HttpStatusCode.Forbidden
            }
        }

        test(
            "returnToMainRoom: closes only the caller's own open assignment, leaving another member's assignment in the same room untouched, and is idempotent",
        ) {
            val fake = FakeBreakoutLiveKitAdminClient()
            val moderator = createTestMember("bo-return-mod@example.org")
            val memberA = createTestMember("bo-return-a@example.org")
            val memberB = createTestMember("bo-return-b@example.org")
            val (roomId, _) = createTestParentRoom(fake, moderator, listOf(memberA, memberB))

            testApplication {
                application {
                    install(StatusPages) { installConferenceBreakoutExceptionHandlers() }
                    routing { registerConferenceBreakoutTestRoutes(fake) }
                }
                val created =
                    client.post("/test/create-breakout-rooms?roomId=$roomId&roomCount=1") { header("X-Member-Id", moderator.toString()) }
                val breakoutRoomId =
                    created
                        .bodyAsText()
                        .split(";")
                        .first()
                        .split("|")[0]

                client
                    .post("/test/return-to-main-room?breakoutRoomId=$breakoutRoomId") {
                        header("X-Member-Id", memberA.toString())
                    }.status shouldBe HttpStatusCode.OK

                val aAssignment = client.get("/test/my-breakout-assignment?roomId=$roomId") { header("X-Member-Id", memberA.toString()) }
                aAssignment.bodyAsText() shouldBe "none"
                val bAssignment = client.get("/test/my-breakout-assignment?roomId=$roomId") { header("X-Member-Id", memberB.toString()) }
                bAssignment.bodyAsText() shouldNotBe "none"

                // Idempotent -- a second call for the already-closed assignment is a no-op.
                client
                    .post("/test/return-to-main-room?breakoutRoomId=$breakoutRoomId") {
                        header("X-Member-Id", memberA.toString())
                    }.status shouldBe HttpStatusCode.OK
            }
        }

        test(
            "rejoinMainRoomToken: succeeds for a caller with an open participation row, does NOT insert a new one, and a moderator regains MODERATOR",
        ) {
            val fake = FakeBreakoutLiveKitAdminClient()
            val moderator = createTestMember("bo-rejoin-mod@example.org")
            val (roomId, _) = createTestParentRoom(fake, moderator)
            val countBefore =
                transaction { ConferenceParticipationTable.selectAll().where { ConferenceParticipationTable.roomId eq roomId }.count() }

            testApplication {
                application {
                    install(StatusPages) { installConferenceBreakoutExceptionHandlers() }
                    routing { registerConferenceBreakoutTestRoutes(fake) }
                }
                val response = client.post("/test/rejoin-main-room-token?roomId=$roomId") { header("X-Member-Id", moderator.toString()) }
                response.status shouldBe HttpStatusCode.OK
                val parts = response.bodyAsText().split("|")
                parts[3] shouldBe "MODERATOR"
            }
            val countAfter =
                transaction { ConferenceParticipationTable.selectAll().where { ConferenceParticipationTable.roomId eq roomId }.count() }
            countAfter shouldBe countBefore
        }

        test("rejoinMainRoomToken: rejected with Forbidden for a caller with no open participation in the room") {
            val fake = FakeBreakoutLiveKitAdminClient()
            val moderator = createTestMember("bo-rejoin-forbid-mod@example.org")
            val outsider = createTestMember("bo-rejoin-forbid-outsider@example.org")
            val (roomId, _) = createTestParentRoom(fake, moderator)

            testApplication {
                application {
                    install(StatusPages) { installConferenceBreakoutExceptionHandlers() }
                    routing { registerConferenceBreakoutTestRoutes(fake) }
                }
                client
                    .post("/test/rejoin-main-room-token?roomId=$roomId") {
                        header("X-Member-Id", outsider.toString())
                    }.status shouldBe HttpStatusCode.Forbidden
            }
        }

        // ── moderator-only gate (mandatory tamper case) ───────────────────

        test(
            "createBreakoutRooms/assignParticipants/recallAll: a non-moderator, non-privileged caller is rejected with Forbidden and causes zero DB writes and zero LiveKit calls",
        ) {
            val fake = FakeBreakoutLiveKitAdminClient()
            val moderator = createTestMember("bo-tamper-nonmod-mod@example.org")
            val nonModerator = createTestMember("bo-tamper-nonmod-other@example.org")
            val (roomId, _) = createTestParentRoom(fake, moderator, listOf(nonModerator))

            testApplication {
                application {
                    install(StatusPages) { installConferenceBreakoutExceptionHandlers() }
                    routing { registerConferenceBreakoutTestRoutes(fake) }
                }
                client
                    .post("/test/create-breakout-rooms?roomId=$roomId&roomCount=2") {
                        header("X-Member-Id", nonModerator.toString())
                    }.status shouldBe HttpStatusCode.Forbidden
                // Only the fixture's own createRoom call for the parent room -- zero breakout rooms.
                fake.createRoomCallCount shouldBe 1
                transaction {
                    ConferenceBreakoutRoomTable.selectAll().where { ConferenceBreakoutRoomTable.parentRoomId eq roomId }.count()
                } shouldBe 0L

                // Seed one real batch (as the moderator) so assignParticipants/recallAll have something to target.
                client.post("/test/create-breakout-rooms?roomId=$roomId&roomCount=1") { header("X-Member-Id", moderator.toString()) }
                val createCountAfterSeed = fake.createRoomCallCount

                client
                    .post("/test/assign-participants?roomId=$roomId&memberId=$nonModerator&breakoutIndex=0") {
                        header("X-Member-Id", nonModerator.toString())
                    }.status shouldBe HttpStatusCode.Forbidden
                client
                    .post("/test/recall-all?roomId=$roomId") {
                        header("X-Member-Id", nonModerator.toString())
                    }.status shouldBe HttpStatusCode.Forbidden
                fake.createRoomCallCount shouldBe createCountAfterSeed
                fake.deleteRoomCallCount shouldBe 0
            }
        }
    })

private fun cleanUpConferenceBreakoutTestData(memberIds: List<Uuid>) {
    if (memberIds.isEmpty()) return
    transaction {
        val roomIds =
            ConferenceRoomTable
                .selectAll()
                .where { ConferenceRoomTable.createdByMemberId inList memberIds }
                .map { it[ConferenceRoomTable.id] }
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
        OidcGuestProfileTable.deleteWhere { OidcGuestProfileTable.memberId inList memberIds }
        SessionTable.deleteWhere { SessionTable.memberId inList memberIds }
        AccountTable.deleteWhere { AccountTable.memberId inList memberIds }
        MemberTable.deleteWhere { MemberTable.id inList memberIds }
    }
}

private fun StatusPagesConfig.installConferenceBreakoutExceptionHandlers() {
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

private fun Route.registerConferenceBreakoutTestRoutes(
    liveKitAdminClient: LiveKitAdminClient,
    createRateLimiter: FederationInboxRateLimiter = FederationInboxRateLimiter(maxRequests = 10, window = 1.minutes),
    assignRateLimiter: FederationInboxRateLimiter = FederationInboxRateLimiter(maxRequests = 20, window = 1.minutes),
    recallRateLimiter: FederationInboxRateLimiter = FederationInboxRateLimiter(maxRequests = 10, window = 1.minutes),
    tokenRateLimiter: FederationInboxRateLimiter = FederationInboxRateLimiter(maxRequests = 30, window = 1.minutes),
) {
    fun service(call: ApplicationCall) =
        ConferenceBreakoutService(
            call,
            liveKitAdminClient,
            config = BREAKOUT_ENABLED_CONFIG,
            createRateLimiter = createRateLimiter,
            assignRateLimiter = assignRateLimiter,
            recallRateLimiter = recallRateLimiter,
            tokenRateLimiter = tokenRateLimiter,
        )
    post("/test/create-breakout-rooms") {
        val service = service(call)
        val q = call.request.queryParameters
        val roomCount = q["roomCount"]!!.toInt()
        val manualMemberId = q["manualMemberId"]
        val manualIndex = q["manualIndex"]?.toIntOrNull()
        // `manualRepeatCount` (default 1) repeats the SAME manual pin that many times -- lets
        // tests exercise the security-audit MAX_MANUAL_ASSIGNMENTS cap without seeding dozens of
        // distinct live participants.
        val manualRepeatCount = q["manualRepeatCount"]?.toIntOrNull() ?: 1
        val manualAssignments =
            if (manualMemberId != null && manualIndex != null) {
                List(manualRepeatCount) { ConferenceBreakoutAssignmentInput(manualMemberId, manualIndex) }
            } else {
                emptyList()
            }
        val plan = ConferenceBreakoutPlanInput(roomCount = roomCount, manualAssignments = manualAssignments)
        val dtos = service.createBreakoutRooms(q["roomId"]!!, plan)
        call.respondText(dtos.joinToString(";") { it.toPipeString() })
    }
    post("/test/assign-participants") {
        val service = service(call)
        val q = call.request.queryParameters
        // `repeatCount` (default 1) repeats the SAME (memberId, breakoutIndex) pair that many
        // times -- lets tests exercise the security-audit MAX_ASSIGNMENTS_PER_CALL cap and the
        // dedup-by-memberId fix without needing to seed dozens of distinct live participants.
        val repeatCount = q["repeatCount"]?.toIntOrNull() ?: 1
        val single = ConferenceBreakoutAssignmentInput(q["memberId"]!!, q["breakoutIndex"]!!.toInt())
        val dtos = service.assignParticipants(q["roomId"]!!, List(repeatCount) { single })
        call.respondText(dtos.joinToString(";") { it.toPipeString() })
    }
    post("/test/recall-all") {
        val service = service(call)
        val q = call.request.queryParameters
        val count = service.recallAll(q["roomId"]!!)
        call.respondText(count.toString())
    }
    get("/test/my-breakout-assignment") {
        val service = service(call)
        val q = call.request.queryParameters
        val dto = service.getMyBreakoutAssignment(q["roomId"]!!).singleOrNull()
        call.respondText(dto?.toPipeString() ?: "none")
    }
    post("/test/request-breakout-join-token") {
        val service = service(call)
        val q = call.request.queryParameters
        val dto = service.requestBreakoutJoinToken(q["breakoutRoomId"]!!)
        call.respondText(dto.toPipeString())
    }
    post("/test/return-to-main-room") {
        val service = service(call)
        val q = call.request.queryParameters
        service.returnToMainRoom(q["breakoutRoomId"]!!)
        call.respondText("ok")
    }
    post("/test/rejoin-main-room-token") {
        val service = service(call)
        val q = call.request.queryParameters
        val dto = service.rejoinMainRoomToken(q["roomId"]!!)
        call.respondText(dto.toPipeString())
    }
}

/** id|parentRoomId|label|isOpen|assignedMemberIds(comma-joined) */
private fun ConferenceBreakoutRoomDto.toPipeString(): String =
    "$id|$parentRoomId|$label|${closedAt == null}|${assignedMemberIds.joinToString(",")}"

/** breakoutRoomId|breakoutRoomLabel */
private fun ConferenceBreakoutAssignmentDto.toPipeString(): String = "$breakoutRoomId|$breakoutRoomLabel"

/** roomId|livekitRoomName|identity|role|hasToken|expiresAt */
private fun ConferenceJoinTokenDto.toPipeString(): String = "$roomId|$livekitRoomName|$identity|$role|${token.isNotBlank()}|$expiresAt"
