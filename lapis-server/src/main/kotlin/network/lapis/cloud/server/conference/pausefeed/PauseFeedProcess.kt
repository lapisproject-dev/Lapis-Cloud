package network.lapis.cloud.server.conference.pausefeed

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.Duration

private val logger = KotlinLogging.logger {}

/** The seam to the operating system: one running feed process. */
internal interface PauseFeedProcessHandle {
    val isAlive: Boolean

    /** `System.nanoTime()` of the last `progress=` line ffmpeg printed (or of the start, before the first one). */
    val lastProgressAtNanos: Long

    /** `true` once ffmpeg printed at least one progress block (the feed is really sending). */
    val hasProgressed: Boolean

    /** SIGTERM. */
    fun terminate()

    fun destroyForcibly()

    /** `true` iff the process ended within [timeout]. */
    suspend fun awaitExit(timeout: Duration): Boolean
}

internal fun interface PauseFeedLauncher {
    /** Throws on a start failure. */
    fun launch(command: PauseFeedCommand): PauseFeedProcessHandle
}

/**
 * Starts the real ffmpeg. stdout (the `-progress` stream) and stderr are drained on their own daemon threads so neither pipe can fill up and
 * block the child. stderr passes through [PauseFeedCommand.sanitize] and is logged at WARN, the last 20 lines, only when the process ended
 * on its own.
 */
internal class FfmpegPauseFeedLauncher(
    private val ffmpegPath: String,
    private val nanoTime: () -> Long = System::nanoTime,
) : PauseFeedLauncher {
    override fun launch(command: PauseFeedCommand): PauseFeedProcessHandle {
        val process =
            try {
                ProcessBuilder(listOf(ffmpegPath) + command.args).redirectErrorStream(false).start()
            } catch (e: IOException) {
                throw IllegalStateException("pause feed process could not be started (${e::class.simpleName})")
            }
        runCatching { process.outputStream.close() }
        return RealHandle(process = process, command = command, nanoTime = nanoTime)
    }

    private class RealHandle(
        private val process: Process,
        private val command: PauseFeedCommand,
        private val nanoTime: () -> Long,
    ) : PauseFeedProcessHandle {
        private val lastProgress = AtomicLong(nanoTime())

        @Volatile private var progressed = false
        private val stderrTail = ArrayDeque<String>()
        private var stderrChars = 0

        init {
            Thread {
                try {
                    process.inputStream.bufferedReader().forEachLine { line ->
                        if (line.startsWith("progress=")) {
                            lastProgress.set(nanoTime())
                            progressed = true
                        }
                    }
                } catch (_: IOException) {
                    // closed on exit
                }
            }.apply {
                isDaemon = true
                name = "pause-feed-progress"
                start()
            }
            Thread {
                try {
                    process.errorStream.bufferedReader().forEachLine { line ->
                        synchronized(stderrTail) {
                            stderrTail.addLast(line)
                            stderrChars += line.length
                            while (stderrChars > MAX_STDERR_CHARS && stderrTail.size > 1) stderrChars -= stderrTail.removeFirst().length
                        }
                    }
                } catch (_: IOException) {
                    // closed on exit
                }
                logEnd()
            }.apply {
                isDaemon = true
                name = "pause-feed-stderr"
                start()
            }
        }

        private fun logEnd() {
            val lines = synchronized(stderrTail) { stderrTail.toList() }.takeLast(LOGGED_LINES).map { command.sanitize(it) }
            if (lines.isNotEmpty()) logger.warn { "pause feed ffmpeg output (last ${lines.size} lines):\n" + lines.joinToString("\n") }
        }

        override val isAlive: Boolean get() = process.isAlive
        override val lastProgressAtNanos: Long get() = lastProgress.get()
        override val hasProgressed: Boolean get() = progressed

        override fun terminate() {
            process.destroy()
        }

        override fun destroyForcibly() {
            process.destroyForcibly()
        }

        override suspend fun awaitExit(timeout: Duration): Boolean =
            withContext(Dispatchers.IO) { process.waitFor(timeout.inWholeMilliseconds.coerceAtLeast(1L), TimeUnit.MILLISECONDS) }
    }

    private companion object {
        const val MAX_STDERR_CHARS = 64 * 1024
        const val LOGGED_LINES = 20
    }
}
