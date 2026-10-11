package network.lapis.cloud.server.conference

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import java.io.File

/** V1.9.110 -- the parser of the `blackdetect` log without an ffmpeg binary, plus the "never throws" behaviour of the detector. */
class FfmpegBlankVideoDetectorTest :
    FunSpec({
        fun parse(
            vararg lines: String,
            start: Double = 0.0,
        ) = FfmpegBlankVideoDetector.parse(lines = lines.toList(), videoStreamStartSeconds = start)

        test("normal stretches are parsed in order") {
            val ranges =
                parse(
                    "[blackdetect @ 0x6000] black_start:20.02 black_end:40.04 black_duration:20.02",
                    "[blackdetect @ 0x6000] black_start:100 black_end:104 black_duration:4",
                )
            ranges.size shouldBe 2
            ranges[0].startSeconds shouldBe (20.02 plusOrMinus 1e-9)
            ranges[0].endSeconds shouldBe (40.04 plusOrMinus 1e-9)
            ranges[1].startSeconds shouldBe 100.0
            ranges[1].endSeconds shouldBe 104.0
        }

        test(
            "the REAL blackdetect line of a muted camera in a LiveKit 1.13.0 egress file (2026-10-11) is parsed, video starting at 1.226 s",
        ) {
            val ranges =
                parse(
                    "[Parsed_blackdetect_1 @ 0xb4ac2e040] black_start:9.738333 black_end:23.738333 black_duration:14",
                    start = 1.226,
                )
            ranges.single().startSeconds shouldBe (8.512333 plusOrMinus 1e-6)
            ranges.single().endSeconds shouldBe (22.512333 plusOrMinus 1e-6)
        }

        test("a stretch without black_end runs to the open end") {
            val ranges = parse("[blackdetect @ 0x1] black_start:55.5")
            ranges.size shouldBe 1
            ranges.single().startSeconds shouldBe 55.5
            ranges.single().endSeconds shouldBe FfmpegBlankVideoDetector.OPEN_END_SECONDS
        }

        test("the start time of the video stream is subtracted from the container timestamps (never below 0)") {
            val ranges = parse("[blackdetect @ 0x1] black_start:10.4 black_end:20.4 black_duration:10", start = 0.4)
            ranges.single().startSeconds shouldBe (10.0 plusOrMinus 1e-9)
            ranges.single().endSeconds shouldBe (20.0 plusOrMinus 1e-9)
            val early = parse("[blackdetect @ 0x1] black_start:0 black_end:5 black_duration:5", start = 0.4)
            early.single().startSeconds shouldBe 0.0
            early.single().endSeconds shouldBe (4.6 plusOrMinus 1e-9)
        }

        test("a decimal comma is not accepted as a number, garbage and non-finite values are skipped, never thrown") {
            parse(
                "black_start:1,5 black_end:2,5",
                "[blackdetect] black_start:abc black_end:3",
                "[blackdetect] black_start:NaN black_end:3",
                "[blackdetect] black_start:3 black_end:inf",
                "",
                "frame=1 fps=0",
            ).shouldBeEmpty()
        }

        test("an empty or inverted stretch is dropped, output is sorted by start") {
            val ranges =
                parse(
                    "[blackdetect] black_start:50 black_end:60",
                    "[blackdetect] black_start:5 black_end:5",
                    "[blackdetect] black_start:9 black_end:7",
                    "[blackdetect] black_start:10 black_end:20",
                )
            ranges.map { it.startSeconds } shouldBe listOf(10.0, 50.0)
        }

        test("a missing binary or an unreadable file yields null instead of an exception") {
            runBlocking {
                FfmpegBlankVideoDetector(
                    ffmpegPath = "this-binary-does-not-exist-12345",
                ).detectBlankRanges(file = File("nope.mp4"), videoStreamStartSeconds = 0.0, minSeconds = 1.5) shouldBe
                    null
                RecordingBlankVideoDetector.None.detectBlankRanges(
                    file = File("nope.mp4"),
                    videoStreamStartSeconds = 0.0,
                    minSeconds = 1.5,
                ) shouldBe
                    null
            }
        }

        test("an invalid minimum length or start time is refused without starting a process") {
            runBlocking {
                val detector = FfmpegBlankVideoDetector(ffmpegPath = "true")
                detector.detectBlankRanges(file = File("nope.mp4"), videoStreamStartSeconds = 0.0, minSeconds = 0.0) shouldBe null
                detector.detectBlankRanges(file = File("nope.mp4"), videoStreamStartSeconds = 0.0, minSeconds = Double.NaN) shouldBe null
                detector.detectBlankRanges(file = File("nope.mp4"), videoStreamStartSeconds = Double.NaN, minSeconds = 1.5) shouldBe null
            }
        }
    })
