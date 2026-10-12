package network.lapis.cloud.server.conference

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
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
import network.lapis.cloud.server.db.generated.ConferenceRecordingSpeakerSpanTable
import network.lapis.cloud.server.db.generated.ConferenceRecordingTable
import network.lapis.cloud.server.db.generated.ConferenceRecordingTrackTable
import network.lapis.cloud.server.db.generated.ConferenceRoomTable
import network.lapis.cloud.server.db.generated.DocumentTable
import network.lapis.cloud.server.db.generated.DocumentVersionTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.server.routes.archiveGeneratedFile
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.ConferenceBroadcastView
import network.lapis.cloud.shared.domain.ConferenceRecordingEgressMode
import network.lapis.cloud.shared.domain.ConferenceRecordingFailureCode
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
internal class FakeLiveKitAdminClient : LiveKitAdminClient {
    override suspend fun muteMicrophone(
        room: String,
        identity: String,
        trackSid: String,
    ): Unit = throw UnsupportedOperationException("muteMicrophone is not used by this fake")

    override suspend fun updateParticipantPermission(
        room: String,
        identity: String,
        permission: LiveKitParticipantPermissionUpdate,
    ): Unit = throw UnsupportedOperationException("updateParticipantPermission is not used by this fake")

    override suspend fun sendDataTo(
        room: String,
        topic: String,
        payload: ByteArray,
        destinationIdentities: List<String>,
    ): Unit = throw UnsupportedOperationException("sendDataTo is not used by this fake")

    override suspend fun sendData(
        room: String,
        topic: String,
        payload: ByteArray,
    ): Unit = throw UnsupportedOperationException("sendData is not used by this fake")

    val participantsByRoom = mutableMapOf<String, List<LiveKitParticipantInfo>>()

    override suspend fun createRoom(
        name: String,
        maxParticipants: Int,
        emptyTimeoutSeconds: Int,
        departureTimeoutSeconds: Int?,
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
internal class FakeLiveKitEgressClient(
    private val failListEgress: Boolean = false,
) : LiveKitEgressClient {
    val started = mutableListOf<Triple<String, String, String>>() // (roomName, trackId, outputPath)

    /** V1.9.110: (roomName, identity, outputFilepath) of every StartParticipantEgress with a file output. */
    val participantFileStarts = mutableListOf<Triple<String, String, String>>()

    /** V1.9.110: while `true`, startParticipantFileEgress is refused like LiveKit does when it has no egress CPU left. */
    var failParticipantFileStart = false
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

    override suspend fun startParticipantFileEgress(
        roomName: String,
        identity: String,
        outputFilepath: String,
    ): LiveKitEgressInfo {
        if (failParticipantFileStart) throw LiveKitAdminException(message = "StartParticipantEgress refused (simulated)")
        val egressId = "EG_fake-${Uuid.random()}"
        participantFileStarts += Triple(roomName, identity, outputFilepath)
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

/**
 * V1.9.109: a composition no longer runs inside `tick()` but on the poller's worker coroutine -- a test that wants its outcome ticks AND
 * waits for the worker (a no-op for every tick that started none).
 */
internal suspend fun RecordingPoller.tickAndSettle() {
    tick()
    awaitCompositionIdleForTest()
}

/** [behavior] defaults to writing a few bytes to [outputFile] -- override to simulate a failure via [RecordingComposeException]. */
internal class FakeRecordingComposer(
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
 * A/V-sync fix (2026-09-27) -- returns a scripted media duration per file name, `null` for anything
 * unscripted (a failed probe). Uses the interface's default `probe()` (start time `null`), which is
 * exactly the shape a minimal implementation has.
 */
private class FakeRecordingMediaProber(
    private val durationsByFileName: Map<String, Double> = emptyMap(),
) : RecordingMediaProber {
    val probedFileNames = mutableListOf<String>()

    override suspend fun probeDurationSeconds(file: File): Double? {
        probedFileNames += file.name
        return durationsByFileName[file.name]
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
                    it[status] = MemberStatus.ACTIVE
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
            egressMode: ConferenceRecordingEgressMode = ConferenceRecordingEgressMode.TRACK,
            broadcastView: ConferenceBroadcastView = ConferenceBroadcastView.RASTER,
        ): Uuid {
            val id = Uuid.random()
            transaction {
                ConferenceRecordingTable.insert {
                    it[ConferenceRecordingTable.broadcastView] = broadcastView
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
                    it[ConferenceRecordingTable.egressMode] = egressMode
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
            egressKind: ConferenceRecordingEgressMode = ConferenceRecordingEgressMode.TRACK,
            identity: String? = null,
        ): Uuid {
            val id = Uuid.random()
            transaction {
                ConferenceRecordingTrackTable.insert {
                    it[ConferenceRecordingTrackTable.id] = id
                    it[ConferenceRecordingTrackTable.recordingId] = recordingId
                    it[ConferenceRecordingTrackTable.egressId] = egressId
                    it[ConferenceRecordingTrackTable.livekitTrackId] = livekitTrackId
                    it[participantIdentity] = identity ?: "identity-$livekitTrackId"
                    it[ConferenceRecordingTrackTable.trackSource] = trackSource
                    it[ConferenceRecordingTrackTable.status] = status
                    it[ConferenceRecordingTrackTable.startedAtEpochNanos] = startedAtEpochNanos
                    it[endedAtEpochNanos] = null
                    it[ConferenceRecordingTrackTable.fileName] = fileName
                    it[ConferenceRecordingTrackTable.durationMs] = durationMs
                    it[sizeBytes] = null
                    it[ConferenceRecordingTrackTable.egressKind] = egressKind
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
            pollIntervalSeconds: Long = 10,
            prober: RecordingMediaProber = RecordingMediaProber.None,
            clock: () -> LocalDateTime = { DbClock.nowLocalDateTime() },
            composeTimeoutMinutes: Long? = null,
            diskSpace: (File) -> Long = { Long.MAX_VALUE / 4 },
            egressMode: ConferenceRecordingEgressMode = ConferenceRecordingEgressMode.TRACK,
            blankDetector: RecordingBlankVideoDetector = RecordingBlankVideoDetector.None,
            speechAnalyzer: SpeechActivityAnalyzer = SpeechActivityAnalyzer.None,
        ): RecordingPoller {
            val config =
                ConferenceRecordingConfig.load { key ->
                    when (key) {
                        "LAPIS_RECORDING_ENABLED" -> "true"
                        "LAPIS_RECORDING_POLL_INTERVAL_SECONDS" -> pollIntervalSeconds.toString()
                        "LAPIS_RECORDING_MAX_TRACKS" -> maxTracks.toString()
                        "LAPIS_RECORDING_MAX_DURATION_MINUTES" -> maxDurationMinutes.toString()
                        "LAPIS_RECORDING_EGRESS_TIMEOUT_MINUTES" -> egressTimeoutMinutes.toString()
                        "LAPIS_RECORDING_KEEP_RAW" -> keepRaw.toString()
                        "LAPIS_RECORDING_EGRESS_MODE" -> egressMode.name.lowercase()
                        "LAPIS_RECORDING_COMPOSE_TIMEOUT_MINUTES" -> composeTimeoutMinutes?.toString()
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
                prober = prober,
                blankDetector = blankDetector,
                clock = clock,
                diskSpace = diskSpace,
                speechAnalyzer = speechAnalyzer,
            )
        }

        // ── RECORDING ────────────────────────────────────────────────────

        test(
            "RECORDING: requestTick() wakes the running loop long before the poll interval elapses " +
                "(first seconds no longer wait for the next tick)",
        ) {
            val hostRawRoot = Files.createTempDirectory("poller-test-raw").toFile()
            val documentStorageRoot = Files.createTempDirectory("poller-test-docs").toFile()
            val adminClient = FakeLiveKitAdminClient()
            val egressClient = FakeLiveKitEgressClient()
            // One-hour interval: without requestTick() the second tick would never come during this test.
            val poller =
                buildPoller(
                    adminClient,
                    egressClient,
                    FakeRecordingComposer(),
                    hostRawRoot,
                    documentStorageRoot,
                    pollIntervalSeconds = 3_600,
                )
            try {
                poller.start()
                // Let the loop's FIRST tick (nothing to do yet -- no RECORDING row exists) run and go to sleep.
                delay(300)

                // Now what startRecording does: insert the row, then poke the poller.
                val member = createMember("poller-request-tick@example.org")
                val (roomId, livekitRoomName) = createRoom(member)
                adminClient.participantsByRoom[livekitRoomName] =
                    listOf(
                        LiveKitParticipantInfo(
                            identity = "alice",
                            tracks = listOf(LiveKitTrackInfo(sid = "TR_wake", source = "CAMERA", muted = false)),
                        ),
                    )
                val recordingId = createRecording(roomId, member, ConferenceRecordingStatus.RECORDING)
                poller.requestTick()

                // Generous bounds: these only cost time when the test would fail anyway (a loaded CI runner can starve the poller).
                withTimeout(30_000) { while (egressClient.started.isEmpty()) delay(10) }
                egressClient.started.single().second shouldBe "TR_wake"

                // The poller records the track row AFTER it has asked the egress client to start, so the row is not there the instant
                // `started` fills (a race that failed on a loaded CI runner: expected 1, was 0) -- wait for it instead of reading once.
                fun trackRows(): Long =
                    transaction {
                        ConferenceRecordingTrackTable.selectAll().where { ConferenceRecordingTrackTable.recordingId eq recordingId }.count()
                    }
                withTimeout(30_000) { while (trackRows() < 1L) delay(10) }
                trackRows() shouldBe 1L
            } finally {
                poller.stop()
                hostRawRoot.deleteRecursively()
                documentStorageRoot.deleteRecursively()
            }
        }

        test("requestTick() on a poller that was never started is a harmless no-op") {
            val hostRawRoot = Files.createTempDirectory("poller-test-raw").toFile()
            val documentStorageRoot = Files.createTempDirectory("poller-test-docs").toFile()
            try {
                val poller =
                    buildPoller(
                        FakeLiveKitAdminClient(),
                        FakeLiveKitEgressClient(),
                        FakeRecordingComposer(),
                        hostRawRoot,
                        documentStorageRoot,
                    )
                poller.requestTick()
                poller.requestTick()
            } finally {
                hostRawRoot.deleteRecursively()
                documentStorageRoot.deleteRecursively()
            }
        }

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

                poller.tickAndSettle()

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

                poller.tickAndSettle()

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

                poller.tickAndSettle()

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
                poller.tickAndSettle()

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
                poller.tickAndSettle()

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

                poller.tickAndSettle()

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
                poller.tickAndSettle()

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
                poller.tickAndSettle()

                val row = recordingRow(recordingId)
                row[ConferenceRecordingTable.status] shouldBe ConferenceRecordingStatus.FAILED
                row[ConferenceRecordingTable.failureReason] shouldBe "Zeitüberschreitung beim Abschluss der Aufzeichnung."
                row[ConferenceRecordingTable.failureCode] shouldBe ConferenceRecordingFailureCode.EGRESS_TIMEOUT
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
                poller.tickAndSettle()

                val row = recordingRow(recordingId)
                row[ConferenceRecordingTable.status] shouldBe ConferenceRecordingStatus.FAILED
                row[ConferenceRecordingTable.failureReason] shouldBe "Es wurde keine Audio- oder Videospur aufgezeichnet."
                row[ConferenceRecordingTable.failureCode] shouldBe ConferenceRecordingFailureCode.NO_TRACKS
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
                poller.tickAndSettle()

                val row = recordingRow(recordingId)
                row[ConferenceRecordingTable.status] shouldBe ConferenceRecordingStatus.FAILED
                row[ConferenceRecordingTable.failureReason] shouldBe "Zeitüberschreitung beim Abschluss der Aufzeichnung."
                row[ConferenceRecordingTable.failureCode] shouldBe ConferenceRecordingFailureCode.EGRESS_TIMEOUT
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
                poller.tickAndSettle()

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
                poller.tickAndSettle()

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

                poller.tickAndSettle()

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

        test(
            "PROCESSING: A/V alignment (real ELB numbers 2026-09-27) -- offsets are the started_at differences ONLY " +
                "(mic 0.000, cam 0.084, share 5.000); the probe's reported-minus-probed differences (0.385 s / 0.100 s) " +
                "are logged but never applied",
        ) {
            val hostRawRoot = Files.createTempDirectory("poller-test-raw").toFile()
            val documentStorageRoot = Files.createTempDirectory("poller-test-docs").toFile()
            try {
                val member = createMember("poller-avsync@example.org")
                val (roomId, _) = createRoom(member)
                val recordingId = createRecording(roomId, member, ConferenceRecordingStatus.PROCESSING)
                val rawDir = hostRawRoot.resolve(recordingId.toString()).apply { mkdirs() }
                val micFile = rawDir.resolve("alice__MICROPHONE__TR_m.ogg").apply { writeBytes(byteArrayOf(1)) }
                val camFile = rawDir.resolve("alice__CAMERA__TR_c.webm").apply { writeBytes(byteArrayOf(1)) }
                val shareFile = rawDir.resolve("alice__SCREEN_SHARE__TR_s.mp4").apply { writeBytes(byteArrayOf(1)) }
                // REAL values of ELB recording 80394924-4185-4f82-8cc6-03ee8d9b269c (2026-09-27), see
                // RecordingTrackAlignmentTest -- the recording that exposed round 1's double shift.
                val t0 = 1_790_529_971_823_572_890L
                createTrack(
                    recordingId,
                    egressId = "EG_m-$recordingId",
                    livekitTrackId = "TR_m",
                    status = ConferenceRecordingTrackStatus.COMPLETE,
                    trackSource = ConferenceRecordingTrackSource.MICROPHONE,
                    fileName = micFile.name,
                    startedAtEpochNanos = t0,
                    durationMs = 40_312L,
                )
                createTrack(
                    recordingId,
                    egressId = "EG_c-$recordingId",
                    livekitTrackId = "TR_c",
                    status = ConferenceRecordingTrackStatus.COMPLETE,
                    trackSource = ConferenceRecordingTrackSource.CAMERA,
                    fileName = camFile.name,
                    startedAtEpochNanos = 1_790_529_971_907_704_186L,
                    durationMs = 39_905L,
                )
                createTrack(
                    recordingId,
                    egressId = "EG_s-$recordingId",
                    livekitTrackId = "TR_s",
                    status = ConferenceRecordingTrackStatus.COMPLETE,
                    trackSource = ConferenceRecordingTrackSource.SCREEN_SHARE,
                    fileName = shareFile.name,
                    startedAtEpochNanos = t0 + 5_000_000_000L,
                    durationMs = 17_000L,
                )
                val prober =
                    FakeRecordingMediaProber(
                        mapOf(
                            micFile.name to 39.927313, // ffprobe of the real mic.ogg: 0.385 s shorter than reported
                            camFile.name to 39.804511, // ffprobe of the real cam.webm: 0.100 s shorter than reported
                            // shareFile deliberately unscripted -> probe "fails" -> same offset as a successful probe
                        ),
                    )
                var captured: RecordingComposeSpec? = null
                val composer =
                    FakeRecordingComposer { spec, outputFile ->
                        captured = spec
                        outputFile.writeBytes(byteArrayOf(1))
                    }
                val poller =
                    buildPoller(
                        FakeLiveKitAdminClient(),
                        FakeLiveKitEgressClient(),
                        composer,
                        hostRawRoot,
                        documentStorageRoot,
                        prober = prober,
                    )

                poller.tickAndSettle()

                val spec = captured ?: error("composer was not invoked")
                prober.probedFileNames.toSet() shouldBe setOf(micFile.name, camFile.name, shareFile.name)
                spec.audioInputs.single().offsetSeconds shouldBe 0.0
                val cam = spec.videoInputs.single { it.file.name == camFile.name }
                val share = spec.videoInputs.single { it.file.name == shareFile.name }
                // Round 1 (93f378ba) composed this recording with mic 0.385 / cam 0.184; round 2 keeps only
                // the real 0.084 s started_at difference and the probe differences stay out of the offsets.
                cam.offsetSeconds shouldBe (0.084 plusOrMinus 0.0005)
                share.offsetSeconds shouldBe (5.0 plusOrMinus 0.0005)
                share.isScreenShare shouldBe true
                // Output duration is governed by start offsets + reported durations (mic: 0 + 40.312 is the longest).
                spec.outputDurationSeconds shouldBe (40.312 plusOrMinus 0.0005)
                recordingRow(recordingId)[ConferenceRecordingTable.documentId]?.let { createdDocumentIds += it }
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
                poller.tickAndSettle()

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

                poller.tickAndSettle()

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

        test("WORKER: a second tick while the LAST attempt is composing neither fails the row nor loses the READY result") {
            val hostRawRoot = Files.createTempDirectory("poller-test-raw").toFile()
            val documentStorageRoot = Files.createTempDirectory("poller-test-docs").toFile()
            try {
                val member = createMember("poller-last-attempt-running@example.org")
                val (roomId, _) = createRoom(member)
                val recordingId =
                    createRecording(roomId, member, ConferenceRecordingStatus.PROCESSING, composeAttempts = MAX_COMPOSE_ATTEMPTS - 1)
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
                val gate = CompletableDeferred<Unit>()
                val entered = CompletableDeferred<Unit>()
                val composer =
                    object : RecordingComposer {
                        override suspend fun compose(
                            spec: RecordingComposeSpec,
                            outputFile: File,
                        ) {
                            entered.complete(Unit)
                            gate.await()
                            outputFile.writeBytes(ByteArray(10) { 1 })
                        }
                    }
                val poller = buildPoller(FakeLiveKitAdminClient(), FakeLiveKitEgressClient(), composer, hostRawRoot, documentStorageRoot)

                withTimeout(10_000) { poller.tick() }
                withTimeout(10_000) { entered.await() }
                // the counter is now at the ceiling, but the worker is composing: the next tick must leave the row alone
                withTimeout(10_000) { poller.tick() }
                recordingRow(recordingId)[ConferenceRecordingTable.status] shouldBe ConferenceRecordingStatus.PROCESSING

                gate.complete(Unit)
                withTimeout(10_000) { poller.awaitCompositionIdleForTest() }
                val ready = recordingRow(recordingId)
                ready[ConferenceRecordingTable.status] shouldBe ConferenceRecordingStatus.READY
                ready[ConferenceRecordingTable.documentId]?.let { createdDocumentIds += it }
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
                // Already at composeAttempts=4 -- this tick is attempt 5, the MAX_COMPOSE_ATTEMPTS ceiling.
                val recordingId = createRecording(roomId, member, ConferenceRecordingStatus.PROCESSING, composeAttempts = 4)
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

                poller.tickAndSettle()

                val row = recordingRow(recordingId)
                row[ConferenceRecordingTable.status] shouldBe ConferenceRecordingStatus.FAILED
                row[ConferenceRecordingTable.failureReason] shouldBe "Die Aufzeichnung konnte nicht zusammengesetzt werden."
                row[ConferenceRecordingTable.failureCode] shouldBe ConferenceRecordingFailureCode.COMPOSE_FAILED
                row[ConferenceRecordingTable.composeAttempts] shouldBe 5
                // The critical D13 assertion: raw files survive a FAILED terminal state even though keepRaw=false.
                rawDir.exists() shouldBe true
                trackFile.exists() shouldBe true
            } finally {
                hostRawRoot.deleteRecursively()
                documentStorageRoot.deleteRecursively()
            }
        }

        test("PROCESSING: no COMPLETE track at all -> FAILED with NO_TRACK_STORED") {
            val hostRawRoot = Files.createTempDirectory("poller-test-raw").toFile()
            val documentStorageRoot = Files.createTempDirectory("poller-test-docs").toFile()
            try {
                val member = createMember("poller-no-track-stored@example.org")
                val (roomId, _) = createRoom(member)
                val recordingId = createRecording(roomId, member, ConferenceRecordingStatus.PROCESSING, composeAttempts = 0)
                createTrack(recordingId, egressId = "EG_x-$recordingId", status = ConferenceRecordingTrackStatus.ABORTED)

                val composer = FakeRecordingComposer()
                buildPoller(FakeLiveKitAdminClient(), FakeLiveKitEgressClient(), composer, hostRawRoot, documentStorageRoot).tickAndSettle()

                composer.callCount shouldBe 0
                val row = recordingRow(recordingId)
                row[ConferenceRecordingTable.status] shouldBe ConferenceRecordingStatus.FAILED
                row[ConferenceRecordingTable.failureCode] shouldBe ConferenceRecordingFailureCode.NO_TRACK_STORED
            } finally {
                hostRawRoot.deleteRecursively()
                documentStorageRoot.deleteRecursively()
            }
        }

        test(
            "PROCESSING: restart reconciliation -- a row already at composeAttempts=5 is marked FAILED without ever invoking the composer again",
        ) {
            val hostRawRoot = Files.createTempDirectory("poller-test-raw").toFile()
            val documentStorageRoot = Files.createTempDirectory("poller-test-docs").toFile()
            try {
                val member = createMember("poller-processing-restart-reconciliation@example.org")
                val (roomId, _) = createRoom(member)
                val recordingId = createRecording(roomId, member, ConferenceRecordingStatus.PROCESSING, composeAttempts = 5)

                val composer = FakeRecordingComposer()
                val poller = buildPoller(FakeLiveKitAdminClient(), FakeLiveKitEgressClient(), composer, hostRawRoot, documentStorageRoot)

                poller.tickAndSettle()

                composer.callCount shouldBe 0
                recordingRow(recordingId)[ConferenceRecordingTable.status] shouldBe ConferenceRecordingStatus.FAILED
                recordingRow(recordingId)[ConferenceRecordingTable.failureCode] shouldBe ConferenceRecordingFailureCode.COMPOSE_FAILED
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

                poller.tickAndSettle()

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

        // ── RECOMPUTE of a READY recording (V1.9.109) ─────────────────────

        /** READY recording with an archived document (version 1, a real blob), a camera + microphone track, raw files, and a recompute marker. */
        class ReadyFixture(
            val recordingId: Uuid,
            val documentId: Uuid,
            val rawDir: File,
            val marker: LocalDateTime,
            val firstVersionId: Uuid,
        )

        fun createReadyForRecompute(
            member: Uuid,
            documentStorageRoot: File,
            hostRawRoot: File,
            marker: LocalDateTime? = DbClock.nowLocalDateTime(),
        ): ReadyFixture {
            val (roomId, _) = createRoom(member)
            val recordingId = createRecording(roomId, member, ConferenceRecordingStatus.READY)
            val source = Files.createTempFile("poller-first", ".mp4").toFile().apply { writeBytes(ByteArray(100) { 9 }) }
            val documentId =
                archiveGeneratedFile(
                    storageRoot = documentStorageRoot,
                    folderName = "Aufzeichnungen",
                    fileName = "aufzeichnung-$recordingId.mp4",
                    title = "Aufzeichnung $recordingId",
                    sourceFile = source,
                    mimeType = "video/mp4",
                    uploadedBy = member,
                    accessLevel = DocumentAccessLevel.BOARD_ONLY,
                )
            source.delete()
            createdDocumentIds += documentId
            val firstVersion =
                transaction {
                    DocumentTable.selectAll().where { DocumentTable.id eq documentId }.single()[DocumentTable.currentVersionId]!!
                }
            transaction {
                ConferenceRecordingTable.update({ ConferenceRecordingTable.id eq recordingId }) {
                    it[ConferenceRecordingTable.documentId] = documentId
                    it[durationSeconds] = 5L
                    it[fileSizeBytes] = 100L
                    it[readyAt] = DbClock.nowLocalDateTime()
                    it[recomputeStartedAt] = marker
                }
            }
            val rawDir = hostRawRoot.resolve(recordingId.toString()).apply { mkdirs() }
            val camFile = rawDir.resolve("alice__CAMERA__TR_a.mp4").apply { writeBytes(byteArrayOf(1, 2, 3)) }
            val micFile = rawDir.resolve("alice__MICROPHONE__TR_m.ogg").apply { writeBytes(byteArrayOf(4, 5)) }
            createTrack(
                recordingId,
                egressId = "EG_a-$recordingId",
                livekitTrackId = "TR_a",
                status = ConferenceRecordingTrackStatus.COMPLETE,
                trackSource = ConferenceRecordingTrackSource.CAMERA,
                fileName = camFile.name,
                startedAtEpochNanos = 0L,
                durationMs = 5_000L,
            )
            createTrack(
                recordingId,
                egressId = "EG_m-$recordingId",
                livekitTrackId = "TR_m",
                status = ConferenceRecordingTrackStatus.COMPLETE,
                trackSource = ConferenceRecordingTrackSource.MICROPHONE,
                fileName = micFile.name,
                startedAtEpochNanos = 0L,
                durationMs = 5_000L,
            )
            val stored = recordingRow(recordingId)[ConferenceRecordingTable.recomputeStartedAt]
            return ReadyFixture(recordingId, documentId, rawDir, stored ?: DbClock.nowLocalDateTime(), firstVersion)
        }

        fun versionsOf(documentId: Uuid) =
            transaction { DocumentVersionTable.selectAll().where { DocumentVersionTable.documentId eq documentId }.toList() }

        fun currentVersionOf(documentId: Uuid): Uuid =
            transaction { DocumentTable.selectAll().where { DocumentTable.id eq documentId }.single()[DocumentTable.currentVersionId]!! }

        fun recomputeAudit(recordingId: Uuid) =
            transaction {
                AuditLogEntryTable
                    .selectAll()
                    .where { AuditLogEntryTable.entityId eq recordingId }
                    .map { it[AuditLogEntryTable.afterSnapshot] }
            }

        test("RECOMPUTE: success adds a NEW document version, switches the current one, keeps the old blob and the raw files") {
            val hostRawRoot = Files.createTempDirectory("poller-test-raw").toFile()
            val documentStorageRoot = Files.createTempDirectory("poller-test-docs").toFile()
            try {
                val member = createMember("poller-recompute-ok@example.org")
                val fx = createReadyForRecompute(member, documentStorageRoot, hostRawRoot)
                val oldStorageKey =
                    versionsOf(fx.documentId).single()[DocumentVersionTable.storageKey]
                var capturedSpec: RecordingComposeSpec? = null
                val composer =
                    FakeRecordingComposer { spec, outputFile ->
                        capturedSpec = spec
                        outputFile.writeBytes(ByteArray(777) { 3 })
                    }
                val poller =
                    buildPoller(
                        FakeLiveKitAdminClient(),
                        FakeLiveKitEgressClient(),
                        composer,
                        hostRawRoot,
                        documentStorageRoot,
                        keepRaw = false,
                    )

                poller.tickAndSettle()

                composer.callCount shouldBe 1
                (capturedSpec != null) shouldBe true
                val versions = versionsOf(fx.documentId).sortedBy { it[DocumentVersionTable.versionNumber] }
                versions.map { it[DocumentVersionTable.versionNumber] } shouldBe listOf(1, 2)
                val newVersion = versions.last()
                currentVersionOf(fx.documentId) shouldBe newVersion[DocumentVersionTable.id]
                newVersion[DocumentVersionTable.fileSizeBytes] shouldBe 777L
                documentStorageRoot.resolve(newVersion[DocumentVersionTable.storageKey]).length() shouldBe 777L
                // the previous version stays: row and bytes
                documentStorageRoot.resolve(oldStorageKey).exists() shouldBe true
                val row = recordingRow(fx.recordingId)
                row[ConferenceRecordingTable.status] shouldBe ConferenceRecordingStatus.READY
                row[ConferenceRecordingTable.recomputeStartedAt] shouldBe null
                (row[ConferenceRecordingTable.recomputedAt] != null) shouldBe true
                row[ConferenceRecordingTable.lastRecomputeFailureCode] shouldBe null
                row[ConferenceRecordingTable.fileSizeBytes] shouldBe 777L
                row[ConferenceRecordingTable.documentId] shouldBe fx.documentId
                // the raw files are NEVER deleted by a recompute, whatever keepRaw says
                fx.rawDir.exists() shouldBe true
                fx.rawDir.resolve("alice__CAMERA__TR_a.mp4").exists() shouldBe true
                // no work files left behind
                fx.rawDir.listFiles { f -> f.name.endsWith(".part") || f.name.startsWith(".work-") }!!.size shouldBe 0
                recomputeAudit(fx.recordingId) shouldContain "recompute completed"
            } finally {
                hostRawRoot.deleteRecursively()
                documentStorageRoot.deleteRecursively()
            }
        }

        test(
            "RECOMPUTE: the composer fails -> the old version stays current and intact, COMPOSE_FAILED recorded, marker cleared, .part removed",
        ) {
            val hostRawRoot = Files.createTempDirectory("poller-test-raw").toFile()
            val documentStorageRoot = Files.createTempDirectory("poller-test-docs").toFile()
            try {
                val member = createMember("poller-recompute-fail@example.org")
                val fx = createReadyForRecompute(member, documentStorageRoot, hostRawRoot)
                val composer =
                    FakeRecordingComposer { _, outputFile ->
                        outputFile.writeBytes(byteArrayOf(1, 2, 3)) // a half-written result
                        throw RecordingComposeException(message = "simulated failure")
                    }
                val poller = buildPoller(FakeLiveKitAdminClient(), FakeLiveKitEgressClient(), composer, hostRawRoot, documentStorageRoot)

                poller.tickAndSettle()

                versionsOf(fx.documentId).size shouldBe 1
                currentVersionOf(fx.documentId) shouldBe fx.firstVersionId
                val row = recordingRow(fx.recordingId)
                row[ConferenceRecordingTable.status] shouldBe ConferenceRecordingStatus.READY
                row[ConferenceRecordingTable.recomputeStartedAt] shouldBe null
                row[ConferenceRecordingTable.lastRecomputeFailureCode] shouldBe ConferenceRecordingFailureCode.COMPOSE_FAILED
                row[ConferenceRecordingTable.fileSizeBytes] shouldBe 100L
                fx.rawDir.listFiles { f -> f.name.endsWith(".part") }!!.size shouldBe 0
                fx.rawDir.resolve("alice__CAMERA__TR_a.mp4").exists() shouldBe true
                recomputeAudit(fx.recordingId) shouldContain "recompute failed (COMPOSE_FAILED)"
                // a failed marker is not retried by the next tick
                poller.tickAndSettle()
                composer.callCount shouldBe 1
            } finally {
                hostRawRoot.deleteRecursively()
                documentStorageRoot.deleteRecursively()
            }
        }

        test("RECOMPUTE: the recording is deleted while the composer runs -> fencing: no orphan version, the new blob is removed") {
            val hostRawRoot = Files.createTempDirectory("poller-test-raw").toFile()
            val documentStorageRoot = Files.createTempDirectory("poller-test-docs").toFile()
            try {
                val member = createMember("poller-recompute-deleted@example.org")
                val fx = createReadyForRecompute(member, documentStorageRoot, hostRawRoot)
                val composer =
                    FakeRecordingComposer { _, outputFile ->
                        outputFile.writeBytes(ByteArray(500) { 1 })
                        // what ConferenceRecordingService.deleteRecording does, in the middle of the composition
                        transaction {
                            ConferenceRecordingTrackTable.deleteWhere { ConferenceRecordingTrackTable.recordingId eq fx.recordingId }
                            ConferenceRecordingTable.deleteWhere { ConferenceRecordingTable.id eq fx.recordingId }
                        }
                    }
                val poller = buildPoller(FakeLiveKitAdminClient(), FakeLiveKitEgressClient(), composer, hostRawRoot, documentStorageRoot)
                val blobsBefore = documentStorageRoot.resolve(fx.documentId.toString()).listFiles()!!.size

                poller.tickAndSettle()

                versionsOf(fx.documentId).size shouldBe 1
                currentVersionOf(fx.documentId) shouldBe fx.firstVersionId
                documentStorageRoot.resolve(fx.documentId.toString()).listFiles()!!.size shouldBe blobsBefore
                transaction {
                    ConferenceRecordingTable.selectAll().where { ConferenceRecordingTable.id eq fx.recordingId }.count()
                } shouldBe
                    0L
            } finally {
                hostRawRoot.deleteRecursively()
                documentStorageRoot.deleteRecursively()
            }
        }

        test("RECOMPUTE: a newer request replaced the marker while the composer ran -> the old worker writes nothing") {
            val hostRawRoot = Files.createTempDirectory("poller-test-raw").toFile()
            val documentStorageRoot = Files.createTempDirectory("poller-test-docs").toFile()
            try {
                val member = createMember("poller-recompute-superseded@example.org")
                val fx = createReadyForRecompute(member, documentStorageRoot, hostRawRoot)
                val newerMarker = fx.marker.toJavaLocalDateTimeShiftedMinutes(5)
                val composer =
                    FakeRecordingComposer { _, outputFile ->
                        outputFile.writeBytes(ByteArray(500) { 1 })
                        transaction {
                            ConferenceRecordingTable.update({ ConferenceRecordingTable.id eq fx.recordingId }) {
                                it[recomputeStartedAt] =
                                    newerMarker
                            }
                        }
                    }
                val poller = buildPoller(FakeLiveKitAdminClient(), FakeLiveKitEgressClient(), composer, hostRawRoot, documentStorageRoot)

                poller.tickAndSettle()

                versionsOf(fx.documentId).size shouldBe 1
                currentVersionOf(fx.documentId) shouldBe fx.firstVersionId
                // the newer request's marker is untouched (the failure write of the old worker is fenced as well)
                recordingRow(fx.recordingId)[ConferenceRecordingTable.recomputeStartedAt] shouldBe newerMarker
            } finally {
                hostRawRoot.deleteRecursively()
                documentStorageRoot.deleteRecursively()
            }
        }

        test("RECOMPUTE: a marker far older than twice the compose timeout with no worker is declared failed without composing") {
            val hostRawRoot = Files.createTempDirectory("poller-test-raw").toFile()
            val documentStorageRoot = Files.createTempDirectory("poller-test-docs").toFile()
            try {
                val member = createMember("poller-recompute-stale@example.org")
                val old = DbClock.nowLocalDateTime().toJavaLocalDateTimeShiftedMinutes(-600)
                val fx = createReadyForRecompute(member, documentStorageRoot, hostRawRoot, marker = old)
                val composer = FakeRecordingComposer()
                val poller =
                    buildPoller(
                        FakeLiveKitAdminClient(),
                        FakeLiveKitEgressClient(),
                        composer,
                        hostRawRoot,
                        documentStorageRoot,
                        composeTimeoutMinutes = 120,
                    )

                poller.tickAndSettle()

                composer.callCount shouldBe 0
                val row = recordingRow(fx.recordingId)
                row[ConferenceRecordingTable.recomputeStartedAt] shouldBe null
                row[ConferenceRecordingTable.lastRecomputeFailureCode] shouldBe ConferenceRecordingFailureCode.COMPOSE_FAILED
                versionsOf(fx.documentId).size shouldBe 1
            } finally {
                hostRawRoot.deleteRecursively()
                documentStorageRoot.deleteRecursively()
            }
        }

        test("RECOMPUTE: not enough free disk space right before the start fails the recompute without composing") {
            val hostRawRoot = Files.createTempDirectory("poller-test-raw").toFile()
            val documentStorageRoot = Files.createTempDirectory("poller-test-docs").toFile()
            try {
                val member = createMember("poller-recompute-disk@example.org")
                val fx = createReadyForRecompute(member, documentStorageRoot, hostRawRoot)
                val composer = FakeRecordingComposer()
                val poller =
                    buildPoller(
                        FakeLiveKitAdminClient(),
                        FakeLiveKitEgressClient(),
                        composer,
                        hostRawRoot,
                        documentStorageRoot,
                        diskSpace = { 1_000L },
                    )

                poller.tickAndSettle()

                composer.callCount shouldBe 0
                recordingRow(fx.recordingId)[ConferenceRecordingTable.lastRecomputeFailureCode] shouldBe
                    ConferenceRecordingFailureCode.COMPOSE_FAILED
                recordingRow(fx.recordingId)[ConferenceRecordingTable.recomputeStartedAt] shouldBe null
                versionsOf(fx.documentId).size shouldBe 1
            } finally {
                hostRawRoot.deleteRecursively()
                documentStorageRoot.deleteRecursively()
            }
        }

        test("WORKER: a long composition does not block the tick -- a RECORDING meeting still gets its egress started meanwhile") {
            val hostRawRoot = Files.createTempDirectory("poller-test-raw").toFile()
            val documentStorageRoot = Files.createTempDirectory("poller-test-docs").toFile()
            try {
                val member = createMember("poller-worker-nonblocking@example.org")
                val fx = createReadyForRecompute(member, documentStorageRoot, hostRawRoot)
                val (liveRoomId, liveRoomName) = createRoom(member)
                createRecording(liveRoomId, member, ConferenceRecordingStatus.RECORDING)
                val adminClient = FakeLiveKitAdminClient()
                adminClient.participantsByRoom[liveRoomName] =
                    listOf(
                        LiveKitParticipantInfo(
                            identity = member.toString(),
                            tracks = listOf(LiveKitTrackInfo(sid = "TR_late_joiner", type = "VIDEO", source = "CAMERA", muted = false)),
                        ),
                    )
                val egressClient = FakeLiveKitEgressClient()
                val gate = CompletableDeferred<Unit>()
                val entered = CompletableDeferred<Unit>()
                val composer =
                    object : RecordingComposer {
                        override suspend fun compose(
                            spec: RecordingComposeSpec,
                            outputFile: File,
                        ) {
                            entered.complete(Unit)
                            gate.await()
                            outputFile.writeBytes(ByteArray(10) { 1 })
                        }
                    }
                val poller = buildPoller(adminClient, egressClient, composer, hostRawRoot, documentStorageRoot)

                // the first tick starts the worker and returns at once, with the composition still hanging
                withTimeout(10_000) { poller.tick() }
                withTimeout(10_000) { entered.await() }
                // the egress of the live meeting was started in THIS tick already, although the composer has not finished
                egressClient.started.map { it.second } shouldContain "TR_late_joiner"
                // a second tick while the worker is busy neither blocks nor starts a second composition
                withTimeout(10_000) { poller.tick() }
                (recordingRow(fx.recordingId)[ConferenceRecordingTable.recomputeStartedAt] != null) shouldBe true

                gate.complete(Unit)
                withTimeout(10_000) { poller.awaitCompositionIdleForTest() }
                versionsOf(fx.documentId).size shouldBe 2
            } finally {
                hostRawRoot.deleteRecursively()
                documentStorageRoot.deleteRecursively()
            }
        }

        test("WORKER: composeOne with ranges from the prober: the spec carries the data ranges, media durations and the wall duration") {
            val hostRawRoot = Files.createTempDirectory("poller-test-raw").toFile()
            val documentStorageRoot = Files.createTempDirectory("poller-test-docs").toFile()
            try {
                val member = createMember("poller-ranges@example.org")
                val (roomId, _) = createRoom(member)
                val recordingId = createRecording(roomId, member, ConferenceRecordingStatus.PROCESSING)
                val rawDir = hostRawRoot.resolve(recordingId.toString()).apply { mkdirs() }
                val camFile = rawDir.resolve("a__CAMERA__TR_a.webm").apply { writeBytes(byteArrayOf(1)) }
                val micFile = rawDir.resolve("a__MICROPHONE__TR_m.ogg").apply { writeBytes(byteArrayOf(2)) }
                createTrack(
                    recordingId,
                    "EG_a-$recordingId",
                    "TR_a",
                    ConferenceRecordingTrackStatus.COMPLETE,
                    ConferenceRecordingTrackSource.CAMERA,
                    camFile.name,
                    1_000_000_000L,
                    60_000L,
                )
                createTrack(
                    recordingId,
                    "EG_m-$recordingId",
                    "TR_m",
                    ConferenceRecordingTrackStatus.COMPLETE,
                    ConferenceRecordingTrackSource.MICROPHONE,
                    micFile.name,
                    1_000_000_000L,
                    60_400L,
                )
                val prober =
                    object : RecordingMediaProber {
                        override suspend fun probeDurationSeconds(file: File): Double? = null

                        override suspend fun probePacketRanges(
                            file: File,
                            kind: StreamKind,
                            gapThresholdSeconds: Double,
                        ): PacketRanges? =
                            if (kind == StreamKind.VIDEO) {
                                PacketRanges(
                                    ranges =
                                        listOf(
                                            TimeRange(startSeconds = 0.0, endSeconds = 20.0),
                                            TimeRange(startSeconds = 30.0, endSeconds = 60.0),
                                        ),
                                    mediaDurationSeconds = 60.0,
                                )
                            } else {
                                PacketRanges(ranges = listOf(TimeRange(startSeconds = 0.0, endSeconds = 60.0)), mediaDurationSeconds = 59.5)
                            }
                    }
                var captured: RecordingComposeSpec? = null
                val composer =
                    FakeRecordingComposer { spec, outputFile ->
                        captured = spec
                        outputFile.writeBytes(byteArrayOf(1))
                    }
                buildPoller(
                    FakeLiveKitAdminClient(),
                    FakeLiveKitEgressClient(),
                    composer,
                    hostRawRoot,
                    documentStorageRoot,
                    prober = prober,
                ).tickAndSettle()

                val spec = captured ?: error("composer was not invoked")
                spec.videoInputs.single().ranges shouldBe
                    listOf(TimeRange(startSeconds = 0.0, endSeconds = 20.0), TimeRange(startSeconds = 30.0, endSeconds = 60.0))
                spec.videoInputs.single().durationSeconds shouldBe 60.0
                spec.audioInputs.single().ranges shouldBe listOf(TimeRange(startSeconds = 0.0, endSeconds = 60.0))
                spec.audioInputs.single().mediaDurationSeconds shouldBe 59.5
                // no ended_at stored in this fixture: the reported duration is the wall duration
                spec.audioInputs.single().wallDurationSeconds shouldBe (60.4 plusOrMinus 1e-9)
                recordingRow(recordingId)[ConferenceRecordingTable.documentId]?.let { createdDocumentIds += it }
            } finally {
                hostRawRoot.deleteRecursively()
                documentStorageRoot.deleteRecursively()
            }
        }

        // ── V1.9.110: Participant Egress mode ───────────────────────────────────

        fun trackRowsOf(recordingId: Uuid) =
            transaction {
                ConferenceRecordingTrackTable.selectAll().where { ConferenceRecordingTrackTable.recordingId eq recordingId }.toList()
            }

        fun person(
            sid: String,
            identity: String,
            vararg tracks: LiveKitTrackInfo,
        ) = LiveKitParticipantInfo(sid = sid, identity = identity, tracks = tracks.toList())

        fun cam(
            sid: String,
            muted: Boolean = false,
        ) = LiveKitTrackInfo(sid = sid, type = "VIDEO", source = "CAMERA", muted = muted)

        fun mic(
            sid: String,
            muted: Boolean = false,
        ) = LiveKitTrackInfo(sid = sid, type = "AUDIO", source = "MICROPHONE", muted = muted)

        test("PARTICIPANT mode (a): in TRACK mode StartParticipantEgress is never used, however many people are present") {
            val hostRawRoot = Files.createTempDirectory("poller-test-raw").toFile()
            val documentStorageRoot = Files.createTempDirectory("poller-test-docs").toFile()
            try {
                val member = createMember("poller-pe-trackmode@example.org")
                val (roomId, livekitRoomName) = createRoom(member)
                createRecording(roomId, member, ConferenceRecordingStatus.RECORDING)
                val adminClient = FakeLiveKitAdminClient()
                adminClient.participantsByRoom[livekitRoomName] = listOf(person("PA_1", "alice", cam("TR_c1"), mic("TR_m1")))
                val egressClient = FakeLiveKitEgressClient()
                buildPoller(adminClient, egressClient, FakeRecordingComposer(), hostRawRoot, documentStorageRoot).tickAndSettle()

                egressClient.participantFileStarts shouldHaveSize 0
                egressClient.started shouldHaveSize 2
            } finally {
                hostRawRoot.deleteRecursively()
                documentStorageRoot.deleteRecursively()
            }
        }

        test(
            "PARTICIPANT mode (b): one egress per person with a camera OR a microphone (a muted one counts), none for a person without tracks",
        ) {
            val hostRawRoot = Files.createTempDirectory("poller-test-raw").toFile()
            val documentStorageRoot = Files.createTempDirectory("poller-test-docs").toFile()
            try {
                val member = createMember("poller-pe-b@example.org")
                val (roomId, livekitRoomName) = createRoom(member)
                val recordingId =
                    createRecording(
                        roomId,
                        member,
                        ConferenceRecordingStatus.RECORDING,
                        egressMode = ConferenceRecordingEgressMode.PARTICIPANT,
                    )
                val adminClient = FakeLiveKitAdminClient()
                adminClient.participantsByRoom[livekitRoomName] =
                    listOf(
                        person("PA_alice", "alice", cam("TR_c1"), mic("TR_m1")),
                        person("PA_bob", "bob", mic("TR_m2", muted = true)),
                        person("PA_carol", "carol"),
                    )
                val egressClient = FakeLiveKitEgressClient()
                val poller =
                    buildPoller(
                        adminClient,
                        egressClient,
                        FakeRecordingComposer(),
                        hostRawRoot,
                        documentStorageRoot,
                        egressMode = ConferenceRecordingEgressMode.PARTICIPANT,
                    )

                poller.tickAndSettle()
                poller.tickAndSettle() // idempotent: nothing new on the second pass

                egressClient.started shouldHaveSize 0
                egressClient.participantFileStarts.map { it.second } shouldBe listOf("alice", "bob")
                egressClient.participantFileStarts.all { it.first == livekitRoomName } shouldBe true
                val rows = trackRowsOf(recordingId)
                rows shouldHaveSize 2
                rows.all { it[ConferenceRecordingTrackTable.egressKind] == ConferenceRecordingEgressMode.PARTICIPANT } shouldBe true
                rows.all { it[ConferenceRecordingTrackTable.trackSource] == ConferenceRecordingTrackSource.CAMERA } shouldBe true
                rows.map { it[ConferenceRecordingTrackTable.livekitTrackId] }.toSet() shouldBe setOf("PA_alice", "PA_bob")
                rows.map { it[ConferenceRecordingTrackTable.participantIdentity] }.toSet() shouldBe setOf("alice", "bob")
            } finally {
                hostRawRoot.deleteRecursively()
                documentStorageRoot.deleteRecursively()
            }
        }

        test(
            "PARTICIPANT mode (c): screen share and screen-share audio still run as Track Egress (egress_kind TRACK), a muted share is skipped",
        ) {
            val hostRawRoot = Files.createTempDirectory("poller-test-raw").toFile()
            val documentStorageRoot = Files.createTempDirectory("poller-test-docs").toFile()
            try {
                val member = createMember("poller-pe-c@example.org")
                val (roomId, livekitRoomName) = createRoom(member)
                val recordingId =
                    createRecording(
                        roomId,
                        member,
                        ConferenceRecordingStatus.RECORDING,
                        egressMode = ConferenceRecordingEgressMode.PARTICIPANT,
                    )
                val adminClient = FakeLiveKitAdminClient()
                adminClient.participantsByRoom[livekitRoomName] =
                    listOf(
                        person(
                            "PA_alice",
                            "alice",
                            cam("TR_c1"),
                            LiveKitTrackInfo(sid = "TR_share", type = "VIDEO", source = "SCREEN_SHARE", muted = false),
                            LiveKitTrackInfo(sid = "TR_shareaudio", type = "AUDIO", source = "SCREEN_SHARE_AUDIO", muted = false),
                            LiveKitTrackInfo(sid = "TR_share2", type = "VIDEO", source = "SCREEN_SHARE", muted = true),
                        ),
                    )
                val egressClient = FakeLiveKitEgressClient()
                buildPoller(
                    adminClient,
                    egressClient,
                    FakeRecordingComposer(),
                    hostRawRoot,
                    documentStorageRoot,
                    egressMode = ConferenceRecordingEgressMode.PARTICIPANT,
                ).tickAndSettle()

                egressClient.started.map { it.second } shouldBe listOf("TR_share", "TR_shareaudio")
                egressClient.participantFileStarts shouldHaveSize 1
                val rows = trackRowsOf(recordingId)
                rows shouldHaveSize 3
                val byTrackId = rows.associateBy { it[ConferenceRecordingTrackTable.livekitTrackId] }
                byTrackId.getValue("TR_share")[ConferenceRecordingTrackTable.egressKind] shouldBe ConferenceRecordingEgressMode.TRACK
                byTrackId.getValue("TR_share")[ConferenceRecordingTrackTable.trackSource] shouldBe
                    ConferenceRecordingTrackSource.SCREEN_SHARE
                byTrackId.getValue("TR_shareaudio")[ConferenceRecordingTrackTable.trackSource] shouldBe
                    ConferenceRecordingTrackSource.SCREEN_SHARE_AUDIO
                byTrackId.getValue("PA_alice")[ConferenceRecordingTrackTable.egressKind] shouldBe ConferenceRecordingEgressMode.PARTICIPANT
            } finally {
                hostRawRoot.deleteRecursively()
                documentStorageRoot.deleteRecursively()
            }
        }

        test("PARTICIPANT mode (d): a person who comes back with a NEW participant SID gets a second row and a second file") {
            val hostRawRoot = Files.createTempDirectory("poller-test-raw").toFile()
            val documentStorageRoot = Files.createTempDirectory("poller-test-docs").toFile()
            try {
                val member = createMember("poller-pe-d@example.org")
                val (roomId, livekitRoomName) = createRoom(member)
                val recordingId =
                    createRecording(
                        roomId,
                        member,
                        ConferenceRecordingStatus.RECORDING,
                        egressMode = ConferenceRecordingEgressMode.PARTICIPANT,
                    )
                val adminClient = FakeLiveKitAdminClient()
                val egressClient = FakeLiveKitEgressClient()
                val poller =
                    buildPoller(
                        adminClient,
                        egressClient,
                        FakeRecordingComposer(),
                        hostRawRoot,
                        documentStorageRoot,
                        egressMode = ConferenceRecordingEgressMode.PARTICIPANT,
                    )
                adminClient.participantsByRoom[livekitRoomName] = listOf(person("PA_first", "alice", cam("TR_c1")))
                poller.tickAndSettle()
                adminClient.participantsByRoom[livekitRoomName] = listOf(person("PA_second", "alice", cam("TR_c2")))
                poller.tickAndSettle()

                egressClient.participantFileStarts shouldHaveSize 2
                val rows = trackRowsOf(recordingId)
                rows.map { it[ConferenceRecordingTrackTable.livekitTrackId] }.toSet() shouldBe setOf("PA_first", "PA_second")
                rows.map { it[ConferenceRecordingTrackTable.egressId] }.toSet() shouldHaveSize 2
            } finally {
                hostRawRoot.deleteRecursively()
                documentStorageRoot.deleteRecursively()
            }
        }

        test(
            "PARTICIPANT mode (e): a person who leaves gets exactly ONE StopEgress, the row is refreshed, the next tick does not repeat it",
        ) {
            val hostRawRoot = Files.createTempDirectory("poller-test-raw").toFile()
            val documentStorageRoot = Files.createTempDirectory("poller-test-docs").toFile()
            try {
                val member = createMember("poller-pe-e@example.org")
                val (roomId, livekitRoomName) = createRoom(member)
                val recordingId =
                    createRecording(
                        roomId,
                        member,
                        ConferenceRecordingStatus.RECORDING,
                        egressMode = ConferenceRecordingEgressMode.PARTICIPANT,
                    )
                val adminClient = FakeLiveKitAdminClient()
                val egressClient = FakeLiveKitEgressClient()
                val poller =
                    buildPoller(
                        adminClient,
                        egressClient,
                        FakeRecordingComposer(),
                        hostRawRoot,
                        documentStorageRoot,
                        egressMode = ConferenceRecordingEgressMode.PARTICIPANT,
                    )
                adminClient.participantsByRoom[livekitRoomName] =
                    listOf(person("PA_alice", "alice", cam("TR_c1")), person("PA_bob", "bob", cam("TR_c2")))
                poller.tickAndSettle()
                val myEgressIds = trackRowsOf(recordingId).map { it[ConferenceRecordingTrackTable.egressId] }.toSet()
                val aliceEgressId =
                    trackRowsOf(recordingId)
                        .single { it[ConferenceRecordingTrackTable.livekitTrackId] == "PA_alice" }[ConferenceRecordingTrackTable.egressId]
                // LiveKit finished alice's egress on its own account by the time the poller looks.
                egressClient.egressInfoByEgressId[aliceEgressId] =
                    LiveKitEgressInfo(
                        egressId = aliceEgressId,
                        roomName = livekitRoomName,
                        status = "EGRESS_COMPLETE",
                        startedAtEpochNanos = "1000000000",
                        endedAtEpochNanos = "9000000000",
                        fileResults =
                            listOf(
                                LiveKitEgressFileInfo(
                                    filename = "/out/$recordingId/p-x.mp4",
                                    startedAtEpochNanos = "1000000000",
                                    duration = "8000000000",
                                    size = "123",
                                ),
                            ),
                    )

                adminClient.participantsByRoom[livekitRoomName] = listOf(person("PA_bob", "bob", cam("TR_c2")))
                poller.tickAndSettle()
                // (the shared test database may hold other specs' recordings: only this recording's egresses count)
                egressClient.stoppedEgressIds.filter { it in myEgressIds } shouldBe listOf(aliceEgressId)
                val alice = trackRowsOf(recordingId).single { it[ConferenceRecordingTrackTable.livekitTrackId] == "PA_alice" }
                alice[ConferenceRecordingTrackTable.status] shouldBe ConferenceRecordingTrackStatus.COMPLETE
                alice[ConferenceRecordingTrackTable.durationMs] shouldBe 8_000L
                alice[ConferenceRecordingTrackTable.fileName] shouldBe "/out/$recordingId/p-x.mp4"

                poller.tickAndSettle()
                poller.tickAndSettle()
                egressClient.stoppedEgressIds.filter { it in myEgressIds } shouldBe listOf(aliceEgressId)
                // bob is still there: never stopped, never restarted
                egressClient.participantFileStarts shouldHaveSize 2
            } finally {
                hostRawRoot.deleteRecursively()
                documentStorageRoot.deleteRecursively()
            }
        }

        test("PARTICIPANT mode (e2): a StopEgress that fails is tried again on the next tick (not remembered as done)") {
            val hostRawRoot = Files.createTempDirectory("poller-test-raw").toFile()
            val documentStorageRoot = Files.createTempDirectory("poller-test-docs").toFile()
            try {
                val member = createMember("poller-pe-e2@example.org")
                val (roomId, livekitRoomName) = createRoom(member)
                createRecording(roomId, member, ConferenceRecordingStatus.RECORDING, egressMode = ConferenceRecordingEgressMode.PARTICIPANT)
                val adminClient = FakeLiveKitAdminClient()
                var failStop = true
                val stopCallsAll = mutableListOf<String>()
                val inner = FakeLiveKitEgressClient()
                val egressClient =
                    object : LiveKitEgressClient by inner {
                        override suspend fun stopEgress(
                            roomName: String,
                            egressId: String,
                        ): LiveKitEgressInfo {
                            stopCallsAll += egressId
                            if (failStop) throw LiveKitAdminException(message = "StopEgress failed (simulated)")
                            return inner.stopEgress(roomName = roomName, egressId = egressId)
                        }
                    }
                val poller =
                    buildPoller(
                        adminClient,
                        egressClient,
                        FakeRecordingComposer(),
                        hostRawRoot,
                        documentStorageRoot,
                        egressMode = ConferenceRecordingEgressMode.PARTICIPANT,
                    )
                adminClient.participantsByRoom[livekitRoomName] = listOf(person("PA_alice", "alice", cam("TR_c1")))
                poller.tickAndSettle()
                val aliceEgressId = inner.egressInfoByEgressId.keys.single()
                val stopCalls =
                    object {
                        val size get() = stopCallsAll.count { it == aliceEgressId }
                    }
                adminClient.participantsByRoom[livekitRoomName] = emptyList()
                poller.tickAndSettle()
                stopCalls.size shouldBe 1
                failStop = false
                poller.tickAndSettle()
                stopCalls.size shouldBe 2
                poller.tickAndSettle()
                stopCalls.size shouldBe 2
            } finally {
                hostRawRoot.deleteRecursively()
                documentStorageRoot.deleteRecursively()
            }
        }

        test("PARTICIPANT mode (f): person rows count against maxTracks") {
            val hostRawRoot = Files.createTempDirectory("poller-test-raw").toFile()
            val documentStorageRoot = Files.createTempDirectory("poller-test-docs").toFile()
            try {
                val member = createMember("poller-pe-f@example.org")
                val (roomId, livekitRoomName) = createRoom(member)
                createRecording(roomId, member, ConferenceRecordingStatus.RECORDING, egressMode = ConferenceRecordingEgressMode.PARTICIPANT)
                val adminClient = FakeLiveKitAdminClient()
                adminClient.participantsByRoom[livekitRoomName] =
                    listOf(
                        person("PA_1", "alice", cam("TR_c1")),
                        person("PA_2", "bob", cam("TR_c2")),
                        person("PA_3", "carol", cam("TR_c3")),
                    )
                val egressClient = FakeLiveKitEgressClient()
                val poller =
                    buildPoller(
                        adminClient,
                        egressClient,
                        FakeRecordingComposer(),
                        hostRawRoot,
                        documentStorageRoot,
                        maxTracks = 2,
                        egressMode = ConferenceRecordingEgressMode.PARTICIPANT,
                    )
                poller.tickAndSettle()
                poller.tickAndSettle()

                egressClient.participantFileStarts.map { it.second } shouldBe listOf("alice", "bob")
            } finally {
                hostRawRoot.deleteRecursively()
                documentStorageRoot.deleteRecursively()
            }
        }

        test("PARTICIPANT mode (g): the path holds the row UUID only -- never the identity; an unusable identity is not recorded at all") {
            val hostRawRoot = Files.createTempDirectory("poller-test-raw").toFile()
            val documentStorageRoot = Files.createTempDirectory("poller-test-docs").toFile()
            try {
                val member = createMember("poller-pe-g@example.org")
                val (roomId, livekitRoomName) = createRoom(member)
                val recordingId =
                    createRecording(
                        roomId,
                        member,
                        ConferenceRecordingStatus.RECORDING,
                        egressMode = ConferenceRecordingEgressMode.PARTICIPANT,
                    )
                val adminClient = FakeLiveKitAdminClient()
                adminClient.participantsByRoom[livekitRoomName] =
                    listOf(
                        person("PA_evil", "../x", cam("TR_c1"), LiveKitTrackInfo(sid = "TR_s1", source = "SCREEN_SHARE")),
                        person("PA_long", "y".repeat(65), mic("TR_m2")),
                        person("PA_ok", "alice", mic("TR_m3")),
                    )
                val egressClient = FakeLiveKitEgressClient()
                buildPoller(
                    adminClient,
                    egressClient,
                    FakeRecordingComposer(),
                    hostRawRoot,
                    documentStorageRoot,
                    egressMode = ConferenceRecordingEgressMode.PARTICIPANT,
                ).tickAndSettle()

                egressClient.participantFileStarts shouldHaveSize 1
                egressClient.started shouldHaveSize 0
                val rows = trackRowsOf(recordingId)
                rows shouldHaveSize 1
                val rowId = rows.single()[ConferenceRecordingTrackTable.id]
                egressClient.participantFileStarts.single().third shouldBe "/out/$recordingId/p-$rowId.mp4"
            } finally {
                hostRawRoot.deleteRecursively()
                documentStorageRoot.deleteRecursively()
            }
        }

        test(
            "PARTICIPANT mode (h): the mode is per recording -- a running PARTICIPANT recording stays PARTICIPANT after the configuration changed to TRACK",
        ) {
            val hostRawRoot = Files.createTempDirectory("poller-test-raw").toFile()
            val documentStorageRoot = Files.createTempDirectory("poller-test-docs").toFile()
            try {
                val member = createMember("poller-pe-h@example.org")
                val (roomId, livekitRoomName) = createRoom(member)
                val recordingId =
                    createRecording(
                        roomId,
                        member,
                        ConferenceRecordingStatus.RECORDING,
                        egressMode = ConferenceRecordingEgressMode.PARTICIPANT,
                    )
                val adminClient = FakeLiveKitAdminClient()
                adminClient.participantsByRoom[livekitRoomName] = listOf(person("PA_alice", "alice", cam("TR_c1"), mic("TR_m1")))
                val egressClient = FakeLiveKitEgressClient()
                // The poller of this "restarted" server is configured for TRACK ...
                buildPoller(
                    adminClient,
                    egressClient,
                    FakeRecordingComposer(),
                    hostRawRoot,
                    documentStorageRoot,
                    egressMode = ConferenceRecordingEgressMode.TRACK,
                ).tickAndSettle()

                // ... but this recording keeps the mode it started with: one person egress, no per-track egress (no doubled audio).
                egressClient.participantFileStarts shouldHaveSize 1
                egressClient.started shouldHaveSize 0
                trackRowsOf(recordingId).single()[ConferenceRecordingTrackTable.egressKind] shouldBe
                    ConferenceRecordingEgressMode.PARTICIPANT
            } finally {
                hostRawRoot.deleteRecursively()
                documentStorageRoot.deleteRecursively()
            }
        }

        test("PARTICIPANT mode (k): a refused start is logged and retried next tick -- no fallback to Track Egress, no row") {
            val hostRawRoot = Files.createTempDirectory("poller-test-raw").toFile()
            val documentStorageRoot = Files.createTempDirectory("poller-test-docs").toFile()
            try {
                val member = createMember("poller-pe-k@example.org")
                val (roomId, livekitRoomName) = createRoom(member)
                val recordingId =
                    createRecording(
                        roomId,
                        member,
                        ConferenceRecordingStatus.RECORDING,
                        egressMode = ConferenceRecordingEgressMode.PARTICIPANT,
                    )
                val adminClient = FakeLiveKitAdminClient()
                adminClient.participantsByRoom[livekitRoomName] = listOf(person("PA_alice", "alice", cam("TR_c1"), mic("TR_m1")))
                val egressClient = FakeLiveKitEgressClient()
                egressClient.failParticipantFileStart = true
                val poller =
                    buildPoller(
                        adminClient,
                        egressClient,
                        FakeRecordingComposer(),
                        hostRawRoot,
                        documentStorageRoot,
                        egressMode = ConferenceRecordingEgressMode.PARTICIPANT,
                    )

                poller.tickAndSettle()
                poller.tickAndSettle()
                egressClient.started shouldHaveSize 0
                egressClient.participantFileStarts shouldHaveSize 0
                trackRowsOf(recordingId) shouldHaveSize 0

                egressClient.failParticipantFileStart = false
                poller.tickAndSettle()
                egressClient.participantFileStarts shouldHaveSize 1
                egressClient.started shouldHaveSize 0
                trackRowsOf(recordingId) shouldHaveSize 1
            } finally {
                hostRawRoot.deleteRecursively()
                documentStorageRoot.deleteRecursively()
            }
        }

        test(
            "PARTICIPANT mode (i)+(j): composition of person files plus a screen share -- per-stream offsets, no wall duration, one file feeds a video AND an audio input",
        ) {
            val hostRawRoot = Files.createTempDirectory("poller-test-raw").toFile()
            val documentStorageRoot = Files.createTempDirectory("poller-test-docs").toFile()
            try {
                val member = createMember("poller-pe-compose@example.org")
                val (roomId, _) = createRoom(member)
                val recordingId =
                    createRecording(
                        roomId,
                        member,
                        ConferenceRecordingStatus.PROCESSING,
                        egressMode = ConferenceRecordingEgressMode.PARTICIPANT,
                    )
                val rawDir = hostRawRoot.resolve(recordingId.toString()).apply { mkdirs() }
                val aliceFile = rawDir.resolve("p-alice.mp4").apply { writeBytes(byteArrayOf(1)) }
                val bobFile = rawDir.resolve("p-bob.mp4").apply { writeBytes(byteArrayOf(1)) }
                val shareFile = rawDir.resolve("alice__SCREEN_SHARE__TR_s.mp4").apply { writeBytes(byteArrayOf(1)) }
                val t0 = 1_000_000_000_000L
                val aliceRow =
                    createTrack(
                        recordingId,
                        "EG_a-$recordingId",
                        "PA_alice",
                        ConferenceRecordingTrackStatus.COMPLETE,
                        ConferenceRecordingTrackSource.CAMERA,
                        aliceFile.name,
                        t0,
                        60_000L,
                        ConferenceRecordingEgressMode.PARTICIPANT,
                    )
                createTrack(
                    recordingId,
                    "EG_b-$recordingId",
                    "PA_bob",
                    ConferenceRecordingTrackStatus.COMPLETE,
                    ConferenceRecordingTrackSource.CAMERA,
                    bobFile.name,
                    t0 + 10_000_000_000L,
                    40_000L,
                    ConferenceRecordingEgressMode.PARTICIPANT,
                )
                createTrack(
                    recordingId,
                    "EG_s-$recordingId",
                    "TR_s",
                    ConferenceRecordingTrackStatus.COMPLETE,
                    ConferenceRecordingTrackSource.SCREEN_SHARE,
                    shareFile.name,
                    t0 + 20_000_000_000L,
                    15_000L,
                )
                val prober =
                    object : RecordingMediaProber {
                        override suspend fun probeDurationSeconds(file: File): Double? = null

                        override suspend fun probeStreamStartTimes(file: File): StreamStartTimes? =
                            if (file.name == "p-alice.mp4") StreamStartTimes(videoSeconds = 0.40, audioSeconds = 0.0) else null

                        override suspend fun probePacketRanges(
                            file: File,
                            kind: StreamKind,
                            gapThresholdSeconds: Double,
                        ): PacketRanges? =
                            if (kind == StreamKind.VIDEO) {
                                PacketRanges(ranges = listOf(TimeRange(startSeconds = 0.0, endSeconds = 60.0)), mediaDurationSeconds = 60.0)
                            } else {
                                PacketRanges(ranges = listOf(TimeRange(startSeconds = 0.0, endSeconds = 60.4)), mediaDurationSeconds = 60.4)
                            }
                    }
                val blankDetector =
                    object : RecordingBlankVideoDetector {
                        val seen = mutableListOf<Pair<String, Double>>()

                        override suspend fun detectBlankRanges(
                            file: File,
                            videoStreamStartSeconds: Double,
                            minSeconds: Double,
                        ): List<TimeRange>? {
                            seen += file.name to videoStreamStartSeconds
                            return if (file.name ==
                                "p-alice.mp4"
                            ) {
                                listOf(TimeRange(startSeconds = 20.0, endSeconds = 35.0))
                            } else {
                                emptyList()
                            }
                        }
                    }
                var captured: RecordingComposeSpec? = null
                val composer =
                    FakeRecordingComposer { spec, outputFile ->
                        captured = spec
                        outputFile.writeBytes(byteArrayOf(1))
                    }
                buildPoller(
                    FakeLiveKitAdminClient(),
                    FakeLiveKitEgressClient(),
                    composer,
                    hostRawRoot,
                    documentStorageRoot,
                    prober = prober,
                    blankDetector = blankDetector,
                ).tickAndSettle()

                val spec = captured ?: error("composer was not invoked")
                val aliceVideo = spec.videoInputs.single { it.file.name == "p-alice.mp4" }
                val aliceAudio = spec.audioInputs.single { it.file.name == "p-alice.mp4" }
                aliceVideo.trackId shouldBe aliceRow.toString()
                aliceVideo.offsetSeconds shouldBe (0.40 plusOrMinus 1e-9) // file offset 0 (earliest) + 0.40 start difference
                aliceAudio.offsetSeconds shouldBe (0.0 plusOrMinus 1e-9)
                aliceVideo.ranges shouldBe
                    listOf(TimeRange(startSeconds = 0.0, endSeconds = 20.0), TimeRange(startSeconds = 35.0, endSeconds = 60.0))
                aliceAudio.wallDurationSeconds shouldBe null
                aliceAudio.ranges shouldBe listOf(TimeRange(startSeconds = 0.0, endSeconds = 60.4))
                val bobVideo = spec.videoInputs.single { it.file.name == "p-bob.mp4" }
                val bobAudio = spec.audioInputs.single { it.file.name == "p-bob.mp4" }
                bobVideo.offsetSeconds shouldBe (10.0 plusOrMinus 1e-9) // unmeasured start times: both streams start with the file
                bobAudio.offsetSeconds shouldBe (10.0 plusOrMinus 1e-9)
                bobVideo.ranges shouldBe listOf(TimeRange(startSeconds = 0.0, endSeconds = 60.0))
                // the screen share stays a plain video input (no audio input of its own in this fixture)
                val share = spec.videoInputs.single { it.isScreenShare }
                share.file.name shouldBe shareFile.name
                share.offsetSeconds shouldBe (20.0 plusOrMinus 1e-9)
                spec.videoInputs shouldHaveSize 3
                spec.audioInputs shouldHaveSize 2
                // the blank scan ran for the person files only, with the measured start of the video stream (0.40 / not measured -> 0)
                blankDetector.seen.toSet() shouldBe setOf("p-alice.mp4" to 0.40, "p-bob.mp4" to 0.0)
                // reported duration: the longest file offset + duration governs the output length (bob: 10 + 40, alice: 0 + 60, share: 20 + 15)
                spec.outputDurationSeconds shouldBe (60.0 plusOrMinus 1e-9)
                recordingRow(recordingId)[ConferenceRecordingTable.documentId]?.let { createdDocumentIds += it }
            } finally {
                hostRawRoot.deleteRecursively()
                documentStorageRoot.deleteRecursively()
            }
        }

        test(
            "PARTICIPANT mode: STOPPING -> PROCESSING works for person rows (stops them, refreshes from ListEgress, then every row is terminal)",
        ) {
            val hostRawRoot = Files.createTempDirectory("poller-test-raw").toFile()
            val documentStorageRoot = Files.createTempDirectory("poller-test-docs").toFile()
            try {
                val member = createMember("poller-pe-stopping@example.org")
                val (roomId, livekitRoomName) = createRoom(member)
                val recordingId =
                    createRecording(
                        roomId,
                        member,
                        ConferenceRecordingStatus.STOPPING,
                        stoppedAt = DbClock.nowLocalDateTime(),
                        egressMode = ConferenceRecordingEgressMode.PARTICIPANT,
                    )
                val egressId = "EG_p-$recordingId"
                createTrack(
                    recordingId,
                    egressId,
                    "PA_alice",
                    ConferenceRecordingTrackStatus.ACTIVE,
                    ConferenceRecordingTrackSource.CAMERA,
                    egressKind = ConferenceRecordingEgressMode.PARTICIPANT,
                )
                val egressClient = FakeLiveKitEgressClient()
                egressClient.egressInfoByEgressId[egressId] =
                    LiveKitEgressInfo(
                        egressId = egressId,
                        roomName = livekitRoomName,
                        status = "EGRESS_COMPLETE",
                        startedAtEpochNanos = "5",
                        fileResults =
                            listOf(
                                LiveKitEgressFileInfo(
                                    filename = "/out/$recordingId/p-a.mp4",
                                    startedAtEpochNanos = "5",
                                    duration = "2000000000",
                                ),
                            ),
                    )
                buildPoller(
                    FakeLiveKitAdminClient(),
                    egressClient,
                    FakeRecordingComposer(),
                    hostRawRoot,
                    documentStorageRoot,
                    egressMode = ConferenceRecordingEgressMode.PARTICIPANT,
                ).tickAndSettle()

                egressClient.stoppedEgressIds.filter { it == egressId } shouldBe listOf(egressId)
                recordingRow(recordingId)[ConferenceRecordingTable.status] shouldBe ConferenceRecordingStatus.PROCESSING
            } finally {
                hostRawRoot.deleteRecursively()
                documentStorageRoot.deleteRecursively()
            }
        }

        test(
            "PARTICIPANT mode: a person file whose picture is black throughout leaves no video at all -> NoVideo -> COMPOSE_FAILED, never an all-black 'visible' tile",
        ) {
            val hostRawRoot = Files.createTempDirectory("poller-test-raw").toFile()
            val documentStorageRoot = Files.createTempDirectory("poller-test-docs").toFile()
            try {
                val member = createMember("poller-pe-allblack@example.org")
                val (roomId, _) = createRoom(member)
                val recordingId =
                    createRecording(
                        roomId,
                        member,
                        ConferenceRecordingStatus.PROCESSING,
                        composeAttempts = MAX_COMPOSE_ATTEMPTS - 1,
                        egressMode = ConferenceRecordingEgressMode.PARTICIPANT,
                    )
                val rawDir = hostRawRoot.resolve(recordingId.toString()).apply { mkdirs() }
                val file = rawDir.resolve("p-alice.mp4").apply { writeBytes(byteArrayOf(1)) }
                createTrack(
                    recordingId,
                    "EG_a-$recordingId",
                    "PA_alice",
                    ConferenceRecordingTrackStatus.COMPLETE,
                    ConferenceRecordingTrackSource.CAMERA,
                    file.name,
                    1_000_000_000L,
                    30_000L,
                    ConferenceRecordingEgressMode.PARTICIPANT,
                )
                val prober =
                    object : RecordingMediaProber {
                        override suspend fun probeDurationSeconds(file: File): Double? = null

                        override suspend fun probePacketRanges(
                            file: File,
                            kind: StreamKind,
                            gapThresholdSeconds: Double,
                        ): PacketRanges? =
                            PacketRanges(ranges = listOf(TimeRange(startSeconds = 0.0, endSeconds = 30.0)), mediaDurationSeconds = 30.0)
                    }
                val blankDetector =
                    object : RecordingBlankVideoDetector {
                        override suspend fun detectBlankRanges(
                            file: File,
                            videoStreamStartSeconds: Double,
                            minSeconds: Double,
                        ): List<TimeRange> = listOf(TimeRange(startSeconds = 0.0, endSeconds = FfmpegBlankVideoDetector.OPEN_END_SECONDS))
                    }
                val composer = FakeRecordingComposer()
                buildPoller(
                    FakeLiveKitAdminClient(),
                    FakeLiveKitEgressClient(),
                    composer,
                    hostRawRoot,
                    documentStorageRoot,
                    prober = prober,
                    blankDetector = blankDetector,
                ).tickAndSettle()

                composer.callCount shouldBe 0
                val row = recordingRow(recordingId)
                row[ConferenceRecordingTable.status] shouldBe ConferenceRecordingStatus.FAILED
                row[ConferenceRecordingTable.failureCode] shouldBe ConferenceRecordingFailureCode.COMPOSE_FAILED
            } finally {
                hostRawRoot.deleteRecursively()
                documentStorageRoot.deleteRecursively()
            }
        }

        // ── V1.9.115: speaker timeline ──────────────────────────────────────────

        /** Two people (each with a camera and a microphone file, one identity per person), PROCESSING, the given view. */
        fun seedTwoPeople(
            hostRawRoot: File,
            view: ConferenceBroadcastView,
            composeAttempts: Int = 0,
        ): Triple<Uuid, Pair<Uuid, Uuid>, Pair<File, File>> {
            val member = createMember("poller-speaker-${Uuid.random()}@example.org")
            val (roomId, _) = createRoom(member)
            val recordingId =
                createRecording(
                    roomId,
                    member,
                    ConferenceRecordingStatus.PROCESSING,
                    composeAttempts = composeAttempts,
                    broadcastView = view,
                )
            val rawDir = hostRawRoot.resolve(recordingId.toString()).apply { mkdirs() }
            val camA = rawDir.resolve("a__CAMERA__TR_ca.webm").apply { writeBytes(byteArrayOf(1)) }
            val micA = rawDir.resolve("a__MICROPHONE__TR_ma.ogg").apply { writeBytes(byteArrayOf(2)) }
            val camB = rawDir.resolve("b__CAMERA__TR_cb.webm").apply { writeBytes(byteArrayOf(3)) }
            val micB = rawDir.resolve("b__MICROPHONE__TR_mb.ogg").apply { writeBytes(byteArrayOf(4)) }
            val t0 = 1_000_000_000L

            fun track(
                egress: String,
                lk: String,
                source: ConferenceRecordingTrackSource,
                file: File,
                who: String,
            ) = createTrack(
                recordingId,
                "$egress-$recordingId",
                lk,
                ConferenceRecordingTrackStatus.COMPLETE,
                source,
                file.name,
                t0,
                30_000L,
                identity = who,
            )
            val camAId = track("EG_ca", "TR_ca", ConferenceRecordingTrackSource.CAMERA, camA, "person-a")
            track("EG_ma", "TR_ma", ConferenceRecordingTrackSource.MICROPHONE, micA, "person-a")
            val camBId = track("EG_cb", "TR_cb", ConferenceRecordingTrackSource.CAMERA, camB, "person-b")
            track("EG_mb", "TR_mb", ConferenceRecordingTrackSource.MICROPHONE, micB, "person-b")
            return Triple(recordingId, camAId to camBId, micA to micB)
        }

        /** Levels in a quiet room (-70 dB) with a loud stretch (-20 dB) from [from] to [to] seconds. */
        fun levelsWithSpeech(
            from: Double,
            to: Double,
        ) = AudioLevelSeries(
            points =
                (0 until 300).map { i ->
                    AudioLevelPoint(
                        timeSeconds = i * 0.1,
                        levelDb =
                            if (i * 0.1 >= from &&
                                i * 0.1 < to
                            ) {
                                -20.0
                            } else {
                                -70.0
                            },
                    )
                },
            windowSeconds = 0.1,
        )

        class CountingAnalyzer(
            private val byFile: Map<String, AudioLevelSeries?>,
        ) : SpeechActivityAnalyzer {
            var calls = 0

            override suspend fun levels(
                file: File,
                windowMs: Int,
            ): AudioLevelSeries? {
                calls++
                return byFile[file.name]
            }
        }

        test(
            "SPEAKER: the microphones are analysed once, the spec carries the speaker layout with opaque person keys, and the timeline is stored",
        ) {
            val hostRawRoot = Files.createTempDirectory("poller-test-raw").toFile()
            val documentStorageRoot = Files.createTempDirectory("poller-test-docs").toFile()
            try {
                val (recordingId, cams, mics) = seedTwoPeople(hostRawRoot, ConferenceBroadcastView.SPEAKER)
                val analyzer =
                    CountingAnalyzer(
                        mapOf(
                            mics.first.name to levelsWithSpeech(2.0, 10.0),
                            mics.second.name to levelsWithSpeech(14.0, 25.0),
                        ),
                    )
                var captured: RecordingComposeSpec? = null
                val composer =
                    FakeRecordingComposer { spec, out ->
                        captured = spec
                        out.writeBytes(byteArrayOf(1))
                    }
                buildPoller(
                    FakeLiveKitAdminClient(),
                    FakeLiveKitEgressClient(),
                    composer,
                    hostRawRoot,
                    documentStorageRoot,
                    keepRaw = true,
                    speechAnalyzer = analyzer,
                ).tickAndSettle()

                val spec = captured ?: error("composer was not invoked")
                analyzer.calls shouldBe 2
                spec.layout shouldBe RecordingLayout.SPEAKER
                spec.videoInputs.map { it.personKey }.toSet() shouldBe setOf(cams.first.toString(), cams.second.toString())
                spec.speakerSpans.map { it.personKey } shouldBe listOf(cams.first.toString(), cams.second.toString())
                // no identity anywhere in what the composer is handed
                spec.speakerSpans.forEach { (it.personKey.contains("person-")) shouldBe false }
                // stored for a later recompute
                val stored = RecordingSpeakerTimelineStore.load(recordingId) ?: error("not stored")
                stored.map { it.trackId } shouldBe listOf(cams.first, cams.second)
                recordingRow(recordingId)[ConferenceRecordingTable.speakerTimelineComputed] shouldBe true
                recordingRow(recordingId)[ConferenceRecordingTable.status] shouldBe ConferenceRecordingStatus.READY
                recordingRow(recordingId)[ConferenceRecordingTable.documentId]?.let { createdDocumentIds += it }
            } finally {
                hostRawRoot.deleteRecursively()
                documentStorageRoot.deleteRecursively()
            }
        }

        test("SPEAKER: a stored timeline is reused -- the analyzer is not called again (recompute)") {
            val hostRawRoot = Files.createTempDirectory("poller-test-raw").toFile()
            val documentStorageRoot = Files.createTempDirectory("poller-test-docs").toFile()
            try {
                val (recordingId, cams, _) = seedTwoPeople(hostRawRoot, ConferenceBroadcastView.SPEAKER)
                RecordingSpeakerTimelineStore.replace(
                    recordingId = recordingId,
                    spans = listOf(StoredSpeakerSpan(trackId = cams.second, startMs = 1_000, endMs = 9_000)),
                )
                val analyzer = CountingAnalyzer(emptyMap())
                var captured: RecordingComposeSpec? = null
                buildPoller(
                    FakeLiveKitAdminClient(),
                    FakeLiveKitEgressClient(),
                    FakeRecordingComposer { spec, out ->
                        captured = spec
                        out.writeBytes(byteArrayOf(1))
                    },
                    hostRawRoot,
                    documentStorageRoot,
                    keepRaw = true,
                    speechAnalyzer = analyzer,
                ).tickAndSettle()

                analyzer.calls shouldBe 0
                captured!!.speakerSpans shouldBe
                    listOf(RecordingSpeakerSpan(personKey = cams.second.toString(), startSeconds = 1.0, endSeconds = 9.0))
                recordingRow(recordingId)[ConferenceRecordingTable.documentId]?.let { createdDocumentIds += it }
            } finally {
                hostRawRoot.deleteRecursively()
                documentStorageRoot.deleteRecursively()
            }
        }

        test("SPEAKER: an analysis that fails falls back to the grid -- the recording is READY, not FAILED") {
            val hostRawRoot = Files.createTempDirectory("poller-test-raw").toFile()
            val documentStorageRoot = Files.createTempDirectory("poller-test-docs").toFile()
            try {
                val (recordingId, _, _) = seedTwoPeople(hostRawRoot, ConferenceBroadcastView.SPEAKER)
                var captured: RecordingComposeSpec? = null
                buildPoller(
                    FakeLiveKitAdminClient(),
                    FakeLiveKitEgressClient(),
                    FakeRecordingComposer { spec, out ->
                        captured = spec
                        out.writeBytes(byteArrayOf(1))
                    },
                    hostRawRoot,
                    documentStorageRoot,
                    keepRaw = true,
                    speechAnalyzer = CountingAnalyzer(emptyMap()), // every file: null = measurement failed
                ).tickAndSettle()

                captured!!.layout shouldBe RecordingLayout.GRID
                captured!!.speakerSpans shouldBe emptyList()
                recordingRow(recordingId)[ConferenceRecordingTable.status] shouldBe ConferenceRecordingStatus.READY
                recordingRow(recordingId)[ConferenceRecordingTable.speakerTimelineComputed] shouldBe false
                recordingRow(recordingId)[ConferenceRecordingTable.documentId]?.let { createdDocumentIds += it }
            } finally {
                hostRawRoot.deleteRecursively()
                documentStorageRoot.deleteRecursively()
            }
        }

        test("RASTER: the analyzer is never called and the spec is the plain grid") {
            val hostRawRoot = Files.createTempDirectory("poller-test-raw").toFile()
            val documentStorageRoot = Files.createTempDirectory("poller-test-docs").toFile()
            try {
                val (recordingId, _, _) = seedTwoPeople(hostRawRoot, ConferenceBroadcastView.RASTER)
                val analyzer = CountingAnalyzer(emptyMap())
                var captured: RecordingComposeSpec? = null
                buildPoller(
                    FakeLiveKitAdminClient(),
                    FakeLiveKitEgressClient(),
                    FakeRecordingComposer { spec, out ->
                        captured = spec
                        out.writeBytes(byteArrayOf(1))
                    },
                    hostRawRoot,
                    documentStorageRoot,
                    speechAnalyzer = analyzer,
                ).tickAndSettle()

                analyzer.calls shouldBe 0
                captured!!.layout shouldBe RecordingLayout.GRID
                captured!!.videoInputs.forEach { it.personKey shouldBe null }
                recordingRow(recordingId)[ConferenceRecordingTable.documentId]?.let { createdDocumentIds += it }
            } finally {
                hostRawRoot.deleteRecursively()
                documentStorageRoot.deleteRecursively()
            }
        }

        test(
            "PRIVACY: the timeline is deleted with the raw files (keepRaw=false), kept with keepRaw=true and kept when the composition fails",
        ) {
            fun spansOf(recordingId: Uuid) =
                transaction {
                    ConferenceRecordingSpeakerSpanTable
                        .selectAll()
                        .where { ConferenceRecordingSpeakerSpanTable.recordingId eq recordingId }
                        .count()
                }

            val hostRawRoot = Files.createTempDirectory("poller-test-raw").toFile()
            val documentStorageRoot = Files.createTempDirectory("poller-test-docs").toFile()
            try {
                fun analyzer(mics: Pair<File, File>) =
                    CountingAnalyzer(
                        mapOf(mics.first.name to levelsWithSpeech(2.0, 10.0), mics.second.name to levelsWithSpeech(14.0, 25.0)),
                    )

                // keepRaw = false: success deletes the raw files AND the timeline
                val (gone, _, goneMics) = seedTwoPeople(hostRawRoot, ConferenceBroadcastView.SPEAKER)
                buildPoller(
                    FakeLiveKitAdminClient(),
                    FakeLiveKitEgressClient(),
                    FakeRecordingComposer(),
                    hostRawRoot,
                    documentStorageRoot,
                    keepRaw = false,
                    speechAnalyzer = analyzer(goneMics),
                ).tickAndSettle()
                recordingRow(gone)[ConferenceRecordingTable.status] shouldBe ConferenceRecordingStatus.READY
                spansOf(gone) shouldBe 0L
                recordingRow(gone)[ConferenceRecordingTable.speakerTimelineComputed] shouldBe false
                recordingRow(gone)[ConferenceRecordingTable.documentId]?.let { createdDocumentIds += it }

                // keepRaw = true: raw files stay, so does the timeline
                val (kept, _, keptMics) = seedTwoPeople(hostRawRoot, ConferenceBroadcastView.SPEAKER)
                buildPoller(
                    FakeLiveKitAdminClient(),
                    FakeLiveKitEgressClient(),
                    FakeRecordingComposer(),
                    hostRawRoot,
                    documentStorageRoot,
                    keepRaw = true,
                    speechAnalyzer = analyzer(keptMics),
                ).tickAndSettle()
                (spansOf(kept) > 0L) shouldBe true
                recordingRow(kept)[ConferenceRecordingTable.speakerTimelineComputed] shouldBe true
                recordingRow(kept)[ConferenceRecordingTable.documentId]?.let { createdDocumentIds += it }

                // a failed composition (final attempt) retains the raw files regardless of keepRaw, and with them the timeline
                val (failed, _, failedMics) =
                    seedTwoPeople(hostRawRoot, ConferenceBroadcastView.SPEAKER, composeAttempts = MAX_COMPOSE_ATTEMPTS - 1)
                buildPoller(
                    FakeLiveKitAdminClient(),
                    FakeLiveKitEgressClient(),
                    FakeRecordingComposer { _, _ -> throw RecordingComposeException(message = "boom") },
                    hostRawRoot,
                    documentStorageRoot,
                    keepRaw = false,
                    speechAnalyzer = analyzer(failedMics),
                ).tickAndSettle()
                recordingRow(failed)[ConferenceRecordingTable.status] shouldBe ConferenceRecordingStatus.FAILED
                (spansOf(failed) > 0L) shouldBe true
            } finally {
                hostRawRoot.deleteRecursively()
                documentStorageRoot.deleteRecursively()
            }
        }
    })

/** Test-only helper -- shifts a [LocalDateTime] by [minutes] using kotlinx-datetime's own Instant arithmetic, same conversion RecordingPoller itself uses. */
private fun LocalDateTime.toJavaLocalDateTimeShiftedMinutes(minutes: Long): LocalDateTime {
    val zone = TimeZone.UTC
    return this
        .toInstant(zone)
        .plus(minutes.minutes)
        .toLocalDateTime(zone)
}
