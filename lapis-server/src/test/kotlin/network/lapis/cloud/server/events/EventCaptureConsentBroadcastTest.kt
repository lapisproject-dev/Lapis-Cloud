package network.lapis.cloud.server.events

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import network.lapis.cloud.server.conference.ConferenceAudioGrantState
import network.lapis.cloud.server.conference.ConferenceNotesState
import network.lapis.cloud.server.conference.ConferenceWhiteboardState
import network.lapis.cloud.server.db.DatabaseConfig
import network.lapis.cloud.server.db.DbClock
import network.lapis.cloud.server.db.DevSeedData
import network.lapis.cloud.server.db.generated.ConferenceParticipationTable
import network.lapis.cloud.server.db.generated.ConferenceRecordingTable
import network.lapis.cloud.server.db.generated.ConferenceRoomTable
import network.lapis.cloud.server.rpc.ConferenceRoomCloser
import network.lapis.cloud.server.time.OrganizationTimeZone
import network.lapis.cloud.server.time.TimeTestSupport
import network.lapis.cloud.shared.domain.ConferenceRecordingStatus
import network.lapis.cloud.shared.domain.ConferenceRole
import network.lapis.cloud.shared.domain.DocumentAccessLevel
import network.lapis.cloud.shared.domain.EVENT_CAPTURE_ASK_TOPIC
import network.lapis.cloud.shared.domain.EVENT_CAPTURE_COUNT_TOPIC
import network.lapis.cloud.shared.domain.EventCaptureAcknowledgmentInput
import network.lapis.cloud.shared.domain.EventCaptureConsentCountDto
import network.lapis.cloud.shared.domain.eventCaptureAskPacket
import network.lapis.cloud.shared.domain.eventCaptureCountPacket
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * V1.9.280 -- the consent pass of [EventCaptureLiveBroadcaster]: the people who are in the room and lack a proof get a point-to-point ASK, the MODERATORS get
 * the count (numbers only), nobody else gets anything; while a recording runs a person without a proof is removed (connection cut, participation closed);
 * no network call is ever made inside a transaction; a failed send is simply tried again.
 */
class EventCaptureConsentBroadcastTest :
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
            live.sentTo.clear()
            live.removed.clear()
            live.failSend = false
            live.networkCallInsideTransaction = false
            live.participants.values.forEach { it.clear() }
            now = open
            transaction {
                ConferenceRecordingTable.deleteWhere { startedByMemberId eq organizer }
                fx.eventIds.flatMap { fx.roomIdsOf(it) }.forEach { room ->
                    ConferenceRoomTable.update({ ConferenceRoomTable.id eq room }) { it[endedAt] = DbClock.nowLocalDateTime() }
                }
            }
            EventCaptureTestState.reset()
        }
        afterSpec {
            fx.cleanUp()
            EventCaptureTestState.reset()
            TimeTestSupport.resetOrganizationZone()
        }

        fun broadcaster() =
            EventCaptureLiveBroadcaster(
                liveKit = live,
                clock = { now },
                guestKeySecret = EVENT_ONLINE_CONFIG.apiSecret,
            )

        fun releasedRoom(): Triple<Uuid, Uuid, String> {
            val event = fx.event(createdBy = organizer, captureAllowed = true)
            val ready = runBlocking { provisioner.ensureRoom(eventId = event, now = open) } as EventConferenceProvisioner.Ready.Room
            return Triple(event, ready.roomId, ready.livekitRoomName)
        }

        fun participate(
            room: Uuid,
            member: Uuid,
            role: ConferenceRole = ConferenceRole.PARTICIPANT,
        ) = transaction {
            ConferenceParticipationTable.insert {
                it[id] = Uuid.random()
                it[roomId] = room
                it[memberId] = member
                it[ConferenceParticipationTable.role] = role
                it[joinedAt] = DbClock.nowLocalDateTime()
                it[leftAt] = null
            }
        }

        fun acknowledge(
            event: Uuid,
            member: Uuid,
            consent: EventCaptureAcknowledgmentInput,
        ) = transaction {
            EventCaptureAcknowledgments.recordMember(
                eventId = event,
                memberId = member,
                consentVersion = consent.consentVersion,
                consentSha256 = consent.consentSha256,
                today = OrganizationTimeZone.today(),
            )
        }

        fun record(room: Uuid) {
            transaction {
                ConferenceRecordingTable.insert {
                    it[id] = Uuid.random()
                    it[roomId] = room
                    it[startedByMemberId] = organizer
                    it[startedAt] = DbClock.nowLocalDateTime()
                    it[stoppedAt] = null
                    it[readyAt] = null
                    it[status] = ConferenceRecordingStatus.RECORDING
                    it[accessLevel] = DocumentAccessLevel.BOARD_ONLY
                    it[documentId] = null
                    it[rawDir] = "x"
                    it[durationSeconds] = null
                    it[fileSizeBytes] = null
                    it[failureReason] = null
                    it[composeAttempts] = 0
                }
            }
        }

        test(
            "the people without a proof get the ask (and only they); the moderators get the count (and only they); nothing names anybody",
        ) {
            val consent = EventCaptureTestState.enabledWithText()
            val (event, room, name) = releasedRoom()
            val moderator = fx.member()
            val agreed = fx.member()
            val lacking = fx.member()
            participate(room, moderator, ConferenceRole.MODERATOR)
            participate(room, agreed)
            participate(room, lacking)
            listOf(moderator, agreed, lacking).forEach { live.seedLive(room = name, identity = it.toString()) }
            acknowledge(event, moderator, consent)
            acknowledge(event, agreed, consent)

            runBlocking { broadcaster().consentSweep() }

            val asks = live.sentTo.filter { it.topic == EVENT_CAPTURE_ASK_TOPIC }
            asks.size shouldBe 1
            asks.single().room shouldBe name
            asks.single().destinations shouldBe listOf(lacking.toString())
            eventCaptureAskPacket(fromParticipant = false, payload = asks.single().payload.toByteArray()) shouldBe true
            val counts = live.sentTo.filter { it.topic == EVENT_CAPTURE_COUNT_TOPIC }
            counts.size shouldBe 1
            counts.single().destinations shouldBe listOf(moderator.toString())
            eventCaptureCountPacket(fromParticipant = false, payload = counts.single().payload.toByteArray()) shouldBe
                EventCaptureConsentCountDto(acknowledged = 2, total = 3)
            // no identity inside a payload
            live.sentTo.forEach { packet ->
                listOf(moderator, agreed, lacking).forEach {
                    packet.payload.contains(it.toString()) shouldBe
                        false
                }
            }
            // nobody is removed while nothing records
            live.removed.shouldBeEmpty()
            live.networkCallInsideTransaction shouldBe false
        }

        test("the ask is sent once, not every pass; again after 30 s (a lost packet) or when a change forces a pass") {
            EventCaptureTestState.enabledWithText()
            val (event, room, name) = releasedRoom()
            val lacking = fx.member()
            participate(room, lacking)
            live.seedLive(room = name, identity = lacking.toString())
            val b = broadcaster()
            runBlocking { b.consentSweep() }
            live.sentTo.count { it.topic == EVENT_CAPTURE_ASK_TOPIC } shouldBe 1
            now = open + 6.seconds
            runBlocking { b.consentSweep() }
            live.sentTo.count { it.topic == EVENT_CAPTURE_ASK_TOPIC } shouldBe 1
            now = open + 31.seconds
            runBlocking { b.consentSweep() }
            live.sentTo.count { it.topic == EVENT_CAPTURE_ASK_TOPIC } shouldBe 2
            // a release change forces a pass and asks right away
            runBlocking { b.consentChanged(event) }
            live.sentTo.count { it.topic == EVENT_CAPTURE_ASK_TOPIC } shouldBe 3
        }

        test("an unchanged count is not repeated; a change or a new moderator sends it again; everybody agreed sends no ask") {
            val consent = EventCaptureTestState.enabledWithText()
            val (event, room, name) = releasedRoom()
            val moderator = fx.member()
            val guest = fx.member()
            participate(room, moderator, ConferenceRole.MODERATOR)
            participate(room, guest)
            listOf(moderator, guest).forEach { live.seedLive(room = name, identity = it.toString()) }
            val b = broadcaster()
            runBlocking { b.consentSweep() }
            live.sentTo.count { it.topic == EVENT_CAPTURE_COUNT_TOPIC } shouldBe 1
            runBlocking { b.consentSweep() }
            // the count did not change: not repeated (inside the heartbeat)
            live.sentTo.count { it.topic == EVENT_CAPTURE_COUNT_TOPIC } shouldBe 1
            acknowledge(event, guest, consent)
            runBlocking { b.consentChanged(event) }
            live.sentTo.count { it.topic == EVENT_CAPTURE_COUNT_TOPIC } shouldBe 2
            acknowledge(event, moderator, consent)
            live.sentTo.clear()
            runBlocking { b.consentChanged(event) }
            live.sentTo.filter { it.topic == EVENT_CAPTURE_ASK_TOPIC }.shouldBeEmpty()
            eventCaptureCountPacket(
                fromParticipant = false,
                payload =
                    live.sentTo
                        .single { it.topic == EVENT_CAPTURE_COUNT_TOPIC }
                        .payload
                        .toByteArray(),
            ) shouldBe EventCaptureConsentCountDto(acknowledged = 2, total = 2)
        }

        test("a room of a release that is switched off, or of an event that is not released, is left alone") {
            EventCaptureTestState.enabledWithText()
            val (event, room, name) = releasedRoom()
            val someone = fx.member()
            participate(room, someone)
            live.seedLive(room = name, identity = someone.toString())
            EventCaptureTestState.release(eventId = event, allowed = false)
            runBlocking { broadcaster().consentSweep() }
            live.sentTo.shouldBeEmpty()
            // the instance switch off: dormant as well
            EventCaptureTestState.release(eventId = event, allowed = true)
            EventCaptureTestState.instance(enabled = false)
            runBlocking { broadcaster().consentSweep() }
            live.sentTo.shouldBeEmpty()
        }

        test("while a recording runs, whoever is in the room without a proof is removed: the connection and the participation") {
            val consent = EventCaptureTestState.enabledWithText()
            val (event, room, name) = releasedRoom()
            val agreed = fx.member()
            val intruder = fx.member()
            val evg = "evg-" + Uuid.random()
            participate(room, agreed)
            participate(room, intruder)
            listOf(agreed.toString(), intruder.toString(), evg).forEach { live.seedLive(room = name, identity = it) }
            acknowledge(event, agreed, consent)
            record(room)

            runBlocking { broadcaster().consentSweep() }

            live.removed.map { it.second } shouldContainExactlyInAnyOrder listOf(intruder.toString(), evg)
            transaction {
                ConferenceParticipationTable
                    .selectAll()
                    .where {
                        (ConferenceParticipationTable.roomId eq room) and
                            (ConferenceParticipationTable.memberId eq intruder) and
                            ConferenceParticipationTable.leftAt.isNull()
                    }.count()
            } shouldBe 0
            // the person with the proof stays
            transaction {
                ConferenceParticipationTable
                    .selectAll()
                    .where { (ConferenceParticipationTable.memberId eq agreed) and ConferenceParticipationTable.leftAt.isNull() }
                    .count()
            } shouldBe 1
            live.networkCallInsideTransaction shouldBe false
        }

        test("LiveKit not reachable for a send: nothing breaks and the next pass tries again") {
            EventCaptureTestState.enabledWithText()
            val (_, room, name) = releasedRoom()
            val someone = fx.member()
            participate(room, someone)
            live.seedLive(room = name, identity = someone.toString())
            live.failSend = true
            val b = broadcaster()
            runBlocking { b.consentSweep() }
            live.sentTo.shouldBeEmpty()
            live.failSend = false
            now = open + 6.seconds
            runBlocking { b.consentSweep() }
            live.sentTo.count { it.topic == EVENT_CAPTURE_ASK_TOPIC } shouldBe 1
        }
    })
