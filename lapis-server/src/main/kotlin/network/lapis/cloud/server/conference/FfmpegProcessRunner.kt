package network.lapis.cloud.server.conference

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.time.Duration

private val logger = KotlinLogging.logger {}

/**
 * Runs ONE `ffmpeg`/`ffprobe` child process to completion -- the process discipline of the recording composition, shared by every step of
 * [FfmpegSegmentedComposer] (it used to live in `FfmpegGalleryComposer`):
 *
 * - **Argument list only, never a shell.** The command is `binary + args`; nothing is interpreted.
 * - **stderr never reaches an exception message.** The combined stdout+stderr is drained on a daemon thread into a bounded in-memory buffer
 *   (so the OS pipe never fills and blocks the child -- ffmpeg's own logging easily exceeds the ~64 KiB pipe buffer) and is only ever
 *   logged through `kotlin-logging`, never folded into [RecordingComposeException] -- same "a subprocess's own output may contain something
 *   sensitive" discipline as [LiveKitAdminException].
 * - **Timeout with `destroyForcibly`**, also when the calling coroutine is cancelled; the drain thread is joined.
 */
class FfmpegProcessRunner(
    private val binaryPath: String,
) {
    /**
     * Runs the binary with [args]. Throws [RecordingComposeException] if the process cannot start, exits non-zero, or runs longer than
     * [timeout]; [label] names the step in the (sanitized) messages.
     */
    suspend fun run(
        args: List<String>,
        timeout: Duration,
        label: String,
    ) {
        val command = listOf(binaryPath) + args
        val process =
            try {
                withContext(Dispatchers.IO) { ProcessBuilder(command).redirectErrorStream(true).start() }
            } catch (e: IOException) {
                throw RecordingComposeException(
                    message = "$label: process could not be started (${e::class.simpleName ?: "unknown error"})",
                    cause = e,
                )
            }

        val outputBuffer = StringBuilder()
        val drainThread =
            Thread {
                try {
                    process.inputStream.bufferedReader().forEachLine { line ->
                        synchronized(outputBuffer) {
                            outputBuffer.appendLine(line)
                            if (outputBuffer.length > MAX_BUFFER_CHARS) outputBuffer.delete(0, outputBuffer.length - MAX_BUFFER_CHARS / 2)
                        }
                    }
                } catch (_: IOException) {
                    // Stream closed on process end/destroy -- expected, not an error.
                }
            }.apply {
                isDaemon = true
                name = "ffmpeg-output-drain"
                start()
            }

        var finished = false
        try {
            finished = withContext(Dispatchers.IO) { process.waitFor(timeout.inWholeMilliseconds.coerceAtLeast(1L), TimeUnit.MILLISECONDS) }
        } finally {
            // Timeout OR cancellation of the caller: never leave a child process running.
            if (!finished) process.destroyForcibly()
            drainThread.join(DRAIN_JOIN_MILLIS)
        }
        if (!finished) {
            logger.warn { "$label exceeded $timeout and was killed -- last output: ${lastLines(outputBuffer)}" }
            throw RecordingComposeException(message = "$label exceeded its time limit and was forcibly terminated")
        }
        val exitCode = process.exitValue()
        if (exitCode != 0) {
            logger.warn { "$label exited with code $exitCode -- last output: ${lastLines(outputBuffer)}" }
            throw RecordingComposeException(message = "$label exited with code $exitCode")
        }
    }

    private fun lastLines(buffer: StringBuilder): String =
        synchronized(buffer) { buffer.toString() }.lines().takeLast(30).joinToString("\n")

    private companion object {
        const val MAX_BUFFER_CHARS = 256 * 1024
        const val DRAIN_JOIN_MILLIS = 5_000L
    }
}
