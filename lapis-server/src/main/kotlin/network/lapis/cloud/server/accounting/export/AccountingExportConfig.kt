package network.lapis.cloud.server.accounting.export

import io.github.oshai.kotlinlogging.KotlinLogging
import network.lapis.cloud.server.crypto.SecretBox
import java.util.Base64

private val logger = KotlinLogging.logger {}

/**
 * Welle V1.4.5.3 "lexoffice-Live-Anbindung" -- deliberately NOT fail-fast the way
 * `network.lapis.cloud.server.webhook.WebhookConfig`/`ConferenceStreamingConfig` are for their own
 * `LAPIS_SECRET_ENCRYPTION_KEY` gate. This feature is entirely per-organization opt-in through the
 * UI itself (`setToken`) -- there is no server-wide "feature flag" an operator flips, only a
 * per-connection token a TREASURER stores. A missing/malformed encryption key therefore does not
 * crash the whole server; it only makes [network.lapis.cloud.server.rpc.AccountingExportService
 * .setToken] itself unusable (a [network.lapis.cloud.shared.rpc.ConflictException] with a clear
 * message), same posture [network.lapis.cloud.server.payment.sepa.SepaConfig]/
 * `BankStatementImportService` already take for the SAME shared env var.
 *
 * **No user/operator-configurable target URL.** Unlike [network.lapis.cloud.server.payment.psp
 * .PspConfig] (which allows `LAPIS_STRIPE_API_BASE_URL` with validation), this config carries NO
 * base-URL field at all -- [network.lapis.cloud.server.accounting.export.lexoffice
 * .LEXOFFICE_API_BASE_URL] is a hardcoded constant, overridable only as a test constructor
 * parameter. See that constant's own KDoc for why this is the deliberately stronger SSRF posture.
 */
data class AccountingExportConfig(
    val enabled: Boolean,
    val pollIntervalSeconds: Long,
    val secretEncryptionKey: ByteArray?,
) {
    override fun toString(): String {
        val keyState = if (secretEncryptionKey == null) "<unset>" else "<redacted, ${secretEncryptionKey.size} bytes>"
        return "AccountingExportConfig(enabled=$enabled, pollIntervalSeconds=$pollIntervalSeconds, secretEncryptionKey=$keyState)"
    }

    companion object {
        private const val DEFAULT_POLL_INTERVAL_SECONDS = 2L
        private const val MIN_POLL_INTERVAL_SECONDS = 1L
        private const val MAX_POLL_INTERVAL_SECONDS = 60L

        fun load(env: (String) -> String? = System::getenv): AccountingExportConfig {
            val enabled = env("LAPIS_ACCOUNTING_EXPORT_ENABLED")?.trim()?.equals("false", ignoreCase = true) != true

            val rawKey = env("LAPIS_SECRET_ENCRYPTION_KEY")?.trim().orEmpty()
            val decodedKey =
                if (rawKey.isBlank()) {
                    null
                } else {
                    val decoded = runCatching { Base64.getDecoder().decode(rawKey) }.getOrNull()
                    when {
                        decoded == null -> {
                            logger.warn {
                                "LAPIS_SECRET_ENCRYPTION_KEY is set but is not valid base64 -- lexoffice token storage stays unusable."
                            }
                            null
                        }
                        decoded.size != SecretBox.KEY_SIZE_BYTES -> {
                            logger.warn {
                                "LAPIS_SECRET_ENCRYPTION_KEY decodes to ${decoded.size} bytes, expected " +
                                    "${SecretBox.KEY_SIZE_BYTES} -- lexoffice token storage stays unusable."
                            }
                            null
                        }
                        else -> decoded
                    }
                }

            return AccountingExportConfig(
                enabled = enabled,
                pollIntervalSeconds =
                    (env("LAPIS_ACCOUNTING_EXPORT_POLL_INTERVAL_SECONDS")?.trim()?.toLongOrNull() ?: DEFAULT_POLL_INTERVAL_SECONDS)
                        .coerceIn(MIN_POLL_INTERVAL_SECONDS, MAX_POLL_INTERVAL_SECONDS),
                secretEncryptionKey = decodedKey,
            )
        }
    }
}
