package network.lapis.cloud.server.events

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.comparables.shouldBeGreaterThanOrEqualTo
import io.kotest.matchers.comparables.shouldBeLessThanOrEqualTo
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.datetime.TimeZone
import network.lapis.cloud.server.conference.ConferenceAudioGrantState
import network.lapis.cloud.server.conference.ConferenceNotesState
import network.lapis.cloud.server.conference.ConferenceWhiteboardState
import network.lapis.cloud.server.db.DevSeedData
import network.lapis.cloud.server.db.generated.ConferenceRoomTable
import network.lapis.cloud.server.db.generated.EventConferenceStatus
import network.lapis.cloud.server.db.generated.EventConferenceTable
import network.lapis.cloud.server.rpc.ConferenceRoomCloser
import network.lapis.cloud.server.testdb.TestDatabase
import network.lapis.cloud.server.testdb.installLaneGuards
import network.lapis.cloud.server.time.TimeTestSupport
import network.lapis.cloud.shared.domain.AccountRole
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * Welle V1.9.113 -- [EventConferenceProvisioner] on H2 AND PostgreSQL. The claim protocol is the whole point: the room of an event is created
 * EXACTLY ONCE, also when two or ten first joins arrive at the same moment (counted at the fake LiveKit and in `conference_room`), a failed
 * LiveKit call is retried by the next join, a crashed claimer's `PENDING` row is taken over, a superseded claimer can neither activate the room
 * nor leave a room row behind (the fencing token), an empty room that LiveKit forgot is re-created under the same name, a room a moderator
 * ended ends the event conference, and the sweep ends exactly the conferences whose window is over. No LiveKit call runs inside a
 * transaction (the fake would not notice, but the protocol runs `createRoom` after the claim committed -- a task that holds `createRoom`
 * open while other joins read the row proves it).
 */
abstract class EventConferenceProvisioningScenarios(
    private val db: TestDatabase,
) : FunSpec({
        val fx = EventOnlineFixtures()
        lateinit var organizer: Uuid

        // 2030-06-15 18:00-20:00 in New York = 22:00Z-00:00Z; window 21:30Z-01:00Z.
        val open = Instant.parse("2030-06-15T22:30:00Z")

        beforeSpec {
            db.activate()
            if (!db.isPostgres) DevSeedData.seedIfEmpty(force = true)
            TimeTestSupport.setOrganizationZone("America/New_York")
            organizer = fx.member(role = AccountRole.MEMBER)
        }
        installLaneGuards(db = db)
        afterSpec {
            fx.cleanUp()
            TimeTestSupport.resetOrganizationZone()
            db.deactivate()
        }

        fun provisioner(
            live: EventFakeLiveKit,
            retryDelay: kotlin.time.Duration = 25.milliseconds,
            maxWaits: Int = 40,
            takeoverAfter: kotlin.time.Duration = 30.seconds,
        ) = EventConferenceProvisioner(
            config = EVENT_ONLINE_CONFIG,
            liveKit = live,
            closer =
                ConferenceRoomCloser(
                    liveKitAdminClient = live,
                    whiteboardState = ConferenceWhiteboardState(),
                    notesState = ConferenceNotesState(),
                    audioGrantState = ConferenceAudioGrantState(),
                ),
            retryDelay = retryDelay,
            maxWaits = maxWaits,
            pendingTakeoverAfter = takeoverAfter,
        )

        fun conferenceRow(eventId: Uuid) =
            transaction { EventConferenceTable.selectAll().where { EventConferenceTable.eventId eq eventId }.singleOrNull() }

        fun roomRows(eventId: Uuid): Int = fx.roomIdsOf(eventId).size

        test("the first join creates the room, a second join gets the same one -- LiveKit was asked exactly once") {
            val live = EventFakeLiveKit()
            val p = provisioner(live)
            val event = fx.event(createdBy = organizer)
            val first =
                runBlocking {
                    p.ensureRoom(
                        eventId = event,
                        now = open,
                    )
                }.shouldBeInstanceOf<EventConferenceProvisioner.Ready.Room>()
            val second =
                runBlocking {
                    p.ensureRoom(
                        eventId = event,
                        now = open,
                    )
                }.shouldBeInstanceOf<EventConferenceProvisioner.Ready.Room>()
            second.roomId shouldBe first.roomId
            live.createCalls.get() shouldBe 1
            roomRows(event) shouldBe 1
            conferenceRow(event)!![EventConferenceTable.status] shouldBe EventConferenceStatus.ACTIVE
            // the room belongs to the organizer (so the organizer is the moderator of the room) and carries the event's title and window
            transaction {
                val room = ConferenceRoomTable.selectAll().where { ConferenceRoomTable.id eq first.roomId }.single()
                room[ConferenceRoomTable.createdByMemberId] shouldBe organizer
                room[ConferenceRoomTable.title] shouldBe "Online-Event"
                room[ConferenceRoomTable.allowFederationGuests] shouldBe true // a PUBLIC event
            }
        }

        listOf(2, 10).forEach { parallel ->
            test("$parallel concurrent first joins create exactly one room and one LiveKit room, and all end up in the same room") {
                val live = EventFakeLiveKit()
                live.createGate = CompletableDeferred()
                val p = provisioner(live)
                val event = fx.event(createdBy = organizer)
                val results =
                    runBlocking {
                        val calls = (1..parallel).map { async(Dispatchers.IO) { p.ensureRoom(eventId = event, now = open) } }
                        // The winner holds createRoom open: everybody else reads a PENDING row and waits OUTSIDE any transaction.
                        delay(200.milliseconds)
                        live.createGate!!.complete(Unit)
                        calls.awaitAll()
                    }
                live.createCalls.get() shouldBe 1
                roomRows(event) shouldBe 1
                val rooms = results.filterIsInstance<EventConferenceProvisioner.Ready.Room>()
                rooms.size shouldBe parallel
                rooms.map { it.roomId }.toSet() shouldHaveSize 1
            }
        }

        test("joins that cannot wait long enough are told the room is being prepared -- never a second room") {
            val live = EventFakeLiveKit()
            live.createGate = CompletableDeferred()
            val p = provisioner(live, retryDelay = 5.milliseconds, maxWaits = 2)
            val event = fx.event(createdBy = organizer)
            val results =
                runBlocking {
                    val winner = async(Dispatchers.IO) { p.ensureRoom(eventId = event, now = open) }
                    delay(150.milliseconds)
                    val impatient = p.ensureRoom(eventId = event, now = open)
                    impatient shouldBe EventConferenceProvisioner.Ready.Preparing
                    live.createGate!!.complete(Unit)
                    winner.await()
                }
            results.shouldBeInstanceOf<EventConferenceProvisioner.Ready.Room>()
            live.createCalls.get() shouldBe 1
            roomRows(event) shouldBe 1
        }

        test("a failed LiveKit call marks the claim FAILED (no room row) and the next join takes it over") {
            val live = EventFakeLiveKit()
            val p = provisioner(live)
            val event = fx.event(createdBy = organizer)
            live.failCreate = true
            runBlocking { p.ensureRoom(eventId = event, now = open) } shouldBe EventConferenceProvisioner.Ready.Failed
            conferenceRow(event)!![EventConferenceTable.status] shouldBe EventConferenceStatus.FAILED
            roomRows(event) shouldBe 0
            live.failCreate = false
            runBlocking { p.ensureRoom(eventId = event, now = open) }.shouldBeInstanceOf<EventConferenceProvisioner.Ready.Room>()
            conferenceRow(event)!![EventConferenceTable.status] shouldBe EventConferenceStatus.ACTIVE
            roomRows(event) shouldBe 1
            live.createCalls.get() shouldBe 2
        }

        test(
            "a PENDING row written under the old 15-minute lead is deleted by the next save of the event (self-healing, no migration)",
        ) {
            val event = fx.event(createdBy = organizer)
            val oldWindow =
                EventOnlineWindow.Window(
                    opensAt = Instant.parse("2030-06-15T21:45:00Z"),
                    closesAt = Instant.parse("2030-06-16T01:00:00Z"),
                )
            transaction {
                EventConferenceStore.insertPending(
                    eventId = event,
                    claimToken = Uuid.random(),
                    livekitRoomName = "lc-stale-${Uuid.random()}",
                    now = EventOnlineWindow.toUtc(open),
                    window = oldWindow,
                )
            }
            conferenceRow(event) shouldNotBe null
            transaction {
                EventOnlineLifecycle.onEventSaved(
                    eventId = event,
                    kind = network.lapis.cloud.shared.domain.EventOnlineKind.LAPIS_CONFERENCE,
                    startsAt = kotlinx.datetime.LocalDateTime(2030, 6, 15, 18, 0),
                    endsAt = kotlinx.datetime.LocalDateTime(2030, 6, 15, 20, 0),
                    previousVisibility = network.lapis.cloud.shared.domain.EventVisibility.PUBLIC,
                    newVisibility = network.lapis.cloud.shared.domain.EventVisibility.PUBLIC,
                    previousRegistrationClosesAt = null,
                    newRegistrationClosesAt = null,
                )
            }
            conferenceRow(event) shouldBe null
        }

        test("a PENDING claim of a crashed request is taken over once it is old enough, a fresh one is waited for") {
            val live = EventFakeLiveKit()
            val event = fx.event(createdBy = organizer)
            // a claimer that crashed before it reached LiveKit: PENDING, claimed 5 minutes ago
            transaction {
                EventConferenceStore.insertPending(
                    eventId = event,
                    claimToken = Uuid.random(),
                    livekitRoomName = "lc-orphan-${Uuid.random()}",
                    now = EventOnlineWindow.toUtc(open - 5.minutes),
                    window =
                        EventOnlineWindow.of(
                            startsAt = kotlinx.datetime.LocalDateTime(2030, 6, 15, 18, 0),
                            endsAt = kotlinx.datetime.LocalDateTime(2030, 6, 15, 20, 0),
                            orgZone = TimeZone.of("America/New_York"),
                        ),
                )
            }
            // a join that is not willing to wait long treats a FRESH claim as "being prepared"
            val impatient = provisioner(live, retryDelay = 1.milliseconds, maxWaits = 1, takeoverAfter = 10.minutes)
            runBlocking { impatient.ensureRoom(eventId = event, now = open) } shouldBe EventConferenceProvisioner.Ready.Preparing
            live.createCalls.get() shouldBe 0
            // ... and an old one (older than the take-over age) is taken over by the next join
            runBlocking { provisioner(live, takeoverAfter = 30.seconds).ensureRoom(eventId = event, now = open) }
                .shouldBeInstanceOf<EventConferenceProvisioner.Ready.Room>()
            live.createCalls.get() shouldBe 1
            roomRows(event) shouldBe 1
        }

        test("fencing: a claimer that was taken over cannot activate its room, leaves no room row, and deletes the LiveKit room it made") {
            val live = EventFakeLiveKit()
            live.createGate = CompletableDeferred()
            val p = provisioner(live)
            val event = fx.event(createdBy = organizer)
            val result =
                runBlocking {
                    val slow = async(Dispatchers.IO) { p.ensureRoom(eventId = event, now = open) }
                    delay(150.milliseconds)
                    // somebody else took the claim over while the slow claimer sat in createRoom
                    transaction {
                        EventConferenceTable.update(
                            { EventConferenceTable.eventId eq event },
                        ) { it[claimToken] = Uuid.random() }
                    }
                    live.createGate!!.complete(Unit)
                    slow.await()
                }
            result shouldBe EventConferenceProvisioner.Ready.Preparing
            roomRows(event) shouldBe 0
            conferenceRow(event)!![EventConferenceTable.status] shouldBe EventConferenceStatus.PENDING
            transaction { ConferenceRoomTable.selectAll().count() } // the table stays readable
            live.deleted.size shouldBe 1
        }

        test(
            "an empty room that LiveKit forgot (empty timeout) is re-created under the same name -- the conference does not end inside its window",
        ) {
            val live = EventFakeLiveKit()
            val p = provisioner(live)
            val event = fx.event(createdBy = organizer)
            val first =
                runBlocking {
                    p.ensureRoom(
                        eventId = event,
                        now = open,
                    )
                }.shouldBeInstanceOf<EventConferenceProvisioner.Ready.Room>()
            live.forgetRoom(first.livekitRoomName)
            val again =
                runBlocking {
                    p.ensureRoom(
                        eventId = event,
                        now = open,
                    )
                }.shouldBeInstanceOf<EventConferenceProvisioner.Ready.Room>()
            again.roomId shouldBe first.roomId
            again.livekitRoomName shouldBe first.livekitRoomName
            live.createCalls.get() shouldBe 2
            live.rooms.containsKey(first.livekitRoomName) shouldBe true
            roomRows(event) shouldBe 1
        }

        test("a room that a moderator ended through endRoom ends the event conference (ENDED), no new room is made") {
            val live = EventFakeLiveKit()
            val p = provisioner(live)
            val event = fx.event(createdBy = organizer)
            val room = runBlocking { p.ensureRoom(eventId = event, now = open) }.shouldBeInstanceOf<EventConferenceProvisioner.Ready.Room>()
            transaction {
                ConferenceRoomTable.update({ ConferenceRoomTable.id eq room.roomId }) {
                    it[endedAt] =
                        network.lapis.cloud.server.db.DbClock
                            .nowLocalDateTime()
                }
            }
            runBlocking { p.ensureRoom(eventId = event, now = open) } shouldBe EventConferenceProvisioner.Ready.Ended
            conferenceRow(event)!![EventConferenceTable.status] shouldBe EventConferenceStatus.ENDED
            live.createCalls.get() shouldBe 1
        }

        test(
            "seats: the event's capacity, the default of 25 without one, never more than the server ceiling; the empty timeout is clamped",
        ) {
            val live = EventFakeLiveKit()
            val p = provisioner(live)
            val small = fx.event(createdBy = organizer, capacity = 10)
            val unbounded = fx.event(createdBy = organizer, capacity = null)
            val huge = fx.event(createdBy = organizer, capacity = 5000)
            runBlocking {
                p.ensureRoom(eventId = small, now = open)
                p.ensureRoom(eventId = unbounded, now = open)
                p.ensureRoom(eventId = huge, now = open)
            }
            live.createdParams.map { it.first } shouldBe listOf(10, 25, 100)
            // 22:30Z .. window end 01:00Z next day = 2h30 left -> 9000 s, inside [300 s, 24 h]
            live.createdParams.forEach {
                it.second shouldBeGreaterThanOrEqualTo 300
                it.second shouldBeLessThanOrEqualTo 24 * 3600
            }
            live.createdParams.first().second shouldBe 9000
            // a join in the last seconds of the window still yields at least the default empty timeout
            val late = fx.event(createdBy = organizer)
            runBlocking { p.ensureRoom(eventId = late, now = Instant.parse("2030-06-16T00:59:50Z")) }
            live.createdParams.last().second shouldBe 300
        }

        test(
            "the sweep ends exactly the conferences whose window is over; a running one and a LiveKit failure leave the conference as it is",
        ) {
            val live = EventFakeLiveKit()
            val p = provisioner(live)
            // earlier events of this spec stay untouched: these two ran in May 2030, the sweep clock is in May 2030
            val may = kotlinx.datetime.LocalDateTime(2030, 5, 1, 18, 0)
            val mayEnd = kotlinx.datetime.LocalDateTime(2030, 5, 1, 20, 0)
            val mayOpen = Instant.parse("2030-05-01T22:30:00Z")
            val done = fx.event(createdBy = organizer, startsAt = may, endsAt = mayEnd)
            val stuck = fx.event(createdBy = organizer, startsAt = may, endsAt = mayEnd)
            val running =
                fx.event(
                    createdBy = organizer,
                    startsAt = kotlinx.datetime.LocalDateTime(2030, 5, 2, 18, 0),
                    endsAt = kotlinx.datetime.LocalDateTime(2030, 5, 2, 20, 0),
                )
            val doneRoom =
                runBlocking {
                    p.ensureRoom(
                        eventId = done,
                        now = mayOpen,
                    )
                }.shouldBeInstanceOf<EventConferenceProvisioner.Ready.Room>()
            val stuckRoom =
                runBlocking {
                    p.ensureRoom(
                        eventId = stuck,
                        now = mayOpen,
                    )
                }.shouldBeInstanceOf<EventConferenceProvisioner.Ready.Room>()
            runBlocking { p.ensureRoom(eventId = running, now = Instant.parse("2030-05-02T22:30:00Z")) }
            val afterWindow = Instant.parse("2030-05-02T01:30:00Z")
            // LiveKit refuses to close the room of `stuck`: it stays ACTIVE for the next sweep
            val refusing =
                object : network.lapis.cloud.server.conference.LiveKitAdminClient by live {
                    override suspend fun deleteRoom(name: String) {
                        if (name == stuckRoom.livekitRoomName) {
                            throw network.lapis.cloud.server.conference
                                .LiveKitAdminException(message = "down")
                        }
                        live.deleteRoom(name)
                    }
                }
            val sweeping =
                EventConferenceProvisioner(
                    config = EVENT_ONLINE_CONFIG,
                    liveKit = refusing,
                    closer =
                        ConferenceRoomCloser(
                            liveKitAdminClient = refusing,
                            whiteboardState = ConferenceWhiteboardState(),
                            notesState = ConferenceNotesState(),
                            audioGrantState = ConferenceAudioGrantState(),
                        ),
                )
            runBlocking { sweeping.endExpired(now = afterWindow) } shouldBe 1
            conferenceRow(done)!![EventConferenceTable.status] shouldBe EventConferenceStatus.ENDED
            transaction {
                ConferenceRoomTable.selectAll().where { ConferenceRoomTable.id eq doneRoom.roomId }.single()[ConferenceRoomTable.endedAt] !=
                    null
            } shouldBe true
            live.deleted.contains(doneRoom.livekitRoomName) shouldBe true
            conferenceRow(running)!![EventConferenceTable.status] shouldBe EventConferenceStatus.ACTIVE
            conferenceRow(stuck)!![EventConferenceTable.status] shouldBe EventConferenceStatus.ACTIVE
            // the next sweep (LiveKit back) finishes the job
            runBlocking { p.endExpired(now = afterWindow) } shouldBe 1
            conferenceRow(stuck)!![EventConferenceTable.status] shouldBe EventConferenceStatus.ENDED
            conferenceRow(running)!![EventConferenceTable.status] shouldBe EventConferenceStatus.ACTIVE
        }

        test("a PENDING or FAILED claim of an event whose window is over is ended without a room ever having existed") {
            val live = EventFakeLiveKit()
            val p = provisioner(live)
            val failed = fx.event(createdBy = organizer)
            live.failCreate = true
            runBlocking { p.ensureRoom(eventId = failed, now = open) } shouldBe EventConferenceProvisioner.Ready.Failed
            live.failCreate = false
            runBlocking { p.endExpired(now = Instant.parse("2030-06-16T01:30:00Z")) }
            conferenceRow(failed)!![EventConferenceTable.status] shouldBe EventConferenceStatus.ENDED
            roomRows(failed) shouldBe 0
        }

        test("cancelling an event ends its running conference right away; without a conference it is a no-op") {
            val live = EventFakeLiveKit()
            val p = provisioner(live)
            val event = fx.event(createdBy = organizer)
            val none = fx.event(createdBy = organizer)
            val room = runBlocking { p.ensureRoom(eventId = event, now = open) }.shouldBeInstanceOf<EventConferenceProvisioner.Ready.Room>()
            runBlocking { p.endNow(eventId = event, now = open) }
            runBlocking { p.endNow(eventId = none, now = open) }
            conferenceRow(event)!![EventConferenceTable.status] shouldBe EventConferenceStatus.ENDED
            live.deleted.contains(room.livekitRoomName) shouldBe true
            conferenceRow(none) shouldBe null
        }

        test("ensureRoom does not run LiveKit inside a transaction: an open createRoom does not block reads of the claim") {
            val live = EventFakeLiveKit()
            live.createGate = CompletableDeferred()
            val p = provisioner(live)
            val event = fx.event(createdBy = organizer)
            runBlocking {
                val winner = async(Dispatchers.IO) { p.ensureRoom(eventId = event, now = open) }
                delay(150.milliseconds)
                // the claim row is committed and readable while createRoom is still open (a surrounding transaction would hide it)
                withContext(Dispatchers.IO) { conferenceRow(event)!![EventConferenceTable.status] } shouldBe EventConferenceStatus.PENDING
                live.createGate!!.complete(Unit)
                winner.await().shouldBeInstanceOf<EventConferenceProvisioner.Ready.Room>()
            }
        }
    })
