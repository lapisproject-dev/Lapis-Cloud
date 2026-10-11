package network.lapis.cloud.server.conference

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

private val logger = KotlinLogging.logger {}

/**
 * What a probe of one raw per-track egress file yields -- both values `null` when they could not be
 * determined. Since round 2 of the A/V-sync fix (2026-09-27) this is DIAGNOSTIC information only,
 * logged by `RecordingPoller.composeOne` next to the offsets it actually applies (see
 * [RecordingTrackAlignment] KDoc for why the duration difference is not a correction).
 *
 * @property durationSeconds the container-level media duration (`format.duration`).
 * @property startTimeSeconds the file's own first timestamp (`format.start_time`). LiveKit's egress
 *   muxers (`webmmux` with `offset-to-zero=false`, `oggmux`) write ABSOLUTE pipeline running
 *   times, so a value clearly above zero is the one direct sign of media that was received (PTS
 *   starts at the first received packet) but never written -- `ffmpeg` itself re-zeros every
 *   input's container start before the composer's filter graph runs (default `-copyts` off), so
 *   exactly this amount would be lost. Logged so the next real recording shows whether that ever
 *   happens; `mp4mux` re-zeros the file itself and always reports ~0 here. NOT covered by this
 *   value: a WebM whose first packets are present but undecodable (non-keyframes before the first
 *   keyframe) -- `start_time` is 0 then; see the video chain in `FfmpegArgumentBuilder` for why
 *   that case is harmless since A/V-sync round 3.
 */
data class RecordingMediaProbe(
    val durationSeconds: Double?,
    val startTimeSeconds: Double?,
) {
    companion object {
        val EMPTY = RecordingMediaProbe(durationSeconds = null, startTimeSeconds = null)
    }
}

/** Which stream of a raw per-track file [RecordingMediaProber.probePacketRanges] reads. */
enum class StreamKind(
    val selector: String,
) {
    VIDEO("v:0"),
    AUDIO("a:0"),
}

/**
 * The stretches of a track's own media timeline in which packets actually exist (V1.9.109): [ranges] start at 0 (the first packet of the
 * stream) and are separated by every gap of at least the requested threshold; [mediaDurationSeconds] is the span from the first packet to
 * the end of the last one. A muted LiveKit track does not end -- it stops sending, so the file continues with a gap -- which is exactly
 * what this makes visible to the layout.
 */
data class PacketRanges(
    val ranges: List<TimeRange>,
    val mediaDurationSeconds: Double,
)

/** What a finished composition looks like from outside -- see [RecordingMediaProber.probeOutput]. */
data class RecordingOutputProbe(
    val hasVideo: Boolean,
    val hasAudio: Boolean,
    val durationSeconds: Double?,
)

/**
 * Pluggable boundary around measuring a raw per-track egress file with `ffprobe`. Same
 * pluggable-boundary pattern as [RecordingComposer]: the poller depends on this interface, tests use
 * an in-memory fake, and `./gradlew clean check` never needs a real `ffprobe` binary.
 */
interface RecordingMediaProber {
    /**
     * The container-level media duration of [file] in seconds, or `null` if it could not be
     * determined (binary missing, timeout, unparseable output, unreadable file). NEVER throws --
     * a failed probe must degrade to "no diagnostic" in the poller, not fail the composition.
     */
    suspend fun probeDurationSeconds(file: File): Double?

    /**
     * Duration AND start time of [file] -- see [RecordingMediaProbe]. The default derives only the
     * duration via [probeDurationSeconds] (start time `null`), so a fake or a minimal implementation
     * keeps working; [FfprobeMediaProber] answers both from one `ffprobe` call. NEVER throws.
     */
    suspend fun probe(file: File): RecordingMediaProbe =
        RecordingMediaProbe(durationSeconds = probeDurationSeconds(file), startTimeSeconds = null)

    /**
     * V1.9.109: the data ranges of the [kind] stream of [file] (see [PacketRanges]), or `null` if they could not be determined (binary
     * missing, timeout, unreadable or empty file). The caller then falls back to one range covering the reported duration. NEVER throws.
     */
    suspend fun probePacketRanges(
        file: File,
        kind: StreamKind,
        gapThresholdSeconds: Double,
    ): PacketRanges? = null

    /** V1.9.109: stream presence and duration of a finished composition, or `null` when it cannot be determined. NEVER throws. */
    suspend fun probeOutput(file: File): RecordingOutputProbe? = null

    /** Always empty -- for deployments/tests without a probe; the poller then logs `n/a` for the diagnostics. */
    object None : RecordingMediaProber {
        override suspend fun probeDurationSeconds(file: File): Double? = null

        override suspend fun probe(file: File): RecordingMediaProbe = RecordingMediaProbe.EMPTY
    }
}

/**
 * `ProcessBuilder`-backed [RecordingMediaProber] running
 * `ffprobe -v error -show_entries format=start_time,duration -of default=noprint_wrappers=1 <file>`
 * -- one `key=value` line per entry (`start_time=0.000000`, `duration=39.927313`; `ffprobe` always
 * prints with a period, locale-independent, and prints `N/A` for a value it cannot determine).
 * `ffprobe` ships in the same Debian `ffmpeg` package the server image installs (see `Dockerfile`),
 * so wherever composition works, probing does too.
 *
 * Same output-drain and timeout discipline as [FfmpegGalleryComposer]: combined stdout+stderr read
 * on the calling coroutine (a probe's output is a handful of bytes, no separate drain thread
 * needed), hard timeout with [Process.destroyForcibly], and the raw output is only ever logged via
 * `kotlin-logging`, never propagated.
 */
class FfprobeMediaProber(
    private val ffprobePath: String,
    private val timeoutSeconds: Long = DEFAULT_TIMEOUT_SECONDS,
) : RecordingMediaProber {
    override suspend fun probeDurationSeconds(file: File): Double? = probe(file).durationSeconds

    override suspend fun probe(file: File): RecordingMediaProbe =
        withContext(Dispatchers.IO) {
            val command =
                listOf(
                    ffprobePath,
                    "-v",
                    "error",
                    "-show_entries",
                    "format=start_time,duration",
                    "-of",
                    "default=noprint_wrappers=1",
                    file.absolutePath,
                )
            val process =
                try {
                    ProcessBuilder(command).redirectErrorStream(true).start()
                } catch (e: IOException) {
                    logger.warn { "ffprobe could not be started (${e::class.simpleName ?: "unknown error"}, path='$ffprobePath')" }
                    return@withContext RecordingMediaProbe.EMPTY
                }
            val output = process.inputStream.bufferedReader().use { it.readText() }
            val finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS)
            if (!finished) {
                process.destroyForcibly()
                logger.warn { "ffprobe did not finish within ${timeoutSeconds}s for ${file.name}" }
                return@withContext RecordingMediaProbe.EMPTY
            }
            if (process.exitValue() != 0) {
                logger.warn { "ffprobe exited with ${process.exitValue()} for ${file.name} -- output: ${output.trim().take(500)}" }
                return@withContext RecordingMediaProbe.EMPTY
            }
            val parsed = parseProbe(output)
            if (parsed.durationSeconds == null) {
                logger.warn { "ffprobe output for ${file.name} carried no parseable duration: '${output.trim().take(200)}'" }
            }
            parsed
        }

    /**
     * `ffprobe -select_streams <kind> -show_entries packet=pts_time,duration_time -of csv=p=0`. The output (one line per packet, a
     * 70 minute recording is a few hundred thousand lines) is PARSED AS A STREAM, never held as text; the number of lines and the run
     * time are bounded.
     */
    override suspend fun probePacketRanges(
        file: File,
        kind: StreamKind,
        gapThresholdSeconds: Double,
    ): PacketRanges? =
        withContext(Dispatchers.IO) {
            val command =
                listOf(
                    ffprobePath,
                    "-v",
                    "error",
                    "-select_streams",
                    kind.selector,
                    "-show_entries",
                    "packet=pts_time,duration_time",
                    "-of",
                    "csv=p=0",
                    file.absolutePath,
                )
            val process =
                try {
                    ProcessBuilder(command).redirectErrorStream(false).start()
                } catch (e: IOException) {
                    logger.warn { "ffprobe could not be started (${e::class.simpleName ?: "unknown error"}, path='$ffprobePath')" }
                    return@withContext null
                }
            // stderr is drained (and discarded) so the child can never block on a full pipe
            val errDrain =
                Thread { runCatching { process.errorStream.copyTo(java.io.OutputStream.nullOutputStream()) } }
                    .apply {
                        isDaemon = true
                        start()
                    }
            val collector = PacketCollector()
            // The deadline must hold even when ffprobe hangs WITHOUT writing: readLine() would block forever, so a watchdog
            // kills the process (readLine then ends with EOF/IOException) independently of the reading below.
            val watchdog = startWatchdog(process = process, seconds = PACKET_PROBE_TIMEOUT_SECONDS)
            val deadlineNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(PACKET_PROBE_TIMEOUT_SECONDS)
            var tooLong = false
            try {
                process.inputStream.bufferedReader().use { reader ->
                    while (true) {
                        if (System.nanoTime() > deadlineNanos || collector.lineCount > MAX_PACKET_LINES) {
                            tooLong = true
                            break
                        }
                        val line = reader.readLine() ?: break
                        collector.accept(line)
                    }
                }
            } catch (_: IOException) {
                tooLong = true
            } finally {
                watchdog.interrupt()
            }
            if (watchdog.timedOut) tooLong = true
            if (tooLong) process.destroyForcibly()
            val finished = tooLong || process.waitFor(PACKET_PROBE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            if (!finished) process.destroyForcibly()
            errDrain.join(2_000)
            if (tooLong || !finished || process.exitValue() != 0) {
                logger.warn {
                    "ffprobe packet scan of ${file.name} did not complete (timeout, line cap or exit ${if (finished && !tooLong) {
                        process
                            .exitValue()
                    } else {
                        -1
                    }})"
                }
                return@withContext null
            }
            collector.toRanges(gapThresholdSeconds)
        }

    /** `ffprobe -show_entries stream=codec_type:format=duration` of a finished composition. */
    override suspend fun probeOutput(file: File): RecordingOutputProbe? =
        withContext(Dispatchers.IO) {
            val command =
                listOf(
                    ffprobePath,
                    "-v",
                    "error",
                    "-show_entries",
                    "stream=codec_type:format=duration",
                    "-of",
                    "default=noprint_wrappers=1",
                    file.absolutePath,
                )
            val process =
                try {
                    ProcessBuilder(command).redirectErrorStream(true).start()
                } catch (e: IOException) {
                    logger.warn { "ffprobe could not be started (${e::class.simpleName ?: "unknown error"}, path='$ffprobePath')" }
                    return@withContext null
                }
            val output = process.inputStream.bufferedReader().use { it.readText() }
            if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                return@withContext null
            }
            if (process.exitValue() != 0) return@withContext null
            parseOutputProbe(output)
        }

    /** Daemon thread that destroys [process] after [seconds] unless interrupted first; [Watchdog.timedOut] tells which happened. */
    private class Watchdog(
        private val process: Process,
        private val seconds: Long,
    ) : Thread("ffprobe-watchdog") {
        @Volatile
        var timedOut = false

        init {
            isDaemon = true
        }

        override fun run() {
            try {
                sleep(TimeUnit.SECONDS.toMillis(seconds))
                timedOut = true
                process.destroyForcibly()
            } catch (_: InterruptedException) {
                // finished in time
            }
        }
    }

    private fun startWatchdog(
        process: Process,
        seconds: Long,
    ): Watchdog = Watchdog(process = process, seconds = seconds).also { it.start() }

    companion object {
        const val DEFAULT_TIMEOUT_SECONDS = 30L

        /** A packet scan of a 70 minute track takes seconds; this is a safety bound, not an expected time. */
        const val PACKET_PROBE_TIMEOUT_SECONDS = 120L

        /** Hard cap on the packet lines read (a 70 minute 60 fps screen share is about 250 000). */
        const val MAX_PACKET_LINES = 5_000_000

        /** Parses [probeOutput]'s `default=noprint_wrappers=1` text. */
        fun parseOutputProbe(output: String): RecordingOutputProbe {
            val lines = output.lineSequence().map { it.trim() }.toList()
            return RecordingOutputProbe(
                hasVideo = lines.any { it == "codec_type=video" },
                hasAudio = lines.any { it == "codec_type=audio" },
                durationSeconds =
                    lines
                        .firstOrNull { it.startsWith("duration=") }
                        ?.substringAfter('=')
                        ?.toDoubleOrNull()
                        ?.takeIf { it.isFinite() && it >= 0.0 },
            )
        }

        /**
         * `ffprobe` lives next to `ffmpeg` in every packaging this project targets -- derive its
         * path from the configured [ffmpegPath] (`/usr/bin/ffmpeg` -> `/usr/bin/ffprobe`, bare
         * `ffmpeg` -> bare `ffprobe`) unless the operator names it explicitly.
         */
        fun deriveFfprobePath(ffmpegPath: String): String {
            val file = File(ffmpegPath)
            val name = file.name
            val probeName = if (name.startsWith("ffmpeg")) "ffprobe" + name.removePrefix("ffmpeg") else "ffprobe"
            val parent = file.parent
            return if (parent == null) probeName else File(parent, probeName).path
        }

        /**
         * Parses `default=noprint_wrappers=1` output: `duration` must be a finite, non-negative
         * decimal (`N/A` for a broken container -> `null`); `start_time` may legitimately be a
         * small negative number (Opus pre-skip), so it is kept whenever it is finite.
         */
        fun parseProbe(output: String): RecordingMediaProbe {
            val values =
                output
                    .lineSequence()
                    .map { it.trim() }
                    .filter { '=' in it }
                    .associate { line ->
                        val key = line.substringBefore('=').trim()
                        val value = line.substringAfter('=').trim().trimEnd(',')
                        key to value.toDoubleOrNull()
                    }
            val duration = values["duration"]?.takeIf { it.isFinite() && it >= 0.0 }
            val startTime = values["start_time"]?.takeIf { it.isFinite() }
            return RecordingMediaProbe(durationSeconds = duration, startTimeSeconds = startTime)
        }
    }
}

/**
 * Streaming accumulator for the `packet=pts_time,duration_time` CSV lines of [FfprobeMediaProber.probePacketRanges]. Lines look like
 * `0.000000,0.033000`; an unparseable line (`N/A`, empty, a stray warning) is skipped. Packets arrive in decode order, so the
 * presentation times are sorted before the gaps are looked for.
 */
internal class PacketCollector {
    private var pts = DoubleArray(INITIAL_CAPACITY)
    private var durations = DoubleArray(INITIAL_CAPACITY)
    private var size = 0

    /** Every line offered, parseable or not -- the cap of the caller counts these. */
    var lineCount = 0
        private set

    fun accept(line: String) {
        lineCount++
        val comma = line.indexOf(',')
        val ptsText = (if (comma < 0) line else line.substring(0, comma)).trim()
        val durationText = if (comma < 0) "" else line.substring(comma + 1).trim().trimEnd(',')
        val p = ptsText.toDoubleOrNull() ?: return
        if (!p.isFinite()) return
        if (size == pts.size) {
            pts = pts.copyOf(size * 2)
            durations = durations.copyOf(size * 2)
        }
        pts[size] = p
        durations[size] = durationText.toDoubleOrNull()?.takeIf { it.isFinite() && it >= 0.0 } ?: Double.NaN
        size++
    }

    /**
     * The ranges relative to the first packet. A gap is a distance between two consecutive presentation times of at least
     * [gapThresholdSeconds]; a range ends at its last packet plus that packet's duration (at most the threshold; unknown durations use
     * the median spacing of the stream, 0.04 s without one).
     */
    fun toRanges(gapThresholdSeconds: Double): PacketRanges? {
        if (size == 0) return null
        val order = (0 until size).sortedBy { pts[it] }
        val first = pts[order.first()]
        val deltas =
            DoubleArray(
                order.size - 1,
            ) { pts[order[it + 1]] - pts[order[it]] }.filter { it > 0.0 && it < gapThresholdSeconds }.sorted()
        val fallbackDuration = if (deltas.isEmpty()) DEFAULT_PACKET_SECONDS else deltas[deltas.size / 2]

        fun endOf(index: Int): Double {
            val d = durations[index]
            val duration = if (d.isNaN() || d <= 0.0) fallbackDuration else minOf(d, gapThresholdSeconds)
            return pts[index] + duration
        }
        val ranges = ArrayList<TimeRange>()
        var start = pts[order.first()]
        var last = order.first()
        for (k in 1 until order.size) {
            val current = order[k]
            if (pts[current] - pts[last] >= gapThresholdSeconds) {
                ranges += TimeRange(startSeconds = start - first, endSeconds = endOf(last) - first)
                start = pts[current]
            }
            last = current
        }
        ranges += TimeRange(startSeconds = start - first, endSeconds = endOf(last) - first)
        return PacketRanges(ranges = ranges, mediaDurationSeconds = endOf(order.last()) - first)
    }

    private companion object {
        const val INITIAL_CAPACITY = 4096
        const val DEFAULT_PACKET_SECONDS = 0.04
    }
}
