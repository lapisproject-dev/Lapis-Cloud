package network.lapis.cloud.client

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.promise
import network.lapis.cloud.client.livekit.TimerPhase
import network.lapis.cloud.shared.domain.ConferenceTimerDto
import network.lapis.cloud.shared.domain.ConferenceTimerStatus
import kotlin.js.Promise
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * V1.9.133 -- the conference timer controller with a fake gateway and a fake monotonic clock: the epoch rule, the countdown on the LOCAL clock,
 * the ticker that runs only while the timer runs, the throttled live-region notes, the reset note after a server restart and the one gong.
 */
class ConferenceTimerControllerTest {
    private val e1 = "0123456789abcdef"
    private val e2 = "fedcba9876543210"

    private fun test(block: suspend () -> Unit): Promise<Unit> = CoroutineScope(SupervisorJob()).promise { block() }

    private class FakeGateway : ConferenceTimerGateway {
        var next: ConferenceTimerDto? = null
        val calls = mutableListOf<String>()

        override suspend fun start(seconds: Int): ConferenceTimerDto? = next.also { calls += "start:$seconds" }

        override suspend fun pause(): ConferenceTimerDto? = next.also { calls += "pause" }

        override suspend fun resume(): ConferenceTimerDto? = next.also { calls += "resume" }

        override suspend fun stop(): ConferenceTimerDto? = next.also { calls += "stop" }

        override suspend fun extend(seconds: Int): ConferenceTimerDto? = next.also { calls += "extend:$seconds" }

        override suspend fun state(): ConferenceTimerDto? = next.also { calls += "state" }
    }

    private class Rig(
        moderator: Boolean = true,
        eventGuest: Boolean = false,
        resetNoticeMs: Int = CONFERENCE_TIMER_RESET_NOTICE_MS,
    ) {
        var now = 1_000.0
        var changes = 0
        var gongs = 0
        val notes = mutableListOf<String>()
        val gateway = FakeGateway()
        var tickerStarts = 0
        var tickerStops = 0
        var tickerPeriod = 0
        val controller =
            ConferenceTimerController(
                gateway = gateway,
                scope = CoroutineScope(SupervisorJob()),
                perfNow = { now },
                onChanged = { changes++ },
                announce = { notes += it },
                gong = { gongs++ },
                startTicker = { period, _ ->
                    tickerStarts++
                    tickerPeriod = period
                    ({ tickerStops++ })
                },
                isModerator = moderator,
                isEventGuest = eventGuest,
                resetNoticeMs = resetNoticeMs,
            )

        /** The server's clock is `local + 5_000_000` here, so a snapshot taken "now" carries `t = now + 5_000_000`. */
        fun dto(
            epoch: String,
            seq: Long,
            status: ConferenceTimerStatus,
            initial: Long,
            remaining: Long,
        ) = ConferenceTimerDto(epoch, seq, status, initial, remaining, now.toLong() + 5_000_000)
    }

    @Test
    fun aRunningTimer_countsDownOnTheLocalClock_andOnlyTheRunningStateHasATicker() =
        test {
            val rig = Rig()
            rig.controller.onPacket(rig.dto(e1, 1, ConferenceTimerStatus.RUNNING, 60_000, 60_000))
            assertEquals(ConferenceTimerStatus.RUNNING, rig.controller.display().status)
            assertEquals(60_000L, rig.controller.display().remainingMs)
            assertEquals(1, rig.tickerStarts)
            assertEquals(CONFERENCE_TIMER_TICK_MS, rig.tickerPeriod)
            rig.now += 20_000
            assertEquals(40_000L, rig.controller.display().remainingMs)
            // pause: the remaining time is the server's, the clock no longer matters, the ticker stops
            rig.controller.onPacket(rig.dto(e1, 2, ConferenceTimerStatus.PAUSED, 60_000, 40_000))
            assertEquals(1, rig.tickerStops)
            rig.now += 30_000
            assertEquals(40_000L, rig.controller.display().remainingMs)
            assertEquals(ConferenceTimerStatus.PAUSED, rig.controller.display().status)
            // resume: a new ticker
            rig.controller.onPacket(rig.dto(e1, 3, ConferenceTimerStatus.RUNNING, 60_000, 40_000))
            assertEquals(2, rig.tickerStarts)
            // stop
            rig.controller.onPacket(rig.dto(e1, 4, ConferenceTimerStatus.OFF, 0, 0))
            assertEquals(2, rig.tickerStops)
            assertFalse(rig.controller.display().visible)
        }

    @Test
    fun anOlderSnapshot_changesNothing_andAnEqualOneOnlyReAnchorsTheCountdown() =
        test {
            val rig = Rig()
            rig.controller.onPacket(rig.dto(e1, 5, ConferenceTimerStatus.RUNNING, 60_000, 60_000))
            rig.now += 10_000
            rig.controller.onPacket(rig.dto(e1, 4, ConferenceTimerStatus.OFF, 0, 0))
            assertEquals(ConferenceTimerStatus.RUNNING, rig.controller.display().status, "an older seq is dropped")
            assertEquals(50_000L, rig.controller.display().remainingMs)
            val before = rig.notes.size
            rig.controller.onPacket(rig.dto(e1, 5, ConferenceTimerStatus.RUNNING, 60_000, 50_000))
            assertEquals(50_000L, rig.controller.display().remainingMs)
            assertEquals(before, rig.notes.size, "no second announcement for the same state")
        }

    @Test
    fun theEndOfTheCountdown_isExpired_noNegativeTime_andTheGongSoundsExactlyOnce() =
        test {
            val rig = Rig()
            rig.controller.onPacket(rig.dto(e1, 1, ConferenceTimerStatus.RUNNING, 10_000, 10_000))
            rig.now += 9_800
            rig.controller.tick()
            assertEquals(0, rig.gongs)
            assertEquals(TimerPhase.FINAL, rig.controller.display().phase)
            rig.now += 300
            rig.controller.tick()
            assertEquals(1, rig.gongs)
            assertEquals(TimerPhase.EXPIRED, rig.controller.display().phase)
            assertEquals(ConferenceTimerStatus.EXPIRED, rig.controller.display().status)
            assertEquals(0L, rig.controller.display().remainingMs)
            assertEquals(1, rig.tickerStops, "the ticker stopped itself at the end")
            rig.now += 5_000
            rig.controller.tick()
            assertEquals(1, rig.gongs, "once")
            assertTrue(rig.notes.contains("Zeit abgelaufen"))
        }

    @Test
    fun aTabThatWasAsleepForTooLong_playsNoGong_butStillShowsTheEnd() =
        test {
            val rig = Rig()
            rig.controller.onPacket(rig.dto(e1, 1, ConferenceTimerStatus.RUNNING, 10_000, 10_000))
            rig.now += 60_000
            rig.controller.tick()
            assertEquals(0, rig.gongs, "the end was long ago")
            assertEquals(TimerPhase.EXPIRED, rig.controller.display().phase)
        }

    @Test
    fun joiningAnAlreadyExpiredTimer_isSilent() =
        test {
            val rig = Rig()
            rig.gateway.next = rig.dto(e1, 7, ConferenceTimerStatus.EXPIRED, 60_000, 0)
            rig.controller.resync()
            assertEquals(TimerPhase.EXPIRED, rig.controller.display().phase)
            assertEquals(0, rig.gongs)
            assertTrue(rig.notes.isEmpty(), "a late joiner hears nothing about the state they found: ${rig.notes}")
            assertEquals(0, rig.tickerStarts)
        }

    @Test
    fun theNotes_areSaidInWords_andThrottled() =
        test {
            val rig = Rig(eventGuest = true)
            // an event guest is in the room when the timer starts: the first snapshot is a (server-realistic, 1 ms elapsed) fresh run, so it IS announced
            rig.controller.onPacket(rig.dto(e1, 1, ConferenceTimerStatus.RUNNING, 135_000, 134_999))
            assertEquals(listOf("Timer gestartet: 2 Minuten 15 Sekunden"), rig.notes)
            rig.now += 1_000
            rig.controller.onPacket(rig.dto(e1, 2, ConferenceTimerStatus.PAUSED, 135_000, 134_000))
            assertEquals(1, rig.notes.size, "inside the 5 s gap")
            rig.now += 5_000
            rig.controller.onPacket(rig.dto(e1, 3, ConferenceTimerStatus.RUNNING, 135_000, 134_000))
            assertEquals("Timer fortgesetzt", rig.notes.last())
        }

    @Test
    fun aServerRestart_showsTheResetNoteForEightSeconds_andIsSaidOnce() =
        test {
            assertEquals(8_000, CONFERENCE_TIMER_RESET_NOTICE_MS)
            val rig = Rig(resetNoticeMs = 50)
            rig.controller.onPacket(rig.dto(e1, 3, ConferenceTimerStatus.RUNNING, 60_000, 60_000))
            rig.now += 20_000
            rig.controller.onPacket(rig.dto(e2, 0, ConferenceTimerStatus.OFF, 0, 0))
            assertFalse(rig.controller.display().visible)
            assertTrue(rig.controller.display().resetNotice)
            assertEquals("Timer wurde zurückgesetzt", rig.notes.last())
            assertEquals(1, rig.notes.count { it == "Timer wurde zurückgesetzt" })
            delay(300)
            assertFalse(rig.controller.display().resetNotice, "the note goes away by itself")
        }

    @Test
    fun aNewTimerInANewEpoch_isNotAResetNote() =
        test {
            val rig = Rig()
            rig.controller.onPacket(rig.dto(e1, 3, ConferenceTimerStatus.RUNNING, 60_000, 60_000))
            rig.controller.onPacket(rig.dto(e2, 1, ConferenceTimerStatus.RUNNING, 30_000, 30_000))
            assertFalse(rig.controller.display().resetNotice)
            assertEquals(30_000L, rig.controller.display().remainingMs)
        }

    @Test
    fun anEventGuest_neverCallsTheServer() =
        test {
            val rig = Rig(moderator = false, eventGuest = true)
            rig.controller.resync()
            rig.controller.start(60)
            assertTrue(rig.gateway.calls.isEmpty())
            rig.controller.onPacket(rig.dto(e1, 1, ConferenceTimerStatus.RUNNING, 60_000, 60_000))
            rig.now += 120_000
            rig.controller.tick()
            assertTrue(rig.gateway.calls.isEmpty(), "not even the one-minute resync")
        }

    @Test
    fun onlyAModeratorControls_andTheAnswerIsAppliedLikeABroadcast() =
        test {
            val participant = Rig(moderator = false)
            participant.gateway.next = participant.dto(e1, 1, ConferenceTimerStatus.RUNNING, 60_000, 60_000)
            participant.controller.start(60)
            assertTrue(participant.gateway.calls.isEmpty())

            val rig = Rig()
            rig.gateway.next = rig.dto(e1, 1, ConferenceTimerStatus.RUNNING, 60_000, 60_000)
            rig.controller.start(60)
            assertEquals(listOf("start:60"), rig.gateway.calls)
            assertEquals(ConferenceTimerStatus.RUNNING, rig.controller.display().status)
            assertFalse(rig.controller.busy)
            rig.gateway.next = rig.dto(e1, 2, ConferenceTimerStatus.RUNNING, 90_000, 90_000)
            rig.controller.extend(30)
            rig.gateway.next = rig.dto(e1, 3, ConferenceTimerStatus.PAUSED, 90_000, 90_000)
            rig.controller.pause()
            rig.gateway.next = rig.dto(e1, 4, ConferenceTimerStatus.RUNNING, 90_000, 90_000)
            rig.controller.resume()
            rig.gateway.next = rig.dto(e1, 5, ConferenceTimerStatus.OFF, 0, 0)
            rig.controller.stop()
            assertEquals(listOf("start:60", "extend:30", "pause", "resume", "stop"), rig.gateway.calls)
            assertFalse(rig.controller.display().visible)
        }

    @Test
    fun aFailedControlCall_changesNothing() =
        test {
            val rig = Rig()
            rig.gateway.next = null
            rig.controller.start(60)
            assertFalse(rig.controller.display().visible)
            assertFalse(rig.controller.busy)
            assertNull(
                rig.controller
                    .display()
                    .remainingMs
                    .takeIf { it > 0 },
            )
        }

    @Test
    fun aRunningTimer_isReadFromTheServerOnceAMinute_andNothingAfterDispose() =
        test {
            val rig = Rig()
            rig.controller.onPacket(rig.dto(e1, 1, ConferenceTimerStatus.RUNNING, 600_000, 600_000))
            rig.gateway.next = rig.dto(e1, 1, ConferenceTimerStatus.RUNNING, 600_000, 600_000)
            rig.now += 61_000
            rig.gateway.next = rig.dto(e1, 1, ConferenceTimerStatus.RUNNING, 600_000, 539_000)
            rig.controller.tick()
            delay(30)
            assertEquals(listOf("state"), rig.gateway.calls)
            rig.controller.dispose()
            val changes = rig.changes
            rig.now += 61_000
            rig.controller.tick()
            rig.controller.onPacket(rig.dto(e1, 9, ConferenceTimerStatus.OFF, 0, 0))
            assertEquals(changes, rig.changes, "a disposed controller paints nothing")
            assertTrue(rig.tickerStops >= 1)
        }
}
