package network.lapis.cloud.server.rpc

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
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
import kotlinx.serialization.json.Json
import network.lapis.cloud.server.conference.ConferenceConfig
import network.lapis.cloud.server.conference.ConferenceRecordingConfig
import network.lapis.cloud.server.db.DatabaseConfig
import network.lapis.cloud.server.db.DbClock
import network.lapis.cloud.server.db.DevSeedData
import network.lapis.cloud.server.db.generated.AccountTable
import network.lapis.cloud.server.db.generated.AuditLogEntryTable
import network.lapis.cloud.server.db.generated.ConferenceParticipationTable
import network.lapis.cloud.server.db.generated.ConferenceRecordingTable
import network.lapis.cloud.server.db.generated.ConferenceRecordingTrackTable
import network.lapis.cloud.server.db.generated.ConferenceRoomTable
import network.lapis.cloud.server.db.generated.DocumentFolderTable
import network.lapis.cloud.server.db.generated.DocumentTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.server.federation.FederationInboxRateLimiter
import network.lapis.cloud.server.security.LoginRateLimiter
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.AuditAction
import network.lapis.cloud.shared.domain.AuditEntityType
import network.lapis.cloud.shared.domain.ConferenceRecordingAvailabilityDto
import network.lapis.cloud.shared.domain.ConferenceRecordingDto
import network.lapis.cloud.shared.domain.ConferenceRecordingListQuery
import network.lapis.cloud.shared.domain.ConferenceRecordingSnapshot
import network.lapis.cloud.shared.domain.ConferenceRecordingStatus
import network.lapis.cloud.shared.domain.ConferenceRecordingTrackSource
import network.lapis.cloud.shared.domain.ConferenceRecordingTrackStatus
import network.lapis.cloud.shared.domain.ConferenceRole
import network.lapis.cloud.shared.domain.DocumentAccessLevel
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
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.minutes
import kotlin.uuid.Uuid

private const val ADMIN_ID = "00000000-0000-0000-0000-000000000001"
private const val BOARD_ID = "00000000-0000-0000-0000-000000000002"

/** [ConferenceConfig] with `enabled=true` -- built via the injectable `env` seam, no real env vars touched. */
private val ENABLED_CONFERENCE_CONFIG =
    ConferenceConfig.load { key ->
        when (key) {
            "LAPIS_LIVEKIT_URL" -> "ws://localhost:7880"
            "LAPIS_LIVEKIT_API_KEY" -> "test-livekit-key"
            "LAPIS_LIVEKIT_API_SECRET" -> "test-livekit-secret-at-least-32-bytes-long!!"
            else -> null
        }
    }

private val DISABLED_CONFERENCE_CONFIG = ConferenceConfig.load { null }

private val ENABLED_RECORDING_CONFIG = ConferenceRecordingConfig.load { key -> if (key == "LAPIS_RECORDING_ENABLED") "true" else null }
private val DISABLED_RECORDING_CONFIG = ConferenceRecordingConfig.load { null }

/**
 * Exercises [ConferenceRecordingService] end to end, mirroring [ConferenceServiceTest]'s house
 * style (throwaway routes calling the service class directly, fields pipe-separated in the
 * response body). No [network.lapis.cloud.server.conference.LiveKitEgressClient] involvement
 * anywhere in this file -- this service never touches LiveKit at all, see class KDoc
 * "startRecording is transaction-only". [afterSpec] hard-deletes every recording/room/member row
 * this file created.
 */
class ConferenceRecordingServiceTest :
    FunSpec({
        val createdMemberIds = mutableListOf<Uuid>()
        val createdRoomIds = mutableListOf<Uuid>()
        val createdDocumentIds = mutableListOf<Uuid>()
        val createdFolderIds = mutableListOf<Uuid>()

        beforeSpec {
            DatabaseConfig.connect()
            DevSeedData.seedIfEmpty(force = true)
        }

        afterSpec {
            cleanUpConferenceRecordingTestData(
                memberIds = createdMemberIds,
                roomIds = createdRoomIds,
                documentIds = createdDocumentIds,
                folderIds = createdFolderIds,
            )
        }

        fun createTestMember(
            email: String,
            status: MemberStatus = MemberStatus.ACTIVE,
        ): Uuid {
            val id = Uuid.random()
            transaction {
                MemberTable.insert {
                    it[MemberTable.id] = id
                    it[displayName] = "Aufzeichnung Testmitglied"
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

        /** Inserts a `conference_room` row directly (no LiveKit involved) -- [ConferenceRecordingService] never reads `livekit_room_name`. */
        fun createTestRoom(
            creatorId: Uuid,
            title: String,
            ended: Boolean = false,
        ): Uuid {
            val id = Uuid.random()
            val now = DbClock.nowLocalDateTime()
            transaction {
                ConferenceRoomTable.insert {
                    it[ConferenceRoomTable.id] = id
                    it[ConferenceRoomTable.title] = title
                    it[description] = ""
                    it[livekitRoomName] = "lc-test-$id"
                    it[createdByMemberId] = creatorId
                    it[createdAt] = now
                    it[endedAt] = if (ended) now else null
                    it[maxParticipants] = 25
                }
            }
            createdRoomIds += id
            return id
        }

        /**
         * Inserts a `conference_recording` row DIRECTLY, bypassing [ConferenceRecordingService.startRecording]
         * -- the only way to obtain a `PROCESSING`/`READY`/`FAILED` row in a test (that service only
         * ever writes `RECORDING`/`STOPPING`, `RecordingPoller` owns every later transition), and it
         * sidesteps the one-active-recording-per-room invariant when a test needs several rows for
         * the same room.
         */
        fun seedRecording(
            roomId: Uuid,
            startedByMemberId: Uuid,
            status: ConferenceRecordingStatus,
            accessLevel: DocumentAccessLevel = DocumentAccessLevel.BOARD_ONLY,
            documentId: Uuid? = null,
            rawDir: String? = null,
        ): Uuid {
            val id = Uuid.random()
            val now = DbClock.nowLocalDateTime()
            transaction {
                ConferenceRecordingTable.insert {
                    it[ConferenceRecordingTable.id] = id
                    it[ConferenceRecordingTable.roomId] = roomId
                    it[ConferenceRecordingTable.startedByMemberId] = startedByMemberId
                    it[startedAt] = now
                    it[stoppedAt] = now
                    it[readyAt] = if (status == ConferenceRecordingStatus.READY) now else null
                    it[ConferenceRecordingTable.status] = status
                    it[ConferenceRecordingTable.accessLevel] = accessLevel
                    it[ConferenceRecordingTable.documentId] = documentId
                    it[ConferenceRecordingTable.rawDir] = rawDir ?: id.toString()
                    it[durationSeconds] = null
                    it[fileSizeBytes] = null
                    it[failureReason] = if (status == ConferenceRecordingStatus.FAILED) "Die Zusammenführung ist fehlgeschlagen." else null
                    it[composeAttempts] = 0
                }
            }
            return id
        }

        /** One `conference_recording_track` child row -- exists purely to prove [ConferenceRecordingService.deleteRecording] removes children before the parent. */
        fun seedTrack(recordingId: Uuid): Uuid {
            val id = Uuid.random()
            transaction {
                ConferenceRecordingTrackTable.insert {
                    it[ConferenceRecordingTrackTable.id] = id
                    it[ConferenceRecordingTrackTable.recordingId] = recordingId
                    it[egressId] = "eg-$id"
                    it[livekitTrackId] = "tr-$id"
                    it[participantIdentity] = "member-$recordingId"
                    it[trackSource] = ConferenceRecordingTrackSource.CAMERA
                    it[ConferenceRecordingTrackTable.status] = ConferenceRecordingTrackStatus.COMPLETE
                    it[startedAtEpochNanos] = null
                    it[endedAtEpochNanos] = null
                    it[fileName] = null
                    it[durationMs] = null
                    it[sizeBytes] = null
                }
            }
            return id
        }

        /** Folder + document, no version/blob -- [ConferenceRecordingService.deleteRecording] only ever flips `document.is_deleted`. */
        fun seedDocument(
            createdBy: Uuid,
            accessLevel: DocumentAccessLevel = DocumentAccessLevel.BOARD_ONLY,
        ): Uuid {
            val folderId = Uuid.random()
            val documentId = Uuid.random()
            transaction {
                DocumentFolderTable.insert {
                    it[id] = folderId
                    it[name] = "Aufzeichnungen-Delete-Test-$folderId"
                    it[parentFolderId] = null
                }
                DocumentTable.insert {
                    it[id] = documentId
                    it[DocumentTable.folderId] = folderId
                    it[title] = "Aufzeichnung Delete-Test"
                    it[currentVersionId] = null
                    it[DocumentTable.createdBy] = createdBy
                    it[createdAt] = DbClock.nowLocalDateTime()
                    it[DocumentTable.accessLevel] = accessLevel
                    it[isDeleted] = false
                }
            }
            createdFolderIds += folderId
            createdDocumentIds += documentId
            return documentId
        }

        fun recordingExists(recordingId: Uuid): Boolean =
            transaction { ConferenceRecordingTable.selectAll().where { ConferenceRecordingTable.id eq recordingId }.any() }

        // ── getRecordingAvailability ─────────────────────────────────────────

        test("getRecordingAvailability: all three gates true -> enabled=true; any one false -> enabled=false, never throws") {
            testApplication {
                application {
                    install(StatusPages) { installConferenceRecordingExceptionHandlers() }
                    routing {
                        get("/test/availability-all-true") {
                            val dto =
                                ConferenceRecordingService(
                                    call = call,
                                    ffmpegAvailable = true,
                                    config = ENABLED_CONFERENCE_CONFIG,
                                    recordingConfig = ENABLED_RECORDING_CONFIG,
                                ).getRecordingAvailability()
                            call.respondText(dto.toPipeString())
                        }
                        get("/test/availability-no-ffmpeg") {
                            val dto =
                                ConferenceRecordingService(
                                    call = call,
                                    ffmpegAvailable = false,
                                    config = ENABLED_CONFERENCE_CONFIG,
                                    recordingConfig = ENABLED_RECORDING_CONFIG,
                                ).getRecordingAvailability()
                            call.respondText(dto.toPipeString())
                        }
                        get("/test/availability-recording-disabled") {
                            val dto =
                                ConferenceRecordingService(
                                    call = call,
                                    ffmpegAvailable = true,
                                    config = ENABLED_CONFERENCE_CONFIG,
                                    recordingConfig = DISABLED_RECORDING_CONFIG,
                                ).getRecordingAvailability()
                            call.respondText(dto.toPipeString())
                        }
                        get("/test/availability-conference-disabled") {
                            val dto =
                                ConferenceRecordingService(
                                    call = call,
                                    ffmpegAvailable = true,
                                    config = DISABLED_CONFERENCE_CONFIG,
                                    recordingConfig = ENABLED_RECORDING_CONFIG,
                                ).getRecordingAvailability()
                            call.respondText(dto.toPipeString())
                        }
                    }
                }
                val member = createTestMember("rec-availability@example.org")

                client.get("/test/availability-all-true") { header("X-Member-Id", member.toString()) }.bodyAsText() shouldBe
                    "true|true|240"
                client.get("/test/availability-no-ffmpeg") { header("X-Member-Id", member.toString()) }.bodyAsText() shouldBe
                    "false|false|240"
                client.get("/test/availability-recording-disabled") { header("X-Member-Id", member.toString()) }.bodyAsText() shouldBe
                    "false|true|240"
                client.get("/test/availability-conference-disabled") { header("X-Member-Id", member.toString()) }.bodyAsText() shouldBe
                    "false|true|240"
            }
        }

        test("getRecordingAvailability: unauthenticated caller is rejected") {
            testApplication {
                application {
                    install(StatusPages) { installConferenceRecordingExceptionHandlers() }
                    routing {
                        get("/test/availability") {
                            val dto =
                                ConferenceRecordingService(
                                    call = call,
                                    ffmpegAvailable = true,
                                    config = ENABLED_CONFERENCE_CONFIG,
                                    recordingConfig = ENABLED_RECORDING_CONFIG,
                                ).getRecordingAvailability()
                            call.respondText(dto.toPipeString())
                        }
                    }
                }
                client.get("/test/availability").status shouldBe HttpStatusCode.Unauthorized
            }
        }

        // ── startRecording ────────────────────────────────────────────────

        test("startRecording: happy path as the room's own creator -> RECORDING, correct accessLevel, one row") {
            testApplication {
                application {
                    install(StatusPages) { installConferenceRecordingExceptionHandlers() }
                    routing { registerConferenceRecordingTestRoutes() }
                }
                val creator = createTestMember("rec-start-happy@example.org")
                val roomId = createTestRoom(creator, "Vorstandssitzung")

                val response =
                    client.post("/test/start-recording?roomId=$roomId&accessLevel=BOARD_ONLY") {
                        header("X-Member-Id", creator.toString())
                    }
                response.status shouldBe HttpStatusCode.OK
                val dto = response.bodyAsText().toDto()
                dto.roomId shouldBe roomId.toString()
                dto.status shouldBe ConferenceRecordingStatus.RECORDING
                dto.accessLevel shouldBe DocumentAccessLevel.BOARD_ONLY
                dto.startedByMemberId shouldBe creator.toString()
                dto.trackCount shouldBe 0
                dto.documentId shouldBe null
                dto.mediaUrl shouldBe null

                val row =
                    transaction {
                        ConferenceRecordingTable.selectAll().where { ConferenceRecordingTable.id eq Uuid.parse(dto.id) }.single()
                    }
                row[ConferenceRecordingTable.rawDir] shouldBe dto.id
                row[ConferenceRecordingTable.composeAttempts] shouldBe 0
            }
        }

        test("startRecording: allowed for a global BOARD account even though it did not create the room") {
            testApplication {
                application {
                    install(StatusPages) { installConferenceRecordingExceptionHandlers() }
                    routing { registerConferenceRecordingTestRoutes() }
                }
                val creator = createTestMember("rec-start-board@example.org")
                val roomId = createTestRoom(creator, "Sitzung")

                client
                    .post("/test/start-recording?roomId=$roomId&accessLevel=BOARD_ONLY") {
                        header("X-Member-Id", BOARD_ID)
                    }.status shouldBe HttpStatusCode.OK
            }
        }

        test("startRecording: rejected with Forbidden for an ordinary participant who is neither creator nor privileged") {
            testApplication {
                application {
                    install(StatusPages) { installConferenceRecordingExceptionHandlers() }
                    routing { registerConferenceRecordingTestRoutes() }
                }
                val creator = createTestMember("rec-start-forbidden-creator@example.org")
                val other = createTestMember("rec-start-forbidden-other@example.org")
                val roomId = createTestRoom(creator, "Sitzung")

                client
                    .post("/test/start-recording?roomId=$roomId&accessLevel=BOARD_ONLY") {
                        header("X-Member-Id", other.toString())
                    }.status shouldBe HttpStatusCode.Forbidden
            }
        }

        test("startRecording: rejected with NotFound for a nonexistent room") {
            testApplication {
                application {
                    install(StatusPages) { installConferenceRecordingExceptionHandlers() }
                    routing { registerConferenceRecordingTestRoutes() }
                }
                val creator = createTestMember("rec-start-notfound@example.org")

                client
                    .post("/test/start-recording?roomId=${Uuid.random()}&accessLevel=BOARD_ONLY") {
                        header("X-Member-Id", creator.toString())
                    }.status shouldBe HttpStatusCode.NotFound
            }
        }

        test("startRecording: rejected with Conflict for an already-ended room") {
            testApplication {
                application {
                    install(StatusPages) { installConferenceRecordingExceptionHandlers() }
                    routing { registerConferenceRecordingTestRoutes() }
                }
                val creator = createTestMember("rec-start-ended@example.org")
                val roomId = createTestRoom(creator, "Sitzung", ended = true)

                client
                    .post("/test/start-recording?roomId=$roomId&accessLevel=BOARD_ONLY") {
                        header("X-Member-Id", creator.toString())
                    }.status shouldBe HttpStatusCode.Conflict
            }
        }

        test("startRecording: one-active-recording-per-room invariant -- a second start while RECORDING is rejected with Conflict") {
            testApplication {
                application {
                    install(StatusPages) { installConferenceRecordingExceptionHandlers() }
                    routing { registerConferenceRecordingTestRoutes() }
                }
                val creator = createTestMember("rec-start-invariant@example.org")
                val roomId = createTestRoom(creator, "Sitzung")

                client
                    .post("/test/start-recording?roomId=$roomId&accessLevel=BOARD_ONLY") {
                        header("X-Member-Id", creator.toString())
                    }.status shouldBe HttpStatusCode.OK
                client
                    .post("/test/start-recording?roomId=$roomId&accessLevel=BOARD_ONLY") {
                        header("X-Member-Id", creator.toString())
                    }.status shouldBe HttpStatusCode.Conflict
            }
        }

        test("startRecording: still blocked while the prior recording is only STOPPING, not yet terminal") {
            testApplication {
                application {
                    install(StatusPages) { installConferenceRecordingExceptionHandlers() }
                    routing { registerConferenceRecordingTestRoutes() }
                }
                val creator = createTestMember("rec-start-stopping@example.org")
                val roomId = createTestRoom(creator, "Sitzung")
                val startResponse =
                    client.post("/test/start-recording?roomId=$roomId&accessLevel=BOARD_ONLY") {
                        header("X-Member-Id", creator.toString())
                    }
                val recordingId = startResponse.bodyAsText().toDto().id
                client
                    .post("/test/stop-recording?recordingId=$recordingId") {
                        header("X-Member-Id", creator.toString())
                    }.status shouldBe HttpStatusCode.OK

                client
                    .post("/test/start-recording?roomId=$roomId&accessLevel=BOARD_ONLY") {
                        header("X-Member-Id", creator.toString())
                    }.status shouldBe HttpStatusCode.Conflict
            }
        }

        test("startRecording: rejected with Conflict when recording is not configured (LAPIS_RECORDING_ENABLED unset)") {
            testApplication {
                application {
                    install(StatusPages) { installConferenceRecordingExceptionHandlers() }
                    routing {
                        post("/test/start-recording") {
                            val q = call.request.queryParameters
                            val dto =
                                ConferenceRecordingService(
                                    call = call,
                                    ffmpegAvailable = true,
                                    config = ENABLED_CONFERENCE_CONFIG,
                                    recordingConfig = DISABLED_RECORDING_CONFIG,
                                ).startRecording(roomId = q["roomId"]!!, accessLevel = DocumentAccessLevel.valueOf(q["accessLevel"]!!))
                            call.respondText(dto.toPipeString())
                        }
                    }
                }
                val creator = createTestMember("rec-start-unconfigured@example.org")
                val roomId = createTestRoom(creator, "Sitzung")

                client
                    .post("/test/start-recording?roomId=$roomId&accessLevel=BOARD_ONLY") {
                        header("X-Member-Id", creator.toString())
                    }.status shouldBe HttpStatusCode.Conflict
            }
        }

        test("startRecording: rejected with Conflict when ffmpeg is unavailable") {
            testApplication {
                application {
                    install(StatusPages) { installConferenceRecordingExceptionHandlers() }
                    routing {
                        post("/test/start-recording") {
                            val q = call.request.queryParameters
                            val dto =
                                ConferenceRecordingService(
                                    call = call,
                                    ffmpegAvailable = false,
                                    config = ENABLED_CONFERENCE_CONFIG,
                                    recordingConfig = ENABLED_RECORDING_CONFIG,
                                ).startRecording(roomId = q["roomId"]!!, accessLevel = DocumentAccessLevel.valueOf(q["accessLevel"]!!))
                            call.respondText(dto.toPipeString())
                        }
                    }
                }
                val creator = createTestMember("rec-start-noffmpeg@example.org")
                val roomId = createTestRoom(creator, "Sitzung")

                client
                    .post("/test/start-recording?roomId=$roomId&accessLevel=BOARD_ONLY") {
                        header("X-Member-Id", creator.toString())
                    }.status shouldBe HttpStatusCode.Conflict
            }
        }

        test("startRecording: two genuinely concurrent starts for the same room -- exactly one succeeds, the other Conflicts") {
            testApplication {
                application {
                    install(StatusPages) { installConferenceRecordingExceptionHandlers() }
                    routing { registerConferenceRecordingTestRoutes() }
                }
                val creator = createTestMember("rec-start-race-creator@example.org")
                val roomId = createTestRoom(creator, "Sitzung")

                // Two DIFFERENT moderators (room creator + a global BOARD account) racing on the
                // SAME room, both threads started only after both are past setup (startLatch) so
                // their startRecording transactions genuinely overlap -- unlike the sequential
                // "second start after the first has already committed" test above, this is the
                // actual check-then-act race the `.forUpdate()` room-row lock in
                // ConferenceRecordingService.startRecording closes.
                val outcomes =
                    runConcurrentStartRecording(
                        client = client,
                        roomId = roomId.toString(),
                        callerA = creator,
                        callerB = Uuid.parse(BOARD_ID),
                    )
                outcomes.count { it == HttpStatusCode.OK } shouldBe 1
                outcomes.count { it == HttpStatusCode.Conflict } shouldBe 1

                val activeCount =
                    transaction {
                        ConferenceRecordingTable
                            .selectAll()
                            .where {
                                (ConferenceRecordingTable.roomId eq roomId) and
                                    (
                                        ConferenceRecordingTable.status inList
                                            listOf(ConferenceRecordingStatus.RECORDING, ConferenceRecordingStatus.STOPPING)
                                    )
                            }.count()
                    }
                activeCount shouldBe 1
            }
        }

        test("startRecording: throttled after the configured number of attempts, regardless of success/failure") {
            testApplication {
                application {
                    install(StatusPages) { installConferenceRecordingExceptionHandlers() }
                    routing { registerConferenceRecordingTestRoutes(startLimiter = LoginRateLimiter(maxFailures = 1)) }
                }
                val creator = createTestMember("rec-start-throttle@example.org")
                val roomA = createTestRoom(creator, "A")
                val roomB = createTestRoom(creator, "B")

                client
                    .post("/test/start-recording?roomId=$roomA&accessLevel=BOARD_ONLY") {
                        header("X-Member-Id", creator.toString())
                    }.status shouldBe HttpStatusCode.OK
                client
                    .post("/test/start-recording?roomId=$roomB&accessLevel=BOARD_ONLY") {
                        header("X-Member-Id", creator.toString())
                    }.status shouldBe HttpStatusCode.Conflict
            }
        }

        // ── stopRecording ─────────────────────────────────────────────────

        test("stopRecording: happy path transitions RECORDING -> STOPPING, idempotent on a second call") {
            testApplication {
                application {
                    install(StatusPages) { installConferenceRecordingExceptionHandlers() }
                    routing { registerConferenceRecordingTestRoutes() }
                }
                val creator = createTestMember("rec-stop-happy@example.org")
                val roomId = createTestRoom(creator, "Sitzung")
                val recordingId =
                    client
                        .post("/test/start-recording?roomId=$roomId&accessLevel=BOARD_ONLY") { header("X-Member-Id", creator.toString()) }
                        .bodyAsText()
                        .toDto()
                        .id

                val firstStop =
                    client.post("/test/stop-recording?recordingId=$recordingId") { header("X-Member-Id", creator.toString()) }
                firstStop.status shouldBe HttpStatusCode.OK
                firstStop.bodyAsText().toDto().status shouldBe ConferenceRecordingStatus.STOPPING

                val secondStop =
                    client.post("/test/stop-recording?recordingId=$recordingId") { header("X-Member-Id", creator.toString()) }
                secondStop.status shouldBe HttpStatusCode.OK
                secondStop.bodyAsText().toDto().status shouldBe ConferenceRecordingStatus.STOPPING
            }
        }

        test("stopRecording: a moderator other than the original starter (BOARD) may stop it") {
            testApplication {
                application {
                    install(StatusPages) { installConferenceRecordingExceptionHandlers() }
                    routing { registerConferenceRecordingTestRoutes() }
                }
                val creator = createTestMember("rec-stop-board@example.org")
                val roomId = createTestRoom(creator, "Sitzung")
                val recordingId =
                    client
                        .post("/test/start-recording?roomId=$roomId&accessLevel=BOARD_ONLY") { header("X-Member-Id", creator.toString()) }
                        .bodyAsText()
                        .toDto()
                        .id

                client.post("/test/stop-recording?recordingId=$recordingId") { header("X-Member-Id", BOARD_ID) }.status shouldBe
                    HttpStatusCode.OK
            }
        }

        test("stopRecording: rejected with Forbidden for an unprivileged non-creator") {
            testApplication {
                application {
                    install(StatusPages) { installConferenceRecordingExceptionHandlers() }
                    routing { registerConferenceRecordingTestRoutes() }
                }
                val creator = createTestMember("rec-stop-forbidden-creator@example.org")
                val other = createTestMember("rec-stop-forbidden-other@example.org")
                val roomId = createTestRoom(creator, "Sitzung")
                val recordingId =
                    client
                        .post("/test/start-recording?roomId=$roomId&accessLevel=BOARD_ONLY") { header("X-Member-Id", creator.toString()) }
                        .bodyAsText()
                        .toDto()
                        .id

                client.post("/test/stop-recording?recordingId=$recordingId") { header("X-Member-Id", other.toString()) }.status shouldBe
                    HttpStatusCode.Forbidden
            }
        }

        test("stopRecording: rejected with NotFound for a nonexistent recording") {
            testApplication {
                application {
                    install(StatusPages) { installConferenceRecordingExceptionHandlers() }
                    routing { registerConferenceRecordingTestRoutes() }
                }
                val creator = createTestMember("rec-stop-notfound@example.org")

                client
                    .post("/test/stop-recording?recordingId=${Uuid.random()}") {
                        header("X-Member-Id", creator.toString())
                    }.status shouldBe HttpStatusCode.NotFound
            }
        }

        // ── getActiveRecording ───────────────────────────────────────────────

        test("getActiveRecording: empty when no recording is active, single element while RECORDING, never gated by DocumentAccessLevel") {
            testApplication {
                application {
                    install(StatusPages) { installConferenceRecordingExceptionHandlers() }
                    routing { registerConferenceRecordingTestRoutes() }
                }
                val creator = createTestMember("rec-active-happy@example.org")
                val other = createTestMember("rec-active-other@example.org")
                val roomId = createTestRoom(creator, "Sitzung")

                client.get("/test/active-recording?roomId=$roomId") { header("X-Member-Id", other.toString()) }.bodyAsText() shouldBe ""

                val recordingId =
                    client
                        .post("/test/start-recording?roomId=$roomId&accessLevel=ADMIN_ONLY") { header("X-Member-Id", creator.toString()) }
                        .bodyAsText()
                        .toDto()
                        .id

                // A caller who is neither ADMIN nor the recording's own starter still sees it here
                // -- getActiveRecording is never gated by DocumentAccessLevel, see interface KDoc.
                val activeResponse = client.get("/test/active-recording?roomId=$roomId") { header("X-Member-Id", other.toString()) }
                activeResponse.bodyAsText().toDto().id shouldBe recordingId
                activeResponse.bodyAsText().toDto().status shouldBe ConferenceRecordingStatus.RECORDING
            }
        }

        // ── getActiveRecording -- Wave 5 "Föderations-Gastbeitritt", design review D13 ────────

        test(
            "D13: a GAST who has joined an opted-in room sees the active recording -- 'everyone in the room has a legal right to know' applies to a guest too",
        ) {
            testApplication {
                application {
                    install(StatusPages) { installConferenceRecordingExceptionHandlers() }
                    routing { registerConferenceRecordingTestRoutes() }
                }
                val creator = createTestMember("rec-d13-happy@example.org")
                val roomId = createTestRoom(creator, "D13-Sitzung")
                transaction { ConferenceRoomTable.update({ ConferenceRoomTable.id eq roomId }) { it[allowFederationGuests] = true } }
                val guest = createTestMember("rec-d13-guest@example.org", status = MemberStatus.GUEST)
                transaction {
                    ConferenceParticipationTable.insert {
                        it[id] = Uuid.random()
                        it[ConferenceParticipationTable.roomId] = roomId
                        it[memberId] = guest
                        it[role] = ConferenceRole.PARTICIPANT
                        it[joinedAt] = DbClock.nowLocalDateTime()
                        it[leftAt] = null
                    }
                }

                client
                    .post("/test/start-recording?roomId=$roomId&accessLevel=ADMIN_ONLY") { header("X-Member-Id", creator.toString()) }
                    .bodyAsText()

                val response = client.get("/test/active-recording?roomId=$roomId") { header("X-Member-Id", guest.toString()) }
                response.status shouldBe HttpStatusCode.OK
                response.bodyAsText().toDto().status shouldBe ConferenceRecordingStatus.RECORDING
            }
        }

        test(
            "D13: a GAST who never joined the room is rejected; a GAST in a non-opted-in room is rejected; ANTRAG/AUSGETRETEN/ABGELEHNT unchanged",
        ) {
            testApplication {
                application {
                    install(StatusPages) { installConferenceRecordingExceptionHandlers() }
                    routing { registerConferenceRecordingTestRoutes() }
                }
                val creator = createTestMember("rec-d13-neg@example.org")
                val openRoomId = createTestRoom(creator, "D13-Offen")
                transaction { ConferenceRoomTable.update({ ConferenceRoomTable.id eq openRoomId }) { it[allowFederationGuests] = true } }
                val closedRoomId = createTestRoom(creator, "D13-Geschlossen")

                val neverJoinedGuest = createTestMember("rec-d13-never@example.org", status = MemberStatus.GUEST)
                client
                    .get(
                        "/test/active-recording?roomId=$openRoomId",
                    ) { header("X-Member-Id", neverJoinedGuest.toString()) }
                    .status shouldBe
                    HttpStatusCode.Forbidden

                val closedRoomGuest = createTestMember("rec-d13-closed@example.org", status = MemberStatus.GUEST)
                transaction {
                    ConferenceParticipationTable.insert {
                        it[id] = Uuid.random()
                        it[ConferenceParticipationTable.roomId] = closedRoomId
                        it[memberId] = closedRoomGuest
                        it[role] = ConferenceRole.PARTICIPANT
                        it[joinedAt] = DbClock.nowLocalDateTime()
                        it[leftAt] = null
                    }
                }
                client
                    .get(
                        "/test/active-recording?roomId=$closedRoomId",
                    ) { header("X-Member-Id", closedRoomGuest.toString()) }
                    .status shouldBe
                    HttpStatusCode.Forbidden

                listOf(MemberStatus.APPLICATION, MemberStatus.WITHDRAWN, MemberStatus.REJECTED).forEach { status ->
                    val member = createTestMember("rec-d13-${status.name.lowercase()}@example.org", status = status)
                    client.get("/test/active-recording?roomId=$openRoomId") { header("X-Member-Id", member.toString()) }.status shouldBe
                        HttpStatusCode.Forbidden
                }
            }
        }

        // ── listRecordings ───────────────────────────────────────────────────

        test("listRecordings: filtered to canAccessDocumentAtLevel OR startedByMemberId == caller") {
            testApplication {
                application {
                    install(StatusPages) { installConferenceRecordingExceptionHandlers() }
                    routing { registerConferenceRecordingTestRoutes() }
                }
                val starter = createTestMember("rec-list-starter@example.org")
                val ordinaryMember = createTestMember("rec-list-ordinary@example.org")
                val roomId = createTestRoom(starter, "Sitzung")

                // ADMIN_ONLY -- visible to the starter (own recording) and to ADMIN, NOT to an
                // ordinary member who neither started it nor has ADMIN_ONLY access.
                client.post("/test/start-recording?roomId=$roomId&accessLevel=ADMIN_ONLY") { header("X-Member-Id", starter.toString()) }

                val asStarter = client.get("/test/list-recordings?roomId=$roomId") { header("X-Member-Id", starter.toString()) }
                asStarter.bodyAsText().split(";").filter { it.isNotBlank() } shouldHaveSize 1

                val asOrdinary = client.get("/test/list-recordings?roomId=$roomId") { header("X-Member-Id", ordinaryMember.toString()) }
                asOrdinary.bodyAsText() shouldBe ""

                val asAdmin = client.get("/test/list-recordings?roomId=$roomId") { header("X-Member-Id", ADMIN_ID) }
                asAdmin.bodyAsText().split(";").filter { it.isNotBlank() } shouldHaveSize 1
            }
        }

        test("listRecordings: PUBLIC_MEMBERS is visible to any AKTIV member") {
            testApplication {
                application {
                    install(StatusPages) { installConferenceRecordingExceptionHandlers() }
                    routing { registerConferenceRecordingTestRoutes() }
                }
                val starter = createTestMember("rec-list-public-starter@example.org")
                val anyone = createTestMember("rec-list-public-anyone@example.org")
                val roomId = createTestRoom(starter, "Sitzung")

                client.post("/test/start-recording?roomId=$roomId&accessLevel=PUBLIC_MEMBERS") { header("X-Member-Id", starter.toString()) }

                val response = client.get("/test/list-recordings?roomId=$roomId") { header("X-Member-Id", anyone.toString()) }
                response.bodyAsText().split(";").filter { it.isNotBlank() } shouldHaveSize 1
            }
        }

        test("listRecordings: roomId omitted lists across all rooms, still filtered by the access predicate") {
            testApplication {
                application {
                    install(StatusPages) { installConferenceRecordingExceptionHandlers() }
                    routing { registerConferenceRecordingTestRoutes() }
                }
                val starter = createTestMember("rec-list-allrooms-starter@example.org")
                val roomId = createTestRoom(starter, "Sitzung")
                client.post("/test/start-recording?roomId=$roomId&accessLevel=BOARD_ONLY") { header("X-Member-Id", starter.toString()) }

                val response = client.get("/test/list-recordings") { header("X-Member-Id", BOARD_ID) }
                // Pipe format is "id|roomId|...", so the room id is the SECOND field, not a prefix.
                response
                    .bodyAsText()
                    .split(";")
                    .filter { it.isNotBlank() }
                    .any { it.split("|")[1] == roomId.toString() } shouldBe true
            }
        }

        // ── listRecordings -- offset pagination ──────────────────────────────

        test("listRecordings: pages by limit/offset, totalCount counts the caller's OWN accessible rows only") {
            testApplication {
                application {
                    install(StatusPages) { installConferenceRecordingExceptionHandlers() }
                    routing { registerConferenceRecordingTestRoutes() }
                }
                val starter = createTestMember("rec-page-starter@example.org")
                val ordinaryMember = createTestMember("rec-page-ordinary@example.org")
                val roomId = createTestRoom(starter, "Paginierung")
                repeat(3) {
                    seedRecording(
                        roomId = roomId,
                        startedByMemberId = starter,
                        status = ConferenceRecordingStatus.READY,
                        accessLevel = DocumentAccessLevel.PUBLIC_MEMBERS,
                    )
                }
                // Invisible to `ordinaryMember` (neither ADMIN nor its starter) -- it must NOT be
                // counted in their totalCount, and must not consume one of their page slots either.
                seedRecording(
                    roomId = roomId,
                    startedByMemberId = starter,
                    status = ConferenceRecordingStatus.READY,
                    accessLevel = DocumentAccessLevel.ADMIN_ONLY,
                )

                val firstPage =
                    client
                        .get("/test/list-recordings-page?roomId=$roomId&limit=2&offset=0") {
                            header("X-Member-Id", ordinaryMember.toString())
                        }.bodyAsText()
                        .toPage()
                firstPage.totalCount shouldBe 3
                firstPage.limit shouldBe 2
                firstPage.offset shouldBe 0
                firstPage.rows shouldHaveSize 2

                val secondPage =
                    client
                        .get("/test/list-recordings-page?roomId=$roomId&limit=2&offset=2") {
                            header("X-Member-Id", ordinaryMember.toString())
                        }.bodyAsText()
                        .toPage()
                secondPage.totalCount shouldBe 3
                secondPage.rows shouldHaveSize 1
                // No row appears on two pages -- the (started_at DESC, id ASC) tie-break holds even
                // though all four rows were seeded within the same clock tick.
                (firstPage.rows.map { it.id } + secondPage.rows.map { it.id }).toSet() shouldHaveSize 3

                // Same query as ADMIN: the ADMIN_ONLY row is both counted AND returned.
                client
                    .get("/test/list-recordings-page?roomId=$roomId&limit=25&offset=0") { header("X-Member-Id", ADMIN_ID) }
                    .bodyAsText()
                    .toPage()
                    .totalCount shouldBe 4
            }
        }

        test("listRecordings: client-supplied limit/offset are clamped server-side, and the applied values are echoed back") {
            testApplication {
                application {
                    install(StatusPages) { installConferenceRecordingExceptionHandlers() }
                    routing { registerConferenceRecordingTestRoutes() }
                }
                val starter = createTestMember("rec-page-clamp@example.org")
                val roomId = createTestRoom(starter, "Clamping")
                seedRecording(
                    roomId = roomId,
                    startedByMemberId = starter,
                    status = ConferenceRecordingStatus.READY,
                    accessLevel = DocumentAccessLevel.PUBLIC_MEMBERS,
                )

                val overLimit =
                    client
                        .get("/test/list-recordings-page?roomId=$roomId&limit=5000&offset=0") { header("X-Member-Id", starter.toString()) }
                        .bodyAsText()
                        .toPage()
                overLimit.limit shouldBe ConferenceRecordingListQuery.MAX_LIMIT

                val negative =
                    client
                        .get("/test/list-recordings-page?roomId=$roomId&limit=-3&offset=-7") { header("X-Member-Id", starter.toString()) }
                        .bodyAsText()
                        .toPage()
                negative.limit shouldBe 1
                negative.offset shouldBe 0
            }
        }

        // ── deleteRecording ──────────────────────────────────────────────────

        test("deleteRecording: READY -- row and tracks are gone, the backing document is SOFT-deleted, audit entry written") {
            testApplication {
                application {
                    install(StatusPages) { installConferenceRecordingExceptionHandlers() }
                    routing { registerConferenceRecordingTestRoutes() }
                }
                val creator = createTestMember("rec-delete-ready@example.org")
                val roomId = createTestRoom(creator, "Sitzung")
                val documentId = seedDocument(createdBy = creator, accessLevel = DocumentAccessLevel.PUBLIC_MEMBERS)
                // PUBLIC_MEMBERS, not the seed default BOARD_ONLY: this caller is the room's creator
                // but a plain ACTIVE MEMBER, and deleting a recording WITH an archived document
                // additionally requires access to that document's own level -- see
                // ConferenceRecordingService.deleteRecording KDoc fact 1 and the ADMIN_ONLY test
                // below. This test is the "non-elevated level, therefore still allowed" half of that
                // rule.
                val recordingId =
                    seedRecording(
                        roomId = roomId,
                        startedByMemberId = creator,
                        status = ConferenceRecordingStatus.READY,
                        accessLevel = DocumentAccessLevel.PUBLIC_MEMBERS,
                        documentId = documentId,
                    )
                seedTrack(recordingId)

                val response =
                    client.post("/test/delete-recording?recordingId=$recordingId") { header("X-Member-Id", creator.toString()) }
                response.status shouldBe HttpStatusCode.OK
                response.bodyAsText() shouldBe "true"

                recordingExists(recordingId) shouldBe false
                transaction {
                    ConferenceRecordingTrackTable
                        .selectAll()
                        .where { ConferenceRecordingTrackTable.recordingId eq recordingId }
                        .count()
                } shouldBe 0L
                // Soft-delete ONLY -- the document row survives, flagged, exactly like every other
                // deleted Document in this app.
                transaction {
                    DocumentTable.selectAll().where { DocumentTable.id eq documentId }.single()[DocumentTable.isDeleted]
                } shouldBe true

                val auditRows =
                    transaction {
                        AuditLogEntryTable
                            .selectAll()
                            .where { AuditLogEntryTable.entityId eq recordingId }
                            .map {
                                Triple(
                                    it[AuditLogEntryTable.entityType],
                                    it[AuditLogEntryTable.action],
                                    it[AuditLogEntryTable.beforeSnapshot],
                                )
                            }
                    }
                // Exactly one: this row was seeded directly, so the deletion is its only audited
                // mutation. UPDATE, not DELETE -- AuditAction has no DELETE literal.
                auditRows shouldHaveSize 1
                val (entityType, action, beforeSnapshot) = auditRows.single()
                entityType shouldBe AuditEntityType.CONFERENCE_RECORDING
                action shouldBe AuditAction.UPDATE
                // The row itself is HARD-deleted, so this snapshot is the only surviving record of
                // what existed -- asserted by CONTENT, not merely by presence. Deserialized back
                // rather than string-matched, so a field reordering in ConferenceRecordingSnapshot
                // can never quietly turn this into a weaker assertion.
                val snapshot =
                    Json.decodeFromString(ConferenceRecordingSnapshot.serializer(), requireNotNull(beforeSnapshot))
                snapshot.recordingId shouldBe recordingId.toString()
                snapshot.roomId shouldBe roomId.toString()
                snapshot.roomTitle shouldBe "Sitzung"
                snapshot.status shouldBe ConferenceRecordingStatus.READY
                snapshot.startedByMemberId shouldBe creator.toString()
                snapshot.accessLevel shouldBe DocumentAccessLevel.PUBLIC_MEMBERS
                snapshot.documentId shouldBe documentId.toString()
                snapshot.durationSeconds shouldBe null
                snapshot.fileSizeBytes shouldBe null
                snapshot.failureReason shouldBe null
                // Counted BEFORE the child rows were deleted -- one seedTrack above.
                snapshot.trackCount shouldBe 1
            }
        }

        test("deleteRecording: FAILED (no document at all) is deletable -- the case deleteDocument could never reach") {
            testApplication {
                application {
                    install(StatusPages) { installConferenceRecordingExceptionHandlers() }
                    routing { registerConferenceRecordingTestRoutes() }
                }
                val creator = createTestMember("rec-delete-failed@example.org")
                val roomId = createTestRoom(creator, "Sitzung")
                val recordingId =
                    seedRecording(roomId = roomId, startedByMemberId = creator, status = ConferenceRecordingStatus.FAILED)

                client
                    .post("/test/delete-recording?recordingId=$recordingId") { header("X-Member-Id", creator.toString()) }
                    .status shouldBe HttpStatusCode.OK
                recordingExists(recordingId) shouldBe false
            }
        }

        test("deleteRecording: room creator without document access is rejected whole -- no document flip, no row deletion") {
            testApplication {
                application {
                    install(StatusPages) { installConferenceRecordingExceptionHandlers() }
                    routing { registerConferenceRecordingTestRoutes() }
                }
                // The escalation this closes: a plain MEMBER creates the room (and stays its
                // moderator), a privileged participant starts an ADMIN_ONLY recording in it, and the
                // creator would otherwise be able to soft-delete a document they can neither read
                // nor delete via IDocumentService.deleteDocument -- see
                // ConferenceRecordingService.deleteRecording KDoc fact 1.
                val creator = createTestMember("rec-delete-escalation-creator@example.org")
                val roomId = createTestRoom(creator, "Sitzung")
                val documentId = seedDocument(createdBy = creator, accessLevel = DocumentAccessLevel.ADMIN_ONLY)
                val recordingId =
                    seedRecording(
                        roomId = roomId,
                        startedByMemberId = Uuid.parse(ADMIN_ID),
                        status = ConferenceRecordingStatus.READY,
                        accessLevel = DocumentAccessLevel.ADMIN_ONLY,
                        documentId = documentId,
                    )
                val trackId = seedTrack(recordingId)

                client
                    .post("/test/delete-recording?recordingId=$recordingId") { header("X-Member-Id", creator.toString()) }
                    .status shouldBe HttpStatusCode.Forbidden

                // Rejected WHOLE -- never a half-deleted state where the document is flagged but the
                // recording survives, or vice versa.
                transaction {
                    DocumentTable.selectAll().where { DocumentTable.id eq documentId }.single()[DocumentTable.isDeleted]
                } shouldBe false
                recordingExists(recordingId) shouldBe true
                transaction {
                    ConferenceRecordingTrackTable.selectAll().where { ConferenceRecordingTrackTable.id eq trackId }.count()
                } shouldBe 1L

                // ADMIN may delete exactly the same row -- the narrowing is about document access,
                // not about making ADMIN_ONLY recordings undeletable.
                client
                    .post("/test/delete-recording?recordingId=$recordingId") { header("X-Member-Id", ADMIN_ID) }
                    .status shouldBe HttpStatusCode.OK
                recordingExists(recordingId) shouldBe false
            }
        }

        test("deleteRecording: rejected with Conflict for every non-terminal status -- the poller may still be driving that row") {
            testApplication {
                application {
                    install(StatusPages) { installConferenceRecordingExceptionHandlers() }
                    routing { registerConferenceRecordingTestRoutes() }
                }
                val creator = createTestMember("rec-delete-nonterminal@example.org")
                val roomId = createTestRoom(creator, "Sitzung")

                listOf(
                    ConferenceRecordingStatus.RECORDING,
                    ConferenceRecordingStatus.STOPPING,
                    ConferenceRecordingStatus.PROCESSING,
                ).forEach { status ->
                    val recordingId = seedRecording(roomId = roomId, startedByMemberId = creator, status = status)
                    client
                        .post("/test/delete-recording?recordingId=$recordingId") { header("X-Member-Id", creator.toString()) }
                        .status shouldBe HttpStatusCode.Conflict
                    recordingExists(recordingId) shouldBe true
                }
            }
        }

        test("deleteRecording: allowed for a global BOARD account, rejected with Forbidden for an unprivileged non-creator") {
            testApplication {
                application {
                    install(StatusPages) { installConferenceRecordingExceptionHandlers() }
                    routing { registerConferenceRecordingTestRoutes() }
                }
                val creator = createTestMember("rec-delete-auth-creator@example.org")
                val other = createTestMember("rec-delete-auth-other@example.org")
                val roomId = createTestRoom(creator, "Sitzung")

                // Not even the caller's OWN recording is deletable without moderator standing --
                // deleteRecording gates on the ROOM, never on DocumentAccessLevel/startedBy.
                val ownRecordingId =
                    seedRecording(roomId = roomId, startedByMemberId = other, status = ConferenceRecordingStatus.READY)
                client
                    .post("/test/delete-recording?recordingId=$ownRecordingId") { header("X-Member-Id", other.toString()) }
                    .status shouldBe HttpStatusCode.Forbidden
                recordingExists(ownRecordingId) shouldBe true

                val boardDeletable =
                    seedRecording(roomId = roomId, startedByMemberId = creator, status = ConferenceRecordingStatus.READY)
                client
                    .post("/test/delete-recording?recordingId=$boardDeletable") { header("X-Member-Id", BOARD_ID) }
                    .status shouldBe HttpStatusCode.OK
            }
        }

        test("deleteRecording: rejected with NotFound for a nonexistent recording") {
            testApplication {
                application {
                    install(StatusPages) { installConferenceRecordingExceptionHandlers() }
                    routing { registerConferenceRecordingTestRoutes() }
                }
                val creator = createTestMember("rec-delete-notfound@example.org")

                client
                    .post("/test/delete-recording?recordingId=${Uuid.random()}") { header("X-Member-Id", creator.toString()) }
                    .status shouldBe HttpStatusCode.NotFound
            }
        }

        test("deleteRecording: removes the raw per-track directory from disk, regardless of keepRaw") {
            val rawRoot = Files.createTempDirectory("conf-rec-delete-raw").toFile()
            // keepRaw=true on purpose: that flag protects the poller's SILENT auto-deletion, never
            // an explicit, confirmed user deletion -- see deleteRawDirectory's own KDoc.
            val keepRawConfig =
                ConferenceRecordingConfig.load { key ->
                    when (key) {
                        "LAPIS_RECORDING_ENABLED" -> "true"
                        "LAPIS_RECORDING_KEEP_RAW" -> "true"
                        "LAPIS_EGRESS_OUTPUT_HOST_DIR" -> rawRoot.absolutePath
                        else -> null
                    }
                }
            testApplication {
                application {
                    install(StatusPages) { installConferenceRecordingExceptionHandlers() }
                    routing { registerConferenceRecordingTestRoutes(recordingConfig = keepRawConfig) }
                }
                val creator = createTestMember("rec-delete-rawdir@example.org")
                val roomId = createTestRoom(creator, "Sitzung")
                val recordingId =
                    seedRecording(roomId = roomId, startedByMemberId = creator, status = ConferenceRecordingStatus.READY)
                val rawDirectory = rawRoot.resolve(recordingId.toString())
                rawDirectory.mkdirs()
                rawDirectory.resolve("track-1.mp4").writeBytes("raw track bytes".toByteArray())

                client
                    .post("/test/delete-recording?recordingId=$recordingId") { header("X-Member-Id", creator.toString()) }
                    .status shouldBe HttpStatusCode.OK
                rawDirectory.exists() shouldBe false
                // Only THIS recording's directory -- never the shared root.
                rawRoot.exists() shouldBe true
            }
        }

        test("deleteRecording: throttled once the dedicated delete budget is exhausted, without touching the stop budget") {
            testApplication {
                application {
                    install(StatusPages) { installConferenceRecordingExceptionHandlers() }
                    routing {
                        registerConferenceRecordingTestRoutes(
                            deleteLimiter = FederationInboxRateLimiter(maxRequests = 1, window = 1.minutes),
                        )
                    }
                }
                val creator = createTestMember("rec-delete-throttle@example.org")
                val roomId = createTestRoom(creator, "Sitzung")
                val first = seedRecording(roomId = roomId, startedByMemberId = creator, status = ConferenceRecordingStatus.READY)
                val second = seedRecording(roomId = roomId, startedByMemberId = creator, status = ConferenceRecordingStatus.READY)

                client
                    .post("/test/delete-recording?recordingId=$first") { header("X-Member-Id", creator.toString()) }
                    .status shouldBe HttpStatusCode.OK
                client
                    .post("/test/delete-recording?recordingId=$second") { header("X-Member-Id", creator.toString()) }
                    .status shouldBe HttpStatusCode.Conflict
                recordingExists(second) shouldBe true

                // The stop budget is untouched by the exhausted delete budget -- separate limiters.
                val stoppable =
                    client
                        .post("/test/start-recording?roomId=$roomId&accessLevel=BOARD_ONLY") { header("X-Member-Id", creator.toString()) }
                        .bodyAsText()
                        .toDto()
                        .id
                client
                    .post("/test/stop-recording?recordingId=$stoppable") { header("X-Member-Id", creator.toString()) }
                    .status shouldBe HttpStatusCode.OK
            }
        }
    })

/**
 * Fires [ConferenceRecordingService.startRecording] for the same [roomId] from two different
 * callers on two real JVM threads, released together via [startLatch] so the two `transaction {}`
 * blocks in `startRecording` genuinely overlap -- mirrors [AuctionServiceTest]'s own
 * `runConcurrentBuyNow` helper (same "two-thread race, one must win" shape as its "one active
 * recording per room" invariant test).
 */
private fun runConcurrentStartRecording(
    client: HttpClient,
    roomId: String,
    callerA: Uuid,
    callerB: Uuid,
    timeoutSeconds: Long = 20,
): List<HttpStatusCode> {
    val startLatch = CountDownLatch(2)
    val doneLatch = CountDownLatch(2)
    val results = java.util.Collections.synchronizedList(mutableListOf<HttpStatusCode>())
    val failures = mutableListOf<Throwable>()

    fun startThread(callerId: Uuid): Thread =
        Thread {
            try {
                startLatch.countDown()
                startLatch.await(timeoutSeconds, TimeUnit.SECONDS)
                runBlocking {
                    val response =
                        client.post("/test/start-recording?roomId=$roomId&accessLevel=BOARD_ONLY") {
                            header("X-Member-Id", callerId.toString())
                        }
                    results += response.status
                }
            } catch (t: Throwable) {
                synchronized(failures) { failures += t }
            } finally {
                doneLatch.countDown()
            }
        }

    val t1 = startThread(callerA)
    val t2 = startThread(callerB)
    t1.start()
    t2.start()
    val completed = doneLatch.await(timeoutSeconds, TimeUnit.SECONDS)
    check(completed) { "Concurrent startRecording attempts did not complete within ${timeoutSeconds}s -- likely deadlock" }
    if (failures.isNotEmpty()) throw failures.first()
    return results.toList()
}

private fun cleanUpConferenceRecordingTestData(
    memberIds: List<Uuid>,
    roomIds: List<Uuid>,
    documentIds: List<Uuid>,
    folderIds: List<Uuid>,
) {
    transaction {
        // V1.0 Wave 2 "Aufzeichnung": startRecording/stopRecording/ConferenceRecordingCoordinator
        // write an AuditLogEntryTable row per mutation, referencing the acting member via a real
        // FK (actor_member_id) -- null it out first (audit_log_entry rows themselves are never
        // deleted, see AuditLogRecorder KDoc) so the MemberTable delete below does not violate
        // that FK. Same pattern AccountingServiceTest's own cleanUpAccountingTestData establishes.
        if (memberIds.isNotEmpty()) {
            AuditLogEntryTable.update({ AuditLogEntryTable.actorMemberId inList memberIds }) {
                it[actorMemberId] = null
            }
        }
        val recordingIds =
            ConferenceRecordingTable
                .selectAll()
                .filter { row ->
                    row[ConferenceRecordingTable.roomId] in roomIds || row[ConferenceRecordingTable.startedByMemberId] in memberIds
                }.map { it[ConferenceRecordingTable.id] }
        recordingIds.forEach { recordingId ->
            ConferenceRecordingTrackTable.deleteWhere { ConferenceRecordingTrackTable.recordingId eq recordingId }
        }
        recordingIds.forEach { recordingId ->
            ConferenceRecordingTable.deleteWhere { ConferenceRecordingTable.id eq recordingId }
        }
        // Wave 5 "Föderations-Gastbeitritt" D13 tests insert conference_participation rows
        // directly (a GAST "joining" a room, no LiveKit involved) -- delete before the room/member
        // rows they FK-reference.
        if (roomIds.isNotEmpty() || memberIds.isNotEmpty()) {
            ConferenceParticipationTable.deleteWhere {
                (ConferenceParticipationTable.roomId inList roomIds) or (ConferenceParticipationTable.memberId inList memberIds)
            }
        }
        roomIds.forEach { roomId -> ConferenceRoomTable.deleteWhere { ConferenceRoomTable.id eq roomId } }
        // deleteRecording's own tests seed a folder+document per recording -- delete them AFTER the
        // recording rows above (conference_recording.document_id is a real FK) and before the
        // members whose id they carry as created_by.
        documentIds.forEach { documentId -> DocumentTable.deleteWhere { DocumentTable.id eq documentId } }
        folderIds.forEach { folderId -> DocumentFolderTable.deleteWhere { DocumentFolderTable.id eq folderId } }
        memberIds.forEach { memberId -> AccountTable.deleteWhere { AccountTable.memberId eq memberId } }
        memberIds.forEach { memberId -> MemberTable.deleteWhere { MemberTable.id eq memberId } }
    }
}

/** Mirror of the `/test/list-recordings-page` body format -- "totalCount|limit|offset#row;row;...". */
private data class TestRecordingPage(
    val totalCount: Int,
    val limit: Int,
    val offset: Int,
    val rows: List<ConferenceRecordingDto>,
)

private fun String.toPage(): TestRecordingPage {
    val (header, rowsPart) = split("#", limit = 2)
    val headerParts = header.split("|")
    return TestRecordingPage(
        totalCount = headerParts[0].toInt(),
        limit = headerParts[1].toInt(),
        offset = headerParts[2].toInt(),
        rows =
            rowsPart
                .split(";")
                .filter { it.isNotBlank() }
                .map { it.toDto() },
    )
}

private fun StatusPagesConfig.installConferenceRecordingExceptionHandlers() {
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
 * Shared throwaway routes for [ConferenceRecordingService] using the ENABLED conference+recording
 * config and `ffmpegAvailable=true` -- mirrors [ConferenceServiceTest]'s own
 * `registerConferenceTestRoutes` style. Tests that need a DIFFERENT config (unconfigured/no-ffmpeg)
 * register their own one-off route instead of using this helper.
 */
private fun Route.registerConferenceRecordingTestRoutes(
    startLimiter: LoginRateLimiter = LoginRateLimiter(),
    stopLimiter: FederationInboxRateLimiter = FederationInboxRateLimiter(maxRequests = 30, window = 1.minutes),
    deleteLimiter: FederationInboxRateLimiter = FederationInboxRateLimiter(maxRequests = 10, window = 1.minutes),
    readLimiter: FederationInboxRateLimiter = FederationInboxRateLimiter(maxRequests = 60, window = 1.minutes),
    recordingConfig: ConferenceRecordingConfig = ENABLED_RECORDING_CONFIG,
) {
    fun service(call: ApplicationCall) =
        ConferenceRecordingService(
            call = call,
            ffmpegAvailable = true,
            config = ENABLED_CONFERENCE_CONFIG,
            recordingConfig = recordingConfig,
            startRecordingRateLimiter = startLimiter,
            stopRecordingRateLimiter = stopLimiter,
            deleteRecordingRateLimiter = deleteLimiter,
            readRateLimiter = readLimiter,
        )

    post("/test/start-recording") {
        val q = call.request.queryParameters
        val dto = service(call).startRecording(roomId = q["roomId"]!!, accessLevel = DocumentAccessLevel.valueOf(q["accessLevel"]!!))
        call.respondText(dto.toPipeString())
    }
    post("/test/stop-recording") {
        val q = call.request.queryParameters
        val dto = service(call).stopRecording(q["recordingId"]!!)
        call.respondText(dto.toPipeString())
    }
    post("/test/delete-recording") {
        val q = call.request.queryParameters
        call.respondText(service(call).deleteRecording(q["recordingId"]!!).toString())
    }
    get("/test/active-recording") {
        val q = call.request.queryParameters
        val dtos = service(call).getActiveRecording(q["roomId"]!!)
        call.respondText(dtos.joinToString(";") { it.toPipeString() })
    }
    get("/test/list-recordings") {
        val q = call.request.queryParameters
        val page = service(call).listRecordings(ConferenceRecordingListQuery(roomId = q["roomId"]))
        call.respondText(page.rows.joinToString(";") { it.toPipeString() })
    }
    // Pagination-aware variant -- "totalCount|limit|offset#row;row;...". A separate route rather
    // than a widened /test/list-recordings, so the pre-pagination access-filter tests above keep
    // asserting against their own unchanged, row-only body format.
    get("/test/list-recordings-page") {
        val q = call.request.queryParameters
        val page =
            service(call).listRecordings(
                ConferenceRecordingListQuery(
                    roomId = q["roomId"],
                    limit = q["limit"]?.toInt() ?: ConferenceRecordingListQuery.DEFAULT_LIMIT,
                    offset = q["offset"]?.toInt() ?: 0,
                ),
            )
        call.respondText("${page.totalCount}|${page.limit}|${page.offset}#" + page.rows.joinToString(";") { it.toPipeString() })
    }
}

/** id|roomId|status|startedByMemberId|accessLevel|documentId|mediaUrl|trackCount|failureReason */
private fun ConferenceRecordingDto.toPipeString(): String =
    "$id|$roomId|$status|$startedByMemberId|$accessLevel|$documentId|$mediaUrl|$trackCount|$failureReason"

private fun String.toDto(): ConferenceRecordingDto {
    val parts = split("|")
    return ConferenceRecordingDto(
        id = parts[0],
        roomId = parts[1],
        roomTitle = "",
        status = ConferenceRecordingStatus.valueOf(parts[2]),
        startedByMemberId = parts[3],
        startedByDisplayName = "",
        startedAt = DbClock.nowLocalDateTime(),
        stoppedAt = null,
        readyAt = null,
        durationSeconds = null,
        accessLevel = DocumentAccessLevel.valueOf(parts[4]),
        documentId = parts[5].takeIf { it != "null" },
        mediaUrl = parts[6].takeIf { it != "null" },
        fileSizeBytes = null,
        trackCount = parts[7].toInt(),
        failureReason = parts[8].takeIf { it != "null" },
    )
}

/** enabled|ffmpegAvailable|maxDurationMinutes */
private fun ConferenceRecordingAvailabilityDto.toPipeString(): String = "$enabled|$ffmpegAvailable|$maxDurationMinutes"
