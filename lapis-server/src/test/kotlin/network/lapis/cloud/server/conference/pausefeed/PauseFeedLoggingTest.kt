package network.lapis.cloud.server.conference.pausefeed

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.slf4j.LoggerFactory
import java.io.File
import java.net.InetAddress
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import kotlin.time.Duration.Companion.milliseconds
import kotlin.uuid.Uuid

/**
 * V1.9.255 -- a feed whose ffmpeg writes the target URL and the stream key to stderr and dies must leave NOTHING of either in any log level
 * (the supervisor, the launcher and the process-end dump together), and not in the command's `toString` either.
 */
class PauseFeedLoggingTest :
    FunSpec({
        test(
            "stderr that names the URL and the key, a crash loop up to FAILED: no key and no plaintext URL in any log record at any level",
        ) {
            val key = "TOPSECRETSTREAMKEY99"
            val url = "rtmp://sink.example.org:1935/live/$key"
            val dir = Files.createTempDirectory("fake-ffmpeg").toFile()
            val script = File(dir, "ffmpeg")
            script.writeText(
                "#!/bin/sh\necho \"Error opening output $url: Input/output error\" 1>&2\necho \"slave '$key' failed, host sink.example.org\" 1>&2\nexit 1\n",
            )
            Files.setPosixFilePermissions(script.toPath(), PosixFilePermissions.fromString("rwxr-xr-x"))

            val root = LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME) as Logger
            val previous = root.level
            val appender = ListAppender<ILoggingEvent>().also { it.start() }
            root.level = Level.TRACE
            root.addAppender(appender)
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            try {
                val id = Uuid.random()
                val supervisor =
                    PauseFeedSupervisor(
                        launcher = FfmpegPauseFeedLauncher(ffmpegPath = script.path),
                        media =
                            object : PauseMediaSource {
                                override fun imageFile() = File("/nonexistent.png")

                                override fun musicFile(): File? = null
                            },
                        targetPolicy =
                            PauseFeedTargetPolicy(
                                resolver = { listOf(InetAddress.getByName("203.0.113.9")) },
                                allowRtmps = true,
                            ),
                        dataSource = { sid ->
                            if (sid == id) PauseFeedWish(roomId = Uuid.random(), targetUrls = listOf(url)) else null
                        },
                        enabled = true,
                        tlsVerifyArgs = emptyList(),
                        onStateChanged = {},
                        onFeedEnded = {},
                        scope = scope,
                        timings =
                            PauseFeedTimings(
                                watchdogInterval = 30.milliseconds,
                                livenessTimeout = 5_000.milliseconds,
                                stopGrace = 200.milliseconds,
                                killGrace = 100.milliseconds,
                                restartBackoff = listOf(40.milliseconds, 60.milliseconds),
                            ),
                    )
                kotlinx.coroutines.runBlocking { supervisor.ensureRunning(id) }
                val deadline = System.currentTimeMillis() + 8_000
                while (supervisor.stateOf(id) != network.lapis.cloud.shared.domain.ConferenceStreamPauseFeedState.FAILED &&
                    System.currentTimeMillis() < deadline
                ) {
                    Thread.sleep(20)
                }
                supervisor.stateOf(id) shouldBe network.lapis.cloud.shared.domain.ConferenceStreamPauseFeedState.FAILED
                Thread.sleep(300) // let the stderr threads write their end dump
                val rendered = appender.list.map { it.formattedMessage + (it.throwableProxy?.message ?: "") }
                (rendered.isNotEmpty()) shouldBe true
                rendered.forEach { line ->
                    (line.contains(key)) shouldBe false
                    (line.contains(url)) shouldBe false
                    (line.contains("sink.example.org:1935")) shouldBe false
                }
                // the sanitized dump itself is there (the operator can see that ffmpeg complained), without the secrets
                rendered.any { it.contains("pause feed ffmpeg output") } shouldBe true
            } finally {
                root.detachAppender(appender)
                root.level = previous
                scope.cancel()
            }
        }
    })
