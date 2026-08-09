package network.lapis.cloud.server.conference

import io.github.oshai.kotlinlogging.KotlinLogging

private val logger = KotlinLogging.logger {}

/**
 * V1.0 Videokonferenzen (Kleinsitzung), Wave 2 "Aufzeichnung" -- environment configuration for the
 * Track-Egress-based recording pipeline this wave adds on top of Wave 1's [ConferenceConfig]
 * (LiveKit URL/API key/secret, reused as-is for minting egress admin tokens -- see
 * [LiveKitEgressClient] KDoc; this class carries none of its own LiveKit credentials). Mirrors
 * [ConferenceConfig]'s own "plain `LAPIS_`-prefixed env var, injected `(String) -> String?` lookup,
 * sane local default" shape -- see that class's KDoc for why `env` is injected rather than a bare
 * `System.getenv` call scattered through this class (same testability reasoning).
 *
 * **[load] is string validation ONLY -- no filesystem, no process, no network I/O** -- so
 * `./gradlew clean check` never needs `ffmpeg`, Docker, or a running Egress container to exercise
 * this class. Whether `ffmpeg` is actually installed and runnable is a SEPARATE, explicit I/O step
 * ([probeFfmpegAvailable]), run exactly once at [network.lapis.cloud.server.Application.module]
 * startup (not from here) -- see that function's own KDoc for why recording degrades honestly
 * (`ffmpegAvailable = false`, surfaced on [network.lapis.cloud.shared.domain.ConferenceRecordingAvailabilityDto])
 * rather than crashing the whole server merely because a deployment legitimately wants conferencing
 * without recording.
 *
 * **Two deliberately separate output-directory env vars, never collapsed into one.** LiveKit
 * Track Egress's `DirectFileOutput.filepath` is a path INSIDE the egress container
 * ([outputContainerDir], default `/out`); the Ktor server reads those same bytes back from the
 * HOST filesystem ([outputHostDir], default `deploy/local/egress-out`) because in local dev it runs
 * outside Docker entirely. These are two different strings pointing at the same underlying bytes --
 * conflating them into a single env var produces a `FileNotFoundException` on the composer side
 * that looks exactly like "egress silently produced nothing" (see `deploy/local/docker-compose.yml`
 * `egress` service comment for the matching bind mount, and `deploy/local/README.adoc` for the
 * documented file-ownership caveat on native Linux Docker).
 *
 * **Why an external `ffmpeg` binary, not `org.bytedeco:ffmpeg-platform`**: the JavaCPP presets would
 * add roughly 150 MB of per-platform native binaries to a build that already carries a Kotlin/JS
 * client. A later wave's `RecordingComposer` sits behind a pluggable interface with an in-memory
 * fake for tests -- the same pluggable-boundary pattern [LiveKitAdminClient]/
 * [network.lapis.cloud.server.postal.PostalMailProvider]/
 * [network.lapis.cloud.server.economy.LtrBalanceProvider] already establish -- so nothing in the
 * hermetic test suite depends on the binary either way, regardless of which implementation choice
 * is made here.
 */
class ConferenceRecordingConfig private constructor(
    /** Master opt-in -- recording additionally requires [ConferenceConfig.enabled] (a deployment cannot record without also being able to confer at all). */
    val enabled: Boolean,
    /** Path INSIDE the egress container that [LiveKitEgressClient.startTrackEgress] writes into ([DirectFileOutput].filepath) -- see class KDoc "Two deliberately separate output-directory env vars". */
    val outputContainerDir: String,
    /** Path on the HOST filesystem where a later wave's `RecordingComposer`/poller reads the SAME raw track files back -- see class KDoc. */
    val outputHostDir: String,
    /** `ffmpeg` binary name or absolute path -- resolved via the process `PATH` if not absolute. Only ever probed/invoked by a later wave's composer; this class never runs it. */
    val ffmpegPath: String,
    /** [network.lapis.cloud.server.RecordingPoller] tick interval -- see that class's own KDoc (a later wave) for the full poll-not-webhook mechanism this drives. */
    val pollIntervalSeconds: Long,
    /** After `stoppedAt + this`, a recording still not fully `STOPPING` composes from whatever tracks ARE complete (>=1 video) or goes `FAILED` -- disk/stuck-egress guard. */
    val egressTimeoutMinutes: Long,
    /** Hard auto-stop ceiling from `startedAt` -- disk guard against a recording nobody ever stops. */
    val maxDurationMinutes: Long,
    /** Wall-clock kill threshold for the `ffmpeg` composition subprocess (`Process.destroyForcibly()` beyond this). */
    val composeTimeoutMinutes: Long,
    /** DoS cap on simultaneous track egresses per recording (a 25-participant Kleinsitzung ceiling x2 tracks each, plus headroom for screen shares). */
    val maxTracks: Int,
    /** `true` iff raw per-track files are KEPT after a successful compose (default `false` -- deleted once the composed file is archived as a document). Independent of the poller's FAILED-branch retention, which always keeps raw files regardless of this flag -- see that class's own KDoc "raw-file fate on FAILED". */
    val keepRaw: Boolean,
) {
    /** Deliberately omits nothing secret -- this class carries no credentials of its own (see class KDoc), so every field is safe to log as-is. Kept for symmetry with [ConferenceConfig.toString] regardless. */
    override fun toString(): String =
        "ConferenceRecordingConfig(enabled=$enabled, outputContainerDir='$outputContainerDir', " +
            "outputHostDir='$outputHostDir', ffmpegPath='$ffmpegPath', pollIntervalSeconds=$pollIntervalSeconds, " +
            "egressTimeoutMinutes=$egressTimeoutMinutes, maxDurationMinutes=$maxDurationMinutes, " +
            "composeTimeoutMinutes=$composeTimeoutMinutes, maxTracks=$maxTracks, keepRaw=$keepRaw)"

    companion object {
        private const val DEFAULT_OUTPUT_CONTAINER_DIR = "/out"
        private const val DEFAULT_OUTPUT_HOST_DIR = "deploy/local/egress-out"
        private const val DEFAULT_FFMPEG_PATH = "ffmpeg"
        private const val DEFAULT_POLL_INTERVAL_SECONDS = 10L
        private const val DEFAULT_EGRESS_TIMEOUT_MINUTES = 30L
        private const val DEFAULT_MAX_DURATION_MINUTES = 240L
        private const val DEFAULT_COMPOSE_TIMEOUT_MINUTES = 120L
        private const val DEFAULT_MAX_TRACKS = 60

        /**
         * Reads `LAPIS_RECORDING_ENABLED`/`LAPIS_EGRESS_OUTPUT_CONTAINER_DIR`/
         * `LAPIS_EGRESS_OUTPUT_HOST_DIR`/`LAPIS_FFMPEG_PATH`/`LAPIS_RECORDING_POLL_INTERVAL_SECONDS`/
         * `LAPIS_RECORDING_EGRESS_TIMEOUT_MINUTES`/`LAPIS_RECORDING_MAX_DURATION_MINUTES`/
         * `LAPIS_RECORDING_COMPOSE_TIMEOUT_MINUTES`/`LAPIS_RECORDING_MAX_TRACKS`/
         * `LAPIS_RECORDING_KEEP_RAW` via [env] (defaults to [System.getenv]). Pure string parsing,
         * no I/O -- see class KDoc. Unparseable numeric values silently fall back to their default
         * rather than throwing (same "degrade to a sane default, never crash config load over a
         * malformed number" posture [ConferenceConfig.load]'s own TTL/max-participants parsing
         * already establishes).
         */
        fun load(env: (String) -> String? = System::getenv): ConferenceRecordingConfig =
            ConferenceRecordingConfig(
                enabled = env("LAPIS_RECORDING_ENABLED")?.trim().equals("true", ignoreCase = true),
                outputContainerDir = env("LAPIS_EGRESS_OUTPUT_CONTAINER_DIR")?.trim().orEmpty().ifBlank { DEFAULT_OUTPUT_CONTAINER_DIR },
                outputHostDir = env("LAPIS_EGRESS_OUTPUT_HOST_DIR")?.trim().orEmpty().ifBlank { DEFAULT_OUTPUT_HOST_DIR },
                ffmpegPath = env("LAPIS_FFMPEG_PATH")?.trim().orEmpty().ifBlank { DEFAULT_FFMPEG_PATH },
                pollIntervalSeconds = env("LAPIS_RECORDING_POLL_INTERVAL_SECONDS")?.trim()?.toLongOrNull() ?: DEFAULT_POLL_INTERVAL_SECONDS,
                egressTimeoutMinutes =
                    env("LAPIS_RECORDING_EGRESS_TIMEOUT_MINUTES")?.trim()?.toLongOrNull() ?: DEFAULT_EGRESS_TIMEOUT_MINUTES,
                maxDurationMinutes = env("LAPIS_RECORDING_MAX_DURATION_MINUTES")?.trim()?.toLongOrNull() ?: DEFAULT_MAX_DURATION_MINUTES,
                composeTimeoutMinutes =
                    env("LAPIS_RECORDING_COMPOSE_TIMEOUT_MINUTES")?.trim()?.toLongOrNull() ?: DEFAULT_COMPOSE_TIMEOUT_MINUTES,
                maxTracks = env("LAPIS_RECORDING_MAX_TRACKS")?.trim()?.toIntOrNull() ?: DEFAULT_MAX_TRACKS,
                keepRaw = env("LAPIS_RECORDING_KEEP_RAW")?.trim().equals("true", ignoreCase = true),
            )

        /**
         * The ONE-TIME I/O probe [network.lapis.cloud.server.Application.module] runs at startup
         * (never from [load], see class KDoc) -- attempts `<ffmpegPath> -version` with a short
         * timeout and reports whether it started and exited successfully. Never throws: any
         * [java.io.IOException] (binary not found, not executable) or timeout is caught and reported
         * as `false`, exactly like [network.lapis.cloud.server.postal.LetterxpressPostalMailProvider]'s
         * own "degrade honestly, never crash the server over an optional integration" posture. Logs
         * a single WARN on failure so an operator sees why `ffmpegAvailable=false` shows up in
         * [network.lapis.cloud.shared.domain.ConferenceRecordingAvailabilityDto] without needing to
         * enable debug logging.
         */
        fun probeFfmpegAvailable(ffmpegPath: String): Boolean =
            try {
                val process = ProcessBuilder(ffmpegPath, "-version").redirectErrorStream(true).start()
                val finished = process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)
                if (!finished) {
                    process.destroyForcibly()
                    logger.warn { "ffmpeg -version did not complete within 5s ($ffmpegPath) -- recording composition will be unavailable" }
                    false
                } else {
                    val exitedCleanly = process.exitValue() == 0
                    if (!exitedCleanly) {
                        logger.warn {
                            "ffmpeg -version exited with ${process.exitValue()} ($ffmpegPath) -- recording composition will be unavailable"
                        }
                    }
                    exitedCleanly
                }
            } catch (e: Exception) {
                logger.warn {
                    "ffmpeg probe failed (${e::class.simpleName ?: "unknown error"}, path='$ffmpegPath') -- recording composition will be unavailable"
                }
                false
            }
    }
}
