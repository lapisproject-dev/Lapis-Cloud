package network.lapis.cloud.server.rpc

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.statement.HttpResponse
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
import network.lapis.cloud.server.conference.ConferenceConfig
import network.lapis.cloud.server.conference.ConferenceStreamingConfig
import network.lapis.cloud.server.conference.DefaultSecretBallotStreamGuard
import network.lapis.cloud.server.conference.LiveKitAdminClient
import network.lapis.cloud.server.conference.LiveKitAdminException
import network.lapis.cloud.server.conference.LiveKitEgressClient
import network.lapis.cloud.server.conference.LiveKitEgressInfo
import network.lapis.cloud.server.conference.LiveKitParticipantInfo
import network.lapis.cloud.server.conference.LiveKitRoomInfo
import network.lapis.cloud.server.conference.NoOpSecretBallotStreamGuard
import network.lapis.cloud.server.conference.SecretBallotStreamGuard
import network.lapis.cloud.server.conference.StreamPoller
import network.lapis.cloud.server.crypto.SecretBox
import network.lapis.cloud.server.db.DatabaseConfig
import network.lapis.cloud.server.db.DbClock
import network.lapis.cloud.server.db.DevSeedData
import network.lapis.cloud.server.db.generated.AccountTable
import network.lapis.cloud.server.db.generated.AuditLogEntryTable
import network.lapis.cloud.server.db.generated.CommitteeMembershipTable
import network.lapis.cloud.server.db.generated.CommitteeTable
import network.lapis.cloud.server.db.generated.ConferenceRoomTable
import network.lapis.cloud.server.db.generated.ConferenceStreamDestinationTable
import network.lapis.cloud.server.db.generated.ConferenceStreamTable
import network.lapis.cloud.server.db.generated.ConferenceStreamTargetTable
import network.lapis.cloud.server.db.generated.ElectionBallotSelectionTable
import network.lapis.cloud.server.db.generated.ElectionBallotTable
import network.lapis.cloud.server.db.generated.ElectionBoardMemberTable
import network.lapis.cloud.server.db.generated.ElectionEligibleVoterTable
import network.lapis.cloud.server.db.generated.ElectionParticipationTable
import network.lapis.cloud.server.db.generated.ElectionTable
import network.lapis.cloud.server.db.generated.MeetingTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.server.db.generated.MotionTable
import network.lapis.cloud.server.db.generated.SystemicConsensusEligibleVoterTable
import network.lapis.cloud.server.db.generated.SystemicConsensusOptionTable
import network.lapis.cloud.server.db.generated.SystemicConsensusParticipationTable
import network.lapis.cloud.server.db.generated.SystemicConsensusTable
import network.lapis.cloud.server.federation.FederationInboxRateLimiter
import network.lapis.cloud.server.security.LoginRateLimiter
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.CommitteeRole
import network.lapis.cloud.shared.domain.CommitteeType
import network.lapis.cloud.shared.domain.ConferenceStreamLatencyMode
import network.lapis.cloud.shared.domain.ConferenceStreamLayout
import network.lapis.cloud.shared.domain.ConferenceStreamPauseReason
import network.lapis.cloud.shared.domain.ConferenceStreamPlatform
import network.lapis.cloud.shared.domain.ConferenceStreamStatus
import network.lapis.cloud.shared.domain.ElectionAnswer
import network.lapis.cloud.shared.domain.ElectionBallotInput
import network.lapis.cloud.shared.domain.ElectionOpenInput
import network.lapis.cloud.shared.domain.ElectionStatus
import network.lapis.cloud.shared.domain.ElectionType
import network.lapis.cloud.shared.domain.MeetingFormat
import network.lapis.cloud.shared.domain.MeetingStatus
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.domain.MotionStatus
import network.lapis.cloud.shared.domain.SystemicConsensusBallotInput
import network.lapis.cloud.shared.domain.SystemicConsensusBindingness
import network.lapis.cloud.shared.domain.SystemicConsensusOpenInput
import network.lapis.cloud.shared.rpc.BadRequestException
import network.lapis.cloud.shared.rpc.ConflictException
import network.lapis.cloud.shared.rpc.ForbiddenException
import network.lapis.cloud.shared.rpc.NotFoundException
import network.lapis.cloud.shared.rpc.UnauthenticatedException
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import java.util.Base64
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.minutes
import kotlin.uuid.Uuid

private const val ADMIN_ID = "00000000-0000-0000-0000-000000000001"

private val ENABLED_CONFERENCE_CONFIG =
    ConferenceConfig.load { key ->
        when (key) {
            "LAPIS_LIVEKIT_URL" -> "ws://localhost:7880"
            "LAPIS_LIVEKIT_API_KEY" -> "test-livekit-key"
            "LAPIS_LIVEKIT_API_SECRET" -> "test-livekit-secret-at-least-32-bytes-long!!"
            else -> null
        }
    }

private val TEST_ENCRYPTION_KEY_B64 = Base64.getEncoder().encodeToString(ByteArray(32) { it.toByte() })

/** `pauseVerifyTimeoutSeconds=2` -- short enough that the deliberately-never-confirming scenarios (#16/#17) finish in low seconds, not the 20s production default. */
private val ENABLED_STREAMING_CONFIG =
    ConferenceStreamingConfig.load { key ->
        when (key) {
            "LAPIS_STREAMING_ENABLED" -> "true"
            "LAPIS_SECRET_ENCRYPTION_KEY" -> TEST_ENCRYPTION_KEY_B64
            "LAPIS_STREAM_MAX_DESTINATIONS" -> "5"
            "LAPIS_STREAMING_PAUSE_VERIFY_TIMEOUT_SECONDS" -> "2"
            else -> null
        }
    }

/**
 * V1.0 Videokonferenzen, Wave 9 "Stream-Pause bei geheimen Abstimmungen" -- the wave's own core test
 * suite (plan §8.1, scenarios 1-19) plus its concurrency/race matrix (plan §8.2, R1-R5), in one file
 * so the whole cross-domain behaviour (Election/SystemicConsensus <-> ConferenceStreaming, mediated by
 * [network.lapis.cloud.server.rpc.SecretBallotStreamLock]/[SecretBallotStreamGuard]) is discoverable
 * from a single place. Mirrors [ConferenceStreamingServiceTest]/[ElectionServiceTest]/
 * [SystemicConsensusServiceTest]'s own house style (throwaway routes, `testApplication`, direct
 * Exposed queries for DB-state assertions, [afterSpec] hard-delete cleanup) -- see those three files
 * for the established conventions this one reuses rather than reinvents.
 *
 * Every scenario that needs REAL quiescing/resume behaviour (not just "did the RPC not throw") wires a
 * real [DefaultSecretBallotStreamGuard] backed by [ControllableFakeLiveKitEgressClient] -- unlike
 * [ElectionServiceTest]/[SystemicConsensusServiceTest]/[BoardMembershipServiceTest], which use
 * [NoOpSecretBallotStreamGuard] throughout because none of THEIR scenarios exercise stream pausing at
 * all.
 */
class SecretBallotStreamPauseTest :
    FunSpec({
        val createdMemberIds = mutableListOf<Uuid>()
        val createdCommitteeIds = mutableListOf<Uuid>()
        val createdRoomIds = mutableListOf<Uuid>()
        val createdDestinationIds = mutableListOf<Uuid>()

        beforeSpec {
            DatabaseConfig.connect()
            DevSeedData.seedIfEmpty(force = true)
        }

        afterSpec {
            cleanUpSecretBallotPauseTestData(
                memberIds = createdMemberIds,
                committeeIds = createdCommitteeIds,
                roomIds = createdRoomIds,
                destinationIds = createdDestinationIds,
            )
        }

        // ── Fixture helpers -- same shape as ElectionServiceTest/SystemicConsensusServiceTest/ConferenceStreamingServiceTest ──

        fun createTestMember(
            email: String,
            status: MemberStatus = MemberStatus.AKTIV,
        ): Uuid {
            val id = Uuid.random()
            transaction {
                MemberTable.insert {
                    it[MemberTable.id] = id
                    it[displayName] = "Pause Testmitglied"
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

        fun createTestCommittee(name: String): Uuid {
            val id = Uuid.random()
            transaction {
                CommitteeTable.insert {
                    it[CommitteeTable.id] = id
                    it[CommitteeTable.name] = name
                    it[CommitteeTable.type] = CommitteeType.EXECUTIVE_BOARD
                    it[description] = "Pause-Testcommittee"
                    it[active] = true
                    it[quorumPercent] = 50
                    it[createdAt] = LocalDateTime(2026, 1, 1, 0, 0)
                }
            }
            createdCommitteeIds += id
            return id
        }

        fun addMember(
            committeeId: Uuid,
            memberId: Uuid,
            role: CommitteeRole,
        ) {
            transaction {
                CommitteeMembershipTable.insert {
                    it[id] = Uuid.random()
                    it[CommitteeMembershipTable.committeeId] = committeeId
                    it[CommitteeMembershipTable.memberId] = memberId
                    it[CommitteeMembershipTable.role] = role
                    it[since] = LocalDate(2020, 1, 1)
                    it[until] = null
                }
            }
        }

        fun createTestMeeting(committeeId: Uuid): Uuid {
            val id = Uuid.random()
            transaction {
                MeetingTable.insert {
                    it[MeetingTable.id] = id
                    it[MeetingTable.committeeId] = committeeId
                    it[title] = "Pause-Testsitzung"
                    it[MeetingTable.scheduledAt] = LocalDateTime(2026, 3, 1, 18, 0)
                    it[location] = "Vereinsheim"
                    it[format] = MeetingFormat.IN_PERSON
                    it[status] = MeetingStatus.PLANNED
                    it[calledBy] = null
                    it[calledAt] = null
                    it[chairMemberId] = null
                    it[minuteTakerMemberId] = null
                    it[protocolDocumentId] = null
                    it[createdAt] = LocalDateTime(2026, 3, 1, 18, 0)
                }
            }
            return id
        }

        /** Directly seeds an already-[MotionStatus.SCHEDULED] Motion, same shortcut [ElectionServiceTest]/[SystemicConsensusServiceTest] use. */
        fun createTerminierterMotion(
            committeeId: Uuid,
            meetingId: Uuid,
            submitterId: Uuid,
        ): Uuid {
            val id = Uuid.random()
            transaction {
                MotionTable.insert {
                    it[MotionTable.id] = id
                    it[targetCommitteeId] = committeeId
                    it[title] = "Pause-Testantrag"
                    it[rationale] = "Rationale"
                    it[text] = "Antragstext"
                    it[submitterMemberId] = submitterId
                    it[status] = MotionStatus.SCHEDULED
                    it[submittedAt] = LocalDateTime(2026, 1, 1, 0, 0)
                    it[reviewedBy] = submitterId
                    it[reviewedAt] = LocalDateTime(2026, 1, 1, 0, 0)
                    it[reviewNote] = null
                    it[MotionTable.meetingId] = meetingId
                    it[agendaItemId] = null
                    it[resolutionId] = null
                    it[withdrawnAt] = null
                }
            }
            return id
        }

        fun createTestRoom(
            creatorId: Uuid,
            title: String,
            meetingId: Uuid? = null,
        ): Uuid {
            val id = Uuid.random()
            val now = DbClock.nowLocalDateTime()
            transaction {
                ConferenceRoomTable.insert {
                    it[ConferenceRoomTable.id] = id
                    it[ConferenceRoomTable.title] = title
                    it[description] = ""
                    it[livekitRoomName] = "lc-pause-test-$id"
                    it[createdByMemberId] = creatorId
                    it[createdAt] = now
                    it[endedAt] = null
                    it[maxParticipants] = 25
                    it[ConferenceRoomTable.meetingId] = meetingId
                }
            }
            createdRoomIds += id
            return id
        }

        fun createTestDestination(
            label: String,
            creatorId: Uuid,
        ): Uuid {
            val id = Uuid.random()
            val now = DbClock.nowLocalDateTime()
            val secretBox = SecretBox(Base64.getDecoder().decode(TEST_ENCRYPTION_KEY_B64))
            transaction {
                ConferenceStreamDestinationTable.insert {
                    it[ConferenceStreamDestinationTable.id] = id
                    it[ConferenceStreamDestinationTable.label] = label
                    it[platform] = ConferenceStreamPlatform.GENERIC_RTMP
                    it[rtmpUrl] = "rtmp://sink.example.org:1935/live"
                    it[streamKeyCiphertext] = secretBox.seal(plaintext = "test-stream-key-123456", aad = id.toString())
                    it[streamKeySetAt] = now
                    it[createdByMemberId] = creatorId
                    it[createdAt] = now
                    it[enabled] = true
                }
            }
            createdDestinationIds += id
            return id
        }

        fun streamRow(streamId: Uuid) =
            transaction { ConferenceStreamTable.selectAll().where { ConferenceStreamTable.id eq streamId }.single() }

        fun streamCountForRoom(roomId: Uuid): Long =
            transaction {
                ConferenceStreamTable
                    .selectAll()
                    .where {
                        ConferenceStreamTable.roomId eq
                            roomId
                    }.count()
            }

        // ── Client-side helpers -- thin wrappers over the HTTP test routes, mirroring the existing three files' own style ──

        suspend fun HttpClient.startStream(
            memberId: Uuid,
            roomId: Uuid,
            destinationId: Uuid,
        ): HttpResponse =
            post("/test/start-stream?roomId=$roomId&destinationIds=$destinationId") { header("X-Member-Id", memberId.toString()) }

        suspend fun HttpClient.openElection(
            memberId: Uuid,
            motionId: Uuid,
            secret: Boolean,
        ): String {
            val body = post("/test/open-election/$motionId?secret=$secret") { header("X-Member-Id", memberId.toString()) }.bodyAsText()
            return body.substringBefore(":")
        }

        suspend fun HttpClient.appointElectionBoard(
            memberId: Uuid,
            electionId: String,
            board: List<Uuid>,
        ) {
            post(
                "/test/appoint-election-board/$electionId?memberIds=${board.joinToString(",")}",
            ) { header("X-Member-Id", memberId.toString()) }
        }

        suspend fun HttpClient.openVoting(
            memberId: Uuid,
            electionId: String,
        ): HttpResponse = post("/test/open-voting/$electionId") { header("X-Member-Id", memberId.toString()) }

        suspend fun HttpClient.castElectionBallot(
            memberId: Uuid,
            electionId: String,
        ): HttpResponse = post("/test/cast-election-ballot/$electionId?answer=YES") { header("X-Member-Id", memberId.toString()) }

        suspend fun HttpClient.closeVoting(
            memberId: Uuid,
            electionId: String,
        ): HttpResponse = post("/test/close-voting/$electionId") { header("X-Member-Id", memberId.toString()) }

        /** Full happy-path secret-election setup: open (YES_NO, so no candidate list needed) -> appoint a 3-member board -> openVoting. Returns the electionId + the board (any board member may cast). */
        suspend fun HttpClient.setUpAndOpenSecretElection(
            chair: Uuid,
            motionId: Uuid,
            board: List<Uuid>,
            secret: Boolean = true,
        ): String {
            val electionId = openElection(chair, motionId, secret)
            appointElectionBoard(chair, electionId, board)
            openVoting(board[0], electionId).status shouldBe HttpStatusCode.OK
            return electionId
        }

        suspend fun HttpClient.openSystemicConsensus(
            memberId: Uuid,
            motionId: Uuid,
            secret: Boolean,
        ): String {
            val body =
                post("/test/open-systemic_consensus/$motionId?secret=$secret") { header("X-Member-Id", memberId.toString()) }.bodyAsText()
            return body.substringBefore(":")
        }

        suspend fun HttpClient.freezeOptions(
            memberId: Uuid,
            id: String,
        ): HttpResponse = post("/test/freeze-optionen/$id") { header("X-Member-Id", memberId.toString()) }

        suspend fun HttpClient.setUpAndFreezeSecretSystemicConsensus(
            chair: Uuid,
            motionId: Uuid,
            secret: Boolean = true,
        ): String {
            val id = openSystemicConsensus(chair, motionId, secret)
            freezeOptions(chair, id).status shouldBe HttpStatusCode.OK
            return id
        }

        suspend fun HttpClient.castResistanceBallot(
            memberId: Uuid,
            id: String,
        ): HttpResponse {
            val optionIds =
                transaction {
                    SystemicConsensusOptionTable
                        .selectAll()
                        .where { SystemicConsensusOptionTable.systemicConsensusId eq Uuid.parse(id) }
                        .map { it[SystemicConsensusOptionTable.id] }
                }
            val resistances = optionIds.joinToString(",") { "$it:5" }
            return post("/test/cast-resistance/$id?resistances=$resistances") { header("X-Member-Id", memberId.toString()) }
        }

        suspend fun HttpClient.closeRating(
            memberId: Uuid,
            id: String,
        ): HttpResponse = post("/test/close-rating/$id") { header("X-Member-Id", memberId.toString()) }

        suspend fun HttpClient.reopenRating(
            memberId: Uuid,
            id: String,
        ): HttpResponse = post("/test/reopen-rating/$id") { header("X-Member-Id", memberId.toString()) }

        // ── Scenario 1 -- "no stream running" is a true no-op ────────────────────────────

        test("1: openVoting(secret) with no conference_stream row for the bound room -- true no-op, quiesce never mutates anything") {
            testApplication {
                val fakeClient = ControllableFakeLiveKitEgressClient()
                val guard = DefaultSecretBallotStreamGuard(liveKitEgressClient = fakeClient, streamingConfig = ENABLED_STREAMING_CONFIG)
                application {
                    install(StatusPages) { installSecretBallotPauseExceptionHandlers() }
                    routing { registerSecretBallotPauseTestRoutes(egressClient = fakeClient, streamGuard = guard) }
                }
                val committeeId = createTestCommittee("Pause-1")
                val chair = createTestMember("pause1-chair@example.org")
                addMember(committeeId, chair, CommitteeRole.CHAIR)
                val board = (1..3).map { createTestMember("pause1-wv$it@example.org") }
                val meetingId = createTestMeeting(committeeId)
                val roomId = createTestRoom(chair, "Pause-1 Raum", meetingId)
                val motionId = createTerminierterMotion(committeeId, meetingId, chair)

                client.setUpAndOpenSecretElection(chair, motionId, board)

                streamCountForRoom(roomId) shouldBe 0
                fakeClient.stopCalls.size shouldBe 0
                fakeClient.startCalls.size shouldBe 0
            }
        }

        // ── Scenario 2 -- coupling is opt-in, honestly bounded ────────────────────────────

        test("2: room NOT bound to the meeting -- a secret election opening leaves its unrelated LIVE stream untouched") {
            testApplication {
                val fakeClient = ControllableFakeLiveKitEgressClient()
                val guard = DefaultSecretBallotStreamGuard(liveKitEgressClient = fakeClient, streamingConfig = ENABLED_STREAMING_CONFIG)
                application {
                    install(StatusPages) { installSecretBallotPauseExceptionHandlers() }
                    routing { registerSecretBallotPauseTestRoutes(egressClient = fakeClient, streamGuard = guard) }
                }
                val committeeId = createTestCommittee("Pause-2")
                val chair = createTestMember("pause2-chair@example.org")
                addMember(committeeId, chair, CommitteeRole.CHAIR)
                val board = (1..3).map { createTestMember("pause2-wv$it@example.org") }
                val meetingId = createTestMeeting(committeeId)
                val motionId = createTerminierterMotion(committeeId, meetingId, chair)
                // Deliberately UNBOUND -- meetingId is null.
                val roomId = createTestRoom(chair, "Pause-2 Raum", meetingId = null)
                val destId = createTestDestination("Pause-2 Ziel", chair)

                client.startStream(chair, roomId, destId).status shouldBe HttpStatusCode.OK
                val streamId =
                    transaction {
                        ConferenceStreamTable
                            .selectAll()
                            .where {
                                ConferenceStreamTable.roomId eq roomId
                            }.single()[ConferenceStreamTable.id]
                    }

                client.setUpAndOpenSecretElection(chair, motionId, board)

                streamRow(streamId)[ConferenceStreamTable.status] shouldBe ConferenceStreamStatus.LIVE
                fakeClient.stopCalls.size shouldBe 0
            }
        }

        // ── Scenario 3 -- the core positive case ──────────────────────────────────────────

        test("3: bound room, LIVE stream, secret openVoting -- ends up PAUSED/SECRET_BALLOT, stopEgress called once") {
            testApplication {
                val fakeClient = ControllableFakeLiveKitEgressClient()
                val guard = DefaultSecretBallotStreamGuard(liveKitEgressClient = fakeClient, streamingConfig = ENABLED_STREAMING_CONFIG)
                application {
                    install(StatusPages) { installSecretBallotPauseExceptionHandlers() }
                    routing { registerSecretBallotPauseTestRoutes(egressClient = fakeClient, streamGuard = guard) }
                }
                val committeeId = createTestCommittee("Pause-3")
                val chair = createTestMember("pause3-chair@example.org")
                addMember(committeeId, chair, CommitteeRole.CHAIR)
                val board = (1..3).map { createTestMember("pause3-wv$it@example.org") }
                val meetingId = createTestMeeting(committeeId)
                val roomId = createTestRoom(chair, "Pause-3 Raum", meetingId)
                val destId = createTestDestination("Pause-3 Ziel", chair)
                val motionId = createTerminierterMotion(committeeId, meetingId, chair)

                client.startStream(chair, roomId, destId).status shouldBe HttpStatusCode.OK
                val streamId =
                    transaction {
                        ConferenceStreamTable
                            .selectAll()
                            .where {
                                ConferenceStreamTable.roomId eq roomId
                            }.single()[ConferenceStreamTable.id]
                    }

                client.setUpAndOpenSecretElection(chair, motionId, board)

                val row = streamRow(streamId)
                row[ConferenceStreamTable.status] shouldBe ConferenceStreamStatus.PAUSED
                row[ConferenceStreamTable.pauseReason] shouldBe ConferenceStreamPauseReason.SECRET_BALLOT
                row[ConferenceStreamTable.pausedAt] shouldNotBe null
                fakeClient.stopCalls.size shouldBe 1
            }
        }

        // ── Scenario 4 -- non-secret ballots never touch streaming ────────────────────────

        test("4: a non-secret election opening leaves a bound room's LIVE stream untouched") {
            testApplication {
                val fakeClient = ControllableFakeLiveKitEgressClient()
                val guard = DefaultSecretBallotStreamGuard(liveKitEgressClient = fakeClient, streamingConfig = ENABLED_STREAMING_CONFIG)
                application {
                    install(StatusPages) { installSecretBallotPauseExceptionHandlers() }
                    routing { registerSecretBallotPauseTestRoutes(egressClient = fakeClient, streamGuard = guard) }
                }
                val committeeId = createTestCommittee("Pause-4")
                val chair = createTestMember("pause4-chair@example.org")
                addMember(committeeId, chair, CommitteeRole.CHAIR)
                val board = (1..3).map { createTestMember("pause4-wv$it@example.org") }
                val meetingId = createTestMeeting(committeeId)
                val roomId = createTestRoom(chair, "Pause-4 Raum", meetingId)
                val destId = createTestDestination("Pause-4 Ziel", chair)
                val motionId = createTerminierterMotion(committeeId, meetingId, chair)

                client.startStream(chair, roomId, destId).status shouldBe HttpStatusCode.OK
                val streamId =
                    transaction {
                        ConferenceStreamTable
                            .selectAll()
                            .where {
                                ConferenceStreamTable.roomId eq roomId
                            }.single()[ConferenceStreamTable.id]
                    }

                client.setUpAndOpenSecretElection(chair, motionId, board, secret = false)

                streamRow(streamId)[ConferenceStreamTable.status] shouldBe ConferenceStreamStatus.LIVE
                fakeClient.stopCalls.size shouldBe 0
            }
        }

        // ── Scenario 5 -- THE most important test of the wave ─────────────────────────────

        test("5: castElectionBallot during PAUSING is rejected with ConflictException, writes NO ballot/participation row") {
            testApplication {
                // Never confirms -- the guard's own StopEgress "succeeds" but ListEgress never reports
                // termination, so the row stays PAUSING for the whole (short) verify-timeout window.
                val fakeClient = ControllableFakeLiveKitEgressClient(autoConfirmOnStop = false)
                val guard = DefaultSecretBallotStreamGuard(liveKitEgressClient = fakeClient, streamingConfig = ENABLED_STREAMING_CONFIG)
                application {
                    install(StatusPages) { installSecretBallotPauseExceptionHandlers() }
                    routing { registerSecretBallotPauseTestRoutes(egressClient = fakeClient, streamGuard = guard) }
                }
                val committeeId = createTestCommittee("Pause-5")
                val chair = createTestMember("pause5-chair@example.org")
                addMember(committeeId, chair, CommitteeRole.CHAIR)
                val board = (1..3).map { createTestMember("pause5-wv$it@example.org") }
                val voter = createTestMember("pause5-voter@example.org")
                addMember(committeeId, voter, CommitteeRole.MEMBER)
                val meetingId = createTestMeeting(committeeId)
                val roomId = createTestRoom(chair, "Pause-5 Raum", meetingId)
                val destId = createTestDestination("Pause-5 Ziel", chair)
                val motionId = createTerminierterMotion(committeeId, meetingId, chair)

                client.startStream(chair, roomId, destId).status shouldBe HttpStatusCode.OK
                val streamId =
                    transaction {
                        ConferenceStreamTable
                            .selectAll()
                            .where {
                                ConferenceStreamTable.roomId eq roomId
                            }.single()[ConferenceStreamTable.id]
                    }
                val electionId = client.setUpAndOpenSecretElection(chair, motionId, board)

                streamRow(streamId)[ConferenceStreamTable.status] shouldBe ConferenceStreamStatus.PAUSING

                val castResponse = client.castElectionBallot(voter, electionId)
                castResponse.status shouldBe HttpStatusCode.Conflict

                val wId = Uuid.parse(electionId)
                transaction { ElectionBallotTable.selectAll().where { ElectionBallotTable.electionId eq wId }.count() } shouldBe 0
                transaction {
                    ElectionParticipationTable.selectAll().where { ElectionParticipationTable.electionId eq wId }.count()
                } shouldBe
                    0
            }
        }

        // ── Scenario 6 -- ballots resume once the pause is confirmed ──────────────────────

        test("6: castElectionBallot succeeds once the stream is confirmed PAUSED") {
            testApplication {
                val fakeClient = ControllableFakeLiveKitEgressClient()
                val guard = DefaultSecretBallotStreamGuard(liveKitEgressClient = fakeClient, streamingConfig = ENABLED_STREAMING_CONFIG)
                application {
                    install(StatusPages) { installSecretBallotPauseExceptionHandlers() }
                    routing { registerSecretBallotPauseTestRoutes(egressClient = fakeClient, streamGuard = guard) }
                }
                val committeeId = createTestCommittee("Pause-6")
                val chair = createTestMember("pause6-chair@example.org")
                addMember(committeeId, chair, CommitteeRole.CHAIR)
                val board = (1..3).map { createTestMember("pause6-wv$it@example.org") }
                val voter = createTestMember("pause6-voter@example.org")
                addMember(committeeId, voter, CommitteeRole.MEMBER)
                val meetingId = createTestMeeting(committeeId)
                val roomId = createTestRoom(chair, "Pause-6 Raum", meetingId)
                val destId = createTestDestination("Pause-6 Ziel", chair)
                val motionId = createTerminierterMotion(committeeId, meetingId, chair)

                client.startStream(chair, roomId, destId).status shouldBe HttpStatusCode.OK
                val electionId = client.setUpAndOpenSecretElection(chair, motionId, board)

                val castResponse = client.castElectionBallot(voter, electionId)
                castResponse.status shouldBe HttpStatusCode.OK
            }
        }

        // ── Scenario 7 -- startStream hard-blocked while a secret ballot is open ──────────

        test("7: startStream is rejected with Conflict while a secret ballot is open -- no stream row, no LiveKit call") {
            testApplication {
                val fakeClient = ControllableFakeLiveKitEgressClient()
                val guard = DefaultSecretBallotStreamGuard(liveKitEgressClient = fakeClient, streamingConfig = ENABLED_STREAMING_CONFIG)
                application {
                    install(StatusPages) { installSecretBallotPauseExceptionHandlers() }
                    routing { registerSecretBallotPauseTestRoutes(egressClient = fakeClient, streamGuard = guard) }
                }
                val committeeId = createTestCommittee("Pause-7")
                val chair = createTestMember("pause7-chair@example.org")
                addMember(committeeId, chair, CommitteeRole.CHAIR)
                val board = (1..3).map { createTestMember("pause7-wv$it@example.org") }
                val meetingId = createTestMeeting(committeeId)
                val roomId = createTestRoom(chair, "Pause-7 Raum", meetingId)
                val destId = createTestDestination("Pause-7 Ziel", chair)
                val motionId = createTerminierterMotion(committeeId, meetingId, chair)

                client.setUpAndOpenSecretElection(chair, motionId, board)

                client.startStream(chair, roomId, destId).status shouldBe HttpStatusCode.Conflict
                streamCountForRoom(roomId) shouldBe 0
                fakeClient.startCalls.size shouldBe 0
            }
        }

        // ── Scenario 8 -- manual resumeStream hard-blocked while a secret ballot is open ──

        test("8: resumeStream (manual) is rejected with Conflict while a secret ballot is open -- stays PAUSED/SECRET_BALLOT") {
            testApplication {
                val fakeClient = ControllableFakeLiveKitEgressClient()
                val guard = DefaultSecretBallotStreamGuard(liveKitEgressClient = fakeClient, streamingConfig = ENABLED_STREAMING_CONFIG)
                application {
                    install(StatusPages) { installSecretBallotPauseExceptionHandlers() }
                    routing { registerSecretBallotPauseTestRoutes(egressClient = fakeClient, streamGuard = guard) }
                }
                val committeeId = createTestCommittee("Pause-8")
                val chair = createTestMember("pause8-chair@example.org")
                addMember(committeeId, chair, CommitteeRole.CHAIR)
                val board = (1..3).map { createTestMember("pause8-wv$it@example.org") }
                val meetingId = createTestMeeting(committeeId)
                val roomId = createTestRoom(chair, "Pause-8 Raum", meetingId)
                val destId = createTestDestination("Pause-8 Ziel", chair)
                val motionId = createTerminierterMotion(committeeId, meetingId, chair)

                client.startStream(chair, roomId, destId).status shouldBe HttpStatusCode.OK
                val streamId =
                    transaction {
                        ConferenceStreamTable
                            .selectAll()
                            .where {
                                ConferenceStreamTable.roomId eq roomId
                            }.single()[ConferenceStreamTable.id]
                    }
                client.setUpAndOpenSecretElection(chair, motionId, board)

                client
                    .post("/test/resume-stream?streamId=$streamId") { header("X-Member-Id", chair.toString()) }
                    .status shouldBe HttpStatusCode.Conflict

                val row = streamRow(streamId)
                row[ConferenceStreamTable.status] shouldBe ConferenceStreamStatus.PAUSED
                row[ConferenceStreamTable.pauseReason] shouldBe ConferenceStreamPauseReason.SECRET_BALLOT
            }
        }

        // ── Scenario 9 -- manual pause escalation, one-way ────────────────────────────────

        test(
            "9: manual pauseStream while PAUSED/SECRET_BALLOT escalates pause_reason to MANUAL; a later closeVoting does NOT auto-resume",
        ) {
            testApplication {
                val fakeClient = ControllableFakeLiveKitEgressClient()
                val guard = DefaultSecretBallotStreamGuard(liveKitEgressClient = fakeClient, streamingConfig = ENABLED_STREAMING_CONFIG)
                application {
                    install(StatusPages) { installSecretBallotPauseExceptionHandlers() }
                    routing { registerSecretBallotPauseTestRoutes(egressClient = fakeClient, streamGuard = guard) }
                }
                val committeeId = createTestCommittee("Pause-9")
                val chair = createTestMember("pause9-chair@example.org")
                addMember(committeeId, chair, CommitteeRole.CHAIR)
                val board = (1..3).map { createTestMember("pause9-wv$it@example.org") }
                val meetingId = createTestMeeting(committeeId)
                val roomId = createTestRoom(chair, "Pause-9 Raum", meetingId)
                val destId = createTestDestination("Pause-9 Ziel", chair)
                val motionId = createTerminierterMotion(committeeId, meetingId, chair)

                client.startStream(chair, roomId, destId).status shouldBe HttpStatusCode.OK
                val streamId =
                    transaction {
                        ConferenceStreamTable
                            .selectAll()
                            .where {
                                ConferenceStreamTable.roomId eq roomId
                            }.single()[ConferenceStreamTable.id]
                    }
                val electionId = client.setUpAndOpenSecretElection(chair, motionId, board)
                streamRow(streamId)[ConferenceStreamTable.status] shouldBe ConferenceStreamStatus.PAUSED

                client
                    .post("/test/pause-stream?streamId=$streamId") { header("X-Member-Id", chair.toString()) }
                    .status shouldBe HttpStatusCode.OK
                streamRow(streamId)[ConferenceStreamTable.pauseReason] shouldBe ConferenceStreamPauseReason.MANUAL

                val startCallsBeforeClose = fakeClient.startCalls.size
                client.closeVoting(board[0], electionId).status shouldBe HttpStatusCode.OK

                val row = streamRow(streamId)
                row[ConferenceStreamTable.status] shouldBe ConferenceStreamStatus.PAUSED
                row[ConferenceStreamTable.pauseReason] shouldBe ConferenceStreamPauseReason.MANUAL
                fakeClient.startCalls.size shouldBe startCallsBeforeClose
            }
        }

        // ── Scenario 10 -- stopStream remains allowed mid-PAUSING ─────────────────────────

        test(
            "10: stopStream is allowed while PAUSING -- ends ENDED once the confirmation loop sees the egress gone, " +
                "stopEgress called with the PAUSING egressId",
        ) {
            testApplication {
                // autoConfirmOnStop=false -- keeps the quiesce phase's OWN confirmation loop from
                // ever completing during openVoting, so the row is still genuinely PAUSING (not
                // already PAUSED) by the time stopStream is called, matching this scenario's actual
                // name/intent (stopStream mid-PAUSING). Security-audit MAJOR-1/MINOR-6 fix means
                // stopStream ITSELF now also needs egress confirmation before writing ENDED -- forced
                // via fakeClient.forceGone right before the stop-stream call below, simulating "the
                // EARLIER quiesce-triggered StopEgress actually took effect at LiveKit by now, even
                // though this guard's own confirm loop had already timed out and left the row
                // PAUSING". See scenario 10b for the genuine timeout/never-confirmed case.
                val fakeClient = ControllableFakeLiveKitEgressClient(autoConfirmOnStop = false)
                val guard = DefaultSecretBallotStreamGuard(liveKitEgressClient = fakeClient, streamingConfig = ENABLED_STREAMING_CONFIG)
                application {
                    install(StatusPages) { installSecretBallotPauseExceptionHandlers() }
                    routing { registerSecretBallotPauseTestRoutes(egressClient = fakeClient, streamGuard = guard) }
                }
                val committeeId = createTestCommittee("Pause-10")
                val chair = createTestMember("pause10-chair@example.org")
                addMember(committeeId, chair, CommitteeRole.CHAIR)
                val board = (1..3).map { createTestMember("pause10-wv$it@example.org") }
                val meetingId = createTestMeeting(committeeId)
                val roomId = createTestRoom(chair, "Pause-10 Raum", meetingId)
                val destId = createTestDestination("Pause-10 Ziel", chair)
                val motionId = createTerminierterMotion(committeeId, meetingId, chair)

                client.startStream(chair, roomId, destId).status shouldBe HttpStatusCode.OK
                val streamId =
                    transaction {
                        ConferenceStreamTable
                            .selectAll()
                            .where {
                                ConferenceStreamTable.roomId eq roomId
                            }.single()[ConferenceStreamTable.id]
                    }
                val liveEgressId = streamRow(streamId)[ConferenceStreamTable.livekitEgressId]!!
                client.setUpAndOpenSecretElection(chair, motionId, board)
                streamRow(streamId)[ConferenceStreamTable.status] shouldBe ConferenceStreamStatus.PAUSING

                fakeClient.forceGone(liveEgressId)
                client
                    .post("/test/stop-stream?streamId=$streamId") { header("X-Member-Id", chair.toString()) }
                    .status shouldBe HttpStatusCode.OK

                streamRow(streamId)[ConferenceStreamTable.status] shouldBe ConferenceStreamStatus.ENDED
                fakeClient.stopCalls.any { it.second == liveEgressId } shouldBe true
            }
        }

        test(
            "10b: stopStream leaves the row STOPPING (never ENDED) when the confirmation loop times out -- " +
                "StreamPoller.handleStopping confirms it on a later tick",
        ) {
            testApplication {
                // autoConfirmOnStop=false -- stopEgress is called (recorded), but the fake egress
                // never actually disappears from ListEgress, simulating a StopEgress that LiveKit
                // accepted but has not yet actually settled by the time
                // ENABLED_STREAMING_CONFIG.pauseVerifyTimeoutSeconds (2s in this test config) elapses.
                val fakeClient = ControllableFakeLiveKitEgressClient(autoConfirmOnStop = false)
                val guard = DefaultSecretBallotStreamGuard(liveKitEgressClient = fakeClient, streamingConfig = ENABLED_STREAMING_CONFIG)
                application {
                    install(StatusPages) { installSecretBallotPauseExceptionHandlers() }
                    routing { registerSecretBallotPauseTestRoutes(egressClient = fakeClient, streamGuard = guard) }
                }
                val committeeId = createTestCommittee("Pause-10b")
                val chair = createTestMember("pause10b-chair@example.org")
                addMember(committeeId, chair, CommitteeRole.CHAIR)
                val destId = createTestDestination("Pause-10b Ziel", chair)
                val roomId = createTestRoom(chair, "Pause-10b Raum", meetingId = null)

                client.startStream(chair, roomId, destId).status shouldBe HttpStatusCode.OK
                val streamId =
                    transaction {
                        ConferenceStreamTable
                            .selectAll()
                            .where {
                                ConferenceStreamTable.roomId eq roomId
                            }.single()[ConferenceStreamTable.id]
                    }
                val liveEgressId = streamRow(streamId)[ConferenceStreamTable.livekitEgressId]!!

                client
                    .post("/test/stop-stream?streamId=$streamId") { header("X-Member-Id", chair.toString()) }
                    .status shouldBe HttpStatusCode.OK

                // Timed out -- still STOPPING, NOT ENDED. This is the exact state
                // requireStreamQuiescedForBallot's MAJOR-1 fix now blocks ballot casting on.
                streamRow(streamId)[ConferenceStreamTable.status] shouldBe ConferenceStreamStatus.STOPPING
                fakeClient.stopCalls.any { it.second == liveEgressId } shouldBe true

                // StreamPoller's own confirm-and-retry loop (also part of the MAJOR-1/MINOR-6 fix)
                // picks it up on a later tick and confirms it once the egress is actually reported
                // gone.
                fakeClient.forceGone(liveEgressId)
                val poller = StreamPoller(liveKitEgressClient = fakeClient, streamingConfig = ENABLED_STREAMING_CONFIG)
                poller.tick()
                streamRow(streamId)[ConferenceStreamTable.status] shouldBe ConferenceStreamStatus.ENDED
            }
        }

        // ── Scenario 11 -- auto-resume on closeVoting ─────────────────────────────────────

        test("11: closeVoting auto-resumes -- new egress id, restartCount=1, pause_reason/paused_at cleared, back to LIVE") {
            testApplication {
                val fakeClient = ControllableFakeLiveKitEgressClient()
                val guard = DefaultSecretBallotStreamGuard(liveKitEgressClient = fakeClient, streamingConfig = ENABLED_STREAMING_CONFIG)
                application {
                    install(StatusPages) { installSecretBallotPauseExceptionHandlers() }
                    routing { registerSecretBallotPauseTestRoutes(egressClient = fakeClient, streamGuard = guard) }
                }
                val committeeId = createTestCommittee("Pause-11")
                val chair = createTestMember("pause11-chair@example.org")
                addMember(committeeId, chair, CommitteeRole.CHAIR)
                val board = (1..3).map { createTestMember("pause11-wv$it@example.org") }
                val meetingId = createTestMeeting(committeeId)
                val roomId = createTestRoom(chair, "Pause-11 Raum", meetingId)
                val destId = createTestDestination("Pause-11 Ziel", chair)
                val motionId = createTerminierterMotion(committeeId, meetingId, chair)

                client.startStream(chair, roomId, destId).status shouldBe HttpStatusCode.OK
                val streamId =
                    transaction {
                        ConferenceStreamTable
                            .selectAll()
                            .where {
                                ConferenceStreamTable.roomId eq roomId
                            }.single()[ConferenceStreamTable.id]
                    }
                val firstEgressId = streamRow(streamId)[ConferenceStreamTable.livekitEgressId]
                val electionId = client.setUpAndOpenSecretElection(chair, motionId, board)

                client.closeVoting(board[0], electionId).status shouldBe HttpStatusCode.OK

                val row = streamRow(streamId)
                row[ConferenceStreamTable.status] shouldBe ConferenceStreamStatus.LIVE
                row[ConferenceStreamTable.livekitEgressId] shouldNotBe firstEgressId
                row[ConferenceStreamTable.restartCount] shouldBe 1
                row[ConferenceStreamTable.pauseReason] shouldBe null
                row[ConferenceStreamTable.pausedAt] shouldBe null
            }
        }

        // ── Scenario 12 -- two simultaneous secret ballots on the SAME meeting ────────────

        test("12: two simultaneous secret elections on the same meeting -- stream stays paused until BOTH close") {
            testApplication {
                val fakeClient = ControllableFakeLiveKitEgressClient()
                val guard = DefaultSecretBallotStreamGuard(liveKitEgressClient = fakeClient, streamingConfig = ENABLED_STREAMING_CONFIG)
                application {
                    install(StatusPages) { installSecretBallotPauseExceptionHandlers() }
                    routing { registerSecretBallotPauseTestRoutes(egressClient = fakeClient, streamGuard = guard) }
                }
                val committeeId = createTestCommittee("Pause-12")
                val chair = createTestMember("pause12-chair@example.org")
                addMember(committeeId, chair, CommitteeRole.CHAIR)
                val board1 = (1..3).map { createTestMember("pause12-w1-$it@example.org") }
                val board2 = (1..3).map { createTestMember("pause12-w2-$it@example.org") }
                val meetingId = createTestMeeting(committeeId)
                val roomId = createTestRoom(chair, "Pause-12 Raum", meetingId)
                val destId = createTestDestination("Pause-12 Ziel", chair)
                val motion1 = createTerminierterMotion(committeeId, meetingId, chair)
                val motion2 = createTerminierterMotion(committeeId, meetingId, chair)

                client.startStream(chair, roomId, destId).status shouldBe HttpStatusCode.OK
                val streamId =
                    transaction {
                        ConferenceStreamTable
                            .selectAll()
                            .where {
                                ConferenceStreamTable.roomId eq roomId
                            }.single()[ConferenceStreamTable.id]
                    }
                val e1 = client.setUpAndOpenSecretElection(chair, motion1, board1)
                val e2 = client.setUpAndOpenSecretElection(chair, motion2, board2)
                streamRow(streamId)[ConferenceStreamTable.status] shouldBe ConferenceStreamStatus.PAUSED

                client.closeVoting(board1[0], e1).status shouldBe HttpStatusCode.OK
                val row = streamRow(streamId)
                row[ConferenceStreamTable.status] shouldBe ConferenceStreamStatus.PAUSED
                row[ConferenceStreamTable.pauseReason] shouldBe ConferenceStreamPauseReason.SECRET_BALLOT

                client.closeVoting(board2[0], e2).status shouldBe HttpStatusCode.OK
                streamRow(streamId)[ConferenceStreamTable.status] shouldBe ConferenceStreamStatus.LIVE
            }
        }

        // ── Scenario 13 -- one secret + one open ballot together ──────────────────────────

        test("13: a secret and a non-secret election open together -- closing the non-secret one leaves the stream paused") {
            testApplication {
                val fakeClient = ControllableFakeLiveKitEgressClient()
                val guard = DefaultSecretBallotStreamGuard(liveKitEgressClient = fakeClient, streamingConfig = ENABLED_STREAMING_CONFIG)
                application {
                    install(StatusPages) { installSecretBallotPauseExceptionHandlers() }
                    routing { registerSecretBallotPauseTestRoutes(egressClient = fakeClient, streamGuard = guard) }
                }
                val committeeId = createTestCommittee("Pause-13")
                val chair = createTestMember("pause13-chair@example.org")
                addMember(committeeId, chair, CommitteeRole.CHAIR)
                val secretBoard = (1..3).map { createTestMember("pause13-s-$it@example.org") }
                val openBoard = (1..3).map { createTestMember("pause13-o-$it@example.org") }
                val meetingId = createTestMeeting(committeeId)
                val roomId = createTestRoom(chair, "Pause-13 Raum", meetingId)
                val destId = createTestDestination("Pause-13 Ziel", chair)
                val secretMotion = createTerminierterMotion(committeeId, meetingId, chair)
                val openMotion = createTerminierterMotion(committeeId, meetingId, chair)

                client.startStream(chair, roomId, destId).status shouldBe HttpStatusCode.OK
                val streamId =
                    transaction {
                        ConferenceStreamTable
                            .selectAll()
                            .where {
                                ConferenceStreamTable.roomId eq roomId
                            }.single()[ConferenceStreamTable.id]
                    }
                val secretElectionId = client.setUpAndOpenSecretElection(chair, secretMotion, secretBoard)
                streamRow(streamId)[ConferenceStreamTable.status] shouldBe ConferenceStreamStatus.PAUSED

                client.setUpAndOpenSecretElection(chair, openMotion, openBoard, secret = false)

                val openElectionId =
                    client.openElection(chair, openMotion, false).let {
                        transaction {
                            ElectionTable
                                .selectAll()
                                .where {
                                    ElectionTable.motionId eq
                                        openMotion
                                }.first { it[ElectionTable.secret].not() }[ElectionTable.id]
                                .toString()
                        }
                    }
                client.closeVoting(openBoard[0], openElectionId).status shouldBe HttpStatusCode.OK

                streamRow(streamId)[ConferenceStreamTable.status] shouldBe ConferenceStreamStatus.PAUSED

                client.closeVoting(secretBoard[0], secretElectionId).status shouldBe HttpStatusCode.OK
                streamRow(streamId)[ConferenceStreamTable.status] shouldBe ConferenceStreamStatus.LIVE
            }
        }

        // ── Scenario 14 -- abortElection resume semantics ─────────────────────────────────

        test("14: abortElection from OPEN auto-resumes; abortElection from PREPARATION attempts no resume") {
            testApplication {
                val fakeClient = ControllableFakeLiveKitEgressClient()
                val guard = DefaultSecretBallotStreamGuard(liveKitEgressClient = fakeClient, streamingConfig = ENABLED_STREAMING_CONFIG)
                application {
                    install(StatusPages) { installSecretBallotPauseExceptionHandlers() }
                    routing { registerSecretBallotPauseTestRoutes(egressClient = fakeClient, streamGuard = guard) }
                }
                val committeeId = createTestCommittee("Pause-14")
                val chair = createTestMember("pause14-chair@example.org")
                addMember(committeeId, chair, CommitteeRole.CHAIR)
                val board = (1..3).map { createTestMember("pause14-wv$it@example.org") }
                val meetingId = createTestMeeting(committeeId)
                val roomId = createTestRoom(chair, "Pause-14 Raum", meetingId)
                val destId = createTestDestination("Pause-14 Ziel", chair)
                val motionId = createTerminierterMotion(committeeId, meetingId, chair)

                client.startStream(chair, roomId, destId).status shouldBe HttpStatusCode.OK
                val streamId =
                    transaction {
                        ConferenceStreamTable
                            .selectAll()
                            .where {
                                ConferenceStreamTable.roomId eq roomId
                            }.single()[ConferenceStreamTable.id]
                    }
                val electionId = client.setUpAndOpenSecretElection(chair, motionId, board)
                streamRow(streamId)[ConferenceStreamTable.status] shouldBe ConferenceStreamStatus.PAUSED

                client.post("/test/abort-election/$electionId") { header("X-Member-Id", chair.toString()) }.status shouldBe
                    HttpStatusCode.OK
                streamRow(streamId)[ConferenceStreamTable.status] shouldBe ConferenceStreamStatus.LIVE

                // Second election, aborted while still in PREPARATION (never opened -> never paused).
                val motion2 = createTerminierterMotion(committeeId, meetingId, chair)
                val election2 = client.openElection(chair, motion2, true)
                val startCallsBefore = fakeClient.startCalls.size
                client.post("/test/abort-election/$election2") { header("X-Member-Id", chair.toString()) }.status shouldBe HttpStatusCode.OK
                fakeClient.startCalls.size shouldBe startCallsBefore
            }
        }

        // ── Scenario 15 -- SystemicConsensus mirrors 3/5/11/14 ────────────────────────────

        test("15a: SystemicConsensus mirror of #3 -- freezeOptions pauses a bound room's LIVE stream") {
            testApplication {
                val fakeClient = ControllableFakeLiveKitEgressClient()
                val guard = DefaultSecretBallotStreamGuard(liveKitEgressClient = fakeClient, streamingConfig = ENABLED_STREAMING_CONFIG)
                application {
                    install(StatusPages) { installSecretBallotPauseExceptionHandlers() }
                    routing { registerSecretBallotPauseTestRoutes(egressClient = fakeClient, streamGuard = guard) }
                }
                val committeeId = createTestCommittee("Pause-15a")
                val chair = createTestMember("pause15a-chair@example.org")
                addMember(committeeId, chair, CommitteeRole.CHAIR)
                val meetingId = createTestMeeting(committeeId)
                val roomId = createTestRoom(chair, "Pause-15a Raum", meetingId)
                val destId = createTestDestination("Pause-15a Ziel", chair)
                val motionId = createTerminierterMotion(committeeId, meetingId, chair)

                client.startStream(chair, roomId, destId).status shouldBe HttpStatusCode.OK
                val streamId =
                    transaction {
                        ConferenceStreamTable
                            .selectAll()
                            .where {
                                ConferenceStreamTable.roomId eq roomId
                            }.single()[ConferenceStreamTable.id]
                    }
                client.setUpAndFreezeSecretSystemicConsensus(chair, motionId)

                val row = streamRow(streamId)
                row[ConferenceStreamTable.status] shouldBe ConferenceStreamStatus.PAUSED
                row[ConferenceStreamTable.pauseReason] shouldBe ConferenceStreamPauseReason.SECRET_BALLOT
                fakeClient.stopCalls.size shouldBe 1
            }
        }

        test("15b: SystemicConsensus mirror of #5 -- castResistanceBallot during PAUSING is rejected, writes nothing") {
            testApplication {
                val fakeClient = ControllableFakeLiveKitEgressClient(autoConfirmOnStop = false)
                val guard = DefaultSecretBallotStreamGuard(liveKitEgressClient = fakeClient, streamingConfig = ENABLED_STREAMING_CONFIG)
                application {
                    install(StatusPages) { installSecretBallotPauseExceptionHandlers() }
                    routing { registerSecretBallotPauseTestRoutes(egressClient = fakeClient, streamGuard = guard) }
                }
                val committeeId = createTestCommittee("Pause-15b")
                val chair = createTestMember("pause15b-chair@example.org")
                addMember(committeeId, chair, CommitteeRole.CHAIR)
                val voter = createTestMember("pause15b-voter@example.org")
                addMember(committeeId, voter, CommitteeRole.MEMBER)
                val meetingId = createTestMeeting(committeeId)
                val roomId = createTestRoom(chair, "Pause-15b Raum", meetingId)
                val destId = createTestDestination("Pause-15b Ziel", chair)
                val motionId = createTerminierterMotion(committeeId, meetingId, chair)

                client.startStream(chair, roomId, destId).status shouldBe HttpStatusCode.OK
                val kId = client.setUpAndFreezeSecretSystemicConsensus(chair, motionId)

                val castResponse = client.castResistanceBallot(voter, kId)
                castResponse.status shouldBe HttpStatusCode.Conflict

                val kUuid = Uuid.parse(kId)
                transaction {
                    SystemicConsensusParticipationTable
                        .selectAll()
                        .where { SystemicConsensusParticipationTable.systemicConsensusId eq kUuid }
                        .count()
                } shouldBe 0
            }
        }

        test("15c: SystemicConsensus mirror of #11 -- closeRating auto-resumes with restartCount=1") {
            testApplication {
                val fakeClient = ControllableFakeLiveKitEgressClient()
                val guard = DefaultSecretBallotStreamGuard(liveKitEgressClient = fakeClient, streamingConfig = ENABLED_STREAMING_CONFIG)
                application {
                    install(StatusPages) { installSecretBallotPauseExceptionHandlers() }
                    routing { registerSecretBallotPauseTestRoutes(egressClient = fakeClient, streamGuard = guard) }
                }
                val committeeId = createTestCommittee("Pause-15c")
                val chair = createTestMember("pause15c-chair@example.org")
                addMember(committeeId, chair, CommitteeRole.CHAIR)
                val meetingId = createTestMeeting(committeeId)
                val roomId = createTestRoom(chair, "Pause-15c Raum", meetingId)
                val destId = createTestDestination("Pause-15c Ziel", chair)
                val motionId = createTerminierterMotion(committeeId, meetingId, chair)

                client.startStream(chair, roomId, destId).status shouldBe HttpStatusCode.OK
                val streamId =
                    transaction {
                        ConferenceStreamTable
                            .selectAll()
                            .where {
                                ConferenceStreamTable.roomId eq roomId
                            }.single()[ConferenceStreamTable.id]
                    }
                val firstEgressId = streamRow(streamId)[ConferenceStreamTable.livekitEgressId]
                val kId = client.setUpAndFreezeSecretSystemicConsensus(chair, motionId)

                client.closeRating(chair, kId).status shouldBe HttpStatusCode.OK

                val row = streamRow(streamId)
                row[ConferenceStreamTable.status] shouldBe ConferenceStreamStatus.LIVE
                row[ConferenceStreamTable.livekitEgressId] shouldNotBe firstEgressId
                row[ConferenceStreamTable.restartCount] shouldBe 1
            }
        }

        test("15d: SystemicConsensus mirror of #14 -- abortSystemicConsensus resumes only when the prior status was RATING") {
            testApplication {
                val fakeClient = ControllableFakeLiveKitEgressClient()
                val guard = DefaultSecretBallotStreamGuard(liveKitEgressClient = fakeClient, streamingConfig = ENABLED_STREAMING_CONFIG)
                application {
                    install(StatusPages) { installSecretBallotPauseExceptionHandlers() }
                    routing { registerSecretBallotPauseTestRoutes(egressClient = fakeClient, streamGuard = guard) }
                }
                val committeeId = createTestCommittee("Pause-15d")
                val chair = createTestMember("pause15d-chair@example.org")
                addMember(committeeId, chair, CommitteeRole.CHAIR)
                val meetingId = createTestMeeting(committeeId)
                val roomId = createTestRoom(chair, "Pause-15d Raum", meetingId)
                val destId = createTestDestination("Pause-15d Ziel", chair)
                val motionId = createTerminierterMotion(committeeId, meetingId, chair)

                client.startStream(chair, roomId, destId).status shouldBe HttpStatusCode.OK
                val streamId =
                    transaction {
                        ConferenceStreamTable
                            .selectAll()
                            .where {
                                ConferenceStreamTable.roomId eq roomId
                            }.single()[ConferenceStreamTable.id]
                    }
                val kId = client.setUpAndFreezeSecretSystemicConsensus(chair, motionId)
                streamRow(streamId)[ConferenceStreamTable.status] shouldBe ConferenceStreamStatus.PAUSED

                client.post("/test/abort-systemic_consensus/$kId") { header("X-Member-Id", chair.toString()) }.status shouldBe
                    HttpStatusCode.OK
                streamRow(streamId)[ConferenceStreamTable.status] shouldBe ConferenceStreamStatus.LIVE

                val motion2 = createTerminierterMotion(committeeId, meetingId, chair)
                val k2 = client.openSystemicConsensus(chair, motion2, true)
                val startCallsBefore = fakeClient.startCalls.size
                client.post("/test/abort-systemic_consensus/$k2") { header("X-Member-Id", chair.toString()) }.status shouldBe
                    HttpStatusCode.OK
                fakeClient.startCalls.size shouldBe startCallsBefore
            }
        }

        // ── Scenario 16 -- StopEgress failure -- fail-closed, no crash ────────────────────

        test("16: LiveKit stopEgress throws -- row stays PAUSING (fail-closed), openVoting RPC itself does not crash") {
            testApplication {
                val hostnameLike = "internal-livekit-worker-07.example.corp"
                val fakeClient =
                    ControllableFakeLiveKitEgressClient(
                        failStop = true,
                        stopErrorMessage = "Failed to connect to $hostnameLike: connection refused",
                        autoConfirmOnStop = false,
                    )
                val guard = DefaultSecretBallotStreamGuard(liveKitEgressClient = fakeClient, streamingConfig = ENABLED_STREAMING_CONFIG)
                application {
                    install(StatusPages) { installSecretBallotPauseExceptionHandlers() }
                    routing { registerSecretBallotPauseTestRoutes(egressClient = fakeClient, streamGuard = guard) }
                }
                val committeeId = createTestCommittee("Pause-16")
                val chair = createTestMember("pause16-chair@example.org")
                addMember(committeeId, chair, CommitteeRole.CHAIR)
                val board = (1..3).map { createTestMember("pause16-wv$it@example.org") }
                val meetingId = createTestMeeting(committeeId)
                val roomId = createTestRoom(chair, "Pause-16 Raum", meetingId)
                val destId = createTestDestination("Pause-16 Ziel", chair)
                val motionId = createTerminierterMotion(committeeId, meetingId, chair)

                client.startStream(chair, roomId, destId).status shouldBe HttpStatusCode.OK
                val streamId =
                    transaction {
                        ConferenceStreamTable
                            .selectAll()
                            .where {
                                ConferenceStreamTable.roomId eq roomId
                            }.single()[ConferenceStreamTable.id]
                    }

                val electionId = client.setUpAndOpenSecretElection(chair, motionId, board)
                electionId.isBlank() shouldBe false

                streamRow(streamId)[ConferenceStreamTable.status] shouldBe ConferenceStreamStatus.PAUSING

                val voter = createTestMember("pause16-voter@example.org")
                addMember(committeeId, voter, CommitteeRole.MEMBER)
                client.castElectionBallot(voter, electionId).status shouldBe HttpStatusCode.Conflict
            }
        }

        // ── Scenario 17 -- ListEgress confirmation times out -- StreamPoller finishes the job ──

        test("17: confirmation timeout leaves PAUSING; the next StreamPoller.tick() confirms PAUSED") {
            testApplication {
                val fakeClient = ControllableFakeLiveKitEgressClient(autoConfirmOnStop = false)
                val guard = DefaultSecretBallotStreamGuard(liveKitEgressClient = fakeClient, streamingConfig = ENABLED_STREAMING_CONFIG)
                application {
                    install(StatusPages) { installSecretBallotPauseExceptionHandlers() }
                    routing { registerSecretBallotPauseTestRoutes(egressClient = fakeClient, streamGuard = guard) }
                }
                val committeeId = createTestCommittee("Pause-17")
                val chair = createTestMember("pause17-chair@example.org")
                addMember(committeeId, chair, CommitteeRole.CHAIR)
                val board = (1..3).map { createTestMember("pause17-wv$it@example.org") }
                val meetingId = createTestMeeting(committeeId)
                val roomId = createTestRoom(chair, "Pause-17 Raum", meetingId)
                val destId = createTestDestination("Pause-17 Ziel", chair)
                val motionId = createTerminierterMotion(committeeId, meetingId, chair)

                client.startStream(chair, roomId, destId).status shouldBe HttpStatusCode.OK
                val streamId =
                    transaction {
                        ConferenceStreamTable
                            .selectAll()
                            .where {
                                ConferenceStreamTable.roomId eq roomId
                            }.single()[ConferenceStreamTable.id]
                    }
                val egressId = streamRow(streamId)[ConferenceStreamTable.livekitEgressId]!!

                client.setUpAndOpenSecretElection(chair, motionId, board)
                streamRow(streamId)[ConferenceStreamTable.status] shouldBe ConferenceStreamStatus.PAUSING

                // The egress is now, from the poller's point of view, confirmed gone -- StreamPoller's
                // own handlePausing retry (belt-and-braces on top of the guard's own, already-timed-out
                // confirmation loop) finishes the job.
                fakeClient.forceGone(egressId)
                StreamPoller(liveKitEgressClient = fakeClient, streamingConfig = ENABLED_STREAMING_CONFIG).tick()

                val row = streamRow(streamId)
                row[ConferenceStreamTable.status] shouldBe ConferenceStreamStatus.PAUSED
                row[ConferenceStreamTable.pauseReason] shouldBe ConferenceStreamPauseReason.SECRET_BALLOT
            }
        }

        // ── Scenario 18 -- setRoomMeeting is the new trust boundary ───────────────────────

        test("18: setRoomMeeting is rejected both un-binding from and binding into a meeting with an open secret ballot") {
            testApplication {
                val fakeClient = ControllableFakeLiveKitEgressClient()
                val guard = DefaultSecretBallotStreamGuard(liveKitEgressClient = fakeClient, streamingConfig = ENABLED_STREAMING_CONFIG)
                application {
                    install(StatusPages) { installSecretBallotPauseExceptionHandlers() }
                    routing { registerSecretBallotPauseTestRoutes(egressClient = fakeClient, streamGuard = guard) }
                }
                val committeeId = createTestCommittee("Pause-18")
                val chair = createTestMember("pause18-chair@example.org")
                addMember(committeeId, chair, CommitteeRole.CHAIR)
                val board = (1..3).map { createTestMember("pause18-wv$it@example.org") }
                val meetingId = createTestMeeting(committeeId)
                val roomBound = createTestRoom(chair, "Pause-18 Raum Gebunden", meetingId)
                val roomFree = createTestRoom(chair, "Pause-18 Raum Frei", meetingId = null)
                val motionId = createTerminierterMotion(committeeId, meetingId, chair)

                client.setUpAndOpenSecretElection(chair, motionId, board)

                // weg-binden -- security-audit MAJOR-3 fix: "Lösen" requires BOARD/ADMIN, so this
                // (and the hin-binden call below) uses ADMIN_ID rather than the mere room-creator
                // `chair`, so this scenario isolates the "blocked by an open/pending secret ballot"
                // behaviour from the (separately tested, see the MAJOR-2/MAJOR-3 scenarios further
                // down) authorization boundary.
                client
                    .post("/test/set-room-meeting?roomId=$roomBound&meetingId=") { header("X-Member-Id", ADMIN_ID) }
                    .status shouldBe HttpStatusCode.Conflict

                // hin-binden.
                client
                    .post("/test/set-room-meeting?roomId=$roomFree&meetingId=$meetingId") { header("X-Member-Id", ADMIN_ID) }
                    .status shouldBe HttpStatusCode.Conflict

                transaction {
                    ConferenceRoomTable.selectAll().where { ConferenceRoomTable.id eq roomBound }.single()[ConferenceRoomTable.meetingId]
                } shouldBe meetingId
                transaction {
                    ConferenceRoomTable.selectAll().where { ConferenceRoomTable.id eq roomFree }.single()[ConferenceRoomTable.meetingId]
                } shouldBe null
            }
        }

        // ── Scenario 24 -- security-audit MAJOR-2: hin-binden requires Gremiumsmitgliedschaft ──

        test("24: setRoomMeeting hin-binden is rejected for a room creator who is NOT a member of the target Sitzung's Gremium") {
            testApplication {
                val fakeClient = ControllableFakeLiveKitEgressClient()
                val guard = DefaultSecretBallotStreamGuard(liveKitEgressClient = fakeClient, streamingConfig = ENABLED_STREAMING_CONFIG)
                application {
                    install(StatusPages) { installSecretBallotPauseExceptionHandlers() }
                    routing { registerSecretBallotPauseTestRoutes(egressClient = fakeClient, streamGuard = guard) }
                }
                val committeeId = createTestCommittee("Pause-24")
                val chair = createTestMember("pause24-chair@example.org")
                addMember(committeeId, chair, CommitteeRole.CHAIR)
                val meetingId = createTestMeeting(committeeId)
                // Room creator has NO relationship to committeeId/meetingId at all.
                val outsider = createTestMember("pause24-outsider@example.org")
                val roomId = createTestRoom(outsider, "Pause-24 Raum", meetingId = null)

                client
                    .post("/test/set-room-meeting?roomId=$roomId&meetingId=$meetingId") { header("X-Member-Id", outsider.toString()) }
                    .status shouldBe HttpStatusCode.Forbidden

                transaction {
                    ConferenceRoomTable.selectAll().where { ConferenceRoomTable.id eq roomId }.single()[ConferenceRoomTable.meetingId]
                } shouldBe null
            }
        }

        test("24b: setRoomMeeting hin-binden succeeds for a room creator who IS an active member of the target Sitzung's Gremium") {
            testApplication {
                val fakeClient = ControllableFakeLiveKitEgressClient()
                val guard = DefaultSecretBallotStreamGuard(liveKitEgressClient = fakeClient, streamingConfig = ENABLED_STREAMING_CONFIG)
                application {
                    install(StatusPages) { installSecretBallotPauseExceptionHandlers() }
                    routing { registerSecretBallotPauseTestRoutes(egressClient = fakeClient, streamGuard = guard) }
                }
                val committeeId = createTestCommittee("Pause-24b")
                val member = createTestMember("pause24b-member@example.org")
                addMember(committeeId, member, CommitteeRole.MEMBER)
                val meetingId = createTestMeeting(committeeId)
                val roomId = createTestRoom(member, "Pause-24b Raum", meetingId = null)

                client
                    .post("/test/set-room-meeting?roomId=$roomId&meetingId=$meetingId") { header("X-Member-Id", member.toString()) }
                    .status shouldBe HttpStatusCode.OK

                transaction {
                    ConferenceRoomTable.selectAll().where { ConferenceRoomTable.id eq roomId }.single()[ConferenceRoomTable.meetingId]
                } shouldBe meetingId
            }
        }

        // ── Scenario 25 -- security-audit MAJOR-3: lösen requires BOARD/ADMIN, not merely the creator ──

        test("25: setRoomMeeting lösen (unbind) is rejected for the room creator even though they ARE a Gremiumsmitglied") {
            testApplication {
                val fakeClient = ControllableFakeLiveKitEgressClient()
                val guard = DefaultSecretBallotStreamGuard(liveKitEgressClient = fakeClient, streamingConfig = ENABLED_STREAMING_CONFIG)
                application {
                    install(StatusPages) { installSecretBallotPauseExceptionHandlers() }
                    routing { registerSecretBallotPauseTestRoutes(egressClient = fakeClient, streamGuard = guard) }
                }
                val committeeId = createTestCommittee("Pause-25")
                val chair = createTestMember("pause25-chair@example.org")
                addMember(committeeId, chair, CommitteeRole.CHAIR)
                val meetingId = createTestMeeting(committeeId)
                val roomId = createTestRoom(chair, "Pause-25 Raum", meetingId)

                client
                    .post("/test/set-room-meeting?roomId=$roomId&meetingId=") { header("X-Member-Id", chair.toString()) }
                    .status shouldBe HttpStatusCode.Forbidden

                transaction {
                    ConferenceRoomTable.selectAll().where { ConferenceRoomTable.id eq roomId }.single()[ConferenceRoomTable.meetingId]
                } shouldBe meetingId
            }
        }

        test("25b: setRoomMeeting lösen (unbind) succeeds for BOARD/ADMIN even though they are not the room creator") {
            testApplication {
                val fakeClient = ControllableFakeLiveKitEgressClient()
                val guard = DefaultSecretBallotStreamGuard(liveKitEgressClient = fakeClient, streamingConfig = ENABLED_STREAMING_CONFIG)
                application {
                    install(StatusPages) { installSecretBallotPauseExceptionHandlers() }
                    routing { registerSecretBallotPauseTestRoutes(egressClient = fakeClient, streamGuard = guard) }
                }
                val committeeId = createTestCommittee("Pause-25b")
                val chair = createTestMember("pause25b-chair@example.org")
                addMember(committeeId, chair, CommitteeRole.CHAIR)
                val meetingId = createTestMeeting(committeeId)
                val roomId = createTestRoom(chair, "Pause-25b Raum", meetingId)

                client
                    .post("/test/set-room-meeting?roomId=$roomId&meetingId=") { header("X-Member-Id", ADMIN_ID) }
                    .status shouldBe HttpStatusCode.OK

                transaction {
                    ConferenceRoomTable.selectAll().where { ConferenceRoomTable.id eq roomId }.single()[ConferenceRoomTable.meetingId]
                } shouldBe null
            }
        }

        // ── Scenario 26 -- security-audit MAJOR-3: lösen additionally blocked in a Vorbereitungs-Zustand ──

        test("26: setRoomMeeting lösen is rejected while the currently-bound Sitzung has a secret Election in PREPARATION (not yet OPEN)") {
            testApplication {
                val fakeClient = ControllableFakeLiveKitEgressClient()
                val guard = DefaultSecretBallotStreamGuard(liveKitEgressClient = fakeClient, streamingConfig = ENABLED_STREAMING_CONFIG)
                application {
                    install(StatusPages) { installSecretBallotPauseExceptionHandlers() }
                    routing { registerSecretBallotPauseTestRoutes(egressClient = fakeClient, streamGuard = guard) }
                }
                val committeeId = createTestCommittee("Pause-26")
                val chair = createTestMember("pause26-chair@example.org")
                addMember(committeeId, chair, CommitteeRole.CHAIR)
                val meetingId = createTestMeeting(committeeId)
                val roomId = createTestRoom(chair, "Pause-26 Raum", meetingId)
                val motionId = createTerminierterMotion(committeeId, meetingId, chair)

                // openElection alone -> ElectionStatus.PREPARATION, secret=true -- openVoting is
                // deliberately never called, so hasOpenSecretBallotForMeeting would still say
                // "false" (pre-MAJOR-3 behaviour); hasPendingOrOpenSecretBallot must say "true".
                client.openElection(chair, motionId, secret = true)

                client
                    .post("/test/set-room-meeting?roomId=$roomId&meetingId=") { header("X-Member-Id", ADMIN_ID) }
                    .status shouldBe HttpStatusCode.Conflict

                transaction {
                    ConferenceRoomTable.selectAll().where { ConferenceRoomTable.id eq roomId }.single()[ConferenceRoomTable.meetingId]
                } shouldBe meetingId
            }
        }

        // ── Scenario 27 -- security-audit MINOR-10: MAX_ROOMS_PER_MEETING cap ──────────────

        test("27: setRoomMeeting hin-binden is rejected once a Sitzung already has MAX_ROOMS_PER_MEETING (10) bound rooms") {
            testApplication {
                val fakeClient = ControllableFakeLiveKitEgressClient()
                val guard = DefaultSecretBallotStreamGuard(liveKitEgressClient = fakeClient, streamingConfig = ENABLED_STREAMING_CONFIG)
                application {
                    install(StatusPages) { installSecretBallotPauseExceptionHandlers() }
                    routing { registerSecretBallotPauseTestRoutes(egressClient = fakeClient, streamGuard = guard) }
                }
                val committeeId = createTestCommittee("Pause-27")
                val chair = createTestMember("pause27-chair@example.org")
                addMember(committeeId, chair, CommitteeRole.CHAIR)
                val meetingId = createTestMeeting(committeeId)
                repeat(10) { i -> createTestRoom(chair, "Pause-27 Raum $i", meetingId) }
                val eleventhRoom = createTestRoom(chair, "Pause-27 Raum 11", meetingId = null)

                client
                    .post("/test/set-room-meeting?roomId=$eleventhRoom&meetingId=$meetingId") { header("X-Member-Id", ADMIN_ID) }
                    .status shouldBe HttpStatusCode.Conflict

                transaction {
                    ConferenceRoomTable.selectAll().where { ConferenceRoomTable.id eq eleventhRoom }.single()[ConferenceRoomTable.meetingId]
                } shouldBe null
            }
        }

        // ── Scenario 28 -- security-audit MAJOR-4: maxRounds hard cap ──────────────────────

        test("28: openSystemicConsensus rejects maxRounds above the MAX_ROUNDS_HARD_CAP (10)") {
            testApplication {
                val fakeClient = ControllableFakeLiveKitEgressClient()
                val guard = DefaultSecretBallotStreamGuard(liveKitEgressClient = fakeClient, streamingConfig = ENABLED_STREAMING_CONFIG)
                application {
                    install(StatusPages) { installSecretBallotPauseExceptionHandlers() }
                    routing { registerSecretBallotPauseTestRoutes(egressClient = fakeClient, streamGuard = guard) }
                }
                val committeeId = createTestCommittee("Pause-28")
                val chair = createTestMember("pause28-chair@example.org")
                addMember(committeeId, chair, CommitteeRole.CHAIR)
                val meetingId = createTestMeeting(committeeId)
                val motionId = createTerminierterMotion(committeeId, meetingId, chair)

                client
                    .post("/test/open-systemic_consensus/$motionId?secret=true&maxRounds=11") {
                        header("X-Member-Id", chair.toString())
                    }.status shouldBe HttpStatusCode.Conflict

                // The cap itself (10) still succeeds.
                client
                    .post("/test/open-systemic_consensus/$motionId?secret=true&maxRounds=10") {
                        header("X-Member-Id", chair.toString())
                    }.status shouldBe HttpStatusCode.OK
            }
        }

        // ── Scenario 29 -- security-audit MAJOR-4: resume rate limit (never the pause direction) ──

        test(
            "29: resumeStreamsForMeeting declines auto-resume once its per-meeting rate limit is exhausted -- the " +
                "governance transition itself (closeRating) still succeeds, only the auto-restart is skipped",
        ) {
            testApplication {
                val fakeClient = ControllableFakeLiveKitEgressClient()
                // maxRequests=1 -- the FIRST pause/resume round (freezeOptions -> closeRating) consumes
                // the entire budget; the SECOND round's closeRating must decline to auto-resume.
                val guard =
                    DefaultSecretBallotStreamGuard(
                        liveKitEgressClient = fakeClient,
                        streamingConfig = ENABLED_STREAMING_CONFIG,
                        resumeRateLimiter = FederationInboxRateLimiter(maxRequests = 1, window = 5.minutes),
                    )
                application {
                    install(StatusPages) { installSecretBallotPauseExceptionHandlers() }
                    routing { registerSecretBallotPauseTestRoutes(egressClient = fakeClient, streamGuard = guard) }
                }
                val committeeId = createTestCommittee("Pause-29")
                val chair = createTestMember("pause29-chair@example.org")
                addMember(committeeId, chair, CommitteeRole.CHAIR)
                val meetingId = createTestMeeting(committeeId)
                val roomId = createTestRoom(chair, "Pause-29 Raum", meetingId)
                val destId = createTestDestination("Pause-29 Ziel", chair)
                val motionId = createTerminierterMotion(committeeId, meetingId, chair)

                client.startStream(chair, roomId, destId).status shouldBe HttpStatusCode.OK
                val streamId =
                    transaction {
                        ConferenceStreamTable
                            .selectAll()
                            .where {
                                ConferenceStreamTable.roomId eq roomId
                            }.single()[ConferenceStreamTable.id]
                    }

                // statusQuoOptionAuto=true (hardcoded by the test route) already seeds ONE option, so
                // freezeOptions's own "at least one option" guard is satisfied without adding another.
                val id = client.openSystemicConsensus(chair, motionId, secret = true)
                client.freezeOptions(chair, id).status shouldBe HttpStatusCode.OK
                streamRow(streamId)[ConferenceStreamTable.status] shouldBe ConferenceStreamStatus.PAUSED

                // Round 1 close -- consumes the ONLY rate-limit slot, auto-resume succeeds.
                client.closeRating(chair, id).status shouldBe HttpStatusCode.OK
                streamRow(streamId)[ConferenceStreamTable.status] shouldBe ConferenceStreamStatus.LIVE

                // Round 2: reopen (pauses again -- PAUSE is never rate-limited) -> close (resume is
                // now rate-limited).
                client.reopenRating(chair, id).status shouldBe HttpStatusCode.OK
                streamRow(streamId)[ConferenceStreamTable.status] shouldBe ConferenceStreamStatus.PAUSED
                val closeResponse = client.closeRating(chair, id)
                closeResponse.status shouldBe HttpStatusCode.OK // governance transition itself is NEVER blocked
                val afterDecline = streamRow(streamId)
                afterDecline[ConferenceStreamTable.status] shouldBe ConferenceStreamStatus.PAUSED // auto-resume declined
                // Security-audit-round-2 F3 fix -- pauseReason must escalate to MANUAL here, not stay
                // SECRET_BALLOT: StreamPoller.handlePaused's own crash-recovery reconciliation calls
                // restartEgressForStream DIRECTLY (bypassing THIS rate limiter entirely), so leaving
                // pauseReason=SECRET_BALLOT would let the very next poll tick silently re-attempt (and,
                // since the destination itself is perfectly fine here, actually SUCCEED) completely
                // unthrottled -- defeating the budget this rate limiter exists to enforce in the first
                // place. Escalating to MANUAL takes the stream out of that automatic machinery for good.
                afterDecline[ConferenceStreamTable.pauseReason] shouldBe ConferenceStreamPauseReason.MANUAL

                // A StreamPoller tick occurring right now must NOT auto-resume it either -- proves the
                // escalation, not just the rate-limiter's own one-time decline, is what stops the retry.
                StreamPoller(liveKitEgressClient = fakeClient, streamingConfig = ENABLED_STREAMING_CONFIG).tick()
                val afterTick = streamRow(streamId)
                afterTick[ConferenceStreamTable.status] shouldBe ConferenceStreamStatus.PAUSED
                afterTick[ConferenceStreamTable.pauseReason] shouldBe ConferenceStreamPauseReason.MANUAL
                // Exactly 2: the ORIGINAL startStream call + round-1's successful auto-resume -- never
                // a round-2 retry, neither from the declined resumeStreamsForMeeting call itself nor
                // from the StreamPoller tick just above.
                fakeClient.startCalls.size shouldBe 2
            }
        }

        // ── Scenario 30 -- security-audit MINOR-9: auto-resume respects destination.enabled ──

        test("30: auto-resume (resumeStreamsForMeeting) declines to restart a stream whose destination was disabled while paused") {
            testApplication {
                val fakeClient = ControllableFakeLiveKitEgressClient()
                val guard = DefaultSecretBallotStreamGuard(liveKitEgressClient = fakeClient, streamingConfig = ENABLED_STREAMING_CONFIG)
                application {
                    install(StatusPages) { installSecretBallotPauseExceptionHandlers() }
                    routing { registerSecretBallotPauseTestRoutes(egressClient = fakeClient, streamGuard = guard) }
                }
                val committeeId = createTestCommittee("Pause-30")
                val chair = createTestMember("pause30-chair@example.org")
                addMember(committeeId, chair, CommitteeRole.CHAIR)
                val board = (1..3).map { createTestMember("pause30-wv$it@example.org") }
                val meetingId = createTestMeeting(committeeId)
                val roomId = createTestRoom(chair, "Pause-30 Raum", meetingId)
                val destId = createTestDestination("Pause-30 Ziel", chair)
                val motionId = createTerminierterMotion(committeeId, meetingId, chair)

                client.startStream(chair, roomId, destId).status shouldBe HttpStatusCode.OK
                val streamId =
                    transaction {
                        ConferenceStreamTable
                            .selectAll()
                            .where {
                                ConferenceStreamTable.roomId eq roomId
                            }.single()[ConferenceStreamTable.id]
                    }

                client.setUpAndOpenSecretElection(chair, motionId, board)
                streamRow(streamId)[ConferenceStreamTable.status] shouldBe ConferenceStreamStatus.PAUSED

                // Operator disables the destination WHILE the stream sits paused.
                transaction {
                    ConferenceStreamDestinationTable.update({ ConferenceStreamDestinationTable.id eq destId }) { it[enabled] = false }
                }

                val electionId =
                    transaction {
                        ElectionTable.selectAll().where { ElectionTable.motionId eq motionId }.single()[ElectionTable.id]
                    }
                client.closeVoting(board[0], electionId.toString()).status shouldBe HttpStatusCode.OK

                // Auto-resume must NOT have restarted the egress -- stream stays PAUSED.
                streamRow(streamId)[ConferenceStreamTable.status] shouldBe ConferenceStreamStatus.PAUSED
                fakeClient.startCalls.size shouldBe 1 // only the ORIGINAL startStream call, no restart
            }
        }

        // ── Scenario 31 -- security-audit MINOR-7: startStream Tx2 never trusts a fail-open PAUSED row ──

        test(
            "31: a stream fail-open-marked PAUSED (simulating StreamPoller.handlePausing's own orphan race) WHILE " +
                "startStream's LiveKit call is still in flight ends up PAUSING (with the egress id recorded), never " +
                "trusted as PAUSED with an untracked live egress",
        ) {
            testApplication {
                lateinit var roomUuid: Uuid
                var injected = false
                val fakeClient =
                    ControllableFakeLiveKitEgressClient(
                        onStartRoomCompositeEgress = {
                            // Simulates StreamPoller.handlePausing's own (pre-MINOR-7-fix) fail-open
                            // conclusion -- a stream this VERY call is starting gets marked PAUSED by a
                            // DIFFERENT, concurrent actor (the poller) before this call's own Tx2 ever
                            // gets to look at the row again. Fires exactly once, synchronously, from
                            // INSIDE the (test-simulated) LiveKit call, mirroring how R2/R3 use this
                            // same hook for their own races.
                            if (!injected) {
                                injected = true
                                transaction {
                                    ConferenceStreamTable.update({
                                        (ConferenceStreamTable.roomId eq roomUuid) and
                                            (ConferenceStreamTable.status eq ConferenceStreamStatus.STARTING)
                                    }) {
                                        it[status] = ConferenceStreamStatus.PAUSED
                                        it[pauseReason] = ConferenceStreamPauseReason.SECRET_BALLOT
                                        it[pausedAt] = DbClock.nowLocalDateTime()
                                    }
                                }
                            }
                        },
                    )
                val guard = DefaultSecretBallotStreamGuard(liveKitEgressClient = fakeClient, streamingConfig = ENABLED_STREAMING_CONFIG)
                application {
                    install(StatusPages) { installSecretBallotPauseExceptionHandlers() }
                    routing { registerSecretBallotPauseTestRoutes(egressClient = fakeClient, streamGuard = guard) }
                }
                val chair = createTestMember("pause31-chair@example.org")
                val roomId = createTestRoom(chair, "Pause-31 Raum", meetingId = null)
                roomUuid = roomId
                val destId = createTestDestination("Pause-31 Ziel", chair)

                client.startStream(chair, roomId, destId).status shouldBe HttpStatusCode.OK
                val streamId =
                    transaction {
                        ConferenceStreamTable
                            .selectAll()
                            .where {
                                ConferenceStreamTable.roomId eq roomId
                            }.single()[ConferenceStreamTable.id]
                    }

                val row = streamRow(streamId)
                // NEVER PAUSED here -- that would mean a live, never-stopped egress hiding behind a
                // status that requireStreamQuiescedForBallot (with the MAJOR-1 STOPPING fix) treats as
                // fully quiesced.
                row[ConferenceStreamTable.status] shouldBe ConferenceStreamStatus.PAUSING
                row[ConferenceStreamTable.livekitEgressId] shouldNotBe null
            }
        }

        // ── Scenario 32 -- security-audit MAJOR-1: STOPPING joins the fail-closed ballot-casting blocklist ──

        test("32: castElectionBallot is rejected while the bound room's stream is STOPPING (StopEgress requested, not yet confirmed)") {
            testApplication {
                val fakeClient = ControllableFakeLiveKitEgressClient()
                val guard = DefaultSecretBallotStreamGuard(liveKitEgressClient = fakeClient, streamingConfig = ENABLED_STREAMING_CONFIG)
                application {
                    install(StatusPages) { installSecretBallotPauseExceptionHandlers() }
                    routing { registerSecretBallotPauseTestRoutes(egressClient = fakeClient, streamGuard = guard) }
                }
                val committeeId = createTestCommittee("Pause-32")
                val chair = createTestMember("pause32-chair@example.org")
                addMember(committeeId, chair, CommitteeRole.CHAIR)
                val board = (1..3).map { createTestMember("pause32-wv$it@example.org") }
                val meetingId = createTestMeeting(committeeId)
                val roomId = createTestRoom(chair, "Pause-32 Raum", meetingId)
                val motionId = createTerminierterMotion(committeeId, meetingId, chair)

                // Directly seeded STOPPING row -- MUST predate openVoting: markPausingForSecretBallot's
                // own candidate WHERE clause only ever matches STARTING/LIVE, so a pre-existing STOPPING
                // row is guaranteed untouched by opening the election, isolating this assertion to
                // requireStreamQuiescedForBallot's own blocklist.
                val streamId = Uuid.random()
                transaction {
                    ConferenceStreamTable.insert {
                        it[id] = streamId
                        it[ConferenceStreamTable.roomId] = roomId
                        it[startedByMemberId] = chair
                        it[status] = ConferenceStreamStatus.STOPPING
                        it[layout] = ConferenceStreamLayout.GRID
                        it[latencyMode] = ConferenceStreamLatencyMode.STANDARD
                        it[participantIdentity] = null
                        it[livekitEgressId] = "EG_pause32_stopping"
                        it[startedAt] = DbClock.nowLocalDateTime()
                        it[pausedAt] = null
                        it[endedAt] = null
                        it[restartCount] = 0
                        it[failureReason] = null
                    }
                }
                val electionId = client.setUpAndOpenSecretElection(chair, motionId, board)
                val castResponse = client.castElectionBallot(board[0], electionId)
                castResponse.status shouldBe HttpStatusCode.Conflict

                transaction {
                    ElectionParticipationTable
                        .selectAll()
                        .where { ElectionParticipationTable.electionId eq Uuid.parse(electionId) }
                        .count()
                } shouldBe 0
            }
        }

        // ── Scenario 19 -- no raw LiveKit error text ever reaches a DTO ────────────────────

        test("19: no raw LiveKit error text (a fake hostname) ever appears in the ElectionDto or ConferenceStreamDto responses") {
            testApplication {
                val hostnameLike = "leak-me-not.internal.example.corp"
                val fakeClient =
                    ControllableFakeLiveKitEgressClient(
                        failStop = true,
                        stopErrorMessage = "Failed to connect to $hostnameLike: connection refused",
                        autoConfirmOnStop = false,
                    )
                val guard = DefaultSecretBallotStreamGuard(liveKitEgressClient = fakeClient, streamingConfig = ENABLED_STREAMING_CONFIG)
                application {
                    install(StatusPages) { installSecretBallotPauseExceptionHandlers() }
                    routing { registerSecretBallotPauseTestRoutes(egressClient = fakeClient, streamGuard = guard) }
                }
                val committeeId = createTestCommittee("Pause-19")
                val chair = createTestMember("pause19-chair@example.org")
                addMember(committeeId, chair, CommitteeRole.CHAIR)
                val board = (1..3).map { createTestMember("pause19-wv$it@example.org") }
                val meetingId = createTestMeeting(committeeId)
                val roomId = createTestRoom(chair, "Pause-19 Raum", meetingId)
                val destId = createTestDestination("Pause-19 Ziel", chair)
                val motionId = createTerminierterMotion(committeeId, meetingId, chair)

                client.startStream(chair, roomId, destId).status shouldBe HttpStatusCode.OK

                val electionId = client.openElection(chair, motionId, true)
                client.appointElectionBoard(chair, electionId, board)
                val openVotingResponse = client.openVoting(board[0], electionId)
                openVotingResponse.status shouldBe HttpStatusCode.OK
                openVotingResponse.bodyAsText().contains(hostnameLike) shouldBe false

                val activeStreamResponse = client.get("/test/active-stream?roomId=$roomId") { header("X-Member-Id", chair.toString()) }
                activeStreamResponse.bodyAsText().contains(hostnameLike) shouldBe false
            }
        }

        // ══════════════════════════════════════════════════════════════════════════════════
        // ── Concurrency/race tests, plan §8.2 (R1-R5) ──────────────────────────────────────
        // ══════════════════════════════════════════════════════════════════════════════════

        // ── R1 -- startStream vs openVoting(secret), same room, released together ────────

        test("R1: startStream and openVoting(secret) racing on the same room -- never LIVE while a secret ballot is open") {
            testApplication {
                val fakeClient = ControllableFakeLiveKitEgressClient()
                val guard = DefaultSecretBallotStreamGuard(liveKitEgressClient = fakeClient, streamingConfig = ENABLED_STREAMING_CONFIG)
                application {
                    install(StatusPages) { installSecretBallotPauseExceptionHandlers() }
                    routing { registerSecretBallotPauseTestRoutes(egressClient = fakeClient, streamGuard = guard) }
                }
                val committeeId = createTestCommittee("Pause-R1")
                val chair = createTestMember("pauser1-chair@example.org")
                addMember(committeeId, chair, CommitteeRole.CHAIR)
                val board = (1..3).map { createTestMember("pauser1-wv$it@example.org") }
                val meetingId = createTestMeeting(committeeId)
                val roomId = createTestRoom(chair, "Pause-R1 Raum", meetingId)
                val destId = createTestDestination("Pause-R1 Ziel", chair)
                val motionId = createTerminierterMotion(committeeId, meetingId, chair)
                val electionId = client.openElection(chair, motionId, true)
                client.appointElectionBoard(chair, electionId, board)

                val startLatch = CountDownLatch(2)
                val doneLatch = CountDownLatch(2)
                val failures = Collections.synchronizedList(mutableListOf<Throwable>())

                fun race(action: suspend () -> Unit): Thread =
                    Thread {
                        try {
                            startLatch.countDown()
                            startLatch.await(20, TimeUnit.SECONDS)
                            runBlocking { action() }
                        } catch (t: Throwable) {
                            failures += t
                        } finally {
                            doneLatch.countDown()
                        }
                    }

                val t1 = race { client.startStream(chair, roomId, destId) }
                val t2 = race { client.openVoting(board[0], electionId) }
                t1.start()
                t2.start()
                check(doneLatch.await(20, TimeUnit.SECONDS)) { "R1 threads did not complete in time" }
                if (failures.isNotEmpty()) throw failures.first()

                val electionStatus =
                    transaction {
                        ElectionTable
                            .selectAll()
                            .where {
                                ElectionTable.id eq
                                    Uuid.parse(
                                        electionId,
                                    )
                            }.single()[ElectionTable.status]
                    }
                val streamRows = transaction { ConferenceStreamTable.selectAll().where { ConferenceStreamTable.roomId eq roomId }.toList() }
                val anyLive = streamRows.any { it[ConferenceStreamTable.status] == ConferenceStreamStatus.LIVE }
                // The invariant: never simultaneously "secret ballot open" AND "stream LIVE". Either the
                // election lost the race (still PREPARATION, startStream got there first and IS live),
                // or the election won (OPEN) and no stream in this room is LIVE (PAUSING/PAUSED, or
                // startStream itself was rejected with Conflict and never created a row at all).
                if (electionStatus == ElectionStatus.OPEN) {
                    anyLive shouldBe false
                }
                // Whichever stream row DOES exist must never have leaked a LiveKit egress: if a row was
                // created (startStream won its own race) it must carry an egress id once past STARTING.
                streamRows.filter { it[ConferenceStreamTable.status] != ConferenceStreamStatus.STARTING }.forEach { row ->
                    if (row[ConferenceStreamTable.status] != ConferenceStreamStatus.FAILED) {
                        row[ConferenceStreamTable.livekitEgressId] shouldNotBe null
                    }
                }
            }
        }

        // ── R2 -- openVoting lands exactly between startStream's two transactions ─────────

        test("R2: openVoting(secret) completes exactly between startStream's Tx1 and Tx2 -- Tx2 writes PAUSING, never LIVE") {
            testApplication {
                val readyLatch = CountDownLatch(1)
                val releaseLatch = CountDownLatch(1)
                val fakeClient =
                    ControllableFakeLiveKitEgressClient(
                        onStartRoomCompositeEgress = {
                            readyLatch.countDown()
                            releaseLatch.await(20, TimeUnit.SECONDS)
                        },
                    )
                val guard = DefaultSecretBallotStreamGuard(liveKitEgressClient = fakeClient, streamingConfig = ENABLED_STREAMING_CONFIG)
                application {
                    install(StatusPages) { installSecretBallotPauseExceptionHandlers() }
                    routing { registerSecretBallotPauseTestRoutes(egressClient = fakeClient, streamGuard = guard) }
                }
                val committeeId = createTestCommittee("Pause-R2")
                val chair = createTestMember("pauser2-chair@example.org")
                addMember(committeeId, chair, CommitteeRole.CHAIR)
                val board = (1..3).map { createTestMember("pauser2-wv$it@example.org") }
                val meetingId = createTestMeeting(committeeId)
                val roomId = createTestRoom(chair, "Pause-R2 Raum", meetingId)
                val destId = createTestDestination("Pause-R2 Ziel", chair)
                val motionId = createTerminierterMotion(committeeId, meetingId, chair)
                val electionId = client.openElection(chair, motionId, true)
                client.appointElectionBoard(chair, electionId, board)

                val failures = Collections.synchronizedList(mutableListOf<Throwable>())
                val startThread =
                    Thread {
                        try {
                            runBlocking { client.startStream(chair, roomId, destId) }
                        } catch (t: Throwable) {
                            failures += t
                        }
                    }
                startThread.start()
                check(readyLatch.await(20, TimeUnit.SECONDS)) { "startStream never reached its LiveKit call" }

                // startStream's Tx1 has committed (the room lock is released) and its LiveKit call is
                // now blocked -- openVoting can freely acquire the room lock and run to completion.
                client.openVoting(board[0], electionId).status shouldBe HttpStatusCode.OK

                releaseLatch.countDown()
                startThread.join(20_000)
                if (failures.isNotEmpty()) throw failures.first()

                val row = transaction { ConferenceStreamTable.selectAll().where { ConferenceStreamTable.roomId eq roomId }.single() }
                row[ConferenceStreamTable.status] shouldBe ConferenceStreamStatus.PAUSING
                row[ConferenceStreamTable.pauseReason] shouldBe ConferenceStreamPauseReason.SECRET_BALLOT
                row[ConferenceStreamTable.livekitEgressId] shouldNotBe null
            }
        }

        // ── R3 -- closeVoting auto-resume vs stopStream ────────────────────────────────────

        test(
            "R3: closeVoting-driven auto-resume racing stopStream -- never LIVE after ENDED, and " +
                "(security-audit-round-2 F1) the freshly-started egress is never silently leaked behind " +
                "a terminal row either",
        ) {
            testApplication {
                val readyLatch = CountDownLatch(1)
                val releaseLatch = CountDownLatch(1)
                // Test-infrastructure fix (uncovered while adding the F1 regression assertions below):
                // the block/release hook must NOT fire on the very first startRoomCompositeEgress call
                // -- that first call is this test's own INITIAL client.startStream(...) setup below,
                // needed just to get the stream into a LIVE (then auto-paused) state before the actual
                // race begins. A shared, always-on hook would make THAT setup call block for the full
                // releaseLatch timeout (nothing releases it until much later in the test), stealing the
                // readyLatch/releaseLatch signal from the call this test actually means to control
                // (closeThread's own auto-resume attempt) -- so the intended race between stopStream and
                // the auto-resume's LiveKit call would never genuinely happen; readyLatch would already
                // read as "signalled" from the unrelated first call, and the two threads would run
                // uncoordinated. Only the SECOND (and any later) startRoomCompositeEgress call is the
                // one this test wants to pause mid-flight.
                val startRoomCompositeEgressCallCount = AtomicInteger(0)
                val fakeClient =
                    ControllableFakeLiveKitEgressClient(
                        onStartRoomCompositeEgress = {
                            if (startRoomCompositeEgressCallCount.incrementAndGet() > 1) {
                                readyLatch.countDown()
                                releaseLatch.await(20, TimeUnit.SECONDS)
                            }
                        },
                    )
                val guard = DefaultSecretBallotStreamGuard(liveKitEgressClient = fakeClient, streamingConfig = ENABLED_STREAMING_CONFIG)
                application {
                    install(StatusPages) { installSecretBallotPauseExceptionHandlers() }
                    routing { registerSecretBallotPauseTestRoutes(egressClient = fakeClient, streamGuard = guard) }
                }
                val committeeId = createTestCommittee("Pause-R3")
                val chair = createTestMember("pauser3-chair@example.org")
                addMember(committeeId, chair, CommitteeRole.CHAIR)
                val board = (1..3).map { createTestMember("pauser3-wv$it@example.org") }
                val meetingId = createTestMeeting(committeeId)
                val roomId = createTestRoom(chair, "Pause-R3 Raum", meetingId)
                val destId = createTestDestination("Pause-R3 Ziel", chair)
                val motionId = createTerminierterMotion(committeeId, meetingId, chair)

                client.startStream(chair, roomId, destId).status shouldBe HttpStatusCode.OK
                val streamId =
                    transaction {
                        ConferenceStreamTable
                            .selectAll()
                            .where {
                                ConferenceStreamTable.roomId eq roomId
                            }.single()[ConferenceStreamTable.id]
                    }
                val electionId = client.setUpAndOpenSecretElection(chair, motionId, board)
                streamRow(streamId)[ConferenceStreamTable.status] shouldBe ConferenceStreamStatus.PAUSED

                val failures = Collections.synchronizedList(mutableListOf<Throwable>())
                val closeThread =
                    Thread {
                        try {
                            runBlocking { client.closeVoting(board[0], electionId) }
                        } catch (t: Throwable) {
                            failures += t
                        }
                    }
                closeThread.start()
                check(readyLatch.await(20, TimeUnit.SECONDS)) { "closeVoting's auto-resume never reached its LiveKit call" }

                // restartEgressForStream's Tx1 has already committed STARTING and released the row lock
                // -- stopStream can now freely acquire it and finalize the stream to ENDED while the
                // auto-resume's LiveKit call is still blocked.
                client
                    .post("/test/stop-stream?streamId=$streamId") { header("X-Member-Id", chair.toString()) }
                    .status shouldBe HttpStatusCode.OK
                streamRow(streamId)[ConferenceStreamTable.status] shouldBe ConferenceStreamStatus.ENDED

                releaseLatch.countDown()
                closeThread.join(20_000)
                if (failures.isNotEmpty()) throw failures.first()

                // Security-audit-round-2 F1 fix -- restartEgressForStream's own Tx2 "abandoned" branch
                // used to handle ONLY a PAUSED row it found itself abandoned against; now it also
                // recognizes STOPPING/ENDED (a concurrent stopStream, exactly this race), records the
                // freshly-started egress id, and resurrects the row to STOPPING (clearing `endedAt`)
                // instead of silently leaking a publishing egress behind a terminal row that
                // StreamPoller would never revisit again. Never LIVE (unchanged from before this fix),
                // but ALSO never a lost egress id.
                val resurrectedRow = streamRow(streamId)
                resurrectedRow[ConferenceStreamTable.status] shouldBe ConferenceStreamStatus.STOPPING
                resurrectedRow[ConferenceStreamTable.endedAt] shouldBe null
                val freshEgressId = resurrectedRow[ConferenceStreamTable.livekitEgressId]
                freshEgressId shouldNotBe null

                // StreamPoller.handleStopping picks the resurrected row up on its very next tick, calls
                // StopEgress for the FRESH id, and only THEN finalizes to ENDED for real -- proving the
                // egress the auto-resume just started is never left publishing behind a terminal row.
                StreamPoller(liveKitEgressClient = fakeClient, streamingConfig = ENABLED_STREAMING_CONFIG).tick()
                (fakeClient.stopCalls.any { it.second == freshEgressId }) shouldBe true
                streamRow(streamId)[ConferenceStreamTable.status] shouldBe ConferenceStreamStatus.ENDED
            }
        }

        // ── R4 -- two closeVoting calls on the same meeting's two different elections ─────

        test("R4: two closeVoting calls (different elections, same meeting) racing -- at most one actual egress-start, restartCount=1") {
            testApplication {
                val fakeClient = ControllableFakeLiveKitEgressClient()
                val guard = DefaultSecretBallotStreamGuard(liveKitEgressClient = fakeClient, streamingConfig = ENABLED_STREAMING_CONFIG)
                application {
                    install(StatusPages) { installSecretBallotPauseExceptionHandlers() }
                    routing { registerSecretBallotPauseTestRoutes(egressClient = fakeClient, streamGuard = guard) }
                }
                val committeeId = createTestCommittee("Pause-R4")
                val chair = createTestMember("pauser4-chair@example.org")
                addMember(committeeId, chair, CommitteeRole.CHAIR)
                val board1 = (1..3).map { createTestMember("pauser4-w1-$it@example.org") }
                val board2 = (1..3).map { createTestMember("pauser4-w2-$it@example.org") }
                val meetingId = createTestMeeting(committeeId)
                val roomId = createTestRoom(chair, "Pause-R4 Raum", meetingId)
                val destId = createTestDestination("Pause-R4 Ziel", chair)
                val motion1 = createTerminierterMotion(committeeId, meetingId, chair)
                val motion2 = createTerminierterMotion(committeeId, meetingId, chair)

                client.startStream(chair, roomId, destId).status shouldBe HttpStatusCode.OK
                val streamId =
                    transaction {
                        ConferenceStreamTable
                            .selectAll()
                            .where {
                                ConferenceStreamTable.roomId eq roomId
                            }.single()[ConferenceStreamTable.id]
                    }
                // The initial startStream call above already used the fake client's startCalls once --
                // the assertion below must only count NEW calls made during the race, not this baseline.
                val startCallsBeforeRace = fakeClient.startCalls.size
                val e1 = client.setUpAndOpenSecretElection(chair, motion1, board1)
                val e2 = client.setUpAndOpenSecretElection(chair, motion2, board2)
                streamRow(streamId)[ConferenceStreamTable.status] shouldBe ConferenceStreamStatus.PAUSED

                val startLatch = CountDownLatch(2)
                val doneLatch = CountDownLatch(2)
                val failures = Collections.synchronizedList(mutableListOf<Throwable>())

                fun closeThread(
                    boardMember: Uuid,
                    electionId: String,
                ): Thread =
                    Thread {
                        try {
                            startLatch.countDown()
                            startLatch.await(20, TimeUnit.SECONDS)
                            runBlocking { client.closeVoting(boardMember, electionId) }
                        } catch (t: Throwable) {
                            failures += t
                        } finally {
                            doneLatch.countDown()
                        }
                    }
                val t1 = closeThread(board1[0], e1)
                val t2 = closeThread(board2[0], e2)
                t1.start()
                t2.start()
                check(doneLatch.await(20, TimeUnit.SECONDS)) { "R4 threads did not complete in time" }
                if (failures.isNotEmpty()) throw failures.first()

                val row = streamRow(streamId)
                row[ConferenceStreamTable.status] shouldBe ConferenceStreamStatus.LIVE
                row[ConferenceStreamTable.restartCount] shouldBe 1
                (fakeClient.startCalls.size - startCallsBeforeRace) shouldBe 1
            }
        }

        // ── R5 -- two openVoting calls on the same meeting racing ─────────────────────────

        test(
            "R5: two openVoting calls (different elections, same meeting) racing -- ends PAUSED, invariant holds regardless of stopEgress call count",
        ) {
            testApplication {
                val fakeClient = ControllableFakeLiveKitEgressClient()
                val guard = DefaultSecretBallotStreamGuard(liveKitEgressClient = fakeClient, streamingConfig = ENABLED_STREAMING_CONFIG)
                application {
                    install(StatusPages) { installSecretBallotPauseExceptionHandlers() }
                    routing { registerSecretBallotPauseTestRoutes(egressClient = fakeClient, streamGuard = guard) }
                }
                val committeeId = createTestCommittee("Pause-R5")
                val chair = createTestMember("pauser5-chair@example.org")
                addMember(committeeId, chair, CommitteeRole.CHAIR)
                val board1 = (1..3).map { createTestMember("pauser5-w1-$it@example.org") }
                val board2 = (1..3).map { createTestMember("pauser5-w2-$it@example.org") }
                val meetingId = createTestMeeting(committeeId)
                val roomId = createTestRoom(chair, "Pause-R5 Raum", meetingId)
                val destId = createTestDestination("Pause-R5 Ziel", chair)
                val motion1 = createTerminierterMotion(committeeId, meetingId, chair)
                val motion2 = createTerminierterMotion(committeeId, meetingId, chair)

                client.startStream(chair, roomId, destId).status shouldBe HttpStatusCode.OK
                val streamId =
                    transaction {
                        ConferenceStreamTable
                            .selectAll()
                            .where {
                                ConferenceStreamTable.roomId eq roomId
                            }.single()[ConferenceStreamTable.id]
                    }
                val e1 = client.openElection(chair, motion1, true)
                client.appointElectionBoard(chair, e1, board1)
                val e2 = client.openElection(chair, motion2, true)
                client.appointElectionBoard(chair, e2, board2)

                val startLatch = CountDownLatch(2)
                val doneLatch = CountDownLatch(2)
                val failures = Collections.synchronizedList(mutableListOf<Throwable>())

                fun openThread(
                    boardMember: Uuid,
                    electionId: String,
                ): Thread =
                    Thread {
                        try {
                            startLatch.countDown()
                            startLatch.await(20, TimeUnit.SECONDS)
                            runBlocking { client.openVoting(boardMember, electionId) }
                        } catch (t: Throwable) {
                            failures += t
                        } finally {
                            doneLatch.countDown()
                        }
                    }
                val t1 = openThread(board1[0], e1)
                val t2 = openThread(board2[0], e2)
                t1.start()
                t2.start()
                check(doneLatch.await(20, TimeUnit.SECONDS)) { "R5 threads did not complete in time" }
                if (failures.isNotEmpty()) throw failures.first()

                val row = streamRow(streamId)
                row[ConferenceStreamTable.status] shouldBe ConferenceStreamStatus.PAUSED
                row[ConferenceStreamTable.pauseReason] shouldBe ConferenceStreamPauseReason.SECRET_BALLOT
                // At most a small, bounded number of StopEgress attempts -- both openVoting calls are
                // entitled to try (each independently observes the row as PAUSING before either
                // confirms it PAUSED), but exactly one confirmed PAUSED write wins (markPaused's own
                // re-check under a fresh read guards THAT). Never zero.
                fakeClient.stopCalls.isNotEmpty() shouldBe true
                (fakeClient.stopCalls.size <= 2) shouldBe true
            }
        }

        // ── R6 -- markPausingForSecretBallot's candidate-SELECT vs a concurrent stopStream ────
        // (review-round finding: the ORIGINAL code gathered affectedIds via SELECT, then ran a
        // SEPARATE `UPDATE ... WHERE id inList affectedIds` with no status re-check -- a race class
        // R4 already fixed for restartEgressForStream but had NOT yet been closed here.)

        test(
            "R6: markPausingForSecretBallot's candidate-SELECT captures a row, then stopStream commits ENDED " +
                "before the atomic per-row claim runs -- stays ENDED, never resurrected to PAUSING",
        ) {
            testApplication {
                val fakeClient = ControllableFakeLiveKitEgressClient()
                val guard = DefaultSecretBallotStreamGuard(liveKitEgressClient = fakeClient, streamingConfig = ENABLED_STREAMING_CONFIG)
                application {
                    install(StatusPages) { installSecretBallotPauseExceptionHandlers() }
                    routing { registerSecretBallotPauseTestRoutes(egressClient = fakeClient, streamGuard = guard) }
                }
                val committeeId = createTestCommittee("Pause-R6")
                val chair = createTestMember("pauser6-chair@example.org")
                addMember(committeeId, chair, CommitteeRole.CHAIR)
                val meetingId = createTestMeeting(committeeId)
                val roomId = createTestRoom(chair, "Pause-R6 Raum", meetingId)
                val destId = createTestDestination("Pause-R6 Ziel", chair)

                client.startStream(chair, roomId, destId).status shouldBe HttpStatusCode.OK
                val streamId =
                    transaction {
                        ConferenceStreamTable
                            .selectAll()
                            .where { ConferenceStreamTable.roomId eq roomId }
                            .single()[ConferenceStreamTable.id]
                    }

                val readyLatch = CountDownLatch(1)
                val releaseLatch = CountDownLatch(1)
                val failures = Collections.synchronizedList(mutableListOf<Throwable>())
                var markResult: List<Uuid>? = null

                // Directly exercises ConferenceStreamPauseCoordinator.markPausingForSecretBallot --
                // the exact function this finding is about -- rather than routing through
                // ElectionService.openVoting, which has no seam to control this interleaving.
                // Mirrors the production call shape byte-for-byte: SecretBallotStreamLock.lockRooms
                // first (same "Locking order" contract every real caller follows), then the
                // coordinator, both inside ONE transaction.
                val markThread =
                    Thread {
                        try {
                            markResult =
                                transaction {
                                    SecretBallotStreamLock.lockRooms(listOf(roomId))
                                    ConferenceStreamPauseCoordinator.markPausingForSecretBallot(
                                        roomIds = listOf(roomId),
                                        actorMemberId = chair,
                                        actorRole = AccountRole.MEMBER,
                                        onCandidatesSelected = {
                                            readyLatch.countDown()
                                            releaseLatch.await(20, TimeUnit.SECONDS)
                                        },
                                    )
                                }
                        } catch (t: Throwable) {
                            failures += t
                        }
                    }
                markThread.start()
                check(readyLatch.await(20, TimeUnit.SECONDS)) {
                    "markPausingForSecretBallot never reached its candidate-selected hook"
                }

                // markThread's transaction holds the conference_room lock (SecretBallotStreamLock
                // .lockRooms above), but stopStream never touches conference_room at all -- see
                // ConferenceStreamingService.stopStream's own prep transaction -- so it runs to
                // completion, uncontended, while markThread is parked mid-transaction, exactly
                // recreating the finding's window: the candidate SELECT already captured this
                // stream's id BEFORE stopStream commits ENDED.
                client
                    .post("/test/stop-stream?streamId=$streamId") { header("X-Member-Id", chair.toString()) }
                    .status shouldBe HttpStatusCode.OK
                streamRow(streamId)[ConferenceStreamTable.status] shouldBe ConferenceStreamStatus.ENDED

                releaseLatch.countDown()
                markThread.join(20_000)
                if (failures.isNotEmpty()) throw failures.first()

                // The atomic per-row claim's own WHERE clause re-checks status IN (STARTING, LIVE) at
                // write time -- by then the row is ENDED, so the claim affects zero rows and the
                // stream is never resurrected to PAUSING.
                markResult shouldBe emptyList()
                streamRow(streamId)[ConferenceStreamTable.status] shouldBe ConferenceStreamStatus.ENDED
            }
        }

        // ── R4-1 -- security-audit round-4: DefaultSecretBallotStreamGuard.markPaused's own resurrection race ──

        test(
            "R4-1: security-audit round-4 -- a resurrection that attaches a FRESH egress id to the row WHILE " +
                "quiesceOne is confirming the OLD (stale, snapshotted) egress id's stop must not be silently " +
                "overwritten with PAUSED; the row stays PAUSING under the fresh id, and the NEXT StreamPoller " +
                "tick correctly confirms+stops that fresh id for real",
        ) {
            testApplication {
                var streamId: Uuid? = null
                val freshEgressId = "EG_fresh_r4_1"
                val fakeClient =
                    ControllableFakeLiveKitEgressClient(
                        // Fires synchronously inside stopEgress, exactly where the real race lands:
                        // after quiesceOne has requested the stop of the STALE snapshotted egress id
                        // (pausingStreamsForMeeting's own read, taken before this call), but BEFORE
                        // awaitEgressStopped has confirmed it gone and markPaused has run -- simulates a
                        // concurrent startStream/restartEgressForStream "abandoned" branch resurrecting
                        // this SAME row onto a fresh, actually-publishing egress right here. Fires once
                        // (autoConfirmOnStop=true then marks the STALE id gone in listEgress -- see
                        // that flag's own KDoc -- so awaitEgressStopped's very next poll confirms it and
                        // reaches markPaused, without needing a second stop call to guard against).
                        onStopEgress = { id ->
                            val sid = streamId
                            if (sid != null) {
                                transaction {
                                    ConferenceStreamTable.update({ ConferenceStreamTable.id eq sid }) {
                                        it[livekitEgressId] = freshEgressId
                                    }
                                }
                            }
                        },
                    )
                val guard = DefaultSecretBallotStreamGuard(liveKitEgressClient = fakeClient, streamingConfig = ENABLED_STREAMING_CONFIG)
                application {
                    install(StatusPages) { installSecretBallotPauseExceptionHandlers() }
                    routing { registerSecretBallotPauseTestRoutes(egressClient = fakeClient, streamGuard = guard) }
                }
                val committeeId = createTestCommittee("Pause-R4-1")
                val chair = createTestMember("pauser4-1-chair@example.org")
                addMember(committeeId, chair, CommitteeRole.CHAIR)
                val board = (1..3).map { createTestMember("pauser4-1-wv$it@example.org") }
                val meetingId = createTestMeeting(committeeId)
                val roomId = createTestRoom(chair, "Pause-R4-1 Raum", meetingId)
                val destId = createTestDestination("Pause-R4-1 Ziel", chair)
                val motionId = createTerminierterMotion(committeeId, meetingId, chair)

                client.startStream(chair, roomId, destId).status shouldBe HttpStatusCode.OK
                val sid =
                    transaction {
                        ConferenceStreamTable.selectAll().where { ConferenceStreamTable.roomId eq roomId }.single()[
                            ConferenceStreamTable.id,
                        ]
                    }
                streamId = sid

                // openVoting's own suspend call awaits ElectionService.openVoting -> ... ->
                // DefaultSecretBallotStreamGuard.quiesceStreamsForMeeting SYNCHRONOUSLY before
                // responding -- the resurrection hook above fires DURING this single HTTP call.
                client.setUpAndOpenSecretElection(chair, motionId, board)

                // The R4-1 fix: markPaused's finalizing write is guarded on the egress id it actually
                // confirmed (the stale, pre-resurrection id) still matching the row's CURRENT
                // livekit_egress_id -- since the row now carries freshEgressId, the write is skipped
                // entirely. Without the fix, this assertion fails: the row would read PAUSED, and
                // freshEgressId would never be revisited by anything (handlePaused polls no egress at
                // all for a PAUSED/SECRET_BALLOT row with an open ballot).
                val afterOpenVoting = streamRow(sid)
                afterOpenVoting[ConferenceStreamTable.status] shouldBe ConferenceStreamStatus.PAUSING
                afterOpenVoting[ConferenceStreamTable.livekitEgressId] shouldBe freshEgressId
                afterOpenVoting[ConferenceStreamTable.pausedAt] shouldBe null

                // StreamPoller's own PAUSING handling (belt-and-braces on top of the guard, see
                // handlePausing KDoc) picks the fresh id up, confirms it for real, and only THEN
                // reaches PAUSED -- proving the fresh egress is never abandoned.
                fakeClient.forceGone(freshEgressId)
                StreamPoller(liveKitEgressClient = fakeClient, streamingConfig = ENABLED_STREAMING_CONFIG).tick()
                val afterTick = streamRow(sid)
                afterTick[ConferenceStreamTable.status] shouldBe ConferenceStreamStatus.PAUSED
                afterTick[ConferenceStreamTable.pauseReason] shouldBe ConferenceStreamPauseReason.SECRET_BALLOT
                (afterTick[ConferenceStreamTable.pausedAt] != null) shouldBe true
            }
        }

        // ── R4-3 -- security-audit round-4: pauseStream now routes through a REAL confirmation ──

        test(
            "R4-3: security-audit round-4 -- a MANUAL pauseStream call on a LIVE stream now requires a REAL " +
                "StopEgress confirmation before ever writing PAUSED; an unconfirmed stop leaves the row " +
                "PAUSING (never PAUSED with a possibly-still-publishing egress), and StreamPoller's own " +
                "PAUSING handling finishes the job once the egress is actually confirmed gone",
        ) {
            testApplication {
                // autoConfirmOnStop=false -- keeps pauseStream's OWN new confirmation loop from ever
                // completing (same technique scenario #17/#16 already use for the guard's own loop),
                // so this test can observe the intermediate PAUSING state pauseStream now passes
                // through instead of writing PAUSED unconditionally right after the best-effort
                // StopEgress request (the pre-fix behaviour).
                val fakeClient = ControllableFakeLiveKitEgressClient(autoConfirmOnStop = false)
                val guard = DefaultSecretBallotStreamGuard(liveKitEgressClient = fakeClient, streamingConfig = ENABLED_STREAMING_CONFIG)
                application {
                    install(StatusPages) { installSecretBallotPauseExceptionHandlers() }
                    routing { registerSecretBallotPauseTestRoutes(egressClient = fakeClient, streamGuard = guard) }
                }
                val chair = createTestMember("pauser4-3-chair@example.org")
                val roomId = createTestRoom(chair, "Pause-R4-3 Raum", meetingId = null)
                val destId = createTestDestination("Pause-R4-3 Ziel", chair)

                client.startStream(chair, roomId, destId).status shouldBe HttpStatusCode.OK
                val streamId =
                    transaction {
                        ConferenceStreamTable.selectAll().where { ConferenceStreamTable.roomId eq roomId }.single()[
                            ConferenceStreamTable.id,
                        ]
                    }
                val egressId = streamRow(streamId)[ConferenceStreamTable.livekitEgressId]!!

                client
                    .post("/test/pause-stream?streamId=$streamId") { header("X-Member-Id", chair.toString()) }
                    .status shouldBe HttpStatusCode.OK

                // The R4-3 fix: since the fake client never auto-confirms the stop, pauseStream's own
                // awaitEgressStopConfirmation loop times out (pauseVerifyTimeoutSeconds=2 here) and the
                // finalizing PAUSED write is skipped entirely -- the row is left exactly as pauseStream's
                // own Tx1 wrote it, PAUSING/MANUAL. Before the fix, pauseStream wrote PAUSED
                // unconditionally right after the best-effort StopEgress REQUEST, regardless of whether
                // the egress was ever actually confirmed stopped.
                val row = streamRow(streamId)
                row[ConferenceStreamTable.status] shouldBe ConferenceStreamStatus.PAUSING
                row[ConferenceStreamTable.pauseReason] shouldBe ConferenceStreamPauseReason.MANUAL
                row[ConferenceStreamTable.pausedAt] shouldBe null
                fakeClient.stopCalls.size shouldBe 1

                // StreamPoller's own PAUSING handling (belt-and-braces on top of pauseStream's own,
                // already-timed-out confirmation loop, same "leave it PAUSING, retry later" posture
                // stopStream/DefaultSecretBallotStreamGuard already establish) finishes the job once the
                // egress is actually confirmed gone.
                fakeClient.forceGone(egressId)
                StreamPoller(liveKitEgressClient = fakeClient, streamingConfig = ENABLED_STREAMING_CONFIG).tick()
                val afterTick = streamRow(streamId)
                afterTick[ConferenceStreamTable.status] shouldBe ConferenceStreamStatus.PAUSED
                afterTick[ConferenceStreamTable.pauseReason] shouldBe ConferenceStreamPauseReason.MANUAL
                (afterTick[ConferenceStreamTable.pausedAt] != null) shouldBe true
            }
        }
    })

// ── Fakes ────────────────────────────────────────────────────────────────

/**
 * Extends [ConferenceStreamingServiceTest]'s own `FakeLiveKitEgressClient` shape with a steerable
 * `listEgress` (needed so [network.lapis.cloud.server.conference.DefaultSecretBallotStreamGuard]'s
 * confirmation loop can be made to terminate deterministically -- or deterministically run out the
 * clock into a timeout, plan §8.1 "Basis erweitert um steuerbares ListEgress").
 *
 * [autoConfirmOnStop] (default `true`) marks an egress as gone from `listEgress` the instant
 * [stopEgress] is (successfully) called for it -- the fast, deterministic happy path most scenarios
 * need. Set `false` for scenarios that must observe a genuine `PAUSING` window (the row never
 * auto-confirms; call [forceGone] to manually confirm it later, e.g. simulating
 * [network.lapis.cloud.server.conference.StreamPoller]'s own retry finding it gone on a later tick).
 */
private class ControllableFakeLiveKitEgressClient(
    private val failStart: Boolean = false,
    private val failStop: Boolean = false,
    private val stopErrorMessage: String = "simulated stop failure",
    private val autoConfirmOnStop: Boolean = true,
    /** Fires synchronously inside [startRoomCompositeEgress], before it returns -- used by R2/R3 to block the call on a latch. */
    private val onStartRoomCompositeEgress: (() -> Unit)? = null,
    /**
     * Security-audit round-4 R4-1 regression test -- fires synchronously inside [stopEgress], BEFORE
     * it returns and BEFORE [autoConfirmOnStop] marks the id gone -- mirrors
     * `StreamPollerTest`'s own `StreamPollerFakeEgressClient.onStopEgress`/
     * `ConferenceStreamingServiceTest`'s own `FakeLiveKitEgressClient.onStopEgress` hook shape
     * one-for-one (duplicated here rather than shared -- both are `private` to their own test files).
     * Lets a test deterministically simulate a resurrection (a concurrent
     * `startStream`/`restartEgressForStream` "abandoned" branch attaching a FRESH egress id to the
     * SAME row) landing in the exact window between
     * [network.lapis.cloud.server.conference.DefaultSecretBallotStreamGuard.quiesceOne] requesting the
     * stop and it actually confirming/finalizing -- without a real, timing-dependent thread race.
     */
    private val onStopEgress: ((String) -> Unit)? = null,
) : LiveKitEgressClient {
    private val counter = AtomicInteger(0)
    val startCalls = Collections.synchronizedList(mutableListOf<Triple<String, ConferenceStreamLayout?, List<String>>>())
    val stopCalls = Collections.synchronizedList(mutableListOf<Pair<String, String>>())
    val listEgressCallCount = AtomicInteger(0)

    /** `true` == still reported by [listEgress] (i.e. "not yet confirmed stopped"); absent from this map or `false` == gone/terminal. */
    private val stillActive = ConcurrentHashMap<String, Boolean>()

    override suspend fun startTrackEgress(
        roomName: String,
        trackId: String,
        outputFilepathWithoutExtension: String,
    ): LiveKitEgressInfo = error("not used by SecretBallotStreamPauseTest")

    override suspend fun stopEgress(
        roomName: String,
        egressId: String,
    ): LiveKitEgressInfo {
        stopCalls += roomName to egressId
        onStopEgress?.invoke(egressId)
        if (failStop) throw LiveKitAdminException(message = stopErrorMessage)
        if (autoConfirmOnStop) stillActive[egressId] = false
        return LiveKitEgressInfo(egressId = egressId, status = "EGRESS_ENDING")
    }

    override suspend fun listEgress(roomName: String): List<LiveKitEgressInfo> {
        listEgressCallCount.incrementAndGet()
        return stillActive.filterValues { it }.keys.map { LiveKitEgressInfo(egressId = it, status = "EGRESS_ACTIVE") }
    }

    override suspend fun startRoomCompositeEgress(
        roomName: String,
        layout: ConferenceStreamLayout,
        latencyMode: ConferenceStreamLatencyMode,
        rtmpUrls: List<String>,
    ): LiveKitEgressInfo {
        startCalls += Triple(roomName, layout, rtmpUrls)
        onStartRoomCompositeEgress?.invoke()
        if (failStart) throw LiveKitAdminException(message = "simulated egress start failure")
        val id = "EG_fake_${counter.incrementAndGet()}"
        stillActive[id] = true
        return LiveKitEgressInfo(egressId = id, status = "EGRESS_STARTING")
    }

    override suspend fun startParticipantEgress(
        roomName: String,
        identity: String,
        latencyMode: ConferenceStreamLatencyMode,
        rtmpUrls: List<String>,
    ): LiveKitEgressInfo = error("not used by SecretBallotStreamPauseTest")

    override suspend fun updateStream(
        roomName: String,
        egressId: String,
        addUrls: List<String>,
        removeUrls: List<String>,
    ): LiveKitEgressInfo = error("not used by SecretBallotStreamPauseTest")

    /** Manually marks [egressId] as gone from [listEgress] -- see class KDoc. */
    fun forceGone(egressId: String) {
        stillActive[egressId] = false
    }
}

/** Only [ConferenceService.setRoomMeeting] is ever exercised through this client in this file -- no LiveKit admin call is on that path, so every method here is unreachable. */
private object StubLiveKitAdminClient : LiveKitAdminClient {
    override suspend fun createRoom(
        name: String,
        maxParticipants: Int,
        emptyTimeoutSeconds: Int,
    ): LiveKitRoomInfo = error("not used by SecretBallotStreamPauseTest")

    override suspend fun deleteRoom(name: String): Unit = error("not used by SecretBallotStreamPauseTest")

    override suspend fun listRooms(): List<LiveKitRoomInfo> = error("not used by SecretBallotStreamPauseTest")

    override suspend fun listParticipants(room: String): List<LiveKitParticipantInfo> = error("not used by SecretBallotStreamPauseTest")

    override suspend fun removeParticipant(
        room: String,
        identity: String,
    ): Unit = error("not used by SecretBallotStreamPauseTest")
}

// ── Routes ───────────────────────────────────────────────────────────────

private fun StatusPagesConfig.installSecretBallotPauseExceptionHandlers() {
    exception<UnauthenticatedException> { call, cause -> call.respondText(cause.message, status = HttpStatusCode.Unauthorized) }
    exception<ForbiddenException> { call, cause -> call.respondText(cause.message, status = HttpStatusCode.Forbidden) }
    exception<NotFoundException> { call, cause -> call.respondText(cause.message, status = HttpStatusCode.NotFound) }
    exception<ConflictException> { call, cause -> call.respondText(cause.message, status = HttpStatusCode.Conflict) }
    exception<BadRequestException> { call, cause -> call.respondText(cause.message, status = HttpStatusCode.BadRequest) }
}

/**
 * The union of the throwaway routes [ElectionServiceTest.registerElectionTestRoutes]/
 * [SystemicConsensusServiceTest.registerSystemicConsensusTestRoutes]/
 * [ConferenceStreamingServiceTest.registerConferenceStreamingTestRoutes] each separately establish --
 * written fresh here (those three are file-private) rather than reused, trimmed to exactly the surface
 * this file's scenarios need. [streamGuard] is threaded through both [ElectionService] and
 * [SystemicConsensusService] -- the SAME instance, so a scenario spanning both governance modules (15a-d)
 * observes one consistent guard/fake-LiveKit-client pair.
 */
private fun Route.registerSecretBallotPauseTestRoutes(
    egressClient: LiveKitEgressClient,
    streamGuard: SecretBallotStreamGuard,
) {
    // ── Election ──────────────────────────────────────────────────────
    post("/test/open-election/{motionId}") {
        val service = ElectionService(call = call, streamGuard = streamGuard)
        val q = call.request.queryParameters
        val w =
            service.openElection(
                ElectionOpenInput(
                    motionId = call.parameters["motionId"]!!,
                    electionType = ElectionType.YES_NO,
                    secret = q["secret"]?.toBoolean() ?: true,
                    seatCount = 1,
                    targetCommitteeId = null,
                    targetRole = null,
                    requiredMajorityPercent = 50,
                    tallyThreshold = 1,
                ),
            )
        call.respondText("${w.id}:${w.status}")
    }
    post("/test/appoint-election-board/{electionId}") {
        val service = ElectionService(call = call, streamGuard = streamGuard)
        val memberIds = call.request.queryParameters["memberIds"]!!.split(",")
        val list = service.appointElectionBoard(electionId = call.parameters["electionId"]!!, memberIds = memberIds)
        call.respondText(list.size.toString())
    }
    post("/test/open-voting/{electionId}") {
        val service = ElectionService(call = call, streamGuard = streamGuard)
        val w = service.openVoting(call.parameters["electionId"]!!)
        call.respondText(w.status.name)
    }
    post("/test/cast-election-ballot/{electionId}") {
        val service = ElectionService(call = call, streamGuard = streamGuard)
        val q = call.request.queryParameters
        val answer = q["answer"]?.let { ElectionAnswer.valueOf(it) } ?: ElectionAnswer.YES
        val result =
            service.castElectionBallot(
                ElectionBallotInput(electionId = call.parameters["electionId"]!!, answer = answer, selectedOptionIds = emptyList()),
            )
        call.respondText("${result.id}:${result.receiptCode ?: ""}")
    }
    post("/test/close-voting/{electionId}") {
        val service = ElectionService(call = call, streamGuard = streamGuard)
        val w = service.closeVoting(call.parameters["electionId"]!!)
        call.respondText(w.status.name)
    }
    post("/test/abort-election/{electionId}") {
        val service = ElectionService(call = call, streamGuard = streamGuard)
        val w = service.abortElection(call.parameters["electionId"]!!)
        call.respondText(w.status.name)
    }
    get("/test/get-election/{electionId}") {
        val service = ElectionService(call = call, streamGuard = streamGuard)
        val w = service.getElection(call.parameters["electionId"]!!)
        call.respondText(w.status.name)
    }

    // ── SystemicConsensus ─────────────────────────────────────────────
    post("/test/open-systemic_consensus/{motionId}") {
        val service = SystemicConsensusService(call = call, streamGuard = streamGuard)
        val q = call.request.queryParameters
        val k =
            service.openSystemicConsensus(
                SystemicConsensusOpenInput(
                    motionId = call.parameters["motionId"]!!,
                    secret = q["secret"]?.toBoolean() ?: true,
                    scaleMax = 10,
                    statusQuoOptionAuto = true,
                    bindingness = SystemicConsensusBindingness.ADVISORY,
                    // Security-audit MAJOR-4 test hook -- default 3 (pre-existing behaviour),
                    // overridable via ?maxRounds= so scenario 28 can probe the new MAX_ROUNDS_HARD_CAP.
                    maxRounds = q["maxRounds"]?.toIntOrNull() ?: 3,
                ),
            )
        call.respondText("${k.id}:${k.status}")
    }
    post("/test/freeze-optionen/{id}") {
        val service = SystemicConsensusService(call = call, streamGuard = streamGuard)
        val k = service.freezeOptions(call.parameters["id"]!!)
        call.respondText(k.status.name)
    }
    post("/test/cast-resistance/{id}") {
        val service = SystemicConsensusService(call = call, streamGuard = streamGuard)
        val param = call.request.queryParameters["resistances"] ?: ""
        val resistances =
            param
                .split(",")
                .filter { it.isNotBlank() }
                .associate { pair ->
                    val (optId, value) = pair.split(":")
                    optId to value.toInt()
                }
        val result =
            service.castResistanceBallot(
                SystemicConsensusBallotInput(systemicConsensusId = call.parameters["id"]!!, resistances = resistances),
            )
        call.respondText("${result.id}:${result.receiptCode ?: ""}")
    }
    post("/test/close-rating/{id}") {
        val service = SystemicConsensusService(call = call, streamGuard = streamGuard)
        val k = service.closeRating(call.parameters["id"]!!)
        call.respondText(k.status.name)
    }
    post("/test/reopen-rating/{id}") {
        val service = SystemicConsensusService(call = call, streamGuard = streamGuard)
        val k = service.reopenRating(call.parameters["id"]!!)
        call.respondText("${k.status}:${k.round}")
    }
    post("/test/abort-systemic_consensus/{id}") {
        val service = SystemicConsensusService(call = call, streamGuard = streamGuard)
        val k = service.abortSystemicConsensus(call.parameters["id"]!!)
        call.respondText(k.status.name)
    }
    get("/test/get-systemic_consensus/{id}") {
        val service = SystemicConsensusService(call = call, streamGuard = streamGuard)
        val k = service.getSystemicConsensus(call.parameters["id"]!!)
        call.respondText(k.status.name)
    }

    // ── ConferenceStreaming ───────────────────────────────────────────
    fun streamingService(call: ApplicationCall) =
        ConferenceStreamingService(
            call = call,
            liveKitEgressClient = egressClient,
            config = ENABLED_CONFERENCE_CONFIG,
            streamingConfig = ENABLED_STREAMING_CONFIG,
        )
    post("/test/start-stream") {
        val q = call.request.queryParameters
        val dto =
            streamingService(call).startStream(
                roomId = q["roomId"]!!,
                destinationIds = q["destinationIds"]!!.split(","),
                layout = ConferenceStreamLayout.GRID,
                latencyMode = ConferenceStreamLatencyMode.STANDARD,
                participantIdentity = null,
            )
        call.respondText(dto.id)
    }
    post("/test/pause-stream") {
        val dto = streamingService(call).pauseStream(call.request.queryParameters["streamId"]!!)
        call.respondText(dto.status.name)
    }
    post("/test/resume-stream") {
        val dto = streamingService(call).resumeStream(call.request.queryParameters["streamId"]!!)
        call.respondText(dto.status.name)
    }
    post("/test/stop-stream") {
        val dto = streamingService(call).stopStream(call.request.queryParameters["streamId"]!!)
        call.respondText(dto.status.name)
    }
    get("/test/active-stream") {
        val dtos = streamingService(call).getActiveStream(call.request.queryParameters["roomId"]!!)
        call.respondText(dtos.joinToString(";") { "${it.id}|${it.status}|${it.pauseReason ?: ""}|${it.failureReason ?: ""}" })
    }

    // ── Conference (setRoomMeeting only) ───────────────────────────────
    post("/test/set-room-meeting") {
        val q = call.request.queryParameters
        val service =
            ConferenceService(
                call = call,
                liveKitAdminClient = StubLiveKitAdminClient,
                createRoomRateLimiter = LoginRateLimiter(),
                config = ENABLED_CONFERENCE_CONFIG,
                conferenceMeetingBindRateLimiter = FederationInboxRateLimiter(maxRequests = 100, window = 1.minutes),
            )
        val dto = service.setRoomMeeting(roomId = q["roomId"]!!, meetingId = q["meetingId"]?.takeIf { it.isNotBlank() })
        call.respondText(dto.meetingId ?: "")
    }
}

private fun cleanUpSecretBallotPauseTestData(
    memberIds: List<Uuid>,
    committeeIds: List<Uuid>,
    roomIds: List<Uuid>,
    destinationIds: List<Uuid>,
) {
    transaction {
        if (memberIds.isNotEmpty()) {
            AuditLogEntryTable.update({ AuditLogEntryTable.actorMemberId inList memberIds }) { it[actorMemberId] = null }
        }

        val streamIds =
            ConferenceStreamTable
                .selectAll()
                .filter { row -> row[ConferenceStreamTable.roomId] in roomIds || row[ConferenceStreamTable.startedByMemberId] in memberIds }
                .map { it[ConferenceStreamTable.id] }
        streamIds.forEach { streamId -> ConferenceStreamTargetTable.deleteWhere { ConferenceStreamTargetTable.streamId eq streamId } }
        streamIds.forEach { streamId -> ConferenceStreamTable.deleteWhere { ConferenceStreamTable.id eq streamId } }
        if (destinationIds.isNotEmpty()) {
            ConferenceStreamTargetTable.deleteWhere { ConferenceStreamTargetTable.destinationId inList destinationIds }
            ConferenceStreamDestinationTable.deleteWhere { ConferenceStreamDestinationTable.id inList destinationIds }
        }
        roomIds.forEach { roomId -> ConferenceRoomTable.deleteWhere { ConferenceRoomTable.id eq roomId } }

        val motionIds =
            if (committeeIds.isNotEmpty()) {
                MotionTable.selectAll().where { MotionTable.targetCommitteeId inList committeeIds }.map { it[MotionTable.id] }
            } else {
                emptyList()
            }
        val electionIds =
            if (motionIds.isNotEmpty()) {
                ElectionTable.selectAll().where { ElectionTable.motionId inList motionIds }.map { it[ElectionTable.id] }
            } else {
                emptyList()
            }
        val systemicConsensusIds =
            if (motionIds.isNotEmpty()) {
                SystemicConsensusTable
                    .selectAll()
                    .where {
                        SystemicConsensusTable.motionId inList motionIds
                    }.map { it[SystemicConsensusTable.id] }
            } else {
                emptyList()
            }

        if (electionIds.isNotEmpty()) {
            ElectionTable.update({ ElectionTable.id inList electionIds }) { it[resolutionId] = null }
            val ballotIds =
                ElectionBallotTable
                    .selectAll()
                    .where {
                        ElectionBallotTable.electionId inList electionIds
                    }.map { it[ElectionBallotTable.id] }
            if (ballotIds.isNotEmpty()) {
                ElectionBallotSelectionTable.deleteWhere { ElectionBallotSelectionTable.ballotId inList ballotIds }
            }
            ElectionBallotTable.deleteWhere { ElectionBallotTable.electionId inList electionIds }
            ElectionParticipationTable.deleteWhere { ElectionParticipationTable.electionId inList electionIds }
            ElectionEligibleVoterTable.deleteWhere { ElectionEligibleVoterTable.electionId inList electionIds }
            ElectionBoardMemberTable.deleteWhere { ElectionBoardMemberTable.electionId inList electionIds }
            network.lapis.cloud.server.db.generated.ElectionOptionTable.deleteWhere {
                network.lapis.cloud.server.db.generated.ElectionOptionTable.electionId inList electionIds
            }
            network.lapis.cloud.server.db.generated.ElectionTallyApprovalTable.deleteWhere {
                network.lapis.cloud.server.db.generated.ElectionTallyApprovalTable.electionId inList electionIds
            }
            ElectionTable.deleteWhere { ElectionTable.id inList electionIds }
        }
        if (systemicConsensusIds.isNotEmpty()) {
            SystemicConsensusTable.update({ SystemicConsensusTable.id inList systemicConsensusIds }) { it[resolutionId] = null }
            val kBallotIds =
                network.lapis.cloud.server.db.generated.SystemicConsensusBallotTable
                    .selectAll()
                    .where {
                        network.lapis.cloud.server.db.generated.SystemicConsensusBallotTable.systemicConsensusId inList
                            systemicConsensusIds
                    }.map { it[network.lapis.cloud.server.db.generated.SystemicConsensusBallotTable.id] }
            if (kBallotIds.isNotEmpty()) {
                network.lapis.cloud.server.db.generated.SystemicConsensusResistanceTable.deleteWhere {
                    network.lapis.cloud.server.db.generated.SystemicConsensusResistanceTable.ballotId inList kBallotIds
                }
            }
            network.lapis.cloud.server.db.generated.SystemicConsensusBallotTable.deleteWhere {
                network.lapis.cloud.server.db.generated.SystemicConsensusBallotTable.systemicConsensusId inList systemicConsensusIds
            }
            SystemicConsensusParticipationTable.deleteWhere {
                SystemicConsensusParticipationTable.systemicConsensusId inList
                    systemicConsensusIds
            }
            SystemicConsensusEligibleVoterTable.deleteWhere {
                SystemicConsensusEligibleVoterTable.systemicConsensusId inList
                    systemicConsensusIds
            }
            SystemicConsensusOptionTable.deleteWhere { SystemicConsensusOptionTable.systemicConsensusId inList systemicConsensusIds }
            SystemicConsensusTable.deleteWhere { SystemicConsensusTable.id inList systemicConsensusIds }
        }
        if (motionIds.isNotEmpty()) {
            MotionTable.update({ MotionTable.id inList motionIds }) { it[resolutionId] = null }
            MotionTable.deleteWhere { MotionTable.id inList motionIds }
        }

        val meetingIds =
            if (committeeIds.isNotEmpty()) {
                MeetingTable.selectAll().where { MeetingTable.committeeId inList committeeIds }.map { it[MeetingTable.id] }
            } else {
                emptyList()
            }
        if (meetingIds.isNotEmpty()) {
            network.lapis.cloud.server.db.generated.ResolutionTable.deleteWhere {
                network.lapis.cloud.server.db.generated.ResolutionTable.meetingId inList meetingIds
            }
            MeetingTable.deleteWhere { MeetingTable.id inList meetingIds }
        }

        CommitteeMembershipTable.deleteWhere { CommitteeMembershipTable.committeeId inList committeeIds }
        CommitteeTable.deleteWhere { CommitteeTable.id inList committeeIds }
        memberIds.forEach { memberId -> AccountTable.deleteWhere { AccountTable.memberId eq memberId } }
        memberIds.forEach { memberId -> MemberTable.deleteWhere { MemberTable.id eq memberId } }
    }
}
