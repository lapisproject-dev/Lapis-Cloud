package network.lapis.cloud.server.conference

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldEndWith
import io.kotest.matchers.string.shouldStartWith
import kotlinx.coroutines.delay
import java.io.File
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid

/**
 * V1.9.110 -- opt-in, env-gated live-container integration test for [LiveKitEgressClient.startParticipantFileEgress] (a Participant Egress
 * with an MP4 FILE output, the recording's per-person mode), same skip-unless-enabled posture as [LiveKitEgressLiveIntegrationTest] and
 * [LiveKitStreamEgressLiveIntegrationTest] (see those files' KDoc for why Testcontainers is not used here).
 *
 * **Enable explicitly**: `LAPIS_LIVEKIT_IT=true`, with `deploy/local/docker-compose.yml` up (`livekit`/`redis`/`egress`) and a `docker` binary
 * on `PATH` able to run `livekit/livekit-cli:latest` on the SAME compose network (`lapis-conference-dev_default`, override via
 * `LAPIS_LIVEKIT_IT_DOCKER_NETWORK`). The egress output directory on the HOST is `deploy/local/egress-out` (override via
 * `LAPIS_LIVEKIT_IT_EGRESS_OUT`).
 *
 * ```bash
 * docker compose -f deploy/local/docker-compose.yml up -d
 * LAPIS_LIVEKIT_IT=true ./gradlew :lapis-server:test --tests "*.LiveKitParticipantFileEgressLiveIntegrationTest"
 * ```
 *
 * What it proves against the REAL stack: the request shape of `startParticipantFileEgress` is accepted (no [LiveKitAdminException]), the
 * egress reaches ACTIVE, a `StopEgress` ends it with `EGRESS_COMPLETE`, `file_results[0].filename` ends in `.mp4`, the file exists on the host and
 * (when `ffprobe` is available) carries an audio AND a video stream. It does NOT judge synchronicity or camera-off behaviour -- the
 * staging runbook in `docs/architecture/conference-recording.adoc` does, with a real browser participant.
 */
class LiveKitParticipantFileEgressLiveIntegrationTest :
    FunSpec({
        val liveItEnabled = System.getenv("LAPIS_LIVEKIT_IT") == "true"
        val apiUrl = System.getenv("LAPIS_LIVEKIT_API_URL") ?: "http://localhost:7880"
        val apiKey = System.getenv("LAPIS_LIVEKIT_API_KEY") ?: "devkey"
        val apiSecret = System.getenv("LAPIS_LIVEKIT_API_SECRET") ?: "lapis-dev-livekit-secret-32bytes-min!!"
        val dockerNetwork = System.getenv("LAPIS_LIVEKIT_IT_DOCKER_NETWORK") ?: "lapis-conference-dev_default"
        // Gradle runs the tests with the module directory as working directory, the compose file lives at the repository root.
        val hostOutDir =
            System.getenv("LAPIS_LIVEKIT_IT_EGRESS_OUT")?.let { File(it) }
                ?: listOf("deploy/local/egress-out", "../deploy/local/egress-out").map { File(it) }.firstOrNull { it.isDirectory }
                ?: File("deploy/local/egress-out")

        val adminClient: LiveKitAdminClient by lazy { HttpLiveKitAdminClient(apiUrl = apiUrl, apiKey = apiKey, apiSecret = apiSecret) }
        val egressClient: LiveKitEgressClient by lazy { HttpLiveKitEgressClient(apiUrl = apiUrl, apiKey = apiKey, apiSecret = apiSecret) }

        test(
            "StartParticipantEgress with an MP4 file output is accepted, runs, and ends with an .mp4 on the host that carries audio and video",
        ).config(enabled = liveItEnabled, timeout = 150.seconds) {
            val roomName = "lc-egress-it-${Uuid.random()}"
            val identity = "it-person-${Uuid.random().toString().take(8)}"
            val container = "lc-it-person-${identity.takeLast(8)}"
            val rawDir = Uuid.random()
            val rowId = Uuid.random()
            val containerPath = RecordingEgressPaths.participantFile(containerDir = "/out", rawDir = rawDir.toString(), rowId = rowId)
            var startedEgressId: String? = null
            adminClient.createRoom(name = roomName, maxParticipants = 5, emptyTimeoutSeconds = 300)
            try {
                val process =
                    ProcessBuilder(
                        "docker",
                        "run",
                        "--rm",
                        "-d",
                        "--network",
                        dockerNetwork,
                        "--name",
                        container,
                        "livekit/livekit-cli:latest",
                        "room",
                        "join",
                        "--url",
                        "ws://livekit:7880",
                        "--api-key",
                        apiKey,
                        "--api-secret",
                        apiSecret,
                        "--identity",
                        identity,
                        "--room",
                        roomName,
                        "--publish-demo",
                    ).redirectErrorStream(true).start()
                val output = process.inputStream.bufferedReader().readText()
                check(process.waitFor() == 0) { "docker run for the synthetic publisher failed: $output" }
                var published = false
                repeat(20) {
                    if (!published) {
                        published = adminClient.listParticipants(roomName).any { it.identity == identity && it.tracks.isNotEmpty() }
                        if (!published) delay(500)
                    }
                }
                check(published) { "the synthetic publisher never showed a published track within 10 s" }

                val started =
                    egressClient.startParticipantFileEgress(
                        roomName = roomName,
                        identity = identity,
                        outputFilepath = containerPath,
                    )
                startedEgressId = started.egressId
                started.egressId shouldStartWith "EG_"

                var status = started.status
                repeat(15) {
                    if (status != "EGRESS_ACTIVE") {
                        delay(2_000)
                        status = egressClient.listEgress(roomName).firstOrNull { it.egressId == started.egressId }?.status ?: status
                    }
                }
                status shouldBe "EGRESS_ACTIVE"
                delay(10_000)

                egressClient.stopEgress(roomName = roomName, egressId = started.egressId)
                var finished: LiveKitEgressInfo? = null
                repeat(30) {
                    if (finished == null) {
                        delay(2_000)
                        finished =
                            egressClient.listEgress(roomName).firstOrNull {
                                it.egressId == started.egressId &&
                                    it.status == "EGRESS_COMPLETE"
                            }
                    }
                }
                val info = checkNotNull(finished) { "the egress did not reach EGRESS_COMPLETE within 60 s" }
                val filename = checkNotNull(info.firstFileResult) { "no file result on the completed egress" }.filename
                filename shouldEndWith ".mp4"

                val hostFile = File(hostOutDir, "$rawDir/${File(filename).name}")
                check(
                    hostFile.isFile && hostFile.length() > 0,
                ) { "expected a non-empty file at ${hostFile.path} (egress file_result: $filename)" }
                val prober = FfprobeMediaProber(ffprobePath = "ffprobe")
                val times = prober.probeStreamStartTimes(hostFile)
                if (times != null) {
                    times.hasVideo shouldBe true
                    times.hasAudio shouldBe true
                    // A real egress file does NOT start both streams together (2026-10-11: video 1.2-1.6 s after the audio): this is the
                    // fact ParticipantFileInputs' per-stream offsets exist for. Logged, not asserted -- the value depends on the machine.
                    println("LiveKitParticipantFileEgressLiveIntegrationTest: stream start times of the real egress file: $times")
                }
            } finally {
                runCatching { ProcessBuilder("docker", "rm", "-f", container).start().waitFor() }
                runCatching { startedEgressId?.let { egressClient.stopEgress(roomName = roomName, egressId = it) } }
                runCatching { adminClient.deleteRoom(roomName) }
            }
        }
    })
