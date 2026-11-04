package network.lapis.cloud.server.events

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.ktor.server.application.ApplicationCall
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import network.lapis.cloud.server.conference.ConferenceAudioGrantState
import network.lapis.cloud.server.conference.ConferenceNotesState
import network.lapis.cloud.server.conference.ConferenceWhiteboardState
import network.lapis.cloud.server.db.DatabaseConfig
import network.lapis.cloud.server.db.DbClock
import network.lapis.cloud.server.db.DevSeedData
import network.lapis.cloud.server.db.generated.ConferenceParticipationTable
import network.lapis.cloud.server.db.generated.ConferenceRoomTable
import network.lapis.cloud.server.db.generated.EventCaptureAcknowledgmentTable
import network.lapis.cloud.server.encounter.encounterApp
import network.lapis.cloud.server.encounter.failure
import network.lapis.cloud.server.encounter.runAs
import network.lapis.cloud.server.federation.FederationInboxRateLimiter
import network.lapis.cloud.server.mail.MailDispatcher
import network.lapis.cloud.server.mail.NoOpMailTransport
import network.lapis.cloud.server.rpc.EventService
import network.lapis.cloud.server.time.TimeTestSupport
import network.lapis.cloud.shared.domain.EventOnlineOutcome
import network.lapis.cloud.shared.rpc.EventCaptureNoticeOutdatedException
import network.lapis.cloud.shared.rpc.EventCaptureNoticeRequiredException
import network.lapis.cloud.shared.rpc.EventCaptureTextMissingException
import network.lapis.cloud.shared.rpc.ForbiddenException
import network.lapis.cloud.shared.rpc.ServiceBusyException
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * V1.9.280 -- `IEventService.acknowledgeEventCaptureInRoom`: a member (or a guest with an account) who is INSIDE the event room acknowledges the consent that
 * was switched on after they entered. Needs an open participation, refuses a stale text, is throttled, is idempotent and writes the nameless proof. "Ablehnen"
 * has no server call: the participation simply ends and the count falls. Whoever joins later still needs the consent at the door.
 */
class EventCaptureInRoomConsentTest :
    FunSpec({
        val fx = EventOnlineFixtures()
        val live = EventFakeLiveKit()
        val open = Instant.parse("2030-06-15T22:30:00Z")
        lateinit var organizer: Uuid
        lateinit var services: EventOnlineServices

        fun buildServices(joinLimit: Int) =
            EventOnlineServices.create(
                config = EVENT_ONLINE_CONFIG,
                liveKitAdminClient = live,
                whiteboardState = ConferenceWhiteboardState(),
                notesState = ConferenceNotesState(),
                audioGrantState = ConferenceAudioGrantState(),
                memberJoinRateLimiter = FederationInboxRateLimiter(maxRequests = joinLimit, window = 1.minutes),
                adminRateLimiter = FederationInboxRateLimiter(maxRequests = 1000, window = 1.minutes),
            )

        beforeSpec {
            DatabaseConfig.connect()
            DevSeedData.seedIfEmpty(force = true)
            TimeTestSupport.setOrganizationZone("America/New_York")
            organizer = fx.member(name = "Veranstalterin")
            services = buildServices(joinLimit = 1000)
        }
        afterEach { EventCaptureTestState.reset() }
        afterSpec {
            fx.cleanUp()
            EventCaptureTestState.reset()
            TimeTestSupport.resetOrganizationZone()
        }

        fun service(
            call: ApplicationCall,
            online: EventOnlineServices = services,
        ) = EventService(
            call = call,
            checkoutGateways = emptyMap(),
            baseUrl = "https://example.org",
            mailDispatcher = MailDispatcher(transport = NoOpMailTransport(), scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)),
            writeRateLimiter = FederationInboxRateLimiter(maxRequests = 1000, window = 1.minutes),
            checkInRateLimiter = FederationInboxRateLimiter(maxRequests = 1000, window = 1.minutes),
            seriesPreviewRateLimiter = FederationInboxRateLimiter(maxRequests = 1000, window = 1.minutes),
            eventOnline = online,
        )

        /** [who] joins the not yet released event (so no consent was asked), then the release is switched on: the state this wave is about. */
        fun joinedThenReleased(
            who: Uuid,
            event: Uuid,
        ) {
            val result =
                runBlocking {
                    services.join!!.joinMember(
                        eventId = event,
                        current = fx.current(memberId = who),
                        guestConsent = null,
                        now = open,
                    )
                }
            result.outcome shouldBe EventOnlineOutcome.JOINED
        }

        fun proofs(event: Uuid) = EventCaptureTestState.acknowledgments(event)

        test("somebody without an open participation in the room is refused; so is a stranger to an unknown event") {
            val consent = EventCaptureTestState.enabledWithText()
            val event = fx.event(createdBy = organizer)
            TimeTestSupport.withServerClock(instant = "2030-06-15T22:30:00Z") {
                // the room must exist for the question to make sense
                runBlocking {
                    services.join!!.joinMember(
                        eventId = event,
                        current = fx.current(memberId = fx.member()),
                        guestConsent = null,
                        now = open,
                    )
                }
                EventCaptureTestState.release(eventId = event)
                encounterApp {
                    val outsider = fx.member()
                    client
                        .runAs(
                            member = outsider,
                        ) { call -> service(call).acknowledgeEventCaptureInRoom(eventId = event.toString(), consent = consent) }
                        .failure<ForbiddenException>()
                    client
                        .runAs(
                            member = outsider,
                        ) { call -> service(call).acknowledgeEventCaptureInRoom(eventId = Uuid.random().toString(), consent = consent) }
                        .failure<ForbiddenException>()
                    proofs(event).size shouldBe 0
                }
            }
        }

        test("a member inside acknowledges: the proof is written once, a second call is idempotent, the state comes back") {
            val consent = EventCaptureTestState.enabledWithText()
            val event = fx.event(createdBy = organizer)
            val who = fx.member(name = "Erika Mustermann")
            TimeTestSupport.withServerClock(instant = "2030-06-15T22:30:00Z") {
                joinedThenReleased(who, event)
                EventCaptureTestState.release(eventId = event)
                encounterApp {
                    val state =
                        client
                            .runAs(
                                member = who,
                            ) { call -> service(call).acknowledgeEventCaptureInRoom(eventId = event.toString(), consent = consent) }
                            .getOrThrow()
                    state.noticeRequired shouldBe true
                    client
                        .runAs(
                            member = who,
                        ) { call -> service(call).acknowledgeEventCaptureInRoom(eventId = event.toString(), consent = consent) }
                        .getOrThrow()
                    val rows = proofs(event)
                    rows.size shouldBe 1
                    rows.single()[EventCaptureAcknowledgmentTable.memberId] shouldBe who
                    rows.single()[EventCaptureAcknowledgmentTable.consentSha256] shouldBe consent.consentSha256
                    rows.single().toString().contains("Erika") shouldBe false
                }
            }
        }

        test("a stale or missing text is refused and nothing is written") {
            val consent = EventCaptureTestState.enabledWithText()
            val event = fx.event(createdBy = organizer)
            val who = fx.member()
            TimeTestSupport.withServerClock(instant = "2030-06-15T22:30:00Z") {
                joinedThenReleased(who, event)
                EventCaptureTestState.release(eventId = event)
                encounterApp {
                    client
                        .runAs(member = who) { call ->
                            service(
                                call,
                            ).acknowledgeEventCaptureInRoom(
                                eventId = event.toString(),
                                consent = consent.copy(consentSha256 = "0".repeat(64)),
                            )
                        }.failure<EventCaptureNoticeOutdatedException>()
                    client
                        .runAs(member = who) { call ->
                            service(
                                call,
                            ).acknowledgeEventCaptureInRoom(
                                eventId = event.toString(),
                                consent = consent.copy(consentVersion = "2026-01-01.other"),
                            )
                        }.failure<EventCaptureNoticeOutdatedException>()
                    proofs(event).size shouldBe 0
                    // no text published at all: the placeholder is never a basis
                    EventCaptureTestState.reset()
                    EventCaptureTestState.instance(enabled = true)
                    client
                        .runAs(
                            member = who,
                        ) { call -> service(call).acknowledgeEventCaptureInRoom(eventId = event.toString(), consent = consent) }
                        .failure<EventCaptureTextMissingException>()
                    proofs(event).size shouldBe 0
                }
            }
        }

        test("the caller is throttled: ServiceBusy") {
            val consent = EventCaptureTestState.enabledWithText()
            val event = fx.event(createdBy = organizer)
            val who = fx.member()
            val tight = buildServices(joinLimit = 2)
            TimeTestSupport.withServerClock(instant = "2030-06-15T22:30:00Z") {
                joinedThenReleased(who, event)
                EventCaptureTestState.release(eventId = event)
                encounterApp {
                    val results =
                        (1..4).map {
                            client.runAs(
                                member = who,
                            ) { call -> service(call, tight).acknowledgeEventCaptureInRoom(eventId = event.toString(), consent = consent) }
                        }
                    results.last().failure<ServiceBusyException>()
                }
            }
        }

        test("declining is leaving: the participation ends, the count falls by one; a later joiner still needs the consent at the door") {
            val consent = EventCaptureTestState.enabledWithText()
            val event = fx.event(createdBy = organizer)
            val stays = fx.member()
            val leaves = fx.member()
            TimeTestSupport.withServerClock(instant = "2030-06-15T22:30:00Z") {
                joinedThenReleased(stays, event)
                joinedThenReleased(leaves, event)
                EventCaptureTestState.release(eventId = event)
                val room = transaction { EventCaptureLock.roomIdOf(event)!! }

                fun total() =
                    transaction {
                        EventCaptureConsentGate
                            .count(
                                eventId = event,
                                roomId = room,
                                present = EventRoomPresence.EMPTY,
                                guestKeySecret = EVENT_ONLINE_CONFIG.apiSecret,
                            )
                    }
                total().total shouldBe 2
                transaction {
                    ConferenceParticipationTable.update({
                        (ConferenceParticipationTable.roomId eq room) and
                            (ConferenceParticipationTable.memberId eq leaves) and
                            ConferenceParticipationTable.leftAt.isNull()
                    }) { it[leftAt] = DbClock.nowLocalDateTime() }
                }
                total().total shouldBe 1
                total().missingIdentities shouldBe setOf(stays.toString())
                // somebody who comes later is stopped at the door
                val late = fx.member()
                val refused =
                    runCatching {
                        runBlocking {
                            services.join!!.joinMember(
                                eventId = event,
                                current = fx.current(memberId = late),
                                guestConsent = null,
                                captureConsent = null,
                                now = open,
                            )
                        }
                    }
                (refused.exceptionOrNull() is EventCaptureNoticeRequiredException) shouldBe true
                // ... and let in with the consent
                runBlocking {
                    services.join!!.joinMember(
                        eventId = event,
                        current = fx.current(memberId = late),
                        guestConsent = null,
                        captureConsent = consent,
                        now = open,
                    )
                }.outcome shouldBe
                    EventOnlineOutcome.JOINED
                total().total shouldBe 2
            }
        }

        test(
            "removeUnacknowledged asks LiveKit to remove only connected identities, but closes the participation of every missing member",
        ) {
            EventCaptureTestState.enabledWithText()
            val event = fx.event(createdBy = organizer)
            val connectedMember = fx.member()
            val droppedMember = fx.member()
            TimeTestSupport.withServerClock(instant = "2030-06-15T22:30:00Z") {
                joinedThenReleased(connectedMember, event)
                joinedThenReleased(droppedMember, event)
                val room = transaction { EventCaptureLock.roomIdOf(event)!! }
                val roomName =
                    transaction {
                        ConferenceRoomTable
                            .selectAll()
                            .where {
                                ConferenceRoomTable.id eq room
                            }.single()[ConferenceRoomTable.livekitRoomName]
                    }
                live.removed.clear()
                val guestPending = "evg-" + Uuid.random()
                val removed =
                    runBlocking {
                        EventCaptureConsentGate.removeUnacknowledged(
                            liveKit = live,
                            livekitRoomName = roomName,
                            roomId = room,
                            missing = setOf(connectedMember.toString(), droppedMember.toString(), guestPending),
                            connected = setOf(connectedMember.toString()),
                        )
                    }
                removed shouldBe 1
                // an identity that is not connected never reaches LiveKit (no WARN every pass for a pending guest or a crashed browser) ...
                live.removed.toList() shouldBe listOf(roomName to connectedMember.toString())

                // ... but the open participations of both members are closed in the database
                fun openParticipations() =
                    transaction {
                        ConferenceParticipationTable
                            .selectAll()
                            .where { (ConferenceParticipationTable.roomId eq room) and ConferenceParticipationTable.leftAt.isNull() }
                            .count()
                    }
                openParticipations() shouldBe 0L
                // a repeated pass is a no-op
                runBlocking {
                    EventCaptureConsentGate.removeUnacknowledged(
                        liveKit = live,
                        livekitRoomName = roomName,
                        roomId = room,
                        missing = setOf(connectedMember.toString(), droppedMember.toString()),
                        connected = emptySet(),
                    )
                }
                live.removed.size shouldBe 1
            }
        }
    })
