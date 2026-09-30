package network.lapis.cloud.server.mail.newsletter

import io.github.oshai.kotlinlogging.KotlinLogging
import network.lapis.cloud.shared.domain.MailingDeliveryMode
import java.security.SecureRandom
import java.util.Base64

private val logger = KotlinLogging.logger {}

/**
 * Welle V1.9.15 -- `LAPIS_MAILING_TRACKING_KEY`, the HMAC key [MailingTrackingToken] signs
 * click/open tokens with. Pure validation, no I/O -- same posture as [MailingDeliveryConfig].
 *
 * **Fail-fast for real delivery**: in [MailingDeliveryMode.SMTP] a missing/short/degenerate key
 * throws [IllegalStateException] at startup -- an operator who asked for real bulk mail must never
 * silently get mails whose tracking links cannot be verified. In [MailingDeliveryMode.LOG] (nothing
 * leaves the server) a missing key falls back to an ephemeral `SecureRandom` key with a warning.
 *
 * **Key rotation invalidates every already-sent tracking link** (they answer 404 afterwards).
 * The key value is never written to an exception message or a log line.
 */
object MailingTrackingConfig {
    const val ENV_KEY = "LAPIS_MAILING_TRACKING_KEY"
    const val MIN_KEY_BYTES = 32

    fun loadKey(
        mode: MailingDeliveryMode,
        env: (String) -> String? = System::getenv,
    ): ByteArray {
        val raw = env(ENV_KEY)?.trim()?.takeUnless { it.isBlank() }
        if (raw == null) {
            if (mode == MailingDeliveryMode.SMTP) {
                throw IllegalStateException(
                    "$ENV_KEY is required when LAPIS_MAILING_DELIVERY=smtp (generate one with: openssl rand -base64 32).",
                )
            }
            logger.warn { "$ENV_KEY is not set -- using an ephemeral key; tracking links do not survive a restart (LOG mode only)." }
            return ByteArray(MIN_KEY_BYTES).also { SecureRandom().nextBytes(it) }
        }
        val decoded =
            decodeBase64(raw)
                ?: throw IllegalStateException("$ENV_KEY is not valid base64.")
        if (decoded.size < MIN_KEY_BYTES) {
            throw IllegalStateException("$ENV_KEY must decode to at least $MIN_KEY_BYTES bytes.")
        }
        if (decoded.all { it == 0.toByte() } || decoded.all { it == 0xFF.toByte() }) {
            throw IllegalStateException("$ENV_KEY must not be an all-zero or all-ones value.")
        }
        return decoded
    }

    private fun decodeBase64(raw: String): ByteArray? =
        runCatching { Base64.getDecoder().decode(raw) }.getOrNull()
            ?: runCatching { Base64.getUrlDecoder().decode(raw) }.getOrNull()
}
