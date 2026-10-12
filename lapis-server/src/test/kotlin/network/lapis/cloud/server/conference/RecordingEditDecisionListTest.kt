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

        test("a screen share switches to the presentation layout with the cameras in one row, the latest started share wins") {
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
            // while both shares run the one that started LAST (V1.9.115, the rule of the live view) is large, the other one is a picture in the strip
            val both = segments.first { it.startFrame >= 25 * fps && it.endFrame <= 40 * fps }
            both.screenShareTrackId shouldBe "share2"
            both.cameraSlots.keys shouldBe setOf("cam1", "cam2", "share1")
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

        // -- V1.9.115: the speaker layout ---------------------------------------------------------------------------------------------

        fun person(
            id: String,
            key: String,
            start: Double = 0.0,
            end: Double = 60.0,
        ) = track(id, start, end).copy(personKey = key)

        fun buildSpeaker(
            tracks: List<EdlVideoTrack>,
            spans: List<RecordingSpeakerSpan>,
            total: Double = 60.0,
        ) = RecordingEditDecisionList.build(
            tracks = tracks,
            totalSeconds = total,
            fps = fps,
            canvasW = 1280,
            canvasH = 720,
            layout = RecordingLayout.SPEAKER,
            speakerSpans = spans,
        )

        test("speaker layout: the segment boundaries are the exact frames of the span boundaries and the sums stay exact") {
            val segments =
                buildSpeaker(
                    listOf(person("ta", "A"), person("tb", "B")),
                    listOf(
                        RecordingSpeakerSpan(personKey = "A", startSeconds = 2.0, endSeconds = 10.4),
                        RecordingSpeakerSpan(personKey = "B", startSeconds = 10.4, endSeconds = 30.0),
                        RecordingSpeakerSpan(personKey = "A", startSeconds = 30.0, endSeconds = 60.0),
                    ),
                )
            val boundaries = segments.map { it.startFrame }.toSet()
            (Math.round(2.0 * fps) in boundaries) shouldBe true
            (Math.round(10.4 * fps) in boundaries) shouldBe true
            (30L * fps in boundaries) shouldBe true
            segments.sumOf { it.frames } shouldBe 1800L
            segments.zipWithNext().forEach { (x, y) -> x.endFrame shouldBe y.startFrame }
            segments.first { it.startFrame == Math.round(2.0 * fps) }.speakerTrackId shouldBe "ta"
            segments.first { it.startFrame == Math.round(10.4 * fps) }.speakerTrackId shouldBe "tb"
        }

        test("speaker layout: before the first span the grid is shown") {
            val segments =
                buildSpeaker(
                    listOf(person("ta", "A"), person("tb", "B")),
                    listOf(RecordingSpeakerSpan(personKey = "A", startSeconds = 5.0, endSeconds = 60.0)),
                )
            val first = segments.first()
            first.speakerTrackId shouldBe null
            first.cameraSlots.keys shouldBe setOf("ta", "tb")
        }

        test("speaker layout: a speaker without a picture shows the grid, a speaker with one is large") {
            // B has a camera only from 30 s on; A is the speaker until 20, B from 20
            val segments =
                buildSpeaker(
                    listOf(person("ta", "A"), person("tc", "C"), person("tb", "B", start = 30.0)),
                    listOf(
                        RecordingSpeakerSpan(personKey = "A", startSeconds = 0.5, endSeconds = 20.0),
                        RecordingSpeakerSpan(personKey = "B", startSeconds = 20.0, endSeconds = 60.0),
                    ),
                )
            val at25 = segments.first { it.startFrame <= 25 * fps && it.endFrame > 25 * fps }
            at25.speakerTrackId shouldBe null
            at25.cameraSlots.keys shouldBe setOf("ta", "tc")
            val at40 = segments.first { it.startFrame <= 40 * fps && it.endFrame > 40 * fps }
            at40.speakerTrackId shouldBe "tb"
        }

        test("speaker layout: the strip has at most five pictures, most recent speakers first, then by first appearance") {
            val tracks = (0 until 8).map { person("t$it", "P$it", start = it * 0.1) }
            val spans =
                listOf(
                    RecordingSpeakerSpan(personKey = "P3", startSeconds = 1.0, endSeconds = 10.0),
                    RecordingSpeakerSpan(personKey = "P6", startSeconds = 10.0, endSeconds = 20.0),
                    RecordingSpeakerSpan(personKey = "P1", startSeconds = 20.0, endSeconds = 40.0),
                )
            val seg = buildSpeaker(tracks, spans).first { it.startFrame <= 30 * fps && it.endFrame > 30 * fps }
            seg.speakerTrackId shouldBe "t1"
            seg.stripTrackIds.size shouldBe 5
            // P6 spoke last (until 20), then P3 (until 10); the rest by first appearance: P0, P2, P4
            seg.stripTrackIds shouldBe listOf("t6", "t3", "t0", "t2", "t4")
        }

        test("speaker layout: a screen share always wins and a single picture is the grid of one") {
            val withShare =
                buildSpeaker(
                    listOf(person("ta", "A"), person("tb", "B"), track("sh", 10.0, 30.0, screen = true)),
                    listOf(RecordingSpeakerSpan(personKey = "A", startSeconds = 0.5, endSeconds = 60.0)),
                )
            val during = withShare.first { it.startFrame <= 20 * fps && it.endFrame > 20 * fps }
            during.screenShareTrackId shouldBe "sh"
            during.speakerTrackId shouldBe null
            val alone =
                buildSpeaker(
                    listOf(person("ta", "A")),
                    listOf(RecordingSpeakerSpan(personKey = "A", startSeconds = 0.5, endSeconds = 60.0)),
                )
            alone.all { it.speakerTrackId == null } shouldBe true
        }

        test("the grid layout ignores speaker spans and RASTER stays byte-identical to before") {
            val tracks = listOf(person("ta", "A"), person("tb", "B"))
            val grid = build(tracks, total = 60.0)
            val ignored =
                RecordingEditDecisionList.build(
                    tracks = tracks,
                    totalSeconds = 60.0,
                    fps = fps,
                    canvasW = 1280,
                    canvasH = 720,
                    layout = RecordingLayout.GRID,
                    speakerSpans = listOf(RecordingSpeakerSpan(personKey = "A", startSeconds = 5.0, endSeconds = 30.0)),
                )
            ignored shouldBe grid
        }
    })
