package network.lapis.cloud.server.conference

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

private val logger = KotlinLogging.logger {}

/**
 * A/V-sync fix (2026-09-27) -- pluggable boundary around measuring a raw per-track egress file's
 * ACTUAL media duration, the second anchor [RecordingTrackAlignment] needs (see that object's KDoc
 * "Two anchors per track"). Same pluggable-boundary pattern as [RecordingComposer]: the poller
 * depends on this interface, tests use an in-memory fake, and `./gradlew clean check` never needs
 * a real `ffprobe` binary.
 */
interface RecordingMediaProber {
    /**
     * The container-level media duration of [file] in seconds, or `null` if it could not be
     * determined (binary missing, timeout, unparseable output, unreadable file). NEVER throws --
     * a failed probe must degrade to "no head-loss correction" in the poller, not fail the
     * composition.
     */
    suspend fun probeDurationSeconds(file: File): Double?

    /** Always `null` -- for deployments/tests without a probe; the poller then behaves exactly as before this fix. */
    object None : RecordingMediaProber {
        override suspend fun probeDurationSeconds(file: File): Double? = null
    }
}

/**
 * `ProcessBuilder`-backed [RecordingMediaProber] running
 * `ffprobe -v error -show_entries format=duration -of csv=p=0 <file>` -- one line, the duration
 * as a decimal number (`ffprobe` always prints with a period, locale-independent). `ffprobe` ships
 * in the same Debian `ffmpeg` package the server image installs (see `Dockerfile`), so wherever
 * composition works, probing does too.
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
    override suspend fun probeDurationSeconds(file: File): Double? =
        withContext(Dispatchers.IO) {
            val command =
                listOf(ffprobePath, "-v", "error", "-show_entries", "format=duration", "-of", "csv=p=0", file.absolutePath)
            val process =
                try {
                    ProcessBuilder(command).redirectErrorStream(true).start()
                } catch (e: IOException) {
                    logger.warn { "ffprobe could not be started (${e::class.simpleName ?: "unknown error"}, path='$ffprobePath')" }
                    return@withContext null
                }
            val output = process.inputStream.bufferedReader().use { it.readText() }
            val finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS)
            if (!finished) {
                process.destroyForcibly()
                logger.warn { "ffprobe did not finish within ${timeoutSeconds}s for ${file.name}" }
                return@withContext null
            }
            if (process.exitValue() != 0) {
                logger.warn { "ffprobe exited with ${process.exitValue()} for ${file.name} -- output: ${output.trim().take(500)}" }
                return@withContext null
            }
            parseDuration(output) ?: run {
                logger.warn { "ffprobe output for ${file.name} carried no parseable duration: '${output.trim().take(200)}'" }
                null
            }
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

        /** First line that parses as a finite, non-negative decimal (`ffprobe` may print `N/A` for a broken container). */
        fun parseDuration(output: String): Double? =
            output
                .lineSequence()
                .map { it.trim().trimEnd(',') }
                .filter { it.isNotEmpty() }
                .mapNotNull { it.toDoubleOrNull() }
                .firstOrNull { it.isFinite() && it >= 0.0 }
    }
}
