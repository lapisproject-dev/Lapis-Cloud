package network.lapis.cloud.server.conference

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import network.lapis.cloud.server.conference.ConferenceHandQueueState.Change
import network.lapis.cloud.shared.domain.CONFERENCE_HANDS_MAX_PAYLOAD_BYTES
import kotlin.uuid.Uuid

/** V1.9.127 -- the pure logic of the raised-hand queue, with an injected clock. */
class ConferenceHandQueueStateTest :
    FunSpec({
        fun id() = Uuid.random().toString()

        var clock = 1_000_000L

        fun state(max: Int = 100) = ConferenceHandQueueState(maxPerRoom = max, nowMillis = { clock })

        fun ConferenceHandQueueState.order(room: Uuid) = snapshot(room).identities

        test("raising keeps the order, is idempotent (position kept, no change) and lowering plus raising again goes to the end") {
            val s = state()
            val room = Uuid.random()
            val (a, b, c) = Triple(id(), id(), id())
            s.raise(roomId = room, identity = a).shouldBeInstanceOf<Change.Changed>()
            s.raise(roomId = room, identity = b)
            s.raise(roomId = room, identity = c)
            s.raise(roomId = room, identity = a) shouldBe Change.None
            s.order(room) shouldContainExactly listOf(a, b, c)
            s.lower(roomId = room, identity = a).shouldBeInstanceOf<Change.Changed>()
            s.order(room) shouldContainExactly listOf(b, c)
            s.raise(roomId = room, identity = a)
            s.order(room) shouldContainExactly listOf(b, c, a)
            s.lower(roomId = room, identity = id()) shouldBe Change.None
        }

        test("lowerAll empties the queue; on an empty queue it is no change") {
            val s = state()
            val room = Uuid.random()
            s.lowerAll(room) shouldBe Change.None
            s.raise(roomId = room, identity = id())
            s.raise(roomId = room, identity = id())
            s.lowerAll(room).shouldBeInstanceOf<Change.Changed>()
            s.order(room).shouldBeEmpty()
        }

        test("the queue is capped per room and the cap is per room") {
            val s = state(max = 3)
            val room = Uuid.random()
            repeat(3) { s.raise(roomId = room, identity = id()) }
            shouldThrow<HandQueueFullException> { s.raise(roomId = room, identity = id()) }
            s.raise(roomId = Uuid.random(), identity = id()).shouldBeInstanceOf<Change.Changed>()
        }

        test("seq rises only on a real change; the epoch is stable per instance and differs between instances") {
            val s = state()
            val room = Uuid.random()
            val me = id()
            val start = s.snapshot(room).seq
            s.raise(roomId = room, identity = me)
            s.snapshot(room).seq shouldBe start + 1
            s.raise(roomId = room, identity = me)
            s.lower(roomId = room, identity = id())
            s.snapshot(room).seq shouldBe start + 1
            s.lower(roomId = room, identity = me)
            s.snapshot(room).seq shouldBe start + 2
            s.snapshot(room).epoch shouldBe s.epoch
            s.epoch.matches(Regex("[0-9a-f]{16}")) shouldBe true
            ConferenceHandQueueState().epoch shouldNotBe ConferenceHandQueueState().epoch
        }

        test("only a member UUID or evg-<uuid> may hold a hand (nothing else can reach a packet)") {
            val s = state()
            val room = Uuid.random()
            listOf("", "x", "EG_egress-1", "evg-nope", "\",\"x", Uuid.random().toString().uppercase()).forEach {
                shouldThrow<IllegalArgumentException> { s.raise(roomId = room, identity = it) }
            }
            s.raise(roomId = room, identity = "evg-${Uuid.random()}").shouldBeInstanceOf<Change.Changed>()
        }

        test("a person who is absent for less than the grace keeps the position, one absent for 20 s is removed") {
            clock = 5_000_000L
            val s = state()
            val room = Uuid.random()
            val (a, b) = id() to id()
            s.raise(roomId = room, identity = a)
            s.raise(roomId = room, identity = b)
            s.observePresence(roomId = room, present = setOf(a, b)).change shouldBe Change.None
            // a is first seen missing here; the grace counts from this observation
            s.observePresence(roomId = room, present = setOf(b)).change shouldBe Change.None
            clock += 19_000
            s.observePresence(roomId = room, present = setOf(b)).change shouldBe Change.None
            s.order(room) shouldContainExactly listOf(a, b)
            // back within the grace: the clock of the absence is forgotten
            s.observePresence(roomId = room, present = setOf(a, b)).change shouldBe Change.None
            s.observePresence(roomId = room, present = setOf(b)).change shouldBe Change.None
            clock += 19_000
            s.observePresence(roomId = room, present = setOf(b)).change shouldBe Change.None
            clock += 1_000
            s.observePresence(roomId = room, present = setOf(b)).change.shouldBeInstanceOf<Change.Changed>()
            s.order(room) shouldContainExactly listOf(b)
        }

        test("newlySeen reports an identity once, until it leaves and comes back") {
            val s = state()
            val room = Uuid.random()
            val (a, b) = id() to id()
            s.observePresence(roomId = room, present = setOf(a)).newlySeen shouldBe setOf(a)
            s.observePresence(roomId = room, present = setOf(a)).newlySeen.shouldBeEmpty()
            s.observePresence(roomId = room, present = setOf(a, b)).newlySeen shouldBe setOf(b)
            s.observePresence(roomId = room, present = setOf(a)).newlySeen.shouldBeEmpty()
            s.observePresence(roomId = room, present = setOf(a, b)).newlySeen shouldBe setOf(b)
        }

        test("activeRoomIds lists rooms with a hand; pruneInactiveRooms forgets ended rooms") {
            val s = state()
            val (r1, r2) = Uuid.random() to Uuid.random()
            s.raise(roomId = r1, identity = id())
            s.snapshot(r2)
            s.activeRoomIds() shouldBe setOf(r1)
            s.knownRoomIds() shouldBe setOf(r1, r2)
            s.pruneInactiveRooms(setOf(r1))
            s.knownRoomIds() shouldBe setOf(r1)
            s.pruneInactiveRooms(emptySet())
            s.knownRoomIds().shouldBeEmpty()
        }

        test("concurrent raises under the room lock give one unique order without loss") {
            val s = state()
            val room = Uuid.random()
            val ids = List(60) { id() }
            runBlocking {
                ids
                    .map { who ->
                        async(
                            kotlinx.coroutines.Dispatchers.Default,
                        ) { s.withRoomLock(roomId = room) { s.raise(roomId = room, identity = who) } }
                    }.awaitAll()
            }
            s.order(room).toSet() shouldBe ids.toSet()
            s.order(room).size shouldBe 60
            s.snapshot(room).seq shouldBe 60
        }

        test("the worst-case packet (100 account-less guests) fits the payload cap and is exactly the wire format") {
            val s = state()
            val room = Uuid.random()
            repeat(100) { s.raise(roomId = room, identity = "evg-${Uuid.random()}") }
            val snap = s.snapshot(room)
            val bytes = ConferenceHandsPackets.snapshot(snap)
            (bytes.size <= CONFERENCE_HANDS_MAX_PAYLOAD_BYTES) shouldBe true
            val text = bytes.decodeToString()
            text.startsWith("""{"v":1,"e":"${s.epoch}","seq":100,"ids":["evg-""") shouldBe true
            // an empty queue is a valid packet too
            val empty = state()
            ConferenceHandsPackets.snapshot(empty.snapshot(Uuid.random())).decodeToString() shouldBe
                """{"v":1,"e":"${empty.epoch}","seq":0,"ids":[]}"""
        }
    })
