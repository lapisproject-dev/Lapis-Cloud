package network.lapis.cloud.server.conference

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe

/** V1.9.115 -- the astats parser, the loudness threshold and the wall clock mapping the speaker timeline shares with the composer. */
class SpeechActivityTest :
    FunSpec({
        test("the parser reads time and level, -inf is silence, nan and garbage are skipped, CRLF is tolerated") {
            val lines =
                listOf(
                    "frame:0    pts:0       pts_time:0\r",
                    "lavfi.astats.Overall.RMS_level=-inf\r",
                    "frame:1    pts:1600    pts_time:0.1",
                    "lavfi.astats.Overall.RMS_level=-32.5",
                    "some banner line",
                    "frame:2    pts:3200    pts_time:0.2",
                    "lavfi.astats.Overall.RMS_level=nan",
                    "frame:3    pts:4800    pts_time:0.3",
                    "lavfi.astats.Overall.RMS_level=abc",
                    "lavfi.astats.Overall.RMS_level=-10",
                    "frame:4    pts:6400    pts_time:-1",
                    "lavfi.astats.Overall.RMS_level=-20",
                    "frame:5    pts:8000    pts_time:0.5",
                    "lavfi.astats.Overall.RMS_level=-200",
                )
            val series = parseAstatsOutput(lines = lines, windowSeconds = 0.1)
            series.points.map { it.timeSeconds } shouldBe listOf(0.0, 0.1, 0.5)
            series.points.map { it.levelDb } shouldBe listOf(SpeechActivity.SILENCE_DB, -32.5, SpeechActivity.SILENCE_DB)
        }

        test("the parser never fails on empty or hostile input") {
            parseAstatsOutput(lines = emptyList(), windowSeconds = 0.1).points.shouldBeEmpty()
            parseAstatsOutput(
                lines = listOf("frame:", "lavfi.astats.Overall.RMS_level=", "pts_time:1"),
                windowSeconds = 0.1,
            ).points.shouldBeEmpty()
        }

        fun series(levels: DoubleArray) =
            AudioLevelSeries(
                points = levels.mapIndexed { i, db -> AudioLevelPoint(timeSeconds = i * 0.1, levelDb = db) },
                windowSeconds = 0.1,
            )

        test("a window is speech above the noise floor plus 15 dB and above -50 dBFS") {
            // floor (10th percentile) -60 -> threshold max(-45, -50) = -45
            val s = series((DoubleArray(18) { -60.0 } + doubleArrayOf(-30.0, -44.0)))
            SpeechActivity.threshold(s) shouldBe -45.0
            SpeechActivity.detect(series = s, offsetSeconds = 0.0, tempo = null).map { it.levelDb } shouldBe listOf(-30.0, -44.0)
        }

        test("a file that is quiet throughout contains no speech and the absolute minimum protects a very quiet room") {
            SpeechActivity.detect(series = series(DoubleArray(20) { -70.0 }), offsetSeconds = 0.0, tempo = null).shouldBeEmpty()
            // a floor of -90: floor + 15 = -75, but -50 dBFS is the minimum -> -55 is not speech
            val s = series((DoubleArray(18) { -90.0 } + doubleArrayOf(-55.0, -40.0)))
            SpeechActivity.detect(series = s, offsetSeconds = 0.0, tempo = null).map { it.levelDb } shouldBe listOf(-40.0)
            SpeechActivity.threshold(AudioLevelSeries(points = emptyList(), windowSeconds = 0.1)) shouldBe Double.POSITIVE_INFINITY
        }

        test("offset and tempo are applied exactly as the composer places audio on the wall clock") {
            val s =
                AudioLevelSeries(
                    points =
                        listOf(
                            AudioLevelPoint(timeSeconds = 10.0, levelDb = -20.0),
                            AudioLevelPoint(timeSeconds = 0.0, levelDb = -90.0),
                        ),
                    windowSeconds = 0.1,
                )
            val tempo = 0.99
            val window = SpeechActivity.detect(series = s, offsetSeconds = 2.5, tempo = tempo).single()
            window.range.startSeconds shouldBe
                (AudioWallClock.toWallSeconds(offsetSeconds = 2.5, mediaSeconds = 10.0, tempo = tempo) plusOrMinus 1e-12)
            window.range.endSeconds shouldBe
                (AudioWallClock.toWallSeconds(offsetSeconds = 2.5, mediaSeconds = 10.1, tempo = tempo) plusOrMinus 1e-12)
            AudioWallClock.toWallSeconds(offsetSeconds = 1.0, mediaSeconds = 10.0, tempo = null) shouldBe 11.0
        }

        test("a drift tempo above 1 maps media time 471 s to wall time 469.43 s") {
            AudioWallClock.toWallSeconds(offsetSeconds = 0.0, mediaSeconds = 471.0, tempo = 1.00335) shouldBe (469.43 plusOrMinus 0.01)
        }
    })
