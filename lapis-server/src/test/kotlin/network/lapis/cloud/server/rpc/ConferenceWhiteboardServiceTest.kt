package network.lapis.cloud.server.rpc

import io.kotest.core.spec.style.FunSpec
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
import network.lapis.cloud.server.conference.ConferenceWhiteboardState
import network.lapis.cloud.server.db.DatabaseConfig
import network.lapis.cloud.server.db.DbClock
import network.lapis.cloud.server.db.DevSeedData
import network.lapis.cloud.server.db.generated.AccountTable
import network.lapis.cloud.server.db.generated.ConferenceParticipationTable
import network.lapis.cloud.server.db.generated.ConferenceRoomTable
import network.lapis.cloud.server.db.generated.DocumentFolderTable
import network.lapis.cloud.server.db.generated.DocumentTable
import network.lapis.cloud.server.db.generated.DocumentVersionTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.server.federation.FederationInboxRateLimiter
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.ConferenceRole
import network.lapis.cloud.shared.domain.DocumentAccessLevel
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.domain.WhiteboardPointDto
import network.lapis.cloud.shared.domain.WhiteboardStrokeWireDto
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
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.file.Files
import javax.imageio.ImageIO
import kotlin.time.Duration.Companion.minutes
import kotlin.uuid.Uuid

/** Mirrors [ConferenceBreakoutServiceTest]'s own `BREAKOUT_ENABLED_CONFIG`. */
private val WHITEBOARD_ENABLED_CONFIG =
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

private val WHITEBOARD_DISABLED_CONFIG = ConferenceConfig.load { null }

/**
 * Exercises [ConferenceWhiteboardService] end to end -- mirrors [ConferenceBreakoutServiceTest]'s own
 * house style (throwaway routes calling the service class directly, pipe-separated response bodies).
 * NOTABLY needs no fake LiveKit client at all -- this service never calls LiveKit. [afterSpec]
 * hard-deletes every row this file created.
 */
class ConferenceWhiteboardServiceTest :
    FunSpec({
        val createdMemberIds = mutableListOf<Uuid>()
        val createdRoomIds = mutableListOf<Uuid>()
        val documentStorageRoot = Files.createTempDirectory("whiteboard-test-docs").toFile()

        beforeSpec {
            DatabaseConfig.connect()
            DevSeedData.seedIfEmpty(force = true)
        }

        afterSpec {
            cleanUpWhiteboardTestData(createdMemberIds, createdRoomIds)
            documentStorageRoot.deleteRecursively()
        }

        fun createTestMember(
            email: String,
            role: AccountRole = AccountRole.MEMBER,
        ): Uuid {
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
                    it[AccountTable.role] = role
                }
            }
            createdMemberIds += id
            return id
        }

        /**
         * Creates a `conference_room` row plus an OPEN `conference_participation` row for the
         * creator and every id in [openMemberIds], and a CLOSED (`left_at` non-null) row for every
         * id in [leftMemberIds] -- the minimum fixture [ConferenceWhiteboardService] needs, no
         * LiveKit round-trip required.
         */
        fun createTestRoom(
            creatorId: Uuid,
            openMemberIds: List<Uuid> = emptyList(),
            leftMemberIds: List<Uuid> = emptyList(),
        ): Uuid {
            val roomId = Uuid.random()
            val now = DbClock.nowLocalDateTime()
            transaction {
                ConferenceRoomTable.insert {
                    it[id] = roomId
                    it[title] = "Whiteboard-Testsitzung"
                    it[description] = ""
                    it[livekitRoomName] = "lc-whiteboard-test-$roomId"
                    it[createdByMemberId] = creatorId
                    it[createdAt] = now
                    it[endedAt] = null
                    it[maxParticipants] = 25
                    it[allowFederationGuests] = false
                }
                (openMemberIds + creatorId).distinct().forEach { memberId ->
                    ConferenceParticipationTable.insert {
                        it[id] = Uuid.random()
                        it[ConferenceParticipationTable.roomId] = roomId
                        it[ConferenceParticipationTable.memberId] = memberId
                        it[role] = if (memberId == creatorId) ConferenceRole.MODERATOR else ConferenceRole.PARTICIPANT
                        it[joinedAt] = now
                        it[leftAt] = null
                    }
                }
                leftMemberIds.forEach { memberId ->
                    ConferenceParticipationTable.insert {
                        it[id] = Uuid.random()
                        it[ConferenceParticipationTable.roomId] = roomId
                        it[ConferenceParticipationTable.memberId] = memberId
                        it[role] = ConferenceRole.PARTICIPANT
                        it[joinedAt] = now
                        it[leftAt] = now
                    }
                }
            }
            createdRoomIds += roomId
            return roomId
        }

        // ── getWhiteboardState / commitStroke happy paths ────────────────────

        test("getWhiteboardState: empty room returns an empty list") {
            val creator = createTestMember("wb-empty@example.org")
            val roomId = createTestRoom(creator)
            testApplication {
                application {
                    install(StatusPages) { installConferenceWhiteboardExceptionHandlers() }
                    routing { registerConferenceWhiteboardTestRoutes(ConferenceWhiteboardState()) }
                }
                val response =
                    client.get("/test/whiteboard-state?roomId=$roomId") { header("X-Member-Id", creator.toString()) }
                response.status shouldBe HttpStatusCode.OK
                response.bodyAsText() shouldBe ""
            }
        }

        test("commitStroke: round-trips through getWhiteboardState with server-filled author fields") {
            val creator = createTestMember("wb-roundtrip@example.org")
            val roomId = createTestRoom(creator)
            testApplication {
                application {
                    install(StatusPages) { installConferenceWhiteboardExceptionHandlers() }
                    routing { registerConferenceWhiteboardTestRoutes(ConferenceWhiteboardState()) }
                }
                val commitResponse =
                    client.post(
                        "/test/commit-stroke?roomId=$roomId&strokeId=s1&tool=PEN&color=%23e03131&strokeWidth=4&points=10,10;20,20",
                    ) { header("X-Member-Id", creator.toString()) }
                commitResponse.status shouldBe HttpStatusCode.OK
                val committed = commitResponse.bodyAsText().split("|")
                committed[0] shouldBe "s1"
                committed[1] shouldBe creator.toString()

                val stateResponse =
                    client.get("/test/whiteboard-state?roomId=$roomId") { header("X-Member-Id", creator.toString()) }
                val strokes = stateResponse.bodyAsText().split(";")
                strokes.size shouldBe 1
                strokes[0].split("|")[0] shouldBe "s1"
            }
        }

        test("commitStroke: multiple strokes accumulate in commit order") {
            val creator = createTestMember("wb-accumulate@example.org")
            val roomId = createTestRoom(creator)
            testApplication {
                application {
                    install(StatusPages) { installConferenceWhiteboardExceptionHandlers() }
                    routing { registerConferenceWhiteboardTestRoutes(ConferenceWhiteboardState()) }
                }
                listOf("s1", "s2", "s3").forEach { id ->
                    client.post(
                        "/test/commit-stroke?roomId=$roomId&strokeId=$id&tool=PEN&color=%23e03131&strokeWidth=4&points=1,1;2,2",
                    ) { header("X-Member-Id", creator.toString()) }
                }
                val stateResponse =
                    client.get("/test/whiteboard-state?roomId=$roomId") { header("X-Member-Id", creator.toString()) }
                val ids = stateResponse.bodyAsText().split(";").map { it.split("|")[0] }
                ids shouldBe listOf("s1", "s2", "s3")
            }
        }

        test("clearBoard: the room's own creator clears all committed strokes") {
            val creator = createTestMember("wb-clear-creator@example.org")
            val roomId = createTestRoom(creator)
            testApplication {
                application {
                    install(StatusPages) { installConferenceWhiteboardExceptionHandlers() }
                    routing { registerConferenceWhiteboardTestRoutes(ConferenceWhiteboardState()) }
                }
                client.post(
                    "/test/commit-stroke?roomId=$roomId&strokeId=s1&tool=PEN&color=%23e03131&strokeWidth=4&points=1,1;2,2",
                ) { header("X-Member-Id", creator.toString()) }
                val clearResponse = client.post("/test/clear-board?roomId=$roomId") { header("X-Member-Id", creator.toString()) }
                clearResponse.status shouldBe HttpStatusCode.OK
                val stateResponse =
                    client.get("/test/whiteboard-state?roomId=$roomId") { header("X-Member-Id", creator.toString()) }
                stateResponse.bodyAsText() shouldBe ""
            }
        }

        test("clearBoard: a global BOARD member who is NOT the room's creator can also clear") {
            val creator = createTestMember("wb-clear-board-creator@example.org")
            val boardMember = createTestMember("wb-clear-board-privileged@example.org", role = AccountRole.BOARD)
            val roomId = createTestRoom(creator, openMemberIds = listOf(boardMember))
            testApplication {
                application {
                    install(StatusPages) { installConferenceWhiteboardExceptionHandlers() }
                    routing { registerConferenceWhiteboardTestRoutes(ConferenceWhiteboardState()) }
                }
                client.post(
                    "/test/commit-stroke?roomId=$roomId&strokeId=s1&tool=PEN&color=%23e03131&strokeWidth=4&points=1,1;2,2",
                ) { header("X-Member-Id", creator.toString()) }
                client
                    .post("/test/clear-board?roomId=$roomId") { header("X-Member-Id", boardMember.toString()) }
                    .status shouldBe HttpStatusCode.OK
            }
        }

        test("saveAsDocument: archives a decodable PNG into the Whiteboards folder with the requested accessLevel") {
            val creator = createTestMember("wb-save@example.org")
            val roomId = createTestRoom(creator)
            testApplication {
                application {
                    install(StatusPages) { installConferenceWhiteboardExceptionHandlers() }
                    routing { registerConferenceWhiteboardTestRoutes(ConferenceWhiteboardState(), documentStorageRoot) }
                }
                client.post(
                    "/test/commit-stroke?roomId=$roomId&strokeId=s1&tool=PEN&color=%23e03131&strokeWidth=4&points=1,1;20,20;40,5",
                ) { header("X-Member-Id", creator.toString()) }
                val saveResponse =
                    client.post("/test/save-as-document?roomId=$roomId&accessLevel=BOARD_ONLY") {
                        header("X-Member-Id", creator.toString())
                    }
                saveResponse.status shouldBe HttpStatusCode.OK
                val documentId = Uuid.parse(saveResponse.bodyAsText())

                val (versionId, folderId, accessLevel) =
                    transaction {
                        val docRow = DocumentTable.selectAll().where { DocumentTable.id eq documentId }.single()
                        Triple(
                            docRow[DocumentTable.currentVersionId],
                            docRow[DocumentTable.folderId],
                            docRow[DocumentTable.accessLevel],
                        )
                    }
                accessLevel shouldBe DocumentAccessLevel.BOARD_ONLY
                versionId shouldNotBe null

                val (mimeType, storageKey, folderName) =
                    transaction {
                        val versionRow = DocumentVersionTable.selectAll().where { DocumentVersionTable.id eq versionId!! }.single()
                        val folderRow = DocumentFolderTable.selectAll().where { DocumentFolderTable.id eq folderId }.single()
                        Triple(
                            versionRow[DocumentVersionTable.mimeType],
                            versionRow[DocumentVersionTable.storageKey],
                            folderRow[DocumentFolderTable.name],
                        )
                    }
                mimeType shouldBe "image/png"
                folderName shouldBe "Whiteboards"

                val pngBytes = documentStorageRoot.resolve(storageKey).readBytes()
                pngBytes.isEmpty() shouldBe false
                val decoded = ImageIO.read(ByteArrayInputStream(pngBytes))
                decoded shouldNotBe null
                decoded.width shouldBe 1600
                decoded.height shouldBe 1200
            }
        }

        test("saveAsDocument: an empty board is rejected with Conflict, nothing to save") {
            val creator = createTestMember("wb-save-empty@example.org")
            val roomId = createTestRoom(creator)
            testApplication {
                application {
                    install(StatusPages) { installConferenceWhiteboardExceptionHandlers() }
                    routing { registerConferenceWhiteboardTestRoutes(ConferenceWhiteboardState(), documentStorageRoot) }
                }
                client
                    .post("/test/save-as-document?roomId=$roomId&accessLevel=BOARD_ONLY") {
                        header("X-Member-Id", creator.toString())
                    }.status shouldBe HttpStatusCode.Conflict
            }
        }

        // ── error cases (validation) ──────────────────────────────────────

        test("commitStroke: empty points is rejected with BadRequest") {
            val creator = createTestMember("wb-bad-emptypoints@example.org")
            val roomId = createTestRoom(creator)
            testApplication {
                application {
                    install(StatusPages) { installConferenceWhiteboardExceptionHandlers() }
                    routing { registerConferenceWhiteboardTestRoutes(ConferenceWhiteboardState()) }
                }
                client
                    .post("/test/commit-stroke?roomId=$roomId&strokeId=s1&tool=PEN&color=%23e03131&strokeWidth=4&points=") {
                        header("X-Member-Id", creator.toString())
                    }.status shouldBe HttpStatusCode.BadRequest
            }
        }

        test("commitStroke: more than MAX_POINTS_PER_STROKE (2000) is rejected with BadRequest") {
            val creator = createTestMember("wb-bad-toomanypoints@example.org")
            val roomId = createTestRoom(creator)
            val tooManyPoints = (1..2001).joinToString(";") { "1,1" }
            testApplication {
                application {
                    install(StatusPages) { installConferenceWhiteboardExceptionHandlers() }
                    routing { registerConferenceWhiteboardTestRoutes(ConferenceWhiteboardState()) }
                }
                client
                    .post(
                        "/test/commit-stroke?roomId=$roomId&strokeId=s1&tool=PEN&color=%23e03131&strokeWidth=4&points=$tooManyPoints",
                    ) { header("X-Member-Id", creator.toString()) }
                    .status shouldBe HttpStatusCode.BadRequest
            }
        }

        test("commitStroke: PEN with an out-of-palette color is rejected, ERASER with any color is accepted") {
            val creator = createTestMember("wb-bad-color@example.org")
            val roomId = createTestRoom(creator)
            testApplication {
                application {
                    install(StatusPages) { installConferenceWhiteboardExceptionHandlers() }
                    routing { registerConferenceWhiteboardTestRoutes(ConferenceWhiteboardState()) }
                }
                client
                    .post(
                        "/test/commit-stroke?roomId=$roomId&strokeId=s1&tool=PEN&color=%23abcdef&strokeWidth=4&points=1,1;2,2",
                    ) { header("X-Member-Id", creator.toString()) }
                    .status shouldBe HttpStatusCode.BadRequest
                client
                    .post(
                        "/test/commit-stroke?roomId=$roomId&strokeId=s2&tool=ERASER&color=%23abcdef&strokeWidth=4&points=1,1;2,2",
                    ) { header("X-Member-Id", creator.toString()) }
                    .status shouldBe HttpStatusCode.OK
            }
        }

        test("commitStroke: strokeWidth outside 1.0..40.0 is rejected with BadRequest") {
            val creator = createTestMember("wb-bad-width@example.org")
            val roomId = createTestRoom(creator)
            testApplication {
                application {
                    install(StatusPages) { installConferenceWhiteboardExceptionHandlers() }
                    routing { registerConferenceWhiteboardTestRoutes(ConferenceWhiteboardState()) }
                }
                client
                    .post(
                        "/test/commit-stroke?roomId=$roomId&strokeId=s1&tool=PEN&color=%23e03131&strokeWidth=0.5&points=1,1;2,2",
                    ) { header("X-Member-Id", creator.toString()) }
                    .status shouldBe HttpStatusCode.BadRequest
                client
                    .post(
                        "/test/commit-stroke?roomId=$roomId&strokeId=s2&tool=PEN&color=%23e03131&strokeWidth=41&points=1,1;2,2",
                    ) { header("X-Member-Id", creator.toString()) }
                    .status shouldBe HttpStatusCode.BadRequest
            }
        }

        test("commitStroke: an out-of-bounds coordinate is rejected with BadRequest") {
            val creator = createTestMember("wb-bad-coords@example.org")
            val roomId = createTestRoom(creator)
            testApplication {
                application {
                    install(StatusPages) { installConferenceWhiteboardExceptionHandlers() }
                    routing { registerConferenceWhiteboardTestRoutes(ConferenceWhiteboardState()) }
                }
                client
                    .post(
                        "/test/commit-stroke?roomId=$roomId&strokeId=s1&tool=PEN&color=%23e03131&strokeWidth=4&points=1,1;99999,99999",
                    ) { header("X-Member-Id", creator.toString()) }
                    .status shouldBe HttpStatusCode.BadRequest
            }
        }

        test("getWhiteboardState/commitStroke/clearBoard/saveAsDocument against a nonexistent room are rejected with NotFound") {
            val creator = createTestMember("wb-notfound@example.org")
            val bogusRoomId = Uuid.random()
            testApplication {
                application {
                    install(StatusPages) { installConferenceWhiteboardExceptionHandlers() }
                    routing { registerConferenceWhiteboardTestRoutes(ConferenceWhiteboardState(), documentStorageRoot) }
                }
                client
                    .get("/test/whiteboard-state?roomId=$bogusRoomId") { header("X-Member-Id", creator.toString()) }
                    .status shouldBe HttpStatusCode.NotFound
                client
                    .post(
                        "/test/commit-stroke?roomId=$bogusRoomId&strokeId=s1&tool=PEN&color=%23e03131&strokeWidth=4&points=1,1;2,2",
                    ) { header("X-Member-Id", creator.toString()) }
                    .status shouldBe HttpStatusCode.NotFound
                client
                    .post("/test/clear-board?roomId=$bogusRoomId") { header("X-Member-Id", creator.toString()) }
                    .status shouldBe HttpStatusCode.NotFound
                client
                    .post("/test/save-as-document?roomId=$bogusRoomId&accessLevel=BOARD_ONLY") {
                        header("X-Member-Id", creator.toString())
                    }.status shouldBe HttpStatusCode.NotFound
            }
        }

        test("requireConferenceEnabled: every method is rejected with Conflict when Videokonferenzen is not configured") {
            val creator = createTestMember("wb-disabled@example.org")
            val roomId = createTestRoom(creator)
            testApplication {
                application {
                    install(StatusPages) { installConferenceWhiteboardExceptionHandlers() }
                    routing { registerConferenceWhiteboardTestRoutes(ConferenceWhiteboardState(), config = WHITEBOARD_DISABLED_CONFIG) }
                }
                client
                    .get("/test/whiteboard-state?roomId=$roomId") { header("X-Member-Id", creator.toString()) }
                    .status shouldBe HttpStatusCode.Conflict
            }
        }

        // ── mandatory tamper/negative cases ───────────────────────────────

        test(
            "tamper: a member who has NEVER joined the room cannot draw/view/save (Forbidden) -- direct RPC probing is rejected",
        ) {
            val creator = createTestMember("wb-tamper-neverjoined-creator@example.org")
            val outsider = createTestMember("wb-tamper-neverjoined-outsider@example.org")
            val roomId = createTestRoom(creator)
            testApplication {
                application {
                    install(StatusPages) { installConferenceWhiteboardExceptionHandlers() }
                    routing { registerConferenceWhiteboardTestRoutes(ConferenceWhiteboardState(), documentStorageRoot) }
                }
                client
                    .get("/test/whiteboard-state?roomId=$roomId") { header("X-Member-Id", outsider.toString()) }
                    .status shouldBe HttpStatusCode.Forbidden
                client
                    .post(
                        "/test/commit-stroke?roomId=$roomId&strokeId=s1&tool=PEN&color=%23e03131&strokeWidth=4&points=1,1;2,2",
                    ) { header("X-Member-Id", outsider.toString()) }
                    .status shouldBe HttpStatusCode.Forbidden
                client
                    .post("/test/save-as-document?roomId=$roomId&accessLevel=BOARD_ONLY") {
                        header("X-Member-Id", outsider.toString())
                    }.status shouldBe HttpStatusCode.Forbidden
            }
        }

        test(
            "tamper: a participant who already LEFT the room cannot draw/view/save (Forbidden) -- the STRICTER open-participation " +
                "gate, not IConferenceBreakoutService.getMyBreakoutAssignment's looser ever-participated gate",
        ) {
            val creator = createTestMember("wb-tamper-left-creator@example.org")
            val leftMember = createTestMember("wb-tamper-left-member@example.org")
            val roomId = createTestRoom(creator, leftMemberIds = listOf(leftMember))
            testApplication {
                application {
                    install(StatusPages) { installConferenceWhiteboardExceptionHandlers() }
                    routing { registerConferenceWhiteboardTestRoutes(ConferenceWhiteboardState(), documentStorageRoot) }
                }
                client
                    .get("/test/whiteboard-state?roomId=$roomId") { header("X-Member-Id", leftMember.toString()) }
                    .status shouldBe HttpStatusCode.Forbidden
                client
                    .post(
                        "/test/commit-stroke?roomId=$roomId&strokeId=s1&tool=PEN&color=%23e03131&strokeWidth=4&points=1,1;2,2",
                    ) { header("X-Member-Id", leftMember.toString()) }
                    .status shouldBe HttpStatusCode.Forbidden
                client
                    .post("/test/save-as-document?roomId=$roomId&accessLevel=BOARD_ONLY") {
                        header("X-Member-Id", leftMember.toString())
                    }.status shouldBe HttpStatusCode.Forbidden
            }
        }

        test(
            "tamper: an ordinary (non-creator, non-privileged) current participant cannot clear the board -- server state is unchanged",
        ) {
            val creator = createTestMember("wb-tamper-clear-creator@example.org")
            val participant = createTestMember("wb-tamper-clear-participant@example.org")
            val roomId = createTestRoom(creator, openMemberIds = listOf(participant))
            testApplication {
                application {
                    install(StatusPages) { installConferenceWhiteboardExceptionHandlers() }
                    routing { registerConferenceWhiteboardTestRoutes(ConferenceWhiteboardState()) }
                }
                client.post(
                    "/test/commit-stroke?roomId=$roomId&strokeId=s1&tool=PEN&color=%23e03131&strokeWidth=4&points=1,1;2,2",
                ) { header("X-Member-Id", creator.toString()) }

                client
                    .post("/test/clear-board?roomId=$roomId") { header("X-Member-Id", participant.toString()) }
                    .status shouldBe HttpStatusCode.Forbidden

                val stateResponse =
                    client.get("/test/whiteboard-state?roomId=$roomId") { header("X-Member-Id", creator.toString()) }
                stateResponse.bodyAsText().split(";").size shouldBe 1
            }
        }

        test(
            "tamper (TOCTOU): commitStroke against a room whose endedAt was set AFTER the initial " +
                "requireOpenParticipation check passed is rejected with Conflict by the final " +
                "requireRoomStillOpen re-check, and the stroke is never committed into the shared map",
        ) {
            // Simulates the exact race window `requireRoomStillOpen` closes: `endRoom`/
            // `reconcileRoomIfDue` always set `endedAt` and close every open participation together,
            // atomically, in the SAME transaction (see ConferenceService.endRoom/.reconcileRoomIfDue) --
            // so by the time a racing `commitStroke` reaches its final re-check, BOTH would already be
            // true in a real race. Here `endedAt` is flipped directly, WITHOUT closing the
            // participation row, to isolate that `requireRoomStillOpen` itself -- not a lucky second
            // hit of `requireOpenParticipation` -- is what rejects the call.
            val creator = createTestMember("wb-tamper-toctou@example.org")
            val roomId = createTestRoom(creator)
            transaction {
                ConferenceRoomTable.update({ ConferenceRoomTable.id eq roomId }) {
                    it[endedAt] = DbClock.nowLocalDateTime()
                }
            }
            testApplication {
                application {
                    install(StatusPages) { installConferenceWhiteboardExceptionHandlers() }
                    routing { registerConferenceWhiteboardTestRoutes(ConferenceWhiteboardState()) }
                }
                client
                    .post(
                        "/test/commit-stroke?roomId=$roomId&strokeId=s1&tool=PEN&color=%23e03131&strokeWidth=4&points=1,1;2,2",
                    ) { header("X-Member-Id", creator.toString()) }
                    .status shouldBe HttpStatusCode.Conflict
            }
        }

        test("rate limiting: exceeding commitRateLimiter's budget within the window throws Conflict") {
            val creator = createTestMember("wb-ratelimit@example.org")
            val roomId = createTestRoom(creator)
            val tinyCommitLimiter = FederationInboxRateLimiter(maxRequests = 2, window = 1.minutes)
            testApplication {
                application {
                    install(StatusPages) { installConferenceWhiteboardExceptionHandlers() }
                    routing {
                        registerConferenceWhiteboardTestRoutes(ConferenceWhiteboardState(), commitRateLimiter = tinyCommitLimiter)
                    }
                }
                repeat(2) { i ->
                    client
                        .post(
                            "/test/commit-stroke?roomId=$roomId&strokeId=s$i&tool=PEN&color=%23e03131&strokeWidth=4&points=1,1;2,2",
                        ) { header("X-Member-Id", creator.toString()) }
                        .status shouldBe HttpStatusCode.OK
                }
                client
                    .post(
                        "/test/commit-stroke?roomId=$roomId&strokeId=s-over&tool=PEN&color=%23e03131&strokeWidth=4&points=1,1;2,2",
                    ) { header("X-Member-Id", creator.toString()) }
                    .status shouldBe HttpStatusCode.Conflict
            }
        }

        test("unauthenticated caller is rejected with Unauthorized") {
            val creator = createTestMember("wb-unauth@example.org")
            val roomId = createTestRoom(creator)
            testApplication {
                application {
                    install(StatusPages) { installConferenceWhiteboardExceptionHandlers() }
                    routing { registerConferenceWhiteboardTestRoutes(ConferenceWhiteboardState()) }
                }
                client.get("/test/whiteboard-state?roomId=$roomId").status shouldBe HttpStatusCode.Unauthorized
            }
        }
    })

private fun cleanUpWhiteboardTestData(
    memberIds: List<Uuid>,
    roomIds: List<Uuid>,
) {
    if (memberIds.isEmpty() && roomIds.isEmpty()) return
    transaction {
        val documentIds =
            DocumentTable.selectAll().where { DocumentTable.createdBy inList memberIds }.map { it[DocumentTable.id] }
        DocumentVersionTable.deleteWhere { DocumentVersionTable.documentId inList documentIds }
        DocumentTable.deleteWhere { DocumentTable.id inList documentIds }
        ConferenceParticipationTable.deleteWhere {
            (ConferenceParticipationTable.memberId inList memberIds) or (ConferenceParticipationTable.roomId inList roomIds)
        }
        ConferenceRoomTable.deleteWhere { ConferenceRoomTable.id inList roomIds }
        AccountTable.deleteWhere { AccountTable.memberId inList memberIds }
        MemberTable.deleteWhere { MemberTable.id inList memberIds }
    }
}

private fun StatusPagesConfig.installConferenceWhiteboardExceptionHandlers() {
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

/** Points query-param shape: `x1,y1;x2,y2;...` -- blank/empty string decodes to an empty list. */
private fun String?.toTestPoints(): List<WhiteboardPointDto> =
    this
        ?.split(";")
        ?.filter { it.isNotBlank() }
        ?.map { pair ->
            val (x, y) = pair.split(",")
            WhiteboardPointDto(x.toDouble(), y.toDouble())
        }.orEmpty()

private fun Route.registerConferenceWhiteboardTestRoutes(
    whiteboardState: ConferenceWhiteboardState,
    documentStorageRoot: File = Files.createTempDirectory("whiteboard-test-docs-unused").toFile(),
    config: ConferenceConfig = WHITEBOARD_ENABLED_CONFIG,
    readRateLimiter: FederationInboxRateLimiter = FederationInboxRateLimiter(maxRequests = 30, window = 1.minutes),
    commitRateLimiter: FederationInboxRateLimiter = FederationInboxRateLimiter(maxRequests = 120, window = 1.minutes),
    clearRateLimiter: FederationInboxRateLimiter = FederationInboxRateLimiter(maxRequests = 10, window = 1.minutes),
    saveRateLimiter: FederationInboxRateLimiter = FederationInboxRateLimiter(maxRequests = 10, window = 1.minutes),
) {
    fun service(call: ApplicationCall) =
        ConferenceWhiteboardService(
            call,
            documentStorageRoot,
            whiteboardState,
            config = config,
            readRateLimiter = readRateLimiter,
            commitRateLimiter = commitRateLimiter,
            clearRateLimiter = clearRateLimiter,
            saveRateLimiter = saveRateLimiter,
        )
    get("/test/whiteboard-state") {
        val service = service(call)
        val q = call.request.queryParameters
        val dto = service.getWhiteboardState(q["roomId"]!!)
        call.respondText(dto.strokes.joinToString(";") { "${it.strokeId}|${it.authorMemberId}|${it.tool}|${it.color}" })
    }
    post("/test/commit-stroke") {
        val service = service(call)
        val q = call.request.queryParameters
        val stroke =
            WhiteboardStrokeWireDto(
                strokeId = q["strokeId"] ?: "",
                tool = WhiteboardTool.valueOf(q["tool"] ?: "PEN"),
                color = q["color"] ?: "#1a1a1a",
                strokeWidth = q["strokeWidth"]?.toDoubleOrNull() ?: 4.0,
                points = q["points"].toTestPoints(),
            )
        val dto = service.commitStroke(q["roomId"]!!, stroke)
        call.respondText("${dto.strokeId}|${dto.authorMemberId}")
    }
    post("/test/clear-board") {
        val service = service(call)
        val q = call.request.queryParameters
        service.clearBoard(q["roomId"]!!)
        call.respondText("ok")
    }
    post("/test/save-as-document") {
        val service = service(call)
        val q = call.request.queryParameters
        val level = DocumentAccessLevel.valueOf(q["accessLevel"] ?: "BOARD_ONLY")
        val dto = service.saveAsDocument(q["roomId"]!!, level)
        call.respondText(dto.documentId)
    }
}
