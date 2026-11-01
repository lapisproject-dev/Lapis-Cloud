package network.lapis.cloud.server.conference

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe

/** V1.9.254 -- pause cut-outs in the edit decision list: output frames stay gap-free, the source frames skip the cut, the borders are hard. */
class RecordingEditDecisionListCutTest :
    FunSpec({
        val fps = 30

        fun track(
            id: String,
            start: Double,
            end: Double,
        ) = EdlVideoTrack(
            trackId = id,
            isScreenShare = false,
            firstSeenSeconds = start,
            ranges = listOf(TimeRange(startSeconds = start, endSeconds = end)),
        )

        fun build(
            total: Double,
            cuts: List<TimeRange>,
            tracks: List<EdlVideoTrack> = listOf(track("a", 0.0, total)),
        ) = RecordingEditDecisionList.build(
            tracks = tracks,
            totalSeconds = total,
            fps = fps,
            canvasW = 1280,
            canvasH = 720,
            cutOuts = cuts,
        )

        test("one cut in the middle: output frames are gap-free, the source frame jumps over the cut, the length is total minus cut") {
            val segments = build(total = 50.0, cuts = listOf(TimeRange(startSeconds = 10.0, endSeconds = 20.0)))
            segments.first().startFrame shouldBe 0L
            segments.zipWithNext().forEach { (x, y) -> x.endFrame shouldBe y.startFrame }
            segments.last().endFrame shouldBe 40L * fps
            // the segment after the cut starts at the output frame of 10 s and reads the source from 20 s
            val after = segments.first { it.sourceStartFrame >= 20L * fps }
            after.startFrame shouldBe 10L * fps
            after.sourceStartFrame shouldBe 20L * fps
            // nothing of the cut is left: no segment's source range intersects [10 s, 20 s)
            segments.none { it.sourceStartFrame < 20L * fps && it.sourceStartFrame + it.frames > 10L * fps } shouldBe true
        }

        test("a cut at the very beginning and a cut up to the very end") {
            val head = build(total = 30.0, cuts = listOf(TimeRange(startSeconds = 0.0, endSeconds = 5.0)))
            head.first().startFrame shouldBe 0L
            head.first().sourceStartFrame shouldBe 5L * fps
            head.last().endFrame shouldBe 25L * fps

            val tail = build(total = 30.0, cuts = listOf(TimeRange(startSeconds = 25.0, endSeconds = 30.0)))
            tail.last().endFrame shouldBe 25L * fps
            tail.last().sourceStartFrame + tail.last().frames shouldBe 25L * fps
        }

        test("overlapping and touching cuts are merged into one") {
            val merged =
                build(
                    total = 60.0,
                    cuts =
                        listOf(
                            TimeRange(startSeconds = 10.0, endSeconds = 20.0),
                            TimeRange(startSeconds = 18.0, endSeconds = 25.0),
                            TimeRange(startSeconds = 25.0, endSeconds = 30.0),
                        ),
                )
            merged.last().endFrame shouldBe 40L * fps
            val single = build(total = 60.0, cuts = listOf(TimeRange(startSeconds = 10.0, endSeconds = 30.0)))
            merged.map { it.startFrame to it.sourceStartFrame } shouldBe single.map { it.startFrame to it.sourceStartFrame }
        }

        test("a cut shorter than MIN_CUT_FRAMES still removes exactly its frames and its borders are never merged away") {
            // 1 frame (0.033 s) cut at 10 s; the neighbouring data cuts would otherwise merge short segments
            val segments = build(total = 20.0, cuts = listOf(TimeRange(startSeconds = 10.0, endSeconds = 10.0 + 1.0 / fps)))
            segments.last().endFrame shouldBe 20L * fps - 1L
        }

        test("a cut across the 120 s forced boundary: segments stay gap-free and the total is right") {
            val segments = build(total = 300.0, cuts = listOf(TimeRange(startSeconds = 110.0, endSeconds = 130.0)))
            segments.zipWithNext().forEach { (x, y) -> x.endFrame shouldBe y.startFrame }
            segments.last().endFrame shouldBe 280L * fps
            segments.sumOf { it.frames } shouldBe 280L * fps
        }

        test("data borders next to a cut do not leak 1-2 frames of the pause into the video") {
            // a second track starts 1 frame after the cut ends -> a tiny segment there must not swallow cut frames
            val tracks = listOf(track("a", 0.0, 40.0), track("b", 20.0 + 1.0 / fps, 40.0))
            val segments = build(total = 40.0, cuts = listOf(TimeRange(startSeconds = 10.0, endSeconds = 20.0)), tracks = tracks)
            segments.last().endFrame shouldBe 30L * fps
            segments.none { it.sourceStartFrame < 20L * fps && it.sourceStartFrame + it.frames > 10L * fps } shouldBe true
        }

        test("a cut that covers everything leaves no segment") {
            build(total = 10.0, cuts = listOf(TimeRange(startSeconds = 0.0, endSeconds = 10.0))).shouldBeEmpty()
        }

        test("without cuts the result is the same as before (source frames equal output frames)") {
            val segments = build(total = 50.0, cuts = emptyList(), tracks = listOf(track("a", 0.0, 50.0), track("b", 12.34, 33.3)))
            segments.all { it.sourceStartFrame == it.startFrame } shouldBe true
            segments.last().endFrame shouldBe 1500L
        }
    })
