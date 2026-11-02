package network.lapis.cloud.server.conference.pausefeed

import io.github.oshai.kotlinlogging.KotlinLogging
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import network.lapis.cloud.server.conference.FfmpegProcessRunner
import java.io.File
import java.net.ServerSocket
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.seconds

private val logger = KotlinLogging.logger {}

private fun ffmpegWithX264(): Boolean =
    runCatching {
        val p = ProcessBuilder("ffmpeg", "-hide_banner", "-encoders").redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText()
        p.waitFor(10, TimeUnit.SECONDS)
        out.contains("libx264") && out.contains(" aac ")
    }.getOrDefault(false)

private fun freePort(): Int = ServerSocket(0).use { it.localPort }

/**
 * V1.9.255 -- tests with the REAL ffmpeg (they run only where ffmpeg with libx264 and aac is installed; otherwise the body returns at once and
 * the test is reported as passed without having tested anything -- the CI image has ffmpeg).
 *
 *  * the feed really sends (an ffmpeg RTMP listener on a local port receives video AND audio) and ends on SIGTERM;
 *  * **the measurement behind the design decision "`-progress` is no orphan guard"**: closing the reader of `-progress pipe:1` does NOT end
 *    ffmpeg (it ignores SIGPIPE). The result is logged; the test asserts only that nothing breaks.
 */
class PauseFeedRealFfmpegTest :
    FunSpec({
        test("the feed reaches a local RTMP listener with picture and music, reports progress and ends on SIGTERM") {
            if (!ffmpegWithX264()) return@test
            val dir = Files.createTempDirectory("pause-feed-real").toFile()
            val image = PauseMediaStore(root = dir.resolve("store")).defaultImage()
            // music: a 6 s sine wave, run through the same processor as an upload
            val wav = File(dir, "tone.wav")
            ProcessBuilder(
                "ffmpeg",
                "-nostdin",
                "-v",
                "error",
                "-y",
                "-f",
                "lavfi",
                "-i",
                "sine=frequency=330:duration=6",
                wav.path,
            ).start().waitFor() shouldBe
                0
            val music = File(dir, "music.m4a")
            val processed =
                runBlocking {
                    PauseMusicProcessor(runner = FfmpegProcessRunner("ffmpeg"), prober = FfprobeAudioProber("ffprobe"))
                        .process(input = wav, sniffed = SniffedAudio.WAV, out = music)
                }
            (processed is MusicProcessingResult.Ok) shouldBe true

            val port = freePort()
            val received = File(dir, "received.flv")
            val listener =
                ProcessBuilder(
                    "ffmpeg",
                    "-nostdin",
                    "-v",
                    "error",
                    "-y",
                    "-listen",
                    "1",
                    "-timeout",
                    "30",
                    "-i",
                    "rtmp://127.0.0.1:$port/live/testkey",
                    "-c",
                    "copy",
                    "-f",
                    "flv",
                    received.path,
                ).redirectErrorStream(true).start()
            Thread { runCatching { listener.inputStream.readAllBytes() } }.apply {
                isDaemon = true
                start()
            }
            try {
                Thread.sleep(800) // let the listener bind
                val url = "rtmp://127.0.0.1:$port/live/testkey"
                val command =
                    PauseFeedArgumentBuilder.build(
                        imagePng = image,
                        musicM4a = music,
                        targetUrls = listOf(url),
                        tlsVerifyArgs = emptyList(),
                    )
                val handle = FfmpegPauseFeedLauncher(ffmpegPath = "ffmpeg").launch(command)
                val deadline = System.currentTimeMillis() + 20_000
                while (!handle.hasProgressed && handle.isAlive && System.currentTimeMillis() < deadline) Thread.sleep(50)
                handle.hasProgressed shouldBe true
                Thread.sleep(3_000) // a little payload
                handle.terminate()
                runBlocking { handle.awaitExit(10.seconds) } shouldBe true
                listener.waitFor(15, TimeUnit.SECONDS)
                (received.length() > 1_000) shouldBe true
                val probe =
                    ProcessBuilder(
                        "ffprobe",
                        "-v",
                        "error",
                        "-show_entries",
                        "stream=codec_type",
                        "-of",
                        "csv=p=0",
                        received.path,
                    ).redirectErrorStream(true).start()
                val kinds =
                    probe.inputStream
                        .bufferedReader()
                        .readLines()
                        .map { it.trim() }
                        .toSet()
                probe.waitFor(10, TimeUnit.SECONDS)
                (kinds.contains("video") && kinds.contains("audio")) shouldBe true
            } finally {
                listener.destroyForcibly()
            }
        }

        test(
            "MEASUREMENT: closing the reader of -progress pipe:1 does not end ffmpeg (so -progress is a sign of life, not an orphan guard)",
        ) {
            if (!ffmpegWithX264()) return@test
            val process =
                ProcessBuilder(
                    "ffmpeg",
                    "-nostdin",
                    "-hide_banner",
                    "-loglevel",
                    "error",
                    "-re",
                    "-f",
                    "lavfi",
                    "-i",
                    "testsrc=r=5:s=160x120",
                    "-t",
                    "60",
                    "-f",
                    "null",
                    "-",
                    "-progress",
                    "pipe:1",
                    "-stats_period",
                    "1",
                ).redirectError(ProcessBuilder.Redirect.DISCARD).start()
            try {
                Thread.sleep(1_500)
                process.inputStream.close() // the reader goes away: the next write to the pipe fails
                Thread.sleep(5_000)
                val stillRunning = process.isAlive
                logger.info {
                    "MEASURED: ffmpeg after the -progress reader was closed: ${if (stillRunning) "STILL RUNNING (not an orphan guard)" else "EXITED"}"
                }
                println(
                    "MEASURED (PauseFeedRealFfmpegTest): ffmpeg after closing the -progress reader: ${if (stillRunning) "still running" else "exited"}",
                )
            } finally {
                process.destroyForcibly()
            }
        }
    })
