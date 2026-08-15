package network.lapis.cloud.server.conference

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

private val logger = KotlinLogging.logger {}

/**
 * V1.0 Videokonferenzen (Kleinsitzung), Wave 2 "Aufzeichnung" -- the real, `ProcessBuilder`-backed
 * [RecordingComposer]. Runs `ffmpeg` with the argument list [FfmpegArgumentBuilder.build] produces
 * (kept in a separate, pure, process-free object precisely so the filter-graph construction is
 * unit-testable without ever invoking this class or a real binary -- see that object's own KDoc).
 *
 * **stderr never reaches the exception message.** ffmpeg's combined stdout+stderr (`ffmpeg` writes
 * its own progress logging to stderr; [redirectErrorStream] merges it into the same stream this
 * class reads) is drained on a background daemon thread into an in-memory buffer purely so the OS
 * pipe never fills and blocks `ffmpeg` mid-composition -- a real risk once a composition runs more
 * than a few seconds and ffmpeg's own verbose logging exceeds the default ~64 KiB pipe buffer. That
 * buffer is logged via `kotlin-logging` on failure, NEVER folded into [RecordingComposeException]'s
 * message -- same "never trust that a subprocess's own output cannot contain something sensitive"
 * discipline [LiveKitAdminException] KDoc establishes for its own outbound-HTTP error surface.
 *
 * **Timeout enforcement**: [Process.waitFor] with an explicit timeout, [Process.destroyForcibly] on
 * expiry -- the literal mechanism the Wave 2 plan's own "`Process.destroyForcibly()` beyond this"
 * describes for [ConferenceRecordingConfig.composeTimeoutMinutes].
 */
class FfmpegGalleryComposer(
    private val ffmpegPath: String,
    private val timeoutMinutes: Long,
) : RecordingComposer {
    override suspend fun compose(
        spec: RecordingComposeSpec,
        outputFile: File,
    ) {
        val args = FfmpegArgumentBuilder.build(spec = spec, outputPath = outputFile.absolutePath)
        val command = listOf(ffmpegPath) + args
        val process =
            try {
                withContext(Dispatchers.IO) { ProcessBuilder(command).redirectErrorStream(true).start() }
            } catch (e: IOException) {
                throw RecordingComposeException(
                    message = "ffmpeg could not be started (${e::class.simpleName ?: "unknown error"})",
                    cause = e,
                )
            }

        val outputBuffer = StringBuilder()
        val drainThread =
            Thread {
                try {
                    process.inputStream.bufferedReader().forEachLine { line ->
                        synchronized(outputBuffer) { outputBuffer.appendLine(line) }
                    }
                } catch (_: IOException) {
                    // Stream closed on process end/destroy -- expected, not an error.
                }
            }.apply {
                isDaemon = true
                name = "ffmpeg-output-drain"
                start()
            }

        val finished = withContext(Dispatchers.IO) { process.waitFor(timeoutMinutes, TimeUnit.MINUTES) }
        if (!finished) {
            process.destroyForcibly()
            drainThread.join(2_000)
            logger.warn { "ffmpeg composition exceeded ${timeoutMinutes}m and was killed -- last output: ${lastLines(outputBuffer)}" }
            throw RecordingComposeException(message = "ffmpeg composition exceeded $timeoutMinutes minute(s) and was forcibly terminated")
        }
        drainThread.join(5_000)
        val exitCode = process.exitValue()
        if (exitCode != 0) {
            logger.warn { "ffmpeg exited with code $exitCode -- last output: ${lastLines(outputBuffer)}" }
            throw RecordingComposeException(message = "ffmpeg exited with code $exitCode")
        }
    }

    private fun lastLines(buffer: StringBuilder): String =
        synchronized(buffer) { buffer.toString() }.lines().takeLast(30).joinToString("\n")
}
