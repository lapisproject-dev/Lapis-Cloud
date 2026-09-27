package network.lapis.cloud.server.conference

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit

/**
 * A/V-sync round 3 (2026-09-27) -- runs the REAL `ffmpeg` binary against the argument list
 * [FfmpegArgumentBuilder.build] produces and measures, in the composed MP4, where audio actually
 * starts to sound and where video actually starts to show. [FfmpegArgumentBuilderTest] can only pin
 * the filter-graph STRING; it cannot tell whether `aresample=async=1:first_pts=0` pads silence up
 * to the offset (correct, what ffmpeg does) or snaps the first frame to pts 0 and swallows the
 * offset (the round-3 suspicion) -- only a real run can, which is what this test exists for.
 *
 * **Skips itself** (`enabled = false`) when no usable `ffmpeg` is on the `PATH` (or at
 * `LAPIS_TEST_FFMPEG_PATH`) or its build lacks the `libx264`/`aac` encoders the production
 * argument list requires. GitHub's `ubuntu-latest` runner ships a full ffmpeg, so CI runs it; a
 * developer machine without ffmpeg still gets a green `./gradlew clean check`.
 *
 * Inputs are synthetic `lavfi` sources with a hard, measurable event at t = 2.0 s in the file's
 * OWN timeline: the microphone is silent until 2.0 s then a 1 kHz tone; the camera is black until
 * 2.0 s then white. Composed with a per-track `offsetSeconds`, that event must appear in the output
 * at exactly `2.0 + offset` on BOTH chains -- measured with ffmpeg's own `silencedetect` /
 * `blackdetect`. Tolerance: one output frame (30 fps -> 33 ms) for video, the AAC priming/edit-list
 * rounding (~5 ms) for audio.
 */
class FfmpegCompositionIntegrationTest :
    FunSpec({
        val ffmpeg = FfmpegForTests.locate()
        val enabled = ffmpeg != null
        val workDir: File by lazy { Files.createTempDirectory("lapis-ffmpeg-avsync").toFile() }

        afterSpec {
            if (enabled) workDir.deleteRecursively()
        }

        fun generateInputs(): Pair<File, File> {
            val mic = File(workDir, "mic.wav")
            val cam = File(workDir, "cam.mp4")
            FfmpegForTests.run(
                ffmpeg = ffmpeg!!,
                args =
                    listOf(
                        "-y",
                        "-f",
                        "lavfi",
                        "-i",
                        "sine=frequency=1000:sample_rate=48000:duration=6,volume=0:enable='lt(t,2)'",
                        "-c:a",
                        "pcm_s16le",
                        mic.absolutePath,
                    ),
            )
            FfmpegForTests.run(
                ffmpeg = ffmpeg,
                args =
                    listOf(
                        "-y",
                        "-f",
                        "lavfi",
                        "-i",
                        "color=black:size=320x180:rate=12:duration=6,drawbox=c=white:t=fill:enable='gte(t,2)'",
                        "-c:v",
                        "mpeg4",
                        "-q:v",
                        "2",
                        "-pix_fmt",
                        "yuv420p",
                        cam.absolutePath,
                    ),
            )
            return mic to cam
        }

        /** Composes via the production argument builder, then returns (video event time, audio event time) in the output. */
        fun composeAndMeasure(
            videoOffset: Double,
            audioOffset: Double,
            name: String,
        ): Pair<Double, Double> {
            val (mic, cam) = generateInputs()
            val out = File(workDir, "$name.mp4")
            val spec =
                RecordingComposeSpec(
                    videoInputs = listOf(RecordingComposeVideoInput(file = cam, offsetSeconds = videoOffset, isScreenShare = false)),
                    audioInputs = listOf(RecordingComposeAudioInput(file = mic, offsetSeconds = audioOffset)),
                    outputDurationSeconds = 7.0,
                )
            FfmpegForTests.run(ffmpeg = ffmpeg!!, args = FfmpegArgumentBuilder.build(spec = spec, outputPath = out.absolutePath))

            val blackdetect =
                FfmpegForTests.run(
                    ffmpeg = ffmpeg,
                    args = listOf("-nostats", "-i", out.absolutePath, "-vf", "blackdetect=d=0:pix_th=0.02", "-an", "-f", "null", "-"),
                )
            val silencedetect =
                FfmpegForTests.run(
                    ffmpeg = ffmpeg,
                    args = listOf("-nostats", "-i", out.absolutePath, "-af", "silencedetect=n=-40dB:d=0.02", "-vn", "-f", "null", "-"),
                )
            val videoEvent = FfmpegForTests.firstNumberAfter(marker = "black_end:", output = blackdetect)
            val audioEvent = FfmpegForTests.firstNumberAfter(marker = "silence_end: ", output = silencedetect)
            requireNotNull(videoEvent) { "blackdetect reported no black_end -- video never turned white in $name" }
            requireNotNull(audioEvent) { "silencedetect reported no silence_end -- audio never started in $name" }
            return videoEvent to audioEvent
        }

        val videoTolerance = 0.05 // one 30 fps output frame plus rounding
        val audioTolerance = 0.03 // AAC priming / edit-list rounding

        test("video offset 0.5 s, audio offset 0 -> video event at 2.5 s, audio event at 2.0 s").config(enabled = enabled) {
            val (video, audio) = composeAndMeasure(videoOffset = 0.5, audioOffset = 0.0, name = "v05-a00")
            video shouldBe (2.5 plusOrMinus videoTolerance)
            audio shouldBe (2.0 plusOrMinus audioTolerance)
        }

        test("audio offset 0.5 s, video offset 0 -> audio event at 2.5 s (aresample first_pts=0 must NOT swallow it), video at 2.0 s")
            .config(enabled = enabled) {
                val (video, audio) = composeAndMeasure(videoOffset = 0.0, audioOffset = 0.5, name = "v00-a05")
                video shouldBe (2.0 plusOrMinus videoTolerance)
                audio shouldBe (2.5 plusOrMinus audioTolerance)
            }

        test("the real ELB numbers (camera +0.055 s, microphone 0) -> both events within one frame of each other")
            .config(enabled = enabled) {
                val (video, audio) = composeAndMeasure(videoOffset = 0.055, audioOffset = 0.0, name = "elb")
                video shouldBe (2.055 plusOrMinus videoTolerance)
                audio shouldBe (2.0 plusOrMinus audioTolerance)
            }
    })

/** Locates and runs a real `ffmpeg` for integration tests -- see [FfmpegCompositionIntegrationTest]. */
internal object FfmpegForTests {
    /**
     * `LAPIS_TEST_FFMPEG_PATH` if set, else `ffmpeg` on the `PATH`; `null` when the binary cannot be
     * started or its build lacks `libx264`/`aac` (both required by [FfmpegArgumentBuilder.build]).
     */
    fun locate(): String? {
        val candidate = System.getenv("LAPIS_TEST_FFMPEG_PATH")?.takeIf { it.isNotBlank() } ?: "ffmpeg"
        val encoders =
            try {
                run(ffmpeg = candidate, args = listOf("-hide_banner", "-encoders"))
            } catch (_: Exception) {
                return null
            }
        val hasEncoder = { name: String -> Regex("""^\s*\S+\s+$name\s""", RegexOption.MULTILINE).containsMatchIn(encoders) }
        return candidate.takeIf { hasEncoder("libx264") && hasEncoder("aac") }
    }

    /** The first decimal number directly following [marker] in [output] (ffmpeg's `blackdetect`/`silencedetect` log format), or `null`. */
    fun firstNumberAfter(
        marker: String,
        output: String,
    ): Double? {
        val match = Regex(Regex.escape(marker) + "([0-9.]+)").find(output) ?: return null
        return match.groupValues[1].toDoubleOrNull()
    }

    /** Runs `ffmpeg` with [args], returning combined stdout+stderr; throws on non-zero exit or a 120 s timeout. */
    fun run(
        ffmpeg: String,
        args: List<String>,
    ): String {
        val process = ProcessBuilder(listOf(ffmpeg) + args).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().use { it.readText() }
        check(process.waitFor(120, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            "ffmpeg did not finish within 120 s: ${args.joinToString(" ")}"
        }
        check(process.exitValue() == 0) {
            "ffmpeg exited with ${process.exitValue()} for: ${args.joinToString(" ")}\n${output.lines().takeLast(30).joinToString("\n")}"
        }
        return output
    }
}
