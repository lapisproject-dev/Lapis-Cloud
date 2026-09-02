package network.lapis.cloud.server.webhook

import io.github.oshai.kotlinlogging.KotlinLogging
import network.lapis.cloud.server.crypto.SecretBox
import java.util.Base64

private val logger = KotlinLogging.logger {}

/**
 * Welle V1.3.2 "Webhooks" (ausgehend) -- opt-in configuration, same "DB/env-flag gated, never
 * fail-fast merely because the feature exists" posture as `network.lapis.cloud.server.payment
 * .dunning.DunningConfig`. [enabled] gates EVERYTHING: `WebhookEventPublisher.install`,
 * `WebhookDeliveryPoller.start`, and every `IWebhookService` write -- with `enabled = false`, the
 * whole subsystem is a documented no-op (see `WebhookEventPublisher.publish` KDoc).
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
                    env("LAPIS_WEBHOOK_POLL_INTERVAL_SECONDS")?.trim()?.toLongOrNull() ?: DEFAULT_POLL_INTERVAL_SECONDS,
                maxDeliveriesPerTick = DEFAULT_MAX_DELIVERIES_PER_TICK,
                maxConcurrentDeliveries = DEFAULT_MAX_CONCURRENT_DELIVERIES,
                retentionDays = env("LAPIS_WEBHOOK_RETENTION_DAYS")?.trim()?.toIntOrNull() ?: DEFAULT_RETENTION_DAYS,
                secretEncryptionKey = decodedKey,
            )
        }

        private fun decodeBase64OrNull(value: String): ByteArray? = runCatching { Base64.getDecoder().decode(value) }.getOrNull()
    }
}
