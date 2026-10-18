package network.lapis.cloud.server.events

import io.kotest.assertions.throwables.shouldNotThrowAny
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.runBlocking
import network.lapis.cloud.server.conference.ConferenceAudioGrantState
import network.lapis.cloud.server.conference.ConferenceNotesState
import network.lapis.cloud.server.conference.ConferenceRecordingConfig
import network.lapis.cloud.server.conference.ConferenceStreamingConfig
import network.lapis.cloud.server.conference.ConferenceWhiteboardState
import network.lapis.cloud.server.conference.LiveKitEgressClient
import network.lapis.cloud.server.conference.LiveKitEgressInfo
import network.lapis.cloud.server.db.DatabaseConfig
import network.lapis.cloud.server.db.DbClock
import network.lapis.cloud.server.db.DevSeedData
import network.lapis.cloud.server.db.generated.ConferenceRecordingTable
import network.lapis.cloud.server.db.generated.ConferenceRoomTable
import network.lapis.cloud.server.db.generated.ConferenceStreamDestinationTable
import network.lapis.cloud.server.db.generated.ConferenceStreamTable
import network.lapis.cloud.server.db.generated.ConferenceStreamTargetTable
import network.lapis.cloud.server.db.generated.EventTable
import network.lapis.cloud.server.encounter.encounterApp
import network.lapis.cloud.server.encounter.failure
import network.lapis.cloud.server.encounter.runAs
import network.lapis.cloud.server.rpc.ConferenceRecordingService
import network.lapis.cloud.server.rpc.ConferenceRoomCloser
import network.lapis.cloud.server.rpc.ConferenceStreamingService
import network.lapis.cloud.server.security.LoginRateLimiter
import network.lapis.cloud.server.time.TimeTestSupport
import network.lapis.cloud.shared.domain.ConferenceStreamLatencyMode
import network.lapis.cloud.shared.domain.ConferenceStreamLayout
import network.lapis.cloud.shared.domain.ConferenceStreamPlatform
import network.lapis.cloud.shared.domain.DocumentAccessLevel
import network.lapis.cloud.shared.domain.EventOnlineKind
import network.lapis.cloud.shared.rpc.EventRoomCaptureBlockedByAdminException
import network.lapis.cloud.shared.rpc.EventRoomCaptureBlockedByOrganizerException
import network.lapis.cloud.shared.rpc.EventRoomRecordingAccessLevelNotAllowedException
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import java.util.Base64
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Instant
import kotlin.uuid.Uuid

private val RECORDING_CONFIG = ConferenceRecordingConfig.load { key -> if (key == "LAPIS_RECORDING_ENABLED") "true" else null }

private val STREAMING_CONFIG =
    ConferenceStreamingConfig.load { key ->
        when (key) {
            "LAPIS_STREAMING_ENABLED" -> "true"
            "LAPIS_SECRET_ENCRYPTION_KEY" -> Base64.getEncoder().encodeToString(ByteArray(32) { it.toByte() })
            "LAPIS_STREAM_MAX_DESTINATIONS" -> "2"
            else -> null
        }
    }

/** An egress that only counts what it was asked to start. */
private class CountingEgress : LiveKitEgressClient {
    val starts = java.util.Collections.synchronizedList(mutableListOf<String>())
    private var n = 0

    override suspend fun startTrackEgress(
        roomName: String,
        trackId: String,
        outputFilepathWithoutExtension: String,
    ): LiveKitEgressInfo = error("not used")

    override suspend fun stopEgress(
        roomName: String,
        egressId: String,
    ): LiveKitEgressInfo = LiveKitEgressInfo(egressId = egressId, status = "EGRESS_ENDING")

    override suspend fun listEgress(roomName: String): List<LiveKitEgressInfo> = emptyList()

    override suspend fun startRoomCompositeEgress(
        roomName: String,
        layout: ConferenceStreamLayout,
        latencyMode: ConferenceStreamLatencyMode,
        rtmpUrls: List<String>,
    ): LiveKitEgressInfo {
        starts += roomName
        return LiveKitEgressInfo(egressId = "EG_${++n}", status = "EGRESS_STARTING")
    }

    override suspend fun startParticipantEgress(
        roomName: String,
        identity: String,
        latencyMode: ConferenceStreamLatencyMode,
        rtmpUrls: List<String>,
    ): LiveKitEgressInfo = error("not used")

    override suspend fun startParticipantFileEgress(
        roomName: String,
        identity: String,
        outputFilepath: String,
    ): LiveKitEgressInfo = error("not used")

    override suspend fun updateStream(
        roomName: String,
        egressId: String,
        addUrls: List<String>,
        removeUrls: List<String>,
    ): LiveKitEgressInfo = error("not used")
}

/**
 * V1.9.156 -- THE decision "may this event room be recorded or streamed" ([EventConferenceGuard.requireEventCaptureAllowed]) over its whole matrix
 * (instance switch x release x event room / ordinary room), and then the two places that ask it: `startRecording` and `startStream`. An ordinary room is
 * never touched; an event room is refused unless the switch is on with the current notice AND the event is released AND it is a Lapis conference.
 */
class EventCaptureDecisionTest :
    FunSpec({
        val fx = EventOnlineFixtures()
        val live = EventFakeLiveKit()
        val open = Instant.parse("2030-06-15T22:30:00Z")
        lateinit var organizer: Uuid
        lateinit var board: Uuid
        lateinit var admin: Uuid
        lateinit var provisioner: EventConferenceProvisioner
        val extraRooms = mutableListOf<Uuid>()

        beforeSpec {
            DatabaseConfig.connect()
            DevSeedData.seedIfEmpty(force = true)
            TimeTestSupport.setOrganizationZone("America/New_York")
            organizer = fx.member(name = "Veranstalterin")
            // seeded actors: an audit entry references its actor and is never deleted, so fixture members could not be cleaned up afterwards
            board = Uuid.parse("00000000-0000-0000-0000-000000000002")
            admin = Uuid.parse("00000000-0000-0000-0000-000000000001")
            provisioner =
                EventConferenceProvisioner(
                    config = EVENT_ONLINE_CONFIG,
                    liveKit = live,
                    closer =
                        ConferenceRoomCloser(
                            liveKitAdminClient = live,
                            whiteboardState = ConferenceWhiteboardState(),
                            notesState = ConferenceNotesState(),
                            audioGrantState = ConferenceAudioGrantState(),
                        ),
                    retryDelay = 20.milliseconds,
                    maxWaits = 20,
                )
        }
        afterEach { EventCaptureTestState.reset() }
        afterSpec {
            transaction {
                val roomIds = extraRooms.toList() + fx.eventIds.flatMap { fx.roomIdsOf(it) }
                if (roomIds.isNotEmpty()) {
                    val streamIds =
                        ConferenceStreamTable
                            .selectAll()
                            .where {
                                ConferenceStreamTable.roomId inList roomIds
                            }.map { it[ConferenceStreamTable.id] }
                    if (streamIds.isNotEmpty()) ConferenceStreamTargetTable.deleteWhere { streamId inList streamIds }
                    ConferenceStreamTable.deleteWhere { roomId inList roomIds }
                    ConferenceRecordingTable.deleteWhere { roomId inList roomIds }
                }
                ConferenceStreamDestinationTable.deleteWhere { createdByMemberId eq admin }
            }
            fx.cleanUp()
            transaction { if (extraRooms.isNotEmpty()) ConferenceRoomTable.deleteWhere { id inList extraRooms } }
            EventCaptureTestState.reset()
            TimeTestSupport.resetOrganizationZone()
        }

        fun eventRoom(
            released: Boolean,
            kind: EventOnlineKind = EventOnlineKind.LAPIS_CONFERENCE,
        ): Uuid {
            val event = fx.event(createdBy = organizer, kind = kind, captureAllowed = released)
            val ready = runBlocking { provisioner.ensureRoom(eventId = event, now = open) }
            return (ready as EventConferenceProvisioner.Ready.Room).roomId
        }

        fun ordinaryRoom(): Uuid {
            val id = Uuid.random()
            transaction {
                ConferenceRoomTable.insert {
                    it[ConferenceRoomTable.id] = id
                    it[title] = "Gewöhnlicher Raum"
                    it[description] = ""
                    it[livekitRoomName] = "lc-$id"
                    it[createdByMemberId] = organizer
                    it[createdAt] = DbClock.nowLocalDateTime()
                    it[endedAt] = null
                    it[maxParticipants] = 25
                    it[allowFederationGuests] = false
                }
            }
            extraRooms += id
            return id
        }

        fun availability(roomId: Uuid): EventCaptureAvailability =
            transaction {
                EventConferenceGuard.eventCaptureAvailability(
                    ConferenceRoomTable.selectAll().where { ConferenceRoomTable.id eq roomId }.single(),
                )
            }

        /** The thrown typed refusal (V1.9.158) of the guard, or `null` when it lets the room through. */
        fun refusal(roomId: Uuid): Throwable? =
            try {
                transaction {
                    EventConferenceGuard.requireEventCaptureAllowed(
                        ConferenceRoomTable.selectAll().where { ConferenceRoomTable.id eq roomId }.single(),
                    )
                }
                null
            } catch (e: Exception) {
                e
            }

        test(
            "the decision matrix: switch x release x room -- only (on, on notice, released, event room) records; an ordinary room is never touched",
        ) {
            val released = eventRoom(released = true)
            val notReleased = eventRoom(released = false)
            val ordinary = ordinaryRoom()
            // V1.9.158: the administration's reason has precedence over the organizers' one (the organizer could not help)
            EventCaptureTestState.instance(enabled = false)
            availability(released) shouldBe EventCaptureAvailability.BLOCKED_BY_ADMIN
            availability(notReleased) shouldBe EventCaptureAvailability.BLOCKED_BY_ADMIN
            availability(ordinary) shouldBe EventCaptureAvailability.NOT_EVENT_ROOM
            refusal(released).shouldBeInstanceOf<EventRoomCaptureBlockedByAdminException>()
            refusal(notReleased).shouldBeInstanceOf<EventRoomCaptureBlockedByAdminException>()
            refusal(ordinary) shouldBe null
            EventCaptureTestState.instance(enabled = true)
            availability(released) shouldBe EventCaptureAvailability.AVAILABLE
            availability(notReleased) shouldBe EventCaptureAvailability.BLOCKED_BY_ORGANIZER
            availability(ordinary) shouldBe EventCaptureAvailability.NOT_EVENT_ROOM
            refusal(released) shouldBe null
            refusal(notReleased).shouldBeInstanceOf<EventRoomCaptureBlockedByOrganizerException>()
            refusal(ordinary) shouldBe null
        }

        test("the switch is on but with an older operator notice: nothing may start") {
            val released = eventRoom(released = true)
            EventCaptureTestState.instance(enabled = true, noticeVersion = "0")
            availability(released) shouldBe EventCaptureAvailability.BLOCKED_BY_ADMIN
            EventCaptureTestState.instance(enabled = true, noticeVersion = null)
            availability(released) shouldBe EventCaptureAvailability.BLOCKED_BY_ADMIN
        }

        test("a release on an event that is no Lapis conference does not count") {
            EventCaptureTestState.instance(enabled = true)
            val room = eventRoom(released = true)
            transaction {
                // the kind is switched away behind the release's back (the lifecycle hook normally resets the flag)
                EventTable.update({ EventTable.id eq fx.eventIds.last() }) { it[onlineKind] = EventOnlineKind.NONE }
            }
            availability(room) shouldBe EventCaptureAvailability.BLOCKED_BY_ORGANIZER
        }

        test(
            "startRecording of an event room: refused in every cell but the allowed one; the file is for the board (never 'all members')",
        ) {
            val released = eventRoom(released = true)
            val notReleased = eventRoom(released = false)
            encounterApp {
                fun recording(call: io.ktor.server.application.ApplicationCall) =
                    ConferenceRecordingService(
                        call = call,
                        ffmpegAvailable = true,
                        config = EVENT_ONLINE_CONFIG,
                        recordingConfig = RECORDING_CONFIG,
                    )

                suspend fun start(
                    room: Uuid,
                    level: DocumentAccessLevel = DocumentAccessLevel.BOARD_ONLY,
                ) = client.runAs(member = board) { call -> recording(call).startRecording(roomId = room.toString(), accessLevel = level) }

                // switch off: refused for both (the moderator is told the administration's reason)
                start(released).failure<EventRoomCaptureBlockedByAdminException>()
                start(notReleased).failure<EventRoomCaptureBlockedByAdminException>()
                EventCaptureTestState.enabledWithText()
                // switch on, event NOT released: refused (the organizers' reason)
                start(notReleased).failure<EventRoomCaptureBlockedByOrganizerException>()
                // released, but the file may not go to all members
                start(released, DocumentAccessLevel.PUBLIC_MEMBERS).failure<EventRoomRecordingAccessLevelNotAllowedException>()
                transaction { ConferenceRecordingTable.selectAll().where { ConferenceRecordingTable.roomId eq released }.count() } shouldBe
                    0
                // released: BOARD_ONLY and ADMIN_ONLY are fine
                val started = start(released).getOrThrow()
                started.roomId shouldBe released.toString()
                transaction { ConferenceRecordingTable.selectAll().where { ConferenceRecordingTable.roomId eq released }.count() } shouldBe
                    1
            }
        }

        test("startStream of an event room: refused unless the switch is on, the event released and a destination exists; then it starts") {
            val released = eventRoom(released = true)
            val notReleased = eventRoom(released = false)
            val egress = CountingEgress()
            encounterApp {
                fun streaming(call: io.ktor.server.application.ApplicationCall) =
                    ConferenceStreamingService(
                        call = call,
                        liveKitEgressClient = egress,
                        config = EVENT_ONLINE_CONFIG,
                        streamingConfig = STREAMING_CONFIG,
                        destinationRateLimiter = LoginRateLimiter(),
                        startStreamRateLimiter = LoginRateLimiter(maxFailures = 100),
                    )
                val destination =
                    client
                        .runAs(member = admin) { call ->
                            streaming(call).createDestination(
                                label = "Kanal",
                                platform = ConferenceStreamPlatform.YOUTUBE,
                                rtmpUrl = "rtmp://a.rtmp.youtube.com/live2",
                                streamKey = "test-key",
                            )
                        }.getOrThrow()

                suspend fun start(room: Uuid) =
                    client.runAs(member = board) { call ->
                        streaming(call).startStream(
                            roomId = room.toString(),
                            destinationIds = listOf(destination.id),
                            layout = ConferenceStreamLayout.GRID,
                            latencyMode = ConferenceStreamLatencyMode.STANDARD,
                            participantIdentity = null,
                        )
                    }

                start(released).failure<EventRoomCaptureBlockedByAdminException>()
                EventCaptureTestState.enabledWithText()
                start(notReleased).failure<EventRoomCaptureBlockedByOrganizerException>()
                egress.starts.shouldBeEmpty()
                start(released).getOrThrow().roomId shouldBe released.toString()
                egress.starts shouldHaveSize 1
            }
        }

        test("an ordinary room can still be recorded and streamed with the feature OFF (nothing of V1.9.156 touches it)") {
            val ordinary = ordinaryRoom()
            shouldNotThrowAny { refusal(ordinary).shouldBeNull() }
            availability(ordinary) shouldBe EventCaptureAvailability.NOT_EVENT_ROOM
        }
    })
