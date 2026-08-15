package network.lapis.cloud.server.conference

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import network.lapis.cloud.server.db.DatabaseConfig
import network.lapis.cloud.server.db.DbClock
import network.lapis.cloud.server.db.DevSeedData
import network.lapis.cloud.server.db.generated.AccountTable
import network.lapis.cloud.server.db.generated.AuditLogEntryTable
import network.lapis.cloud.server.db.generated.ConferenceRecordingTable
import network.lapis.cloud.server.db.generated.ConferenceRecordingTrackTable
import network.lapis.cloud.server.db.generated.ConferenceRoomTable
import network.lapis.cloud.server.db.generated.DocumentTable
import network.lapis.cloud.server.db.generated.DocumentVersionTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.ConferenceRecordingStatus
import network.lapis.cloud.shared.domain.ConferenceRecordingTrackSource
import network.lapis.cloud.shared.domain.ConferenceRecordingTrackStatus
import network.lapis.cloud.shared.domain.ConferenceStreamLatencyMode
import network.lapis.cloud.shared.domain.ConferenceStreamLayout
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
import kotlin.time.Duration.Companion.minutes
import kotlin.uuid.Uuid

private const val FAKE_ROOM_NAME_PREFIX = "lc-poller-test-"

/** Records every call for assertions; only [listParticipants] is ever invoked by [RecordingPoller] -- see that class's own KDoc. */
private class FakeLiveKitAdminClient : LiveKitAdminClient {
    val participantsByRoom = mutableMapOf<String, List<LiveKitParticipantInfo>>()

    override suspend fun createRoom(
        name: String,
        maxParticipants: Int,
        emptyTimeoutSeconds: Int,
    ): LiveKitRoomInfo = error("not used by RecordingPoller")

    override suspend fun deleteRoom(name: String) = error("not used by RecordingPoller")

    override suspend fun listRooms(): List<LiveKitRoomInfo> = error("not used by RecordingPoller")

    override suspend fun listParticipants(room: String): List<LiveKitParticipantInfo> = participantsByRoom[room] ?: emptyList()

    override suspend fun removeParticipant(
        room: String,
        identity: String,
    ) = error("not used by RecordingPoller")
}

/**
 * In-memory fake -- [egressInfoByEgressId] is directly mutable by tests to simulate LiveKit-side
 * status progression between ticks. [failListEgress] simulates a SUSTAINED `ListEgress` Twirp
 * outage/misconfiguration -- every call throws instead of returning, used by the "egress-timeout
 * safety net survives a ListEgress outage" STOPPING test below. `startTrackEgress`/`stopEgress`
 * are deliberately unaffected by this flag -- the finding this fixes is specifically about
 * `ListEgress` failing while the room is already STOPPING.
 */
private class FakeLiveKitEgressClient(
    private val failListEgress: Boolean = false,
) : LiveKitEgressClient {
    val started = mutableListOf<Triple<String, String, String>>() // (roomName, trackId, outputPath)
    val stoppedEgressIds = mutableListOf<String>()
    val egressInfoByEgressId = mutableMapOf<String, LiveKitEgressInfo>()

    override suspend fun startTrackEgress(
        roomName: String,
        trackId: String,
        outputFilepathWithoutExtension: String,
    ): LiveKitEgressInfo {
        // A real Uuid, NOT a per-instance sequential counter -- egress_id carries a DB UNIQUE
        // constraint, and this fake is constructed fresh per test but all tests share the SAME H2
        // in-memory database for the whole spec run, so a sequential "EG_fake-0" would collide
        // across tests.
        val egressId = "EG_fake-${Uuid.random()}"
        started += Triple(roomName, trackId, outputFilepathWithoutExtension)
        val info = LiveKitEgressInfo(egressId = egressId, roomName = roomName, status = "EGRESS_STARTING")
        egressInfoByEgressId[egressId] = info
        return info
    }

    override suspend fun stopEgress(
        roomName: String,
        egressId: String,
    ): LiveKitEgressInfo {
        stoppedEgressIds += egressId
        return egressInfoByEgressId[egressId] ?: error("unknown egressId $egressId in fake")
    }

    override suspend fun listEgress(roomName: String): List<LiveKitEgressInfo> =
        if (failListEgress) {
            throw LiveKitAdminException(message = "ListEgress failed (simulated outage)")
        } else {
            egressInfoByEgressId.values.toList()
        }

    // V1.0 Wave 3 "Externes Streaming" -- not used by RecordingPoller (Wave 2), which never
    // touches composited/streaming egress at all -- see LiveKitEgressClient KDoc "V1.0 Wave 3"
    // section. A future StreamPollerTest (a later wave step) will want its OWN fake with real
    // in-memory behaviour for exactly these three; stubbing them as `error(...)` here matches the
    // SAME "unused by this poller" convention FakeLiveKitAdminClient's own methods above already
    // establish.
    override suspend fun startRoomCompositeEgress(
        roomName: String,
        layout: ConferenceStreamLayout,
        latencyMode: ConferenceStreamLatencyMode,
        rtmpUrls: List<String>,
    ): LiveKitEgressInfo = error("not used by RecordingPoller (Wave 2) -- see FakeLiveKitEgressClient KDoc")

    override suspend fun startParticipantEgress(
        roomName: String,
        identity: String,
        latencyMode: ConferenceStreamLatencyMode,
        rtmpUrls: List<String>,
    ): LiveKitEgressInfo = error("not used by RecordingPoller (Wave 2) -- see FakeLiveKitEgressClient KDoc")

    override suspend fun updateStream(
        roomName: String,
        egressId: String,
        addUrls: List<String>,
        removeUrls: List<String>,
    ): LiveKitEgressInfo = error("not used by RecordingPoller (Wave 2) -- see FakeLiveKitEgressClient KDoc")
}

/** [behavior] defaults to writing a few bytes to [outputFile] -- override to simulate a failure via [RecordingComposeException]. */
private class FakeRecordingComposer(
    private val behavior: (RecordingComposeSpec, File) -> Unit = { _, outputFile -> outputFile.writeBytes(byteArrayOf(1, 2, 3, 4)) },
) : RecordingComposer {
    var callCount = 0

    override suspend fun compose(
        spec: RecordingComposeSpec,
        outputFile: File,
    ) {
        callCount++
        behavior(spec, outputFile)
    }
}

/**
 * [RecordingPoller.tick] end to end -- real H2 DB rows (mirrors [ConferenceRecordingServiceTest]'s
 * own house style), fake LiveKit clients, and a fake composer so no `ffmpeg`/Docker/LiveKit
 * involvement is needed. Covers the RECORDING/STOPPING/PROCESSING transitions, the two Wave 2
 * design-review "must-fix" items this step owns (D13 raw-file retention on FAILED, restart
 * reconciliation), and `tick()`'s own exception-safety.
 */
class RecordingPollerTest :
    FunSpec({
        val createdMemberIds = mutableListOf<Uuid>()
        val createdRoomIds = mutableListOf<Uuid>()
        val createdRecordingIds = mutableListOf<Uuid>()
        val createdDocumentIds = mutableListOf<Uuid>()

        beforeSpec {
            DatabaseConfig.connect()
            DevSeedData.seedIfEmpty(force = true)
        }

        afterSpec {
            transaction {
                if (createdRecordingIds.isNotEmpty()) {
                    ConferenceRecordingTrackTable.deleteWhere { ConferenceRecordingTrackTable.recordingId inList createdRecordingIds }
                }
                if (createdMemberIds.isNotEmpty()) {
                    AuditLogEntryTable.update({ AuditLogEntryTable.actorMemberId inList createdMemberIds }) {
                        it[actorMemberId] = null
                    }
                }
                if (createdRecordingIds.isNotEmpty()) {
                    ConferenceRecordingTable.deleteWhere { ConferenceRecordingTable.id inList createdRecordingIds }
                }
                if (createdRoomIds.isNotEmpty()) {
                    ConferenceRoomTable.deleteWhere { ConferenceRoomTable.id inList createdRoomIds }
                }
                if (createdDocumentIds.isNotEmpty()) {
                    DocumentVersionTable.deleteWhere { DocumentVersionTable.documentId inList createdDocumentIds }
                    DocumentTable.deleteWhere { DocumentTable.id inList createdDocumentIds }
                }
                if (createdMemberIds.isNotEmpty()) {
                    AccountTable.deleteWhere { AccountTable.memberId inList createdMemberIds }
                    MemberTable.deleteWhere { MemberTable.id inList createdMemberIds }
                }
            }
        }

        fun createMember(email: String): Uuid {
            val id = Uuid.random()
            transaction {
                MemberTable.insert {
                    it[MemberTable.id] = id
                    it[displayName] = "RecordingPoller Testmitglied"
                    it[MemberTable.email] = email
                    it[status] = MemberStatus.AKTIV
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

        fun createRoom(
            creatorId: Uuid,
            ended: Boolean = false,
        ): Pair<Uuid, String> {
            val id = Uuid.random()
            val livekitRoomName = "$FAKE_ROOM_NAME_PREFIX$id"
            val now = DbClock.nowLocalDateTime()
            transaction {
                ConferenceRoomTable.insert {
                    it[ConferenceRoomTable.id] = id
                    it[title] = "Poller-Test-Raum"
                    it[description] = ""
                    it[ConferenceRoomTable.livekitRoomName] = livekitRoomName
                    it[createdByMemberId] = creatorId
                    it[createdAt] = now
                    it[endedAt] = if (ended) now else null
                    it[maxParticipants] = 25
                }
            }
            createdRoomIds += id
            return id to livekitRoomName
        }

        fun createRecording(
            roomId: Uuid,
            startedByMemberId: Uuid,
            status: ConferenceRecordingStatus,
            startedAt: LocalDateTime = DbClock.nowLocalDateTime(),
            stoppedAt: LocalDateTime? = null,
            composeAttempts: Int = 0,
        ): Uuid {
            val id = Uuid.random()
            transaction {
                ConferenceRecordingTable.insert {
                    it[ConferenceRecordingTable.id] = id
                    it[ConferenceRecordingTable.roomId] = roomId
                    it[ConferenceRecordingTable.startedByMemberId] = startedByMemberId
                    it[ConferenceRecordingTable.startedAt] = startedAt
                    it[ConferenceRecordingTable.stoppedAt] = stoppedAt
                    it[readyAt] = null
                    it[ConferenceRecordingTable.status] = status
                    it[accessLevel] = DocumentAccessLevel.BOARD_ONLY
                    it[documentId] = null
                    it[rawDir] = id.toString()
                    it[durationSeconds] = null
                    it[fileSizeBytes] = null
                    it[failureReason] = null
                    it[ConferenceRecordingTable.composeAttempts] = composeAttempts
                }
            }
            createdRecordingIds += id
            return id
        }

        fun createTrack(
            recordingId: Uuid,
            egressId: String,
            livekitTrackId: String = "TR_${Uuid.random()}",
            status: ConferenceRecordingTrackStatus = ConferenceRecordingTrackStatus.ACTIVE,
            trackSource: ConferenceRecordingTrackSource = ConferenceRecordingTrackSource.CAMERA,
            fileName: String? = null,
            startedAtEpochNanos: Long? = null,
            durationMs: Long? = null,
        ): Uuid {
            val id = Uuid.random()
            transaction {
                ConferenceRecordingTrackTable.insert {
                    it[ConferenceRecordingTrackTable.id] = id
                    it[ConferenceRecordingTrackTable.recordingId] = recordingId
                    it[ConferenceRecordingTrackTable.egressId] = egressId
                    it[ConferenceRecordingTrackTable.livekitTrackId] = livekitTrackId
                    it[participantIdentity] = "identity-$livekitTrackId"
                    it[ConferenceRecordingTrackTable.trackSource] = trackSource
                    it[ConferenceRecordingTrackTable.status] = status
                    it[ConferenceRecordingTrackTable.startedAtEpochNanos] = startedAtEpochNanos
                    it[endedAtEpochNanos] = null
                    it[ConferenceRecordingTrackTable.fileName] = fileName
                    it[ConferenceRecordingTrackTable.durationMs] = durationMs
                    it[sizeBytes] = null
                }
            }
            return id
        }

        fun recordingRow(id: Uuid) =
            transaction { ConferenceRecordingTable.selectAll().where { ConferenceRecordingTable.id eq id }.single() }

        fun buildPoller(
            adminClient: LiveKitAdminClient,
            egressClient: LiveKitEgressClient,
            composer: RecordingComposer,
            hostRawRoot: File,
            documentStorageRoot: File,
            maxTracks: Int = 60,
            maxDurationMinutes: Long = 240,
            egressTimeoutMinutes: Long = 30,
            keepRaw: Boolean = false,
            clock: () -> LocalDateTime = { DbClock.nowLocalDateTime() },
        ): RecordingPoller {
            val config =
                ConferenceRecordingConfig.load { key ->
                    when (key) {
                        "LAPIS_RECORDING_ENABLED" -> "true"
                        "LAPIS_RECORDING_MAX_TRACKS" -> maxTracks.toString()
                        "LAPIS_RECORDING_MAX_DURATION_MINUTES" -> maxDurationMinutes.toString()
                        "LAPIS_RECORDING_EGRESS_TIMEOUT_MINUTES" -> egressTimeoutMinutes.toString()
                        "LAPIS_RECORDING_KEEP_RAW" -> keepRaw.toString()
                        "LAPIS_EGRESS_OUTPUT_HOST_DIR" -> hostRawRoot.path
                        "LAPIS_EGRESS_OUTPUT_CONTAINER_DIR" -> "/out"
                        else -> null
                    }
                }
            return RecordingPoller(
                liveKitAdminClient = adminClient,
                liveKitEgressClient = egressClient,
                recordingConfig = config,
                documentStorageRoot = documentStorageRoot,
                composer = composer,
                clock = clock,
            )
        }

        // ── RECORDING ────────────────────────────────────────────────────

        test("RECORDING: discovers a new unmuted track, starts egress, and inserts a track row -- muted tracks are skipped") {
            val hostRawRoot = Files.createTempDirectory("poller-test-raw").toFile()
            val documentStorageRoot = Files.createTempDirectory("poller-test-docs").toFile()
            try {
                val member = createMember("poller-recording-discover@example.org")
                val (roomId, livekitRoomName) = createRoom(member)
                val recordingId = createRecording(roomId, member, ConferenceRecordingStatus.RECORDING)

                val adminClient = FakeLiveKitAdminClient()
                adminClient.participantsByRoom[livekitRoomName] =
                    listOf(
                        LiveKitParticipantInfo(
                            identity = member.toString(),
                            tracks =
                                listOf(
                                    LiveKitTrackInfo(sid = "TR_cam", type = "VIDEO", source = "CAMERA", muted = false),
                                    LiveKitTrackInfo(sid = "TR_mic_muted", type = "AUDIO", source = "MICROPHONE", muted = true),
                                ),
                        ),
                    )
                val egressClient = FakeLiveKitEgressClient()
                val poller = buildPoller(adminClient, egressClient, FakeRecordingComposer(), hostRawRoot, documentStorageRoot)

                poller.tick()

                egressClient.started shouldHaveSize 1
                egressClient.started.single().second shouldBe "TR_cam"
                val tracks =
                    transaction {
                        ConferenceRecordingTrackTable
                            .selectAll()
                            .where { ConferenceRecordingTrackTable.recordingId eq recordingId }
                            .toList()
                    }
                tracks shouldHaveSize 1
                tracks.single()[ConferenceRecordingTrackTable.livekitTrackId] shouldBe "TR_cam"
                tracks.single()[ConferenceRecordingTrackTable.trackSource] shouldBe ConferenceRecordingTrackSource.CAMERA
            } finally {
                hostRawRoot.deleteRecursively()
                documentStorageRoot.deleteRecursively()
            }
        }

        test("RECORDING: a track already discovered on a previous tick is never egress-started twice") {
            val hostRawRoot = Files.createTempDirectory("poller-test-raw").toFile()
            val documentStorageRoot = Files.createTempDirectory("poller-test-docs").toFile()
            try {
                val member = createMember("poller-recording-idempotent@example.org")
                val (roomId, livekitRoomName) = createRoom(member)
                val recordingId = createRecording(roomId, member, ConferenceRecordingStatus.RECORDING)
                createTrack(recordingId, egressId = "EG_existing-$recordingId", livekitTrackId = "TR_cam")

                val adminClient = FakeLiveKitAdminClient()
                adminClient.participantsByRoom[livekitRoomName] =
                    listOf(
                        LiveKitParticipantInfo(
                            identity = member.toString(),
                            tracks = listOf(LiveKitTrackInfo(sid = "TR_cam", type = "VIDEO", source = "CAMERA", muted = false)),
                        ),
                    )
                val egressClient = FakeLiveKitEgressClient()
                val poller = buildPoller(adminClient, egressClient, FakeRecordingComposer(), hostRawRoot, documentStorageRoot)

                poller.tick()

                egressClient.started shouldHaveSize 0
            } finally {
                hostRawRoot.deleteRecursively()
                documentStorageRoot.deleteRecursively()
            }
        }

        test("RECORDING: respects maxTracks -- no egress started once the cap is reached") {
            val hostRawRoot = Files.createTempDirectory("poller-test-raw").toFile()
            val documentStorageRoot = Files.createTempDirectory("poller-test-docs").toFile()
            try {
                val member = createMember("poller-recording-maxtracks@example.org")
                val (roomId, livekitRoomName) = createRoom(member)
                val recordingId = createRecording(roomId, member, ConferenceRecordingStatus.RECORDING)
                createTrack(recordingId, egressId = "EG_existing-$recordingId", livekitTrackId = "TR_existing")

                val adminClient = FakeLiveKitAdminClient()
                adminClient.participantsByRoom[livekitRoomName] =
                    listOf(
                        LiveKitParticipantInfo(
                            identity = member.toString(),
                            tracks = listOf(LiveKitTrackInfo(sid = "TR_new", type = "VIDEO", source = "CAMERA", muted = false)),
                        ),
                    )
                val egressClient = FakeLiveKitEgressClient()
                val poller =
                    buildPoller(adminClient, egressClient, FakeRecordingComposer(), hostRawRoot, documentStorageRoot, maxTracks = 1)

                poller.tick()

                egressClient.started shouldHaveSize 0
            } finally {
                hostRawRoot.deleteRecursively()
                documentStorageRoot.deleteRecursively()
            }
        }

        test("RECORDING: auto-stops (transitions to STOPPING) once the room's ended_at is non-null, and records an audit entry") {
            val hostRawRoot = Files.createTempDirectory("poller-test-raw").toFile()
            val documentStorageRoot = Files.createTempDirectory("poller-test-docs").toFile()
            try {
                val member = createMember("poller-recording-roomended@example.org")
                val (roomId, _) = createRoom(member, ended = true)
                val recordingId = createRecording(roomId, member, ConferenceRecordingStatus.RECORDING)

                val poller =
                    buildPoller(
                        FakeLiveKitAdminClient(),
                        FakeLiveKitEgressClient(),
                        FakeRecordingComposer(),
                        hostRawRoot,
                        documentStorageRoot,
                    )
                poller.tick()

                val row = recordingRow(recordingId)
                row[ConferenceRecordingTable.status] shouldBe ConferenceRecordingStatus.STOPPING
                (row[ConferenceRecordingTable.stoppedAt] != null) shouldBe true
                val auditCount =
                    transaction {
                        AuditLogEntryTable.selectAll().where { AuditLogEntryTable.entityId eq recordingId }.count()
                    }
                (auditCount >= 1) shouldBe true
            } finally {
                hostRawRoot.deleteRecursively()
                documentStorageRoot.deleteRecursively()
            }
        }

        test("RECORDING: auto-stops once maxDurationMinutes has elapsed since startedAt") {
            val hostRawRoot = Files.createTempDirectory("poller-test-raw").toFile()
            val documentStorageRoot = Files.createTempDirectory("poller-test-docs").toFile()
            try {
                val member = createMember("poller-recording-maxduration@example.org")
                val (roomId, _) = createRoom(member)
                val startedAt = DbClock.nowLocalDateTime()
                val recordingId = createRecording(roomId, member, ConferenceRecordingStatus.RECORDING, startedAt = startedAt)

                val fakeNow = startedAt.toJavaLocalDateTimeShiftedMinutes(11)
                val poller =
                    buildPoller(
                        FakeLiveKitAdminClient(),
                        FakeLiveKitEgressClient(),
                        FakeRecordingComposer(),
                        hostRawRoot,
                        documentStorageRoot,
                        maxDurationMinutes = 10,
                        clock = { fakeNow },
                    )
                poller.tick()

                recordingRow(recordingId)[ConferenceRecordingTable.status] shouldBe ConferenceRecordingStatus.STOPPING
            } finally {
                hostRawRoot.deleteRecursively()
                documentStorageRoot.deleteRecursively()
            }
        }

        // ── STOPPING ─────────────────────────────────────────────────────

        test(
            "STOPPING: requests StopEgress for non-terminal tracks, refreshes status via ListEgress, transitions to PROCESSING once all terminal",
        ) {
            val hostRawRoot = Files.createTempDirectory("poller-test-raw").toFile()
            val documentStorageRoot = Files.createTempDirectory("poller-test-docs").toFile()
            try {
                val member = createMember("poller-stopping-alltermimal@example.org")
                val (roomId, livekitRoomName) = createRoom(member)
                val now = DbClock.nowLocalDateTime()
                val recordingId = createRecording(roomId, member, ConferenceRecordingStatus.STOPPING, stoppedAt = now)
                val egA = "EG_a-$recordingId"
                val trackId =
                    createTrack(recordingId, egressId = egA, livekitTrackId = "TR_a", status = ConferenceRecordingTrackStatus.ACTIVE)

                val egressClient = FakeLiveKitEgressClient()
                egressClient.egressInfoByEgressId[egA] =
                    LiveKitEgressInfo(
                        egressId = egA,
                        roomName = livekitRoomName,
                        status = "EGRESS_COMPLETE",
                        fileResults =
                            listOf(
                                LiveKitEgressFileInfo(
                                    filename = "/out/$recordingId/alice__CAMERA__TR_a.mp4",
                                    duration = "5000000000",
                                    size = "12345",
                                ),
                            ),
                    )
                val poller = buildPoller(FakeLiveKitAdminClient(), egressClient, FakeRecordingComposer(), hostRawRoot, documentStorageRoot)

                poller.tick()

                egressClient.stoppedEgressIds shouldContain egA
                recordingRow(recordingId)[ConferenceRecordingTable.status] shouldBe ConferenceRecordingStatus.PROCESSING
                val trackRow =
                    transaction { ConferenceRecordingTrackTable.selectAll().where { ConferenceRecordingTrackTable.id eq trackId }.single() }
                trackRow[ConferenceRecordingTrackTable.status] shouldBe ConferenceRecordingTrackStatus.COMPLETE
                trackRow[ConferenceRecordingTrackTable.fileName] shouldBe "/out/$recordingId/alice__CAMERA__TR_a.mp4"
                trackRow[ConferenceRecordingTrackTable.durationMs] shouldBe 5000L
            } finally {
                hostRawRoot.deleteRecursively()
                documentStorageRoot.deleteRecursively()
            }
        }

        test("STOPPING: past egressTimeout with >=1 completed video track -> composes from survivors (PROCESSING)") {
            val hostRawRoot = Files.createTempDirectory("poller-test-raw").toFile()
            val documentStorageRoot = Files.createTempDirectory("poller-test-docs").toFile()
            try {
                val member = createMember("poller-stopping-timeout-survivors@example.org")
                val (roomId, livekitRoomName) = createRoom(member)
                val stoppedAt = DbClock.nowLocalDateTime()
                val recordingId = createRecording(roomId, member, ConferenceRecordingStatus.STOPPING, stoppedAt = stoppedAt)
                val egDone = "EG_done-$recordingId"
                val egStuck = "EG_stuck-$recordingId"
                createTrack(
                    recordingId,
                    egressId = egDone,
                    livekitTrackId = "TR_done",
                    status = ConferenceRecordingTrackStatus.COMPLETE,
                    trackSource = ConferenceRecordingTrackSource.CAMERA,
                )
                createTrack(
                    recordingId,
                    egressId = egStuck,
                    livekitTrackId = "TR_stuck",
                    status = ConferenceRecordingTrackStatus.ACTIVE,
                    trackSource = ConferenceRecordingTrackSource.MICROPHONE,
                )

                val egressClient = FakeLiveKitEgressClient()
                // egStuck never progresses past ACTIVE in the fake, simulating a wedged egress.
                egressClient.egressInfoByEgressId[egStuck] =
                    LiveKitEgressInfo(egressId = egStuck, roomName = livekitRoomName, status = "EGRESS_ACTIVE")
                egressClient.egressInfoByEgressId[egDone] =
                    LiveKitEgressInfo(egressId = egDone, roomName = livekitRoomName, status = "EGRESS_COMPLETE")

                val fakeNow = stoppedAt.toJavaLocalDateTimeShiftedMinutes(31)
                val poller =
                    buildPoller(
                        FakeLiveKitAdminClient(),
                        egressClient,
                        FakeRecordingComposer(),
                        hostRawRoot,
                        documentStorageRoot,
                        egressTimeoutMinutes = 30,
                        clock = { fakeNow },
                    )
                poller.tick()

                recordingRow(recordingId)[ConferenceRecordingTable.status] shouldBe ConferenceRecordingStatus.PROCESSING
            } finally {
                hostRawRoot.deleteRecursively()
                documentStorageRoot.deleteRecursively()
            }
        }

        test("STOPPING: past egressTimeout with ZERO completed video track -> FAILED") {
            val hostRawRoot = Files.createTempDirectory("poller-test-raw").toFile()
            val documentStorageRoot = Files.createTempDirectory("poller-test-docs").toFile()
            try {
                val member = createMember("poller-stopping-timeout-failed@example.org")
                val (roomId, livekitRoomName) = createRoom(member)
                val stoppedAt = DbClock.nowLocalDateTime()
                val recordingId = createRecording(roomId, member, ConferenceRecordingStatus.STOPPING, stoppedAt = stoppedAt)
                val egStuck = "EG_stuck-$recordingId"
                createTrack(
                    recordingId,
                    egressId = egStuck,
                    livekitTrackId = "TR_stuck",
                    status = ConferenceRecordingTrackStatus.ACTIVE,
                    trackSource = ConferenceRecordingTrackSource.CAMERA,
                )

                val egressClient = FakeLiveKitEgressClient()
                egressClient.egressInfoByEgressId[egStuck] =
                    LiveKitEgressInfo(egressId = egStuck, roomName = livekitRoomName, status = "EGRESS_ACTIVE")

                val fakeNow = stoppedAt.toJavaLocalDateTimeShiftedMinutes(31)
                val poller =
                    buildPoller(
                        FakeLiveKitAdminClient(),
                        egressClient,
                        FakeRecordingComposer(),
                        hostRawRoot,
                        documentStorageRoot,
                        egressTimeoutMinutes = 30,
                        clock = { fakeNow },
                    )
                poller.tick()

                val row = recordingRow(recordingId)
                row[ConferenceRecordingTable.status] shouldBe ConferenceRecordingStatus.FAILED
                row[ConferenceRecordingTable.failureReason] shouldBe "Zeitüberschreitung beim Abschluss der Aufzeichnung."
            } finally {
                hostRawRoot.deleteRecursively()
                documentStorageRoot.deleteRecursively()
            }
        }

        test(
            "STOPPING: zero track rows at all -> FAILED immediately, no LiveKit calls, no wait for egressTimeout (merge verification fix)",
        ) {
            val hostRawRoot = Files.createTempDirectory("poller-test-raw").toFile()
            val documentStorageRoot = Files.createTempDirectory("poller-test-docs").toFile()
            try {
                val member = createMember("poller-stopping-no-tracks@example.org")
                val (roomId, _) = createRoom(member)
                val stoppedAt = DbClock.nowLocalDateTime()
                val recordingId = createRecording(roomId, member, ConferenceRecordingStatus.STOPPING, stoppedAt = stoppedAt)
                // Deliberately NO createTrack call -- this recording was started and stopped with zero
                // participant ever publishing an unmuted track (e.g. camera/mic permission denied for
                // the whole meeting). handleRecording is the only startTrackEgress call site and only
                // runs while status == RECORDING, so this row's track set can never grow from here.

                val egressClient = FakeLiveKitEgressClient()
                val poller =
                    buildPoller(
                        FakeLiveKitAdminClient(),
                        egressClient,
                        FakeRecordingComposer(),
                        hostRawRoot,
                        documentStorageRoot,
                        egressTimeoutMinutes = 30,
                        // "now" is only seconds after stoppedAt -- if the old bug regressed, the
                        // recording would stay STOPPING here (nowhere near the 30-minute timeout) and
                        // the assertion below would catch it.
                        clock = { stoppedAt.toJavaLocalDateTimeShiftedMinutes(0) },
                    )
                poller.tick()

                val row = recordingRow(recordingId)
                row[ConferenceRecordingTable.status] shouldBe ConferenceRecordingStatus.FAILED
                row[ConferenceRecordingTable.failureReason] shouldBe "Es wurde keine Audio- oder Videospur aufgezeichnet."
                // No track row exists for THIS recording, so no egressId belonging to it could ever
                // appear in stoppedEgressIds regardless -- not asserted directly since tick() also
                // processes any other non-terminal recording left over from earlier tests sharing this
                // spec's one H2 database, which would make a blanket "stoppedEgressIds is empty" check
                // flaky; the row-scoped assertions above are the real proof this path never called
                // StopEgress/ListEgress for this recording (it returned before reaching that code).
            } finally {
                hostRawRoot.deleteRecursively()
                documentStorageRoot.deleteRecursively()
            }
        }

        test(
            "STOPPING: egress-timeout safety net still fires (-> FAILED) even while ListEgress itself is sustained-failing (review round 2)",
        ) {
            val hostRawRoot = Files.createTempDirectory("poller-test-raw").toFile()
            val documentStorageRoot = Files.createTempDirectory("poller-test-docs").toFile()
            try {
                val member = createMember("poller-stopping-listegress-outage-failed@example.org")
                val (roomId, _) = createRoom(member)
                val stoppedAt = DbClock.nowLocalDateTime()
                val recordingId = createRecording(roomId, member, ConferenceRecordingStatus.STOPPING, stoppedAt = stoppedAt)
                // No COMPLETE video track in the DB -- ListEgress never succeeds even once for this
                // recording, so the ACTIVE status set at creation is the only status ever known.
                val egStuck = "EG_stuck-$recordingId"
                createTrack(
                    recordingId,
                    egressId = egStuck,
                    livekitTrackId = "TR_stuck",
                    status = ConferenceRecordingTrackStatus.ACTIVE,
                    trackSource = ConferenceRecordingTrackSource.CAMERA,
                )

                // failListEgress=true -- every ListEgress call throws LiveKitAdminException,
                // simulating a sustained Twirp-API outage/misconfiguration. Before this fix,
                // handleStopping's `catch` block returned immediately and the elapsed-time/FAILED
                // check a few lines below was never reached, so the recording stayed STOPPING
                // forever regardless of how much wall-clock time passed.
                val egressClient = FakeLiveKitEgressClient(failListEgress = true)
                // Registered so the ACTIVE-track StopEgress call above `listEgress` in handleStopping
                // succeeds (the fake looks egressId up here) -- only `listEgress` itself is made to
                // fail by this test, matching the finding's exact failure mode.
                egressClient.egressInfoByEgressId[egStuck] =
                    LiveKitEgressInfo(egressId = egStuck, roomName = "irrelevant", status = "EGRESS_ACTIVE")

                val fakeNow = stoppedAt.toJavaLocalDateTimeShiftedMinutes(31)
                val poller =
                    buildPoller(
                        FakeLiveKitAdminClient(),
                        egressClient,
                        FakeRecordingComposer(),
                        hostRawRoot,
                        documentStorageRoot,
                        egressTimeoutMinutes = 30,
                        clock = { fakeNow },
                    )
                poller.tick()

                val row = recordingRow(recordingId)
                row[ConferenceRecordingTable.status] shouldBe ConferenceRecordingStatus.FAILED
                row[ConferenceRecordingTable.failureReason] shouldBe "Zeitüberschreitung beim Abschluss der Aufzeichnung."
            } finally {
                hostRawRoot.deleteRecursively()
                documentStorageRoot.deleteRecursively()
            }
        }

        test(
            "STOPPING: egress-timeout safety net still transitions to PROCESSING while ListEgress is failing, if the DB already has >=1 COMPLETE video track from before the outage started",
        ) {
            val hostRawRoot = Files.createTempDirectory("poller-test-raw").toFile()
            val documentStorageRoot = Files.createTempDirectory("poller-test-docs").toFile()
            try {
                val member = createMember("poller-stopping-listegress-outage-survivors@example.org")
                val (roomId, _) = createRoom(member)
                val stoppedAt = DbClock.nowLocalDateTime()
                val recordingId = createRecording(roomId, member, ConferenceRecordingStatus.STOPPING, stoppedAt = stoppedAt)
                // Already COMPLETE in the DB from a prior tick, BEFORE ListEgress started failing.
                createTrack(
                    recordingId,
                    egressId = "EG_done-$recordingId",
                    livekitTrackId = "TR_done",
                    status = ConferenceRecordingTrackStatus.COMPLETE,
                    trackSource = ConferenceRecordingTrackSource.CAMERA,
                )
                val egressClient = FakeLiveKitEgressClient(failListEgress = true)

                val fakeNow = stoppedAt.toJavaLocalDateTimeShiftedMinutes(31)
                val poller =
                    buildPoller(
                        FakeLiveKitAdminClient(),
                        egressClient,
                        FakeRecordingComposer(),
                        hostRawRoot,
                        documentStorageRoot,
                        egressTimeoutMinutes = 30,
                        clock = { fakeNow },
                    )
                poller.tick()

                recordingRow(recordingId)[ConferenceRecordingTable.status] shouldBe ConferenceRecordingStatus.PROCESSING
            } finally {
                hostRawRoot.deleteRecursively()
                documentStorageRoot.deleteRecursively()
            }
        }

        test("STOPPING: ListEgress failing but egressTimeout NOT yet elapsed -> stays STOPPING (no premature FAILED)") {
            val hostRawRoot = Files.createTempDirectory("poller-test-raw").toFile()
            val documentStorageRoot = Files.createTempDirectory("poller-test-docs").toFile()
            try {
                val member = createMember("poller-stopping-listegress-outage-early@example.org")
                val (roomId, _) = createRoom(member)
                val stoppedAt = DbClock.nowLocalDateTime()
                val recordingId = createRecording(roomId, member, ConferenceRecordingStatus.STOPPING, stoppedAt = stoppedAt)
                val egStuck = "EG_stuck-$recordingId"
                createTrack(
                    recordingId,
                    egressId = egStuck,
                    livekitTrackId = "TR_stuck",
                    status = ConferenceRecordingTrackStatus.ACTIVE,
                    trackSource = ConferenceRecordingTrackSource.CAMERA,
                )
                val egressClient = FakeLiveKitEgressClient(failListEgress = true)
                egressClient.egressInfoByEgressId[egStuck] =
                    LiveKitEgressInfo(egressId = egStuck, roomName = "irrelevant", status = "EGRESS_ACTIVE")

                // Only 1 minute elapsed, well under the 30-minute egressTimeoutMinutes below.
                val fakeNow = stoppedAt.toJavaLocalDateTimeShiftedMinutes(1)
                val poller =
                    buildPoller(
                        FakeLiveKitAdminClient(),
                        egressClient,
                        FakeRecordingComposer(),
                        hostRawRoot,
                        documentStorageRoot,
                        egressTimeoutMinutes = 30,
                        clock = { fakeNow },
                    )
                poller.tick()

                recordingRow(recordingId)[ConferenceRecordingTable.status] shouldBe ConferenceRecordingStatus.STOPPING
            } finally {
                hostRawRoot.deleteRecursively()
                documentStorageRoot.deleteRecursively()
            }
        }

        // ── PROCESSING ───────────────────────────────────────────────────

        test("PROCESSING: successful composition archives a document, stamps READY, and deletes raw files (keepRaw=false)") {
            val hostRawRoot = Files.createTempDirectory("poller-test-raw").toFile()
            val documentStorageRoot = Files.createTempDirectory("poller-test-docs").toFile()
            try {
                val member = createMember("poller-processing-success@example.org")
                val (roomId, _) = createRoom(member)
                val recordingId = createRecording(roomId, member, ConferenceRecordingStatus.PROCESSING)
                val rawDir = hostRawRoot.resolve(recordingId.toString()).apply { mkdirs() }
                val trackFile = rawDir.resolve("alice__CAMERA__TR_a.mp4").apply { writeBytes(byteArrayOf(1, 2, 3)) }
                createTrack(
                    recordingId,
                    egressId = "EG_a-$recordingId",
                    livekitTrackId = "TR_a",
                    status = ConferenceRecordingTrackStatus.COMPLETE,
                    trackSource = ConferenceRecordingTrackSource.CAMERA,
                    fileName = trackFile.name,
                    startedAtEpochNanos = 0L,
                    durationMs = 5_000L,
                )

                val composer = FakeRecordingComposer()
                val poller = buildPoller(FakeLiveKitAdminClient(), FakeLiveKitEgressClient(), composer, hostRawRoot, documentStorageRoot)

                poller.tick()

                composer.callCount shouldBe 1
                val row = recordingRow(recordingId)
                row[ConferenceRecordingTable.status] shouldBe ConferenceRecordingStatus.READY
                (row[ConferenceRecordingTable.documentId] != null) shouldBe true
                createdDocumentIds += row[ConferenceRecordingTable.documentId]!!
                (row[ConferenceRecordingTable.fileSizeBytes]!! > 0) shouldBe true
                rawDir.exists() shouldBe false
            } finally {
                hostRawRoot.deleteRecursively()
                documentStorageRoot.deleteRecursively()
            }
        }

        test("PROCESSING: successful composition with keepRaw=true retains the raw per-track directory") {
            val hostRawRoot = Files.createTempDirectory("poller-test-raw").toFile()
            val documentStorageRoot = Files.createTempDirectory("poller-test-docs").toFile()
            try {
                val member = createMember("poller-processing-keepraw@example.org")
                val (roomId, _) = createRoom(member)
                val recordingId = createRecording(roomId, member, ConferenceRecordingStatus.PROCESSING)
                val rawDir = hostRawRoot.resolve(recordingId.toString()).apply { mkdirs() }
                val trackFile = rawDir.resolve("alice__CAMERA__TR_a.mp4").apply { writeBytes(byteArrayOf(1, 2, 3)) }
                createTrack(
                    recordingId,
                    egressId = "EG_a-$recordingId",
                    livekitTrackId = "TR_a",
                    status = ConferenceRecordingTrackStatus.COMPLETE,
                    trackSource = ConferenceRecordingTrackSource.CAMERA,
                    fileName = trackFile.name,
                    startedAtEpochNanos = 0L,
                    durationMs = 5_000L,
                )

                val poller =
                    buildPoller(
                        FakeLiveKitAdminClient(),
                        FakeLiveKitEgressClient(),
                        FakeRecordingComposer(),
                        hostRawRoot,
                        documentStorageRoot,
                        keepRaw = true,
                    )
                poller.tick()

                val row = recordingRow(recordingId)
                row[ConferenceRecordingTable.status] shouldBe ConferenceRecordingStatus.READY
                createdDocumentIds += row[ConferenceRecordingTable.documentId]!!
                rawDir.exists() shouldBe true
                trackFile.exists() shouldBe true
            } finally {
                hostRawRoot.deleteRecursively()
                documentStorageRoot.deleteRecursively()
            }
        }

        test(
            "PROCESSING: composer failure on attempt 1 stays PROCESSING with compose_attempts=1 (retried later); raw files are never touched",
        ) {
            val hostRawRoot = Files.createTempDirectory("poller-test-raw").toFile()
            val documentStorageRoot = Files.createTempDirectory("poller-test-docs").toFile()
            try {
                val member = createMember("poller-processing-fail-attempt1@example.org")
                val (roomId, _) = createRoom(member)
                val recordingId = createRecording(roomId, member, ConferenceRecordingStatus.PROCESSING, composeAttempts = 0)
                val rawDir = hostRawRoot.resolve(recordingId.toString()).apply { mkdirs() }
                val trackFile = rawDir.resolve("alice__CAMERA__TR_a.mp4").apply { writeBytes(byteArrayOf(1, 2, 3)) }
                createTrack(
                    recordingId,
                    egressId = "EG_a-$recordingId",
                    livekitTrackId = "TR_a",
                    status = ConferenceRecordingTrackStatus.COMPLETE,
                    trackSource = ConferenceRecordingTrackSource.CAMERA,
                    fileName = trackFile.name,
                    startedAtEpochNanos = 0L,
                    durationMs = 5_000L,
                )

                val composer =
                    FakeRecordingComposer(behavior = { _, _ -> throw RecordingComposeException(message = "simulated ffmpeg failure") })
                val poller = buildPoller(FakeLiveKitAdminClient(), FakeLiveKitEgressClient(), composer, hostRawRoot, documentStorageRoot)

                poller.tick()

                val row = recordingRow(recordingId)
                row[ConferenceRecordingTable.status] shouldBe ConferenceRecordingStatus.PROCESSING
                row[ConferenceRecordingTable.composeAttempts] shouldBe 1
                rawDir.exists() shouldBe true
                trackFile.exists() shouldBe true
            } finally {
                hostRawRoot.deleteRecursively()
                documentStorageRoot.deleteRecursively()
            }
        }

        test("PROCESSING: composer failure on the FINAL attempt marks FAILED and RETAINS raw files regardless of keepRaw=false (D13)") {
            val hostRawRoot = Files.createTempDirectory("poller-test-raw").toFile()
            val documentStorageRoot = Files.createTempDirectory("poller-test-docs").toFile()
            try {
                val member = createMember("poller-processing-fail-final@example.org")
                val (roomId, _) = createRoom(member)
                // Already at composeAttempts=1 -- this tick is attempt 2, the MAX_COMPOSE_ATTEMPTS ceiling.
                val recordingId = createRecording(roomId, member, ConferenceRecordingStatus.PROCESSING, composeAttempts = 1)
                val rawDir = hostRawRoot.resolve(recordingId.toString()).apply { mkdirs() }
                val trackFile = rawDir.resolve("alice__CAMERA__TR_a.mp4").apply { writeBytes(byteArrayOf(1, 2, 3)) }
                createTrack(
                    recordingId,
                    egressId = "EG_a-$recordingId",
                    livekitTrackId = "TR_a",
                    status = ConferenceRecordingTrackStatus.COMPLETE,
                    trackSource = ConferenceRecordingTrackSource.CAMERA,
                    fileName = trackFile.name,
                    startedAtEpochNanos = 0L,
                    durationMs = 5_000L,
                )

                val composer =
                    FakeRecordingComposer(behavior = { _, _ -> throw RecordingComposeException(message = "simulated ffmpeg failure") })
                val poller =
                    buildPoller(
                        FakeLiveKitAdminClient(),
                        FakeLiveKitEgressClient(),
                        composer,
                        hostRawRoot,
                        documentStorageRoot,
                        keepRaw = false,
                    )

                poller.tick()

                val row = recordingRow(recordingId)
                row[ConferenceRecordingTable.status] shouldBe ConferenceRecordingStatus.FAILED
                row[ConferenceRecordingTable.failureReason] shouldBe "Die Aufzeichnung konnte nicht zusammengesetzt werden."
                row[ConferenceRecordingTable.composeAttempts] shouldBe 2
                // The critical D13 assertion: raw files survive a FAILED terminal state even though keepRaw=false.
                rawDir.exists() shouldBe true
                trackFile.exists() shouldBe true
            } finally {
                hostRawRoot.deleteRecursively()
                documentStorageRoot.deleteRecursively()
            }
        }

        test(
            "PROCESSING: restart reconciliation -- a row already at composeAttempts=2 is marked FAILED without ever invoking the composer again",
        ) {
            val hostRawRoot = Files.createTempDirectory("poller-test-raw").toFile()
            val documentStorageRoot = Files.createTempDirectory("poller-test-docs").toFile()
            try {
                val member = createMember("poller-processing-restart-reconciliation@example.org")
                val (roomId, _) = createRoom(member)
                val recordingId = createRecording(roomId, member, ConferenceRecordingStatus.PROCESSING, composeAttempts = 2)

                val composer = FakeRecordingComposer()
                val poller = buildPoller(FakeLiveKitAdminClient(), FakeLiveKitEgressClient(), composer, hostRawRoot, documentStorageRoot)

                poller.tick()

                composer.callCount shouldBe 0
                recordingRow(recordingId)[ConferenceRecordingTable.status] shouldBe ConferenceRecordingStatus.FAILED
            } finally {
                hostRawRoot.deleteRecursively()
                documentStorageRoot.deleteRecursively()
            }
        }

        // ── tick() exception-safety ─────────────────────────────────────

        test(
            "tick(): an unexpected Throwable while processing one recording does not stop OTHER recordings from making progress in the same tick",
        ) {
            val hostRawRoot = Files.createTempDirectory("poller-test-raw").toFile()
            val documentStorageRoot = Files.createTempDirectory("poller-test-docs").toFile()
            try {
                val member = createMember("poller-tick-safety@example.org")
                val (brokenRoomId, brokenRoomName) = createRoom(member)
                val brokenRecordingId = createRecording(brokenRoomId, member, ConferenceRecordingStatus.RECORDING)
                val (healthyRoomId, healthyRoomName) = createRoom(member)
                val healthyRecordingId = createRecording(healthyRoomId, member, ConferenceRecordingStatus.RECORDING)

                // A raw RuntimeException (NOT LiveKitAdminException) for the broken room's own
                // ListParticipants call -- proves tick()'s per-recording try/catch is genuinely
                // `catch (e: Throwable)`, not narrowly scoped to this codebase's own exception
                // hierarchy.
                val adminClient =
                    object : LiveKitAdminClient by FakeLiveKitAdminClient() {
                        override suspend fun listParticipants(room: String): List<LiveKitParticipantInfo> =
                            if (room == brokenRoomName) {
                                throw RuntimeException("simulated unexpected failure")
                            } else {
                                listOf(
                                    LiveKitParticipantInfo(
                                        identity = member.toString(),
                                        tracks =
                                            listOf(
                                                LiveKitTrackInfo(sid = "TR_healthy", type = "VIDEO", source = "CAMERA", muted = false),
                                            ),
                                    ),
                                )
                            }
                    }
                val egressClient = FakeLiveKitEgressClient()
                val poller = buildPoller(adminClient, egressClient, FakeRecordingComposer(), hostRawRoot, documentStorageRoot)

                poller.tick()

                // The healthy recording still made progress despite the broken one throwing in the same tick.
                egressClient.started shouldHaveSize 1
                egressClient.started.single().second shouldBe "TR_healthy"
                // The broken recording is simply untouched -- still RECORDING, no crash propagated out of tick().
                recordingRow(brokenRecordingId)[ConferenceRecordingTable.status] shouldBe ConferenceRecordingStatus.RECORDING
                recordingRow(healthyRecordingId)[ConferenceRecordingTable.status] shouldBe ConferenceRecordingStatus.RECORDING
            } finally {
                hostRawRoot.deleteRecursively()
                documentStorageRoot.deleteRecursively()
            }
        }
    })

/** Test-only helper -- shifts a [LocalDateTime] by [minutes] using kotlinx-datetime's own Instant arithmetic, same conversion RecordingPoller itself uses. */
private fun LocalDateTime.toJavaLocalDateTimeShiftedMinutes(minutes: Long): LocalDateTime {
    val zone = TimeZone.currentSystemDefault()
    return this
        .toInstant(zone)
        .plus(minutes.minutes)
        .toLocalDateTime(zone)
}
