package network.lapis.cloud.server.payment.psp

import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Welle V1.2.8 "PSP-Checkout (Stripe)" (GitHub Issue #6) -- three-way state, exact mirror of
 * `network.lapis.cloud.server.mail.SmtpConfigState`'s own shape (see that KDoc for the full
 * "opted in but broken must never silently degrade" rationale, one step further split into
 * [NotConfigured] and [Incomplete]).
 */
sealed interface PspConfigState {
    /** No `LAPIS_STRIPE_*` variable is set at all -- the honest, disclosed "no PSP" posture. */
    data object NotConfigured : PspConfigState

    data class Configured(
        val config: PspConfig,
    ) : PspConfigState

    /**
     * At least one `LAPIS_STRIPE_*` variable is set, but the configuration is missing required
     * values or contains an invalid one. There is deliberately no separate `LAPIS_PSP_ENABLED` flag
     * -- the mere presence of any `LAPIS_STRIPE_*` variable IS the opt-in signal (same discipline as
     * `SmtpConfig`), so a half configuration is unambiguously an operator error, never a legitimate
     * intermediate state.
     */
    data class Incomplete(
        val missing: List<String>,
        val invalid: List<String>,
    ) : PspConfigState
}

/**
 * Deployment-supplied Stripe transport credentials/settings (Welle V1.2.8, GitHub Issue #6).
 * Follows `network.lapis.cloud.server.mail.SmtpConfig` **exactly**: a private constructor, a [load]
 * factory taking an injectable `env` lambda (`System.getenv` cannot be mutated per test), and a
 * redacting [toString].
 *
 * **Never persisted, never encrypted-at-rest.** Unlike `ConferenceStreamingConfig`'s stream-
 * destination credentials (which live in the database and therefore need `SecretBox`), the Stripe
 * secret key and webhook signing secret here come EXCLUSIVELY from the environment and are held
 * only in this in-memory config object for the lifetime of the process -- no
 * `LAPIS_SECRET_ENCRYPTION_KEY` involvement. The Stripe key is a single org-wide deployment
 * credential, not per-row data (`SmtpConfig`'s own resolution of this exact same question, see that
 * class's KDoc "Never persisted, never encrypted-at-rest").
 *
 * **Fail-fast, not graceful degradation** -- see [PspStartupCheck] for the full reasoning: the
 * opt-in signal here is the presence of ANY `LAPIS_STRIPE_*` variable (an env var, not a DB flag),
 * so an operator who set some-but-not-all of them almost certainly made a typo, not a deliberate
 * half-configuration.
 *
 * **Read location: [load] and nowhere else.** No other file in this codebase may call
 * `System.getenv` for a PSP value.
 */
class PspConfig private constructor(
    /** `LAPIS_STRIPE_SECRET_KEY` -- `sk_live_...`/`sk_test_...`. Never logged, never in [toString], never in a DTO or exception message. */
    val secretKey: String,
    /** `LAPIS_STRIPE_WEBHOOK_SIGNING_SECRET` -- `whsec_...`. Same discipline as [secretKey]. */
    val webhookSigningSecret: String,
    /** `LAPIS_STRIPE_API_BASE_URL`, default [DEFAULT_API_BASE_URL]. Overridable only for tests -- see [load]'s validation. */
    val apiBaseUrl: String,
    /** `LAPIS_PSP_WEBHOOK_TOLERANCE_SECONDS`, default [DEFAULT_WEBHOOK_TOLERANCE_SECONDS], clamped to [MIN_WEBHOOK_TOLERANCE_SECONDS]..[MAX_WEBHOOK_TOLERANCE_SECONDS]. */
    val webhookToleranceSeconds: Long,
    /** `LAPIS_PSP_MAX_CHECKOUT_AMOUNT_EUR`, default [DEFAULT_MAX_CHECKOUT_AMOUNT_EUR], clamped to [MIN_MAX_CHECKOUT_AMOUNT_EUR]..[MAX_MAX_CHECKOUT_AMOUNT_EUR]. Abuse/DoS cap on `createDonationCheckout`. */
    val maxCheckoutAmountEur: BigDecimal,
    /** `LAPIS_PSP_CHECKOUT_TTL_MINUTES`, default [DEFAULT_CHECKOUT_TTL_MINUTES], clamped to [MIN_CHECKOUT_TTL_MINUTES]..[MAX_CHECKOUT_TTL_MINUTES]. Drives `payment_checkout_session.expires_at` and Stripe's own `expires_at`. */
    val checkoutTtlMinutes: Long,
) {
    /** Redacts [secretKey]/[webhookSigningSecret] -- everything else is operationally useful to see in a startup log and carries no secret. */
    override fun toString(): String =
        "PspConfig(secretKey=<redacted>, webhookSigningSecret=<redacted>, apiBaseUrl=$apiBaseUrl, " +
            "webhookToleranceSeconds=$webhookToleranceSeconds, maxCheckoutAmountEur=$maxCheckoutAmountEur, " +
            "checkoutTtlMinutes=$checkoutTtlMinutes)"

    companion object {
        const val ENV_SECRET_KEY = "LAPIS_STRIPE_SECRET_KEY"
        const val ENV_WEBHOOK_SIGNING_SECRET = "LAPIS_STRIPE_WEBHOOK_SIGNING_SECRET"
        const val ENV_API_BASE_URL = "LAPIS_STRIPE_API_BASE_URL"
        const val ENV_WEBHOOK_TOLERANCE_SECONDS = "LAPIS_PSP_WEBHOOK_TOLERANCE_SECONDS"
        const val ENV_MAX_CHECKOUT_AMOUNT_EUR = "LAPIS_PSP_MAX_CHECKOUT_AMOUNT_EUR"
        const val ENV_CHECKOUT_TTL_MINUTES = "LAPIS_PSP_CHECKOUT_TTL_MINUTES"

        const val DEFAULT_API_BASE_URL = "https://api.stripe.com"
        const val DEFAULT_WEBHOOK_TOLERANCE_SECONDS = 300L
        const val MIN_WEBHOOK_TOLERANCE_SECONDS = 60L
        const val MAX_WEBHOOK_TOLERANCE_SECONDS = 900L

        val DEFAULT_MAX_CHECKOUT_AMOUNT_EUR: BigDecimal = BigDecimal("10000.00")
        val MIN_MAX_CHECKOUT_AMOUNT_EUR: BigDecimal = BigDecimal("1.00")
        val MAX_MAX_CHECKOUT_AMOUNT_EUR: BigDecimal = BigDecimal("1000000.00")

        const val DEFAULT_CHECKOUT_TTL_MINUTES = 60L
        const val MIN_CHECKOUT_TTL_MINUTES = 10L
        const val MAX_CHECKOUT_TTL_MINUTES = 1440L

        private val ALL_ENV_KEYS =
            listOf(
                ENV_SECRET_KEY,
                ENV_WEBHOOK_SIGNING_SECRET,
                ENV_API_BASE_URL,
                ENV_WEBHOOK_TOLERANCE_SECONDS,
                ENV_MAX_CHECKOUT_AMOUNT_EUR,
                ENV_CHECKOUT_TTL_MINUTES,
            )

        /**
         * Pure string validation ONLY -- no network, no I/O of any kind (same "`./gradlew clean
         * check` never needs a running Stripe account" posture every other `*Config.load` in this
         * codebase establishes). Never throws -- see [PspConfigState.Incomplete] for how a broken
         * configuration is reported instead.
         */
        fun load(env: (String) -> String? = System::getenv): PspConfigState {
            fun value(key: String): String? = env(key)?.trim()?.takeUnless { it.isBlank() }

            val anySet = ALL_ENV_KEYS.any { value(it) != null }
            if (!anySet) return PspConfigState.NotConfigured

            val secretKey = value(ENV_SECRET_KEY)
            val webhookSigningSecret = value(ENV_WEBHOOK_SIGNING_SECRET)

            val missing = mutableListOf<String>()
            val invalid = mutableListOf<String>()
            if (secretKey == null) {
                missing += ENV_SECRET_KEY
            } else if (!secretKey.startsWith("sk_")) {
                invalid += ENV_SECRET_KEY
            }
            if (webhookSigningSecret == null) {
                missing += ENV_WEBHOOK_SIGNING_SECRET
            } else if (!webhookSigningSecret.startsWith("whsec_")) {
                invalid += ENV_WEBHOOK_SIGNING_SECRET
            }

            val rawApiBaseUrl = value(ENV_API_BASE_URL)
            val apiBaseUrl =
                when {
                    rawApiBaseUrl == null -> DEFAULT_API_BASE_URL
                    isAcceptableApiBaseUrl(rawApiBaseUrl) -> rawApiBaseUrl
                    else -> {
                        invalid += ENV_API_BASE_URL
                        DEFAULT_API_BASE_URL
                    }
                }

            // Numbers degrade to a sane default rather than fail-fast on garbage -- same
            // "degrade to a sane default for a number, throw for a key" posture
            // `ConferenceRecordingConfig.load` already establishes.
            val webhookToleranceSeconds =
                (value(ENV_WEBHOOK_TOLERANCE_SECONDS)?.toLongOrNull() ?: DEFAULT_WEBHOOK_TOLERANCE_SECONDS)
                    .coerceIn(minimumValue = MIN_WEBHOOK_TOLERANCE_SECONDS, maximumValue = MAX_WEBHOOK_TOLERANCE_SECONDS)
            val maxCheckoutAmountEur =
                (value(ENV_MAX_CHECKOUT_AMOUNT_EUR)?.toBigDecimalOrNull() ?: DEFAULT_MAX_CHECKOUT_AMOUNT_EUR)
                    .coerceIn(minimumValue = MIN_MAX_CHECKOUT_AMOUNT_EUR, maximumValue = MAX_MAX_CHECKOUT_AMOUNT_EUR)
                    .setScale(2, RoundingMode.HALF_EVEN)
            val checkoutTtlMinutes =
                (value(ENV_CHECKOUT_TTL_MINUTES)?.toLongOrNull() ?: DEFAULT_CHECKOUT_TTL_MINUTES)
                    .coerceIn(minimumValue = MIN_CHECKOUT_TTL_MINUTES, maximumValue = MAX_CHECKOUT_TTL_MINUTES)

            if (missing.isNotEmpty() || invalid.isNotEmpty()) {
                return PspConfigState.Incomplete(missing = missing, invalid = invalid)
            }

            return PspConfigState.Configured(
                config =
                    PspConfig(
                        secretKey = requireNotNull(secretKey),
                        webhookSigningSecret = requireNotNull(webhookSigningSecret),
                        apiBaseUrl = apiBaseUrl,
                        webhookToleranceSeconds = webhookToleranceSeconds,
                        maxCheckoutAmountEur = maxCheckoutAmountEur,
                        checkoutTtlMinutes = checkoutTtlMinutes,
                    ),
            )
        }

        /**
         * `https://...` is always accepted. A loopback `http://127.0.0.1...`/`http://localhost...`
         * is accepted ONLY as a test-only escape so `StripeCheckoutClientTest` can point at a
         * `ktor-client-mock` engine -- see [PspConfig] KDoc. Everything else (a plain `http://` to a
         * real host) is rejected: an SSRF/downgrade guard, enforced at [load] time so the loophole
         * cannot be reached in a production deployment without an explicit, clearly-test-shaped env
         * value.
         */
        private fun isAcceptableApiBaseUrl(url: String): Boolean =
            url.startsWith("https://") || url.startsWith("http://127.0.0.1") || url.startsWith("http://localhost")

        private fun String.toBigDecimalOrNull(): BigDecimal? = runCatching { BigDecimal(this) }.getOrNull()
    }
}
