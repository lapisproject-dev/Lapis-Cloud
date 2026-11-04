package network.lapis.cloud.server.events

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import network.lapis.cloud.server.conference.ConferenceAudioGrantState
import network.lapis.cloud.server.conference.ConferenceNotesState
import network.lapis.cloud.server.conference.ConferenceRecordingConfig
import network.lapis.cloud.server.conference.ConferenceWhiteboardState
import network.lapis.cloud.server.db.DatabaseConfig
import network.lapis.cloud.server.db.DevSeedData
import network.lapis.cloud.server.db.generated.ConferenceRecordingTable
import network.lapis.cloud.server.db.generated.ConferenceRoomTable
import network.lapis.cloud.server.encounter.encounterApp
import network.lapis.cloud.server.encounter.failure
import network.lapis.cloud.server.encounter.runAs
import network.lapis.cloud.server.rpc.ConferenceRecordingService
import network.lapis.cloud.server.rpc.ConferenceRoomCloser
import network.lapis.cloud.server.time.OrganizationTimeZone
import network.lapis.cloud.server.time.TimeTestSupport
import network.lapis.cloud.shared.domain.ConferenceGuestConsentAcknowledgmentInput
import network.lapis.cloud.shared.domain.DocumentAccessLevel
import network.lapis.cloud.shared.domain.EventCaptureAcknowledgmentInput
import network.lapis.cloud.shared.domain.EventGuestNoticeCapture
import network.lapis.cloud.shared.domain.EventOnlineOutcome
import network.lapis.cloud.shared.rpc.EventRoomCaptureConsentMissingException
import network.lapis.cloud.shared.rpc.ServiceBusyException
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Instant
import kotlin.uuid.Uuid

private val RECORDING_CONFIG = ConferenceRecordingConfig.load { key -> if (key == "LAPIS_RECORDING_ENABLED") "true" else null }
private const val GUEST_SECRET = "test-guest-key-secret"

/**
 * V1.9.280 -- the consent gate at the START of a recording ([EventCaptureConsentGate]): it starts only when every identity in the room holds a proof of the
 * CURRENT text. A member, a guest with an account (a member row) and a guest without an account (`evg-`, proven by the guest key) all count; the egress does
 * not; every other identity blocks; the placeholder text acknowledges nobody; a pending guest token counts; an unreachable LiveKit refuses (fail closed) --
 * but only after the checks that come first.
 */
class EventCaptureConsentGateTest :
    FunSpec({
        val fx = EventOnlineFixtures()
        val live = EventFakeLiveKit()
        val open = Instant.parse("2030-06-15T22:30:00Z")
        lateinit var organizer: Uuid
        lateinit var board: Uuid
        lateinit var join: EventOnlineJoin
        lateinit var provisioner: EventConferenceProvisioner

        beforeSpec {
            DatabaseConfig.connect()
            DevSeedData.seedIfEmpty(force = true)
            TimeTestSupport.setOrganizationZone("America/New_York")
            organizer = fx.member(name = "Veranstalterin")
            board = Uuid.parse("00000000-0000-0000-0000-000000000002")
            val audio = ConferenceAudioGrantState()
            provisioner =
                EventConferenceProvisioner(
                    config = EVENT_ONLINE_CONFIG,
                    liveKit = live,
                    closer =
                        ConferenceRoomCloser(
                            liveKitAdminClient = live,
                            whiteboardState = ConferenceWhiteboardState(),
                            notesState = ConferenceNotesState(),
                            audioGrantState = audio,
                        ),
                    retryDelay = 20.milliseconds,
                    maxWaits = 20,
                )
            join = EventOnlineJoin(config = EVENT_ONLINE_CONFIG, liveKit = live, provisioner = provisioner, audioGrantState = audio)
            EventCaptureConsentGate.install(
                EventCaptureConsentGate.Context(liveKit = live, join = join, guestKeySecret = EVENT_ONLINE_CONFIG.apiSecret),
            )
        }
        afterEach {
            live.failParticipants = false
            live.participants.values.forEach { it.clear() }
            EventCaptureTestState.reset()
        }
        afterSpec {
            EventCaptureConsentGate.install(null)
            transaction {
                val roomIds = fx.eventIds.flatMap { fx.roomIdsOf(it) }
                if (roomIds.isNotEmpty()) ConferenceRecordingTable.deleteWhere { ConferenceRecordingTable.roomId inList roomIds }
            }
            fx.cleanUp()
            EventCaptureTestState.reset()
            TimeTestSupport.resetOrganizationZone()
        }

        /** A released event with a living room; returns (event, room id, LiveKit room name). */
        fun releasedRoom(): Triple<Uuid, Uuid, String> {
            val event = fx.event(createdBy = organizer, captureAllowed = true)
            val ready = runBlocking { provisioner.ensureRoom(eventId = event, now = open) } as EventConferenceProvisioner.Ready.Room
            return Triple(event, ready.roomId, ready.livekitRoomName)
        }

        fun count(
            event: Uuid,
            room: Uuid,
            roomName: String,
        ): EventCaptureConsentGate.Count {
            val present = runBlocking { EventCaptureConsentGate.collect(livekitRoomName = roomName, liveKit = live, join = join) }
            return transaction {
                EventCaptureConsentGate.count(
                    eventId = event,
                    roomId = room,
                    present = present,
                    guestKeySecret = EVENT_ONLINE_CONFIG.apiSecret,
                )
            }
        }

        fun acknowledgeMember(
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

        fun acknowledgeGuest(
            event: Uuid,
            identity: String,
            consent: EventCaptureAcknowledgmentInput,
        ) = transaction {
            EventCaptureAcknowledgments.recordGuest(
                eventId = event,
                guestKey = EventCaptureAcknowledgments.guestKeyOf(identity = identity, serverSecret = EVENT_ONLINE_CONFIG.apiSecret),
                consentVersion = consent.consentVersion,
                consentSha256 = consent.consentSha256,
                today = OrganizationTimeZone.today(),
            )
        }

        test("an empty room has nobody who could lack the consent") {
            EventCaptureTestState.enabledWithText()
            val (event, room, name) = releasedRoom()
            count(event, room, name) shouldBe EventCaptureConsentGate.Count(acknowledged = 0, total = 0, missingIdentities = emptySet())
        }

        test("a member, an account guest and a guest without an account are each counted until they hold a proof") {
            val consent = EventCaptureTestState.enabledWithText()
            val (event, room, name) = releasedRoom()
            val member = fx.member()
            val accountGuest = fx.member(status = network.lapis.cloud.shared.domain.MemberStatus.GUEST)
            val evg = "evg-" + Uuid.random()
            listOf(member.toString(), accountGuest.toString(), evg).forEach { live.seedLive(room = name, identity = it) }
            count(event, room, name).missingIdentities shouldContainExactlyInAnyOrder
                listOf(member.toString(), accountGuest.toString(), evg)
            acknowledgeMember(event, member, consent)
            count(event, room, name).missingIdentities shouldContainExactlyInAnyOrder listOf(accountGuest.toString(), evg)
            acknowledgeMember(event, accountGuest, consent)
            acknowledgeGuest(event, evg, consent)
            val done = count(event, room, name)
            done.missingIdentities.shouldBeEmpty()
            done.acknowledged shouldBe 3
            done.total shouldBe 3
        }

        test("a proof of an OLDER text does not count; neither does one of another event") {
            val old = EventCaptureTestState.enabledWithText()
            val (event, room, name) = releasedRoom()
            val (otherEvent, _, _) = releasedRoom()
            val member = fx.member()
            live.seedLive(room = name, identity = member.toString())
            acknowledgeMember(otherEvent, member, old)
            count(event, room, name).missingIdentities shouldBe setOf(member.toString())
            acknowledgeMember(event, member, old)
            count(event, room, name).missingIdentities.shouldBeEmpty()
            // a new text is published: the old proof is worth nothing any more
            EventCaptureTestState.publishConsent(label = "2026-11-04.test.v2", body = EVENT_CAPTURE_TEST_BODY + " Neu.")
            count(event, room, name).missingIdentities shouldBe setOf(member.toString())
        }

        test("the egress is ignored; every unknown identity blocks (fail closed)") {
            EventCaptureTestState.enabledWithText()
            val (event, room, name) = releasedRoom()
            live.seedLive(room = name, identity = "EG_something", kind = "EGRESS")
            count(event, room, name).total shouldBe 0
            live.seedLive(room = name, identity = "some-other-participant")
            live.seedLive(room = name, identity = "EG_without_kind")
            count(event, room, name).missingIdentities shouldContainExactlyInAnyOrder listOf("some-other-participant", "EG_without_kind")
        }

        test("the built-in placeholder text acknowledges nobody") {
            EventCaptureTestState.instance(enabled = true)
            val (event, room, name) = releasedRoom()
            val member = fx.member()
            live.seedLive(room = name, identity = member.toString())
            acknowledgeMember(
                event,
                member,
                EventCaptureAcknowledgmentInput(consentVersion = "placeholder", consentSha256 = "0".repeat(64)),
            )
            count(event, room, name).missingIdentities shouldBe setOf(member.toString())
        }

        test(
            "a member with an open participation counts although LiveKit does not list them yet; the moderator without a proof blocks as well",
        ) {
            val consent = EventCaptureTestState.enabledWithText()
            val event = fx.event(createdBy = organizer)
            val moderator = fx.member()
            val result =
                runBlocking {
                    join.joinMember(
                        eventId = event,
                        current = fx.current(memberId = moderator),
                        guestConsent = null,
                        now = open,
                    )
                }
            result.outcome shouldBe EventOnlineOutcome.JOINED
            val room = Uuid.parse(result.token!!.roomId)
            val name = result.token!!.livekitRoomName
            // the release is switched on AFTER the join: the participation exists, LiveKit lists nobody
            EventCaptureTestState.release(eventId = event)
            count(event, room, name).missingIdentities shouldBe setOf(moderator.toString())
            acknowledgeMember(event, moderator, consent)
            count(event, room, name).missingIdentities.shouldBeEmpty()
        }

        test("a guest token that was minted but whose holder has not connected yet counts") {
            val consent = EventCaptureTestState.enabledWithText()
            val event = fx.event(createdBy = organizer)
            // joined before the release: plain notice, no capture consent, no proof
            val joined =
                runBlocking { join.joinAccountless(eventId = event, displayName = "Gast (Gast)", ticketCodeSha256 = null, now = open) }
            joined.outcome shouldBe EventOnlineOutcome.JOINED
            val identity = joined.identity!!
            val room = transaction { EventCaptureLock.roomIdOf(event)!! }
            val name =
                transaction {
                    ConferenceRoomTable.selectAll().where { ConferenceRoomTable.id eq room }.single()[ConferenceRoomTable.livekitRoomName]
                }
            EventCaptureTestState.release(eventId = event)
            // the fake LiveKit does not list the guest: only the pending token knows them
            count(event, room, name).missingIdentities shouldBe setOf(identity)
            acknowledgeGuest(event, identity, consent)
            count(event, room, name).missingIdentities.shouldBeEmpty()
        }

        test("a guest who was seen connected and then left (or reloaded under a new identity) no longer blocks as a pending token") {
            EventCaptureTestState.enabledWithText()
            val event = fx.event(createdBy = organizer)
            val joined =
                runBlocking { join.joinAccountless(eventId = event, displayName = "Gast (Gast)", ticketCodeSha256 = null, now = open) }
            joined.outcome shouldBe EventOnlineOutcome.JOINED
            val identity = joined.identity!!
            val room = transaction { EventCaptureLock.roomIdOf(event)!! }
            val name =
                transaction {
                    ConferenceRoomTable.selectAll().where { ConferenceRoomTable.id eq room }.single()[ConferenceRoomTable.livekitRoomName]
                }
            EventCaptureTestState.release(eventId = event)
            // never seen: still pending
            count(event, room, name).missingIdentities shouldBe setOf(identity)
            // seen connected ...
            live.seedLive(room = name, identity = identity)
            count(event, room, name).missingIdentities shouldBe setOf(identity)
            // ... and gone again: nobody is left who could be asked
            live.participants[name]?.removeIf { it.identity == identity }
            count(event, room, name).missingIdentities.shouldBeEmpty()
        }

        test("LiveKit not reachable: the presence is unavailable and the gate refuses with ServiceBusy (fail closed)") {
            EventCaptureTestState.enabledWithText()
            val (event, room, _) = releasedRoom()
            live.failParticipants = true
            val present = runBlocking { EventCaptureConsentGate.collectForRoom(room) }
            present.unavailable shouldBe true
            shouldThrow<ServiceBusyException> {
                transaction { EventCaptureConsentGate.requireEveryoneAcknowledged(eventId = event, roomId = room, present = present) }
            }
            // an ordinary room is not touched at all
            runBlocking { EventCaptureConsentGate.collectForRoom(Uuid.random()) } shouldBe EventRoomPresence.EMPTY
        }

        test(
            "startRecording of an event room: refused while somebody lacks the consent (nothing is written), started once everybody holds it",
        ) {
            val consent = EventCaptureTestState.enabledWithText()
            val (event, room, name) = releasedRoom()
            val member = fx.member()
            val evg = "evg-" + Uuid.random()
            live.seedLive(room = name, identity = member.toString())
            live.seedLive(room = name, identity = evg)
            encounterApp {
                suspend fun start() =
                    client.runAs(member = board) { call ->
                        ConferenceRecordingService(
                            call = call,
                            ffmpegAvailable = true,
                            config = EVENT_ONLINE_CONFIG,
                            recordingConfig = RECORDING_CONFIG,
                            startRecordingRateLimiter =
                                network.lapis.cloud.server.security
                                    .LoginRateLimiter(maxFailures = 100),
                        ).startRecording(roomId = room.toString(), accessLevel = DocumentAccessLevel.BOARD_ONLY)
                    }

                fun rows() = transaction { ConferenceRecordingTable.selectAll().where { ConferenceRecordingTable.roomId eq room }.count() }
                start().failure<EventRoomCaptureConsentMissingException>()
                rows() shouldBe 0
                acknowledgeMember(event, member, consent)
                start().failure<EventRoomCaptureConsentMissingException>()
                acknowledgeGuest(event, evg, consent)
                // LiveKit down: fail closed, still nothing written
                live.failParticipants = true
                start().failure<ServiceBusyException>()
                rows() shouldBe 0
                live.failParticipants = false
                start().getOrThrow().roomId shouldBe room.toString()
                rows() shouldBe 1
            }
        }

        test("the return from a breakout: refused while something captures and the member holds no proof; free otherwise") {
            val consent = EventCaptureTestState.enabledWithText()
            val (event, room, _) = releasedRoom()
            val member = fx.member()
            val current = fx.current(memberId = member)

            fun check() =
                runCatching {
                    transaction {
                        val row = ConferenceRoomTable.selectAll().where { ConferenceRoomTable.id eq room }.single()
                        EventConferenceGuard.requireCaptureConsentWhileCapturing(row = row, current = current)
                    }
                }.exceptionOrNull()

            // nothing captures: the member may come back (the question is then asked in the room)
            check() shouldBe null
            val recording = Uuid.random()
            transaction {
                ConferenceRecordingTable.insert {
                    it[id] = recording
                    it[roomId] = room
                    it[startedByMemberId] = organizer
                    it[startedAt] =
                        network.lapis.cloud.server.db.DbClock
                            .nowLocalDateTime()
                    it[stoppedAt] = null
                    it[readyAt] = null
                    it[status] = network.lapis.cloud.shared.domain.ConferenceRecordingStatus.RECORDING
                    it[accessLevel] = DocumentAccessLevel.BOARD_ONLY
                    it[documentId] = null
                    it[rawDir] = "x"
                    it[durationSeconds] = null
                    it[fileSizeBytes] = null
                    it[failureReason] = null
                    it[composeAttempts] = 0
                }
            }
            (check() is network.lapis.cloud.shared.rpc.EventCaptureNoticeRequiredException) shouldBe true
            acknowledgeMember(event, member, consent)
            check() shouldBe null
            // an ordinary room is not touched at all
            transaction { ConferenceRecordingTable.deleteWhere { ConferenceRecordingTable.id eq recording } }
        }

        test("the guest notice constant of the capture variant is untouched by the gate (the proof is keyed by the identity)") {
            ConferenceGuestConsentAcknowledgmentInput(
                consentVersion = EventGuestNoticeCapture.VERSION,
                consentSha256 = EventGuestNoticeCapture.SHA256,
            ).consentVersion shouldBe
                EventGuestNoticeCapture.VERSION
            EventCaptureAcknowledgments.guestKeyOf(identity = "evg-a", serverSecret = GUEST_SECRET) shouldBe
                EventCaptureAcknowledgments.guestKeyOf(identity = "evg-a", serverSecret = GUEST_SECRET)
        }
    })
