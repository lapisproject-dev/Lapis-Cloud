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
 * V1.9.110 -- the A/V SYNC proof of the Participant-Egress composition path on a real `ffmpeg`: per-person MP4 files (H.264 + AAC, audio and
 * video in ONE file, as `StartParticipantEgress` writes them) go through the REAL bricks -- [FfprobeMediaProber] (stream start times, packet
 * ranges), [FfmpegBlankVideoDetector] (camera-off stretches), [ParticipantFileInputs] (per-stream offsets) and [FfmpegSegmentedComposer].
 * The files are synthetic: NOT produced by a LiveKit egress (see the staging runbook in `docs/architecture/conference-recording.adoc`).
 *
 * **Setup.** Person A is in the whole meeting, person B joins at 45 s and leaves at 135 s. In every file the VIDEO stream starts 0.4 s after
 * the AUDIO stream (muxed with `-itsoffset`; the two start times differ, as they do in a real file). A 40 ms 1 kHz beep and ONE white frame
 * sit at every full 10 s of WALL time, in both files, so the same event is placed on the timeline by two files with two offsets. Person A's
 * picture is black from 100 s to 120 s (camera off). The 120 s forced cut and the layout changes at 45 s / 100 s / 120 s / 135 s fall
 * into the meeting.
 *
 * **Measurement** (on the composed MP4, like [RecordingSyncIntegrationTest]): flash = a frame whose mean luma is clearly above BOTH neighbours
 * (`signalstats`), beep = end of a silence (`silencedetect`).
 *
 * **Cases**
 * - (synchron) the file is internally in sync: every flash and every beep lies within 150 ms of its nominal time and every pair within
 *   150 ms of each other, over all segments. Without the per-stream offsets the picture would be 0.4 s off and this case fails.
 * - (Versatz in der Datei) the picture is deliberately generated 0.4 s LATER than the sound inside the file: the result keeps exactly that
 *   0.4 s (+-0.15 s) -- the composer neither corrects nor adds anything inside a file.
 *
 * Also checked: exact frame count, duration, the fixture's own stream start times, and that the detector finds the black stretch within the
 * keyframe accuracy (+-2.5 s of 100 s / 120 s; keyframe every 2 s).
 *
 * **Length**: `LAPIS_RECORDING_PARTICIPANT_TEST_SECONDS` (default 240, 150..3600). Skips itself without a `ffmpeg` that has `libx264` and `aac`.
 */
class RecordingParticipantSyncIntegrationTest :
    FunSpec({
        val ffmpeg = locateFfmpeg()
        val enabled = ffmpeg != null
        val total = (System.getenv("LAPIS_RECORDING_PARTICIPANT_TEST_SECONDS")?.toIntOrNull()?.coerceIn(150, 3600) ?: 240).toDouble()
        val fps = 30
        val videoLag = 0.4
        val blackStart = 100.0
        val blackEnd = 120.0
        val workDir: File by lazy { Files.createTempDirectory("lapis-recording-participant-sync").toFile() }

        afterSpec {
            if (enabled) workDir.deleteRecursively()
        }

        val ffprobe by lazy { FfprobeMediaProber.deriveFfprobePath(ffmpeg!!) }
        val prober by lazy { FfprobeMediaProber(ffprobePath = ffprobe, timeoutSeconds = 120) }
        val blankDetector by lazy { FfmpegBlankVideoDetector(ffmpegPath = ffmpeg!!, timeoutSeconds = 300) }

        fun gen(
            args: List<String>,
            seconds: Double,
        ) {
            FfmpegForTests.run(
                ffmpeg = ffmpeg!!,
                args = listOf("-y", "-hide_banner", "-loglevel", "error") + args,
                timeoutSeconds = 600 + seconds.toLong() * 2,
            )
        }

        /**
         * One person file: [fileStartWall] is the wall time of the file's first byte, [seconds] its length. Beeps at every full 10 s of wall
         * time from 10 s on. The picture is a flat grey with a white frame at every full 10 s of wall time, [skewSeconds] later (0 = in sync
         * with the beep INSIDE the file), black between [black] (wall seconds) when given. The video stream starts [videoLag] after the audio.
         */
        fun generatePersonFile(
            name: String,
            fileStartWall: Int,
            seconds: Double,
            skewSeconds: Double,
            black: TimeRange?,
        ): File {
            val audio = File(workDir, "$name-a.m4a")
            val video = File(workDir, "$name-v.mp4")
            val out = File(workDir, "$name.mp4")
            val w = "(t+$fileStartWall)"
            gen(
                listOf(
                    "-f",
                    "lavfi",
                    "-i",
                    "aevalsrc='0.5*sin(2*PI*1000*t)*lt(mod($w,10),0.04)*gte($w,5)':s=48000:c=mono:d=$seconds",
                    "-c:a",
                    "aac",
                    "-b:a",
                    "64k",
                    audio.absolutePath,
                ),
                seconds,
            )
            // frame n of the video file sits at file time videoLag + n/fps; with skew 0 it shows wall time fileStart + videoLag + n/fps (the
            // picture and the sound of one wall instant are at the SAME file time); a skew of s shows wall time fileStart + videoLag - s + n/fps
            // there, so the flash comes s seconds LATER in the file than the beep
            val frameShift = ((fileStartWall + videoLag - skewSeconds) * fps).roundToLong()
            val flash = "drawbox=x=0:y=0:w=iw:h=ih:color=white:t=fill:enable='eq(mod(n+$frameShift,${10 * fps}),0)'"
            val blackBox =
                black?.let {
                    // black wins over the flash; `t` is the video's own time, so shift from wall time
                    val from = it.startSeconds - fileStartWall - videoLag
                    val to = it.endSeconds - fileStartWall - videoLag
                    ",drawbox=x=0:y=0:w=iw:h=ih:color=black:t=fill:enable='between(t,$from,$to)'"
                } ?: ""
            gen(
                listOf(
                    "-f",
                    "lavfi",
                    "-i",
                    "color=c=0x202020:s=320x180:r=$fps:d=${seconds - videoLag},$flash$blackBox",
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
                seconds,
            )
            gen(
                listOf(
                    "-i",
                    audio.absolutePath,
                    "-itsoffset",
                    "$videoLag",
                    "-i",
                    video.absolutePath,
                    "-map",
                    "0:a",
                    "-map",
                    "1:v",
                    "-c",
                    "copy",
                    out.absolutePath,
                ),
                seconds,
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

        /** The inputs of one person file, built exactly like `RecordingPoller.measurePersonFile` + [ParticipantFileInputs]. */
        fun inputsOf(
            file: File,
            trackId: String,
            fileOffset: Double,
            seconds: Double,
        ): Triple<RecordingComposeVideoInput?, RecordingComposeAudioInput?, List<TimeRange>> =
            runBlocking {
                val times = prober.probeStreamStartTimes(file) ?: error("stream start times of ${file.name} could not be probed")
                val videoRanges = prober.probePacketRanges(file = file, kind = StreamKind.VIDEO, gapThresholdSeconds = 1.0)
                val audioRanges = prober.probePacketRanges(file = file, kind = StreamKind.AUDIO, gapThresholdSeconds = 0.5)
                val blank =
                    blankDetector.detectBlankRanges(
                        file = file,
                        videoStreamStartSeconds = times.videoSeconds ?: 0.0,
                        minSeconds = 1.5,
                    )
                requireNotNull(videoRanges) { "video ranges could not be probed" }
                requireNotNull(audioRanges) { "audio ranges could not be probed" }
                requireNotNull(blank) { "blank detection did not run" }
                // the fixture itself: the video stream starts videoLag after the audio stream
                abs((times.videoSeconds!! - times.audioSeconds!!) - videoLag) shouldBe (0.0 plusOrMinus 0.05)
                val (video, audio) =
                    ParticipantFileInputs.build(
                        file = file,
                        trackId = trackId,
                        fileOffsetSeconds = fileOffset,
                        m =
                            ParticipantStreamMeasurements(
                                videoStartSeconds = times.videoSeconds,
                                audioStartSeconds = times.audioSeconds,
                                videoPacketRanges = videoRanges,
                                audioPacketRanges = audioRanges,
                                blankVideoRanges = blank,
                                reportedDurationSeconds = seconds,
                            ),
                    )
                Triple(video, audio, blank)
            }

        fun runCase(
            name: String,
            skewSeconds: Double,
        ) {
            val secondsA = total
            val secondsB = 90.0
            val fileA =
                generatePersonFile(
                    name = "$name-A",
                    fileStartWall = 0,
                    seconds = secondsA,
                    skewSeconds = skewSeconds,
                    black = TimeRange(startSeconds = blackStart, endSeconds = blackEnd),
                )
            val fileB =
                generatePersonFile(name = "$name-B", fileStartWall = 45, seconds = secondsB, skewSeconds = skewSeconds, black = null)
            val (videoA, audioA, blankA) = inputsOf(fileA, trackId = "A", fileOffset = 0.0, seconds = secondsA)
            val (videoB, audioB, _) = inputsOf(fileB, trackId = "B", fileOffset = 45.0, seconds = secondsB)

            // the black stretch of person A is found within the keyframe accuracy (keyframe every 2 s)
            val black = blankA.single()
            check(abs(black.startSeconds - blackStart) <= 2.5 && abs(black.endSeconds - blackEnd) <= 2.5) {
                "black stretch $black is not within 2.5 s of [$blackStart, $blackEnd]"
            }
            // per-stream offsets: video = file offset + 0.4, audio = file offset
            videoA!!.offsetSeconds shouldBe (videoLag plusOrMinus 0.05)
            audioA!!.offsetSeconds shouldBe (0.0 plusOrMinus 0.05)
            videoB!!.offsetSeconds shouldBe (45.0 + videoLag plusOrMinus 0.05)
            audioB!!.offsetSeconds shouldBe (45.0 plusOrMinus 0.05)
            check(
                audioA.wallDurationSeconds == null && audioB.wallDurationSeconds == null,
            ) { "audio of a person file must not be stretched" }

            val spec =
                RecordingComposeSpec(
                    videoInputs = listOf(videoA, videoB),
                    audioInputs = listOf(audioA, audioB),
                    outputDurationSeconds = total,
                    outputWidth = 640,
                    outputHeight = 360,
                    frameRate = fps,
                )
            val out = File(workDir, "$name.mp4")
            val composer = FfmpegSegmentedComposer(ffmpegPath = ffmpeg!!, timeoutMinutes = 60, prober = prober)
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
            val nominal = generateSequence(10.0) { it + 10.0 }.takeWhile { it <= total - 2.0 }.toList()
            // (a flash in the very first frame -- the skewed file's frame 0 -- is no event of the 10 s pattern)
            val flashes = measureFlashes(out).filter { it > 5.0 }
            val beeps = measureBeeps(out)
            flashes.size shouldBe nominal.size
            beeps.size shouldBe nominal.size
            flashes.zip(nominal).forEach { (measured, expected) ->
                check(abs(measured - (expected + skewSeconds)) < TOLERANCE) {
                    "flash at $measured, expected ${expected + skewSeconds}"
                }
            }
            beeps.zip(nominal).forEach { (measured, expected) ->
                check(abs(measured - expected) < TOLERANCE) { "beep at $measured, expected $expected" }
            }
            // every beep/flash pair of the same wall time: their distance is exactly the skew built into the files
            flashes.zip(beeps).forEach { (flash, beep) ->
                check(abs((flash - beep) - skewSeconds) < TOLERANCE) { "flash $flash vs beep $beep, skew $skewSeconds" }
            }
        }

        test(
            "(synchron) per-stream offsets: every flash and every beep within 150 ms over all segments, black stretch found, exact frame count",
        ).config(enabled = enabled, timeout = 60.minutes) {
            runCase(name = "synchron", skewSeconds = 0.0)
        }

        test("(Versatz in der Datei) a 0.4 s offset INSIDE a file is kept exactly -- the composer neither corrects nor adds anything")
            .config(enabled = enabled, timeout = 60.minutes) {
                runCase(name = "versatz", skewSeconds = 0.4)
            }
    })

private const val TOLERANCE = 0.15

/** A flash frame's mean luma is at least this far above BOTH neighbours (see [RecordingSyncIntegrationTest]). */
private const val SPIKE_LUMA = 8.0

/** `LAPIS_TEST_FFMPEG_PATH` or `ffmpeg` on the PATH, if it can encode `libx264` and `aac` -- else `null` (the test then skips itself). */
private fun locateFfmpeg(): String? {
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
