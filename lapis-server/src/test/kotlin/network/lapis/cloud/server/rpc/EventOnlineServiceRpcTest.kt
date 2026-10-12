package network.lapis.cloud.server.rpc

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldStartWith
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.install
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.server.conference.ConferenceAudioGrantState
import network.lapis.cloud.server.conference.ConferenceNotesState
import network.lapis.cloud.server.conference.ConferenceWhiteboardState
import network.lapis.cloud.server.db.DatabaseConfig
import network.lapis.cloud.server.db.DevSeedData
import network.lapis.cloud.server.db.generated.ConferenceParticipationTable
import network.lapis.cloud.server.db.generated.ConferenceRoomTable
import network.lapis.cloud.server.db.generated.EventConferenceStatus
import network.lapis.cloud.server.db.generated.EventConferenceTable
import network.lapis.cloud.server.db.generated.EventTable
import network.lapis.cloud.server.events.EVENT_ONLINE_CONFIG
import network.lapis.cloud.server.events.EventFakeLiveKit
import network.lapis.cloud.server.events.EventOnlineFixtures
import network.lapis.cloud.server.events.EventOnlineServices
import network.lapis.cloud.server.federation.FederationInboxRateLimiter
import network.lapis.cloud.server.mail.MailDispatcher
import network.lapis.cloud.server.mail.NoOpMailTransport
import network.lapis.cloud.server.security.LoginRateLimiter
import network.lapis.cloud.server.time.TimeTestSupport
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.ConferenceGuestConsentAcknowledgmentInput
import network.lapis.cloud.shared.domain.EventGuestNotice
import network.lapis.cloud.shared.domain.EventInput
import network.lapis.cloud.shared.domain.EventOnlineKind
import network.lapis.cloud.shared.domain.EventRegistrationStatus
import network.lapis.cloud.shared.domain.EventVisibility
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.domain.RecurrenceFrequency
import network.lapis.cloud.shared.domain.RecurrenceRuleInput
import network.lapis.cloud.shared.domain.RecurrenceWeekday
import network.lapis.cloud.shared.rpc.BadRequestException
import network.lapis.cloud.shared.rpc.ConflictException
import network.lapis.cloud.shared.rpc.ForbiddenException
import network.lapis.cloud.shared.rpc.NotFoundException
import network.lapis.cloud.shared.rpc.UnauthenticatedException
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import java.math.BigDecimal
import kotlin.time.Duration.Companion.minutes
import kotlin.uuid.Uuid

/**
 * Welle V1.9.113 -- the authenticated RPC side, end to end through throwaway routes (`X-Member-Id`): `joinEventOnline`, the moderator's guest
 * list, the encounter-space picker, the event lifecycle (create/update/cancel/series with the online fields, the conference follows), and the
 * proof that the ORDINARY conference RPCs cannot enter or reconfigure an event room.
 */
class EventOnlineServiceRpcTest :
    FunSpec({
        val fx = EventOnlineFixtures()
        val live = EventFakeLiveKit()
        lateinit var board: Uuid
        lateinit var services: EventOnlineServices

        // 2030-06-15 18:00-20:00 New York; "now" is inside the window.
        val inside = "2030-06-15T22:30:00Z"
        val start = LocalDateTime(2030, 6, 15, 18, 0)
        val end = LocalDateTime(2030, 6, 15, 20, 0)

        beforeSpec {
            DatabaseConfig.connect()
            DevSeedData.seedIfEmpty(force = true)
            TimeTestSupport.setOrganizationZone("America/New_York")
            board = fx.member(role = AccountRole.BOARD, name = "Vorstand")
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
        afterSpec {
            fx.cleanUp()
            TimeTestSupport.resetOrganizationZone()
        }

        fun eventService(
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

        fun conferenceService(call: ApplicationCall) =
            ConferenceService(
                call = call,
                liveKitAdminClient = live,
                createRoomRateLimiter = LoginRateLimiter(),
                config = EVENT_ONLINE_CONFIG,
                conferenceMeetingBindRateLimiter = FederationInboxRateLimiter(maxRequests = 1000, window = 1.minutes),
                roomVotingStateRateLimiter = FederationInboxRateLimiter(maxRequests = 1000, window = 1.minutes),
            )

        fun input(
            kind: EventOnlineKind,
            spaceId: String? = null,
            url: String? = null,
            location: String? = null,
            registrationClosesAt: LocalDateTime? = null,
            starts: LocalDateTime = start,
            ends: LocalDateTime = end,
            visibility: EventVisibility = EventVisibility.PUBLIC,
            capacity: Int? = null,
            public: Boolean = false,
        ) = EventInput(
            title = "Online-Event ${Uuid.random()}",
            description = "test",
            locationText = location,
            onlineUrl = url,
            startsAt = starts,
            endsAt = ends,
            capacity = capacity,
            feeAmount = BigDecimal.ZERO,
            feeCurrency = "EUR",
            visibility = visibility,
            registrationClosesAt = registrationClosesAt,
            onlineUrlPublic = public,
            onlineKind = kind,
            encounterSpaceId = spaceId,
        )

        /** Throwaway routes: every RPC under test answers `OK|...` or the status code of its exception. */
        fun Route.routes(online: EventOnlineServices = services) {
            post("/ev/create") {
                val kind = EventOnlineKind.valueOf(call.request.queryParameters["kind"] ?: "NONE")
                val location = call.request.queryParameters["location"]
                val url = call.request.queryParameters["url"]
                val space = call.request.queryParameters["space"]
                val deadline = call.request.queryParameters["deadline"]?.let { LocalDateTime.parse(it) }
                val visibility = EventVisibility.valueOf(call.request.queryParameters["visibility"] ?: "PUBLIC")
                val dto =
                    eventService(call, online).createEvent(
                        input(
                            kind = kind,
                            spaceId = space,
                            url = url,
                            location = location,
                            registrationClosesAt = deadline,
                            visibility = visibility,
                        ),
                    )
                fx.eventIds += Uuid.parse(dto.id)
                call.respondText(
                    "OK|${dto.id}|${dto.onlineKind}|${dto.encounterSpaceId}|${dto.onlineUrl}|${dto.onlineWindowOpensAt}|${dto.onlineWindowClosesAt}",
                )
            }
            post("/ev/{id}/update") {
                val kind = EventOnlineKind.valueOf(call.request.queryParameters["kind"] ?: "NONE")
                val existing = eventService(call, online).getEvent(call.parameters["id"]!!)
                val dto =
                    eventService(call, online).updateEvent(
                        id = existing.id,
                        input =
                            input(
                                kind = kind,
                                location = call.request.queryParameters["location"],
                                visibility = EventVisibility.valueOf(call.request.queryParameters["visibility"] ?: "PUBLIC"),
                                registrationClosesAt = call.request.queryParameters["deadline"]?.let { LocalDateTime.parse(it) },
                                starts = call.request.queryParameters["starts"]?.let { LocalDateTime.parse(it) } ?: existing.startsAt,
                                ends = call.request.queryParameters["ends"]?.let { LocalDateTime.parse(it) } ?: existing.endsAt,
                            ),
                    )
                call.respondText("OK|${dto.onlineKind}|${dto.startsAt}|${dto.endsAt}")
            }
            post("/ev/{id}/cancel") {
                eventService(call, online).cancelEvent(id = call.parameters["id"]!!, reason = "Absage")
                call.respondText("OK")
            }
            post("/ev/series") {
                val result =
                    eventService(call, online).createEventSeries(
                        input = input(kind = EventOnlineKind.LAPIS_CONFERENCE, location = "Saal"),
                        rule =
                            RecurrenceRuleInput(
                                frequency = RecurrenceFrequency.WEEKLY,
                                byWeekdays = setOf(RecurrenceWeekday.SA),
                                count = 2,
                            ),
                    )
                fx.eventIds += result.createdEventIds.map { Uuid.parse(it) }
                call.respondText("OK|" + result.createdEventIds.joinToString(","))
            }
            post("/ev/{id}/join") {
                val consent =
                    if (call.request.queryParameters["consent"] == "1") {
                        ConferenceGuestConsentAcknowledgmentInput(
                            consentVersion = EventGuestNotice.VERSION,
                            consentSha256 = EventGuestNotice.SHA256,
                        )
                    } else {
                        null
                    }
                val result = eventService(call, online).joinEventOnline(eventId = call.parameters["id"]!!, guestConsent = consent)
                call.respondText(
                    "${result.outcome}|${result.token?.roomId}|${result.token?.role}|${result.token?.eventId}|${result.encounterSpaceId}|${result.opensAt}",
                )
            }
            get("/ev/{id}/guests") {
                call.respondText(
                    "OK|" + eventService(call, online).listEventGuests(call.parameters["id"]!!).joinToString(",") { it.identity },
                )
            }
            post("/ev/{id}/guests/remove") {
                eventService(
                    call,
                    online,
                ).removeEventGuest(eventId = call.parameters["id"]!!, identity = call.request.queryParameters["identity"].orEmpty())
                call.respondText("OK")
            }
            get("/ev/spaces") {
                call.respondText(
                    "OK|" + eventService(call, online).listEncounterSpacesForEvents().joinToString(",") { "${it.id}:${it.membersOnly}" },
                )
            }
            // the ordinary conference RPCs, aimed at an event room
            post("/conf/{op}/{roomId}") {
                val room = call.parameters["roomId"]!!
                val service = conferenceService(call)
                when (call.parameters["op"]) {
                    "join" -> service.joinRoom(roomId = room, guestConsent = null).let { call.respondText("OK|${it.token.length}") }
                    "info" -> service.getGuestJoinInfo(roomId = room).let { call.respondText("OK|${it.title}") }
                    "get" -> service.getRoom(roomId = room).let { call.respondText("OK|${it.title}") }
                    "participants" -> service.listParticipants(roomId = room).let { call.respondText("OK|${it.size}") }
                    "rename" -> service.renameRoom(roomId = room, title = "Neuer Titel").let { call.respondText("OK") }
                    "guestaccess" -> service.setRoomGuestAccess(roomId = room, allowFederationGuests = false).let { call.respondText("OK") }
                    "end" -> service.endRoom(roomId = room).let { call.respondText("OK") }
                    "voting" -> service.getRoomVotingState(roomId = room).let { call.respondText("OK") }
                    else -> call.respondText("unknown op", status = HttpStatusCode.BadRequest)
                }
            }
            get("/conf/list") {
                call.respondText("OK|" + conferenceService(call).listActiveRooms().joinToString(",") { it.id })
            }
        }

        fun io.ktor.server.application.Application.setUp(online: EventOnlineServices = services) {
            install(StatusPages) {
                exception<BadRequestException> { call, cause -> call.respondText(cause.message ?: "", status = HttpStatusCode.BadRequest) }
                exception<ConflictException> { call, cause -> call.respondText(cause.message ?: "", status = HttpStatusCode.Conflict) }
                exception<ForbiddenException> { call, _ -> call.respondText("forbidden", status = HttpStatusCode.Forbidden) }
                exception<NotFoundException> { call, cause -> call.respondText(cause.message ?: "", status = HttpStatusCode.NotFound) }
                exception<UnauthenticatedException> { call, _ -> call.respondText("unauthenticated", status = HttpStatusCode.Unauthorized) }
            }
            routing { routes(online) }
        }

        suspend fun HttpClient.asMember(
            member: Uuid,
            method: String,
            path: String,
        ): HttpResponse =
            if (method == "GET") {
                get(path) { header("X-Member-Id", member.toString()) }
            } else {
                post(path) { header("X-Member-Id", member.toString()) }
            }

        fun roomOf(event: Uuid): Uuid = fx.roomIdsOf(event).single()

        // ── joinEventOnline ─────────────────────────────────────────────────────────────────

        test(
            "joinEventOnline: a member gets a token whose DTO names the event; an unparsable id is the same FORBIDDEN as every other refusal",
        ) {
            TimeTestSupport.withServerClock(instant = inside) {
                testApplication {
                    application { setUp() }
                    val event = fx.event(createdBy = board)
                    val member = fx.member()
                    val ok = client.asMember(member, "POST", "/ev/$event/join").bodyAsText()
                    ok shouldStartWith "JOINED|"
                    ok shouldContain "|PARTICIPANT|$event|"
                    client.asMember(member, "POST", "/ev/not-a-uuid/join").bodyAsText() shouldStartWith "FORBIDDEN|"
                    client.asMember(member, "POST", "/ev/${Uuid.random()}/join").bodyAsText() shouldStartWith "FORBIDDEN|"
                    client.post("/ev/$event/join").status shouldBe HttpStatusCode.Unauthorized
                }
            }
        }

        test("joinEventOnline: not yet shows the opening time in the organization zone; with conferencing off the answer is UNAVAILABLE") {
            testApplication {
                application { setUp() }
                val event = fx.event(createdBy = board)
                val member = fx.member()
                TimeTestSupport.withServerClock(instant = "2030-06-15T20:00:00Z") {
                    client.asMember(member, "POST", "/ev/$event/join").bodyAsText() shouldBe "NOT_YET|null|null|null|null|2030-06-15T17:45"
                }
            }
            TimeTestSupport.withServerClock(instant = inside) {
                testApplication {
                    application { setUp(EventOnlineServices.DISABLED) }
                    val event = fx.event(createdBy = board)
                    client.asMember(fx.member(), "POST", "/ev/$event/join").bodyAsText() shouldStartWith "UNAVAILABLE|"
                }
            }
        }

        test("joinEventOnline: a GUEST/FRIEND account needs the notice (409 without it) and then joins as a guest") {
            TimeTestSupport.withServerClock(instant = inside) {
                testApplication {
                    application { setUp() }
                    val event = fx.event(createdBy = board)
                    val friend = fx.member(status = MemberStatus.FRIEND)
                    client.asMember(friend, "POST", "/ev/$event/join").status shouldBe HttpStatusCode.Conflict
                    client.asMember(friend, "POST", "/ev/$event/join?consent=1").bodyAsText() shouldStartWith "JOINED|"
                }
            }
        }

        test("getRoom: a FRIEND who joined an event room can read it afterwards, a FRIEND who did not cannot") {
            TimeTestSupport.withServerClock(instant = inside) {
                testApplication {
                    application { setUp() }
                    val event = fx.event(createdBy = board)
                    val friend = fx.member(status = MemberStatus.FRIEND)
                    val strangerFriend = fx.member(status = MemberStatus.FRIEND)
                    client.asMember(friend, "POST", "/ev/$event/join?consent=1").bodyAsText() shouldStartWith "JOINED|"
                    val room = roomOf(event)
                    client.asMember(friend, "POST", "/conf/get/$room").status shouldBe HttpStatusCode.OK
                    client.asMember(strangerFriend, "POST", "/conf/get/$room").status shouldBe HttpStatusCode.Forbidden
                }
            }
        }

        test("joinEventOnline: an encounter-space event answers with the space id, nothing is created at LiveKit") {
            TimeTestSupport.withServerClock(instant = inside) {
                testApplication {
                    application { setUp() }
                    val space = fx.space()
                    val event =
                        fx.event(
                            createdBy = board,
                            kind = EventOnlineKind.LAPIS_ENCOUNTER,
                            encounterSpaceId = space,
                            locationText = null,
                        )
                    val before = live.createCalls.get()
                    client.asMember(fx.member(), "POST", "/ev/$event/join").bodyAsText() shouldBe "JOINED|null|null|null|$space|null"
                    live.createCalls.get() shouldBe before
                }
            }
        }

        // ── moderator tools ─────────────────────────────────────────────────────────────────

        test("the guest list and the removal are for the organizer and BOARD/ADMIN only; only evg- identities can be removed") {
            TimeTestSupport.withServerClock(instant = inside) {
                testApplication {
                    application { setUp() }
                    val organizer = fx.member(name = "Organisatorin")
                    val event = fx.event(createdBy = organizer)
                    val member = fx.member()
                    val room = client.asMember(member, "POST", "/ev/$event/join").bodyAsText()
                    room shouldStartWith "JOINED|"
                    val roomName =
                        transaction {
                            EventConferenceTable
                                .selectAll()
                                .where {
                                    EventConferenceTable.eventId eq event
                                }.single()[EventConferenceTable.livekitRoomName]
                        }!!
                    live.seedLive(room = roomName, identity = "evg-1111", name = "Gast (Gast)")
                    client.asMember(organizer, "GET", "/ev/$event/guests").bodyAsText() shouldBe "OK|evg-1111"
                    client.asMember(board, "GET", "/ev/$event/guests").bodyAsText() shouldBe "OK|evg-1111"
                    client.asMember(member, "GET", "/ev/$event/guests").status shouldBe HttpStatusCode.Forbidden
                    client.asMember(member, "POST", "/ev/$event/guests/remove?identity=evg-1111").status shouldBe HttpStatusCode.Forbidden
                    client.asMember(organizer, "POST", "/ev/$event/guests/remove?identity=$member").status shouldBe
                        HttpStatusCode.BadRequest
                    client.asMember(organizer, "POST", "/ev/$event/guests/remove?identity=evg-1111").status shouldBe HttpStatusCode.OK
                    live.removed.last().second shouldBe "evg-1111"
                }
            }
        }

        test("the encounter-space picker lists active spaces for BOARD/ADMIN only, with the members-only marker, never an archived one") {
            testApplication {
                application { setUp() }
                val open = fx.space(membersOnly = false)
                val closed = fx.space(membersOnly = true)
                val archived = fx.space(archived = true)
                val answer = client.asMember(board, "GET", "/ev/spaces").bodyAsText()
                answer shouldContain "$open:false"
                answer shouldContain "$closed:true"
                (answer.contains(archived.toString())) shouldBe false
                client.asMember(fx.member(), "GET", "/ev/spaces").status shouldBe HttpStatusCode.Forbidden
            }
        }

        // ── lifecycle ───────────────────────────────────────────────────────────────────────

        test(
            "createEvent: a Lapis conference needs no place or link, shows its window, and refuses a link or a members-only deadline mix it cannot hold",
        ) {
            testApplication {
                application { setUp() }
                val created = client.asMember(board, "POST", "/ev/create?kind=LAPIS_CONFERENCE").bodyAsText()
                created shouldStartWith "OK|"
                created shouldContain "|LAPIS_CONFERENCE|null|null|2030-06-15T17:45|2030-06-15T21:00"
                client.asMember(board, "POST", "/ev/create?kind=LAPIS_CONFERENCE&url=https://meet.example/x").status shouldBe
                    HttpStatusCode.BadRequest
                client.asMember(board, "POST", "/ev/create?kind=LAPIS_ENCOUNTER").status shouldBe HttpStatusCode.BadRequest
                client.asMember(board, "POST", "/ev/create?kind=LAPIS_ENCOUNTER&space=${Uuid.random()}").status shouldBe
                    HttpStatusCode.BadRequest
                val space = fx.space()
                client.asMember(board, "POST", "/ev/create?kind=LAPIS_ENCOUNTER&space=$space").bodyAsText() shouldContain
                    "|LAPIS_ENCOUNTER|$space|"
                client.asMember(board, "POST", "/ev/create?kind=LAPIS_ENCOUNTER&space=$space&deadline=2030-06-14T12:00").status shouldBe
                    HttpStatusCode.BadRequest
                client.asMember(board, "POST", "/ev/create?kind=LAPIS_CONFERENCE").status shouldBe HttpStatusCode.OK
                client.asMember(fx.member(), "POST", "/ev/create?kind=LAPIS_CONFERENCE").status shouldBe HttpStatusCode.Forbidden
            }
        }

        test(
            "createEvent: an old client that only sends a link keeps its meaning (EXTERNAL_LINK); without conferencing a Lapis kind is refused",
        ) {
            testApplication {
                application { setUp() }
                client.asMember(board, "POST", "/ev/create?kind=NONE&url=https://meet.example/x").bodyAsText() shouldContain
                    "|EXTERNAL_LINK|null|https://meet.example/x|"
            }
            testApplication {
                application { setUp(EventOnlineServices.DISABLED) }
                client.asMember(board, "POST", "/ev/create?kind=LAPIS_CONFERENCE").status shouldBe HttpStatusCode.BadRequest
                client.asMember(board, "POST", "/ev/create?kind=EXTERNAL_LINK&url=https://meet.example/x").status shouldBe HttpStatusCode.OK
            }
        }

        test(
            "updateEvent: the kind cannot change while the conference runs; moving the event later extends the window, an ended one is provisioned afresh",
        ) {
            TimeTestSupport.withServerClock(instant = inside) {
                testApplication {
                    application { setUp() }
                    val event = fx.event(createdBy = board)
                    client.asMember(fx.member(), "POST", "/ev/$event/join").bodyAsText() shouldStartWith "JOINED|"
                    // running: refused
                    client.asMember(board, "POST", "/ev/$event/update?kind=EXTERNAL_LINK&location=Saal").status shouldBe
                        HttpStatusCode.Conflict
                    client.asMember(board, "POST", "/ev/$event/update?kind=NONE&location=Saal").status shouldBe HttpStatusCode.Conflict
                    // the same kind, later end: window end moves later
                    client.asMember(board, "POST", "/ev/$event/update?kind=LAPIS_CONFERENCE&ends=2030-06-15T22:00").status shouldBe
                        HttpStatusCode.OK
                    transaction {
                        val closes =
                            EventConferenceTable
                                .selectAll()
                                .where {
                                    EventConferenceTable.eventId eq event
                                }.single()[EventConferenceTable.windowClosesAt]
                        closes shouldBe LocalDateTime(2030, 6, 16, 3, 0) // 22:00 New York + 60 min = 03:00Z next day
                    }
                    // an EARLIER end never cuts a running meeting short
                    client.asMember(board, "POST", "/ev/$event/update?kind=LAPIS_CONFERENCE&ends=2030-06-15T19:00").status shouldBe
                        HttpStatusCode.OK
                    transaction {
                        EventConferenceTable
                            .selectAll()
                            .where {
                                EventConferenceTable.eventId eq event
                            }.single()[EventConferenceTable.windowClosesAt] shouldBe
                            LocalDateTime(2030, 6, 16, 3, 0)
                    }
                    // an ENDED conference is reset by a rescheduling and provisioned afresh
                    val other = fx.event(createdBy = board)
                    client.asMember(fx.member(), "POST", "/ev/$other/join").bodyAsText() shouldStartWith "JOINED|"
                    transaction {
                        EventConferenceTable.update({ EventConferenceTable.eventId eq other }) {
                            it[status] =
                                EventConferenceStatus.ENDED
                        }
                    }
                    client
                        .asMember(
                            board,
                            "POST",
                            "/ev/$other/update?kind=LAPIS_CONFERENCE&starts=2030-06-15T19:00&ends=2030-06-15T21:00",
                        ).status shouldBe
                        HttpStatusCode.OK
                    transaction { EventConferenceTable.selectAll().where { EventConferenceTable.eventId eq other }.count() } shouldBe 0L
                }
            }
        }

        test("updateEvent: while the conference runs, narrowing who may take part is vetoed; loosening it and an idle event are not") {
            TimeTestSupport.withServerClock(instant = inside) {
                testApplication {
                    application { setUp() }
                    val event = fx.event(createdBy = board)
                    // not running yet: tightening is fine (nobody is connected)
                    val idle = fx.event(createdBy = board)
                    client.asMember(board, "POST", "/ev/$idle/update?kind=LAPIS_CONFERENCE&visibility=MEMBERS_ONLY").status shouldBe
                        HttpStatusCode.OK
                    client.asMember(fx.member(), "POST", "/ev/$event/join").bodyAsText() shouldStartWith "JOINED|"
                    // running: public -> members-only and a new registration deadline are refused, the stored event stays public
                    client.asMember(board, "POST", "/ev/$event/update?kind=LAPIS_CONFERENCE&visibility=MEMBERS_ONLY").status shouldBe
                        HttpStatusCode.Conflict
                    client.asMember(board, "POST", "/ev/$event/update?kind=LAPIS_CONFERENCE&deadline=2030-06-14T12:00").status shouldBe
                        HttpStatusCode.Conflict
                    // an unchanged public, deadline-free edit still works
                    client.asMember(board, "POST", "/ev/$event/update?kind=LAPIS_CONFERENCE&ends=2030-06-15T22:00").status shouldBe
                        HttpStatusCode.OK
                }
            }
        }

        test("updateEvent: switching a not-yet-started event away from a conference removes its bookkeeping") {
            testApplication {
                application { setUp() }
                val event = fx.event(createdBy = board)
                transaction {
                    network.lapis.cloud.server.events.EventConferenceStore.insertPending(
                        eventId = event,
                        claimToken = Uuid.random(),
                        livekitRoomName = "lc-x",
                        now = LocalDateTime(2030, 6, 15, 21, 0),
                        window =
                            network.lapis.cloud.server.events.EventOnlineWindow.of(
                                startsAt = start,
                                endsAt = end,
                                orgZone = kotlinx.datetime.TimeZone.of("America/New_York"),
                            ),
                    )
                }
                client.asMember(board, "POST", "/ev/$event/update?kind=NONE&location=Saal").status shouldBe HttpStatusCode.OK
                transaction { EventConferenceTable.selectAll().where { EventConferenceTable.eventId eq event }.count() } shouldBe 0L
            }
        }

        test(
            "cancelEvent ends the running conference right away (the room is closed, LiveKit told); the poller would only ever be the safety net",
        ) {
            TimeTestSupport.withServerClock(instant = inside) {
                testApplication {
                    application { setUp() }
                    val event = fx.event(createdBy = board)
                    client.asMember(fx.member(), "POST", "/ev/$event/join").bodyAsText() shouldStartWith "JOINED|"
                    val room = roomOf(event)
                    client.asMember(board, "POST", "/ev/$event/cancel").status shouldBe HttpStatusCode.OK
                    transaction {
                        EventConferenceTable
                            .selectAll()
                            .where {
                                EventConferenceTable.eventId eq event
                            }.single()[EventConferenceTable.status] shouldBe
                            EventConferenceStatus.ENDED
                        (
                            ConferenceRoomTable
                                .selectAll()
                                .where {
                                    ConferenceRoomTable.id eq room
                                }.single()[ConferenceRoomTable.endedAt] !=
                                null
                        ) shouldBe
                            true
                    }
                }
            }
        }

        test("a series of two occurrences has two conferences -- one per event, created on their first joins") {
            var ids: List<Uuid> = emptyList()
            // created well before the first occurrence (an event may not start in the past)
            TimeTestSupport.withServerClock(instant = "2030-06-01T12:00:00Z") {
                testApplication {
                    application { setUp() }
                    val created = client.asMember(board, "POST", "/ev/series").bodyAsText()
                    created shouldStartWith "OK|"
                    ids = created.removePrefix("OK|").split(",").map { Uuid.parse(it) }
                }
            }
            ids.size shouldBe 2
            transaction {
                ids
                    .map { id ->
                        EventTable.selectAll().where { EventTable.id eq id }.single()[EventTable.onlineKind]
                    }.toSet()
            } shouldBe
                setOf(EventOnlineKind.LAPIS_CONFERENCE)
            TimeTestSupport.withServerClock(instant = inside) {
                testApplication {
                    application { setUp() }
                    val first = ids.first()
                    val second = ids.last()
                    client.asMember(fx.member(), "POST", "/ev/$first/join").bodyAsText() shouldStartWith "JOINED|"
                    transaction { EventConferenceTable.selectAll().where { EventConferenceTable.eventId eq first }.count() } shouldBe 1L
                    transaction { EventConferenceTable.selectAll().where { EventConferenceTable.eventId eq second }.count() } shouldBe 0L
                }
            }
        }

        // ── the ordinary conference surface cannot reach an event room ──────────────────────

        test(
            "the ordinary conference RPCs refuse an event room: join, guest info, room, roster (for outsiders), rename, guest access -- and the room is not in the room list",
        ) {
            TimeTestSupport.withServerClock(instant = inside) {
                testApplication {
                    application { setUp() }
                    val organizer = fx.member(name = "Organisatorin")
                    val event = fx.event(createdBy = organizer, registrationClosesAt = LocalDateTime(2030, 6, 14, 12, 0))
                    val registered = fx.member()
                    fx.registration(eventId = event, memberId = registered, status = EventRegistrationStatus.CONFIRMED)
                    val outsider = fx.member()
                    client.asMember(registered, "POST", "/ev/$event/join").bodyAsText() shouldStartWith "JOINED|"
                    val room = roomOf(event)

                    // somebody who merely HAS the room id cannot use the ordinary join to get around the registration rule
                    listOf("join", "info", "get", "participants", "voting").forEach { op ->
                        client.asMember(outsider, "POST", "/conf/$op/$room").status shouldBe HttpStatusCode.Forbidden
                    }
                    // not even the registered participant may use the ORDINARY join for it (a second token would bypass the re-check)
                    client.asMember(registered, "POST", "/conf/join/$room").status shouldBe HttpStatusCode.Forbidden
                    client.asMember(registered, "POST", "/conf/info/$room").status shouldBe HttpStatusCode.Forbidden
                    // the participant (open participation) may read the room and its roster; the moderators too
                    client.asMember(registered, "POST", "/conf/get/$room").status shouldBe HttpStatusCode.OK
                    client.asMember(registered, "POST", "/conf/participants/$room").status shouldBe HttpStatusCode.OK
                    client.asMember(organizer, "POST", "/conf/get/$room").status shouldBe HttpStatusCode.OK
                    client.asMember(organizer, "POST", "/conf/participants/$room").status shouldBe HttpStatusCode.OK
                    // who may enter is decided by the event, never by a room toggle; the title is the event's
                    client.asMember(organizer, "POST", "/conf/rename/$room").status shouldBe HttpStatusCode.Forbidden
                    client.asMember(organizer, "POST", "/conf/guestaccess/$room").status shouldBe HttpStatusCode.Forbidden
                    // the room is not enumerable
                    client.asMember(outsider, "GET", "/conf/list").bodyAsText() shouldBe
                        client.asMember(outsider, "GET", "/conf/list").bodyAsText()
                    client.asMember(outsider, "GET", "/conf/list").bodyAsText().contains(room.toString()) shouldBe false
                }
            }
        }

        test(
            "a moderator may end an event room early; the conference then reads as over and nobody gets back in with the ordinary join either",
        ) {
            TimeTestSupport.withServerClock(instant = inside) {
                testApplication {
                    application { setUp() }
                    val organizer = fx.member(name = "Organisatorin")
                    val event = fx.event(createdBy = organizer)
                    val member = fx.member()
                    client.asMember(member, "POST", "/ev/$event/join").bodyAsText() shouldStartWith "JOINED|"
                    val room = roomOf(event)
                    client.asMember(member, "POST", "/conf/end/$room").status shouldBe HttpStatusCode.Forbidden
                    client.asMember(organizer, "POST", "/conf/end/$room").status shouldBe HttpStatusCode.OK
                    transaction {
                        ConferenceRoomTable.selectAll().where { ConferenceRoomTable.id eq room }.single()[ConferenceRoomTable.endedAt] !=
                            null
                    } shouldBe true
                    // a later join through the event reads the ended room: ENDED, and no second room is created
                    client.asMember(member, "POST", "/ev/$event/join").bodyAsText() shouldStartWith "ENDED|"
                    fx.roomIdsOf(event).size shouldBe 1
                    transaction {
                        ConferenceParticipationTable.selectAll().where { ConferenceParticipationTable.roomId eq room }.all {
                            it[ConferenceParticipationTable.leftAt] !=
                                null
                        }
                    } shouldBe true
                }
            }
        }
    })
