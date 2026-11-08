package network.lapis.cloud.server.conference

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files

/**
 * V1.9.294 -- the single-image layout on a REAL `ffmpeg`: the composition of what the poller hands over after
 * [filterForSingleParticipant] (the other person's camera is already gone from the video inputs, all audio is kept).
 *
 * A is red, B is blue, a screen share is green. Chosen person: B. Expected: B fills the frame; with a screen share the share is large and only B
 * sits in the strip; a chosen person without a camera and without a share gives a black picture of the correct length with the audio present.
 *
 * Skips itself without a full `ffmpeg` (see [FfmpegForTests.locate]).
 */
class RecordingSingleParticipantLayoutIntegrationTest :
    FunSpec({
        val ffmpeg = FfmpegForTests.locate()
        val enabled = ffmpeg != null
        val workDir: File by lazy { Files.createTempDirectory("lapis-single-layout").toFile() }
        val width = 640
        val height = 360
        val fps = 30
        val total = 6.0

        afterSpec { if (enabled) workDir.deleteRecursively() }

        fun mic(name: String): File {
            val out = File(workDir, "$name.ogg")
            FfmpegForTests.run(
                ffmpeg = ffmpeg!!,
                args =
                    listOf(
                        "-y",
                        "-f",
                        "lavfi",
                        "-i",
                        "aevalsrc='0.5*sin(2*PI*1000*t)':s=48000:c=mono:d=$total",
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

        fun streamCount(
            video: File,
            kind: String,
        ): Int {
            val ffprobe = FfprobeMediaProber.deriveFfprobePath(ffmpeg!!)
            val process =
                ProcessBuilder(
                    ffprobe,
                    "-v",
                    "error",
                    "-select_streams",
                    kind,
                    "-show_entries",
                    "stream=codec_type",
                    "-of",
                    "csv=p=0",
                    video.absolutePath,
                ).redirectError(ProcessBuilder.Redirect.DISCARD).start()
            val lines =
                process.inputStream
                    .bufferedReader()
                    .readLines()
                    .filter { it.isNotBlank() }
            process.waitFor()
            return lines.size
        }

        fun isRed(c: Triple<Int, Int, Int>) = c.first > 150 && c.third < 100

        fun isBlue(c: Triple<Int, Int, Int>) = c.third > 150 && c.first < 100

        fun isGreen(c: Triple<Int, Int, Int>) = c.second > 120 && c.first < 100 && c.third < 100

        fun isDark(c: Triple<Int, Int, Int>) = c.first < 40 && c.second < 40 && c.third < 40

        fun compose(
            name: String,
            videoInputs: List<RecordingComposeVideoInput>,
            audio: List<File>,
        ): File {
            val spec =
                RecordingComposeSpec(
                    videoInputs = videoInputs,
                    audioInputs = audio.map { RecordingComposeAudioInput(file = it, offsetSeconds = 0.0) },
                    outputDurationSeconds = total,
                    outputWidth = width,
                    outputHeight = height,
                    frameRate = fps,
                    layout = RecordingLayout.SINGLE_PARTICIPANT,
                )
            val out = File(workDir, "$name.mp4")
            val composer =
                FfmpegSegmentedComposer(
                    ffmpegPath = ffmpeg!!,
                    timeoutMinutes = 10,
                    prober = FfprobeMediaProber(ffprobePath = FfprobeMediaProber.deriveFfprobePath(ffmpeg)),
                )
            runBlocking { composer.compose(spec = spec, outputFile = out) }
            return out
        }

        fun video(
            file: File,
            id: String,
            share: Boolean = false,
        ) = RecordingComposeVideoInput(file = file, offsetSeconds = 0.0, isScreenShare = share, trackId = id)

        test("the chosen person fills the frame; the other person (filtered out by the poller) is nowhere, all audio is there") {
            if (!enabled) return@test
            val out = compose("chosen", listOf(video(cam("cam-b", "blue"), "tb")), listOf(mic("mic-a"), mic("mic-b")))
            val center = pixelPerFrame(out, x = width / 2, y = height / 2)
            center shouldHaveSize (total * fps).toInt()
            center.all { isBlue(it) } shouldBe true
            // the whole frame, not only a tile: the corners are blue as well
            pixelPerFrame(out, x = 10, y = 10).all { isBlue(it) } shouldBe true
            pixelPerFrame(out, x = width - 12, y = height - 12).none { isRed(it) } shouldBe true
            streamCount(out, "a") shouldBe 1
        }

        test("with a screen share the share is large and only the chosen person sits in the strip") {
            if (!enabled) return@test
            val out =
                compose(
                    "share",
                    listOf(video(cam("cam-b2", "blue"), "tb"), video(cam("share", "green"), "ts", share = true)),
                    listOf(mic("mic-s")),
                )
            val main = pixelPerFrame(out, x = width / 2, y = 144)
            main.all { isGreen(it) } shouldBe true
            val strip = pixelPerFrame(out, x = width / 2, y = 288 + 36)
            strip.all { isBlue(it) } shouldBe true
            streamCount(out, "a") shouldBe 1
        }

        test("a chosen person without a camera and without a share: a black picture of the correct length, the audio present") {
            if (!enabled) return@test
            val out = compose("nocam", emptyList(), listOf(mic("mic-n")))
            val center = pixelPerFrame(out, x = width / 2, y = height / 2)
            center shouldHaveSize (total * fps).toInt()
            center.all { isDark(it) } shouldBe true
            streamCount(out, "v") shouldBe 1
            streamCount(out, "a") shouldBe 1
        }

        test("the guard still refuses an empty spec for GRID and SPEAKER") {
            if (!enabled) return@test
            listOf(RecordingLayout.GRID, RecordingLayout.SPEAKER).forEach { layout ->
                val spec =
                    RecordingComposeSpec(
                        videoInputs = emptyList(),
                        audioInputs = emptyList(),
                        outputDurationSeconds = total,
                        layout = layout,
                    )
                val composer = FfmpegSegmentedComposer(ffmpegPath = ffmpeg!!, timeoutMinutes = 1)
                val failure = runCatching { runBlocking { composer.compose(spec = spec, outputFile = File(workDir, "never.mp4")) } }
                (failure.exceptionOrNull() is RecordingComposeException) shouldBe true
            }
        }
    })
