package network.lapis.cloud.client

import network.lapis.cloud.shared.domain.GuestScreenShareOutcome
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** V1.9.282 -- the pure rules of the guest screen share: notices, button visibility, the 1 s spacing of sentences, the moderator's row states. */
class EventGuestScreenShareLogicTest {
    @Test
    fun noticeFor_coversEveryTransition() {
        assertNull(guestShareNoticeFor(previous = false, allowed = false, sharing = false, supported = true))
        assertNull(guestShareNoticeFor(previous = true, allowed = true, sharing = false, supported = true))
        assertEquals(GuestShareNotice.ALLOWED, guestShareNoticeFor(previous = false, allowed = true, sharing = false, supported = true))
        assertEquals(
            GuestShareNotice.ALLOWED_BUT_UNSUPPORTED,
            guestShareNoticeFor(previous = false, allowed = true, sharing = false, supported = false),
        )
        assertEquals(GuestShareNotice.WITHDRAWN, guestShareNoticeFor(previous = true, allowed = false, sharing = false, supported = true))
        assertEquals(
            GuestShareNotice.STOPPED_BY_MODERATION,
            guestShareNoticeFor(previous = true, allowed = false, sharing = true, supported = true),
        )
    }

    @Test
    fun theButton_isShownOnlyWithGrantAndSupport_orWhileSharing() {
        assertFalse(guestShareButtonVisible(allowed = false, supported = true, sharing = false))
        assertFalse(guestShareButtonVisible(allowed = true, supported = false, sharing = false))
        assertTrue(guestShareButtonVisible(allowed = true, supported = true, sharing = false))
        // ending a share is always possible
        assertTrue(guestShareButtonVisible(allowed = false, supported = false, sharing = true))
    }

    @Test
    fun throttle_showsTheFirstAtOnce_keepsOnlyTheLatest_andFlushesAfterTheGap() {
        var now = 10_000.0
        val throttle = GuestNoticeThrottle({ now })
        assertEquals("a", throttle.offer("a"))
        now += 200
        assertNull(throttle.offer("b"))
        assertNull(throttle.offer("c"))
        assertTrue(throttle.hasPending)
        assertNull(throttle.flush(), "the gap has not passed")
        assertTrue(throttle.waitMs() in 1..801)
        now += 900
        assertEquals("c", throttle.flush(), "only the latest waits")
        assertFalse(throttle.hasPending)
        assertEquals(0, throttle.waitMs())
        throttle.offer("x")
        now += 10
        throttle.offer("y")
        throttle.clear()
        assertNull(throttle.flush())
    }

    @Test
    fun rowState_sharingWinsOverAllowed_andOnlyTheGrantedGuestIsAllowed() {
        assertEquals(GuestShareRowState.NOT_ALLOWED, guestShareRowState(null, emptySet(), "evg-a"))
        assertEquals(GuestShareRowState.NOT_ALLOWED, guestShareRowState("evg-b", emptySet(), "evg-a"))
        assertEquals(GuestShareRowState.ALLOWED, guestShareRowState("evg-a", emptySet(), "evg-a"))
        assertEquals(GuestShareRowState.SHARING, guestShareRowState("evg-a", setOf("evg-a"), "evg-a"))
        assertEquals(GuestShareRowState.SHARING, guestShareRowState(null, setOf("evg-a"), "evg-a"))
    }

    @Test
    fun captureHint_namesRecordingStreamOrBoth_andNothingOtherwise() {
        assertNull(guestShareCaptureHint(recordingActive = false, streamActive = false))
        val rec = assertNotNull(guestShareCaptureHint(recordingActive = true, streamActive = false))
        val stream = assertNotNull(guestShareCaptureHint(recordingActive = false, streamActive = true))
        val both = assertNotNull(guestShareCaptureHint(recordingActive = true, streamActive = true))
        assertEquals(3, setOf(rec, stream, both).size)
        assertTrue(rec.contains("Aufzeichnung") && !rec.contains("Livestream"))
        assertTrue(stream.contains("Livestream") && !stream.contains("Aufzeichnung"))
        assertTrue(both.contains("Aufzeichnung") && both.contains("Livestream"))
    }

    @Test
    fun everyOutcome_hasASentence_withTheRightSeverity() {
        val errors = setOf(GuestScreenShareOutcome.TAKEN, GuestScreenShareOutcome.NOT_IN_ROOM, GuestScreenShareOutcome.REVOKED_REMOVED)
        GuestScreenShareOutcome.entries.forEach { outcome ->
            val line = assertNotNull(guestShareOutcomeNotice(outcome), outcome.name)
            assertTrue(line.text.isNotBlank())
            assertEquals(outcome in errors, line.error, outcome.name)
        }
    }
}
