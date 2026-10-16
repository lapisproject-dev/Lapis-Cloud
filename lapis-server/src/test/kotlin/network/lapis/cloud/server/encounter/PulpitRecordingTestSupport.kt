package network.lapis.cloud.server.encounter

import io.ktor.client.HttpClient
import io.ktor.server.application.ApplicationCall
import network.lapis.cloud.server.db.generated.ConferenceRoomTable
import network.lapis.cloud.server.federation.FederationInboxRateLimiter
import network.lapis.cloud.server.rpc.PulpitRecordingService
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import kotlin.time.Duration.Companion.minutes
import kotlin.uuid.Uuid

/**
 * V1.9.143 -- a [PulpitRecordingControl] that stops nothing and only writes down what it was asked to do. Services that merely have to be able to
 * stop a recording take it as a constructor parameter without a default (a real engine is a module singleton), so a test hands over this one.
 */
internal class RecordingControlSpy : PulpitRecordingControl {
    val stoppedAll = mutableListOf<PulpitStopReason>()
    val stoppedSessions = mutableListOf<Pair<Uuid, PulpitStopReason>>()
    val stoppedSpaces = mutableListOf<Pair<Uuid, PulpitStopReason>>()
    val reconciledSpaces = mutableListOf<Uuid>()

    /** What [isRecordingActive] answers. */
    @Volatile var recordingActive = false

    override fun isRecordingActive(sessionRoomId: Uuid): Boolean = recordingActive

    override suspend fun stopAll(reason: PulpitStopReason) {
        stoppedAll += reason
    }

    override suspend fun stopSession(
        sessionRoomId: Uuid,
        reason: PulpitStopReason,
    ) {
        stoppedSessions += sessionRoomId to reason
    }

    override suspend fun stopSpace(
        spaceId: Uuid,
        reason: PulpitStopReason,
    ) {
        stoppedSpaces += spaceId to reason
    }

    override suspend fun reconcileSpace(spaceId: Uuid) {
        reconciledSpaces += spaceId
    }
}

/** The shared no-op instance for tests that do not look at it. */
internal val NO_PULPIT_RECORDING: PulpitRecordingControl = RecordingControlSpy()

// ── the egress and the world of a pulpit-recording test ─────────────────────────────────────────────────────────

/** Hermetic [LiveKitEgressClient]: records the starts of participant FILE egresses and stops, with failure injection and settable statuses. */
internal class FakePulpitEgress : network.lapis.cloud.server.conference.LiveKitEgressClient {
    data class Started(
        val room: String,
        val identity: String,
        val path: String,
        val egressId: String,
    )

    val started: MutableList<Started> = java.util.Collections.synchronizedList(mutableListOf())
    val stopped: MutableList<String> = java.util.Collections.synchronizedList(mutableListOf())
    val statuses = java.util.concurrent.ConcurrentHashMap<String, String>()
    private var counter = 0

    @Volatile var failStart = false

    @Volatile var failStop = false

    @Volatile var failList = false

    /** Egresses that exist at LiveKit without this process having started them (what a restart leaves behind). */
    val foreign: MutableList<network.lapis.cloud.server.conference.LiveKitEgressInfo> =
        java.util.Collections.synchronizedList(mutableListOf())

    override suspend fun startParticipantFileEgress(
        roomName: String,
        identity: String,
        outputFilepath: String,
    ): network.lapis.cloud.server.conference.LiveKitEgressInfo {
        if (failStart) {
            throw network.lapis.cloud.server.conference
                .LiveKitAdminException(message = "simulated egress failure")
        }
        val id = "EG_P${++counter}"
        started += Started(room = roomName, identity = identity, path = outputFilepath, egressId = id)
        statuses[id] = "EGRESS_ACTIVE"
        return network.lapis.cloud.server.conference
            .LiveKitEgressInfo(egressId = id, roomName = roomName, status = "EGRESS_STARTING")
    }

    override suspend fun stopEgress(
        roomName: String,
        egressId: String,
    ): network.lapis.cloud.server.conference.LiveKitEgressInfo {
        if (failStop) {
            throw network.lapis.cloud.server.conference
                .LiveKitAdminException(message = "simulated egress failure")
        }
        stopped += egressId
        statuses[egressId] = "EGRESS_COMPLETE"
        return network.lapis.cloud.server.conference
            .LiveKitEgressInfo(egressId = egressId, roomName = roomName, status = "EGRESS_ENDING")
    }

    override suspend fun listEgress(roomName: String): List<network.lapis.cloud.server.conference.LiveKitEgressInfo> {
        if (failList) {
            throw network.lapis.cloud.server.conference
                .LiveKitAdminException(message = "simulated egress failure")
        }
        return started.filter { it.room == roomName }.map {
            network.lapis.cloud.server.conference.LiveKitEgressInfo(
                egressId = it.egressId,
                roomName = roomName,
                status = statuses[it.egressId] ?: "EGRESS_ACTIVE",
                fileResults =
                    listOf(
                        network.lapis.cloud.server.conference
                            .LiveKitEgressFileInfo(filename = it.path),
                    ),
            )
        } + foreign.filter { it.roomName == roomName }
    }

    override suspend fun startTrackEgress(
        roomName: String,
        trackId: String,
        outputFilepathWithoutExtension: String,
    ): network.lapis.cloud.server.conference.LiveKitEgressInfo = error("a pulpit recording never starts a track egress")

    override suspend fun startRoomCompositeEgress(
        roomName: String,
        layout: network.lapis.cloud.shared.domain.ConferenceStreamLayout,
        latencyMode: network.lapis.cloud.shared.domain.ConferenceStreamLatencyMode,
        rtmpUrls: List<String>,
    ): network.lapis.cloud.server.conference.LiveKitEgressInfo = error("a pulpit recording never starts a composite egress")

    override suspend fun startParticipantEgress(
        roomName: String,
        identity: String,
        latencyMode: network.lapis.cloud.shared.domain.ConferenceStreamLatencyMode,
        rtmpUrls: List<String>,
    ): network.lapis.cloud.server.conference.LiveKitEgressInfo = error("a pulpit recording never starts a stream egress")

    override suspend fun updateStream(
        roomName: String,
        egressId: String,
        addUrls: List<String>,
        removeUrls: List<String>,
    ): network.lapis.cloud.server.conference.LiveKitEgressInfo = error("a pulpit recording never updates a stream")
}

/** Writes the pulpit-recording settings of the single organization row directly (the services are tested elsewhere). */
internal object PulpitSettingsFixture {
    fun set(
        enabled: Boolean,
        noticeVersion: String? = if (enabled) network.lapis.cloud.shared.domain.PULPIT_RECORDING_NOTICE_VERSION else null,
        requireConfirmation: Boolean = true,
    ) {
        org.jetbrains.exposed.v1.jdbc.transactions.transaction {
            network.lapis.cloud.server.db.generated.OrganizationSettingsTable.update(
                {
                    network.lapis.cloud.server.db.generated.OrganizationSettingsTable.id eq
                        network.lapis.cloud.server.rpc.ORGANIZATION_SETTINGS_ID
                },
            ) {
                it[pulpitRecordingEnabled] = enabled
                it[pulpitRecordingNoticeVersion] = noticeVersion
                it[pulpitRecordingRequireConfirmation] = requireConfirmation
            }
        }
    }

    fun reset() = set(enabled = false, noticeVersion = null, requireConfirmation = true)

    fun allowInSpace(
        spaceId: Uuid,
        allowed: Boolean = true,
    ) {
        org.jetbrains.exposed.v1.jdbc.transactions.transaction {
            network.lapis.cloud.server.db.generated.EncounterSpaceTable.update(
                { network.lapis.cloud.server.db.generated.EncounterSpaceTable.id eq spaceId },
            ) { it[pulpitRecordingAllowed] = allowed }
        }
    }
}

/**
 * Everything of one pulpit-recording test: the [TableWorld] (steward, pulpit person, board, three congregation members), the fake egress, the
 * coordinator with its state, and a temporary directory standing in for both the egress volume and the document store.
 */
internal class PulpitWorld(
    val w: TableWorld,
    val egress: FakePulpitEgress = FakePulpitEgress(),
    val state: PulpitRecordingState = PulpitRecordingState(),
    val root: java.io.File =
        java.nio.file.Files
            .createTempDirectory("pulpit-recording")
            .toFile(),
) {
    /** What the volumes report as free (a test lowers it to simulate a full disk). */
    @Volatile var freeBytes: Long = Long.MAX_VALUE

    val hostDir: java.io.File = root.resolve("egress").apply { mkdirs() }
    val documents: java.io.File = root.resolve("documents").apply { mkdirs() }

    val recordingConfig: network.lapis.cloud.server.conference.ConferenceRecordingConfig =
        network.lapis.cloud.server.conference.ConferenceRecordingConfig.load { key ->
            when (key) {
                "LAPIS_RECORDING_ENABLED" -> "true"
                "LAPIS_EGRESS_OUTPUT_CONTAINER_DIR" -> "/out"
                "LAPIS_EGRESS_OUTPUT_HOST_DIR" -> hostDir.path
                else -> null
            }
        }

    val coordinator =
        PulpitRecordingCoordinator(
            liveKitAdminClient = w.rig.liveKit,
            liveKitEgressClient = egress,
            recordingConfig = recordingConfig,
            liveKitEnabled = true,
            state = state,
            floorState = w.rig.floorState,
            documentStorageRoot = documents,
            diskSpace = { freeBytes },
        )

    private val control = FederationInboxRateLimiter(maxRequests = 1_000, window = 1.minutes)
    private val read = FederationInboxRateLimiter(maxRequests = 1_000, window = 1.minutes)

    fun service(call: ApplicationCall) =
        PulpitRecordingService(
            call = call,
            coordinator = coordinator,
            controlRateLimiter = control,
            readRateLimiter = read,
            recordingInfrastructureAvailable = true,
        )

    suspend fun <T> asMember(
        client: HttpClient,
        member: Uuid,
        block: suspend (PulpitRecordingService) -> T,
    ): Result<T> = client.runAs(member = member) { call -> block(service(call)) }

    /** The LiveKit room name of the open session of the world (set by [open]). */
    lateinit var roomName: String
    lateinit var roomId: Uuid

    /** Instance on, option on, confirmation as given; the steward opens the room and [people] enter and are marked connected. */
    suspend fun open(
        builder: io.ktor.server.testing.ApplicationTestBuilder,
        confirmation: Boolean = false,
        vararg people: Uuid,
    ) {
        PulpitSettingsFixture.set(enabled = true, requireConfirmation = confirmation)
        PulpitSettingsFixture.allowInSpace(spaceId = w.space)
        with(builder) { openAndEnter(w = w, *people) }
        roomId = transaction { EncounterSessions.openSession(spaceId = w.space)!![ConferenceRoomTable.id] }
        roomName = transaction { EncounterSessions.openSession(spaceId = w.space)!![ConferenceRoomTable.livekitRoomName] }
        people.forEach { id ->
            val office = id == w.steward || id == w.pulpit
            w.rig.liveKit.connect(room = roomName, identity = id.toString(), canPublish = office, canPublishData = true)
        }
    }

    /** The recording packets sent to the room of THIS world (the clock also visits the open rooms of other tests' worlds). */
    fun recordingPackets() =
        w.rig.liveKit.sentData
            .filter { it.second == network.lapis.cloud.shared.domain.ENCOUNTER_RECORDING_TOPIC && it.first == roomName }

    fun recordingPacketsTo() =
        w.rig.liveKit.sentDataTo
            .filter { it.topic == network.lapis.cloud.shared.domain.ENCOUNTER_RECORDING_TOPIC && it.room == roomName }

    fun close() {
        root.deleteRecursively()
        PulpitSettingsFixture.reset()
    }
}
