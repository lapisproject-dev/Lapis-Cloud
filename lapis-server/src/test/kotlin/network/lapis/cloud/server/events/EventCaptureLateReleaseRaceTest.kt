package network.lapis.cloud.server.events

import io.kotest.core.annotation.EnabledIf
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.ktor.server.application.ApplicationCall
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import network.lapis.cloud.server.agreementtexts.AgreementTextTestState
import network.lapis.cloud.server.conference.ConferenceAudioGrantState
import network.lapis.cloud.server.conference.ConferenceNotesState
import network.lapis.cloud.server.conference.ConferenceRecordingConfig
import network.lapis.cloud.server.conference.ConferenceWhiteboardState
import network.lapis.cloud.server.db.generated.AuditLogEntryTable
import network.lapis.cloud.server.db.generated.ConferenceParticipationTable
import network.lapis.cloud.server.db.generated.ConferenceRecordingTable
import network.lapis.cloud.server.db.generated.EventTable
import network.lapis.cloud.server.encounter.encounterApp
import network.lapis.cloud.server.encounter.runAs
import network.lapis.cloud.server.federation.FederationInboxRateLimiter
import network.lapis.cloud.server.mail.MailDispatcher
import network.lapis.cloud.server.mail.NoOpMailTransport
import network.lapis.cloud.server.rpc.ConferenceRecordingService
import network.lapis.cloud.server.rpc.ConferenceRoomCloser
import network.lapis.cloud.server.rpc.EventService
import network.lapis.cloud.server.security.LoginRateLimiter
import network.lapis.cloud.server.testdb.PostgresConfigured
import network.lapis.cloud.server.testdb.TestDatabase
import network.lapis.cloud.server.testdb.installLaneGuards
import network.lapis.cloud.server.time.TimeTestSupport
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.AgreementTextKind
import network.lapis.cloud.shared.domain.DocumentAccessLevel
import network.lapis.cloud.shared.domain.EventCaptureAcknowledgmentInput
import network.lapis.cloud.shared.domain.EventOnlineOutcome
import network.lapis.cloud.shared.rpc.EventCaptureNoticeRequiredException
import network.lapis.cloud.shared.rpc.EventCaptureStillCapturingException
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant
import kotlin.uuid.Uuid

private val RECORDING_CONFIG = ConferenceRecordingConfig.load { key -> if (key == "LAPIS_RECORDING_ENABLED") "true" else null }
private const val ROUNDS = 12

/**
 * V1.9.280 -- the races of a release that is switched on or off while people join and a recording starts. The lock order is event row THEN room row for the
 * join and for switching OFF, and the ROOM row only for the start (which reads the event row without locking it). Whatever the interleaving:
 *
 *  - join against start: a recording never runs next to an open participation without a proof (a joiner into a released room carries its proof in the same
 *    transaction);
 *  - release ON against a join without a proof: either the join is refused ([EventCaptureNoticeRequiredException]) or the person is inside and the gate
 *    SEES them as missing -- there is no third state;
 *  - release OFF against the start: at the end either a recording runs and the release is on, or no recording exists -- never "recording and release off";
 *  - no deadlock (the Postgres run asserts the server's deadlock counter did not move; on H2 a deadlock would run into the lock timeout).
 */
abstract class EventCaptureLateReleaseRaceScenarios(
    private val db: TestDatabase,
) : FunSpec({
        val fx = EventOnlineFixtures()
        val live = EventFakeLiveKit()
        val open = Instant.parse("2030-06-15T22:30:00Z")
        lateinit var organizer: Uuid
        lateinit var board: Uuid
        lateinit var services: EventOnlineServices
        lateinit var provisioner: EventConferenceProvisioner

        beforeSpec {
            db.activate()
            TimeTestSupport.setOrganizationZone("America/New_York")
            organizer = fx.member(name = "Veranstalterin")
            board = fx.member(role = AccountRole.BOARD, name = "Vorstand")
            services =
                EventOnlineServices.create(
                    config = EVENT_ONLINE_CONFIG,
                    liveKitAdminClient = live,
                    whiteboardState = ConferenceWhiteboardState(),
                    notesState = ConferenceNotesState(),
                    audioGrantState = ConferenceAudioGrantState(),
                    memberJoinRateLimiter = FederationInboxRateLimiter(maxRequests = 100_000, window = 1.minutes),
                    adminRateLimiter = FederationInboxRateLimiter(maxRequests = 100_000, window = 1.minutes),
                )
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
            EventCaptureConsentGate.install(
                EventCaptureConsentGate.Context(liveKit = live, join = services.join, guestKeySecret = EVENT_ONLINE_CONFIG.apiSecret),
            )
        }
        installLaneGuards(db = db, checkDeadlocks = true)
        afterEach { EventCaptureTestState.reset() }
        afterSpec {
            EventCaptureConsentGate.install(null)
            transaction {
                val roomIds = fx.eventIds.flatMap { fx.roomIdsOf(it) }
                if (roomIds.isNotEmpty()) ConferenceRecordingTable.deleteWhere { ConferenceRecordingTable.roomId inList roomIds }
            }
            // the audit rows name fixture members as actors: on the shared H2 they are detached first (the Postgres database is dropped as a whole)
            if (db is TestDatabase.H2) {
                transaction {
                    AuditLogEntryTable.update({ AuditLogEntryTable.actorMemberId inList fx.memberIds }) { it[actorMemberId] = null }
                }
            }
            // the Postgres database of this spec is dropped as a whole; the shared H2 is cleaned row by row
            if (db is TestDatabase.H2) fx.cleanUp()
            EventCaptureTestState.reset()
            TimeTestSupport.resetOrganizationZone()
            db.deactivate()
        }

        /** The instance switch on and a published text; the author is a fixture member (neither lane database is seeded). */
        fun enableWithText(): EventCaptureAcknowledgmentInput {
            EventCaptureTestState.instance(enabled = true)
            val sha =
                AgreementTextTestState.publishDirect(
                    kind = AgreementTextKind.EVENT_REC,
                    label = EVENT_CAPTURE_TEST_LABEL,
                    body = EVENT_CAPTURE_TEST_BODY,
                    authorMemberId = board,
                )
            return EventCaptureAcknowledgmentInput(consentVersion = EVENT_CAPTURE_TEST_LABEL, consentSha256 = sha)
        }

        fun eventService(call: ApplicationCall) =
            EventService(
                call = call,
                checkoutGateways = emptyMap(),
                baseUrl = "https://example.org",
                mailDispatcher = MailDispatcher(transport = NoOpMailTransport(), scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)),
                writeRateLimiter = FederationInboxRateLimiter(maxRequests = 100_000, window = 1.minutes),
                checkInRateLimiter = FederationInboxRateLimiter(maxRequests = 100_000, window = 1.minutes),
                seriesPreviewRateLimiter = FederationInboxRateLimiter(maxRequests = 100_000, window = 1.minutes),
                eventOnline = services,
            )

        fun recordingService(call: ApplicationCall) =
            ConferenceRecordingService(
                call = call,
                ffmpegAvailable = true,
                config = EVENT_ONLINE_CONFIG,
                recordingConfig = RECORDING_CONFIG,
                startRecordingRateLimiter = LoginRateLimiter(maxFailures = 100_000),
            )

        /** An event with a living room (the organizer joined first, so the room exists); returns (event, room). */
        fun eventWithRoom(released: Boolean): Pair<Uuid, Uuid> {
            val event = fx.event(createdBy = organizer, captureAllowed = released)
            val ready = runBlocking { provisioner.ensureRoom(eventId = event, now = open) } as EventConferenceProvisioner.Ready.Room
            return event to ready.roomId
        }

        fun recordings(room: Uuid) =
            transaction { ConferenceRecordingTable.selectAll().where { ConferenceRecordingTable.roomId eq room }.count() }

        fun released(event: Uuid) =
            transaction { EventTable.selectAll().where { EventTable.id eq event }.single()[EventTable.captureAllowed] }

        fun openParticipants(room: Uuid) =
            transaction {
                ConferenceParticipationTable
                    .selectAll()
                    .where { (ConferenceParticipationTable.roomId eq room) and ConferenceParticipationTable.leftAt.isNull() }
                    .map { it[ConferenceParticipationTable.memberId] }
            }

        fun missing(
            event: Uuid,
            room: Uuid,
        ) = transaction {
            EventCaptureConsentGate
                .count(eventId = event, roomId = room, present = EventRoomPresence.EMPTY, guestKeySecret = EVENT_ONLINE_CONFIG.apiSecret)
                .missingIdentities
        }

        test("join against start: a recording never runs next to an open participation without a proof") {
            val consent = enableWithText()
            TimeTestSupport.withServerClock(instant = "2030-06-15T22:30:00Z") {
                repeat(ROUNDS) {
                    val (event, room) = eventWithRoom(released = true)
                    val joiner = fx.member()
                    encounterApp {
                        val results =
                            coroutineScope {
                                listOf(
                                    async(Dispatchers.IO) {
                                        runCatching {
                                            services.join!!.joinMember(
                                                eventId = event,
                                                current = fx.current(memberId = joiner),
                                                guestConsent = null,
                                                captureConsent = consent,
                                                now = open,
                                            )
                                        }
                                    },
                                    async(Dispatchers.IO) {
                                        client.runAs(member = board) { call ->
                                            recordingService(
                                                call,
                                            ).startRecording(roomId = room.toString(), accessLevel = DocumentAccessLevel.BOARD_ONLY)
                                        }
                                    },
                                ).awaitAll()
                            }
                        // whatever happened: if a recording runs, nobody who is inside lacks the proof
                        if (recordings(room) > 0) missing(event, room) shouldBe emptySet()
                        (results[0] as Result<*>).isSuccess shouldBe true
                    }
                }
            }
        }

        test("release ON against a join without a proof: refused at the door, or inside and visible to the gate -- no third state") {
            val consent = enableWithText()
            TimeTestSupport.withServerClock(instant = "2030-06-15T22:30:00Z") {
                repeat(ROUNDS) {
                    val (event, room) = eventWithRoom(released = false)
                    val joiner = fx.member()
                    encounterApp {
                        val (join, release) =
                            coroutineScope {
                                val j =
                                    async(Dispatchers.IO) {
                                        runCatching {
                                            services.join!!.joinMember(
                                                eventId = event,
                                                current = fx.current(memberId = joiner),
                                                guestConsent = null,
                                                captureConsent = null,
                                                now = open,
                                            )
                                        }
                                    }
                                val r =
                                    async(Dispatchers.IO) {
                                        client.runAs(
                                            member = board,
                                        ) { call -> eventService(call).setEventCaptureAllowed(eventId = event.toString(), allowed = true) }
                                    }
                                j.await() to r.await()
                            }
                        release.isSuccess shouldBe true
                        released(event) shouldBe true
                        val insideNow = openParticipants(room).contains(joiner)
                        if (join.isSuccess) {
                            join.getOrThrow().outcome shouldBe EventOnlineOutcome.JOINED
                            insideNow shouldBe true
                            missing(event, room) shouldBe setOf(joiner.toString())
                        } else {
                            (join.exceptionOrNull() is EventCaptureNoticeRequiredException) shouldBe true
                            insideNow shouldBe false
                        }
                        // the proof is still possible afterwards and clears the gate
                        if (insideNow) {
                            runBlocking {
                                services.join!!.joinMember(
                                    eventId = event,
                                    current = fx.current(memberId = joiner),
                                    guestConsent = null,
                                    captureConsent = consent,
                                    now = open,
                                )
                            }
                            missing(event, room) shouldBe emptySet()
                        }
                    }
                }
            }
        }

        test("release OFF against the start: never 'recording and release off'") {
            enableWithText()
            TimeTestSupport.withServerClock(instant = "2030-06-15T22:30:00Z") {
                repeat(ROUNDS) {
                    val (event, room) = eventWithRoom(released = true)
                    encounterApp {
                        val (off, start) =
                            coroutineScope {
                                val o =
                                    async(Dispatchers.IO) {
                                        client.runAs(
                                            member = board,
                                        ) { call -> eventService(call).setEventCaptureAllowed(eventId = event.toString(), allowed = false) }
                                    }
                                val s =
                                    async(Dispatchers.IO) {
                                        client.runAs(member = board) { call ->
                                            recordingService(
                                                call,
                                            ).startRecording(roomId = room.toString(), accessLevel = DocumentAccessLevel.BOARD_ONLY)
                                        }
                                    }
                                o.await() to s.await()
                            }
                        val recordingRuns = recordings(room) > 0
                        if (recordingRuns) {
                            // the start won: the release stays on and switching it off was refused
                            released(event) shouldBe true
                            (off.exceptionOrNull() is EventCaptureStillCapturingException) shouldBe true
                        } else {
                            // switching off won: no recording, the release is off
                            released(event) shouldBe false
                            off.isSuccess shouldBe true
                            start.isFailure shouldBe true
                        }
                    }
                }
            }
        }
    })

class EventCaptureLateReleaseRaceTest : EventCaptureLateReleaseRaceScenarios(TestDatabase.H2)

@Tags("Postgres")
@EnabledIf(PostgresConfigured::class)
class EventCaptureLateReleaseRacePostgresTest : EventCaptureLateReleaseRaceScenarios(TestDatabase.Postgres())
