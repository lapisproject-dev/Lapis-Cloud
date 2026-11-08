package network.lapis.cloud.server.rpc

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.response.respondText
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.datetime.LocalDate
import kotlinx.serialization.json.Json
import network.lapis.cloud.server.conference.ConferenceConfig
import network.lapis.cloud.server.conference.ConferenceRecordingConfig
import network.lapis.cloud.server.db.DbClock
import network.lapis.cloud.server.db.DevSeedData
import network.lapis.cloud.server.db.generated.AccountTable
import network.lapis.cloud.server.db.generated.AuditLogEntryTable
import network.lapis.cloud.server.db.generated.ConferenceParticipationTable
import network.lapis.cloud.server.db.generated.ConferenceRecordingTable
import network.lapis.cloud.server.db.generated.ConferenceRoomTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.server.security.LoginRateLimiter
import network.lapis.cloud.server.security.SessionStore
import network.lapis.cloud.server.testdb.TestDatabase
import network.lapis.cloud.server.testdb.installLaneGuards
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.ConferenceBroadcastView
import network.lapis.cloud.shared.domain.ConferenceRecordingDto
import network.lapis.cloud.shared.domain.ConferenceRecordingLayout
import network.lapis.cloud.shared.domain.ConferenceRole
import network.lapis.cloud.shared.domain.DocumentAccessLevel
import network.lapis.cloud.shared.domain.MemberStatus
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import java.io.File
import java.nio.file.Files
import kotlin.uuid.Uuid

private val ENABLED_CONFERENCE_FOR_LAYOUT =
    ConferenceConfig.load { key ->
        when (key) {
            "LAPIS_LIVEKIT_URL" -> "ws://localhost:7880"
            "LAPIS_LIVEKIT_API_KEY" -> "test-livekit-key"
            "LAPIS_LIVEKIT_API_SECRET" -> "test-livekit-secret-at-least-32-bytes-long!!"
            else -> null
        }
    }

/**
 * Welle V1.9.294 -- the layout choice when a conference recording starts, on H2 AND PostgreSQL: all three layouts end up in the row, the DTO
 * and the audit entry; the old `startRecording` derives the layout from the room view; every refusal (shape, a person who is not in the room,
 * a stranger) and the ONE rate-limit budget shared by both methods; the chosen person's identity is only told to moderators.
 */
abstract class ConferenceRecordingLayoutScenarios(
    private val db: TestDatabase,
) : FunSpec({
        val createdMemberIds = mutableListOf<Uuid>()
        val createdRoomIds = mutableListOf<Uuid>()
        val json = Json { ignoreUnknownKeys = true }
        val hostRawRoot: File = Files.createTempDirectory("layout-test-raw").toFile()

        beforeSpec {
            db.activate()
            if (!db.isPostgres) DevSeedData.seedIfEmpty(force = true)
        }
        installLaneGuards(db = db)
        afterSpec {
            hostRawRoot.deleteRecursively()
            transaction {
                if (createdMemberIds.isNotEmpty()) {
                    AuditLogEntryTable.update({ AuditLogEntryTable.actorMemberId inList createdMemberIds }) { it[actorMemberId] = null }
                }
                createdRoomIds.forEach { id -> ConferenceRecordingTable.deleteWhere { roomId eq id } }
                createdRoomIds.forEach { id -> ConferenceParticipationTable.deleteWhere { roomId eq id } }
                createdRoomIds.forEach { id -> ConferenceRoomTable.deleteWhere { ConferenceRoomTable.id eq id } }
            }
            db.deactivate()
        }

        fun recordingConfig(): ConferenceRecordingConfig =
            ConferenceRecordingConfig.load { key ->
                when (key) {
                    "LAPIS_RECORDING_ENABLED" -> "true"
                    "LAPIS_EGRESS_OUTPUT_HOST_DIR" -> hostRawRoot.path
                    "LAPIS_EGRESS_OUTPUT_CONTAINER_DIR" -> "/out"
                    else -> null
                }
            }

        fun createMember(label: String): Uuid {
            val id = Uuid.random()
            transaction {
                MemberTable.insert {
                    it[MemberTable.id] = id
                    it[displayName] = "Layout Test $label"
                    it[email] = "layout-$label-$id@example.org"
                    it[status] = MemberStatus.ACTIVE
                    it[joinedAt] = LocalDate(2026, 1, 1)
                    it[membershipTierId] = null
                }
                AccountTable.insert {
                    it[AccountTable.id] = Uuid.random()
                    it[memberId] = id
                    it[role] = AccountRole.MEMBER
                }
            }
            createdMemberIds += id
            return id
        }

        fun createRoom(
            creator: Uuid,
            view: ConferenceBroadcastView = ConferenceBroadcastView.RASTER,
        ): Uuid {
            val id = Uuid.random()
            transaction {
                ConferenceRoomTable.insert {
                    it[ConferenceRoomTable.id] = id
                    it[title] = "LAYOUT-RAUM-$id"
                    it[description] = ""
                    it[livekitRoomName] = "lc-layout-$id"
                    it[createdByMemberId] = creator
                    it[createdAt] = DbClock.nowLocalDateTime()
                    it[maxParticipants] = 25
                    it[broadcastView] = view
                }
            }
            createdRoomIds += id
            return id
        }

        fun join(
            room: Uuid,
            member: Uuid,
            open: Boolean = true,
        ) {
            val now = DbClock.nowLocalDateTime()
            transaction {
                ConferenceParticipationTable.insert {
                    it[id] = Uuid.random()
                    it[roomId] = room
                    it[memberId] = member
                    it[role] = ConferenceRole.PARTICIPANT
                    it[joinedAt] = now
                    it[leftAt] = if (open) null else now
                }
            }
        }

        fun tokenOf(member: Uuid): String = SessionStore.createSession(member).rawToken

        /** Throwaway routes; every answer is the DTO JSON or `ERR:<ExceptionSimpleName>:<message>`. */
        fun withRoutes(
            limiter: LoginRateLimiter = LoginRateLimiter(maxFailures = 1000),
            block: suspend (client: HttpClient) -> Unit,
        ) {
            testApplication {
                application {
                    routing {
                        fun service(call: ApplicationCall) =
                            ConferenceRecordingService(
                                call = call,
                                ffmpegAvailable = true,
                                config = ENABLED_CONFERENCE_FOR_LAYOUT,
                                recordingConfig = recordingConfig(),
                                startRecordingRateLimiter = limiter,
                            )

                        suspend fun answer(
                            call: ApplicationCall,
                            action: suspend () -> ConferenceRecordingDto,
                        ) {
                            val body =
                                try {
                                    json.encodeToString(ConferenceRecordingDto.serializer(), action())
                                } catch (e: Exception) {
                                    "ERR:" + e::class.simpleName + ":" + e.message
                                }
                            call.respondText(body)
                        }
                        post("/start") {
                            answer(call) {
                                service(call).startRecording(
                                    roomId = call.request.queryParameters["roomId"]!!,
                                    accessLevel = DocumentAccessLevel.BOARD_ONLY,
                                )
                            }
                        }
                        post("/startWithLayout") {
                            val q = call.request.queryParameters
                            answer(call) {
                                service(call).startRecordingWithLayout(
                                    roomId = q["roomId"]!!,
                                    accessLevel = DocumentAccessLevel.BOARD_ONLY,
                                    layout = ConferenceRecordingLayout.valueOf(q["layout"]!!),
                                    singleParticipantIdentity = q["identity"],
                                )
                            }
                        }
                        post("/active") {
                            val body =
                                try {
                                    json.encodeToString(
                                        ConferenceRecordingDto.serializer(),
                                        service(call).getActiveRecording(call.request.queryParameters["roomId"]!!).single(),
                                    )
                                } catch (e: Exception) {
                                    "ERR:" + e::class.simpleName + ":" + e.message
                                }
                            call.respondText(body)
                        }
                    }
                }
                block(client)
            }
        }

        suspend fun HttpClient.start(
            room: Uuid,
            member: Uuid,
        ): String = post("/start?roomId=$room") { header("Authorization", "Bearer ${tokenOf(member)}") }.bodyAsText()

        suspend fun HttpClient.startWith(
            room: Uuid,
            member: Uuid,
            layout: ConferenceRecordingLayout,
            identity: String?,
        ): String =
            post("/startWithLayout?roomId=$room&layout=${layout.name}" + (identity?.let { "&identity=$it" } ?: "")) {
                header("Authorization", "Bearer ${tokenOf(member)}")
            }.bodyAsText()

        suspend fun HttpClient.active(
            room: Uuid,
            member: Uuid,
        ): String = post("/active?roomId=$room") { header("Authorization", "Bearer ${tokenOf(member)}") }.bodyAsText()

        fun parse(body: String): ConferenceRecordingDto = json.decodeFromString(ConferenceRecordingDto.serializer(), body)

        fun rowOf(room: Uuid) =
            transaction { ConferenceRecordingTable.selectAll().where { ConferenceRecordingTable.roomId eq room }.toList() }

        fun auditAfter(recording: String) =
            transaction {
                AuditLogEntryTable.selectAll().where { AuditLogEntryTable.entityId eq Uuid.parse(recording) }.single()[
                    AuditLogEntryTable.afterSnapshot,
                ]
            }

        test("GRID and SPEAKER: stored, in the DTO and in the audit entry; no identity") {
            val moderator = createMember("mod-1")
            withRoutes { client ->
                val gridRoom = createRoom(moderator)
                val grid = parse(client.startWith(gridRoom, moderator, ConferenceRecordingLayout.GRID, null))
                grid.layout shouldBe ConferenceRecordingLayout.GRID
                grid.singleParticipantIdentity shouldBe null
                rowOf(gridRoom).single()[ConferenceRecordingTable.layout] shouldBe ConferenceRecordingLayout.GRID
                auditAfter(grid.id) shouldBe """{"layout":"GRID"}"""

                val speakerRoom = createRoom(moderator)
                val speaker = parse(client.startWith(speakerRoom, moderator, ConferenceRecordingLayout.SPEAKER, null))
                speaker.layout shouldBe ConferenceRecordingLayout.SPEAKER
                // the layout is independent of the room view (the room is RASTER)
                rowOf(speakerRoom).single()[ConferenceRecordingTable.broadcastView] shouldBe ConferenceBroadcastView.RASTER
                rowOf(speakerRoom).single()[ConferenceRecordingTable.layout] shouldBe ConferenceRecordingLayout.SPEAKER
            }
        }

        test("SINGLE_PARTICIPANT: stored with the identity; audit holds the member UUID and no display name") {
            val moderator = createMember("mod-2")
            val chosen = createMember("chosen-2")
            val room = createRoom(moderator)
            join(room, chosen)
            withRoutes { client ->
                val dto = parse(client.startWith(room, moderator, ConferenceRecordingLayout.SINGLE_PARTICIPANT, chosen.toString()))
                dto.layout shouldBe ConferenceRecordingLayout.SINGLE_PARTICIPANT
                dto.singleParticipantIdentity shouldBe chosen.toString()
                val stored = rowOf(room).single()
                stored[ConferenceRecordingTable.layout] shouldBe ConferenceRecordingLayout.SINGLE_PARTICIPANT
                stored[ConferenceRecordingTable.singleParticipantIdentity] shouldBe chosen.toString()
                val audit = auditAfter(dto.id)
                audit shouldContain chosen.toString()
                audit shouldContain "SINGLE_PARTICIPANT"
                audit shouldNotContain "Layout Test"
            }
        }

        test("the old startRecording derives the layout from the room view: RASTER gives GRID, SPEAKER gives SPEAKER") {
            val moderator = createMember("mod-3")
            withRoutes { client ->
                val raster = createRoom(moderator, ConferenceBroadcastView.RASTER)
                parse(client.start(raster, moderator)).layout shouldBe ConferenceRecordingLayout.GRID
                rowOf(raster).single()[ConferenceRecordingTable.layout] shouldBe ConferenceRecordingLayout.GRID
                val speaker = createRoom(moderator, ConferenceBroadcastView.SPEAKER)
                parse(client.start(speaker, moderator)).layout shouldBe ConferenceRecordingLayout.SPEAKER
                rowOf(speaker).single()[ConferenceRecordingTable.layout] shouldBe ConferenceRecordingLayout.SPEAKER
            }
        }

        test(
            "refusals: identity with GRID/SPEAKER, SINGLE_PARTICIPANT without or with a too long identity -- nothing is stored, nothing echoed",
        ) {
            val moderator = createMember("mod-4")
            withRoutes { client ->
                val room = createRoom(moderator)
                val secret = "secret-identity-marker"
                client.startWith(room, moderator, ConferenceRecordingLayout.GRID, secret).let {
                    it shouldContain "ERR:ConflictException"
                    it shouldNotContain secret
                }
                client.startWith(room, moderator, ConferenceRecordingLayout.SPEAKER, secret) shouldContain "ERR:ConflictException"
                client.startWith(room, moderator, ConferenceRecordingLayout.SINGLE_PARTICIPANT, null) shouldContain "ERR:ConflictException"
                client.startWith(room, moderator, ConferenceRecordingLayout.SINGLE_PARTICIPANT, "x".repeat(65)) shouldContain
                    "ERR:ConflictException"
                rowOf(room).size shouldBe 0
            }
        }

        test("a person who is not in the room (never joined, already left, not a UUID) is refused without echoing the identity") {
            val moderator = createMember("mod-5")
            val stranger = createMember("stranger-5")
            val gone = createMember("gone-5")
            val room = createRoom(moderator)
            join(room, gone, open = false)
            withRoutes { client ->
                listOf(stranger.toString(), gone.toString(), "tampered-identity-marker").forEach { identity ->
                    val answer = client.startWith(room, moderator, ConferenceRecordingLayout.SINGLE_PARTICIPANT, identity)
                    answer shouldContain "ERR:ConflictException"
                    answer shouldNotContain identity
                }
                rowOf(room).size shouldBe 0
            }
        }

        test("a member who is not a moderator of the room gets the same refusal as startRecording") {
            val moderator = createMember("mod-6")
            val other = createMember("other-6")
            val room = createRoom(moderator)
            join(room, other)
            withRoutes { client ->
                client.start(room, other) shouldContain "ERR:ForbiddenException"
                client.startWith(room, other, ConferenceRecordingLayout.SINGLE_PARTICIPANT, other.toString()) shouldContain
                    "ERR:ForbiddenException"
                rowOf(room).size shouldBe 0
            }
        }

        test("both methods draw on the SAME rate-limit budget") {
            val moderator = createMember("mod-7")
            withRoutes(limiter = LoginRateLimiter(maxFailures = 2)) { client ->
                parse(client.start(createRoom(moderator), moderator))
                parse(client.startWith(createRoom(moderator), moderator, ConferenceRecordingLayout.GRID, null))
                client.start(createRoom(moderator), moderator) shouldContain "Too many recording-start attempts"
                client.startWith(createRoom(moderator), moderator, ConferenceRecordingLayout.GRID, null) shouldContain
                    "Too many recording-start attempts"
            }
        }

        test("one active recording per room still applies to the new method") {
            val moderator = createMember("mod-8")
            val room = createRoom(moderator)
            withRoutes { client ->
                parse(client.startWith(room, moderator, ConferenceRecordingLayout.GRID, null))
                client.startWith(room, moderator, ConferenceRecordingLayout.SPEAKER, null) shouldContain
                    "ERR:ConferenceRecordingAlreadyActiveException"
                rowOf(room).size shouldBe 1
            }
        }

        test("the chosen identity is told to a moderator only; a plain viewer sees the layout but null") {
            val moderator = createMember("mod-9")
            val viewer = createMember("viewer-9")
            val room = createRoom(moderator)
            join(room, moderator)
            join(room, viewer)
            withRoutes { client ->
                val started = parse(client.startWith(room, moderator, ConferenceRecordingLayout.SINGLE_PARTICIPANT, viewer.toString()))
                started.singleParticipantIdentity shouldBe viewer.toString()
                parse(client.active(room, moderator)).singleParticipantIdentity shouldBe viewer.toString()
                val seenByViewer = parse(client.active(room, viewer))
                seenByViewer.layout shouldBe ConferenceRecordingLayout.SINGLE_PARTICIPANT
                seenByViewer.singleParticipantIdentity shouldBe null
            }
        }

        test("an unknown layout literal is refused on the wire") {
            val failure =
                runCatching { json.decodeFromString(ConferenceRecordingLayout.serializer(), "\"PINNED\"") }.exceptionOrNull()
            (failure != null) shouldBe true
        }
    })
