package network.lapis.cloud.server.events

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.server.conference.ConferenceAudioGrantState
import network.lapis.cloud.server.conference.ConferenceNotesState
import network.lapis.cloud.server.conference.ConferenceWhiteboardState
import network.lapis.cloud.server.conference.LiveKitAdminClient
import network.lapis.cloud.server.db.DatabaseConfig
import network.lapis.cloud.server.db.DevSeedData
import network.lapis.cloud.server.db.generated.ConferenceRoomTable
import network.lapis.cloud.server.db.generated.EventConferenceStatus
import network.lapis.cloud.server.db.generated.EventConferenceTable
import network.lapis.cloud.server.rpc.ConferenceRoomCloser
import network.lapis.cloud.server.time.TimeTestSupport
import network.lapis.cloud.shared.domain.AccountRole
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * Welle V1.9.113 -- [EventConferencePoller]: one sweep ends exactly the conferences whose window is over (through the shared room closer),
 * leaves a running one alone, and never lets a failing sweep escape -- the loop of the real poller must survive anything.
 */
class EventConferencePollerTest :
    FunSpec({
        val fx = EventOnlineFixtures()
        lateinit var organizer: Uuid

        beforeSpec {
            DatabaseConfig.connect()
            DevSeedData.seedIfEmpty(force = true)
            TimeTestSupport.setOrganizationZone("America/New_York")
            organizer = fx.member(role = AccountRole.MEMBER)
        }
        afterSpec {
            fx.cleanUp()
            TimeTestSupport.resetOrganizationZone()
        }

        fun provisioner(live: LiveKitAdminClient) =
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
            )

        test("a sweep ends the conference whose window is over, closes its room, and leaves a running one") {
            val live = EventFakeLiveKit()
            val p = provisioner(live)
            val over =
                fx.event(
                    createdBy = organizer,
                    startsAt = LocalDateTime(2031, 3, 1, 18, 0),
                    endsAt = LocalDateTime(2031, 3, 1, 20, 0),
                )
            val running =
                fx.event(
                    createdBy = organizer,
                    startsAt = LocalDateTime(2031, 3, 2, 18, 0),
                    endsAt = LocalDateTime(2031, 3, 2, 20, 0),
                )
            val overRoom =
                runBlocking {
                    p.ensureRoom(eventId = over, now = Instant.parse("2031-03-01T23:30:00Z"))
                }.shouldBeInstanceOf<EventConferenceProvisioner.Ready.Room>()
            runBlocking { p.ensureRoom(eventId = running, now = Instant.parse("2031-03-02T23:30:00Z")) }
            // 02:00Z on March 2 is after the first window (closes 02:00Z... 20:00 EST + 60 min = 02:00Z) and long before the second
            val poller = EventConferencePoller(provisioner = p, clock = { Instant.parse("2031-03-02T02:30:00Z") })
            runBlocking { poller.tick() } shouldBe 1
            transaction {
                EventConferenceTable
                    .selectAll()
                    .where {
                        EventConferenceTable.eventId eq over
                    }.single()[EventConferenceTable.status] shouldBe
                    EventConferenceStatus.ENDED
                EventConferenceTable
                    .selectAll()
                    .where {
                        EventConferenceTable.eventId eq running
                    }.single()[EventConferenceTable.status] shouldBe
                    EventConferenceStatus.ACTIVE
                (
                    ConferenceRoomTable
                        .selectAll()
                        .where {
                            ConferenceRoomTable.id eq overRoom.roomId
                        }.single()[ConferenceRoomTable.endedAt] !=
                        null
                ) shouldBe
                    true
            }
            live.deleted.contains(overRoom.livekitRoomName) shouldBe true
            // nothing more to do: the next sweep ends nothing
            runBlocking { poller.tick() } shouldBe 0
        }

        test("a sweep that blows up is swallowed (the loop must survive): tick returns 0, never throws") {
            val live = EventFakeLiveKit()
            val p = provisioner(live)
            val event =
                fx.event(
                    createdBy = organizer,
                    startsAt = LocalDateTime(2031, 4, 1, 18, 0),
                    endsAt = LocalDateTime(2031, 4, 1, 20, 0),
                )
            runBlocking { p.ensureRoom(eventId = event, now = Instant.parse("2031-04-01T22:30:00Z")) }
            val exploding =
                object : LiveKitAdminClient by live {
                    override suspend fun deleteRoom(name: String): Unit = throw IllegalStateException("boom")
                }
            val poller = EventConferencePoller(provisioner = provisioner(exploding), clock = { Instant.parse("2031-04-02T05:00:00Z") })
            runBlocking { poller.tick() } shouldBe 0
            // the conference is still ACTIVE: the next sweep (with a healthy LiveKit) ends it
            transaction {
                EventConferenceTable.selectAll().where { EventConferenceTable.eventId eq event }.single()[EventConferenceTable.status]
            } shouldBe
                EventConferenceStatus.ACTIVE
            // (it also ends the still-running conference of the first test, whose window is over by then: hence at least one)
            (runBlocking { EventConferencePoller(provisioner = p, clock = { Instant.parse("2031-04-02T05:00:00Z") }).tick() } >= 1) shouldBe
                true
            transaction {
                EventConferenceTable.selectAll().where { EventConferenceTable.eventId eq event }.single()[EventConferenceTable.status]
            } shouldBe
                EventConferenceStatus.ENDED
        }

        test("start is idempotent and stop is safe, also without a start") {
            val poller = EventConferencePoller(provisioner = provisioner(EventFakeLiveKit()), intervalSeconds = 3600)
            poller.start()
            poller.start()
            poller.stop()
            poller.stop()
        }
    })
