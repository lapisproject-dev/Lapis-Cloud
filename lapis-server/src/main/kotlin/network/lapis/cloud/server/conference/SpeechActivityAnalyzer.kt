package network.lapis.cloud.server.conference

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

private val logger = KotlinLogging.logger {}

/**
 * V1.9.115 -- measures the loudness of an audio file in windows of 100 ms, the raw material of the speaker timeline of a recording
 * ([RecordingSpeakerTimeline]). A test seam like [RecordingBlankVideoDetector]: [None] measures nothing, and a recording whose
 * timeline could not be measured is composed as the plain grid -- a failed analysis NEVER fails a recording.
 */
interface SpeechActivityAnalyzer {
    /**
     * The levels of the first audio stream of [file] in that stream's own timeline (0 = its first sample), or `null` when the measurement
     * failed (binary missing, timeout, output cap, non-zero exit). NEVER throws, and never puts the file name or any ffmpeg output into
     * anything but a log line that carries counts only.
     */
    suspend fun levels(
        file: File,
        windowMs: Int = WINDOW_MS,
    ): AudioLevelSeries?

    /** Always "not measured" -- for deployments and tests without ffmpeg. */
    object None : SpeechActivityAnalyzer {
        override suspend fun levels(
            file: File,
            windowMs: Int,
        ): AudioLevelSeries? = null
    }

    companion object {
        /** The window (and with it the grid of the speaker timeline). */
        const val WINDOW_MS = 100
    }
}

/**
 * `ffmpeg`-backed [SpeechActivityAnalyzer]: ONE decoding run per audio file, `aresample=16000` (cheap), `asetnsamples` cuts the stream
 * into windows of exactly [SpeechActivityAnalyzer.WINDOW_MS], `astats` (reset per window) measures their RMS, `ametadata` prints it to
 * stdout. The argument list is built without a shell and the filter string only contains the (validated) window length -- the file path
 * is the `-i` argument and appears nowhere else. stdout is read with a line and byte cap and a hard timeout; neither stdout nor stderr
 * ever leaves this class.
 */
class FfmpegSpeechActivityAnalyzer(
    private val ffmpegPath: String,
    private val timeoutSeconds: Long = DEFAULT_TIMEOUT_SECONDS,
) : SpeechActivityAnalyzer {
    override suspend fun levels(
        file: File,
        windowMs: Int,
    ): AudioLevelSeries? =
        withContext(Dispatchers.IO) {
            if (windowMs !in MIN_WINDOW_MS..MAX_WINDOW_MS) return@withContext null
            val samplesPerWindow = SAMPLE_RATE * windowMs / 1000
            val command =
                listOf(
                    ffmpegPath,
                    "-hide_banner",
                    "-nostdin",
                    "-nostats",
                    "-loglevel",
                    "error",
                    "-i",
                    file.absolutePath,
                    "-map",
                    "0:a:0",
                    "-vn",
                    "-af",
                    "aresample=$SAMPLE_RATE,asetnsamples=n=$samplesPerWindow:p=0,astats=metadata=1:reset=1," +
                        "ametadata=mode=print:key=$RMS_KEY:file=-",
                    "-f",
                    "null",
                    "-",
                )
            val process =
                try {
                    ProcessBuilder(command).redirectError(ProcessBuilder.Redirect.DISCARD).start()
                } catch (e: IOException) {
                    logger.warn { "ffmpeg could not be started for the speech analysis (${e::class.simpleName ?: "unknown error"})" }
                    return@withContext null
                }
            val watchdog = Watchdog(process = process, seconds = timeoutSeconds).also { it.start() }
            val parser = AstatsLineParser()
            var capped = false
            try {
                process.inputStream.bufferedReader().use { reader ->
                    var bytes = 0L
                    var lineCount = 0
                    while (true) {
                        val line = reader.readLine() ?: break
                        bytes += line.length + 1
                        lineCount++
                        if (lineCount > MAX_LINES || bytes > MAX_BYTES) {
                            capped = true
                            break
                        }
                        parser.accept(line)
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
                logger.warn { "speech analysis did not complete (timeout, output cap or non-zero exit)" }
                return@withContext null
            }
            val series = parser.finish(windowSeconds = windowMs / 1000.0)
            logger.info { "speech analysis: ${series.points.size} window(s) measured" }
            series
        }

    private class Watchdog(
        private val process: Process,
        private val seconds: Long,
    ) : Thread("ffmpeg-speech-watchdog") {
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
        const val SAMPLE_RATE = 16_000
        const val MIN_WINDOW_MS = 20
        const val MAX_WINDOW_MS = 1_000

        /** A day of audio at 100 ms is 864 000 windows (two lines each); this is a safety bound, not an expected size. */
        const val MAX_LINES = 2_000_000
        const val MAX_BYTES = 128L * 1024 * 1024
        const val RMS_KEY = "lavfi.astats.Overall.RMS_level"

        internal val PTS_TIME = Regex("""pts_time:(\S+)""")
    }
}

/**
 * Parses the `ametadata` print output: a `frame:N pts:P pts_time:T` line followed by `lavfi.astats.Overall.RMS_level=V`. `-inf` is
 * silence ([SpeechActivity.SILENCE_DB]); `nan`, unparseable values, non-finite or negative times and a level line without a preceding
 * frame line are skipped. Tolerates CRLF and any other line in between. The result is sorted by time.
 */
fun parseAstatsOutput(
    lines: List<String>,
    windowSeconds: Double,
): AudioLevelSeries {
    val parser = AstatsLineParser()
    for (raw in lines) parser.accept(raw)
    return parser.finish(windowSeconds)
}

/** Incremental parser of the `ametadata` output: keeps one pending `pts_time` and the measured points, never the raw lines. */
internal class AstatsLineParser {
    private val points = ArrayList<AudioLevelPoint>()
    private var time: Double? = null

    fun accept(raw: String) {
        val line = raw.trim()
        if (line.startsWith("frame:")) {
            time =
                FfmpegSpeechActivityAnalyzer.PTS_TIME
                    .find(line)
                    ?.groupValues
                    ?.get(1)
                    ?.toDoubleOrNull()
                    ?.takeIf { it.isFinite() && it >= 0.0 }
            return
        }
        val prefix = "${FfmpegSpeechActivityAnalyzer.RMS_KEY}="
        if (!line.startsWith(prefix)) return
        val t = time ?: return
        time = null
        val text = line.removePrefix(prefix).trim()
        val level =
            when {
                text.equals("-inf", ignoreCase = true) -> SpeechActivity.SILENCE_DB
                else -> text.toDoubleOrNull()?.takeIf { it.isFinite() }?.coerceAtLeast(SpeechActivity.SILENCE_DB)
            } ?: return
        points += AudioLevelPoint(timeSeconds = t, levelDb = level)
    }

    fun finish(windowSeconds: Double): AudioLevelSeries =
        AudioLevelSeries(points = points.sortedBy { it.timeSeconds }, windowSeconds = windowSeconds)
}
