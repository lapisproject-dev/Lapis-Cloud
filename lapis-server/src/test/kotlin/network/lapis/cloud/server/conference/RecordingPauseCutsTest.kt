package network.lapis.cloud.server.conference

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Instant

/** V1.9.254 -- the cut-out stretches computed from the pauses and the track starts of the next stage. */
class RecordingPauseCutsTest :
    FunSpec({
        val t0Seconds = 1_790_000_000L
        val t0 = t0Seconds * 1_000_000_000L

        fun utc(secondsAfterT0: Double): LocalDateTime {
            val epochMs = (t0Seconds * 1000L) + (secondsAfterT0 * 1000.0).toLong()
            return Instant.fromEpochMilliseconds(epochMs).toLocalDateTime(TimeZone.UTC)
        }

        fun nanos(secondsAfterT0: Double) = t0 + (secondsAfterT0 * 1e9).toLong()

        test("the end is the later of the resume and the first track of the next stage; the start is the pause") {
            val cuts =
                RecordingPauseCuts.compute(
                    pauses = listOf(RecordingPauseSpan(seq = 1, pausedAt = utc(10.0), resumedAt = utc(12.0))),
                    tracks =
                        listOf(
                            StageTrackStart(stage = 0, startedAtEpochNanos = nanos(0.0)),
                            StageTrackStart(stage = 1, startedAtEpochNanos = nanos(14.0)),
                            StageTrackStart(stage = 1, startedAtEpochNanos = nanos(15.0)),
                        ),
                    t0EpochNanos = t0,
                    totalSeconds = 40.0,
                )
            cuts.size shouldBe 1
            cuts.single().startSeconds shouldBe (10.0 plusOrMinus 0.001)
            cuts.single().endSeconds shouldBe (14.0 plusOrMinus 0.001)
        }

        test("no track of the next stage: the end is the resume") {
            val cuts =
                RecordingPauseCuts.compute(
                    pauses = listOf(RecordingPauseSpan(seq = 1, pausedAt = utc(10.0), resumedAt = utc(12.0))),
                    tracks = listOf(StageTrackStart(stage = 0, startedAtEpochNanos = nanos(0.0))),
                    t0EpochNanos = t0,
                    totalSeconds = 40.0,
                )
            cuts.single().endSeconds shouldBe (12.0 plusOrMinus 0.001)
        }

        test("an open pause (stop in the pause) runs to the end of the recording") {
            val cuts =
                RecordingPauseCuts.compute(
                    pauses = listOf(RecordingPauseSpan(seq = 1, pausedAt = utc(30.0), resumedAt = null)),
                    tracks = listOf(StageTrackStart(stage = 0, startedAtEpochNanos = nanos(0.0))),
                    t0EpochNanos = t0,
                    totalSeconds = 40.0,
                )
            cuts.single().startSeconds shouldBe (30.0 plusOrMinus 0.001)
            cuts.single().endSeconds shouldBe (40.0 plusOrMinus 0.001)
        }

        test("stage n only looks at the tracks of ITS stage: pause 2 ends at the first stage-2 track") {
            val cuts =
                RecordingPauseCuts.compute(
                    pauses =
                        listOf(
                            RecordingPauseSpan(seq = 1, pausedAt = utc(10.0), resumedAt = utc(12.0)),
                            RecordingPauseSpan(seq = 2, pausedAt = utc(20.0), resumedAt = utc(21.0)),
                        ),
                    tracks =
                        listOf(
                            StageTrackStart(stage = 0, startedAtEpochNanos = nanos(0.0)),
                            StageTrackStart(stage = 1, startedAtEpochNanos = nanos(13.0)),
                            StageTrackStart(stage = 2, startedAtEpochNanos = nanos(23.0)),
                        ),
                    t0EpochNanos = t0,
                    totalSeconds = 40.0,
                )
            cuts.map { it.startSeconds to it.endSeconds }.map { (a, b) -> Math.round(a) to Math.round(b) } shouldBe
                listOf(10L to 13L, 20L to 23L)
        }

        test("the cut is clamped to the recording and a pause before t0 starts at 0") {
            val cuts =
                RecordingPauseCuts.compute(
                    pauses =
                        listOf(
                            RecordingPauseSpan(seq = 1, pausedAt = utc(-5.0), resumedAt = utc(2.0)),
                            RecordingPauseSpan(seq = 2, pausedAt = utc(38.0), resumedAt = utc(60.0)),
                        ),
                    tracks = emptyList(),
                    t0EpochNanos = t0,
                    totalSeconds = 40.0,
                )
            cuts[0].startSeconds shouldBe 0.0
            cuts[1].endSeconds shouldBe 40.0
        }

        test("no pauses: no cuts, and the total length of cuts is the merged length") {
            RecordingPauseCuts
                .compute(
                    pauses = emptyList(),
                    tracks = emptyList(),
                    t0EpochNanos = t0,
                    totalSeconds = 40.0,
                ).shouldBeEmpty()
            val merged =
                listOf(
                    TimeRange(startSeconds = 1.0, endSeconds = 5.0),
                    TimeRange(startSeconds = 4.0, endSeconds = 8.0),
                    TimeRange(startSeconds = 30.0, endSeconds = 50.0),
                )
            RecordingPauseCuts.totalLength(cuts = merged, totalSeconds = 40.0) shouldBe 17.0
        }

        test("the UTC zone is explicit: the process time zone never shifts a cut") {
            val previous = java.util.TimeZone.getDefault()
            try {
                java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("Pacific/Auckland"))
                val cuts =
                    RecordingPauseCuts.compute(
                        pauses = listOf(RecordingPauseSpan(seq = 1, pausedAt = utc(10.0), resumedAt = utc(12.0))),
                        tracks = emptyList(),
                        t0EpochNanos = t0,
                        totalSeconds = 40.0,
                    )
                cuts.single().startSeconds shouldBe (10.0 plusOrMinus 0.001)
            } finally {
                java.util.TimeZone.setDefault(previous)
            }
        }
    })
