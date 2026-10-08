package network.lapis.cloud.server.encounter

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlin.uuid.Uuid

/** Welle V1.9.76 -- [EncounterEntryNoticeState]: the claim, the five-minute grid, the throttle, the bounds. Pure unit tests, no database. */
class EncounterEntryNoticeStateTest :
    FunSpec({
        // 10:00:00 UTC is a grid boundary
        val t0 = Instant.parse("2026-10-08T10:00:00Z")

        test("FIRST_GUEST: the claim succeeds exactly once per session, and again after the session was cleared") {
            val state = EncounterEntryNoticeState()
            val session = Uuid.random()
            val space = Uuid.random()
            state.claimFirstGuest(sessionRoomId = session, spaceId = space) shouldBe true
            state.claimFirstGuest(sessionRoomId = session, spaceId = space) shouldBe false
            state.claimFirstGuest(sessionRoomId = Uuid.random(), spaceId = space) shouldBe true
            state.clearSession(session)
            state.claimFirstGuest(sessionRoomId = session, spaceId = space) shouldBe true
        }

        test("EVERY_GUEST: entries land in the grid slot of their time and nothing is due before the slot ends") {
            val state = EncounterEntryNoticeState()
            val session = Uuid.random()
            val space = Uuid.random()
            state.countEntry(sessionRoomId = session, spaceId = space, now = t0 + 30.seconds) shouldBe true
            state.countEntry(sessionRoomId = session, spaceId = space, now = t0 + 4.minutes + 59.seconds) shouldBe true
            state.countEntry(sessionRoomId = session, spaceId = space, now = t0 + 3.minutes) shouldBe true
            state.drainDue(t0 + 4.minutes + 59.seconds).shouldBeEmpty()
            val due = state.drainDue(t0 + 5.minutes)
            due shouldHaveSize 1
            due.single().count shouldBe 3
            due.single().windowStart shouldBe t0
            due.single().windowEnd shouldBe t0 + 5.minutes
            due.single().sessionRoomId shouldBe session
            due.single().spaceId shouldBe space
            // drained windows are gone
            state.drainDue(t0 + 20.minutes).shouldBeEmpty()
            state.trackedSessions() shouldBe 0
        }

        test("EVERY_GUEST: the slot is the fixed grid, not 'five minutes after the first entry'") {
            val state = EncounterEntryNoticeState()
            val session = Uuid.random()
            val space = Uuid.random()
            state.countEntry(sessionRoomId = session, spaceId = space, now = t0 + 4.minutes) // first entry late in slot 10:00-10:05
            state.countEntry(sessionRoomId = session, spaceId = space, now = t0 + 6.minutes) // belongs to slot 10:05-10:10
            val first = state.drainDue(t0 + 5.minutes)
            first.single().count shouldBe 1
            first.single().windowStart shouldBe t0
        }

        test("the throttle: a space that sent less than five minutes ago keeps its due window until the five minutes have elapsed") {
            val state = EncounterEntryNoticeState()
            val session = Uuid.random()
            val space = Uuid.random()
            state.markSent(spaceId = space, at = t0 + 5.minutes + 30.seconds)
            state.countEntry(sessionRoomId = session, spaceId = space, now = t0 + 5.minutes + 40.seconds)
            // slot 10:05-10:10 is over at 10:10:00, but the last mail left at 10:05:30 -> due only from 10:10:30
            state.drainDue(t0 + 10.minutes).shouldBeEmpty()
            val due = state.drainDue(t0 + 10.minutes + 30.seconds)
            due shouldHaveSize 1
            due.single().count shouldBe 1
        }

        test("the throttle also holds between two drains: a drained window claims the space at once") {
            val state = EncounterEntryNoticeState()
            val space = Uuid.random()
            val a = Uuid.random()
            val b = Uuid.random()
            state.countEntry(sessionRoomId = a, spaceId = space, now = t0)
            // a LATE flush at 10:07 drains the 10:00-10:05 slot ...
            state.drainDue(t0 + 7.minutes) shouldHaveSize 1
            // ... so the 10:05-10:10 slot of a second session of the same space, due at 10:10, waits until 10:12
            state.countEntry(sessionRoomId = b, spaceId = space, now = t0 + 5.minutes)
            state.drainDue(t0 + 11.minutes + 59.seconds).shouldBeEmpty()
            state.drainDue(t0 + 12.minutes) shouldHaveSize 1
        }

        test("late flush: several due slots of one session are merged into ONE window (one mail per space)") {
            val state = EncounterEntryNoticeState()
            val session = Uuid.random()
            val space = Uuid.random()
            state.countEntry(sessionRoomId = session, spaceId = space, now = t0)
            state.countEntry(sessionRoomId = session, spaceId = space, now = t0 + 5.minutes)
            state.countEntry(sessionRoomId = session, spaceId = space, now = t0 + 5.minutes)
            val due = state.drainDue(t0 + 30.minutes)
            due shouldHaveSize 1
            due.single().count shouldBe 3
            due.single().windowStart shouldBe t0
            due.single().windowEnd shouldBe t0 + 10.minutes
        }

        test("clearSpace drops the marker and the pending windows of that space only; clearSession drops one session") {
            val state = EncounterEntryNoticeState()
            val space = Uuid.random()
            val other = Uuid.random()
            val s1 = Uuid.random()
            val s2 = Uuid.random()
            val s3 = Uuid.random()
            state.claimFirstGuest(sessionRoomId = s1, spaceId = space)
            state.countEntry(sessionRoomId = s2, spaceId = space, now = t0)
            state.countEntry(sessionRoomId = s3, spaceId = other, now = t0)
            state.clearSpace(space)
            state.claimFirstGuest(sessionRoomId = s1, spaceId = space) shouldBe true // marker was dropped
            state.drainDue(t0 + 5.minutes).map { it.spaceId } shouldBe listOf(other)
            state.clearSession(s1)
            state.trackedSessions() shouldBe 0
        }

        test("a full state refuses a new session instead of evicting an older one") {
            val state = EncounterEntryNoticeState()
            val space = Uuid.random()
            val first = Uuid.random()
            state.claimFirstGuest(sessionRoomId = first, spaceId = space) shouldBe true
            repeat(EncounterEntryNoticeState.MAX_SESSIONS - 1) {
                state.claimFirstGuest(sessionRoomId = Uuid.random(), spaceId = space) shouldBe
                    true
            }
            state.isFull() shouldBe true
            state.claimFirstGuest(sessionRoomId = Uuid.random(), spaceId = space) shouldBe false
            // the oldest marker is still there (nothing was evicted)
            state.claimFirstGuest(sessionRoomId = first, spaceId = space) shouldBe false
            state.trackedSessions() shouldBe EncounterEntryNoticeState.MAX_SESSIONS

            val counting = EncounterEntryNoticeState()
            repeat(EncounterEntryNoticeState.MAX_SESSIONS) {
                counting.countEntry(sessionRoomId = Uuid.random(), spaceId = space, now = t0) shouldBe
                    true
            }
            counting.countEntry(sessionRoomId = Uuid.random(), spaceId = space, now = t0) shouldBe false
        }

        test("pending slots of one session are bounded") {
            val state = EncounterEntryNoticeState()
            val session = Uuid.random()
            val space = Uuid.random()
            repeat(EncounterEntryNoticeState.MAX_PENDING_SLOTS_PER_SESSION) { i ->
                state.countEntry(sessionRoomId = session, spaceId = space, now = t0 + (5 * i).minutes) shouldBe true
            }
            state.countEntry(sessionRoomId = session, spaceId = space, now = t0 + 60.minutes) shouldBe false
            // an entry into an already open slot is still counted
            state.countEntry(sessionRoomId = session, spaceId = space, now = t0 + 1.seconds) shouldBe true
        }

        test("thread safety: 2 x 50 parallel claims on one session produce exactly one winner") {
            val state = EncounterEntryNoticeState()
            val session = Uuid.random()
            val space = Uuid.random()
            val winners = AtomicInteger(0)
            val pool = Executors.newFixedThreadPool(16)
            val go = CountDownLatch(1)
            val done = CountDownLatch(100)
            repeat(100) {
                pool.execute {
                    go.await()
                    if (state.claimFirstGuest(sessionRoomId = session, spaceId = space)) winners.incrementAndGet()
                    done.countDown()
                }
            }
            go.countDown()
            done.await(30, java.util.concurrent.TimeUnit.SECONDS) shouldBe true
            pool.shutdown()
            winners.get() shouldBe 1
        }
    })
