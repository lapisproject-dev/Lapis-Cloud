package network.lapis.cloud.server.mail.newsletter

import network.lapis.cloud.shared.domain.MailingDeliveryMode
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * Welle V1.9.7 "SuperMailer" -- `LAPIS_MAILING_DELIVERY` (`log` default, or `smtp`) decides whether
 * [MailingDeliveryWorker] actually calls `network.lapis.cloud.server.mail.MailTransport.send` for a
 * mailing-list message, or only runs the full pipeline (sanitize/render/write delivery-log rows)
 * without a real transport call. Mirrors `network.lapis.cloud.server.mail.SmtpConfig`'s own
 * "pure string validation only, no I/O" posture -- [load] never touches the network.
 *
 * **Fail-fast, not graceful degradation, for the invalid-value and `smtp`-without-SMTP cases** --
 * same "opt-in but broken must never silently degrade" posture `SmtpConfigState.Incomplete`/
 * `SmtpStartupCheck` already establish for the transactional mailers: an operator who explicitly
 * asked for real bulk mail delivery and got silent `LOG`-mode behaviour instead would have no way
 * to notice short of reading server logs after the fact. `Application.module()` calls [load] and
 * crashes at startup (`IllegalStateException`) rather than starting in a mode nobody asked for.
 */
object MailingDeliveryConfig {
    const val ENV_KEY = "LAPIS_MAILING_DELIVERY"
    const val ENV_KEY_SEND_DELAY_MS = "LAPIS_MAILING_SEND_DELAY_MS"
    private const val VALUE_LOG = "log"
    private const val VALUE_SMTP = "smtp"
    private const val DEFAULT_SEND_DELAY_MS = 250L

    /**
     * Pure string validation ONLY -- no I/O. Returns the configured [MailingDeliveryMode], or
     * throws [IllegalStateException] for an unrecognized value. Never returns anything for `smtp`
     * unless the caller separately verifies real SMTP is actually configured -- see
     * `Application.module()`'s call site, which checks `SmtpConfigState` immediately after.
     */
    fun load(env: (String) -> String? = System::getenv): MailingDeliveryMode {
        val raw = env(ENV_KEY)?.trim()?.lowercase()?.takeUnless { it.isBlank() } ?: VALUE_LOG
        return when (raw) {
            VALUE_LOG -> MailingDeliveryMode.LOG
            VALUE_SMTP -> MailingDeliveryMode.SMTP
            else ->
                throw IllegalStateException(
                    "$ENV_KEY has an invalid value '$raw' -- must be '$VALUE_LOG' (default) or '$VALUE_SMTP'.",
                )
        }
    }

    /**
     * `LAPIS_MAILING_SEND_DELAY_MS`, default [DEFAULT_SEND_DELAY_MS] -- the pause
     * [MailingDeliveryWorker] takes between two recipients of the same bulk send, a deliberate
     * throttle against the configured SMTP relay's own rate limits (plan Q5 -- the concrete limit
     * for the netcup relay is an open operational question, this default is a conservative
     * placeholder). Fails fast on a non-numeric or negative value, same posture as [load].
     */
    fun loadSendDelay(env: (String) -> String? = System::getenv): Duration {
        val raw = env(ENV_KEY_SEND_DELAY_MS)?.trim()?.takeUnless { it.isBlank() } ?: return DEFAULT_SEND_DELAY_MS.milliseconds
        val millis = raw.toLongOrNull()
        if (millis == null || millis < 0) {
            throw IllegalStateException(
                "$ENV_KEY_SEND_DELAY_MS has an invalid value '$raw' -- must be a non-negative number of milliseconds.",
            )
        }
        return millis.milliseconds
    }
}
