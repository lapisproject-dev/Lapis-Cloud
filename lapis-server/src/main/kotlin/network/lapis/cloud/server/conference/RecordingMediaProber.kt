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

    companion object {
        const val DEFAULT_TIMEOUT_SECONDS = 30L

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
