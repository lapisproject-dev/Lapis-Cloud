package network.lapis.cloud.server.encounter

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.uuid.Uuid

/** Welle V1.9.79 -- [EncounterSeatState]: in memory, atomic, server-authoritative, bounded, cleared per session. */
class EncounterSeatStateTest :
    FunSpec({
        test("select, change and release a seat") {
            val state = EncounterSeatState()
            val room = Uuid.random()
            val a = Uuid.random()
            val present = setOf(a)
            state.select(sessionRoomId = room, memberId = a, seat = 3, present = present) shouldBe EncounterSeatState.Outcome.Ok
            state.snapshot(sessionRoomId = room, present = present) shouldBe mapOf(a to 3)
            state.select(sessionRoomId = room, memberId = a, seat = 7, present = present) shouldBe EncounterSeatState.Outcome.Ok
            state.snapshot(sessionRoomId = room, present = present) shouldBe mapOf(a to 7)
            // the old seat is free again
            val b = Uuid.random()
            state.select(sessionRoomId = room, memberId = b, seat = 3, present = present + b) shouldBe EncounterSeatState.Outcome.Ok
            state.select(sessionRoomId = room, memberId = a, seat = null, present = present + b) shouldBe EncounterSeatState.Outcome.Ok
            state.snapshot(sessionRoomId = room, present = present + b) shouldBe mapOf(b to 3)
            // choosing the own seat again is idempotent
            state.select(sessionRoomId = room, memberId = b, seat = 3, present = present + b) shouldBe EncounterSeatState.Outcome.Ok
        }

        test("a seat of somebody else who is present is taken") {
            val state = EncounterSeatState()
            val room = Uuid.random()
            val a = Uuid.random()
            val b = Uuid.random()
            state.select(sessionRoomId = room, memberId = a, seat = 5, present = setOf(a, b)) shouldBe EncounterSeatState.Outcome.Ok
            state.select(sessionRoomId = room, memberId = b, seat = 5, present = setOf(a, b)) shouldBe EncounterSeatState.Outcome.Taken
            state.snapshot(sessionRoomId = room, present = setOf(a, b)) shouldBe mapOf(a to 5)
        }

        test("a stale occupant (not present any more) does not block the seat") {
            val state = EncounterSeatState()
            val room = Uuid.random()
            val gone = Uuid.random()
            val b = Uuid.random()
            state.select(sessionRoomId = room, memberId = gone, seat = 5, present = setOf(gone)) shouldBe EncounterSeatState.Outcome.Ok
            state.select(sessionRoomId = room, memberId = b, seat = 5, present = setOf(b)) shouldBe EncounterSeatState.Outcome.Ok
            state.snapshot(sessionRoomId = room, present = setOf(b)) shouldBe mapOf(b to 5)
            // the stale one lost the seat entirely
            state.snapshot(sessionRoomId = room, present = setOf(b, gone)) shouldBe mapOf(b to 5)
        }

        test("50 threads choose the same seat at once: exactly one wins") {
            val state = EncounterSeatState()
            val room = Uuid.random()
            val people = List(50) { Uuid.random() }.toSet()
            val start = CountDownLatch(1)
            val ok = AtomicInteger()
            val taken = AtomicInteger()
            val pool = Executors.newFixedThreadPool(50)
            try {
                val futures =
                    people.map { id ->
                        pool.submit {
                            start.await()
                            when (state.select(sessionRoomId = room, memberId = id, seat = 9, present = people)) {
                                EncounterSeatState.Outcome.Ok -> ok.incrementAndGet()
                                EncounterSeatState.Outcome.Taken -> taken.incrementAndGet()
                                EncounterSeatState.Outcome.Full -> error("unexpected")
                            }
                        }
                    }
                start.countDown()
                futures.forEach { it.get() }
            } finally {
                pool.shutdownNow()
            }
            ok.get() shouldBe 1
            taken.get() shouldBe 49
            state.snapshot(sessionRoomId = room, present = people).size shouldBe 1
        }

        test("snapshot returns only the present and prunes the rest") {
            val state = EncounterSeatState()
            val room = Uuid.random()
            val a = Uuid.random()
            val b = Uuid.random()
            state.select(sessionRoomId = room, memberId = a, seat = 1, present = setOf(a, b))
            state.select(sessionRoomId = room, memberId = b, seat = 2, present = setOf(a, b))
            state.snapshot(sessionRoomId = room, present = setOf(a)) shouldBe mapOf(a to 1)
            state.snapshot(sessionRoomId = room, present = setOf(a, b)) shouldBe mapOf(a to 1)
        }

        test("release, releaseAll and clear") {
            val state = EncounterSeatState()
            val room = Uuid.random()
            val people = List(4) { Uuid.random() }
            val present = people.toSet()
            people.forEachIndexed { i, id -> state.select(sessionRoomId = room, memberId = id, seat = i, present = present) }
            state.release(sessionRoomId = room, memberId = people[0])
            state.releaseAll(sessionRoomId = room, memberIds = listOf(people[1], people[2]))
            state.snapshot(sessionRoomId = room, present = present) shouldBe mapOf(people[3] to 3)
            state.clear(room)
            state.snapshot(sessionRoomId = room, present = present) shouldBe emptyMap()
            state.trackedSessions() shouldBe 0
        }

        test("the 65th session is refused with Full, an existing one keeps working") {
            val state = EncounterSeatState()
            val rooms = List(EncounterSeatState.MAX_SESSIONS) { Uuid.random() }
            val who = Uuid.random()
            rooms.forEach {
                state.select(sessionRoomId = it, memberId = who, seat = 0, present = setOf(who)) shouldBe
                    EncounterSeatState.Outcome.Ok
            }
            state.trackedSessions() shouldBe EncounterSeatState.MAX_SESSIONS
            state.select(sessionRoomId = Uuid.random(), memberId = who, seat = 0, present = setOf(who)) shouldBe
                EncounterSeatState.Outcome.Full
            val other = Uuid.random()
            state.select(sessionRoomId = rooms.first(), memberId = other, seat = 1, present = setOf(who, other)) shouldBe
                EncounterSeatState.Outcome.Ok
            state.clear(rooms.last())
            state.select(sessionRoomId = Uuid.random(), memberId = who, seat = 0, present = setOf(who)) shouldBe
                EncounterSeatState.Outcome.Ok
        }
    })
