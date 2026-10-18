package network.lapis.cloud.server.events

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
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
import network.lapis.cloud.server.db.generated.ConferenceParticipationTable
import network.lapis.cloud.server.db.generated.ConferenceRecordingTable
import network.lapis.cloud.server.db.generated.ConferenceRoomTable
import network.lapis.cloud.server.db.generated.ConferenceStreamDestinationTable
import network.lapis.cloud.server.db.generated.ConferenceStreamTable
import network.lapis.cloud.server.db.generated.ConferenceStreamTargetTable
import network.lapis.cloud.server.encounter.encounterApp
import network.lapis.cloud.server.encounter.failure
import network.lapis.cloud.server.encounter.runAs
import network.lapis.cloud.server.federation.FederationInboxRateLimiter
import network.lapis.cloud.server.rpc.ConferenceRecordingService
import network.lapis.cloud.server.rpc.ConferenceRoomCloser
import network.lapis.cloud.server.rpc.ConferenceService
import network.lapis.cloud.server.rpc.ConferenceStreamingService
import network.lapis.cloud.server.security.LoginRateLimiter
import network.lapis.cloud.server.time.TimeTestSupport
import network.lapis.cloud.shared.domain.ConferenceEventCaptureState
import network.lapis.cloud.shared.domain.ConferenceRole
import network.lapis.cloud.shared.domain.ConferenceStreamLatencyMode
import network.lapis.cloud.shared.domain.ConferenceStreamLayout
import network.lapis.cloud.shared.domain.DocumentAccessLevel
import network.lapis.cloud.shared.domain.EventOnlineKind
import network.lapis.cloud.shared.rpc.ConferenceRecordingAlreadyActiveException
import network.lapis.cloud.shared.rpc.ConferenceRoomEndedException
import network.lapis.cloud.shared.rpc.EventRoomCaptureBlockedByAdminException
import network.lapis.cloud.shared.rpc.EventRoomCaptureBlockedByOrganizerException
import network.lapis.cloud.shared.rpc.EventRoomRecordingAccessLevelNotAllowedException
import network.lapis.cloud.shared.rpc.ForbiddenException
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import java.util.Base64
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant
import kotlin.uuid.Uuid

private val UNAVAILABLE_RECORDING_CONFIG = ConferenceRecordingConfig.load { key -> if (key == "LAPIS_RECORDING_ENABLED") "true" else null }

private val UNAVAILABLE_STREAMING_CONFIG =
    ConferenceStreamingConfig.load { key ->
        when (key) {
            "LAPIS_STREAMING_ENABLED" -> "true"
            "LAPIS_SECRET_ENCRYPTION_KEY" -> Base64.getEncoder().encodeToString(ByteArray(32) { it.toByte() })
            "LAPIS_STREAM_MAX_DESTINATIONS" -> "2"
            else -> null
        }
    }

/** An egress that only counts what it was asked to start. */
private class UnavailableCountingEgress : LiveKitEgressClient {
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
 * V1.9.158 -- "an unavailable option never fails silently": the typed refusals of an event room (recording access level, capture blocked by the
 * administration / the organizers, already running, ended) and -- the security part -- that they reach MODERATORS only: the moderator check runs
 * before the capture decision, so a stranger never learns the release state of an event. Also the `eventCapture` field of the room DTO per role.
 */
class EventRoomUnavailableOptionsTest :
    FunSpec({
        val fx = EventOnlineFixtures()
        val live = EventFakeLiveKit()
        val open = Instant.parse("2030-06-15T22:30:00Z")
        lateinit var organizer: Uuid
        lateinit var attendee: Uuid
        lateinit var stranger: Uuid
        lateinit var board: Uuid
        lateinit var admin: Uuid
        lateinit var provisioner: EventConferenceProvisioner
        val extraRooms = mutableListOf<Uuid>()

        beforeSpec {
            DatabaseConfig.connect()
            DevSeedData.seedIfEmpty(force = true)
            TimeTestSupport.setOrganizationZone("America/New_York")
            organizer = fx.member(name = "Veranstalterin")
            attendee = fx.member(name = "Teilnehmer")
            stranger = fx.member(name = "Fremder")
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
                            .where { ConferenceStreamTable.roomId inList roomIds }
                            .map { it[ConferenceStreamTable.id] }
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

        fun eventRoom(released: Boolean): Uuid {
            val event = fx.event(createdBy = organizer, kind = EventOnlineKind.LAPIS_CONFERENCE, captureAllowed = released)
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

        fun recording(call: io.ktor.server.application.ApplicationCall) =
            ConferenceRecordingService(
                call = call,
                ffmpegAvailable = true,
                config = EVENT_ONLINE_CONFIG,
                recordingConfig = UNAVAILABLE_RECORDING_CONFIG,
            )

        fun streaming(
            call: io.ktor.server.application.ApplicationCall,
            egress: LiveKitEgressClient,
        ) = ConferenceStreamingService(
            call = call,
            liveKitEgressClient = egress,
            config = EVENT_ONLINE_CONFIG,
            streamingConfig = UNAVAILABLE_STREAMING_CONFIG,
            destinationRateLimiter = LoginRateLimiter(),
            startStreamRateLimiter = LoginRateLimiter(maxFailures = 100),
        )

        fun conference(call: io.ktor.server.application.ApplicationCall) =
            ConferenceService(
                call = call,
                liveKitAdminClient = live,
                createRoomRateLimiter = LoginRateLimiter(),
                config = EVENT_ONLINE_CONFIG,
                conferenceMeetingBindRateLimiter = FederationInboxRateLimiter(maxRequests = 1000, window = 1.minutes),
                roomVotingStateRateLimiter = FederationInboxRateLimiter(maxRequests = 1000, window = 1.minutes),
            )

        suspend fun io.ktor.client.HttpClient.startRecording(
            who: Uuid,
            room: Uuid,
            level: DocumentAccessLevel = DocumentAccessLevel.BOARD_ONLY,
        ) = runAs(member = who) { call -> recording(call).startRecording(roomId = room.toString(), accessLevel = level) }

        suspend fun io.ktor.client.HttpClient.startStream(
            who: Uuid,
            room: Uuid,
            egress: LiveKitEgressClient,
            destinationId: String,
        ) = runAs(member = who) { call ->
            streaming(call, egress).startStream(
                roomId = room.toString(),
                destinationIds = listOf(destinationId),
                layout = ConferenceStreamLayout.GRID,
                latencyMode = ConferenceStreamLatencyMode.STANDARD,
                participantIdentity = null,
            )
        }

        fun present(
            room: Uuid,
            member: Uuid,
        ) {
            transaction {
                ConferenceParticipationTable.insert {
                    it[ConferenceParticipationTable.id] = Uuid.random()
                    it[ConferenceParticipationTable.roomId] = room
                    it[ConferenceParticipationTable.memberId] = member
                    it[role] = ConferenceRole.PARTICIPANT
                    it[joinedAt] = DbClock.nowLocalDateTime()
                    it[leftAt] = null
                }
            }
        }

        val uuidPattern = Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")

        test("a recording of an event room for all members is refused with its own type, and no row is written; the board level starts") {
            EventCaptureTestState.enabledWithText()
            val released = eventRoom(released = true)
            encounterApp {
                client
                    .startRecording(
                        board,
                        released,
                        DocumentAccessLevel.PUBLIC_MEMBERS,
                    ).failure<EventRoomRecordingAccessLevelNotAllowedException>()
                transaction { ConferenceRecordingTable.selectAll().where { ConferenceRecordingTable.roomId eq released }.count() } shouldBe
                    0
                client.startRecording(board, released, DocumentAccessLevel.ADMIN_ONLY).getOrThrow().roomId shouldBe released.toString()
            }
        }

        test("a second start answers 'already running' with its own type") {
            EventCaptureTestState.enabledWithText()
            val released = eventRoom(released = true)
            encounterApp {
                client.startRecording(board, released).getOrThrow()
                client.startRecording(board, released).failure<ConferenceRecordingAlreadyActiveException>()
            }
        }

        test("REGRESSION: a stranger gets the neutral refusal, never a typed reason -- release state and access-level rule stay private") {
            val released = eventRoom(released = true)
            val notReleased = eventRoom(released = false)
            val egress = UnavailableCountingEgress()
            encounterApp {
                // instance switch OFF, then ON: in every cell the non-moderators meet ForbiddenException only
                listOf(false, true).forEach { switchOn ->
                    if (switchOn) EventCaptureTestState.enabledWithText() else EventCaptureTestState.instance(enabled = false)
                    listOf(stranger, attendee).forEach { who ->
                        listOf(released, notReleased).forEach { room ->
                            client.startRecording(who, room).failure<ForbiddenException>()
                            client.startRecording(who, room, DocumentAccessLevel.PUBLIC_MEMBERS).failure<ForbiddenException>()
                            client.startStream(who, room, egress, Uuid.random().toString()).failure<ForbiddenException>()
                        }
                    }
                }
                egress.starts.shouldBeEmpty()
            }
        }

        test("the moderator of an event room (the organizer who created it) is told the typed reason") {
            val notReleased = eventRoom(released = false)
            encounterApp {
                EventCaptureTestState.enabledWithText()
                client.startRecording(organizer, notReleased).failure<EventRoomCaptureBlockedByOrganizerException>()
                EventCaptureTestState.instance(enabled = false)
                client.startRecording(organizer, notReleased).failure<EventRoomCaptureBlockedByAdminException>()
            }
        }

        test("a stream of an ended room answers 'ended'; so does a recording") {
            EventCaptureTestState.enabledWithText()
            val room = eventRoom(released = true)
            transaction { ConferenceRoomTable.update({ ConferenceRoomTable.id eq room }) { it[endedAt] = DbClock.nowLocalDateTime() } }
            val egress = UnavailableCountingEgress()
            encounterApp {
                client.startRecording(board, room).failure<ConferenceRoomEndedException>()
                client.startStream(board, room, egress, Uuid.random().toString()).failure<ConferenceRoomEndedException>()
            }
        }

        test("the room DTO says what the caller may know: NONE / EVENT_ROOM for strangers, the concrete reason only for a moderator") {
            val released = eventRoom(released = true)
            val notReleased = eventRoom(released = false)
            val ordinary = ordinaryRoom()
            present(released, attendee)
            present(notReleased, attendee)
            encounterApp {
                suspend fun state(
                    who: Uuid,
                    room: Uuid,
                ) = client.runAs(member = who) { call -> conference(call).getRoom(room.toString()) }.getOrThrow().eventCapture

                // switch off: the moderators (organizer = creator, board = privileged) see the administration's reason
                EventCaptureTestState.instance(enabled = false)
                state(organizer, released) shouldBe ConferenceEventCaptureState.EVENT_ROOM_CAPTURE_BLOCKED_BY_ADMIN
                state(board, notReleased) shouldBe ConferenceEventCaptureState.EVENT_ROOM_CAPTURE_BLOCKED_BY_ADMIN
                // a participant (not a moderator) only learns that this is an event room
                state(attendee, released) shouldBe ConferenceEventCaptureState.EVENT_ROOM
                state(attendee, notReleased) shouldBe ConferenceEventCaptureState.EVENT_ROOM
                EventCaptureTestState.enabledWithText()
                state(organizer, released) shouldBe ConferenceEventCaptureState.EVENT_ROOM_CAPTURE_AVAILABLE
                state(board, notReleased) shouldBe ConferenceEventCaptureState.EVENT_ROOM_CAPTURE_BLOCKED_BY_ORGANIZER
                state(attendee, notReleased) shouldBe ConferenceEventCaptureState.EVENT_ROOM
                state(organizer, ordinary) shouldBe ConferenceEventCaptureState.NONE
                state(board, ordinary) shouldBe ConferenceEventCaptureState.NONE
            }
        }

        test("the fixed exception messages carry no id, no title and no name") {
            listOf(
                EventRoomRecordingAccessLevelNotAllowedException().message,
                EventRoomCaptureBlockedByAdminException().message,
                EventRoomCaptureBlockedByOrganizerException().message,
                ConferenceRecordingAlreadyActiveException().message,
                ConferenceRoomEndedException().message,
            ).forEach { message ->
                uuidPattern.containsMatchIn(message) shouldBe false
                message.contains("Gewöhnlicher Raum") shouldBe false
            }
        }
    })
