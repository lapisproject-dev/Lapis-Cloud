package network.lapis.cloud.server.conference

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.ints.shouldBeLessThanOrEqual
import io.kotest.matchers.shouldBe
import network.lapis.cloud.shared.conference.GridShape

/** V1.9.109 -- the pure edit decision list: cut points, visibility, the shared grid, slots, merging, forced cuts, the segment cap. */
class RecordingEditDecisionListTest :
    FunSpec({
        val fps = 30

        fun track(
            id: String,
            start: Double,
            end: Double,
            screen: Boolean = false,
            first: Double = start,
        ) = EdlVideoTrack(
            trackId = id,
            isScreenShare = screen,
            firstSeenSeconds = first,
            ranges = listOf(TimeRange(startSeconds = start, endSeconds = end)),
        )

        fun build(
            tracks: List<EdlVideoTrack>,
            total: Double,
        ) = RecordingEditDecisionList.build(tracks = tracks, totalSeconds = total, fps = fps, canvasW = 1280, canvasH = 720)

        test("segments tile the output exactly: contiguous, start at 0, the last ends at totalFrames") {
            val segments = build(listOf(track("a", 0.0, 50.0), track("b", 12.34, 33.3)), total = 50.0)
            segments.first().startFrame shouldBe 0L
            segments.last().endFrame shouldBe 1500L
            segments.zipWithNext().forEach { (x, y) -> x.endFrame shouldBe y.startFrame }
            segments.sumOf { it.frames } shouldBe 1500L
        }

        test("frame rounding is absolute: many odd cut points never drift from the total") {
            val tracks = (0 until 40).map { track("t$it", it * 1.337, it * 1.337 + 3.1) }
            val segments = build(tracks, total = 100.0)
            segments.sumOf { it.frames } shouldBe 3000L
            segments.last().endFrame shouldBe 3000L
        }

        test("a person who leaves is gone from the layout, a person who joins late does not squeeze the beginning") {
            val segments = build(listOf(track("a", 0.0, 60.0), track("b", 20.0, 40.0)), total = 60.0)
            segments.map { it.cameraSlots.keys } shouldBe listOf(setOf("a"), setOf("a", "b"), setOf("a"))
            segments.map { it.shape } shouldBe
                listOf(GridShape(cols = 1, rows = 1), GridShape(cols = 2, rows = 1), GridShape(cols = 1, rows = 1))
        }

        test("ten people over time, at most six at once: never more than six cells, three by two at six") {
            val tracks =
                (0 until 10).map { i ->
                    // person i is there from i*10 to i*10 + 60 -> at most 6 overlap
                    track("p$i", i * 10.0, i * 10.0 + 60.0)
                }
            val segments = build(tracks, total = 150.0)
            segments.maxOf { it.cameraSlots.size } shouldBe 6
            segments.forEach { it.cameraSlots.size shouldBeLessThanOrEqual 6 }
            segments.first { it.cameraSlots.size == 6 }.shape shouldBe GridShape(cols = 3, rows = 2)
        }

        test("slots stay put while the shape stays the same") {
            // c joins at 10, d joins at 20: shapes 1x1, 2x1? -> a b c in 2x2, then a b c d in 2x2 -> a b c keep their slots
            val segments =
                build(
                    listOf(track("a", 0.0, 60.0), track("b", 0.0, 60.0), track("c", 10.0, 60.0), track("d", 20.0, 60.0)),
                    total = 60.0,
                )
            val three = segments.first { it.cameraSlots.size == 3 }
            val four = segments.first { it.cameraSlots.size == 4 }
            three.shape shouldBe four.shape
            listOf("a", "b", "c").forEach { three.cameraSlots.getValue(it) shouldBe four.cameraSlots.getValue(it) }
        }

        test("a screen share switches to the presentation layout with the cameras in one row, the earliest share wins") {
            val segments =
                build(
                    listOf(
                        track("cam1", 0.0, 60.0),
                        track("cam2", 0.0, 60.0),
                        track("share2", 25.0, 50.0, screen = true),
                        track("share1", 20.0, 40.0, screen = true),
                    ),
                    total = 60.0,
                )
            val during = segments.first { it.startFrame >= 20 * fps && it.endFrame <= 25 * fps }
            during.screenShareTrackId shouldBe "share1"
            during.cameraSlots.keys shouldBe setOf("cam1", "cam2")
            during.shape shouldBe GridShape(cols = 2, rows = 1)
            // while both shares run the second one is just another picture in the strip
            val both = segments.first { it.startFrame >= 25 * fps && it.endFrame <= 40 * fps }
            both.screenShareTrackId shouldBe "share1"
            both.cameraSlots.keys shouldBe setOf("cam1", "cam2", "share2")
            both.shape shouldBe GridShape(cols = 3, rows = 1)
            segments.first().screenShareTrackId shouldBe null
        }

        test("a segment of one frame is merged into its neighbour, the longer layout wins") {
            // b appears for exactly 1 frame
            val segments = build(listOf(track("a", 0.0, 10.0), track("b", 5.0, 5.0 + 1.0 / fps)), total = 10.0)
            // the cut at the end of the one-frame stretch stays (a 150 / 151 frame split), b is nowhere
            segments.size shouldBe 2
            segments.forEach { it.cameraSlots.keys shouldBe setOf("a") }
            segments.sumOf { it.frames } shouldBe 300L
        }

        test("a tiny first segment is merged into its successor") {
            val segments = build(listOf(track("a", 1.0 / fps, 10.0), track("b", 0.0, 10.0)), total = 10.0)
            segments.first().startFrame shouldBe 0L
            segments.first().frames shouldBe 300L
        }

        test("forced cuts every 120 seconds, even without any layout change") {
            val segments = build(listOf(track("a", 0.0, 400.0)), total = 400.0)
            segments.map { it.frames } shouldBe listOf(3600L, 3600L, 3600L, 1200L)
            segments.forEach { it.cameraSlots.keys shouldBe setOf("a") }
        }

        test("a camera that flaps all the time cannot produce more than the maximum number of segments") {
            val total = 7200.0
            val flapper =
                EdlVideoTrack(
                    trackId = "flap",
                    isScreenShare = false,
                    firstSeenSeconds = 0.0,
                    // 0.1 s on, 0.1 s off for two hours: 36000 ranges
                    ranges = (0 until 36_000).map { TimeRange(startSeconds = it * 0.2, endSeconds = it * 0.2 + 0.1) },
                )
            val segments = build(listOf(flapper), total = total)
            segments.size shouldBeLessThanOrEqual RecordingEditDecisionList.MAX_SEGMENTS
            segments.sumOf { it.frames } shouldBe (total * fps).toLong()
            segments.zipWithNext().forEach { (x, y) -> x.endFrame shouldBe y.startFrame }
        }

        test("a gap in the video data of a track makes it invisible there (a muted camera is not an ended track)") {
            val camera =
                EdlVideoTrack(
                    trackId = "cam",
                    isScreenShare = false,
                    firstSeenSeconds = 0.0,
                    ranges = listOf(TimeRange(startSeconds = 0.0, endSeconds = 20.0), TimeRange(startSeconds = 50.0, endSeconds = 60.0)),
                )
            val segments = build(listOf(camera, track("other", 0.0, 60.0)), total = 60.0)
            segments.first { it.startFrame >= 20 * fps && it.endFrame <= 50 * fps }.cameraSlots.keys shouldBe setOf("other")
            segments.first { it.startFrame >= 50 * fps }.cameraSlots.keys shouldBe setOf("cam", "other")
        }

        test("nobody visible: an empty 1x1 segment, no cells") {
            val segments = build(listOf(track("a", 30.0, 40.0)), total = 50.0)
            val first = segments.first()
            first.cameraSlots.keys.shouldBeEmpty()
            first.screenShareTrackId shouldBe null
            first.shape shouldBe GridShape(cols = 1, rows = 1)
        }

        test("invalid input is rejected") {
            runCatching {
                RecordingEditDecisionList.build(tracks = emptyList(), totalSeconds = 0.0, fps = fps, canvasW = 1280, canvasH = 720)
            }.isFailure shouldBe
                true
            runCatching {
                RecordingEditDecisionList.build(tracks = emptyList(), totalSeconds = 10.0, fps = 0, canvasW = 1280, canvasH = 720)
            }.isFailure shouldBe
                true
        }
    })
