package network.lapis.cloud.server.webhook

import io.github.oshai.kotlinlogging.KotlinLogging
import network.lapis.cloud.server.crypto.SecretBox
import java.util.Base64

private val logger = KotlinLogging.logger {}

/**
 * Welle V1.3.2 "Webhooks" (ausgehend) -- opt-in configuration, same "DB/env-flag gated, never
 * fail-fast merely because the feature exists" posture as `network.lapis.cloud.server.payment
 * .dunning.DunningConfig`. [enabled] gates: `WebhookEventPublisher.install` (a `false` install is a
 * documented no-op, see that method's own KDoc), `WebhookDeliveryPoller.start` (a `false` `start()`
 * never launches the poll loop at all), and every `IWebhookService` WRITE method
 * (`network.lapis.cloud.server.rpc.WebhookService.requireEnabled`, called by `setWebhookUrl`/
 * `rotateWebhookSecret`/`reactivateWebhookEndpoint`/`sendWebhookTestEvent`) -- each throws
 * `ConflictException` rather than silently succeeding.
 *
 * **Review fix (S8-adjacent gap, found alongside the encryption-key check below)**: before this fix,
 * `WebhookService` read [enabled] NOWHERE -- `Application.kt` registered `IWebhookService`
 * unconditionally, and every write method's only real gate was `requireSecretBox()` (was
 * `secretBox != null`). Because `LAPIS_SECRET_ENCRYPTION_KEY` is shared with
 * `network.lapis.cloud.server.conference.ConferenceStreamingConfig` ("S8" below), an operator
 * running `LAPIS_STREAMING_ENABLED=true` (which requires that key) while deliberately leaving
 * `LAPIS_WEBHOOKS_ENABLED` unset got a webhook subsystem that LOOKED disabled (`enabled = false`,
 * poller never starts, no events are ever published) but still let a BOARD member configure an
 * endpoint AND fire a real outbound HTTPS POST via `sendWebhookTestEvent` -- persisting
 * `webhook_endpoint`/`webhook_delivery` rows nobody expected to exist. `removeWebhookUrl` and the
 * two read methods (`listWebhookEndpoints`/`listWebhookDeliveries`) are deliberately NOT gated --
 * cleaning up / inspecting rows left over from BEFORE the flag was flipped off must keep working
 * regardless of [enabled]'s current value.
 *
 * **S8 -- own fail-fast gate on `LAPIS_SECRET_ENCRYPTION_KEY`**: this variable is ALREADY validated
 * by `network.lapis.cloud.server.conference.ConferenceStreamingConfig.load` when
 * `LAPIS_STREAMING_ENABLED=true`, but that check does not run (and must not be relied upon) when
 * streaming is off. Mirrors [ConferenceStreamingConfig]'s own KDoc "Fail-fast on the encryption
 * key": if [enabled] is `true`, a missing/malformed/wrong-width key throws immediately at startup
 * rather than storing webhook secrets unencrypted or crashing on the first `setWebhookUrl` call.
 *
 * **O4 -- [allowInsecureHttp]**: intentionally has NO effect unless explicitly set to `"true"` --
 * production `docker-compose.yml` simply omits `LAPIS_WEBHOOKS_ALLOW_INSECURE` rather than setting
 * it to `"false"` (what is absent cannot be flipped on by accident). A WARN is logged at startup
 * when it IS set, so an operator who forgot to remove a development override notices in the logs.
 */
data class WebhookConfig(
    val enabled: Boolean,
    val allowInsecureHttp: Boolean,
    val pollIntervalSeconds: Long,
    val maxDeliveriesPerTick: Int,
    val maxConcurrentDeliveries: Int,
    val retentionDays: Int,
    val secretEncryptionKey: ByteArray?,
) {
    override fun toString(): String {
        val keyState = if (secretEncryptionKey == null) "<unset>" else "<redacted, ${secretEncryptionKey.size} bytes>"
        return "WebhookConfig(enabled=$enabled, allowInsecureHttp=$allowInsecureHttp, " +
            "pollIntervalSeconds=$pollIntervalSeconds, maxDeliveriesPerTick=$maxDeliveriesPerTick, " +
            "maxConcurrentDeliveries=$maxConcurrentDeliveries, retentionDays=$retentionDays, secretEncryptionKey=$keyState)"
    }

    companion object {
        private const val DEFAULT_POLL_INTERVAL_SECONDS = 10L
        private const val DEFAULT_MAX_DELIVERIES_PER_TICK = 50
        private const val DEFAULT_MAX_CONCURRENT_DELIVERIES = 4
        private const val DEFAULT_RETENTION_DAYS = 30

        /**
         * Floor for [pollIntervalSeconds]/[retentionDays] (review fix) -- unlike
         * [LAPIS_SECRET_ENCRYPTION_KEY], these two were taken from `env(...)?.toLongOrNull()`/
         * `toIntOrNull()` with NO lower-bound check at all. `LAPIS_WEBHOOK_POLL_INTERVAL_SECONDS=0`
         * (or negative) makes `delay(...)` in [WebhookDeliveryPoller]'s loop return immediately, so
         * `while (isActive)` spins with no pause, hammering the DB continuously.
         * `LAPIS_WEBHOOK_RETENTION_DAYS=0` makes every DELIVERED/FAILED/ABANDONED row eligible for
         * deletion on the very next tick, emptying the delivery log the UI is supposed to show for
         * 30 days (see [WebhookDeliveryPoller.runRetentionPhase]). `coerceAtLeast` rather than a
         * `check {}` fail-fast (unlike the encryption key): an operator typo here degrades to "one
         * poll a second"/"one day of retention" rather than refusing to start the whole server.
         */
        private const val MIN_POLL_INTERVAL_SECONDS = 1L
        private const val MIN_RETENTION_DAYS = 1

        fun load(env: (String) -> String? = System::getenv): WebhookConfig {
            val enabled = env("LAPIS_WEBHOOKS_ENABLED")?.trim().equals("true", ignoreCase = true)
            val allowInsecureRaw = env("LAPIS_WEBHOOKS_ALLOW_INSECURE")?.trim()
            if (allowInsecureRaw != null) {
                logger.warn {
                    "LAPIS_WEBHOOKS_ALLOW_INSECURE is set (value='$allowInsecureRaw') -- outbound webhook URLs " +
                        "using plain http:// will be accepted. This must never be set in production (see " +
                        "WebhookConfig KDoc \"O4\")."
                }
            }
            val allowInsecureHttp = allowInsecureRaw.equals("true", ignoreCase = true)

            val rawKey = env("LAPIS_SECRET_ENCRYPTION_KEY")?.trim().orEmpty()
            val decodedKey = if (rawKey.isBlank()) null else decodeBase64OrNull(rawKey)
            if (enabled) {
                check(rawKey.isNotBlank()) {
                    "LAPIS_WEBHOOKS_ENABLED=true but LAPIS_SECRET_ENCRYPTION_KEY is unset -- Welle V1.3.2 " +
                        "\"Webhooks\" cannot start without an at-rest encryption key for stored signature " +
                        "secrets (network.lapis.cloud.server.crypto.SecretBox). Generate one with " +
                        "`openssl rand -base64 32`, see WebhookConfig.load KDoc."
                }
                check(decodedKey != null) {
                    "LAPIS_SECRET_ENCRYPTION_KEY is set but is not valid base64 -- see WebhookConfig.load KDoc."
                }
                check(decodedKey.size == SecretBox.KEY_SIZE_BYTES) {
                    "LAPIS_SECRET_ENCRYPTION_KEY decodes to ${decodedKey.size} bytes -- AES-256-GCM " +
                        "(network.lapis.cloud.server.crypto.SecretBox) requires exactly " +
                        "${SecretBox.KEY_SIZE_BYTES} raw bytes. Generate one with `openssl rand -base64 32`, " +
                        "see WebhookConfig.load KDoc."
                }
            }

            return WebhookConfig(
                enabled = enabled,
                allowInsecureHttp = allowInsecureHttp,
                pollIntervalSeconds =
                    (env("LAPIS_WEBHOOK_POLL_INTERVAL_SECONDS")?.trim()?.toLongOrNull() ?: DEFAULT_POLL_INTERVAL_SECONDS)
                        .coerceAtLeast(MIN_POLL_INTERVAL_SECONDS),
                maxDeliveriesPerTick = DEFAULT_MAX_DELIVERIES_PER_TICK,
                maxConcurrentDeliveries = DEFAULT_MAX_CONCURRENT_DELIVERIES,
                retentionDays =
                    (env("LAPIS_WEBHOOK_RETENTION_DAYS")?.trim()?.toIntOrNull() ?: DEFAULT_RETENTION_DAYS)
                        .coerceAtLeast(MIN_RETENTION_DAYS),
                secretEncryptionKey = decodedKey,
            )
        }

        private fun decodeBase64OrNull(value: String): ByteArray? = runCatching { Base64.getDecoder().decode(value) }.getOrNull()
    }
}
