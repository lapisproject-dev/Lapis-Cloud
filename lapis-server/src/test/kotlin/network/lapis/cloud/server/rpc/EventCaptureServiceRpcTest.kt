package network.lapis.cloud.server.rpc

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.ktor.server.application.ApplicationCall
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.server.conference.ConferenceAudioGrantState
import network.lapis.cloud.server.conference.ConferenceNotesState
import network.lapis.cloud.server.conference.ConferenceWhiteboardState
import network.lapis.cloud.server.db.DatabaseConfig
import network.lapis.cloud.server.db.DevSeedData
import network.lapis.cloud.server.db.generated.EventTable
import network.lapis.cloud.server.encounter.encounterApp
import network.lapis.cloud.server.encounter.failure
import network.lapis.cloud.server.encounter.runAs
import network.lapis.cloud.server.events.EVENT_ONLINE_CONFIG
import network.lapis.cloud.server.events.EventCaptureTestState
import network.lapis.cloud.server.events.EventFakeLiveKit
import network.lapis.cloud.server.events.EventOnlineFixtures
import network.lapis.cloud.server.events.EventOnlineLifecycle
import network.lapis.cloud.server.events.EventOnlineServices
import network.lapis.cloud.server.federation.FederationInboxRateLimiter
import network.lapis.cloud.server.mail.MailDispatcher
import network.lapis.cloud.server.mail.NoOpMailTransport
import network.lapis.cloud.server.time.TimeTestSupport
import network.lapis.cloud.shared.domain.ConferenceGuestConsentAcknowledgmentInput
import network.lapis.cloud.shared.domain.EventGuestNoticeCapture
import network.lapis.cloud.shared.domain.EventOnlineKind
import network.lapis.cloud.shared.domain.EventOnlineOutcome
import network.lapis.cloud.shared.domain.EventVisibility
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.rpc.EventCaptureNoticeOutdatedException
import network.lapis.cloud.shared.rpc.EventCaptureNoticeRequiredException
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.time.Duration.Companion.minutes
import kotlin.uuid.Uuid

/**
 * V1.9.156 -- the authenticated entry through the RPC: the OLD `joinEventOnline` (no consent parameter) is refused for a released event, so a cached client
 * answers with an error instead of letting somebody in unasked; `joinEventOnlineWithCapture` carries the consent; the released flag is visible in the
 * event DTO; and an event that stops being a Lapis conference drops its release.
 */
class EventCaptureServiceRpcTest :
    FunSpec({
        val fx = EventOnlineFixtures()
        val live = EventFakeLiveKit()
        lateinit var organizer: Uuid
        lateinit var services: EventOnlineServices
        val inside = "2030-06-15T22:30:00Z"

        beforeSpec {
            DatabaseConfig.connect()
            DevSeedData.seedIfEmpty(force = true)
            TimeTestSupport.setOrganizationZone("America/New_York")
            organizer = fx.member(name = "Veranstalterin")
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

        val captureNotice =
            ConferenceGuestConsentAcknowledgmentInput(
                consentVersion = EventGuestNoticeCapture.VERSION,
                consentSha256 = EventGuestNoticeCapture.SHA256,
            )

        test("the old joinEventOnline is refused for a released event and works for an unreleased one") {
            EventCaptureTestState.enabledWithText()
            val released = fx.event(createdBy = organizer, captureAllowed = true)
            val plain = fx.event(createdBy = organizer)
            val member = fx.member()
            TimeTestSupport.withServerClock(instant = inside) {
                encounterApp {
                    client
                        .runAs(
                            member = member,
                        ) { call -> service(call).joinEventOnline(eventId = released.toString(), guestConsent = null) }
                        .failure<EventCaptureNoticeRequiredException>()
                    client
                        .runAs(member = member) { call -> service(call).joinEventOnline(eventId = plain.toString(), guestConsent = null) }
                        .getOrThrow()
                        .outcome shouldBe EventOnlineOutcome.JOINED
                }
            }
        }

        test("joinEventOnlineWithCapture: the consent admits, no/stale consent does not, an unparsable id is the usual FORBIDDEN") {
            val consent = EventCaptureTestState.enabledWithText()
            val released = fx.event(createdBy = organizer, captureAllowed = true)
            val member = fx.member()
            TimeTestSupport.withServerClock(instant = inside) {
                encounterApp {
                    client
                        .runAs(
                            member = member,
                        ) { call ->
                            service(
                                call,
                            ).joinEventOnlineWithCapture(eventId = released.toString(), guestConsent = null, captureConsent = null)
                        }.failure<EventCaptureNoticeRequiredException>()
                    client
                        .runAs(member = member) { call ->
                            service(
                                call,
                            ).joinEventOnlineWithCapture(
                                eventId = released.toString(),
                                guestConsent = null,
                                captureConsent = consent.copy(consentSha256 = "0".repeat(64)),
                            )
                        }.failure<EventCaptureNoticeOutdatedException>()
                    val joined =
                        client
                            .runAs(
                                member = member,
                            ) { call ->
                                service(
                                    call,
                                ).joinEventOnlineWithCapture(eventId = released.toString(), guestConsent = null, captureConsent = consent)
                            }.getOrThrow()
                    joined.outcome shouldBe EventOnlineOutcome.JOINED
                    joined.token!!.eventId shouldBe released.toString()
                    joined.captureLive!!.recording shouldBe false
                    client
                        .runAs(
                            member = member,
                        ) { call ->
                            service(
                                call,
                            ).joinEventOnlineWithCapture(eventId = "not-a-uuid", guestConsent = null, captureConsent = consent)
                        }.getOrThrow()
                        .outcome shouldBe EventOnlineOutcome.FORBIDDEN
                }
            }
        }

        test("a friend account needs both: the consent and the capture variant of the guest notice") {
            val consent = EventCaptureTestState.enabledWithText()
            val released = fx.event(createdBy = organizer, captureAllowed = true)
            val friend = fx.member(status = MemberStatus.FRIEND)
            TimeTestSupport.withServerClock(instant = inside) {
                encounterApp {
                    client
                        .runAs(
                            member = friend,
                        ) { call ->
                            service(
                                call,
                            ).joinEventOnlineWithCapture(eventId = released.toString(), guestConsent = null, captureConsent = consent)
                        }.exceptionOrNull() shouldNotBe null
                    client
                        .runAs(member = friend) { call ->
                            service(
                                call,
                            ).joinEventOnlineWithCapture(
                                eventId = released.toString(),
                                guestConsent = captureNotice,
                                captureConsent = consent,
                            )
                        }.getOrThrow()
                        .outcome shouldBe EventOnlineOutcome.JOINED
                }
            }
        }

        test("the event DTO shows the release; it is false by default") {
            val released = fx.event(createdBy = organizer, captureAllowed = true)
            val plain = fx.event(createdBy = organizer)
            val member = fx.member()
            encounterApp {
                client.runAs(member = member) { call -> service(call).getEvent(released.toString()) }.getOrThrow().captureAllowed shouldBe
                    true
                client.runAs(member = member) { call -> service(call).getEvent(plain.toString()) }.getOrThrow().captureAllowed shouldBe
                    false
            }
        }

        test("an event that is saved as something else than a Lapis conference drops its release; a conference keeps it") {
            val kept = fx.event(createdBy = organizer, captureAllowed = true)
            val dropped = fx.event(createdBy = organizer, captureAllowed = true)
            transaction {
                fun save(
                    id: Uuid,
                    kind: EventOnlineKind,
                ) = EventOnlineLifecycle.onEventSaved(
                    eventId = id,
                    kind = kind,
                    startsAt = LocalDateTime(2030, 6, 15, 18, 0),
                    endsAt = LocalDateTime(2030, 6, 15, 20, 0),
                    previousVisibility = EventVisibility.PUBLIC,
                    newVisibility = EventVisibility.PUBLIC,
                    previousRegistrationClosesAt = null,
                    newRegistrationClosesAt = null,
                )
                save(kept, EventOnlineKind.LAPIS_CONFERENCE)
                save(dropped, EventOnlineKind.NONE)
            }

            fun flag(id: Uuid) = transaction { EventTable.selectAll().where { EventTable.id eq id }.single()[EventTable.captureAllowed] }
            flag(kept) shouldBe true
            flag(dropped) shouldBe false
        }
    })
