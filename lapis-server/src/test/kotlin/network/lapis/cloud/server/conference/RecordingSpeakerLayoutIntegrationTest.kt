package network.lapis.cloud.server.conference

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files
import kotlin.math.abs

/**
 * V1.9.115 -- the speaker layout on a REAL `ffmpeg`, the whole chain except the poller: the loudness analysis ([FfmpegSpeechActivityAnalyzer])
 * of two synthetic microphones, the speaker timeline ([RecordingSpeakerTimeline]), and the composition ([FfmpegSegmentedComposer]) with two
 * synthetic cameras (A is red, B is blue).
 *
 * A speaks from 2 s to 10 s, B from 12 s to 20 s (a 1 kHz tone on a silent track), the recording is 24 s long. Expected (smoothing: 800 ms claim,
 * 1.5 s gap): before A's claim at 2.8 s the picture is the grid (both colours on screen), then A (red) is large, B (blue) sits in the strip; B
 * takes over at 12.8 s. The measured switch of the large tile must lie within 150 ms of "B's first sound (12.0 s) + the 800 ms claim time" --
 * the proof that the analysis clock and the composition clock agree, over the whole pipeline.
 *
 * Skips itself without a full `ffmpeg` (see [FfmpegForTests.locate]).
 */
class RecordingSpeakerLayoutIntegrationTest :
    FunSpec({
        val ffmpeg = FfmpegForTests.locate()
        val enabled = ffmpeg != null
        val workDir: File by lazy { Files.createTempDirectory("lapis-speaker-layout").toFile() }
        val width = 640
        val height = 360
        val fps = 30
        val total = 24.0

        afterSpec { if (enabled) workDir.deleteRecursively() }

        fun mic(
            name: String,
            from: Double,
            to: Double,
        ): File {
            val out = File(workDir, "$name.ogg")
            val tone = "0.5*sin(2*PI*1000*t)*gte(t,$from)*lt(t,$to)"
            FfmpegForTests.run(
                ffmpeg = ffmpeg!!,
                args =
                    listOf(
                        "-y",
                        "-f",
                        "lavfi",
                        "-i",
                        "aevalsrc='$tone':s=48000:c=mono:d=$total",
                        "-c:a",
                        "libopus",
                        "-b:a",
                        "48k",
                        "-f",
                        "ogg",
                        out.absolutePath,
                    ),
            )
            return out
        }

        fun cam(
            name: String,
            color: String,
        ): File {
            val out = File(workDir, "$name.webm")
            FfmpegForTests.run(
                ffmpeg = ffmpeg!!,
                args =
                    listOf(
                        "-y",
                        "-f",
                        "lavfi",
                        "-i",
                        "color=$color:size=320x180:rate=$fps:duration=$total",
                        "-c:v",
                        "libvpx",
                        "-b:v",
                        "300k",
                        "-pix_fmt",
                        "yuv420p",
                        out.absolutePath,
                    ),
            )
            return out
        }

        /** The RGB colour of the pixel (x, y) of every frame of [video], as a list (one entry per frame). */
        fun pixelPerFrame(
            video: File,
            x: Int,
            y: Int,
        ): List<Triple<Int, Int, Int>> {
            val process =
                ProcessBuilder(
                    ffmpeg!!,
                    "-hide_banner",
                    "-loglevel",
                    "error",
                    "-i",
                    video.absolutePath,
                    "-vf",
                    "crop=2:2:$x:$y,scale=1:1:flags=area,format=rgb24",
                    "-f",
                    "rawvideo",
                    "-",
                ).redirectError(ProcessBuilder.Redirect.DISCARD).start()
            val bytes = process.inputStream.readBytes()
            process.waitFor()
            return (0 until bytes.size / 3).map { i ->
                Triple(bytes[3 * i].toInt() and 0xff, bytes[3 * i + 1].toInt() and 0xff, bytes[3 * i + 2].toInt() and 0xff)
            }
        }

        fun isRed(c: Triple<Int, Int, Int>) = c.first > 150 && c.third < 100

        fun isBlue(c: Triple<Int, Int, Int>) = c.third > 150 && c.first < 100

        test("the analysis, the timeline and the composition agree: the large tile switches within 150 ms of the second speaker's claim") {
            if (!enabled) return@test
            val micA = mic("mic-a", 2.0, 10.0)
            val micB = mic("mic-b", 12.0, 20.0)
            val camA = cam("cam-a", "red")
            val camB = cam("cam-b", "blue")

            // 1. the analysis of the real files
            val analyzer = FfmpegSpeechActivityAnalyzer(ffmpegPath = ffmpeg!!)
            val seriesA = runBlocking { analyzer.levels(file = micA) } ?: error("analysis A failed")
            val seriesB = runBlocking { analyzer.levels(file = micB) } ?: error("analysis B failed")
            (seriesA.points.size in 235..245) shouldBe true
            val windowsA = SpeechActivity.detect(series = seriesA, offsetSeconds = 0.0, tempo = null)
            val windowsB = SpeechActivity.detect(series = seriesB, offsetSeconds = 0.0, tempo = null)
            (abs(windowsA.minOf { it.range.startSeconds } - 2.0) < 0.15) shouldBe true
            (abs(windowsB.minOf { it.range.startSeconds } - 12.0) < 0.15) shouldBe true

            // 2. the timeline
            val spans =
                RecordingSpeakerTimeline.build(
                    perPerson = mapOf("A" to windowsA, "B" to windowsB),
                    totalMs = (total * 1000).toLong(),
                )
            spans.map { it.key } shouldBe listOf("A", "B")
            val claimB = spans[1].startMs / 1000.0
            (abs(claimB - 12.8) < 0.15) shouldBe true

            // 3. the composition
            val spec =
                RecordingComposeSpec(
                    videoInputs =
                        listOf(
                            RecordingComposeVideoInput(
                                file = camA,
                                offsetSeconds = 0.0,
                                isScreenShare = false,
                                trackId = "ta",
                                personKey = "A",
                            ),
                            RecordingComposeVideoInput(
                                file = camB,
                                offsetSeconds = 0.0,
                                isScreenShare = false,
                                trackId = "tb",
                                personKey = "B",
                            ),
                        ),
                    audioInputs =
                        listOf(
                            RecordingComposeAudioInput(file = micA, offsetSeconds = 0.0),
                            RecordingComposeAudioInput(file = micB, offsetSeconds = 0.0),
                        ),
                    outputDurationSeconds = total,
                    outputWidth = width,
                    outputHeight = height,
                    frameRate = fps,
                    layout = RecordingLayout.SPEAKER,
                    speakerSpans =
                        spans.map {
                            RecordingSpeakerSpan(
                                personKey = it.key,
                                startSeconds = it.startMs / 1000.0,
                                endSeconds =
                                    it.endMs / 1000.0,
                            )
                        },
                )
            val out = File(workDir, "out.mp4")
            val composer =
                FfmpegSegmentedComposer(
                    ffmpegPath = ffmpeg,
                    timeoutMinutes = 10,
                    prober = FfprobeMediaProber(ffprobePath = FfprobeMediaProber.deriveFfprobePath(ffmpeg)),
                )
            runBlocking { composer.compose(spec = spec, outputFile = out) }

            // large tile: 80 % of the height, centred -> its centre is at (320, 144); the strip starts at y = 288, the first strip tile is centred
            val main = pixelPerFrame(out, x = width / 2, y = 144)
            main shouldHaveSize (total * fps).toInt()
            // after A's claim A (red) is large
            isRed(main[(8.0 * fps).toInt()]) shouldBe true
            isBlue(main[(18.0 * fps).toInt()]) shouldBe true
            // (before A's claim the grid of two puts the seam of the cells under this pixel, so only the frames after A's turn count)
            val switch = (11 * fps until main.size).first { isBlue(main[it]) }
            val switchSeconds = switch.toDouble() / fps
            (abs(switchSeconds - claimB) < 0.15) shouldBe true
            (abs(switchSeconds - (12.0 + 0.8)) < 0.15) shouldBe true

            // while A is large, B sits in the strip (one tile of 2 pictures: centred in the bottom band)
            val strip = pixelPerFrame(out, x = width / 2, y = 288 + 36)
            isBlue(strip[(8.0 * fps).toInt()]) shouldBe true
            isRed(strip[(18.0 * fps).toInt()]) shouldBe true
        }
    })
