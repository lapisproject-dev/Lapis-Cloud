package network.lapis.cloud.server.conference

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import io.kotest.matchers.types.shouldNotBeInstanceOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.ExperimentalTime
import kotlin.time.TestTimeSource

/** V1.9.155 -- the retry policy of [FfmpegSegmentedComposer] against a fake [FfmpegStepRunner] (no real ffmpeg). */
@OptIn(ExperimentalTime::class)
class FfmpegSegmentedComposerRetryTest :
    FunSpec({
        data class Call(
            val label: String,
            val timeout: Duration,
        )

        /** One video track, 6 s, one segment: remux video, video segment, remux audio, audio segment, final mux. */
        fun spec(dir: File): RecordingComposeSpec {
            val video = File(dir, "in.webm").also { it.writeText("x") }
            val audio = File(dir, "in.ogg").also { it.writeText("x") }
            return RecordingComposeSpec(
                videoInputs =
                    listOf(
                        RecordingComposeVideoInput(
                            trackId = "v",
                            file = video,
                            offsetSeconds = 0.0,
                            isScreenShare = false,
                            durationSeconds = 6.0,
                        ),
                    ),
                audioInputs = listOf(RecordingComposeAudioInput(file = audio, offsetSeconds = 0.0, mediaDurationSeconds = 6.0)),
                outputDurationSeconds = 6.0,
            )
        }

        fun withDir(block: suspend (File) -> Unit) {
            val dir = Files.createTempDirectory("retry-test").toFile()
            try {
                runBlocking { block(dir) }
            } finally {
                dir.deleteRecursively()
            }
        }

        /** A runner whose outcome per call is chosen by [behaviour] (call number counted per label). */
        class FakeRunner(
            val behaviour: suspend (label: String, attempt: Int) -> Unit,
        ) : FfmpegStepRunner {
            val calls = mutableListOf<Call>()
            private val perLabel = HashMap<String, Int>()

            override suspend fun run(
                args: List<String>,
                timeout: Duration,
                label: String,
            ) {
                calls += Call(label = label, timeout = timeout)
                val n = (perLabel[label] ?: 0) + 1
                perLabel[label] = n
                behaviour(label, n)
            }
        }

        fun timeout(label: String) = RecordingComposeTimeoutException(message = "$label exceeded its time limit")

        fun composer(
            runner: FfmpegStepRunner,
            minutes: Long = 120,
            timeSource: kotlin.time.TimeSource = kotlin.time.TimeSource.Monotonic,
        ) = FfmpegSegmentedComposer(ffmpegPath = "ffmpeg", timeoutMinutes = minutes, runner = runner, timeSource = timeSource)

        fun videoCalls(r: FakeRunner) = r.calls.filter { it.label.startsWith("video segment") }

        test("two timeouts, success on the third attempt (which carries the old generous limit)") {
            withDir { dir ->
                val runner = FakeRunner { label, n -> if (label.startsWith("video segment") && n < 3) throw timeout(label) }
                composer(runner).compose(spec = spec(dir), outputFile = File(dir, "out.mp4"))
                videoCalls(runner).map { it.timeout } shouldContainExactly listOf(30.seconds, 30.seconds, 60.seconds)
            }
        }

        test("one timeout, success on the second attempt") {
            withDir { dir ->
                val runner = FakeRunner { label, n -> if (label.startsWith("audio segment") && n < 2) throw timeout(label) }
                composer(runner).compose(spec = spec(dir), outputFile = File(dir, "out.mp4"))
                runner.calls.count { it.label.startsWith("audio segment") } shouldBe 2
            }
        }

        test("three timeouts give up with a time-limit exception after exactly three calls") {
            withDir { dir ->
                val runner = FakeRunner { label, _ -> if (label.startsWith("video segment")) throw timeout(label) }
                val e =
                    shouldThrow<RecordingComposeException> { composer(runner).compose(spec = spec(dir), outputFile = File(dir, "out.mp4")) }
                e.message shouldContain "3 times"
                videoCalls(runner).size shouldBe 3
            }
        }

        test("a normal ffmpeg failure (exit code) is passed on at once, never retried") {
            withDir { dir ->
                val runner =
                    FakeRunner { label, _ ->
                        if (label.startsWith("video segment")) throw RecordingComposeException(message = "$label exited with code 1")
                    }
                val e =
                    shouldThrow<RecordingComposeException> { composer(runner).compose(spec = spec(dir), outputFile = File(dir, "out.mp4")) }
                e.shouldNotBeInstanceOf<RecordingComposeTimeoutException>()
                videoCalls(runner).size shouldBe 1
            }
        }

        test("the overall limit stays binding: no second attempt once the composition time is used up") {
            withDir { dir ->
                val clock = TestTimeSource()
                val runner =
                    FakeRunner { label, _ ->
                        if (label.startsWith("video segment")) {
                            clock += 2.minutes
                            throw timeout(label)
                        }
                    }
                val e =
                    shouldThrow<RecordingComposeException> {
                        composer(runner, minutes = 1, timeSource = clock).compose(spec = spec(dir), outputFile = File(dir, "out.mp4"))
                    }
                e.message shouldContain "composition exceeded 1 minute(s)"
                videoCalls(runner).size shouldBe 1
            }
        }

        test("cancellation is not swallowed and starts no further attempt") {
            withDir { dir ->
                val started = CompletableDeferred<Unit>()
                val runner =
                    FakeRunner { label, _ ->
                        if (label.startsWith("video segment")) {
                            started.complete(Unit)
                            awaitCancellation()
                        }
                    }
                val job = async { composer(runner).compose(spec = spec(dir), outputFile = File(dir, "out.mp4")) }
                started.await()
                job.cancel()
                shouldThrow<CancellationException> { job.await() }
                videoCalls(runner).size shouldBe 1
            }
        }

        test("remux and final mux get exactly one attempt") {
            withDir { dir ->
                val runner = FakeRunner { label, _ -> if (label.startsWith("remux video")) throw timeout(label) }
                val e =
                    shouldThrow<RecordingComposeException> { composer(runner).compose(spec = spec(dir), outputFile = File(dir, "out.mp4")) }
                e.shouldBeInstanceOf<RecordingComposeTimeoutException>()
                runner.calls.count { it.label.startsWith("remux video") } shouldBe 1

                val runner2 = FakeRunner { label, _ -> if (label == "final mux") throw timeout(label) }
                shouldThrow<RecordingComposeException> { composer(runner2).compose(spec = spec(dir), outputFile = File(dir, "out2.mp4")) }
                runner2.calls.count { it.label == "final mux" } shouldBe 1
            }
        }
    })
