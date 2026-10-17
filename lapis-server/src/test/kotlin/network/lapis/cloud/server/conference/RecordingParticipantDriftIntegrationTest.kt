package network.lapis.cloud.server.conference

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files
import kotlin.math.abs
import kotlin.math.roundToLong
import kotlin.time.Duration.Companion.minutes

/**
 * V1.9.154 -- proof of the conditional drift correction on a real `ffmpeg`. The synthetic per-person file imitates the egress DTX drift:
 * the picture runs on the wall clock, the sound is written `k` times too long (a 1 kHz beep of 40 ms sits at `t * k` in the audio
 * stream, a white frame at `t` in the picture; events at 10 s, half and `total - 10 s`). AAC 44.1 kHz as in the original.
 *
 * - (positive) with the correction every beep/flash pair of the composed file lies within 150 ms, over several segments.
 * - (Gegenprobe) the same inputs with `driftTempo = null`: the last beep is clearly late -- the test would fail without the correction.
 * - (Regel) a real file with an excess of 0.1 s, and one with shorter audio: no `driftTempo`.
 *
 * **Length**: `LAPIS_RECORDING_SYNC_TEST_MINUTES` (default 6, 5..70). Skips itself without a `ffmpeg` that has `libx264` and `aac`.
 */
class RecordingParticipantDriftIntegrationTest :
    FunSpec({
        val ffmpeg = locateFfmpegForDrift()
        val enabled = ffmpeg != null
        val total = ((System.getenv("LAPIS_RECORDING_SYNC_TEST_MINUTES")?.toIntOrNull()?.coerceIn(5, 70) ?: 6) * 60).toDouble()
        val fps = 30
        val workDir: File by lazy { Files.createTempDirectory("lapis-recording-participant-drift").toFile() }

        afterSpec {
            if (enabled) workDir.deleteRecursively()
        }

        val ffprobe by lazy { FfprobeMediaProber.deriveFfprobePath(ffmpeg!!) }
        val prober by lazy { FfprobeMediaProber(ffprobePath = ffprobe, timeoutSeconds = 120) }

        fun gen(args: List<String>) {
            FfmpegForTests.run(
                ffmpeg = ffmpeg!!,
                args = listOf("-y", "-hide_banner", "-loglevel", "error") + args,
                timeoutSeconds = 600 + total.toLong() * 2,
            )
        }

        /** A per-person MP4 of [seconds] picture length whose audio is [k] times as long; events at [events] (wall seconds). */
        fun generate(
            name: String,
            seconds: Double,
            k: Double,
            events: List<Double>,
        ): File {
            val audio = File(workDir, "$name-a.m4a")
            val video = File(workDir, "$name-v.mp4")
            val out = File(workDir, "$name.mp4")
            val beeps = events.joinToString("+") { "lt(abs(t-${it * k}),0.02)" }
            gen(
                listOf(
                    "-f",
                    "lavfi",
                    "-i",
                    "aevalsrc='0.5*sin(2*PI*1000*t)*($beeps)':s=44100:c=mono:d=${seconds * k}",
                    "-c:a",
                    "aac",
                    "-b:a",
                    "64k",
                    audio.absolutePath,
                ),
            )
            val flash =
                "drawbox=x=0:y=0:w=iw:h=ih:color=white:t=fill:enable='" +
                    events.joinToString("+") { "eq(n,${(it * fps).roundToLong()})" } + "'"
            gen(
                listOf(
                    "-f",
                    "lavfi",
                    "-i",
                    "color=c=0x202020:s=320x180:r=$fps:d=$seconds,$flash",
                    "-c:v",
                    "libx264",
                    "-preset",
                    "veryfast",
                    "-g",
                    "${2 * fps}",
                    "-keyint_min",
                    "${2 * fps}",
                    "-sc_threshold",
                    "0",
                    "-bf",
                    "0",
                    "-pix_fmt",
                    "yuv420p",
                    video.absolutePath,
                ),
            )
            gen(listOf("-i", audio.absolutePath, "-i", video.absolutePath, "-map", "0:a", "-map", "1:v", "-c", "copy", out.absolutePath))
            return out
        }

        fun probeValue(
            file: File,
            args: List<String>,
        ): String {
            val process = ProcessBuilder(listOf(ffprobe, "-v", "error") + args + file.absolutePath).redirectErrorStream(true).start()
            val text = process.inputStream.bufferedReader().use { it.readText() }
            check(process.waitFor() == 0) { "ffprobe failed: $text" }
            return text.trim()
        }

        fun inputsOf(
            file: File,
            seconds: Double,
        ): Pair<RecordingComposeVideoInput?, RecordingComposeAudioInput?> =
            runBlocking {
                val times = prober.probeStreamStartTimes(file) ?: error("stream start times could not be probed")
                val videoRanges = prober.probePacketRanges(file = file, kind = StreamKind.VIDEO, gapThresholdSeconds = 1.0)
                val audioRanges = prober.probePacketRanges(file = file, kind = StreamKind.AUDIO, gapThresholdSeconds = 0.5)
                requireNotNull(videoRanges) { "video ranges could not be probed" }
                requireNotNull(audioRanges) { "audio ranges could not be probed" }
                ParticipantFileInputs.build(
                    file = file,
                    trackId = "A",
                    fileOffsetSeconds = 0.0,
                    m =
                        ParticipantStreamMeasurements(
                            videoStartSeconds = times.videoSeconds,
                            audioStartSeconds = times.audioSeconds,
                            videoPacketRanges = videoRanges,
                            audioPacketRanges = audioRanges,
                            reportedDurationSeconds = seconds,
                        ),
                )
            }

        val drift = 1.0034
        val events = listOf(10.0, total / 2, total - 10.0)
        val driftFile by lazy { generate(name = "drift", seconds = total, k = drift, events = events) }

        fun compose(
            video: RecordingComposeVideoInput,
            audio: RecordingComposeAudioInput,
            name: String,
        ): File {
            val spec =
                RecordingComposeSpec(
                    videoInputs = listOf(video),
                    audioInputs = listOf(audio),
                    outputDurationSeconds = total,
                    outputWidth = 640,
                    outputHeight = 360,
                    frameRate = fps,
                )
            val out = File(workDir, "$name.mp4")
            runBlocking {
                FfmpegSegmentedComposer(ffmpegPath = ffmpeg!!, timeoutMinutes = 60, prober = prober).compose(spec = spec, outputFile = out)
            }
            return out
        }

        test("(fixture + inputs) the fixture has the intended lengths and the measured driftTempo is the built-in factor")
            .config(enabled = enabled, timeout = 60.minutes) {
                probeValue(driftFile, listOf("-select_streams", "a:0", "-show_entries", "stream=duration", "-of", "csv=p=0"))
                    .toDouble() shouldBe (total * drift plusOrMinus 0.05)
                probeValue(driftFile, listOf("-select_streams", "v:0", "-show_entries", "stream=duration", "-of", "csv=p=0"))
                    .toDouble() shouldBe (total plusOrMinus 0.05)
                val (_, audio) = inputsOf(driftFile, total)
                audio.shouldNotBeNull().driftTempo.shouldNotBeNull() shouldBe (drift plusOrMinus 2e-4)
            }

        test("(positiv) with the correction every beep/flash pair is within 150 ms, over several segments, exact frame count")
            .config(enabled = enabled, timeout = 60.minutes) {
                val (video, audio) = inputsOf(driftFile, total)
                val out = compose(video!!, audio!!, "corrected")
                probeValue(
                    out,
                    listOf("-count_frames", "-select_streams", "v:0", "-show_entries", "stream=nb_read_frames", "-of", "csv=p=0"),
                ).toLong() shouldBe (total * fps).roundToLong()
                val flashes =
                    AvEventMeasurement.flashes(ffmpeg = ffmpeg!!, video = out, workDir = workDir, totalSeconds = total).filter {
                        it >
                            5.0
                    }
                val beeps = AvEventMeasurement.beeps(ffmpeg = ffmpeg, video = out, totalSeconds = total)
                flashes.size shouldBe events.size
                beeps.size shouldBe events.size
                events.indices.forEach { i ->
                    check(abs(flashes[i] - events[i]) < 0.15) { "flash ${flashes[i]} vs ${events[i]}" }
                    check(abs(beeps[i] - events[i]) < 0.15) { "beep ${beeps[i]} vs ${events[i]}" }
                    check(abs(flashes[i] - beeps[i]) < 0.15) { "flash ${flashes[i]} vs beep ${beeps[i]}" }
                }
            }

        test("(Gegenprobe) without the correction the last beep is clearly behind its flash -- the test would fail without the fix")
            .config(enabled = enabled, timeout = 60.minutes) {
                val (video, audio) = inputsOf(driftFile, total)
                val out = compose(video!!, audio!!.copy(driftTempo = null), "uncorrected")
                val flashes =
                    AvEventMeasurement.flashes(ffmpeg = ffmpeg!!, video = out, workDir = workDir, totalSeconds = total).filter {
                        it >
                            5.0
                    }
                val beeps = AvEventMeasurement.beeps(ffmpeg = ffmpeg, video = out, totalSeconds = total)
                flashes.size shouldBe events.size
                beeps.size shouldBe events.size
                check(beeps.last() - flashes.last() > 0.8 * total * (drift - 1.0)) {
                    "last beep ${beeps.last()} vs flash ${flashes.last()}: the drift was not visible"
                }
            }

        test("(Regel) a real file with an excess of only 0.1 s, and one with shorter audio, get no driftTempo")
            .config(enabled = enabled, timeout = 60.minutes) {
                val shortSeconds = 60.0
                val small = generate(name = "small", seconds = shortSeconds, k = 1.0 + 0.1 / shortSeconds, events = listOf(10.0))
                inputsOf(small, shortSeconds)
                    .second
                    .shouldNotBeNull()
                    .driftTempo
                    .shouldBeNull()
                val shorter = generate(name = "shorter", seconds = shortSeconds, k = 0.999, events = listOf(10.0))
                inputsOf(shorter, shortSeconds)
                    .second
                    .shouldNotBeNull()
                    .driftTempo
                    .shouldBeNull()
            }
    })

private fun locateFfmpegForDrift(): String? {
    val candidate = System.getenv("LAPIS_TEST_FFMPEG_PATH")?.takeIf { it.isNotBlank() } ?: "ffmpeg"
    val encoders =
        try {
            FfmpegForTests.run(ffmpeg = candidate, args = listOf("-hide_banner", "-encoders"))
        } catch (_: Exception) {
            return null
        }
    val hasEncoder = { name: String -> Regex("""^\s*\S+\s+$name\s""", RegexOption.MULTILINE).containsMatchIn(encoders) }
    return candidate.takeIf { hasEncoder("libx264") && hasEncoder("aac") }
}
