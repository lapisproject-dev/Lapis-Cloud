package network.lapis.cloud.server.routes

import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.nimbusds.jwt.SignedJWT
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldEndWith
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.string.shouldStartWith
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.datetime.LocalDateTime
import kotlinx.serialization.json.Json
import network.lapis.cloud.server.conference.ConferenceAudioGrantState
import network.lapis.cloud.server.conference.ConferenceNotesState
import network.lapis.cloud.server.conference.ConferenceWhiteboardState
import network.lapis.cloud.server.db.DatabaseConfig
import network.lapis.cloud.server.db.DevSeedData
import network.lapis.cloud.server.db.generated.ConferenceParticipationTable
import network.lapis.cloud.server.events.EVENT_ONLINE_CONFIG
import network.lapis.cloud.server.events.EventFakeLiveKit
import network.lapis.cloud.server.events.EventOnlineFixtures
import network.lapis.cloud.server.events.EventOnlineServices
import network.lapis.cloud.server.events.EventTicketIssuer
import network.lapis.cloud.server.federation.FederationInboxRateLimiter
import network.lapis.cloud.server.time.TimeTestSupport
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.EventGuestJoinRequestDto
import network.lapis.cloud.shared.domain.EventGuestJoinResponseDto
import network.lapis.cloud.shared.domain.EventGuestNotice
import network.lapis.cloud.shared.domain.EventOnlineKind
import network.lapis.cloud.shared.domain.EventOnlineOutcome
import network.lapis.cloud.shared.domain.EventOnlineStateDto
import network.lapis.cloud.shared.domain.EventRegistrationStatus
import network.lapis.cloud.shared.domain.EventVisibility
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.slf4j.LoggerFactory
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.uuid.Uuid

/**
 * Welle V1.9.113 -- the two routes of a person WITHOUT an account (`POST /api/events/{slug}/online-guest`, `GET .../online-state`), the new
 * attack surface of this wave: every guard on the request path (own origin, content type and size, strict JSON, the current notice, the
 * name, two rate limits), the identical answer for every unknown / hidden / unauthorized event, the ticket in the BODY only, what the token
 * may do, that no row about the person exists afterwards, and that no log line carries a name, a ticket code or a token.
 */
class EventOnlineGuestRoutesTest :
    FunSpec({
        val fx = EventOnlineFixtures()
        val live = EventFakeLiveKit()
        val json = Json { ignoreUnknownKeys = true }
        val base = "https://lapis.example.org"
        lateinit var organizer: Uuid

        // The event runs on a fixed day of 2030 in New York; the server clock is pinned inside the window.
        val insideWindow = "2030-06-15T22:30:00Z"

        beforeSpec {
            DatabaseConfig.connect()
            DevSeedData.seedIfEmpty(force = true)
            TimeTestSupport.setOrganizationZone("America/New_York")
            organizer = fx.member(role = AccountRole.BOARD)
        }
        afterSpec {
            fx.cleanUp()
            TimeTestSupport.resetOrganizationZone()
        }

        fun services() =
            EventOnlineServices.create(
                config = EVENT_ONLINE_CONFIG,
                liveKitAdminClient = live,
                whiteboardState = ConferenceWhiteboardState(),
                notesState = ConferenceNotesState(),
                audioGrantState = ConferenceAudioGrantState(),
                memberJoinRateLimiter = FederationInboxRateLimiter(maxRequests = 100, window = 1.minutes),
                adminRateLimiter = FederationInboxRateLimiter(maxRequests = 100, window = 1.minutes),
            )

        fun roomFor(
            ip: FederationInboxRateLimiter = FederationInboxRateLimiter(maxRequests = 1000, window = 1.minutes),
            perEvent: FederationInboxRateLimiter = FederationInboxRateLimiter(maxRequests = 1000, window = 1.hours),
            state: FederationInboxRateLimiter = FederationInboxRateLimiter(maxRequests = 1000, window = 1.minutes),
            eventOnline: EventOnlineServices = services(),
        ): io.ktor.server.routing.Route.() -> Unit =
            {
                registerEventOnlineGuestRoutes(
                    baseUrl = base,
                    eventOnline = eventOnline,
                    ipRateLimiter = ip,
                    eventRateLimiter = perEvent,
                    stateRateLimiter = state,
                )
            }

        fun body(
            name: String = "Anna",
            ticket: String? = null,
            version: String = EventGuestNotice.VERSION,
            sha: String = EventGuestNotice.SHA256,
        ) = json.encodeToString(
            EventGuestJoinRequestDto.serializer(),
            EventGuestJoinRequestDto(name = name, consentVersion = version, consentSha256 = sha, ticketCode = ticket),
        )

        suspend fun HttpClient.join(
            slug: String,
            payload: String = body(),
            origin: String? = base,
            contentType: String = "application/json",
        ): HttpResponse =
            post("/api/events/$slug/online-guest") {
                if (origin != null) header(HttpHeaders.Origin, origin)
                header(HttpHeaders.ContentType, contentType)
                setBody(payload)
            }

        fun decode(text: String) = json.decodeFromString(EventGuestJoinResponseDto.serializer(), text)

        fun slugOf(eventId: Uuid) = "event-online-$eventId"

        test(
            "a public event inside its window: the guest gets a pinned, short token with a suffixed name and a random identity -- and nothing is stored about the person",
        ) {
            TimeTestSupport.withServerClock(instant = insideWindow) {
                testApplication {
                    application { routing(roomFor()) }
                    val event = fx.event(createdBy = organizer)
                    val response = client.join(slugOf(event), body(name = "  Anna   Beispiel "))
                    response.status shouldBe HttpStatusCode.OK
                    response.headers[HttpHeaders.CacheControl] shouldBe "no-store"
                    val answer = decode(response.bodyAsText())
                    answer.outcome shouldBe EventOnlineOutcome.JOINED
                    answer.displayName shouldBe "Anna Beispiel (Gast)"
                    answer.identity!! shouldStartWith "evg-"
                    val claims = SignedJWT.parse(answer.token!!).jwtClaimsSet
                    claims.subject shouldBe answer.identity
                    @Suppress("UNCHECKED_CAST")
                    val grant = claims.claims["video"] as Map<String, Any?>
                    grant.containsKey("roomAdmin") shouldBe false
                    grant["canUpdateOwnMetadata"] shouldBe false
                    // no participation of this person exists (only the room itself)
                    val rooms = fx.roomIdsOf(event)
                    rooms.size shouldBe 1
                    transaction {
                        ConferenceParticipationTable
                            .selectAll()
                            .where { ConferenceParticipationTable.roomId eq rooms.single() }
                            .count()
                    } shouldBe 0L
                }
            }
        }

        test(
            "the state route tells the window and the notice, never a token, a room or an id -- and gives an unknown event the same answer as a hidden one",
        ) {
            TimeTestSupport.withServerClock(instant = insideWindow) {
                testApplication {
                    application { routing(roomFor()) }
                    val open = fx.event(createdBy = organizer, title = "Offene Runde")
                    val deadline = fx.event(createdBy = organizer, registrationClosesAt = LocalDateTime(2030, 6, 14, 12, 0))
                    val hidden = fx.event(createdBy = organizer, visibility = EventVisibility.MEMBERS_ONLY)
                    val encounter = fx.event(createdBy = organizer, kind = EventOnlineKind.LAPIS_ENCOUNTER, encounterSpaceId = fx.space())
                    val draft = fx.event(createdBy = organizer, status = network.lapis.cloud.shared.domain.EventStatus.DRAFT)

                    suspend fun state(slug: String) =
                        client
                            .get("/api/events/$slug/online-state")
                            .also {
                                it.status shouldBe
                                    HttpStatusCode.OK
                            }.bodyAsText()

                    val openState = json.decodeFromString(EventOnlineStateDto.serializer(), state(slugOf(open)))
                    openState.outcome shouldBe EventOnlineOutcome.JOINED
                    openState.title shouldBe "Offene Runde"
                    openState.requiresTicket shouldBe false
                    openState.noticeVersion shouldBe EventGuestNotice.VERSION
                    openState.noticeSha256 shouldBe EventGuestNotice.SHA256
                    openState.opensAt shouldBe "2030-06-15T17:45"
                    openState.closesAt shouldBe "2030-06-15T21:00"
                    json.decodeFromString(EventOnlineStateDto.serializer(), state(slugOf(deadline))).requiresTicket shouldBe true
                    val text = state(slugOf(open))
                    text shouldNotContain "token"
                    // hidden / members-only / encounter / draft / unknown: byte-identical
                    val refusals = listOf(state(slugOf(hidden)), state(slugOf(encounter)), state(slugOf(draft)), state("no-such-event"))
                    refusals.toSet().size shouldBe 1
                    json.decodeFromString(EventOnlineStateDto.serializer(), refusals.first()).outcome shouldBe EventOnlineOutcome.FORBIDDEN
                }
            }
        }

        test("the state route reports not-yet and over") {
            testApplication {
                application { routing(roomFor()) }
                val event = fx.event(createdBy = organizer)
                TimeTestSupport.withServerClock(instant = "2030-06-15T20:00:00Z") {
                    json
                        .decodeFromString(
                            EventOnlineStateDto.serializer(),
                            client.get("/api/events/${slugOf(event)}/online-state").bodyAsText(),
                        ).outcome shouldBe
                        EventOnlineOutcome.NOT_YET
                }
                TimeTestSupport.withServerClock(instant = "2030-06-16T03:00:00Z") {
                    json
                        .decodeFromString(
                            EventOnlineStateDto.serializer(),
                            client.get("/api/events/${slugOf(event)}/online-state").bodyAsText(),
                        ).outcome shouldBe
                        EventOnlineOutcome.ENDED
                }
            }
        }

        test(
            "every refusal for an unknown, hidden, draft, members-only or encounter event is the same 200 FORBIDDEN -- no 404 tells slugs apart",
        ) {
            TimeTestSupport.withServerClock(instant = insideWindow) {
                testApplication {
                    application { routing(roomFor()) }
                    val slugs =
                        listOf(
                            slugOf(fx.event(createdBy = organizer, visibility = EventVisibility.MEMBERS_ONLY)),
                            slugOf(fx.event(createdBy = organizer, status = network.lapis.cloud.shared.domain.EventStatus.DRAFT)),
                            slugOf(fx.event(createdBy = organizer, kind = EventOnlineKind.LAPIS_ENCOUNTER, encounterSpaceId = fx.space())),
                            slugOf(fx.event(createdBy = organizer, kind = EventOnlineKind.NONE)),
                            "no-such-event",
                        )
                    val answers =
                        slugs.map { slug ->
                            val response = client.join(slug)
                            response.status shouldBe HttpStatusCode.OK
                            response.bodyAsText()
                        }
                    answers.toSet().size shouldBe 1
                    decode(answers.first()).outcome shouldBe EventOnlineOutcome.FORBIDDEN
                }
            }
        }

        test("a deadline event admits only the ticket code of a confirmed registration, sent in the body (also pasted as the ticket URL)") {
            TimeTestSupport.withServerClock(instant = insideWindow) {
                testApplication {
                    application { routing(roomFor()) }
                    val event = fx.event(createdBy = organizer, registrationClosesAt = LocalDateTime(2030, 6, 14, 12, 0))
                    val ticket = transaction { EventTicketIssuer.mint() }
                    fx.registration(eventId = event, status = EventRegistrationStatus.CONFIRMED, ticketHash = ticket.sha256)
                    val pending = transaction { EventTicketIssuer.mint() }
                    fx.registration(eventId = event, status = EventRegistrationStatus.PENDING_PAYMENT, ticketHash = pending.sha256)
                    decode(client.join(slugOf(event), body(ticket = null)).bodyAsText()).outcome shouldBe EventOnlineOutcome.FORBIDDEN
                    decode(client.join(slugOf(event), body(ticket = "NOTACODE")).bodyAsText()).outcome shouldBe EventOnlineOutcome.FORBIDDEN
                    decode(client.join(slugOf(event), body(ticket = pending.rawCode)).bodyAsText()).outcome shouldBe
                        EventOnlineOutcome.FORBIDDEN
                    decode(client.join(slugOf(event), body(ticket = ticket.rawCode)).bodyAsText()).outcome shouldBe
                        EventOnlineOutcome.JOINED
                    decode(
                        client
                            .join(
                                slugOf(event),
                                body(ticket = "https://lapis.example.org/veranstaltung/x/ticket?code=${ticket.rawCode}"),
                            ).bodyAsText(),
                    ).outcome shouldBe
                        EventOnlineOutcome.JOINED
                }
            }
        }

        test("the request guards: own origin only, JSON only, small bodies only, strict JSON, the CURRENT notice, a usable name") {
            TimeTestSupport.withServerClock(instant = insideWindow) {
                testApplication {
                    application { routing(roomFor()) }
                    val event = fx.event(createdBy = organizer)
                    val slug = slugOf(event)
                    client.join(slug, origin = null).status shouldBe HttpStatusCode.Forbidden
                    client.join(slug, origin = "https://evil.example").status shouldBe HttpStatusCode.Forbidden
                    client.join(slug, origin = "https://lapis.example.org:8443").status shouldBe HttpStatusCode.Forbidden
                    client.join(slug, origin = "http://lapis.example.org").status shouldBe HttpStatusCode.Forbidden
                    client.join(slug, origin = "https://LAPIS.example.org").status shouldBe HttpStatusCode.OK
                    client.join(slug, contentType = "text/plain").status shouldBe HttpStatusCode.UnsupportedMediaType
                    client.join(slug, payload = "x".repeat(MAX_EVENT_GUEST_BODY_BYTES + 1)).status shouldBe HttpStatusCode.PayloadTooLarge
                    client.join(slug, payload = "{not json").status shouldBe HttpStatusCode.BadRequest
                    client.join(slug, payload = "[".repeat(50) + "]".repeat(50)).status shouldBe HttpStatusCode.BadRequest
                    client.join(slug, payload = body(version = "old")).status shouldBe HttpStatusCode.Conflict
                    client.join(slug, payload = body(sha = "0".repeat(64))).status shouldBe HttpStatusCode.Conflict
                    client.join(slug, payload = body(name = "   ")).status shouldBe HttpStatusCode.BadRequest
                    client.join(slug, payload = body(name = "n".repeat(61))).status shouldBe HttpStatusCode.BadRequest
                    client.join(slug, payload = body(name = "\u0000\u0007")).status shouldBe HttpStatusCode.BadRequest
                    // a guest cannot pose as a member: whatever the name, the server appends the suffix
                    decode(client.join(slug, payload = body(name = "Vorstand")).bodyAsText()).displayName!! shouldEndWith " (Gast)"
                }
            }
        }

        test("two rate limits before anything is read: per address and per event") {
            TimeTestSupport.withServerClock(instant = insideWindow) {
                testApplication {
                    val perAddress = FederationInboxRateLimiter(maxRequests = 2, window = 1.minutes)
                    application { routing(roomFor(ip = perAddress)) }
                    val event = fx.event(createdBy = organizer)
                    client.join(slugOf(event)).status shouldBe HttpStatusCode.OK
                    client.join(slugOf(event)).status shouldBe HttpStatusCode.OK
                    client.join(slugOf(event)).status shouldBe HttpStatusCode.TooManyRequests
                }
                testApplication {
                    val perEvent = FederationInboxRateLimiter(maxRequests = 2, window = 1.hours)
                    application { routing(roomFor(perEvent = perEvent)) }
                    val event = fx.event(createdBy = organizer)
                    val other = fx.event(createdBy = organizer)
                    client.join(slugOf(event)).status shouldBe HttpStatusCode.OK
                    client.join(slugOf(event)).status shouldBe HttpStatusCode.OK
                    client.join(slugOf(event)).status shouldBe HttpStatusCode.TooManyRequests
                    // another event has its own budget
                    client.join(slugOf(other)).status shouldBe HttpStatusCode.OK
                }
                testApplication {
                    val state = FederationInboxRateLimiter(maxRequests = 1, window = 1.minutes)
                    application { routing(roomFor(state = state)) }
                    client.get("/api/events/x/online-state").status shouldBe HttpStatusCode.OK
                    client.get("/api/events/x/online-state").status shouldBe HttpStatusCode.TooManyRequests
                }
            }
        }

        test("a full room answers FULL, a LiveKit outage UNAVAILABLE, unconfigured conferencing UNAVAILABLE -- never a server error") {
            TimeTestSupport.withServerClock(instant = insideWindow) {
                testApplication {
                    application { routing(roomFor()) }
                    val event = fx.event(createdBy = organizer, capacity = 2)
                    val first = decode(client.join(slugOf(event)).bodyAsText())
                    first.outcome shouldBe EventOnlineOutcome.JOINED
                    val roomName = live.rooms.keys.first { name -> live.rooms[name]!!.maxParticipants == 2 }
                    live.seedLive(room = roomName, identity = "a")
                    live.seedLive(room = roomName, identity = "b")
                    decode(client.join(slugOf(event)).bodyAsText()).outcome shouldBe EventOnlineOutcome.FULL
                }
                testApplication {
                    application { routing(roomFor()) }
                    val event = fx.event(createdBy = organizer)
                    live.failCreate = true
                    try {
                        val response = client.join(slugOf(event))
                        response.status shouldBe HttpStatusCode.OK
                        decode(response.bodyAsText()).outcome shouldBe EventOnlineOutcome.UNAVAILABLE
                    } finally {
                        live.failCreate = false
                    }
                }
                testApplication {
                    application { routing(roomFor(eventOnline = EventOnlineServices.DISABLED)) }
                    val event = fx.event(createdBy = organizer)
                    decode(client.join(slugOf(event)).bodyAsText()).outcome shouldBe EventOnlineOutcome.UNAVAILABLE
                }
            }
        }

        test("no log line carries the guest's name, the ticket code or a token (also when LiveKit fails)") {
            val root = LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME) as ch.qos.logback.classic.Logger
            val appender = ListAppender<ILoggingEvent>()
            appender.start()
            root.addAppender(appender)
            try {
                TimeTestSupport.withServerClock(instant = insideWindow) {
                    testApplication {
                        application { routing(roomFor()) }
                        val event = fx.event(createdBy = organizer, registrationClosesAt = LocalDateTime(2030, 6, 14, 12, 0))
                        val ticket = transaction { EventTicketIssuer.mint() }
                        fx.registration(eventId = event, status = EventRegistrationStatus.CONFIRMED, ticketHash = ticket.sha256)
                        client.join(slugOf(event), body(name = "GEHEIMERNAME", ticket = ticket.rawCode)).status shouldBe HttpStatusCode.OK
                        live.failCreate = true
                        try {
                            client.join(slugOf(fx.event(createdBy = organizer)), body(name = "GEHEIMERNAME")).status shouldBe
                                HttpStatusCode.OK
                        } finally {
                            live.failCreate = false
                        }
                        val logged = appender.list.joinToString("\n") { it.formattedMessage + (it.throwableProxy?.message ?: "") }
                        logged shouldNotContain "GEHEIMERNAME"
                        logged shouldNotContain ticket.rawCode
                        logged shouldNotContain "eyJ"
                    }
                }
            } finally {
                root.detachAppender(appender)
            }
        }
    })
