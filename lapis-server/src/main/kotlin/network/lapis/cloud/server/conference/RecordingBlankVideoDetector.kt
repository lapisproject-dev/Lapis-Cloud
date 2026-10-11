package network.lapis.cloud.server.conference

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

private val logger = KotlinLogging.logger {}

/**
 * V1.9.110 -- finds the stretches in which the PICTURE of a per-person file (Participant Egress) is black, i.e. the person's camera is
 * switched off. A per-person file is one continuous video stream: unlike Track Egress, where a switched-off camera leaves a gap in the
 * packets, the egress pipeline may FILL that time with black pictures (this is the expected behaviour and is NOT verified against a
 * live egress yet -- see the staging runbook). The packet-gap detection of [RecordingMediaProber.probePacketRanges] still runs as well
 * and covers the case that the egress writes nothing; this detector covers the case that it writes black.
 *
 * Known limits (documented, not hidden): a very dark REAL picture can count as "off"; a frozen last picture is not recognised; a
 * camera that flickers for less than the minimum length is ignored.
 */
interface RecordingBlankVideoDetector {
    /**
     * The black stretches of the first video stream of [file], in that stream's OWN timeline (0 = its first packet), each at least
     * [minSeconds] long; `null` when the measurement failed (binary missing, timeout, output cap, non-zero exit). NEVER throws.
     * [videoStreamStartSeconds] is the stream's start time inside the container (`stream.start_time`), which is subtracted from the
     * container timestamps ffmpeg reports.
     */
    suspend fun detectBlankRanges(
        file: File,
        videoStreamStartSeconds: Double,
        minSeconds: Double,
    ): List<TimeRange>?

    /** Always "not measured" -- for deployments and tests without ffmpeg. */
    object None : RecordingBlankVideoDetector {
        override suspend fun detectBlankRanges(
            file: File,
            videoStreamStartSeconds: Double,
            minSeconds: Double,
        ): List<TimeRange>? = null
    }
}

/**
 * `ffmpeg`-backed [RecordingBlankVideoDetector]: `-skip_frame nokey` decodes KEYFRAMES ONLY (with the recording's 2 s keyframe interval
 * that is about one decoded 720p picture every two seconds -- a small fraction of real time), `scale=160:-2` shrinks them and
 * `blackdetect` reports the stretches. Accuracy is therefore one keyframe interval.
 *
 * The argument list is built without a shell and the filter string is a CONSTANT: the file path appears only as the `-i` argument, never
 * inside a filter (`movie=` and friends). stderr (where `blackdetect` logs) is read with a line and byte cap and a hard timeout; the
 * raw ffmpeg output never leaves this class (not into exceptions, not into DTOs, at most counts into the log).
 */
class FfmpegBlankVideoDetector(
    private val ffmpegPath: String,
    private val timeoutSeconds: Long = DEFAULT_TIMEOUT_SECONDS,
) : RecordingBlankVideoDetector {
    override suspend fun detectBlankRanges(
        file: File,
        videoStreamStartSeconds: Double,
        minSeconds: Double,
    ): List<TimeRange>? =
        withContext(Dispatchers.IO) {
            if (!minSeconds.isFinite() || minSeconds <= 0.0 || !videoStreamStartSeconds.isFinite()) return@withContext null
            val command =
                listOf(
                    ffmpegPath,
                    "-hide_banner",
                    "-nostdin",
                    "-nostats",
                    "-loglevel",
                    "info",
                    "-copyts",
                    "-skip_frame",
                    "nokey",
                    "-i",
                    file.absolutePath,
                    "-map",
                    "0:v:0",
                    "-an",
                    "-vf",
                    "scale=160:-2,blackdetect=d=${formatSeconds(minSeconds)}:pic_th=$PICTURE_THRESHOLD:pix_th=$PIXEL_THRESHOLD",
                    "-f",
                    "null",
                    "-",
                )
            val process =
                try {
                    ProcessBuilder(command).redirectErrorStream(true).start()
                } catch (e: IOException) {
                    logger.warn { "ffmpeg could not be started for the blank-picture scan (${e::class.simpleName ?: "unknown error"})" }
                    return@withContext null
                }
            // The deadline must hold even when ffmpeg hangs WITHOUT writing (readLine would block for ever): a watchdog kills it.
            val watchdog = Watchdog(process = process, seconds = timeoutSeconds).also { it.start() }
            val lines = ArrayList<String>()
            var capped = false
            try {
                process.inputStream.bufferedReader().use { reader ->
                    var bytes = 0L
                    while (true) {
                        val line = reader.readLine() ?: break
                        bytes += line.length + 1
                        if (lines.size >= MAX_LINES || bytes > MAX_BYTES) {
                            capped = true
                            break
                        }
                        // Only the lines the parser needs are kept; everything else (banner, stream info) is dropped on the spot.
                        if (BLACKDETECT_MARKER in line) lines += line
                    }
                }
            } catch (_: IOException) {
                capped = true
            } finally {
                watchdog.interrupt()
            }
            if (capped || watchdog.timedOut) process.destroyForcibly()
            val finished = capped || watchdog.timedOut || process.waitFor(timeoutSeconds, TimeUnit.SECONDS)
            if (!finished) process.destroyForcibly()
            if (capped || watchdog.timedOut || !finished || process.exitValue() != 0) {
                logger.warn { "blank-picture scan of ${file.name} did not complete (timeout, output cap or non-zero exit)" }
                return@withContext null
            }
            val ranges = parse(lines = lines, videoStreamStartSeconds = videoStreamStartSeconds)
            logger.info { "blank-picture scan of ${file.name}: ${ranges.size} black stretch(es)" }
            ranges
        }

    private class Watchdog(
        private val process: Process,
        private val seconds: Long,
    ) : Thread("ffmpeg-blankdetect-watchdog") {
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

    companion object {
        const val DEFAULT_TIMEOUT_SECONDS = 600L

        /** A ffmpeg run of the filter graph above prints a handful of `blackdetect` lines; this is a safety bound, not an expected size. */
        const val MAX_LINES = 100_000
        const val MAX_BYTES = 4L * 1024 * 1024

        /** `blackdetect`'s `pic_th`: the share of black pixels a picture needs to count as black. */
        const val PICTURE_THRESHOLD = "0.98"

        /** `blackdetect`'s `pix_th`: the luma below which a pixel counts as black (a very dark real picture can fall below it). */
        const val PIXEL_THRESHOLD = "0.08"

        /** The end of a black stretch that has no `black_end` (it runs to the end of the file); callers clip it against the real stream length. */
        const val OPEN_END_SECONDS = 1.0e9

        private const val BLACKDETECT_MARKER = "black_start:"
        private val BLACK_RANGE = Regex("""black_start:(\S+)(?:\s+black_end:(\S+))?""")

        /**
         * Parses the `blackdetect` log lines (`[blackdetect @ 0x...] black_start:10 black_end:30 black_duration:20`). Container
         * timestamps (`-copyts`) become the stream's own timeline by subtracting [videoStreamStartSeconds]; a stretch with a start but
         * no end runs to [OPEN_END_SECONDS]; unparseable lines and non-finite numbers are skipped; the result is sorted and has
         * no stretch with a non-positive length.
         */
        internal fun parse(
            lines: List<String>,
            videoStreamStartSeconds: Double,
        ): List<TimeRange> =
            lines
                .mapNotNull { line ->
                    val match = BLACK_RANGE.find(line) ?: return@mapNotNull null
                    val start = match.groupValues[1].toDoubleOrNull()?.takeIf { it.isFinite() } ?: return@mapNotNull null
                    val endText = match.groupValues[2]
                    val end =
                        if (endText.isEmpty()) {
                            OPEN_END_SECONDS + videoStreamStartSeconds
                        } else {
                            endText.toDoubleOrNull()?.takeIf { it.isFinite() } ?: return@mapNotNull null
                        }
                    TimeRange(
                        startSeconds = (start - videoStreamStartSeconds).coerceAtLeast(0.0),
                        endSeconds = end - videoStreamStartSeconds,
                    )
                }.filter { it.lengthSeconds > 0.0 }
                .sortedBy { it.startSeconds }

        private fun formatSeconds(seconds: Double): String = String.format(java.util.Locale.ROOT, "%.3f", seconds)
    }
}
