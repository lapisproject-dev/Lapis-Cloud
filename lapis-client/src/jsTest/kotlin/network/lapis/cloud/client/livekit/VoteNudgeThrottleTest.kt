package network.lapis.cloud.client.livekit

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** V1.9.24 -- [VoteNudgeThrottle] with a fake clock/scheduler: no DOM, no real timers. */
class VoteNudgeThrottleTest {
    private class Harness {
        var clock = 0.0
        var fired = 0
        var cancelled = 0
        private var nextHandle = 0
        private val pending = linkedMapOf<Int, Pair<Double, () -> Unit>>()

        val throttle =
            VoteNudgeThrottle(
                minIntervalMs = 2_000.0,
                now = { clock },
                schedule = { delayMs, block ->
                    val h = nextHandle++
                    pending[h] = clock + delayMs to block
                    h
                },
                cancelScheduled = { handle ->
                    cancelled++
                    pending.remove(handle as Int)
                },
                onRefresh = { fired++ },
            )

        val pendingCount get() = pending.size

        /** Advances the fake clock to [t], running every due timer in order. */
        fun advanceTo(t: Double) {
            while (true) {
                val due = pending.entries.filter { it.value.first <= t }.minByOrNull { it.value.first } ?: break
                val key = due.key
                val (dueAt, block) = due.value
                pending.remove(key)
                clock = dueAt
                block()
            }
            clock = t
        }
    }

    @Test
    fun firstTrigger_firesImmediately() {
        val h = Harness()
        h.throttle.trigger()
        assertEquals(1, h.fired)
        assertEquals(0, h.pendingCount)
    }

    @Test
    fun tenTriggersWithin100ms_oneImmediate_oneTrailing() {
        val h = Harness()
        repeat(10) {
            h.advanceTo(it * 10.0)
            h.throttle.trigger()
        }
        assertEquals(1, h.fired)
        assertEquals(1, h.pendingCount)
        h.advanceTo(2_100.0)
        assertEquals(2, h.fired)
        assertEquals(0, h.pendingCount)
    }

    @Test
    fun triggerAfterTwoSeconds_firesImmediatelyAgain() {
        val h = Harness()
        h.throttle.trigger()
        h.advanceTo(2_000.0)
        h.throttle.trigger()
        assertEquals(2, h.fired)
        assertEquals(0, h.pendingCount)
    }

    @Test
    fun cancel_discardsPendingTrailingCall() {
        val h = Harness()
        h.throttle.trigger()
        h.advanceTo(100.0)
        h.throttle.trigger()
        assertEquals(1, h.pendingCount)
        h.throttle.cancel()
        assertEquals(1, h.cancelled)
        h.advanceTo(10_000.0)
        assertEquals(1, h.fired)
        // Safe to call again, and the throttle keeps working afterwards.
        h.throttle.cancel()
        assertEquals(1, h.cancelled)
        h.throttle.trigger()
        assertEquals(2, h.fired)
    }

    @Test
    fun flood_of_1000_triggersOver10s_firesAtMostSixTimes() {
        val h = Harness()
        repeat(1000) {
            h.advanceTo(it * 10.0)
            h.throttle.trigger()
        }
        h.advanceTo(10_000.0)
        assertTrue(h.fired <= 6, "fired ${h.fired} times")
        assertTrue(h.fired >= 5, "throttle must not swallow nudges, fired ${h.fired}")
    }
}
