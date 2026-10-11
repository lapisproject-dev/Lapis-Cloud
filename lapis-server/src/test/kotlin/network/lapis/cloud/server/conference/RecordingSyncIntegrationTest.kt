package network.lapis.cloud.server.conference

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files
import kotlin.math.abs
import kotlin.math.roundToLong
import kotlin.time.Duration.Companion.minutes

/**
 * V1.9.109 -- the A/V SYNC proof of the segmented composition on a real `ffmpeg`, with inputs that look like what LiveKit's track egress
 * writes (a WebM/VP8 camera, an OGG/Opus microphone, each with its own timeline).
 *
 * **Setup.** One microphone (a 40 ms 1 kHz beep every 10 s of WALL time), one camera "B" (a flat grey picture with ONE white frame every
 * 10 s of wall time, starting 0.3 s into the recording) and further cameras that join every 45 s and leave 45 s later -- so the layout
 * changes all the time and the 120 s forced cuts also fall into the middle of things. The beep and the flash of one pair are the
 * SAME wall-clock event, placed on the timeline through two different files with two different offsets.
 *
 * **Measurement** (on the composed MP4, never on the inputs): a flash is a frame whose mean luma is clearly above BOTH neighbours
 * (`signalstats`: a layout change is a step, not a spike); a beep is the end of a stretch of silence (`silencedetect`). Every flash and
 * every beep must lie within 150 ms of its nominal time, and each flash/beep pair within 150 ms of each other; the file has exactly the
 * expected number of frames and its duration is right to within a second.
 *
 * **Cases** (the same pipeline, each with one defect the old single-graph composition could not survive):
 * - (a) the microphone's media is 0.5 % shorter than the wall clock (a drifting sender clock, ratio 0.995 -- the plan's 0.25 % is below
 *   the 1.0 s difference that `AudioTempo` requires for a 6 minute recording, so 0.5 % is what actually exercises `atempo`): without
 *   `atempo` the last beep of a 6 minute meeting would be 1.8 s early;
 * - (b) the camera lost every 20th frame (PTS gaps of 66 ms, the flash frames are never among them);
 * - (c) the microphone has a 20 s hole in its own timeline (a muted stretch, longer than the 10 s after which ffmpeg's OGG demuxer would close it
 *   by itself): the beeps inside it do not exist, the later ones must still be on time.
 *
 * **Length**: `LAPIS_RECORDING_SYNC_TEST_MINUTES` (default 6; the 70 minute proof is run by hand, see `docs/architecture/conference-recording.adoc`),
 * working directory `LAPIS_RECORDING_SYNC_TEST_DIR` (default a temp directory). The canvas is 640x360 to keep the run time small.
 *
 * Skips itself without a full `ffmpeg` (`libx264`, `aac`, `libvpx`, `libopus`) -- see [FfmpegForTests.locate].
 */
class RecordingSyncIntegrationTest :
    FunSpec({
        val ffmpeg = FfmpegForTests.locate()
        val enabled = ffmpeg != null
        val syncMinutes = System.getenv("LAPIS_RECORDING_SYNC_TEST_MINUTES")?.toIntOrNull()?.coerceIn(3, 180) ?: 6
        val total = syncMinutes * 60.0
        val fps = 30
        val cameraOffset = 0.3
        val workDir: File by lazy {
            System
                .getenv("LAPIS_RECORDING_SYNC_TEST_DIR")
                ?.takeIf { it.isNotBlank() }
                ?.let { File(it).also { dir -> dir.mkdirs() } }
                ?: Files.createTempDirectory("lapis-recording-sync").toFile()
        }
        // LAPIS_RECORDING_SYNC_TEST_CASES=base,drift,drops,gap (default: all) -- the 70 minute run is usually just "base"
        val cases =
            System
                .getenv("LAPIS_RECORDING_SYNC_TEST_CASES")
                ?.split(',')
                ?.map { it.trim() }
                ?.filter { it.isNotEmpty() }

        fun caseEnabled(name: String) = enabled && (cases == null || name in cases)
        val keepWork = System.getenv("LAPIS_RECORDING_SYNC_TEST_KEEP") == "true"

        afterSpec {
            if (enabled && !keepWork) workDir.deleteRecursively()
        }

        val ffprobe by lazy { FfprobeMediaProber.deriveFfprobePath(ffmpeg!!) }
        val prober by lazy { FfprobeMediaProber(ffprobePath = ffprobe, timeoutSeconds = 120) }

        fun gen(
            args: List<String>,
            seconds: Double,
        ) {
            FfmpegForTests.run(
                ffmpeg = ffmpeg!!,
                args = listOf("-y", "-hide_banner", "-loglevel", "error") + args,
                timeoutSeconds =
                    600 + seconds.toLong() * 2,
            )
        }

        /** One encoded stretch of the microphone: wall seconds `[fromWall, toWall)` of the beep pattern, own timeline starting at 0. */
        fun encodeMicPart(
            file: File,
            drift: Double,
            fromWall: Double,
            toWall: Double,
        ) {
            val w = "((t+${fromWall * drift})/$drift)"
            val tone = "0.5*sin(2*PI*1000*t)*lt(mod($w,10),0.04)*gte($w,5)"
            gen(
                listOf(
                    "-f",
                    "lavfi",
                    "-i",
                    "aevalsrc='$tone':s=48000:c=mono:d=${(toWall - fromWall) * drift}",
                    "-c:a",
                    "libopus",
                    "-b:a",
                    "48k",
                    "-f",
                    "ogg",
                    file.absolutePath,
                ),
                total,
            )
        }

        /**
         * The microphone: beeps every 10 s of WALL time; [drift] < 1 shortens the media; [gap] (wall seconds) is a hole in the file's own
         * timeline -- made by joining two separately encoded parts with the `concat` demuxer, whose `duration` directive places the second
         * part [gap]-end seconds in (a REAL presentation-timestamp gap, as a muted track leaves it; dropping frames in the encoder's input
         * leaves encoder-state artefacts at the seam instead).
         */
        fun generateMic(
            name: String,
            drift: Double,
            gap: TimeRange?,
        ): File {
            val out = File(workDir, "$name-mic.ogg")
            if (gap == null) {
                encodeMicPart(out, drift, 0.0, total)
                return out
            }
            val part1 = File(workDir, "$name-mic-1.ogg")
            val part2 = File(workDir, "$name-mic-2.ogg")
            encodeMicPart(part1, drift, 0.0, gap.startSeconds)
            encodeMicPart(part2, drift, gap.endSeconds, total)
            val list = File(workDir, "$name-mic.txt")
            list.writeText("file '${part1.name}'\nduration ${gap.endSeconds * drift}\nfile '${part2.name}'\n")
            gen(listOf("-f", "concat", "-safe", "0", "-i", list.absolutePath, "-c", "copy", "-f", "ogg", out.absolutePath), total)
            return out
        }

        /**
         * Camera B: flat grey, a white frame at every full 10 s of wall time (media time = wall time - [cameraOffset]);
         * [drops] loses every 20th frame.
         */
        fun generateCameraB(
            name: String,
            drops: Boolean,
        ): File {
            val out = File(workDir, "$name-camB.webm")
            val flashAt = (cameraOffset * fps).roundToLong()
            val flash = "drawbox=x=0:y=0:w=iw:h=ih:color=white:t=fill:enable='eq(mod(n+$flashAt,${10 * fps}),0)'"
            val dropFilter = if (drops) ",select='not(eq(mod(n,20),7))'" else ""
            gen(
                listOf(
                    "-f",
                    "lavfi",
                    "-i",
                    "color=c=0x202020:s=320x180:r=$fps:d=${total - cameraOffset},$flash$dropFilter",
                    "-fps_mode",
                    "passthrough",
                    "-c:v",
                    "libvpx",
                    "-b:v",
                    "400k",
                    "-g",
                    "30",
                    "-deadline",
                    "realtime",
                    "-cpu-used",
                    "8",
                    "-auto-alt-ref",
                    "0",
                    "-f",
                    "webm",
                    out.absolutePath,
                ),
                total,
            )
            return out
        }

        fun generateCameraC(
            name: String,
            index: Int,
        ): File {
            val out = File(workDir, "$name-camC$index.webm")
            gen(
                listOf(
                    "-f",
                    "lavfi",
                    "-i",
                    "testsrc2=s=320x180:r=$fps:d=45",
                    "-c:v",
                    "libvpx",
                    "-b:v",
                    "300k",
                    "-g",
                    "30",
                    "-deadline",
                    "realtime",
                    "-cpu-used",
                    "8",
                    "-auto-alt-ref",
                    "0",
                    "-f",
                    "webm",
                    out.absolutePath,
                ),
                45.0,
            )
            return out
        }

        fun measureFlashes(video: File): List<Double> {
            val stats = File(workDir, "${video.nameWithoutExtension}-yavg.txt")
            FfmpegForTests.run(
                ffmpeg = ffmpeg!!,
                args =
                    listOf(
                        "-nostats",
                        "-hide_banner",
                        "-i",
                        video.absolutePath,
                        "-vf",
                        "signalstats,metadata=print:key=lavfi.signalstats.YAVG:file=${stats.absolutePath}",
                        "-an",
                        "-f",
                        "null",
                        "-",
                    ),
                timeoutSeconds = 600 + total.toLong(),
            )
            val times = ArrayList<Double>()
            val luma = ArrayList<Double>()
            var pendingTime: Double? = null
            stats.forEachLine { line ->
                Regex("pts_time:([0-9.]+)").find(line)?.let { pendingTime = it.groupValues[1].toDouble() }
                Regex("YAVG=([0-9.]+)").find(line)?.let {
                    times += pendingTime ?: 0.0
                    luma += it.groupValues[1].toDouble()
                }
            }
            val flashes = ArrayList<Double>()
            for (i in 1 until luma.size - 1) {
                if (luma[i] - maxOf(luma[i - 1], luma[i + 1]) > SPIKE_LUMA) flashes += times[i]
            }
            return flashes
        }

        fun measureBeeps(video: File): List<Double> {
            val out =
                FfmpegForTests.run(
                    ffmpeg = ffmpeg!!,
                    args =
                        listOf(
                            "-nostats",
                            "-hide_banner",
                            "-i",
                            video.absolutePath,
                            "-vn",
                            "-af",
                            "silencedetect=n=-35dB:d=0.02",
                            "-f",
                            "null",
                            "-",
                        ),
                    timeoutSeconds = 600 + total.toLong(),
                )
            // the silence that runs to the end of the file is closed by a final `silence_end` at the stream end: not a beep
            return Regex("silence_end: ([0-9.]+)")
                .findAll(out)
                .map { it.groupValues[1].toDouble() }
                .filter { it < total - 0.25 }
                .toList()
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

        fun runCase(
            name: String,
            drift: Double = 1.0,
            drops: Boolean = false,
            micGapWall: TimeRange? = null,
        ) {
            val mic = generateMic(name, drift, micGapWall)
            val camB = generateCameraB(name, drops)
            val camCs = (0 until (total / 90.0).toInt()).map { generateCameraC(name, it) }

            val cameraRanges = runBlocking { prober.probePacketRanges(file = camB, kind = StreamKind.VIDEO, gapThresholdSeconds = 1.0) }
            val micRanges = runBlocking { prober.probePacketRanges(file = mic, kind = StreamKind.AUDIO, gapThresholdSeconds = 0.5) }
            requireNotNull(cameraRanges) { "camera ranges could not be probed" }
            requireNotNull(micRanges) { "microphone ranges could not be probed" }
            if (micGapWall != null) {
                check(micRanges.ranges.size == 2) {
                    "the generated microphone file must carry its hole as a PTS gap (two ranges), got ${micRanges.ranges}"
                }
            }
            val wallTail = if (drift < 1.0) AudioTempo.EOS_TAIL_SECONDS else 0.1
            if (drift < 1.0) {
                // the case is only meaningful if the drift is large enough for the composer to correct it
                check(AudioTempo.ratioFor(mediaSeconds = micRanges.mediaDurationSeconds, wallSeconds = total + wallTail) != null) {
                    "the drift of this case must trigger atempo"
                }
            }

            val spec =
                RecordingComposeSpec(
                    videoInputs =
                        listOf(
                            RecordingComposeVideoInput(
                                file = camB,
                                offsetSeconds = cameraOffset,
                                isScreenShare = false,
                                trackId = "B",
                                ranges = cameraRanges.ranges,
                                durationSeconds = cameraRanges.mediaDurationSeconds,
                            ),
                        ) +
                            camCs.mapIndexed { index, file ->
                                RecordingComposeVideoInput(
                                    file = file,
                                    offsetSeconds = 45.0 * (2 * index + 1) + 0.1,
                                    isScreenShare = false,
                                    trackId = "C$index",
                                    durationSeconds = 45.0,
                                )
                            },
                    audioInputs =
                        listOf(
                            RecordingComposeAudioInput(
                                file = mic,
                                offsetSeconds = 0.0,
                                ranges = micRanges.ranges,
                                mediaDurationSeconds = micRanges.mediaDurationSeconds,
                                wallDurationSeconds = total + wallTail,
                            ),
                        ),
                    outputDurationSeconds = total,
                    outputWidth = 640,
                    outputHeight = 360,
                    frameRate = fps,
                )
            val out = File(workDir, "$name.mp4")
            val composer =
                FfmpegSegmentedComposer(ffmpegPath = ffmpeg!!, timeoutMinutes = 180, prober = prober)
            runBlocking { composer.compose(spec = spec, outputFile = out) }

            // ── shape of the file ──
            val frames =
                probeValue(
                    out,
                    listOf("-count_frames", "-select_streams", "v:0", "-show_entries", "stream=nb_read_frames", "-of", "csv=p=0"),
                ).toLong()
            frames shouldBe (total * fps).roundToLong()
            probeValue(out, listOf("-show_entries", "format=duration", "-of", "csv=p=0")).toDouble() shouldBe (total plusOrMinus 1.0)

            // ── the events ──
            val nominal =
                generateSequence(10.0) { it + 10.0 }
                    .takeWhile { it <= total - 2.0 }
                    .toList()
            val flashes = measureFlashes(out)
            flashes.size shouldBe nominal.size
            flashes.zip(nominal).forEach { (measured, expected) -> abs(measured - expected) shouldBeLessThan SYNC_TOLERANCE }

            val expectedBeeps = nominal.filter { micGapWall == null || it < micGapWall.startSeconds || it >= micGapWall.endSeconds }
            val beeps = measureBeeps(out)
            beeps.size shouldBe expectedBeeps.size
            beeps.zip(expectedBeeps).forEach { (measured, expected) -> abs(measured - expected) shouldBeLessThan SYNC_TOLERANCE }

            // every beep with a flash of the same wall time: the pair is within the tolerance of each other
            expectedBeeps.forEachIndexed { index, wall ->
                val flash = flashes[nominal.indexOf(wall)]
                abs(flash - beeps[index]) shouldBeLessThan SYNC_TOLERANCE
            }
        }

        test(
            "(base) a meeting of $syncMinutes minutes: every flash and every beep within 150 ms, exact frame count, 120 s forced cuts and layout changes",
        ).config(enabled = caseEnabled("base"), timeout = 180.minutes) {
            runCase(name = "base")
        }

        test("(a) microphone media 0.5 % shorter than the wall clock: atempo keeps the last beep on time")
            .config(enabled = caseEnabled("drift"), timeout = 180.minutes) {
                runCase(name = "drift", drift = 0.995)
            }

        test("(b) the camera lost every 20th frame: PTS gaps do not move the flashes")
            .config(enabled = caseEnabled("drops"), timeout = 180.minutes) {
                runCase(name = "drops", drops = true)
            }

        test(
            "(c) a 20 s hole in the microphone's own timeline: later beeps stay on time",
        ).config(enabled = caseEnabled("gap"), timeout = 180.minutes) {
            runCase(name = "gap", micGapWall = TimeRange(startSeconds = 105.0, endSeconds = 125.0))
        }
    })

private const val SYNC_TOLERANCE = 0.15

/** A flash frame's mean luma is at least this far above BOTH neighbours (a grey 32 cell of at least a quarter of the canvas turning white gives about 50). */
private const val SPIKE_LUMA = 8.0

private infix fun Double.shouldBeLessThan(limit: Double) {
    check(this < limit) { "expected $this < $limit" }
}
