package network.lapis.cloud.server.conference

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import network.lapis.cloud.shared.conference.GridShape
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit

/**
 * V1.9.155 -- a MEASURING tool, not a regression test: how often does the six-camera video segment of the recording composition hang?
 *
 * Off by default (CI never runs it). Switch on with `LAPIS_RECORDING_STRESS_RUNS=<n>` (runs per variant); optional
 * `LAPIS_RECORDING_STRESS_TIMEOUT_SECONDS` (per run, default 25) kills a run and counts it as a hang. These two variables are TEST
 * variables only, the server never reads them.
 *
 * ```
 * LAPIS_RECORDING_STRESS_RUNS=20 ./gradlew :lapis-server:test --tests '*FfmpegSegmentStressTest' --rerun --no-daemon
 * ```
 *
 * Variants (see [variants]): A the old command (inputs without `-t`), B the production command, C B with the black canvas bounded by `-t`
 * instead of `duration=`, D B with `-thread_queue_size`, E B without `eof_action=repeat`, F the audio segment with six inputs.
 * The only assertion: where A and B both finish, B decodes to exactly the same frames as A (the `-t` changes nothing visible).
 * A summary is printed and written to `build/stress/summary.txt`. `scripts/recording-segment-stress.sh` mirrors the command for use in
 * the production image -- keep the two in sync with [SegmentArgumentBuilder.buildVideoSegment].
 */
class FfmpegSegmentStressTest :
    FunSpec({
        val ffmpeg = FfmpegForTests.locate()
        val runs = System.getenv("LAPIS_RECORDING_STRESS_RUNS")?.toIntOrNull() ?: 0
        val timeoutSeconds = System.getenv("LAPIS_RECORDING_STRESS_TIMEOUT_SECONDS")?.toLongOrNull() ?: 25L
        val enabled = ffmpeg != null && runs > 0

        data class Outcome(
            val timedOut: Boolean,
            val exitCode: Int,
            val seconds: Double,
        )

        fun execute(
            args: List<String>,
            timeout: Long,
        ): Outcome {
            val started = System.nanoTime()
            val process =
                ProcessBuilder(listOf(ffmpeg!!) + args)
                    .redirectErrorStream(true)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .start()
            val finished = process.waitFor(timeout, TimeUnit.SECONDS)
            if (!finished) process.destroyForcibly().waitFor()
            return Outcome(
                timedOut = !finished,
                exitCode = if (finished) process.exitValue() else -1,
                seconds = (System.nanoTime() - started) / 1e9,
            )
        }

        fun frameHashes(file: File): String {
            val process =
                ProcessBuilder(listOf(ffmpeg!!, "-v", "error", "-i", file.absolutePath, "-f", "framemd5", "-"))
                    .redirectErrorStream(true)
                    .start()
            val text = process.inputStream.bufferedReader().readText()
            process.waitFor()
            return text.lines().filter { !it.startsWith("#") && it.isNotBlank() }.joinToString("\n") { it.split(",").last().trim() }
        }

        test("six-camera segment: hang rate per command variant").config(enabled = enabled) {
            val dir = Files.createTempDirectory("lapis-stress").toFile()
            try {
                val fps = 30
                val ids = (1..6).map { "c$it" }
                val sources =
                    ids.withIndex().associate { (i, id) ->
                        val size = if (i % 2 == 0) "1280x720" else "640x360"
                        val webm = File(dir, "$id.webm")
                        execute(
                            listOf(
                                "-y",
                                "-nostdin",
                                "-hide_banner",
                                "-loglevel",
                                "error",
                                "-f",
                                "lavfi",
                                "-i",
                                "testsrc2=size=$size:rate=$fps:duration=20",
                                "-c:v",
                                "libvpx",
                                "-b:v",
                                "300k",
                                "-deadline",
                                "realtime",
                                "-cpu-used",
                                "8",
                                webm.absolutePath,
                            ),
                            120,
                        ).exitCode shouldBe 0
                        val mkv = File(dir, "$id.mkv")
                        execute(
                            listOf(
                                "-y",
                                "-nostdin",
                                "-hide_banner",
                                "-loglevel",
                                "error",
                                "-copyts",
                                "-i",
                                webm.absolutePath,
                                "-map",
                                "0:v:0",
                                "-c",
                                "copy",
                                "-f",
                                "matroska",
                                mkv.absolutePath,
                            ),
                            120,
                        ).exitCode shouldBe 0
                        id to SegmentVideoSource(trackId = id, path = mkv.absolutePath, offsetSeconds = 0.0)
                    }
                val audioSources =
                    ids.map { id ->
                        val ogg = File(dir, "$id.ogg")
                        execute(
                            listOf(
                                "-y",
                                "-nostdin",
                                "-hide_banner",
                                "-loglevel",
                                "error",
                                "-f",
                                "lavfi",
                                "-i",
                                "sine=frequency=440:sample_rate=48000:duration=20",
                                "-c:a",
                                "libopus",
                                ogg.absolutePath,
                            ),
                            120,
                        ).exitCode shouldBe 0
                        SegmentAudioSource(
                            path = ogg.absolutePath,
                            offsetSeconds = 0.0,
                            wallRanges = listOf(TimeRange(startSeconds = 0.0, endSeconds = 20.0)),
                            tempo = null,
                        )
                    }
                // 184 frames = 6.13 s, six cameras in a 3x2 grid -- the shape of the failing production segments.
                val seg =
                    EdlSegment(
                        index = 0,
                        startFrame = 90,
                        endFrame = 274,
                        screenShareTrackId = null,
                        cameraSlots = ids.withIndex().associate { it.value to it.index },
                        shape = GridShape(cols = 3, rows = 2),
                    )
                val canvas = RecordingCanvas(width = 1280, height = 720)
                val production = { out: String ->
                    SegmentArgumentBuilder.buildVideoSegment(seg = seg, inputs = sources, canvas = canvas, fps = fps, outVideo = out)
                }

                fun withoutInputLimit(args: List<String>): List<String> {
                    val result = ArrayList<String>()
                    var i = 0
                    while (i < args.size) {
                        if (args[i] == "-t") i += 2 else result += args[i++]
                    }
                    return result
                }

                fun canvasBoundedByT(args: List<String>): List<String> {
                    val i = args.indexOfFirst { it.startsWith("color=black") }
                    val spec = args[i]
                    val duration = Regex("duration=([0-9.]+)").find(spec)!!.groupValues[1]
                    val result = args.toMutableList()
                    result[i] = spec.replace(Regex(":duration=[0-9.]+"), "")
                    result.add(i - 1, duration)
                    result.add(i - 1, "-t")
                    return result
                }

                fun withThreadQueue(args: List<String>): List<String> {
                    val result = ArrayList<String>()
                    for ((i, a) in args.withIndex()) {
                        if (a == "-i" && args[i + 1].endsWith(".mkv")) result += listOf("-thread_queue_size", "512")
                        result += a
                    }
                    return result
                }

                fun withoutEofRepeat(args: List<String>): List<String> = args.map { it.replace(":eof_action=repeat", "") }

                val variants: List<Pair<String, (String) -> List<String>>> =
                    listOf(
                        "A old (no -t)" to { out -> withoutInputLimit(production(out)) },
                        "B production (-t)" to production,
                        "C B + canvas -t" to { out -> canvasBoundedByT(production(out)) },
                        "D B + thread_queue_size" to { out -> withThreadQueue(production(out)) },
                        "E B - eof_action=repeat" to { out -> withoutEofRepeat(production(out)) },
                        "F audio, 6 inputs" to { out ->
                            SegmentArgumentBuilder.buildAudioSegment(seg = seg, sources = audioSources, fps = fps, outWav = out)
                        },
                    )

                val summary = StringBuilder()
                summary.appendLine("ffmpeg: ${File(ffmpeg!!).name}, runs per variant: $runs, per-run timeout: ${timeoutSeconds}s")
                summary.appendLine("%-26s %5s %9s %7s %10s".format("variant", "runs", "timeouts", "failed", "mean s"))
                val firstOutput = HashMap<String, File>()
                for ((name, build) in variants) {
                    var timeouts = 0
                    var failed = 0
                    var total = 0.0
                    var finishedRuns = 0
                    repeat(runs) { n ->
                        val ext = if (name.startsWith("F")) "wav" else "mp4"
                        val out = File(dir, "out-${name.first()}-$n.$ext")
                        val result = execute(build(out.absolutePath), timeoutSeconds)
                        when {
                            result.timedOut -> timeouts++
                            result.exitCode != 0 -> failed++
                            else -> {
                                total += result.seconds
                                finishedRuns++
                                firstOutput.putIfAbsent(name.first().toString(), out)
                            }
                        }
                    }
                    val mean = if (finishedRuns > 0) total / finishedRuns else Double.NaN
                    summary.appendLine("%-26s %5d %9d %7d %10.2f".format(name, runs, timeouts, failed, mean))
                }
                println(summary)
                File("build/stress").mkdirs()
                File("build/stress/summary.txt").writeText(summary.toString())

                val a = firstOutput["A"]
                val b = firstOutput["B"]
                if (a != null && b != null) {
                    // The -t must not change the picture at all.
                    frameHashes(b) shouldBe frameHashes(a)
                } else {
                    println("framemd5 comparison A/B skipped: no finished run of ${if (a == null) "A" else "B"}")
                }
            } finally {
                dir.deleteRecursively()
            }
        }
    })
