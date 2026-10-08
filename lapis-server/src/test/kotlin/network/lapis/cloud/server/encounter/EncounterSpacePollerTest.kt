package network.lapis.cloud.server.encounter

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.toLocalDateTime
import network.lapis.cloud.server.db.DatabaseConfig
import network.lapis.cloud.server.db.generated.ConferenceRoomTable
import network.lapis.cloud.server.mail.FakeEncounterEntryNoticeMailer
import network.lapis.cloud.server.time.ServerClock
import network.lapis.cloud.shared.domain.EncounterSpaceRole
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid

private fun ago(duration: Duration): LocalDateTime = (ServerClock.nowInstant() - duration).toLocalDateTime(ServerClock.zone)

/**
 * Welle V1.9.61 -- [EncounterSpacePoller], the safety net of the data-protection promise: what a crashed client or a failed LiveKit
 * call leaves behind is cleaned up. Every case uses a fake LiveKit and rows inserted directly with explicit ages.
 */
class EncounterSpacePollerTest :
    FunSpec({
        val fx = EncounterFixtures()

        beforeSpec { DatabaseConfig.connect() }
        afterSpec { fx.cleanUp() }

        class World(
            val liveKit: FakeEncounterLiveKit,
            val moderation: EncounterModerationState,
            val space: Uuid,
            val steward: Uuid,
        ) {
            fun poller(enabled: Boolean = true) =
                EncounterSpacePoller(
                    liveKitAdminClient = liveKit,
                    moderationState = moderation,
                    entryNotifier = EncounterEntryNotifier(state = EncounterEntryNoticeState(), mailer = FakeEncounterEntryNoticeMailer()),
                    liveKitEnabled = enabled,
                )
        }

        suspend fun world(): World {
            val steward = fx.createMember()
            val space = fx.createSpace(createdBy = fx.createMember())
            fx.setRole(spaceId = space, memberId = steward, role = EncounterSpaceRole.STEWARD)
            return World(liveKit = FakeEncounterLiveKit(), moderation = EncounterModerationState(), space = space, steward = steward)
        }

        /** A session whose LiveKit room exists (registered in the fake) and whose room row is [age] old. */
        suspend fun World.liveSession(age: Duration = 5.minutes): Uuid {
            val room = fx.insertSession(spaceId = space, openedBy = steward, createdAt = ago(age))
            liveKit.createRoom(name = fx.livekitName(room), maxParticipants = 25, emptyTimeoutSeconds = 1800)
            return room
        }

        test("presence rows of people who are no longer connected (and older than 2 minutes) are deleted; live and fresh ones stay") {
            runBlocking {
                val w = world()
                val room = w.liveSession()
                val live = fx.createMember()
                val crashed = fx.createMember()
                val justJoined = fx.createMember()
                fx.insertParticipation(roomId = room, memberId = live, joinedAt = ago(10.minutes))
                fx.insertParticipation(roomId = room, memberId = crashed, joinedAt = ago(10.minutes))
                fx.insertParticipation(roomId = room, memberId = justJoined, joinedAt = ago(20.seconds))
                w.liveKit.connect(room = fx.livekitName(room), identity = live.toString())
                val poller = w.poller()
                poller.tick()
                // first sweep only notes the absent person; the second consecutive one deletes
                fx.participationCount(room) shouldBe 3L
                poller.tick()
                fx.participationCount(room) shouldBe 2L
                transaction {
                    network.lapis.cloud.server.db.generated.ConferenceParticipationTable
                        .selectAll()
                        .where { network.lapis.cloud.server.db.generated.ConferenceParticipationTable.roomId eq room }
                        .map { it[network.lapis.cloud.server.db.generated.ConferenceParticipationTable.memberId] }
                        .toSet()
                } shouldBe setOf(live, justJoined)
            }
        }

        test("with NOBODY connected, every stale presence row goes (the whole congregation crashed)") {
            runBlocking {
                val w = world()
                val room = w.liveSession()
                repeat(3) { fx.insertParticipation(roomId = room, memberId = fx.createMember(), joinedAt = ago(10.minutes)) }
                val poller = w.poller()
                poller.tick()
                poller.tick()
                fx.participationCount(room) shouldBe 0L
                // the session itself stays open: an empty room is not an ended room
                fx.openSessionRoom(w.space) shouldBe room
            }
        }

        test("a person absent in ONE sweep only (a short reconnect gap) keeps the presence row") {
            runBlocking {
                val w = world()
                val room = w.liveSession()
                val flaky = fx.createMember()
                fx.insertParticipation(roomId = room, memberId = flaky, joinedAt = ago(10.minutes))
                val poller = w.poller()
                poller.tick() // absent
                w.liveKit.connect(room = fx.livekitName(room), identity = flaky.toString()) // reconnected
                poller.tick() // live again: the earlier absence is forgotten
                w.liveKit.forgetParticipant(room = fx.livekitName(room), identity = flaky.toString())
                poller.tick() // absent once more, not twice in a row
                fx.participationCount(room) shouldBe 1L
            }
        }

        test("a blocked person who reconnected with a still-valid token is disconnected again") {
            runBlocking {
                val w = world()
                val room = w.liveSession()
                val troll = fx.createMember()
                val fine = fx.createMember()
                w.moderation.block(sessionRoomId = room, memberId = troll)
                w.liveKit.connect(room = fx.livekitName(room), identity = troll.toString())
                w.liveKit.connect(room = fx.livekitName(room), identity = fine.toString())
                w.poller().tick()
                w.liveKit.removed shouldContainExactly listOf(fx.livekitName(room) to troll.toString())
                w.liveKit.isConnected(room = fx.livekitName(room), identity = fine.toString()) shouldBe true
            }
        }

        test(
            "a withdrawn office holder who reconnected with the old publishing token is disconnected; a current officer and a listener stay",
        ) {
            runBlocking {
                val w = world()
                val room = w.liveSession()
                val name = fx.livekitName(room)
                val exPulpit = fx.createMember()
                val listener = fx.createMember()
                w.liveKit.connect(room = name, identity = exPulpit.toString(), canPublish = true, canPublishData = true)
                w.liveKit.connect(room = name, identity = w.steward.toString(), canPublish = true, canPublishData = true)
                w.liveKit.connect(room = name, identity = listener.toString(), canPublish = false, canPublishData = true)
                w.poller().tick()
                w.liveKit.removed shouldContainExactly listOf(name to exPulpit.toString())
                w.liveKit.isConnected(room = name, identity = w.steward.toString()) shouldBe true
                w.liveKit.isConnected(room = name, identity = listener.toString()) shouldBe true
            }
        }

        test("a silenced person who reconnected with the old data-channel token is disconnected; one with the new token stays") {
            runBlocking {
                val w = world()
                val room = w.liveSession()
                val name = fx.livekitName(room)
                val noisy = fx.createMember()
                val reentered = fx.createMember()
                w.moderation.silence(sessionRoomId = room, memberId = noisy)
                w.moderation.silence(sessionRoomId = room, memberId = reentered)
                w.liveKit.connect(room = name, identity = noisy.toString(), canPublish = false, canPublishData = true)
                w.liveKit.connect(room = name, identity = reentered.toString(), canPublish = false, canPublishData = false)
                w.poller().tick()
                w.liveKit.removed shouldContainExactly listOf(name to noisy.toString())
            }
        }

        test("the moderation list reserves room for BOARD/ADMIN above the cap an office holder can fill") {
            val state = EncounterModerationState()
            val room = Uuid.random()
            repeat(EncounterModerationState.MAX_ENTRIES_PER_SESSION) {
                state.block(sessionRoomId = room, memberId = Uuid.random()) shouldBe
                    true
            }
            state.block(sessionRoomId = room, memberId = Uuid.random()) shouldBe false
            state.block(sessionRoomId = room, memberId = Uuid.random(), privileged = true) shouldBe true
        }

        test("a session whose LiveKit room is gone is closed (reason EMPTY, no actor), its rows and its moderation state removed") {
            runBlocking {
                val w = world()
                val room = w.liveSession(age = 10.minutes)
                val member = fx.createMember()
                fx.insertParticipation(roomId = room, memberId = member, joinedAt = ago(1.minutes))
                w.moderation.block(sessionRoomId = room, memberId = member)
                w.liveKit.forgetRoom(fx.livekitName(room))
                w.poller().tick()
                fx.openSessionRoom(w.space) shouldBe null
                fx.participationCount(room) shouldBe 0L
                w.moderation.isBlocked(sessionRoomId = room, memberId = member) shouldBe false
                val audit = auditEntriesOf(w.space)
                audit.map { it.second } shouldContainExactly listOf("""{"state":"CLOSED","reason":"EMPTY"}""")
                audit.single().third shouldBe null
            }
        }

        test("a YOUNG session whose room LiveKit does not list yet is left alone (ListRooms may lag right after CreateRoom)") {
            runBlocking {
                val w = world()
                val room = fx.insertSession(spaceId = w.space, openedBy = w.steward, createdAt = ago(20.seconds))
                w.poller().tick()
                fx.openSessionRoom(w.space) shouldBe room
                auditEntriesOf(w.space).shouldBeEmpty()
            }
        }

        test("a session older than the maximum duration is closed (reason MAX_DURATION) and its LiveKit room deleted") {
            runBlocking {
                val w = world()
                val room = w.liveSession(age = 13.hours)
                fx.insertParticipation(roomId = room, memberId = fx.createMember(), joinedAt = ago(1.minutes))
                w.poller().tick()
                fx.openSessionRoom(w.space) shouldBe null
                fx.participationCount(room) shouldBe 0L
                w.liveKit.deletedRooms shouldContainExactly listOf(fx.livekitName(room))
                auditEntriesOf(w.space).map { it.second } shouldContainExactly listOf("""{"state":"CLOSED","reason":"MAX_DURATION"}""")
                // a session within the limit is not touched
                val w2 = world()
                val ok = w2.liveSession(age = 11.hours)
                w2.poller().tick()
                fx.openSessionRoom(w2.space) shouldBe ok
            }
        }

        test("MAX_DURATION closes the session in the database even while LiveKit is unreachable") {
            runBlocking {
                val w = world()
                val room = w.liveSession(age = 13.hours)
                fx.insertParticipation(roomId = room, memberId = fx.createMember(), joinedAt = ago(1.minutes))
                w.liveKit.failAll = true
                w.poller().tick()
                fx.openSessionRoom(w.space) shouldBe null
                fx.participationCount(room) shouldBe 0L
                auditEntriesOf(w.space).map { it.second } shouldContainExactly listOf("""{"state":"CLOSED","reason":"MAX_DURATION"}""")
            }
        }

        test("a LiveKit room of a recently ENDED session that survived a failed deletion is deleted again") {
            runBlocking {
                val w = world()
                val room = fx.insertSession(spaceId = w.space, openedBy = w.steward, endedAt = ago(1.hours), createdAt = ago(2.hours))
                val name = fx.livekitName(room)
                w.liveKit.createRoom(name = name, maxParticipants = 25, emptyTimeoutSeconds = 1800)
                w.poller().tick()
                w.liveKit.deletedRooms shouldContainExactly listOf(name)
                // a session that ended long ago is no longer re-checked
                val w2 = world()
                val old = fx.insertSession(spaceId = w2.space, openedBy = w2.steward, endedAt = ago(30.hours), createdAt = ago(31.hours))
                w2.liveKit.createRoom(name = fx.livekitName(old), maxParticipants = 25, emptyTimeoutSeconds = 1800)
                w2.poller().tick()
                w2.liveKit.deletedRooms.shouldBeEmpty()
            }
        }

        test("LiveKit unreachable: only the database cleanup of ended sessions runs, nothing is closed, nothing throws") {
            runBlocking {
                val w = world()
                val open = w.liveSession()
                val stale = fx.createMember()
                fx.insertParticipation(roomId = open, memberId = stale, joinedAt = ago(10.minutes))
                val ended =
                    fx.insertSession(
                        spaceId = fx.createSpace(createdBy = fx.createMember()),
                        openedBy = w.steward,
                        endedAt = ago(1.hours),
                    )
                fx.insertParticipation(roomId = ended, memberId = fx.createMember())
                w.liveKit.failAll = true
                w.poller().tick()
                // (a) always runs
                fx.participationCount(ended) shouldBe 0L
                // (b), the room-gone part of (c) and (d) are skipped: the open session and its (stale) row are untouched
                fx.openSessionRoom(w.space) shouldBe open
                fx.participationCount(open) shouldBe 1L
                auditEntriesOf(w.space).shouldBeEmpty()
            }
        }

        test("liveKitEnabled = false: only the database cleanup runs") {
            runBlocking {
                val w = world()
                val open = w.liveSession(age = 13.hours)
                w.poller(enabled = false).tick()
                fx.openSessionRoom(w.space) shouldBe open
                w.liveKit.deletedRooms.shouldBeEmpty()
            }
        }

        test("a second run changes nothing (idempotent) and start/stop is safe to repeat") {
            runBlocking {
                val w = world()
                val room = w.liveSession()
                fx.insertParticipation(roomId = room, memberId = fx.createMember(), joinedAt = ago(10.minutes))
                val poller = w.poller()
                poller.tick()
                poller.tick()
                fx.participationCount(room) shouldBe 0L
                fx.openSessionRoom(w.space) shouldBe room
                poller.stop()
                poller.stop()
                transaction { ConferenceRoomTable.selectAll().where { ConferenceRoomTable.id eq room }.count() } shouldBe 1L
            }
        }
    })
