package network.lapis.cloud.shared.conference

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** V1.9.115 -- the speaker smoothing: claim time, dwell, gap merging, silence, simultaneous speech, forget, batch = incremental. */
class SpeakerSmoothingTest {
    private fun SpeakerStageSmoother.speak(
        from: Long,
        to: Long,
        who: List<String>,
        step: Long = 100,
    ) {
        var t = from
        while (t <= to) {
            observe(nowMs = t, speakingByLevel = who)
            t += step
        }
    }

    @Test
    fun `a candidate takes over after exactly 800 ms of uninterrupted speech and not before`() {
        val early = SpeakerStageSmoother()
        early.speak(from = 0, to = 799, who = listOf("a"), step = 1)
        assertNull(early.current())
        val exact = SpeakerStageSmoother()
        exact.speak(from = 0, to = 800, who = listOf("a"), step = 1)
        assertEquals("a", exact.current())
    }

    @Test
    fun `a hole of one sample does not restart the claim but a longer one does`() {
        val ok = SpeakerStageSmoother()
        ok.speak(from = 0, to = 100, who = listOf("a"))
        ok.observe(nowMs = 200, speakingByLevel = emptyList())
        ok.speak(from = 300, to = 700, who = listOf("a"))
        assertNull(ok.current())
        assertEquals("a", ok.observe(nowMs = 800, speakingByLevel = listOf("a")))
        val broken = SpeakerStageSmoother()
        broken.speak(from = 0, to = 100, who = listOf("a"))
        broken.speak(from = 200, to = 700, who = emptyList())
        broken.speak(from = 800, to = 1500, who = listOf("a"))
        assertNull(broken.current()) // the claim restarted at 800
        assertEquals("a", broken.observe(nowMs = 1600, speakingByLevel = listOf("a")))
    }

    @Test
    fun `the large tile does not change before the dwell time of 2000 ms has passed`() {
        val s = SpeakerStageSmoother(gapMergeMs = 0)
        s.speak(from = 0, to = 800, who = listOf("a"))
        assertEquals("a", s.current()) // a took over at 800
        s.speak(from = 900, to = 2799, who = listOf("b"))
        assertEquals("a", s.current()) // b's claim is ready at 1700, but the tile changes 2000 ms after 800 at the earliest
        assertEquals("b", s.observe(nowMs = 2800, speakingByLevel = listOf("b")))
    }

    @Test
    fun `a pause of the current speaker of 1500 ms is bridged and 1501 ms is not`() {
        fun run(at: Long): String? {
            val s = SpeakerStageSmoother(dwellMs = 0)
            s.speak(from = 0, to = 1000, who = listOf("a")) // a is the main speaker, last spoke at 1000
            // b starts at 1000 + 1 (so the claim is ready 800 ms later)
            s.observe(nowMs = 1001, speakingByLevel = listOf("b"))
            s.observe(nowMs = 1100, speakingByLevel = listOf("b"))
            var t = 1100L
            while (t < at) {
                t = minOf(t + 100, at)
                s.observe(nowMs = t, speakingByLevel = listOf("b"))
            }
            return s.current()
        }
        // gap = at - 1000
        assertEquals("a", run(2500))
        assertEquals("b", run(2501))
    }

    @Test
    fun `silence keeps the large tile`() {
        val s = SpeakerStageSmoother()
        s.speak(from = 0, to = 1000, who = listOf("a"))
        s.speak(from = 1100, to = 60_000, who = emptyList(), step = 1000)
        assertEquals("a", s.current())
    }

    @Test
    fun `with two speakers at once the louder one first in the list wins the claim`() {
        val s = SpeakerStageSmoother()
        s.speak(from = 0, to = 800, who = listOf("loud", "quiet"))
        assertEquals("loud", s.current())
    }

    @Test
    fun `the current speaker keeps the tile while somebody else talks over them`() {
        val s = SpeakerStageSmoother()
        s.speak(from = 0, to = 800, who = listOf("a"))
        s.speak(from = 900, to = 6000, who = listOf("a", "b"))
        assertEquals("a", s.current())
    }

    @Test
    fun `forget of the main speaker lets the next claim take over at once and forget of a candidate resets the claim`() {
        val s = SpeakerStageSmoother()
        s.speak(from = 0, to = 800, who = listOf("a"))
        s.forget("a")
        assertEquals("a", s.current()) // stays until the next claim
        s.speak(from = 850, to = 1649, who = listOf("b"))
        assertEquals("a", s.current())
        assertEquals("b", s.observe(nowMs = 1650, speakingByLevel = listOf("b")))
        val c = SpeakerStageSmoother()
        c.speak(from = 0, to = 500, who = listOf("x"))
        c.forget("x")
        c.speak(from = 600, to = 1399, who = listOf("x"))
        assertNull(c.current())
        assertEquals("x", c.observe(nowMs = 1400, speakingByLevel = listOf("x")))
    }

    @Test
    fun `the batch form equals feeding the same samples incrementally for random sequences`() {
        val random = Random(20261012)
        val people = listOf("a", "b", "c", "d")
        repeat(40) {
            val samples =
                (0 until 300).map { i ->
                    val speaking = people.filter { random.nextInt(100) < 18 }.shuffled(random)
                    SpeechSample(atMs = i * 100L, speakingByLevel = speaking)
                }
            val total = 30_000L
            val spans = smoothSpeakerTimeline(samples = samples, totalMs = total)
            // incremental replay
            val smoother = SpeakerStageSmoother()
            val changes = mutableListOf<Pair<Long, String>>()
            var last: String? = null
            samples.forEach {
                val now = smoother.observe(nowMs = it.atMs, speakingByLevel = it.speakingByLevel)
                if (now != last && now != null) changes += it.atMs to now
                last = now
            }
            assertEquals(changes.map { it.first }, spans.map { it.startMs })
            assertEquals(changes.map { it.second }, spans.map { it.key })
            spans.zipWithNext().forEach { (x, y) -> assertEquals(x.endMs, y.startMs) }
            if (spans.isNotEmpty()) assertEquals(total, spans.last().endMs)
        }
    }

    @Test
    fun `an empty or silent timeline has no spans`() {
        assertEquals(emptyList(), smoothSpeakerTimeline(samples = emptyList(), totalMs = 10_000))
        assertEquals(
            emptyList(),
            smoothSpeakerTimeline(
                samples =
                    (0 until 50).map {
                        SpeechSample(atMs = it * 100L, speakingByLevel = emptyList())
                    },
                totalMs = 5_000,
            ),
        )
    }
}
