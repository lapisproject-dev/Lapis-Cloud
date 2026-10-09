package network.lapis.cloud.client

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** V1.9.85 -- the pure logic of the active-speaker mark (no DOM). */
class ConferenceSpeakingMarkTest {
    private val window = 1500L

    @Test
    fun isSpeakingNow_edges() {
        assertFalse(isSpeakingNow(0L, 1_000L, window), "0 means never")
        assertTrue(isSpeakingNow(1L, 2L, window))
        assertTrue(isSpeakingNow(10_000L, 10_000L + window - 1, window))
        assertFalse(isSpeakingNow(10_000L, 10_000L + window, window), "the window is exclusive")
        assertFalse(isSpeakingNow(10_000L, 10_000L + window + 1, window))
        assertFalse(isSpeakingNow(10_000L, 9_999L, window), "a clock that went backwards is never speaking")
        assertFalse(isSpeakingNow(10_000L, 10_000L, 0L), "window 0")
        assertFalse(isSpeakingNow(10_000L, 10_000L, -5L), "negative window")
    }

    @Test
    fun windowIsItsOwnValue() {
        assertEquals(1500L, CONFERENCE_SPEAKING_MARK_WINDOW_MS)
        assertEquals(250L, CONFERENCE_SPEAKING_MARK_TICK_MS)
    }

    @Test
    fun aReportMarksAtOnce_andOnlyTheReportedOnes() {
        val state = SpeakingMarkState()
        state.onReport(listOf("a"), 10_000L)
        assertTrue(state.isMarked("a", 10_000L))
        assertFalse(state.isMarked("b", 10_000L))
        assertTrue(state.hasActive(10_000L))
    }

    @Test
    fun staysMarkedWhileInTheSet_beyondTheWindow_withoutANewReport() {
        // regression: LiveKit reports only changes of the set -- 20 s of speech is ONE report
        val state = SpeakingMarkState()
        state.onReport(listOf("a"), 10_000L)
        var now = 10_000L
        repeat(80) {
            now += CONFERENCE_SPEAKING_MARK_TICK_MS
            state.touch(now)
            assertTrue(state.isMarked("a", now), "still marked at +${now - 10_000L} ms")
        }
        assertTrue(now - 10_000L >= 20_000L)
        assertTrue(state.hasActive(now))
    }

    @Test
    fun leavingTheSet_endsTheMarkNotBefore1500ms() {
        val state = SpeakingMarkState()
        state.onReport(listOf("a", "b"), 10_000L)
        state.touch(11_000L)
        state.onReport(listOf("b"), 11_000L) // a leaves at 11_000 (last seen 11_000)
        assertTrue(state.isMarked("a", 11_000L + window - 1))
        assertFalse(state.isMarked("a", 11_000L + window))
        state.touch(11_000L + window) // the beat: b is still in the set and stays fresh
        assertTrue(state.isMarked("b", 11_000L + window), "b is still in the set")
        assertFalse(state.isMarked("a", 11_000L + window))
    }

    @Test
    fun returningWithinTheWindow_isStable() {
        val state = SpeakingMarkState()
        state.onReport(listOf("a"), 10_000L)
        state.onReport(emptyList(), 10_500L)
        assertTrue(state.isMarked("a", 11_000L))
        state.onReport(listOf("a"), 11_200L)
        state.touch(12_000L)
        assertTrue(state.isMarked("a", 12_000L))
        state.touch(13_000L)
        assertTrue(state.isMarked("a", 13_000L))
    }

    @Test
    fun hasActive_becomesFalse_whenNothingIsActive() {
        val state = SpeakingMarkState()
        assertFalse(state.hasActive(1_000L), "nobody ever")
        state.onReport(listOf("a"), 10_000L)
        state.onReport(emptyList(), 10_100L)
        assertTrue(state.hasActive(10_000L + window - 1))
        assertFalse(state.hasActive(10_000L + window))
        state.touch(10_000L + window)
        assertFalse(state.isMarked("a", 10_000L + window))
    }

    @Test
    fun forgetAndClear_cleanUp() {
        val state = SpeakingMarkState()
        state.onReport(listOf("a", "b"), 10_000L)
        state.forget("a")
        assertFalse(state.isMarked("a", 10_000L))
        assertTrue(state.isMarked("b", 10_000L))
        state.clear()
        assertFalse(state.isMarked("b", 10_000L))
        assertFalse(state.hasActive(10_000L))
    }

    @Test
    fun identitiesAreOnlyKeys_neverInterpreted() {
        val state = SpeakingMarkState()
        val hostile = "<img src=x onerror=alert(1)>"
        state.onReport(listOf(hostile), 5_000L)
        assertTrue(state.isMarked(hostile, 5_000L))
    }
}
