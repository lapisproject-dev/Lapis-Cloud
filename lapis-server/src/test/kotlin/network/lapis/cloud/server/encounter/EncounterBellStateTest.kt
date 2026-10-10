package network.lapis.cloud.server.encounter

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Instant
import kotlin.uuid.Uuid

/** V1.9.96 -- the in-memory bell throttle: interval, bound, clear, lazy prune, thread safety. */
class EncounterBellStateTest :
    FunSpec({
        class Clock(
            var t: Instant = Instant.fromEpochMilliseconds(1_000_000),
        ) {
            fun now() = t

            fun advance(ms: Long) {
                t += ms.milliseconds
            }
        }

        test("the second bell inside the interval is refused, one at the interval is allowed again") {
            val clock = Clock()
            val state = EncounterBellState(now = clock::now, minIntervalMs = 10_000)
            val room = Uuid.random()
            state.tryAcquire(room) shouldBe true
            clock.advance(9_999)
            state.tryAcquire(room) shouldBe false
            clock.advance(1)
            state.tryAcquire(room) shouldBe true
        }

        test("two rooms do not block each other") {
            val state = EncounterBellState(now = Clock()::now)
            state.tryAcquire(Uuid.random()) shouldBe true
            state.tryAcquire(Uuid.random()) shouldBe true
        }

        test("clear forgets the room") {
            val state = EncounterBellState(now = Clock()::now)
            val room = Uuid.random()
            state.tryAcquire(room) shouldBe true
            state.size() shouldBe 1
            state.clear(room)
            state.size() shouldBe 0
            state.tryAcquire(room) shouldBe true
        }

        test("entries older than the interval are pruned lazily") {
            val clock = Clock()
            val state = EncounterBellState(now = clock::now, minIntervalMs = 10_000)
            repeat(5) { state.tryAcquire(Uuid.random()) }
            state.size() shouldBe 5
            clock.advance(10_000)
            state.tryAcquire(Uuid.random()) shouldBe true
            state.size() shouldBe 1
        }

        test("the map is bounded, the oldest entry is dropped first") {
            val clock = Clock()
            val state = EncounterBellState(now = clock::now, minIntervalMs = 10_000, maxEntries = 3)
            val rooms = List(4) { Uuid.random() }
            rooms.forEach {
                state.tryAcquire(it) shouldBe true
                clock.advance(1)
            }
            state.size() shouldBe 3
            // the oldest (rooms[0]) was dropped: it may ring again immediately, the newest is still throttled
            state.tryAcquire(rooms[3]) shouldBe false
            state.tryAcquire(rooms[0]) shouldBe true
        }

        test("parallel acquires of one room let exactly one through") {
            val state = EncounterBellState(now = Clock()::now)
            val room = Uuid.random()
            val pool = Executors.newFixedThreadPool(8)
            val start = CountDownLatch(1)
            val winners = AtomicInteger()
            val futures =
                List(32) {
                    pool.submit {
                        start.await()
                        if (state.tryAcquire(room)) winners.incrementAndGet()
                    }
                }
            start.countDown()
            futures.forEach { it.get() }
            pool.shutdown()
            winners.get() shouldBe 1
        }
    })
