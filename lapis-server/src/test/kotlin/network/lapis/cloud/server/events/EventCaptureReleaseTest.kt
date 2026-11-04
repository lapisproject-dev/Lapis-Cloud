package network.lapis.cloud.server.events

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import io.ktor.server.application.ApplicationCall
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import network.lapis.cloud.server.conference.ConferenceAudioGrantState
import network.lapis.cloud.server.conference.ConferenceNotesState
import network.lapis.cloud.server.conference.ConferenceWhiteboardState
import network.lapis.cloud.server.db.DatabaseConfig
import network.lapis.cloud.server.db.DevSeedData
import network.lapis.cloud.server.db.generated.AuditLogEntryTable
import network.lapis.cloud.server.db.generated.ConferenceRecordingTable
import network.lapis.cloud.server.db.generated.ConferenceStreamTable
import network.lapis.cloud.server.encounter.encounterApp
import network.lapis.cloud.server.encounter.failure
import network.lapis.cloud.server.encounter.runAs
import network.lapis.cloud.server.federation.FederationInboxRateLimiter
import network.lapis.cloud.server.mail.MailDispatcher
import network.lapis.cloud.server.mail.NoOpMailTransport
import network.lapis.cloud.server.rpc.EventService
import network.lapis.cloud.server.time.TimeTestSupport
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.AuditAction
import network.lapis.cloud.shared.domain.AuditEntityType
import network.lapis.cloud.shared.domain.ConferenceRecordingStatus
import network.lapis.cloud.shared.domain.ConferenceStreamLatencyMode
import network.lapis.cloud.shared.domain.ConferenceStreamLayout
import network.lapis.cloud.shared.domain.ConferenceStreamStatus
import network.lapis.cloud.shared.domain.EventCaptureChangeLock
import network.lapis.cloud.shared.domain.EventOnlineKind
import network.lapis.cloud.shared.rpc.BadRequestException
import network.lapis.cloud.shared.rpc.EventCaptureChangeLockedException
import network.lapis.cloud.shared.rpc.EventCaptureStillCapturingException
import network.lapis.cloud.shared.rpc.ForbiddenException
import network.lapis.cloud.shared.rpc.NotFoundException
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * V1.9.156 -- the RELEASE of recording / live streaming of one event ([EventService.setEventCaptureAllowed]): BOARD/ADMIN only, only while the instance
 * switch is on, only for a Lapis conference. Since V1.9.280 switching it ON works at any time (also after the start); switching it OFF is refused only
 * while a recording or stream runs. Audited with the event id, the value and the flag "afterStart", nothing else. A directly inserted event is not released
 * (the default for NEW events lives in `EventCaptureDefaults`, see `EventCaptureDefaultTest`).
 */
class EventCaptureReleaseTest :
    FunSpec({
        val fx = EventOnlineFixtures()
        val live = EventFakeLiveKit()
        lateinit var organizer: Uuid
        lateinit var board: Uuid
        lateinit var admin: Uuid
        lateinit var services: EventOnlineServices

        // 2030-06-15 18:00-20:00 New York; the window is 17:30 .. 21:00 local = 21:30Z .. 01:00Z.
        val before = "2030-06-15T10:00:00Z"
        val inside = "2030-06-15T22:30:00Z"

        beforeSpec {
            DatabaseConfig.connect()
            DevSeedData.seedIfEmpty(force = true)
            TimeTestSupport.setOrganizationZone("America/New_York")
            organizer = fx.member(name = "Veranstalterin")
            // seeded actors: an audit entry references its actor and is never deleted, so fixture members could not be cleaned up afterwards
            board = Uuid.parse("00000000-0000-0000-0000-000000000002")
            admin = Uuid.parse("00000000-0000-0000-0000-000000000001")
            services =
                EventOnlineServices.create(
                    config = EVENT_ONLINE_CONFIG,
                    liveKitAdminClient = live,
                    whiteboardState = ConferenceWhiteboardState(),
                    notesState = ConferenceNotesState(),
                    audioGrantState = ConferenceAudioGrantState(),
                    memberJoinRateLimiter = FederationInboxRateLimiter(maxRequests = 1000, window = 1.minutes),
                    adminRateLimiter = FederationInboxRateLimiter(maxRequests = 1000, window = 1.minutes),
                )
        }
        afterEach { EventCaptureTestState.reset() }
        afterSpec {
            transaction {
                val roomIds = fx.eventIds.flatMap { fx.roomIdsOf(it) }
                if (roomIds.isNotEmpty()) ConferenceRecordingTable.deleteWhere { roomId inList roomIds }
            }
            fx.cleanUp()
            EventCaptureTestState.reset()
            TimeTestSupport.resetOrganizationZone()
        }

        fun service(call: ApplicationCall) =
            EventService(
                call = call,
                checkoutGateways = emptyMap(),
                baseUrl = "https://example.org",
                mailDispatcher = MailDispatcher(transport = NoOpMailTransport(), scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)),
                writeRateLimiter = FederationInboxRateLimiter(maxRequests = 1000, window = 1.minutes),
                checkInRateLimiter = FederationInboxRateLimiter(maxRequests = 1000, window = 1.minutes),
                seriesPreviewRateLimiter = FederationInboxRateLimiter(maxRequests = 1000, window = 1.minutes),
                eventOnline = services,
            )

        fun auditOf(event: Uuid) =
            transaction {
                AuditLogEntryTable
                    .selectAll()
                    .where { (AuditLogEntryTable.entityType eq AuditEntityType.EVENT_CAPTURE) and (AuditLogEntryTable.entityId eq event) }
                    .toList()
            }

        fun released(event: Uuid) =
            transaction {
                network.lapis.cloud.server.db.generated.EventTable
                    .selectAll()
                    .where { network.lapis.cloud.server.db.generated.EventTable.id eq event }
                    .single()[network.lapis.cloud.server.db.generated.EventTable.captureAllowed]
            }

        test("the default is 'not released'") {
            val event = fx.event(createdBy = organizer)
            released(event) shouldBe false
        }

        test("only BOARD and ADMIN may change the release; a member and the organizer without a role may not") {
            EventCaptureTestState.enabledWithText()
            val event = fx.event(createdBy = organizer)
            TimeTestSupport.withServerClock(instant = before) {
                encounterApp {
                    listOf(fx.member(), organizer, fx.member(role = AccountRole.TREASURER)).forEach { who ->
                        client
                            .runAs(
                                member = who,
                            ) { call -> service(call).setEventCaptureAllowed(eventId = event.toString(), allowed = true) }
                            .failure<ForbiddenException>()
                    }
                    released(event) shouldBe false
                    client
                        .runAs(member = board) { call -> service(call).setEventCaptureAllowed(eventId = event.toString(), allowed = true) }
                        .getOrThrow()
                        .allowed shouldBe true
                    client
                        .runAs(member = admin) { call -> service(call).setEventCaptureAllowed(eventId = event.toString(), allowed = false) }
                        .getOrThrow()
                        .allowed shouldBe false
                }
            }
        }

        test("with the instance switch off a release is refused and the value stays; withdrawing an unreleased event is a no-op") {
            val event = fx.event(createdBy = organizer)
            TimeTestSupport.withServerClock(instant = before) {
                encounterApp {
                    client
                        .runAs(member = board) { call -> service(call).setEventCaptureAllowed(eventId = event.toString(), allowed = true) }
                        .failure<EventCaptureChangeLockedException>()
                    released(event) shouldBe false
                    client
                        .runAs(member = board) { call -> service(call).setEventCaptureAllowed(eventId = event.toString(), allowed = false) }
                        .getOrThrow()
                        .allowed shouldBe false
                    auditOf(event).shouldBeEmpty()
                }
            }
        }

        test("only a Lapis conference can be released") {
            EventCaptureTestState.enabledWithText()
            val onSite = fx.event(createdBy = organizer, kind = EventOnlineKind.NONE)
            TimeTestSupport.withServerClock(instant = before) {
                encounterApp {
                    client
                        .runAs(member = board) { call -> service(call).setEventCaptureAllowed(eventId = onSite.toString(), allowed = true) }
                        .failure<BadRequestException>()
                    released(onSite) shouldBe false
                }
            }
        }

        test("a release is audited with the event id and the value -- no name, no participant") {
            EventCaptureTestState.enabledWithText()
            val event = fx.event(createdBy = organizer)
            TimeTestSupport.withServerClock(instant = before) {
                encounterApp {
                    client
                        .runAs(
                            member = board,
                        ) { call -> service(call).setEventCaptureAllowed(eventId = event.toString(), allowed = true) }
                        .getOrThrow()
                    val entries = auditOf(event)
                    entries.size shouldBe 1
                    val entry = entries.single()
                    entry[AuditLogEntryTable.action] shouldBe AuditAction.UPDATE
                    entry[AuditLogEntryTable.actorMemberId] shouldBe board
                    entry[AuditLogEntryTable.beforeSnapshot] shouldBe "{\"eventId\":\"$event\",\"captureAllowed\":false}"
                    entry[AuditLogEntryTable.afterSnapshot] shouldBe
                        "{\"eventId\":\"$event\",\"captureAllowed\":true,\"afterStart\":false}"
                    entry[AuditLogEntryTable.afterSnapshot]!! shouldNotContain "Online-Event"
                    // the same value again writes nothing
                    client
                        .runAs(
                            member = board,
                        ) { call -> service(call).setEventCaptureAllowed(eventId = event.toString(), allowed = true) }
                        .getOrThrow()
                    auditOf(event).size shouldBe 1
                }
            }
        }

        test("V1.9.280: switching ON works while the window is open (after the start); switching OFF works as long as nothing captures") {
            EventCaptureTestState.enabledWithText()
            val free = fx.event(createdBy = organizer)
            val granted = fx.event(createdBy = organizer, captureAllowed = true)
            TimeTestSupport.withServerClock(instant = inside) {
                encounterApp {
                    val onState =
                        client
                            .runAs(
                                member = board,
                            ) { call -> service(call).setEventCaptureAllowed(eventId = free.toString(), allowed = true) }
                            .getOrThrow()
                    onState.allowed shouldBe true
                    // the window is open, so the form is told that people inside are asked
                    onState.roomOpen shouldBe true
                    onState.changeLock shouldBe EventCaptureChangeLock.NONE
                    onState.mayChange shouldBe true
                    client
                        .runAs(
                            member = board,
                        ) { call -> service(call).setEventCaptureAllowed(eventId = granted.toString(), allowed = false) }
                        .getOrThrow()
                        .allowed shouldBe false
                    released(free) shouldBe true
                    released(granted) shouldBe false
                    // the audit carries the flag "afterStart" -- and still no name
                    val entry = auditOf(free).single()
                    entry[AuditLogEntryTable.afterSnapshot] shouldBe "{\"eventId\":\"$free\",\"captureAllowed\":true,\"afterStart\":true}"
                    entry[AuditLogEntryTable.afterSnapshot]!! shouldNotContain "Online-Event"
                }
            }
        }

        test("V1.9.280: before the window the audit says afterStart:false; with the switch off a release is still refused") {
            EventCaptureTestState.enabledWithText()
            val event = fx.event(createdBy = organizer)
            TimeTestSupport.withServerClock(instant = "2030-06-15T10:00:00Z") {
                encounterApp {
                    client
                        .runAs(member = board) { call -> service(call).setEventCaptureAllowed(eventId = event.toString(), allowed = true) }
                        .getOrThrow()
                    auditOf(event).single()[AuditLogEntryTable.afterSnapshot] shouldBe
                        "{\"eventId\":\"$event\",\"captureAllowed\":true,\"afterStart\":false}"
                    EventCaptureTestState.instance(enabled = false)
                    val other = fx.event(createdBy = organizer)
                    client
                        .runAs(member = board) { call -> service(call).setEventCaptureAllowed(eventId = other.toString(), allowed = true) }
                        .failure<EventCaptureChangeLockedException>()
                }
            }
        }

        test("V1.9.280: a living room does not freeze the release; only a running recording or an unfinished stream blocks switching OFF") {
            EventCaptureTestState.enabledWithText()
            val event = fx.event(createdBy = organizer, captureAllowed = true)
            val roomId =
                TimeTestSupport.withServerClock(instant = inside) {
                    val provisioner =
                        EventConferenceProvisioner(
                            config = EVENT_ONLINE_CONFIG,
                            liveKit = live,
                            closer =
                                network.lapis.cloud.server.rpc.ConferenceRoomCloser(
                                    liveKitAdminClient = live,
                                    whiteboardState = ConferenceWhiteboardState(),
                                    notesState = ConferenceNotesState(),
                                    audioGrantState = ConferenceAudioGrantState(),
                                ),
                            retryDelay = 20.milliseconds,
                            maxWaits = 20,
                        )
                    (
                        runBlocking {
                            provisioner.ensureRoom(
                                eventId = event,
                                now = Instant.parse(inside),
                            )
                        } as EventConferenceProvisioner.Ready.Room
                    ).roomId
                }
            TimeTestSupport.withServerClock(instant = before) {
                encounterApp {
                    suspend fun set(allowed: Boolean) =
                        client.runAs(member = board) { call ->
                            service(call).setEventCaptureAllowed(eventId = event.toString(), allowed = allowed)
                        }

                    suspend fun lockOf() =
                        client
                            .runAs(member = board) { call -> service(call).getEventCaptureState(event.toString()) }
                            .getOrThrow()
                            .changeLock

                    // the room is alive and nothing captures: off and on again both work
                    set(false).getOrThrow().allowed shouldBe false
                    set(true).getOrThrow().allowed shouldBe true
                    lockOf() shouldBe EventCaptureChangeLock.NONE
                    // a recording runs
                    transaction {
                        ConferenceRecordingTable.insert {
                            it[id] = Uuid.random()
                            it[ConferenceRecordingTable.roomId] = roomId
                            it[startedByMemberId] = board
                            it[startedAt] =
                                network.lapis.cloud.server.db.DbClock
                                    .nowLocalDateTime()
                            it[stoppedAt] = null
                            it[readyAt] = null
                            it[status] = ConferenceRecordingStatus.RECORDING
                            it[accessLevel] = network.lapis.cloud.shared.domain.DocumentAccessLevel.BOARD_ONLY
                            it[documentId] = null
                            it[rawDir] = "x"
                            it[durationSeconds] = null
                            it[fileSizeBytes] = null
                            it[failureReason] = null
                            it[composeAttempts] = 0
                        }
                    }
                    lockOf() shouldBe EventCaptureChangeLock.CAPTURE_RUNNING
                    set(false).failure<EventCaptureStillCapturingException>()
                    released(event) shouldBe true
                    // switching on while already on is a no-op, not a refusal
                    set(true).getOrThrow().allowed shouldBe true
                    // the recording stopped: off works again
                    transaction { ConferenceRecordingTable.deleteWhere { ConferenceRecordingTable.roomId eq roomId } }
                    set(false).getOrThrow().allowed shouldBe false
                }
            }
        }

        test("V1.9.280: an unfinished stream (LIVE or PAUSED) blocks switching OFF, an ended one does not") {
            EventCaptureTestState.enabledWithText()
            val event = fx.event(createdBy = organizer, captureAllowed = true)
            val roomId =
                TimeTestSupport.withServerClock(instant = inside) {
                    val provisioner =
                        EventConferenceProvisioner(
                            config = EVENT_ONLINE_CONFIG,
                            liveKit = live,
                            closer =
                                network.lapis.cloud.server.rpc.ConferenceRoomCloser(
                                    liveKitAdminClient = live,
                                    whiteboardState = ConferenceWhiteboardState(),
                                    notesState = ConferenceNotesState(),
                                    audioGrantState = ConferenceAudioGrantState(),
                                ),
                            retryDelay = 20.milliseconds,
                            maxWaits = 20,
                        )
                    (
                        runBlocking {
                            provisioner.ensureRoom(
                                eventId = event,
                                now = Instant.parse(inside),
                            )
                        } as EventConferenceProvisioner.Ready.Room
                    ).roomId
                }
            val streamId = Uuid.random()
            try {
                TimeTestSupport.withServerClock(instant = before) {
                    encounterApp {
                        for (status in listOf(ConferenceStreamStatus.LIVE, ConferenceStreamStatus.PAUSED)) {
                            transaction {
                                ConferenceStreamTable.insert {
                                    it[id] = streamId
                                    it[ConferenceStreamTable.roomId] = roomId
                                    it[startedByMemberId] = board
                                    it[startedAt] =
                                        network.lapis.cloud.server.db.DbClock
                                            .nowLocalDateTime()
                                    it[ConferenceStreamTable.status] = status
                                    it[layout] = ConferenceStreamLayout.GRID
                                    it[latencyMode] = ConferenceStreamLatencyMode.STANDARD
                                }
                            }
                            client
                                .runAs(
                                    member = board,
                                ) { call -> service(call).setEventCaptureAllowed(eventId = event.toString(), allowed = false) }
                                .failure<EventCaptureStillCapturingException>()
                            transaction { ConferenceStreamTable.deleteWhere { ConferenceStreamTable.id eq streamId } }
                        }
                        client
                            .runAs(
                                member = board,
                            ) { call -> service(call).setEventCaptureAllowed(eventId = event.toString(), allowed = false) }
                            .getOrThrow()
                            .allowed shouldBe false
                    }
                }
            } finally {
                transaction { ConferenceStreamTable.deleteWhere { ConferenceStreamTable.id eq streamId } }
            }
        }

        test("getEventCaptureState: a participant sees the consent text only when it is required; the lock reason is for the board") {
            val consent = EventCaptureTestState.enabledWithText()
            val event = fx.event(createdBy = organizer, captureAllowed = true)
            val plain = fx.event(createdBy = organizer)
            TimeTestSupport.withServerClock(instant = before) {
                encounterApp {
                    val member = fx.member()
                    val asMember =
                        client
                            .runAs(
                                member = member,
                            ) { call -> service(call).getEventCaptureState(event.toString()) }
                            .getOrThrow()
                    asMember.noticeRequired shouldBe true
                    asMember.notice!!.sha256 shouldBe consent.consentSha256
                    asMember.notice!!.versionLabel shouldBe consent.consentVersion
                    asMember.notice!!.headline shouldBe "Aufzeichnung im Saal"
                    asMember.notice!!.keyPoints.size shouldBe 3
                    asMember.changeLock shouldBe EventCaptureChangeLock.NOT_PRIVILEGED
                    asMember.mayChange shouldBe false
                    val notRequired =
                        client
                            .runAs(
                                member = member,
                            ) { call -> service(call).getEventCaptureState(plain.toString()) }
                            .getOrThrow()
                    notRequired.noticeRequired shouldBe false
                    notRequired.notice shouldBe null
                    val asBoard = client.runAs(member = board) { call -> service(call).getEventCaptureState(plain.toString()) }.getOrThrow()
                    asBoard.changeLock shouldBe EventCaptureChangeLock.NONE
                    asBoard.mayChange shouldBe true
                    EventCaptureTestState.instance(enabled = false)
                    client
                        .runAs(member = board) { call -> service(call).getEventCaptureState(plain.toString()) }
                        .getOrThrow()
                        .changeLock shouldBe EventCaptureChangeLock.INSTANCE_OFF
                    // an unknown event is the same refusal as everywhere
                    client
                        .runAs(member = member) { call -> service(call).getEventCaptureState(Uuid.random().toString()) }
                        .failure<NotFoundException>()
                }
            }
        }
    })
