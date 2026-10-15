package network.lapis.cloud.server.conference

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldMatch
import io.kotest.matchers.types.shouldBeInstanceOf
import network.lapis.cloud.server.conference.ConferenceTimerState.Change
import network.lapis.cloud.shared.domain.CONFERENCE_TIMER_MAX_PAYLOAD_BYTES
import network.lapis.cloud.shared.domain.ConferenceTimerStatus
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.ExperimentalTime
import kotlin.time.TestTimeSource
import kotlin.uuid.Uuid

/** V1.9.133 -- the pure logic of the conference timer with an injected monotonic clock and a fixed wall clock. */
@OptIn(ExperimentalTime::class)
class ConferenceTimerStateTest :
    FunSpec({
        fun newState(source: TestTimeSource = TestTimeSource()) =
            ConferenceTimerState(timeSource = source, wallNowMillis = { 1_760_000_000_000L }) to source

        test("a new room is OFF with seq 0 and a stable 16-hex epoch") {
            val (s, _) = newState()
            val room = Uuid.random()
            val snap = s.snapshot(room)
            snap.status shouldBe ConferenceTimerStatus.OFF
            snap.seq shouldBe 0L
            snap.remainingMs shouldBe 0L
            snap.initialDurationMs shouldBe 0L
            snap.epoch shouldMatch Regex("[0-9a-f]{16}")
            s.snapshot(room).epoch shouldBe snap.epoch
        }

        test("start validates the range 10 s..60 min in whole seconds") {
            val (s, _) = newState()
            val room = Uuid.random()
            shouldThrow<TimerLimitException> { s.start(roomId = room, durationMs = 9_000) }
            shouldThrow<TimerLimitException> { s.start(roomId = room, durationMs = 3_601_000) }
            shouldThrow<TimerLimitException> { s.start(roomId = room, durationMs = 10_500) }
            s.snapshot(room).seq shouldBe 0L
            s.start(roomId = room, durationMs = 10_000).shouldBeInstanceOf<Change.Changed>()
            val (s2, _) = newState()
            val room2 = Uuid.random()
            s2.start(roomId = room2, durationMs = 3_600_000).shouldBeInstanceOf<Change.Changed>()
            s2.snapshot(room2).remainingMs shouldBe 3_600_000L
        }

        test("start runs, counts down by the monotonic clock and starts only from OFF or EXPIRED") {
            val (s, t) = newState()
            val room = Uuid.random()
            val change = s.start(roomId = room, durationMs = 60_000).shouldBeInstanceOf<Change.Changed>()
            change.snapshot.status shouldBe ConferenceTimerStatus.RUNNING
            change.snapshot.seq shouldBe 1L
            change.snapshot.initialDurationMs shouldBe 60_000L
            change.snapshot.remainingMs shouldBe 60_000L
            change.snapshot.serverNowEpochMs shouldBe 1_760_000_000_000L
            t += 20.seconds
            s.snapshot(room).remainingMs shouldBe 40_000L
            shouldThrow<TimerTransitionException> { s.start(roomId = room, durationMs = 30_000) }
            s.pause(room)
            shouldThrow<TimerTransitionException> { s.start(roomId = room, durationMs = 30_000) }
        }

        test("EXPIRED is derived: remaining 0, seq unchanged, no stored state; start and extend and stop work from it") {
            val (s, t) = newState()
            val room = Uuid.random()
            s.start(roomId = room, durationMs = 10_000)
            t += 10.seconds
            val expired = s.snapshot(room)
            expired.status shouldBe ConferenceTimerStatus.EXPIRED
            expired.remainingMs shouldBe 0L
            expired.initialDurationMs shouldBe 10_000L
            expired.seq shouldBe 1L
            t += 5.seconds
            s.snapshot(room).seq shouldBe 1L
            s.snapshot(room).remainingMs shouldBe 0L
            // pause/resume are impossible on an expired timer
            shouldThrow<TimerTransitionException> { s.pause(room) }
            shouldThrow<TimerTransitionException> { s.resume(room) }
            // extend restarts with exactly the extension
            val ext = s.extend(roomId = room, extensionMs = 30_000).shouldBeInstanceOf<Change.Changed>()
            ext.snapshot.status shouldBe ConferenceTimerStatus.RUNNING
            ext.snapshot.initialDurationMs shouldBe 30_000L
            ext.snapshot.remainingMs shouldBe 30_000L
            // and start works from expired
            t += 30.seconds
            s.snapshot(room).status shouldBe ConferenceTimerStatus.EXPIRED
            s.start(roomId = room, durationMs = 20_000).shouldBeInstanceOf<Change.Changed>()
            s.snapshot(room).initialDurationMs shouldBe 20_000L
            // stop works from expired
            t += 20.seconds
            s.stop(room).shouldBeInstanceOf<Change.Changed>()
            s.snapshot(room).status shouldBe ConferenceTimerStatus.OFF
        }

        test("pause and resume keep the remaining time to the millisecond") {
            val (s, t) = newState()
            val room = Uuid.random()
            s.start(roomId = room, durationMs = 60_000)
            t += 12_345.milliseconds
            val paused = s.pause(room).shouldBeInstanceOf<Change.Changed>()
            paused.snapshot.status shouldBe ConferenceTimerStatus.PAUSED
            paused.snapshot.remainingMs shouldBe 47_655L
            t += 5.seconds
            s.snapshot(room).remainingMs shouldBe 47_655L
            val resumed = s.resume(room).shouldBeInstanceOf<Change.Changed>()
            resumed.snapshot.status shouldBe ConferenceTimerStatus.RUNNING
            resumed.snapshot.remainingMs shouldBe 47_655L
            t += 7_655.milliseconds
            s.snapshot(room).remainingMs shouldBe 40_000L
        }

        test("no-ops do not raise seq: stop on OFF, pause on PAUSED, resume on RUNNING") {
            val (s, _) = newState()
            val room = Uuid.random()
            s.stop(room) shouldBe Change.None
            s.start(roomId = room, durationMs = 60_000)
            s.resume(room) shouldBe Change.None
            s.snapshot(room).seq shouldBe 1L
            s.pause(room)
            s.pause(room) shouldBe Change.None
            s.snapshot(room).seq shouldBe 2L
        }

        test("impossible transitions throw: pause/resume/extend on OFF") {
            val (s, _) = newState()
            val room = Uuid.random()
            shouldThrow<TimerTransitionException> { s.pause(room) }
            shouldThrow<TimerTransitionException> { s.resume(room) }
            shouldThrow<TimerTransitionException> { s.extend(roomId = room, extensionMs = 30_000) }
            s.snapshot(room).seq shouldBe 0L
        }

        test("extend adds to running and paused timers, grows the ring denominator, and refuses more than 60 min remaining") {
            val (s, t) = newState()
            val room = Uuid.random()
            s.start(roomId = room, durationMs = 120_000)
            t += 30.seconds
            val ext = s.extend(roomId = room, extensionMs = 60_000).shouldBeInstanceOf<Change.Changed>()
            ext.snapshot.remainingMs shouldBe 150_000L
            ext.snapshot.initialDurationMs shouldBe 180_000L
            s.pause(room)
            val ext2 = s.extend(roomId = room, extensionMs = 30_000).shouldBeInstanceOf<Change.Changed>()
            ext2.snapshot.status shouldBe ConferenceTimerStatus.PAUSED
            ext2.snapshot.remainingMs shouldBe 180_000L
            ext2.snapshot.initialDurationMs shouldBe 210_000L
            // the 60 min cap applies to the remaining time after the extension
            val (s2, _) = newState()
            val r2 = Uuid.random()
            s2.start(roomId = r2, durationMs = 3_600_000)
            shouldThrow<TimerLimitException> { s2.extend(roomId = r2, extensionMs = 30_000) }
            val (s3, t3) = newState()
            val r3 = Uuid.random()
            s3.start(roomId = r3, durationMs = 3_500_000)
            shouldThrow<TimerLimitException> { s3.extend(roomId = r3, extensionMs = 300_000) }
            s3.extend(roomId = r3, extensionMs = 60_000).shouldBeInstanceOf<Change.Changed>()
            t3 += 1.seconds
            s3.snapshot(r3).remainingMs shouldBe 3_559_000L
        }

        test("stop clears everything and the room can start again; seq only ever rises") {
            val (s, _) = newState()
            val room = Uuid.random()
            s.start(roomId = room, durationMs = 60_000)
            val stopped = s.stop(room).shouldBeInstanceOf<Change.Changed>()
            stopped.snapshot.status shouldBe ConferenceTimerStatus.OFF
            stopped.snapshot.initialDurationMs shouldBe 0L
            stopped.snapshot.remainingMs shouldBe 0L
            stopped.snapshot.seq shouldBe 2L
            s
                .start(roomId = room, durationMs = 60_000)
                .shouldBeInstanceOf<Change.Changed>()
                .snapshot.seq shouldBe 3L
        }

        test("rooms are independent") {
            val (s, _) = newState()
            val a = Uuid.random()
            val b = Uuid.random()
            s.start(roomId = a, durationMs = 60_000)
            s.snapshot(b).status shouldBe ConferenceTimerStatus.OFF
            s.nonOffRoomIds() shouldBe setOf(a)
            s.knownRoomIds() shouldBe setOf(a, b)
        }

        test("observePresence returns the identities seen for the first time; pruneInactiveRooms drops ended rooms") {
            val (s, _) = newState()
            val a = Uuid.random()
            val b = Uuid.random()
            s.start(roomId = a, durationMs = 60_000)
            s.start(roomId = b, durationMs = 60_000)

            fun seen(vararg ids: String) = ids.associateWith { "sid-$it" }

            fun observe(vararg ids: String): Map<String, String> =
                s.observePresence(roomId = a, present = seen(*ids)).also { s.markTold(roomId = a, seq = s.snapshot(a).seq, told = it) }

            observe("x", "y") shouldBe seen("x", "y")
            observe("x", "y", "z") shouldBe seen("z")
            observe("x") shouldBe emptyMap()
            observe("x", "y") shouldBe seen("y")
            // same identity, new sid (reload) -> told again
            s.observePresence(roomId = a, present = mapOf("x" to "sid-new", "y" to "sid-y")) shouldBe mapOf("x" to "sid-new")
            // not confirmed with markTold -> still pending on the next call
            s.observePresence(roomId = a, present = mapOf("x" to "sid-new", "y" to "sid-y")) shouldBe mapOf("x" to "sid-new")
            // a change clears the memory: everybody is told again
            s.pause(a)
            s.observePresence(roomId = a, present = seen("y")) shouldBe seen("y")
            s.pruneInactiveRooms(setOf(a))
            s.knownRoomIds() shouldBe setOf(a)
            s.nonOffRoomIds() shouldBe setOf(a)
        }

        test("the packet is a fixed shape within the cap even in the worst case, and refuses a malformed epoch") {
            val worst =
                ConferenceTimerState.Snapshot(
                    epoch = "0123456789abcdef",
                    seq = Long.MAX_VALUE,
                    status = ConferenceTimerStatus.RUNNING,
                    initialDurationMs = 86_400_000L,
                    remainingMs = 3_600_000L,
                    serverNowEpochMs = 9_999_999_999_999L,
                )
            val bytes = ConferenceTimerPackets.snapshot(worst)
            (bytes.size <= CONFERENCE_TIMER_MAX_PAYLOAD_BYTES) shouldBe true
            String(bytes, Charsets.UTF_8) shouldBe
                "{\"v\":1,\"e\":\"0123456789abcdef\",\"seq\":${Long.MAX_VALUE},\"s\":\"R\",\"d\":86400000,\"rem\":3600000," +
                "\"t\":9999999999999}"
            shouldThrow<IllegalArgumentException> { ConferenceTimerPackets.snapshot(worst.copy(epoch = "not-an-epoch")) }
            shouldThrow<IllegalArgumentException> { ConferenceTimerPackets.snapshot(worst.copy(epoch = "0123456789ABCDEF")) }
            listOf(
                ConferenceTimerStatus.RUNNING to "R",
                ConferenceTimerStatus.PAUSED to "P",
                ConferenceTimerStatus.EXPIRED to "X",
                ConferenceTimerStatus.OFF to "O",
            ).forEach { (status, code) ->
                String(ConferenceTimerPackets.snapshot(worst.copy(status = status)), Charsets.UTF_8) shouldMatch
                    Regex(".*\"s\":\"$code\".*")
            }
        }
    })
