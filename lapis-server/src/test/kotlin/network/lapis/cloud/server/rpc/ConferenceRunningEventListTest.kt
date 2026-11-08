package network.lapis.cloud.server.rpc

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.install
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import network.lapis.cloud.server.conference.ConferenceConfig
import network.lapis.cloud.server.db.DatabaseConfig
import network.lapis.cloud.server.db.DbClock
import network.lapis.cloud.server.db.DevSeedData
import network.lapis.cloud.server.db.generated.ConferenceRoomTable
import network.lapis.cloud.server.db.generated.EventConferenceStatus
import network.lapis.cloud.server.db.generated.EventConferenceTable
import network.lapis.cloud.server.events.EVENT_ONLINE_CONFIG
import network.lapis.cloud.server.events.EventConferenceStore
import network.lapis.cloud.server.events.EventFakeLiveKit
import network.lapis.cloud.server.events.EventOnlineFixtures
import network.lapis.cloud.server.events.EventOnlineWindow
import network.lapis.cloud.server.federation.FederationInboxRateLimiter
import network.lapis.cloud.server.security.LoginRateLimiter
import network.lapis.cloud.server.time.ServerClock
import network.lapis.cloud.server.time.TimeTestSupport
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.EventConferenceParticipation
import network.lapis.cloud.shared.domain.EventOnlineKind
import network.lapis.cloud.shared.domain.EventRegistrationStatus
import network.lapis.cloud.shared.domain.EventStatus
import network.lapis.cloud.shared.domain.EventVisibility
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.domain.RunningEventConferenceDto
import network.lapis.cloud.shared.rpc.ConflictException
import network.lapis.cloud.shared.rpc.ForbiddenException
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * Welle V1.9.295 -- the lobby list of running event conferences. The decision rows (S1-S5, S7, S9) run [RunningEventConferenceQuery] with a
 * fixed `now` in the zone UTC; the gate / limiter / regression rows (S6, S8, S10) go through [ConferenceService] and throwaway routes.
 */
class ConferenceRunningEventListTest :
    FunSpec({
        val fx = EventOnlineFixtures()
        val live = EventFakeLiveKit()
        val zone = TimeZone.UTC
        val json = Json

        // event 18:00-20:00 UTC on 2030-06-15; the window is 17:30 .. 21:00
        val start = LocalDateTime(2030, 6, 15, 18, 0)
        val end = LocalDateTime(2030, 6, 15, 20, 0)
        val inside = Instant.parse("2030-06-15T19:00:00Z")
        val before = Instant.parse("2030-06-15T10:00:00Z")
        val after = Instant.parse("2030-06-16T10:00:00Z")

        lateinit var creator: Uuid

        beforeSpec {
            DatabaseConfig.connect()
            DevSeedData.seedIfEmpty(force = true)
            TimeTestSupport.setOrganizationZone("UTC")
            creator = fx.member(role = AccountRole.MEMBER, name = "Ersteller")
        }
        afterSpec {
            fx.cleanUp()
            TimeTestSupport.resetOrganizationZone()
        }

        /** An event with a running conference: `event_conference` ACTIVE, its room open. Returns the event id. */
        fun running(
            title: String = "Lobby-Event ${Uuid.random()}",
            kind: EventOnlineKind = EventOnlineKind.LAPIS_CONFERENCE,
            status: EventStatus = EventStatus.PUBLISHED,
            visibility: EventVisibility = EventVisibility.MEMBERS_ONLY,
            deadline: LocalDateTime? = null,
            starts: LocalDateTime = start,
            ends: LocalDateTime = end,
            conferenceStatus: String = EventConferenceStatus.ACTIVE,
            roomEnded: Boolean = false,
            createdBy: Uuid = creator,
            encounterSpaceId: Uuid? = null,
        ): Uuid {
            val eventId =
                fx.event(
                    createdBy = createdBy,
                    kind = kind,
                    status = status,
                    visibility = visibility,
                    registrationClosesAt = deadline,
                    startsAt = starts,
                    endsAt = ends,
                    title = title,
                    encounterSpaceId = encounterSpaceId,
                )
            val roomId = Uuid.random()
            val now = DbClock.nowLocalDateTime()
            transaction {
                ConferenceRoomTable.insert {
                    it[ConferenceRoomTable.id] = roomId
                    it[ConferenceRoomTable.title] = "Raum-Titel-$roomId"
                    it[description] = ""
                    it[livekitRoomName] = "ev-$roomId".take(60)
                    it[createdByMemberId] = creator
                    it[createdAt] = now
                    it[endedAt] = if (roomEnded) now else null
                    it[maxParticipants] = 25
                    it[allowFederationGuests] = false
                }
                val window = EventOnlineWindow.of(startsAt = starts, endsAt = ends, orgZone = zone)
                EventConferenceStore.insertPending(
                    eventId = eventId,
                    claimToken = Uuid.random(),
                    livekitRoomName = "ev-$roomId".take(60),
                    now = now,
                    window = window,
                )
                EventConferenceStore.activate(
                    eventId = eventId,
                    claimToken =
                        EventConferenceTable
                            .selectAll()
                            .where {
                                EventConferenceTable.eventId eq
                                    eventId
                            }.single()[EventConferenceTable.claimToken],
                    conferenceRoomId = roomId,
                )
                if (conferenceStatus != EventConferenceStatus.ACTIVE) {
                    EventConferenceTable.update({ EventConferenceTable.eventId eq eventId }) {
                        it[EventConferenceTable.status] =
                            conferenceStatus
                    }
                }
            }
            return eventId
        }

        fun list(
            memberId: Uuid,
            role: AccountRole = AccountRole.MEMBER,
            now: Instant = inside,
        ): List<RunningEventConferenceDto> =
            transaction {
                RunningEventConferenceQuery.list(current = fx.current(memberId = memberId, role = role), now = now, orgZone = zone)
            }

        fun List<RunningEventConferenceDto>.of(eventId: Uuid) = filter { it.eventId == eventId.toString() }

        test("S1: an open window of a members-only event without deadline shows OPEN_TO_YOU with id and title") {
            val viewer = fx.member()
            val event = running(title = "Offene Tagung")
            val rows = list(viewer).of(event)
            rows.size shouldBe 1
            rows.single().participation shouldBe EventConferenceParticipation.OPEN_TO_YOU
            rows.single().eventTitle shouldBe "Offene Tagung"
            rows.single().startsAt shouldBe start
            rows.single().endsAt shouldBe end
        }

        test("S2: with a deadline only a CONFIRMED registration opens the entry") {
            val viewer = fx.member()
            val event = running(deadline = LocalDateTime(2030, 6, 14, 12, 0))
            list(viewer).of(event).single().participation shouldBe EventConferenceParticipation.REGISTRATION_REQUIRED
            fx.registration(eventId = event, memberId = viewer, status = EventRegistrationStatus.WAITLISTED)
            list(viewer).of(event).single().participation shouldBe EventConferenceParticipation.REGISTRATION_REQUIRED
            val registered = fx.member()
            fx.registration(eventId = event, memberId = registered, status = EventRegistrationStatus.CONFIRMED)
            list(registered).of(event).single().participation shouldBe EventConferenceParticipation.OPEN_TO_YOU
        }

        test("S3: the organiser, BOARD and ADMIN are moderators and need no registration") {
            val event = running(deadline = LocalDateTime(2030, 6, 14, 12, 0))
            list(creator).of(event).single().participation shouldBe EventConferenceParticipation.OPEN_TO_YOU
            listOf(AccountRole.BOARD, AccountRole.ADMIN).forEach { role ->
                val m = fx.member(role = role)
                list(m, role = role).of(event).single().participation shouldBe EventConferenceParticipation.OPEN_TO_YOU
            }
        }

        test("S4: before the window the entry is NOT_YET_OPEN; without registration under a deadline it stays REGISTRATION_REQUIRED") {
            val viewer = fx.member()
            val free = running()
            val withDeadline = running(deadline = LocalDateTime(2030, 6, 14, 12, 0))
            list(viewer, now = before).of(free).single().participation shouldBe EventConferenceParticipation.NOT_YET_OPEN
            list(viewer, now = before).of(withDeadline).single().participation shouldBe EventConferenceParticipation.REGISTRATION_REQUIRED
        }

        test("S5: everything that is not a running, published Lapis conference is hidden (also for BOARD and ADMIN)") {
            val viewer = fx.member()
            val hidden =
                listOf(
                    running(status = EventStatus.DRAFT),
                    running(status = EventStatus.CANCELLED),
                    running(roomEnded = true),
                    running(conferenceStatus = EventConferenceStatus.PENDING),
                    running(conferenceStatus = EventConferenceStatus.FAILED),
                    running(conferenceStatus = EventConferenceStatus.ENDED),
                    running(kind = EventOnlineKind.LAPIS_ENCOUNTER, encounterSpaceId = fx.space()),
                    running(kind = EventOnlineKind.NONE),
                )
            listOf(AccountRole.MEMBER, AccountRole.BOARD, AccountRole.ADMIN).forEach { role ->
                val m = if (role == AccountRole.MEMBER) viewer else fx.member(role = role)
                val ids = list(m, role = role).map { it.eventId }.toSet()
                hidden.forEach { (it.toString() in ids) shouldBe false }
            }
        }

        test(
            "S5b: an event whose window is over is hidden -- also with a deadline and without a registration (FORBIDDEN must not leak through)",
        ) {
            val viewer = fx.member()
            val plain = running()
            val withDeadline = running(deadline = LocalDateTime(2030, 6, 14, 12, 0))
            list(viewer, now = after).of(plain).shouldBeEmpty()
            list(viewer, now = after).of(withDeadline).shouldBeEmpty()
            // a stale stored window (still open) must not matter: the phase check on the event times drops the row
            transaction {
                EventConferenceTable.update({ EventConferenceTable.eventId eq withDeadline }) {
                    it[windowClosesAt] = LocalDateTime(2100, 1, 1, 0, 0)
                }
            }
            list(viewer, now = after).of(withDeadline).shouldBeEmpty()
            // restore the real window so the stale row cannot use up a slot of the limit in the other tests
            transaction {
                EventConferenceTable.update({ EventConferenceTable.eventId eq withDeadline }) {
                    it[windowClosesAt] = LocalDateTime(2030, 6, 15, 21, 0)
                }
            }
        }

        test("S7: the DTO carries exactly five properties and the JSON leaks no room id, no LiveKit name, no claim token, no room title") {
            val descriptor = RunningEventConferenceDto.serializer().descriptor
            val names = (0 until descriptor.elementsCount).map { descriptor.getElementName(it) }.toSet()
            names shouldBe setOf("eventId", "eventTitle", "startsAt", "endsAt", "participation")
            val viewer = fx.member()
            val event = running()
            val text = json.encodeToString(ListSerializer(RunningEventConferenceDto.serializer()), list(viewer).of(event))
            val secrets =
                transaction {
                    val ec = EventConferenceTable.selectAll().where { EventConferenceTable.eventId eq event }.single()
                    val room =
                        ConferenceRoomTable
                            .selectAll()
                            .where { ConferenceRoomTable.id eq ec[EventConferenceTable.conferenceRoomId]!! }
                            .single()
                    listOf(
                        ec[EventConferenceTable.conferenceRoomId].toString(),
                        ec[EventConferenceTable.livekitRoomName].orEmpty(),
                        ec[EventConferenceTable.claimToken].toString(),
                        room[ConferenceRoomTable.title],
                        room[ConferenceRoomTable.livekitRoomName],
                    )
                }
            secrets.forEach { it.isNotBlank() shouldBe true }
            secrets.forEach { text shouldNotContain it }
        }

        test("S9: at most 50 rows, ordered by start and then by title") {
            val viewer = fx.member()
            (0 until 55).forEach { i ->
                val starts = LocalDateTime(2040, 1, 1 + i / 2, 18, 0)
                val ends = LocalDateTime(2040, 1, 1 + i / 2, 20, 0)
                running(title = "T${if (i % 2 == 0) "b" else "a"}-${i.toString().padStart(2, '0')}", starts = starts, ends = ends)
            }
            val rows = list(viewer, now = Instant.parse("2040-01-01T19:00:00Z"))
            rows.size shouldBe 50
            rows.zipWithNext().forEach { (a, b) ->
                (a.startsAt < b.startsAt || (a.startsAt == b.startsAt && a.eventTitle <= b.eventTitle)) shouldBe true
            }
            rows.first().eventTitle shouldBe "Ta-01"
        }

        // ── service level ───────────────────────────────────────────────────────────────────

        fun service(
            call: ApplicationCall,
            limiter: FederationInboxRateLimiter = FederationInboxRateLimiter(maxRequests = 1000, window = 1.minutes),
            config: ConferenceConfig = EVENT_ONLINE_CONFIG,
        ) = ConferenceService(
            call = call,
            liveKitAdminClient = live,
            createRoomRateLimiter = LoginRateLimiter(),
            config = config,
            listRateLimiter = limiter,
            conferenceMeetingBindRateLimiter = FederationInboxRateLimiter(maxRequests = 1000, window = 1.minutes),
            roomVotingStateRateLimiter = FederationInboxRateLimiter(maxRequests = 1000, window = 1.minutes),
        )

        fun io.ktor.server.application.Application.setUp(
            limiter: FederationInboxRateLimiter = FederationInboxRateLimiter(maxRequests = 1000, window = 1.minutes),
            config: ConferenceConfig = EVENT_ONLINE_CONFIG,
        ) {
            install(StatusPages) {
                exception<ForbiddenException> { call, _ -> call.respondText("forbidden", status = HttpStatusCode.Forbidden) }
                exception<ConflictException> { call, cause -> call.respondText(cause.message, status = HttpStatusCode.Conflict) }
            }
            routing {
                get("/cs/running") { call.respondText("n=" + service(call, limiter, config).listRunningEventConferences().size) }
                get("/cs/rooms") { call.respondText("n=" + service(call, limiter, config).listActiveRooms().size) }
                get("/cs/join") {
                    service(call, limiter, config).joinRoom(roomId = call.request.queryParameters["roomId"]!!)
                    call.respondText("joined")
                }
            }
        }

        test("S6: GUEST, FRIEND, an inactive member and a disabled conference get the same refusal as listActiveRooms") {
            val refused =
                listOf(
                    fx.member(status = MemberStatus.GUEST),
                    fx.member(status = MemberStatus.FRIEND),
                    fx.member(status = MemberStatus.WITHDRAWN),
                )
            testApplication {
                application { setUp() }
                refused.forEach { id ->
                    val a = client.get("/cs/running") { header("X-Member-Id", id.toString()) }
                    val b = client.get("/cs/rooms") { header("X-Member-Id", id.toString()) }
                    a.status shouldBe b.status
                    a.status shouldBe HttpStatusCode.Forbidden
                }
            }
            val disabled = ConferenceConfig.load { null }
            val active = fx.member()
            testApplication {
                application { setUp(config = disabled) }
                val a = client.get("/cs/running") { header("X-Member-Id", active.toString()) }
                val b = client.get("/cs/rooms") { header("X-Member-Id", active.toString()) }
                a.status shouldBe b.status
                a.status shouldBe HttpStatusCode.Conflict
            }
        }

        test("S8: joinRoom still refuses an event room and listActiveRooms does not list it") {
            val viewer = fx.member()
            val now = ServerClock.nowInstant().toLocalDateTime(zone)
            val starts = LocalDateTime(now.year, now.month, now.day, now.hour, 0)
            val event =
                running(starts = starts, ends = starts.let { LocalDateTime(it.year, it.month, it.day, 23, 59) }, title = "Echtzeit-Event")
            val roomId = fx.roomIdsOf(event).single()
            testApplication {
                application { setUp() }
                client.get("/cs/join?roomId=$roomId") { header("X-Member-Id", viewer.toString()) }.status shouldBe HttpStatusCode.Forbidden
                val rooms = client.get("/cs/rooms") { header("X-Member-Id", viewer.toString()) }
                rooms.status shouldBe HttpStatusCode.OK
            }
            val listed = transaction { ConferenceRoomTable.selectAll().where { ConferenceRoomTable.id eq roomId }.count() }
            listed shouldBe 1L
            live.rooms.keys.none { it.contains(roomId.toString()) } shouldBe true
        }

        test("S10: both list methods share one budget -- the 61st call of the minute is throttled") {
            val viewer = fx.member()
            val shared = FederationInboxRateLimiter(maxRequests = 60, window = 1.hours)
            testApplication {
                application { setUp(limiter = shared) }
                repeat(30) {
                    client.get("/cs/running") { header("X-Member-Id", viewer.toString()) }.status shouldBe HttpStatusCode.OK
                    client.get("/cs/rooms") { header("X-Member-Id", viewer.toString()) }.status shouldBe HttpStatusCode.OK
                }
                client.get("/cs/running") { header("X-Member-Id", viewer.toString()) }.status shouldBe HttpStatusCode.Conflict
                client.get("/cs/rooms") { header("X-Member-Id", viewer.toString()) }.status shouldBe HttpStatusCode.Conflict
            }
        }
    })
