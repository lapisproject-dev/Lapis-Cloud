package network.lapis.cloud.client.livekit

import network.lapis.cloud.shared.domain.ConferenceTimerDto
import network.lapis.cloud.shared.domain.ConferenceTimerStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** V1.9.133 -- the strict timer packet, the clock offset, the countdown arithmetic, the epoch rule, the announcements and the gong rule. */
class ConferenceTimerTest {
    private val epoch = "0123456789abcdef"

    private fun packet(
        v: String = "1",
        e: String = "\"$epoch\"",
        seq: String = "3",
        s: String = "\"R\"",
        d: String = "60000",
        rem: String = "45000",
        t: String = "1760000000000",
        extra: String = "",
    ) = """{"v":$v,"e":$e,"seq":$seq,"s":$s,"d":$d,"rem":$rem,"t":$t$extra}""".encodeToByteArray()

    private fun dto(
        seq: Long = 1,
        status: ConferenceTimerStatus = ConferenceTimerStatus.RUNNING,
        initial: Long = 60_000,
        remaining: Long = 60_000,
        t: Long = 1_000_000,
        epoch: String = this.epoch,
    ) = ConferenceTimerDto(epoch, seq, status, initial, remaining, t)

    // -- packet ------------------------------------------------------------------------------------------------------------------------

    @Test
    fun aValidPacketBecomesTheDto() {
        val parsed = assertNotNull(conferenceTimerPacket(fromParticipant = false, payload = packet()))
        assertEquals(ConferenceTimerDto(epoch, 3, ConferenceTimerStatus.RUNNING, 60_000, 45_000, 1_760_000_000_000), parsed)
        assertEquals(ConferenceTimerStatus.PAUSED, conferenceTimerPacket(false, packet(s = "\"P\""))?.status)
        assertEquals(ConferenceTimerStatus.EXPIRED, conferenceTimerPacket(false, packet(s = "\"X\"", rem = "0"))?.status)
        assertEquals(ConferenceTimerStatus.OFF, conferenceTimerPacket(false, packet(s = "\"O\"", d = "0", rem = "0"))?.status)
    }

    @Test
    fun aPacketFromAParticipantIsAForgery() {
        assertNull(conferenceTimerPacket(fromParticipant = true, payload = packet()))
    }

    @Test
    fun everyDeviationIsDropped_andNothingThrows() {
        assertNull(conferenceTimerPacket(false, ByteArray(0)))
        assertNull(conferenceTimerPacket(false, ByteArray(257) { 'a'.code.toByte() }), "too big")
        assertNull(conferenceTimerPacket(false, "not json".encodeToByteArray()))
        assertNull(conferenceTimerPacket(false, "[1,2]".encodeToByteArray()))
        assertNull(conferenceTimerPacket(false, packet(v = "2")))
        assertNull(conferenceTimerPacket(false, packet(v = "\"1\"")))
        assertNull(conferenceTimerPacket(false, packet(extra = ",\"x\":1")), "an extra key")
        assertNull(
            conferenceTimerPacket(false, "{\"v\":1,\"e\":\"$epoch\",\"seq\":1,\"s\":\"R\",\"d\":1,\"rem\":1}".encodeToByteArray()),
            "a missing key",
        )
        assertNull(conferenceTimerPacket(false, packet(e = "\"XYZ\"")), "a wrong epoch")
        assertNull(conferenceTimerPacket(false, packet(e = "\"0123456789ABCDEF\"")), "an upper-case epoch")
        assertNull(conferenceTimerPacket(false, packet(e = "1234567890123456")), "an epoch that is a number")
        assertNull(conferenceTimerPacket(false, packet(seq = "-1")))
        assertNull(conferenceTimerPacket(false, packet(seq = "\"3\"")), "a string instead of a number")
        assertNull(conferenceTimerPacket(false, packet(seq = "1.5")))
        assertNull(conferenceTimerPacket(false, packet(s = "\"Z\"")))
        assertNull(conferenceTimerPacket(false, packet(s = "1")))
        assertNull(conferenceTimerPacket(false, packet(d = "86400001")))
        assertNull(conferenceTimerPacket(false, packet(d = "-1")))
        assertNull(conferenceTimerPacket(false, packet(rem = "3600001")))
        assertNull(conferenceTimerPacket(false, packet(rem = "-1")))
        assertNull(conferenceTimerPacket(false, packet(rem = "\"45000\"")))
        assertNull(conferenceTimerPacket(false, packet(t = "0")))
        assertNull(conferenceTimerPacket(false, packet(t = "\"1760000000000\"")))
    }

    // -- clock offset ------------------------------------------------------------------------------------------------------------------

    @Test
    fun theSampleWithTheSmallestRoundTripWins() {
        val clock = ServerClockOffset()
        // server = local + 1000: sent at 100, answered at 300 (rtt 200), the server stamped 1200 -> offset 1000
        clock.addRpcSample(serverNowEpochMs = 1_200, sendLocal = 100.0, recvLocal = 300.0)
        assertEquals(1_000.0, assertNotNull(clock.offset(300.0)), 1e-9)
        // a slower, skewed sample (rtt 800) does not replace it
        clock.addRpcSample(serverNowEpochMs = 1_900, sendLocal = 400.0, recvLocal = 1_200.0)
        assertEquals(1_000.0, assertNotNull(clock.offset(1_200.0)), 1e-9)
        // a faster one does (rtt 20)
        clock.addRpcSample(serverNowEpochMs = 2_500, sendLocal = 1_490.0, recvLocal = 1_510.0)
        assertEquals(1_000.0, assertNotNull(clock.offset(1_510.0)), 1e-9)
    }

    @Test
    fun theWindowExpires() {
        val clock = ServerClockOffset(windowMs = 300_000.0)
        clock.addRpcSample(serverNowEpochMs = 1_050, sendLocal = 0.0, recvLocal = 100.0)
        assertNotNull(clock.offset(299_000.0))
        assertNull(clock.offset(300_101.0), "older than 5 minutes: gone")
        // a younger, slower sample outlives an older, faster one that expired
        clock.addRpcSample(serverNowEpochMs = 2_000_700, sendLocal = 1_000_000.0, recvLocal = 1_000_600.0)
        assertEquals(2_000_700 - 1_000_300.0, assertNotNull(clock.offset(1_000_700.0)), 1e-9)
    }

    @Test
    fun aPacketSampleCountsOnlyWhileThereIsNoRpcSample() {
        val clock = ServerClockOffset()
        assertNull(clock.offset(0.0))
        clock.addPacketSample(serverNowEpochMs = 5_000, recvLocal = 1_000.0)
        assertEquals(4_000.0, assertNotNull(clock.offset(1_000.0)), 1e-9)
        // the most recent packet is the better guess among packets
        clock.addPacketSample(serverNowEpochMs = 9_100, recvLocal = 5_000.0)
        assertEquals(4_100.0, assertNotNull(clock.offset(5_000.0)), 1e-9)
        // an RPC sample wins from then on, however slow
        clock.addRpcSample(serverNowEpochMs = 6_500, sendLocal = 5_500.0, recvLocal = 7_500.0)
        assertEquals(0.0, assertNotNull(clock.offset(7_500.0)), 1e-9)
        clock.addPacketSample(serverNowEpochMs = 99_000, recvLocal = 8_000.0)
        assertEquals(0.0, assertNotNull(clock.offset(8_000.0)), 1e-9)
        // a negative round trip (a clock that jumped) is no sample
        val other = ServerClockOffset()
        other.addRpcSample(serverNowEpochMs = 1, sendLocal = 10.0, recvLocal = 5.0)
        assertNull(other.offset(10.0))
    }

    @Test
    fun theEndOfTheCountdown_withAndWithoutAnOffset() {
        val d = dto(remaining = 30_000, t = 1_000_000)
        // offset 999_000: server 1_000_000 is local 1_000; the end is 30 s later
        assertEquals(31_000.0, endsAtLocal(d, offset = 999_000.0, recvLocal = 5_000.0), 1e-9)
        // without an offset the packet counts as "just received"
        assertEquals(35_000.0, endsAtLocal(d, offset = null, recvLocal = 5_000.0), 1e-9)
        assertEquals(10_000L, timerRemainingMs(endsAtLocal = 31_000.0, nowLocal = 21_000.0))
        assertEquals(0L, timerRemainingMs(endsAtLocal = 31_000.0, nowLocal = 31_000.0))
        assertEquals(0L, timerRemainingMs(endsAtLocal = 31_000.0, nowLocal = 99_000.0), "never negative")
    }

    // -- display -----------------------------------------------------------------------------------------------------------------------

    @Test
    fun theTimeIsRoundedUp_toMinutesAndSeconds() {
        assertEquals("00:00", formatMmSs(0))
        assertEquals("00:01", formatMmSs(1))
        assertEquals("00:01", formatMmSs(999))
        assertEquals("00:01", formatMmSs(1_000))
        assertEquals("00:02", formatMmSs(1_001))
        assertEquals("00:10", formatMmSs(10_000))
        assertEquals("02:15", formatMmSs(135_000))
        assertEquals("60:00", formatMmSs(3_600_000))
        assertEquals("00:00", formatMmSs(-5), "never negative")
    }

    @Test
    fun thePhasesChangeAtTheirBoundaries() {
        val running = ConferenceTimerStatus.RUNNING
        assertEquals(TimerPhase.NORMAL, timerPhase(10_001, running))
        assertEquals(TimerPhase.FINAL, timerPhase(10_000, running))
        assertEquals(TimerPhase.FINAL, timerPhase(1, running))
        assertEquals(TimerPhase.EXPIRED, timerPhase(0, running))
        assertEquals(TimerPhase.EXPIRED, timerPhase(0, ConferenceTimerStatus.EXPIRED))
        assertEquals(TimerPhase.NORMAL, timerPhase(5_000, ConferenceTimerStatus.PAUSED), "a paused timer does not pulse")
        assertEquals(TimerPhase.NORMAL, timerPhase(0, ConferenceTimerStatus.OFF))
    }

    @Test
    fun theAccessibleName_saysTheTimeInWords() {
        assertEquals("Timer: noch 2 Minuten 15 Sekunden", timerAccessibleName(135_000))
        assertEquals("Timer: noch 1 Minute", timerAccessibleName(60_000))
        assertEquals("Timer: noch 1 Sekunde", timerAccessibleName(1))
        assertEquals("Timer: noch 0 Sekunden", timerAccessibleName(0))
        assertEquals("Timer: noch 1 Minute 1 Sekunde", timerAccessibleName(61_000))
    }

    @Test
    fun theFractionOfTheRing_staysBetweenZeroAndOne() {
        assertEquals(0.75, TimerDisplay(ConferenceTimerStatus.RUNNING, 45_000, 60_000, TimerPhase.NORMAL).fraction, 1e-9)
        assertEquals(1.0, TimerDisplay(ConferenceTimerStatus.RUNNING, 90_000, 60_000, TimerPhase.NORMAL).fraction, 1e-9)
        assertEquals(0.0, TimerDisplay(ConferenceTimerStatus.EXPIRED, 0, 60_000, TimerPhase.EXPIRED).fraction, 1e-9)
        assertEquals(0.0, TimerDisplay.OFF.fraction, 1e-9)
        assertFalse(TimerDisplay.OFF.visible)
    }

    // -- epoch rule --------------------------------------------------------------------------------------------------------------------

    @Test
    fun theView_takesOnlyNewerSnapshots_andAnotherEpochAlwaysWins() {
        val view = ConferenceTimerView()
        assertEquals(TimerAccepted.NEWER, view.accept(dto(seq = 2)))
        assertEquals(TimerAccepted.STALE, view.accept(dto(seq = 2)), "the same seq")
        assertEquals(TimerAccepted.STALE, view.accept(dto(seq = 1)), "an older one")
        assertEquals(2, view.current?.seq)
        assertEquals(TimerAccepted.NEWER, view.accept(dto(seq = 5)))
        assertEquals(TimerAccepted.EPOCH_CHANGED, view.accept(dto(seq = 0, epoch = "fedcba9876543210")), "a restarted server starts over")
        assertEquals(0, view.current?.seq)
        assertEquals(TimerAccepted.STALE, view.accept(dto(seq = 3, epoch = "fedcba9876543210").copy(seq = 0)))
    }

    // -- announcements -----------------------------------------------------------------------------------------------------------------

    @Test
    fun theTransitions_areSaidInWords_andALateJoinerHearsNothing() {
        var clock = 100_000.0
        val planner = TimerAnnouncementPlanner(now = { clock })
        val off = dto(status = ConferenceTimerStatus.OFF, initial = 0, remaining = 0)
        val running = dto(seq = 1, remaining = 135_000, initial = 135_000)
        val paused = dto(seq = 2, status = ConferenceTimerStatus.PAUSED, remaining = 100_000)
        assertEquals("Timer gestartet: 2 Minuten 15 Sekunden", planner.onTransition(off, running, lateJoin = false))
        clock += 5_000
        assertEquals("Timer pausiert", planner.onTransition(running, paused, lateJoin = false))
        clock += 5_000
        assertEquals("Timer fortgesetzt", planner.onTransition(paused, running.copy(seq = 3), lateJoin = false))
        clock += 5_000
        assertEquals("Timer gestoppt", planner.onTransition(running, off.copy(seq = 4), lateJoin = false))
        clock += 5_000
        assertNull(planner.onTransition(off, running.copy(seq = 5), lateJoin = true), "a late joiner hears nothing")
        assertNull(planner.onTransition(running, running.copy(seq = 6), lateJoin = false), "an extension has no sentence of its own")
    }

    @Test
    fun theAnnouncements_areThrottledToOneEveryFiveSeconds_exceptTheEnd() {
        var clock = 0.0
        val planner = TimerAnnouncementPlanner(now = { clock })
        val off = dto(status = ConferenceTimerStatus.OFF, initial = 0, remaining = 0)
        val running = dto(seq = 1, remaining = 60_000, initial = 60_000)
        assertNotNull(planner.onTransition(off, running, lateJoin = false))
        clock += 1_000
        assertNull(planner.onTransition(running, off.copy(seq = 2), lateJoin = false), "inside the gap")
        clock += 4_000
        assertNotNull(planner.onTransition(running, off.copy(seq = 3), lateJoin = false), "after the gap")
        clock += 100
        assertEquals("Zeit abgelaufen", planner.onTick(prevRemaining = 200, remaining = 0, initialMs = 60_000), "the end is always said")
        assertNull(planner.onTick(prevRemaining = 0, remaining = 0, initialMs = 60_000))
    }

    @Test
    fun theCountdownMarks_oneMinuteOnlyFromTwoMinutes_tenSecondsAlways() {
        var clock = 0.0
        val planner = TimerAnnouncementPlanner(now = { clock })
        assertEquals("Noch eine Minute", planner.onTick(prevRemaining = 61_000, remaining = 60_000, initialMs = 120_000))
        clock += 6_000
        assertNull(
            planner.onTick(prevRemaining = 61_000, remaining = 60_000, initialMs = 119_000),
            "a timer under two minutes says no minute mark",
        )
        assertEquals("Noch 10 Sekunden", planner.onTick(prevRemaining = 10_250, remaining = 10_000, initialMs = 60_000))
        clock += 6_000
        assertNull(planner.onTick(prevRemaining = 10_000, remaining = 9_750, initialMs = 60_000), "the mark is said once")
        // a tab that slept: one jump across both marks says only the more urgent one
        clock += 6_000
        assertEquals("Noch 10 Sekunden", planner.onTick(prevRemaining = 90_000, remaining = 5_000, initialMs = 300_000))
        // the burst is throttled
        assertNull(planner.onTick(prevRemaining = 61_000, remaining = 59_000, initialMs = 300_000))
    }

    @Test
    fun theResetNote_isSaidOnceInsideTheThrottle() {
        var clock = 0.0
        val planner = TimerAnnouncementPlanner(now = { clock })
        assertEquals("Timer wurde zurückgesetzt", planner.onReset())
        clock += 1_000
        assertNull(planner.onReset())
    }

    // -- gong --------------------------------------------------------------------------------------------------------------------------

    @Test
    fun theGong_soundsOnlyForAnEndThatWasExperiencedLive() {
        assertTrue(shouldPlayGong(prevRemaining = 200, remaining = 0, expiredAgoMs = 40.0, initialSnapshot = false))
        assertTrue(shouldPlayGong(prevRemaining = 200, remaining = 0, expiredAgoMs = 2_000.0, initialSnapshot = false))
        assertFalse(shouldPlayGong(prevRemaining = 200, remaining = 0, expiredAgoMs = 2_001.0, initialSnapshot = false), "too late")
        assertFalse(
            shouldPlayGong(prevRemaining = 0, remaining = 0, expiredAgoMs = 10.0, initialSnapshot = false),
            "joined an expired timer",
        )
        assertFalse(shouldPlayGong(prevRemaining = 200, remaining = 0, expiredAgoMs = 10.0, initialSnapshot = true), "the first snapshot")
        assertFalse(shouldPlayGong(prevRemaining = 5_000, remaining = 4_000, expiredAgoMs = 0.0, initialSnapshot = false), "still running")
    }

    // -- dialog helpers ----------------------------------------------------------------------------------------------------------------

    @Test
    fun theOwnTime_isValidatedInWholeSecondsBetweenTenSecondsAndAnHour() {
        assertEquals(135, customDurationSeconds(minutes = 2, seconds = 15))
        assertEquals(10, customDurationSeconds(minutes = 0, seconds = 10))
        assertEquals(10, customDurationSeconds(minutes = null, seconds = 10))
        assertEquals(120, customDurationSeconds(minutes = 2, seconds = null))
        assertEquals(3_600, customDurationSeconds(minutes = 60, seconds = 0))
        assertNull(customDurationSeconds(minutes = 0, seconds = 9))
        assertNull(customDurationSeconds(minutes = 60, seconds = 1))
        assertNull(customDurationSeconds(minutes = 61, seconds = 0))
        assertNull(customDurationSeconds(minutes = 0, seconds = 60))
        assertNull(customDurationSeconds(minutes = -1, seconds = 30))
        assertNull(customDurationSeconds(minutes = null, seconds = null))
    }

    @Test
    fun anExtension_isOfferedOnlyWhileTheRemainingTimeStaysUnderAnHour() {
        assertTrue(timerExtensionAllowed(remainingMs = 3_570_000, extensionSeconds = 30))
        assertFalse(timerExtensionAllowed(remainingMs = 3_570_001, extensionSeconds = 30))
        assertFalse(timerExtensionAllowed(remainingMs = 3_300_001, extensionSeconds = 300))
        assertTrue(timerExtensionAllowed(remainingMs = 0, extensionSeconds = 300))
        assertFalse(timerExtensionAllowed(remainingMs = 0, extensionSeconds = 45), "only the three steps")
    }
}
