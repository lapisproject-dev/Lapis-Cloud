package network.lapis.cloud.server.conference

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.kotest.matchers.types.shouldNotBeInstanceOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** V1.9.155 -- the real process discipline of [FfmpegProcessRunner], driven with plain Unix tools (`sh`). */
class FfmpegProcessRunnerTest :
    FunSpec({
        val unix = !System.getProperty("os.name").lowercase().contains("win")

        fun pidAlive(pid: Long) = ProcessHandle.of(pid).map { it.isAlive }.orElse(false)

        test("a hanging process raises the TIMEOUT exception and is dead afterwards").config(enabled = unix) {
            val dir = Files.createTempDirectory("runner-test").toFile()
            try {
                val pidFile = File(dir, "pid")
                // `exec sleep` keeps the shell's pid, so the recorded pid is the one that must be killed.
                val runner = FfmpegProcessRunner("sh")
                val e =
                    shouldThrow<RecordingComposeException> {
                        runner.run(
                            args = listOf("-c", "echo \$\$ > ${pidFile.absolutePath}; exec sleep 30"),
                            timeout = 1.seconds,
                            label = "t",
                        )
                    }
                e.shouldBeInstanceOf<RecordingComposeTimeoutException>()
                val pid = pidFile.readText().trim().toLong()
                var alive = true
                repeat(50) { if (alive && pidAlive(pid)) Thread.sleep(100) else alive = false }
                alive shouldBe false
            } finally {
                dir.deleteRecursively()
            }
        }

        test("a non-zero exit raises the base exception, NOT the timeout one").config(enabled = unix) {
            val e =
                shouldThrow<RecordingComposeException> {
                    FfmpegProcessRunner("sh").run(args = listOf("-c", "exit 3"), timeout = 10.seconds, label = "t")
                }
            e.shouldNotBeInstanceOf<RecordingComposeTimeoutException>()
            e.message shouldBe "t exited with code 3"
        }

        test("cancelling the caller kills the process").config(enabled = unix) {
            val dir = Files.createTempDirectory("runner-test").toFile()
            try {
                val pidFile = File(dir, "pid")
                runBlocking {
                    val job =
                        async {
                            FfmpegProcessRunner("sh")
                                .run(
                                    args = listOf("-c", "echo \$\$ > ${pidFile.absolutePath}; exec sleep 30"),
                                    timeout = 60.seconds,
                                    label = "t",
                                )
                        }
                    var waited = 0
                    while (!(pidFile.exists() && pidFile.readText().isNotBlank()) && waited++ < 100) delay(50.milliseconds)
                    job.cancel()
                    shouldThrow<CancellationException> { job.await() }
                }
                val pid = pidFile.readText().trim().toLong()
                var alive = true
                repeat(50) { if (alive && pidAlive(pid)) Thread.sleep(100) else alive = false }
                alive shouldBe false
            } finally {
                dir.deleteRecursively()
            }
        }
    })
