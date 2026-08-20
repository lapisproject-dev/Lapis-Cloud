package network.lapis.cloud.server.payment.sepa

import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.plus
import network.lapis.cloud.server.crypto.SecretBox
import java.util.Base64

/**
 * V1.2.2 "SEPA-Lastschriftmandate". Configuration for the SEPA direct-debit path.
 *
 * **The encryption key is NOT reinvented.** `ConferenceStreamingConfig.load`'s own KDoc says a later
 * wave should "add its OWN `LAPIS_..._ENABLED` gate but reuse THIS SAME `LAPIS_SECRET_ENCRYPTION_KEY`
 * and this exact validation shape, not mint a second key/env-var/class." This is exactly that: its own
 * poller switch, the SAME key, the SAME three-step validation (not blank -> decodes as base64 ->
 * exactly [SecretBox.KEY_SIZE_BYTES] bytes), an error message naming the fix (`openssl rand -base64 32`).
 *
 * **Fail-fast posture deliberately DIFFERENT from `ConferenceStreamingConfig`**: there, the feature is
 * gated by an env var; here it is gated by a DB flag (`organization_settings.sepa_debit_enabled`).
 * [load] therefore cannot know at startup whether SEPA is actually used, and does NOT throw --
 * [secretEncryptionKey] simply stays `null`. Instead, `SepaService` rejects every mandate-related
 * operation with a `ConflictException` naming `LAPIS_SECRET_ENCRYPTION_KEY` -- exactly the treatment
 * `ConferenceStreamingService` already applies for its own `secretBox == null`. **Never a silent
 * plaintext fallback.**
 */
class SepaConfig private constructor(
    /** `LAPIS_SEPA_POLLER_ENABLED`, default `false`. Allows the poller to be enabled on exactly ONE instance. */
    val pollerEnabled: Boolean,
    /** `LAPIS_SEPA_POLL_INTERVAL_SECONDS`, default 3600 (hourly -- every deadline is a calendar-day deadline). */
    val pollIntervalSeconds: Long,
    /** `LAPIS_SEPA_PAIN008_VERSION`, default [SepaPain008Writer.DEFAULT_VERSION]. Checked against `STRUCTURALLY_COMPATIBLE_VERSIONS`. */
    val pain008Version: String,
    /** Decoded raw bytes for [SecretBox]; `null` iff `LAPIS_SECRET_ENCRYPTION_KEY` is unset. Never logged, never in toString, never in a DTO. */
    val secretEncryptionKey: ByteArray?,
) {
    override fun toString(): String {
        val keyState = if (secretEncryptionKey == null) "<unset>" else "<redacted, ${secretEncryptionKey.size} bytes>"
        return "SepaConfig(pollerEnabled=$pollerEnabled, pollIntervalSeconds=$pollIntervalSeconds, " +
            "pain008Version=$pain008Version, secretEncryptionKey=$keyState)"
    }

    companion object {
        /** 36 months without use -> the mandate lapses. Researched legal status, not legal advice. */
        const val MANDATE_EXPIRY_MONTHS: Int = 36

        /** 8-week SEPA basic direct-debit return window -> 56 calendar days (E-9). */
        const val RETURN_WINDOW_DAYS: Int = 56

        private const val DEFAULT_POLL_INTERVAL_SECONDS = 3600L

        /**
         * Review Round 1 (2026-08-19, MINOR): a floor of 60s -- `LAPIS_SEPA_POLL_INTERVAL_SECONDS=0`
         * (or any small/negative value from a misconfigured deployment) would otherwise make
         * [SepaBatchPoller.start]'s `while (isActive) { tick(); delay(pollIntervalSeconds.seconds) }`
         * loop busy-spin. Same `.coerceIn`/`.coerceAtLeast` bounds-clamping discipline this codebase
         * already applies to caller-supplied `limit` parameters elsewhere (e.g. `listMandates`'
         * `limit.coerceIn(1, 200)` in `SepaService`).
         */
        private const val MIN_POLL_INTERVAL_SECONDS = 60L

        /**
         * The single place "when does a SEPA mandate expire from 36 months of non-use" is computed --
         * Review Round 1 (2026-08-19, M-5): previously duplicated inline in [SepaBatchPoller]'s Phase
         * A and in `SepaService.mandateRowToDto`, with a THIRD, synchronous re-check now added at
         * `SepaService.createDebitBatch`/`generateBatchFile` (see those functions' KDoc) precisely
         * because the poller alone is not a reliable enforcement point (it defaults to disabled).
         * [lastUsedAt] wins over [grantedAt] when present -- a mandate that has been debited at least
         * once resets its own 36-month clock from that collection, not from the original grant.
         */
        fun mandateExpiryDate(
            grantedAt: LocalDate,
            lastUsedAt: LocalDate?,
        ): LocalDate = (lastUsedAt ?: grantedAt).plus(MANDATE_EXPIRY_MONTHS, DateTimeUnit.MONTH)

        fun load(env: (String) -> String? = System::getenv): SepaConfig {
            val pollerEnabled = env("LAPIS_SEPA_POLLER_ENABLED")?.trim().equals("true", ignoreCase = true)
            val pollIntervalSeconds =
                (env("LAPIS_SEPA_POLL_INTERVAL_SECONDS")?.trim()?.toLongOrNull() ?: DEFAULT_POLL_INTERVAL_SECONDS)
                    .coerceAtLeast(MIN_POLL_INTERVAL_SECONDS)
            val pain008Version =
                env("LAPIS_SEPA_PAIN008_VERSION")?.trim().takeUnless { it.isNullOrBlank() } ?: SepaPain008Writer.DEFAULT_VERSION

            val rawKey = env("LAPIS_SECRET_ENCRYPTION_KEY")?.trim().orEmpty()
            val decodedKey =
                if (rawKey.isBlank()) {
                    null
                } else {
                    decodeBase64OrNull(rawKey)?.takeIf { it.size == SecretBox.KEY_SIZE_BYTES }
                }

            return SepaConfig(
                pollerEnabled = pollerEnabled,
                pollIntervalSeconds = pollIntervalSeconds,
                pain008Version = pain008Version,
                secretEncryptionKey = decodedKey,
            )
        }

        private fun decodeBase64OrNull(raw: String): ByteArray? =
            try {
                Base64.getDecoder().decode(raw)
            } catch (e: IllegalArgumentException) {
                null
            }
    }
}
