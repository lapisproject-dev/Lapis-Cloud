package network.lapis.cloud.server.events

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import network.lapis.cloud.server.conference.ConferenceAudioGrantState
import network.lapis.cloud.server.conference.ConferenceNotesState
import network.lapis.cloud.server.conference.ConferenceWhiteboardState
import network.lapis.cloud.server.db.DatabaseConfig
import network.lapis.cloud.server.db.DbClock
import network.lapis.cloud.server.db.DevSeedData
import network.lapis.cloud.server.db.generated.ConferenceRecordingTable
import network.lapis.cloud.server.db.generated.ConferenceRoomTable
import network.lapis.cloud.server.db.generated.ConferenceStreamTable
import network.lapis.cloud.server.rpc.ConferenceRoomCloser
import network.lapis.cloud.server.time.TimeTestSupport
import network.lapis.cloud.shared.domain.ConferenceRecordingStatus
import network.lapis.cloud.shared.domain.DocumentAccessLevel
import network.lapis.cloud.shared.domain.EVENT_CAPTURE_TOPIC
import network.lapis.cloud.shared.domain.EventCaptureLiveDto
import network.lapis.cloud.shared.domain.eventCapturePacket
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * V1.9.156 -- the banner signal of an event room ([EventCaptureLiveBroadcaster]): a packet on `lapis-event-capture` that carries the state and nothing else, sent
 * only for the rooms of RELEASED events that are still alive, on a change at once and as a heartbeat while something runs, never remembered as sent when
 * LiveKit failed. The packet is a valid input of the client's strict parser.
 */
class EventCaptureLiveBroadcasterTest :
    FunSpec({
        val fx = EventOnlineFixtures()
        val live = EventFakeLiveKit()
        val open = Instant.parse("2030-06-15T22:30:00Z")
        lateinit var organizer: Uuid
        lateinit var provisioner: EventConferenceProvisioner
        var now = open

        beforeSpec {
            DatabaseConfig.connect()
            DevSeedData.seedIfEmpty(force = true)
            TimeTestSupport.setOrganizationZone("America/New_York")
            organizer = fx.member(name = "Veranstalterin")
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
        afterEach {
            live.sentData.clear()
            live.failSend = false
            now = open
            transaction {
                ConferenceRecordingTable.deleteWhere { startedByMemberId eq organizer }
                ConferenceStreamTable.deleteWhere { startedByMemberId eq organizer }
                // every room the test made is over, so the next test's sweep sees only its own
                fx.eventIds.flatMap { fx.roomIdsOf(it) }.forEach { room ->
                    ConferenceRoomTable.update({ ConferenceRoomTable.id eq room }) { it[endedAt] = DbClock.nowLocalDateTime() }
                }
            }
        }
        afterSpec {
            fx.cleanUp()
            TimeTestSupport.resetOrganizationZone()
        }

        fun broadcaster() = EventCaptureLiveBroadcaster(liveKit = live, clock = { now })

        fun roomOf(released: Boolean): Pair<Uuid, String> {
            val event = fx.event(createdBy = organizer, captureAllowed = released)
            val ready = runBlocking { provisioner.ensureRoom(eventId = event, now = open) } as EventConferenceProvisioner.Ready.Room
            return ready.roomId to ready.livekitRoomName
        }

        fun record(
            room: Uuid,
            status: ConferenceRecordingStatus = ConferenceRecordingStatus.RECORDING,
        ): Uuid {
            val id = Uuid.random()
            transaction {
                ConferenceRecordingTable.insert {
                    it[ConferenceRecordingTable.id] = id
                    it[roomId] = room
                    it[startedByMemberId] = organizer
                    it[startedAt] = DbClock.nowLocalDateTime()
                    it[stoppedAt] = null
                    it[readyAt] = null
                    it[ConferenceRecordingTable.status] = status
                    it[accessLevel] = DocumentAccessLevel.BOARD_ONLY
                    it[documentId] = null
                    it[rawDir] = "x"
                    it[durationSeconds] = null
                    it[fileSizeBytes] = null
                    it[failureReason] = null
                    it[composeAttempts] = 0
                }
            }
            return id
        }

        fun packets() = live.sentData.filter { it.second == EVENT_CAPTURE_TOPIC }

        test("nothing runs: nothing is sent (a joining person learns the state from the join answer)") {
            roomOf(released = true)
            broadcaster().sweep() shouldBe 0
            packets().shouldBeEmpty()
        }

        test("a recording starts and stops: a packet at each change, a heartbeat while it runs, silence otherwise") {
            val (room, roomName) = roomOf(released = true)
            val b = broadcaster()
            val recording = record(room)
            b.sweep() shouldBe 1
            packets().single().first shouldBe roomName
            packets().single().third shouldBe """{"v":1,"r":1,"s":0}"""
            // unchanged: nothing
            b.sweep() shouldBe 0
            // a heartbeat after 30 seconds
            now = open + 31.seconds
            b.sweep() shouldBe 1
            // the recording is over (the poller moved it on): the banner goes off at once
            transaction {
                ConferenceRecordingTable.update({ ConferenceRecordingTable.id eq recording }) {
                    it[status] =
                        ConferenceRecordingStatus.PROCESSING
                }
            }
            b.sweep() shouldBe 1
            packets().last().third shouldBe """{"v":1,"r":0,"s":0}"""
            b.sweep() shouldBe 0
        }

        test("a stream that is paused is not 'streaming' in the banner; one that publishes is") {
            val (room, _) = roomOf(released = true)
            val b = broadcaster()
            val stream = Uuid.random()
            insertStream(room = room, id = stream, status = network.lapis.cloud.shared.domain.ConferenceStreamStatus.LIVE, by = organizer)
            b.sweep() shouldBe 1
            packets().last().third shouldBe """{"v":1,"r":0,"s":1}"""
            transaction {
                ConferenceStreamTable.update({ ConferenceStreamTable.id eq stream }) {
                    it[status] = network.lapis.cloud.shared.domain.ConferenceStreamStatus.PAUSED
                }
            }
            b.sweep() shouldBe 1
            packets().last().third shouldBe """{"v":1,"r":0,"s":0}"""
        }

        test("only rooms of RELEASED events that are still alive are told; roomChanged of an ordinary or unreleased room sends nothing") {
            val (unreleased, _) = roomOf(released = false)
            record(unreleased)
            val b = broadcaster()
            b.sweep() shouldBe 0
            b.roomChanged(unreleased)
            b.roomChanged(Uuid.random())
            packets().shouldBeEmpty()
            val (released, name) = roomOf(released = true)
            record(released)
            b.roomChanged(released)
            packets().single().first shouldBe name
        }

        test("an ended room is not told, and a LiveKit failure is neither thrown nor remembered as sent: the next sweep tries again") {
            val (room, _) = roomOf(released = true)
            record(room)
            val b = broadcaster()
            live.failSend = true
            b.sweep() shouldBe 0
            b.roomChanged(room)
            packets().shouldBeEmpty()
            live.failSend = false
            b.sweep() shouldBe 1
            transaction {
                ConferenceRoomTable.update({ ConferenceRoomTable.id eq room }) { it[endedAt] = DbClock.nowLocalDateTime() }
            }
            now = open + 120.seconds
            b.sweep() shouldBe 0
        }

        test(
            "the payload is valid input of the strict parser the client uses, carries nothing but the state, and a packet with a sender is refused",
        ) {
            val (room, _) = roomOf(released = true)
            record(room)
            broadcaster().sweep() shouldBe 1
            val payload = packets().single().third.toByteArray()
            eventCapturePacket(fromParticipant = false, payload = payload) shouldBe EventCaptureLiveDto(recording = true, streaming = false)
            eventCapturePacket(fromParticipant = true, payload = payload) shouldBe null
            (payload.size < 32) shouldBe true
        }
    })

private fun insertStream(
    room: Uuid,
    id: Uuid,
    status: network.lapis.cloud.shared.domain.ConferenceStreamStatus,
    by: Uuid,
) {
    transaction {
        ConferenceStreamTable.insert {
            it[ConferenceStreamTable.id] = id
            it[roomId] = room
            it[startedByMemberId] = by
            it[ConferenceStreamTable.status] = status
            it[layout] = network.lapis.cloud.shared.domain.ConferenceStreamLayout.GRID
            it[latencyMode] = network.lapis.cloud.shared.domain.ConferenceStreamLatencyMode.STANDARD
            it[participantIdentity] = null
            it[livekitEgressId] = null
            it[startedAt] = DbClock.nowLocalDateTime()
            it[pausedAt] = null
            it[endedAt] = null
            it[restartCount] = 0
            it[failureReason] = null
        }
    }
}
