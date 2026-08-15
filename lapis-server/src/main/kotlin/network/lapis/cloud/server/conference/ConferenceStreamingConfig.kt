package network.lapis.cloud.server.conference

import network.lapis.cloud.server.crypto.SecretBox
import java.util.Base64

/**
 * V1.0 Videokonferenzen (Kleinsitzung), Wave 3 "Externes Streaming" -- environment configuration
 * for the RTMP-composited-egress streaming pipeline this wave adds on top of Wave 1's
 * [ConferenceConfig] (LiveKit URL/API key/secret, reused as-is for minting egress admin tokens,
 * same as [network.lapis.cloud.server.conference.LiveKitEgressClient] already does for recording --
 * this class carries none of its own LiveKit credentials). Mirrors [ConferenceConfig]'s/
 * [ConferenceRecordingConfig]'s own "plain `LAPIS_`-prefixed env var, injected `(String) -> String?`
 * lookup, sane local default" shape -- see [ConferenceConfig] KDoc for why `env` is injected rather
 * than a bare `System.getenv` call (same testability reasoning: `System.getenv` cannot be mutated
 * per-JVM-test-run).
 *
 * **A third, independent availability gate** (see `IConferenceStreamingService` KDoc, a later wave
 * step, "A third independent availability gate" for the full three-axis comparison against
 * [ConferenceConfig]/[ConferenceRecordingConfig]): streaming needs [ConferenceConfig.enabled] +
 * THIS class's own [enabled] + a valid [secretEncryptionKey]. It does **not** need `ffmpeg`
 * ([ConferenceRecordingConfig.ffmpegPath]) -- there is no local composition, LiveKit's Egress
 * container does 100% of the encoding -- and does not need [ConferenceRecordingConfig.enabled]. A
 * deployment can legitimately stream without being able to record, and vice versa.
 *
 * **[load] is string validation ONLY -- no filesystem, no process, no network I/O** -- same
 * "`./gradlew clean check` never needs a running LiveKit/Egress container" posture
 * [ConferenceRecordingConfig] KDoc already establishes for its own `load`.
 *
 * **Fail-fast on the encryption key, the SAME three-way posture [ConferenceConfig.load] already
 * establishes for the LiveKit url/key/secret trio** (see that method's own KDoc "Startup
 * behaviour"), collapsed here to two ways since there is only one setting, not three:
 * 1. [LAPIS_STREAMING_ENABLED] unset/not `"true"`: [enabled] is `false`, no failure -- every
 *    existing test and `./gradlew clean check` run keeps passing with zero new env, matching every
 *    other optional-integration config in this codebase.
 * 2. [LAPIS_STREAMING_ENABLED] is `"true"` but `LAPIS_SECRET_ENCRYPTION_KEY` is unset, not valid
 *    base64, or does not decode to exactly [SecretBox.KEY_SIZE_BYTES] bytes: throws
 *    [IllegalStateException] naming the exact requirement -- **never a silent downgrade to
 *    plaintext credential storage**, and never an opaque
 *    [javax.crypto.spec.InvalidKeySpecException]-shaped failure surfacing on the first
 *    `createDestination` call instead of at startup.
 *
 * [secretEncryptionKey] is deliberately named generically, matching [SecretBox]'s own class KDoc
 * "later waves needing their own encrypted-at-rest credential ... reuse this class and its
 * key-loading convention" -- a later wave adding, say, SMTP password encryption is expected to add
 * its OWN `LAPIS_..._ENABLED` gate but reuse THIS SAME `LAPIS_SECRET_ENCRYPTION_KEY` and this
 * exact validation shape, not mint a second key/env-var/class.
 */
class ConferenceStreamingConfig private constructor(
    /** Master opt-in -- streaming additionally requires [ConferenceConfig.enabled] (a deployment cannot stream without also being able to confer at all). */
    val enabled: Boolean,
    /**
     * Decoded raw key bytes for [SecretBox] -- `null` iff `LAPIS_SECRET_ENCRYPTION_KEY` was unset
     * (only possible when [enabled] is `false`, see class KDoc "Fail-fast"; when [enabled] is
     * `true` this is guaranteed non-null and exactly [SecretBox.KEY_SIZE_BYTES] bytes, [load]
     * throws before returning otherwise). **Never logged, never included in [toString], never
     * placed in a DTO or exception message** -- same discipline [ConferenceConfig.apiSecret]
     * documents for the LiveKit secret.
     */
    val secretEncryptionKey: ByteArray?,
    /** `LAPIS_STREAM_MAX_DESTINATIONS` -- CPU/DoS guard on simultaneous RTMP destinations per stream, see `IConferenceStreamingService.startStream` KDoc (a later wave step). */
    val maxDestinations: Int,
    /** [network.lapis.cloud.server.conference.StreamPoller] (a later wave step) tick interval. */
    val pollIntervalSeconds: Long,
    /** Hard auto-stop ceiling from `conference_stream.started_at` -- disk/cost guard against a stream nobody ever stops. */
    val maxDurationMinutes: Long,
    /** A `STARTING` row with no `livekit_egress_id` past this is reconciled (cross-checking `ListEgress` first so a genuinely-started egress is adopted, not leaked) by `StreamPoller` (a later wave step) rather than left stuck forever. */
    val startupTimeoutSeconds: Long,
    /**
     * V1.0 Videokonferenzen, Wave 9 "Stream-Pause bei geheimen Abstimmungen" -- the hard ceiling
     * [network.lapis.cloud.server.conference.DefaultSecretBallotStreamGuard]'s own `StopEgress`-
     * confirmation poll (`ListEgress` every 500ms) waits before giving up and leaving a row in
     * [network.lapis.cloud.shared.domain.ConferenceStreamStatus.PAUSING] for
     * `network.lapis.cloud.server.conference.StreamPoller`'s own `PAUSING` handling (a later wave
     * step) to retry on its next tick. `LAPIS_STREAMING_PAUSE_VERIFY_TIMEOUT_SECONDS`, default 20s --
     * generous enough for LiveKit's own `StopEgress` (asynchronous, "requested" not "stopped") to
     * actually settle, short enough that a genuinely stuck ballot pause fails closed (see
     * [network.lapis.cloud.server.rpc.SecretBallotStreamLock.requireStreamQuiescedForBallot]) within
     * a caller-tolerable RPC round-trip rather than hanging indefinitely.
     */
    val pauseVerifyTimeoutSeconds: Long,
) {
    /** Deliberately omits [secretEncryptionKey] (see its own KDoc "Never logged") -- reports only whether a key is present, mirroring [ConferenceConfig.toString]'s own `<blank>`/`<redacted>` shape. */
    override fun toString(): String {
        val keyState = if (secretEncryptionKey == null) "<unset>" else "<redacted, ${secretEncryptionKey.size} bytes>"
        return "ConferenceStreamingConfig(enabled=$enabled, secretEncryptionKey=$keyState, " +
            "maxDestinations=$maxDestinations, pollIntervalSeconds=$pollIntervalSeconds, " +
            "maxDurationMinutes=$maxDurationMinutes, startupTimeoutSeconds=$startupTimeoutSeconds, " +
            "pauseVerifyTimeoutSeconds=$pauseVerifyTimeoutSeconds)"
    }

    companion object {
        private const val DEFAULT_MAX_DESTINATIONS = 3
        private const val DEFAULT_POLL_INTERVAL_SECONDS = 10L
        private const val DEFAULT_MAX_DURATION_MINUTES = 480L
        private const val DEFAULT_STARTUP_TIMEOUT_SECONDS = 60L
        private const val DEFAULT_PAUSE_VERIFY_TIMEOUT_SECONDS = 20L

        /**
         * Reads `LAPIS_STREAMING_ENABLED`/`LAPIS_SECRET_ENCRYPTION_KEY`/
         * `LAPIS_STREAM_MAX_DESTINATIONS`/`LAPIS_STREAM_POLL_INTERVAL_SECONDS`/
         * `LAPIS_STREAM_MAX_DURATION_MINUTES`/`LAPIS_STREAM_STARTUP_TIMEOUT_SECONDS`/
         * `LAPIS_STREAMING_PAUSE_VERIFY_TIMEOUT_SECONDS` via [env] (defaults to [System.getenv]). See
         * class KDoc "Fail-fast on the encryption key" for the exact validation this method
         * performs. Unparseable numeric values silently fall back to their default rather than
         * throwing -- same "degrade to a sane default, never crash config load over a malformed
         * number" posture [ConferenceRecordingConfig.load]'s own KDoc establishes (deliberately
         * different from the encryption-key check above, which DOES throw -- a malformed poll
         * interval degrades gracefully to a sane default, a malformed or missing encryption key
         * while streaming is enabled would silently store credentials with no encryption at all,
         * which this class treats as never acceptable).
         */
        fun load(env: (String) -> String? = System::getenv): ConferenceStreamingConfig {
            val enabled = env("LAPIS_STREAMING_ENABLED")?.trim().equals("true", ignoreCase = true)
            val rawKey = env("LAPIS_SECRET_ENCRYPTION_KEY")?.trim().orEmpty()
            val decodedKey = if (rawKey.isBlank()) null else decodeBase64OrNull(rawKey)

            if (enabled) {
                check(rawKey.isNotBlank()) {
                    "LAPIS_STREAMING_ENABLED=true but LAPIS_SECRET_ENCRYPTION_KEY is unset -- V1.0 " +
                        "Videokonferenzen Wave 3 \"Externes Streaming\" cannot start without an at-rest " +
                        "encryption key for stored stream credentials (network.lapis.cloud.server.crypto" +
                        ".SecretBox). Generate one with `openssl rand -base64 32`, see " +
                        "ConferenceStreamingConfig.load KDoc."
                }
                check(decodedKey != null) {
                    "LAPIS_SECRET_ENCRYPTION_KEY is set but is not valid base64 -- see " +
                        "ConferenceStreamingConfig.load KDoc."
                }
                check(decodedKey.size == SecretBox.KEY_SIZE_BYTES) {
                    "LAPIS_SECRET_ENCRYPTION_KEY decodes to ${decodedKey.size} bytes -- AES-256-GCM " +
                        "(network.lapis.cloud.server.crypto.SecretBox) requires exactly " +
                        "${SecretBox.KEY_SIZE_BYTES} raw bytes. Generate one with `openssl rand -base64 32`, " +
                        "see ConferenceStreamingConfig.load KDoc."
                }
            }

            return ConferenceStreamingConfig(
                enabled = enabled,
                secretEncryptionKey = decodedKey,
                maxDestinations = env("LAPIS_STREAM_MAX_DESTINATIONS")?.trim()?.toIntOrNull() ?: DEFAULT_MAX_DESTINATIONS,
                pollIntervalSeconds =
                    env("LAPIS_STREAM_POLL_INTERVAL_SECONDS")?.trim()?.toLongOrNull() ?: DEFAULT_POLL_INTERVAL_SECONDS,
                maxDurationMinutes =
                    env("LAPIS_STREAM_MAX_DURATION_MINUTES")?.trim()?.toLongOrNull() ?: DEFAULT_MAX_DURATION_MINUTES,
                startupTimeoutSeconds =
                    env("LAPIS_STREAM_STARTUP_TIMEOUT_SECONDS")?.trim()?.toLongOrNull() ?: DEFAULT_STARTUP_TIMEOUT_SECONDS,
                pauseVerifyTimeoutSeconds =
                    env("LAPIS_STREAMING_PAUSE_VERIFY_TIMEOUT_SECONDS")?.trim()?.toLongOrNull() ?: DEFAULT_PAUSE_VERIFY_TIMEOUT_SECONDS,
            )
        }

        /** `null` on ANY decode failure (malformed base64) -- never throws, [load] turns a `null` result into the fail-fast [IllegalStateException] itself only when [enabled] is `true`. */
        private fun decodeBase64OrNull(raw: String): ByteArray? =
            try {
                Base64.getDecoder().decode(raw)
            } catch (e: IllegalArgumentException) {
                null
            }
    }
}
