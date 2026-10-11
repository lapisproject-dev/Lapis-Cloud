package network.lapis.cloud.server.conference

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import java.io.File

private val FILE = File("p-test.mp4")
private const val TRACK = "track-1"

private fun range(
    from: Double,
    to: Double,
) = TimeRange(startSeconds = from, endSeconds = to)

private fun ranges(vararg r: TimeRange) = PacketRanges(ranges = r.toList(), mediaDurationSeconds = r.maxOf { it.endSeconds })

/** V1.9.110 -- per-stream offsets, black stretches and the "no atempo" rule of a per-person file, without a process. */
class ParticipantFileInputsTest :
    FunSpec({
        test("video starting 0.40 s after the audio: video offset = file offset + 0.40, audio offset = file offset") {
            val (video, audio) =
                ParticipantFileInputs.build(
                    file = FILE,
                    trackId = TRACK,
                    fileOffsetSeconds = 12.0,
                    m =
                        ParticipantStreamMeasurements(
                            videoStartSeconds = 0.40,
                            audioStartSeconds = 0.0,
                            videoPacketRanges = ranges(range(from = 0.0, to = 60.0)),
                            audioPacketRanges = ranges(range(from = 0.0, to = 60.4)),
                        ),
                )
            video.shouldNotBeNull().offsetSeconds shouldBe (12.40 plusOrMinus 1e-9)
            audio.shouldNotBeNull().offsetSeconds shouldBe (12.0 plusOrMinus 1e-9)
        }

        test("audio starting AFTER the video: the audio gets the extra offset") {
            val (video, audio) =
                ParticipantFileInputs.build(
                    file = FILE,
                    trackId = TRACK,
                    fileOffsetSeconds = 5.0,
                    m = ParticipantStreamMeasurements(videoStartSeconds = 0.0, audioStartSeconds = 0.25),
                )
            video.shouldNotBeNull().offsetSeconds shouldBe (5.0 plusOrMinus 1e-9)
            audio.shouldNotBeNull().offsetSeconds shouldBe (5.25 plusOrMinus 1e-9)
        }

        test("only the DIFFERENCE of the start times counts: a common shift (e.g. 100 s) or a tiny negative AAC priming changes nothing") {
            val shifted =
                ParticipantFileInputs.build(
                    file = FILE,
                    trackId = TRACK,
                    fileOffsetSeconds = 3.0,
                    m = ParticipantStreamMeasurements(videoStartSeconds = 100.4, audioStartSeconds = 100.0),
                )
            shifted.first.shouldNotBeNull().offsetSeconds shouldBe (3.4 plusOrMinus 1e-9)
            shifted.second.shouldNotBeNull().offsetSeconds shouldBe (3.0 plusOrMinus 1e-9)

            val priming =
                ParticipantFileInputs.build(
                    file = FILE,
                    trackId = TRACK,
                    fileOffsetSeconds = 3.0,
                    m = ParticipantStreamMeasurements(videoStartSeconds = 0.0, audioStartSeconds = -0.021),
                )
            priming.first.shouldNotBeNull().offsetSeconds shouldBe (3.021 plusOrMinus 1e-9)
            priming.second.shouldNotBeNull().offsetSeconds shouldBe (3.0 plusOrMinus 1e-9)
        }

        test("no start time measured at all: both streams get the file offset") {
            val (video, audio) =
                ParticipantFileInputs.build(
                    file = FILE,
                    trackId = TRACK,
                    fileOffsetSeconds = 7.5,
                    m = ParticipantStreamMeasurements(),
                )
            video.shouldNotBeNull().offsetSeconds shouldBe 7.5
            audio.shouldNotBeNull().offsetSeconds shouldBe 7.5
        }

        test("only one start time measured: the unmeasured stream is treated as starting with the file") {
            val (video, audio) =
                ParticipantFileInputs.build(
                    file = FILE,
                    trackId = TRACK,
                    fileOffsetSeconds = 7.5,
                    m = ParticipantStreamMeasurements(videoStartSeconds = 0.3),
                )
            video.shouldNotBeNull().offsetSeconds shouldBe 7.5
            audio.shouldNotBeNull().offsetSeconds shouldBe 7.5
        }

        test("the audio input never carries a wall duration (no atempo), the video input the file's own track id") {
            val (video, audio) =
                ParticipantFileInputs.build(
                    file = FILE,
                    trackId = TRACK,
                    fileOffsetSeconds = 0.0,
                    m =
                        ParticipantStreamMeasurements(
                            audioPacketRanges = ranges(range(from = 0.0, to = 30.0), range(from = 40.0, to = 50.0)),
                            reportedDurationSeconds = 55.0,
                        ),
                )
            audio.shouldNotBeNull().wallDurationSeconds.shouldBeNull()
            audio.ranges shouldBe listOf(range(from = 0.0, to = 30.0), range(from = 40.0, to = 50.0))
            audio.mediaDurationSeconds shouldBe 50.0
            video.shouldNotBeNull().trackId shouldBe TRACK
            video.isScreenShare shouldBe false
            video.file shouldBe FILE
            audio.file shouldBe FILE
        }

        test("a file without a video stream yields only an audio input (the person is heard, not seen)") {
            val (video, audio) =
                ParticipantFileInputs.build(
                    file = FILE,
                    trackId = TRACK,
                    fileOffsetSeconds = 2.0,
                    m = ParticipantStreamMeasurements(hasVideo = false, audioStartSeconds = 0.0),
                )
            video.shouldBeNull()
            audio.shouldNotBeNull().offsetSeconds shouldBe 2.0
        }

        test("a file without an audio stream yields only a video input") {
            val (video, audio) =
                ParticipantFileInputs.build(
                    file = FILE,
                    trackId = TRACK,
                    fileOffsetSeconds = 2.0,
                    m = ParticipantStreamMeasurements(hasAudio = false, videoStartSeconds = 0.0),
                )
            audio.shouldBeNull()
            video.shouldNotBeNull()
        }

        test("black stretches are subtracted from the video packet ranges; audio ranges are untouched") {
            val (video, audio) =
                ParticipantFileInputs.build(
                    file = FILE,
                    trackId = TRACK,
                    fileOffsetSeconds = 0.0,
                    m =
                        ParticipantStreamMeasurements(
                            videoStartSeconds = 0.0,
                            audioStartSeconds = 0.0,
                            videoPacketRanges = ranges(range(from = 0.0, to = 100.0)),
                            audioPacketRanges = ranges(range(from = 0.0, to = 100.0)),
                            blankVideoRanges = listOf(range(from = 20.0, to = 40.0), range(from = 90.0, to = 1.0e9)),
                        ),
                )
            video.shouldNotBeNull().ranges shouldBe listOf(range(from = 0.0, to = 20.0), range(from = 40.0, to = 90.0))
            audio.shouldNotBeNull().ranges shouldBe listOf(range(from = 0.0, to = 100.0))
        }

        test("black stretches without measured packet ranges are subtracted from the whole reported duration") {
            val (video, _) =
                ParticipantFileInputs.build(
                    file = FILE,
                    trackId = TRACK,
                    fileOffsetSeconds = 0.0,
                    m =
                        ParticipantStreamMeasurements(
                            blankVideoRanges = listOf(range(from = 10.0, to = 20.0)),
                            reportedDurationSeconds = 60.0,
                        ),
                )
            video.shouldNotBeNull().ranges shouldBe listOf(range(from = 0.0, to = 10.0), range(from = 20.0, to = 60.0))
        }

        test(
            "a black stretch that ends within one keyframe interval of the end of the picture runs to the end (real egress file: black until 22.5 s of 23.5 s)",
        ) {
            val (video, _) =
                ParticipantFileInputs.build(
                    file = FILE,
                    trackId = TRACK,
                    fileOffsetSeconds = 0.0,
                    m =
                        ParticipantStreamMeasurements(
                            videoPacketRanges = ranges(range(from = 0.0, to = 23.512)),
                            blankVideoRanges = listOf(range(from = 8.512, to = 22.512)),
                        ),
                )
            video.shouldNotBeNull().ranges shouldBe listOf(range(from = 0.0, to = 8.512))
        }

        test("a black stretch that ends well before the end of the picture is left alone") {
            val (video, _) =
                ParticipantFileInputs.build(
                    file = FILE,
                    trackId = TRACK,
                    fileOffsetSeconds = 0.0,
                    m =
                        ParticipantStreamMeasurements(
                            videoPacketRanges = ranges(range(from = 0.0, to = 60.0)),
                            blankVideoRanges = listOf(range(from = 10.0, to = 50.0)),
                        ),
                )
            video.shouldNotBeNull().ranges shouldBe listOf(range(from = 0.0, to = 10.0), range(from = 50.0, to = 60.0))
        }

        test("a picture that is black throughout contributes no video input, but the voice stays") {
            val (video, audio) =
                ParticipantFileInputs.build(
                    file = FILE,
                    trackId = TRACK,
                    fileOffsetSeconds = 0.0,
                    m =
                        ParticipantStreamMeasurements(
                            videoPacketRanges = ranges(range(from = 0.0, to = 30.0)),
                            blankVideoRanges = listOf(range(from = 0.0, to = 1.0e9)),
                            audioPacketRanges = ranges(range(from = 0.0, to = 30.0)),
                        ),
                )
            video.shouldBeNull()
            audio.shouldNotBeNull()
        }

        test("an unmeasured blank detection (null) leaves the packet ranges as they are") {
            val (video, _) =
                ParticipantFileInputs.build(
                    file = FILE,
                    trackId = TRACK,
                    fileOffsetSeconds = 0.0,
                    m =
                        ParticipantStreamMeasurements(
                            videoPacketRanges = ranges(range(from = 0.0, to = 10.0), range(from = 20.0, to = 30.0)),
                            blankVideoRanges = null,
                        ),
                )
            video.shouldNotBeNull().ranges shouldBe listOf(range(from = 0.0, to = 10.0), range(from = 20.0, to = 30.0))
        }

        // ── TimeRanges.subtract ────────────────────────────────────────────

        test("subtract: nothing to remove returns the input, an empty input an empty result") {
            TimeRanges.subtract(from = listOf(range(from = 0.0, to = 10.0)), remove = emptyList()) shouldBe
                listOf(range(from = 0.0, to = 10.0))
            TimeRanges.subtract(from = emptyList(), remove = listOf(range(from = 0.0, to = 10.0))).shouldBeEmpty()
        }

        test("subtract: a cut in the middle splits, at an edge shortens, covering everything removes") {
            TimeRanges.subtract(from = listOf(range(from = 0.0, to = 10.0)), remove = listOf(range(from = 3.0, to = 4.0))) shouldBe
                listOf(range(from = 0.0, to = 3.0), range(from = 4.0, to = 10.0))
            TimeRanges.subtract(from = listOf(range(from = 0.0, to = 10.0)), remove = listOf(range(from = 0.0, to = 4.0))) shouldBe
                listOf(range(from = 4.0, to = 10.0))
            TimeRanges.subtract(from = listOf(range(from = 0.0, to = 10.0)), remove = listOf(range(from = 6.0, to = 12.0))) shouldBe
                listOf(range(from = 0.0, to = 6.0))
            TimeRanges.subtract(from = listOf(range(from = 0.0, to = 10.0)), remove = listOf(range(from = -1.0, to = 11.0))).shouldBeEmpty()
        }

        test("subtract: unsorted, overlapping cuts and several source ranges") {
            TimeRanges.subtract(
                from = listOf(range(from = 0.0, to = 10.0), range(from = 20.0, to = 30.0)),
                remove = listOf(range(from = 25.0, to = 27.0), range(from = 2.0, to = 4.0), range(from = 3.0, to = 5.0)),
            ) shouldBe
                listOf(
                    range(from = 0.0, to = 2.0),
                    range(from = 5.0, to = 10.0),
                    range(from = 20.0, to = 25.0),
                    range(from = 27.0, to = 30.0),
                )
        }

        test("subtract: cuts outside the range change nothing; slivers below the minimum are dropped") {
            TimeRanges.subtract(
                from = listOf(range(from = 5.0, to = 10.0)),
                remove = listOf(range(from = 0.0, to = 5.0), range(from = 10.0, to = 20.0)),
            ) shouldBe
                listOf(range(from = 5.0, to = 10.0))
            TimeRanges
                .subtract(
                    from = listOf(range(from = 0.0, to = 10.0)),
                    remove = listOf(range(from = 0.0004, to = 10.0)),
                    minLengthSeconds = 0.001,
                ).shouldBeEmpty()
            TimeRanges.subtract(from = listOf(range(from = 0.0, to = 10.0)), remove = listOf(range(from = 5.0, to = 5.0))) shouldBe
                listOf(range(from = 0.0, to = 10.0))
        }
    })
