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
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.server.federation.FederationInboxRateLimiter
import network.lapis.cloud.server.security.LoginRateLimiter
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.ConferenceRecordingAvailabilityDto
import network.lapis.cloud.shared.domain.ConferenceRecordingDto
import network.lapis.cloud.shared.domain.ConferenceRecordingStatus
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

        beforeSpec {
            DatabaseConfig.connect()
            DevSeedData.seedIfEmpty(force = true)
        }

        afterSpec { cleanUpConferenceRecordingTestData(createdMemberIds, createdRoomIds) }

        fun createTestMember(
            email: String,
            status: MemberStatus = MemberStatus.AKTIV,
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

        // ── getRecordingAvailability ─────────────────────────────────────────

        test("getRecordingAvailability: all three gates true -> enabled=true; any one false -> enabled=false, never throws") {
            testApplication {
                application {
                    install(StatusPages) { installConferenceRecordingExceptionHandlers() }
                    routing {
                        get("/test/availability-all-true") {
                            val dto =
                                ConferenceRecordingService(
                                    call,
                                    true,
                                    ENABLED_CONFERENCE_CONFIG,
                                    ENABLED_RECORDING_CONFIG,
                                ).getRecordingAvailability()
                            call.respondText(dto.toPipeString())
                        }
                        get("/test/availability-no-ffmpeg") {
                            val dto =
                                ConferenceRecordingService(
                                    call,
                                    false,
                                    ENABLED_CONFERENCE_CONFIG,
                                    ENABLED_RECORDING_CONFIG,
                                ).getRecordingAvailability()
                            call.respondText(dto.toPipeString())
                        }
                        get("/test/availability-recording-disabled") {
                            val dto =
                                ConferenceRecordingService(
                                    call,
                                    true,
                                    ENABLED_CONFERENCE_CONFIG,
                                    DISABLED_RECORDING_CONFIG,
                                ).getRecordingAvailability()
                            call.respondText(dto.toPipeString())
                        }
                        get("/test/availability-conference-disabled") {
                            val dto =
                                ConferenceRecordingService(
                                    call,
                                    true,
                                    DISABLED_CONFERENCE_CONFIG,
                                    ENABLED_RECORDING_CONFIG,
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
                                    call,
                                    true,
                                    ENABLED_CONFERENCE_CONFIG,
                                    ENABLED_RECORDING_CONFIG,
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
                                ConferenceRecordingService(call, true, ENABLED_CONFERENCE_CONFIG, DISABLED_RECORDING_CONFIG)
                                    .startRecording(q["roomId"]!!, DocumentAccessLevel.valueOf(q["accessLevel"]!!))
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
                                ConferenceRecordingService(call, false, ENABLED_CONFERENCE_CONFIG, ENABLED_RECORDING_CONFIG)
                                    .startRecording(q["roomId"]!!, DocumentAccessLevel.valueOf(q["accessLevel"]!!))
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
                val outcomes = runConcurrentStartRecording(client, roomId.toString(), creator, Uuid.parse(BOARD_ID))
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
                val guest = createTestMember("rec-d13-guest@example.org", status = MemberStatus.GAST)
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

                val neverJoinedGuest = createTestMember("rec-d13-never@example.org", status = MemberStatus.GAST)
                client
                    .get(
                        "/test/active-recording?roomId=$openRoomId",
                    ) { header("X-Member-Id", neverJoinedGuest.toString()) }
                    .status shouldBe
                    HttpStatusCode.Forbidden

                val closedRoomGuest = createTestMember("rec-d13-closed@example.org", status = MemberStatus.GAST)
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

                listOf(MemberStatus.ANTRAG, MemberStatus.AUSGETRETEN, MemberStatus.ABGELEHNT).forEach { status ->
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
        memberIds.forEach { memberId -> AccountTable.deleteWhere { AccountTable.memberId eq memberId } }
        memberIds.forEach { memberId -> MemberTable.deleteWhere { MemberTable.id eq memberId } }
    }
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
    readLimiter: FederationInboxRateLimiter = FederationInboxRateLimiter(maxRequests = 60, window = 1.minutes),
) {
    fun service(call: ApplicationCall) =
        ConferenceRecordingService(
            call,
            true,
            ENABLED_CONFERENCE_CONFIG,
            ENABLED_RECORDING_CONFIG,
            startLimiter,
            stopLimiter,
            readLimiter,
        )

    post("/test/start-recording") {
        val q = call.request.queryParameters
        val dto = service(call).startRecording(q["roomId"]!!, DocumentAccessLevel.valueOf(q["accessLevel"]!!))
        call.respondText(dto.toPipeString())
    }
    post("/test/stop-recording") {
        val q = call.request.queryParameters
        val dto = service(call).stopRecording(q["recordingId"]!!)
        call.respondText(dto.toPipeString())
    }
    get("/test/active-recording") {
        val q = call.request.queryParameters
        val dtos = service(call).getActiveRecording(q["roomId"]!!)
        call.respondText(dtos.joinToString(";") { it.toPipeString() })
    }
    get("/test/list-recordings") {
        val q = call.request.queryParameters
        val dtos = service(call).listRecordings(q["roomId"])
        call.respondText(dtos.joinToString(";") { it.toPipeString() })
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
