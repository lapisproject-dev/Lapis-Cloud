package network.lapis.cloud.server.mail.outbox

import io.github.oshai.kotlinlogging.KotlinLogging
import network.lapis.cloud.server.crypto.SecretBox
import network.lapis.cloud.server.mail.MailBudgetConfig
import java.util.Base64

private val logger = KotlinLogging.logger {}

/** What the durable outbox needs: the at-rest cipher and the lookup-hash helper, both derived from `LAPIS_SECRET_ENCRYPTION_KEY`. */
class MailOutboxSetup(
    val secretBox: SecretBox,
    val lookupHasher: MailRecipientHasher,
) {
    override fun toString(): String = "MailOutboxSetup(<redacted>)"
}

/**
 * Welle V1.9.81 -- decides whether the durable outbox for system mails is wired, from three facts: is real SMTP configured, is an hourly
 * budget configured, and is there a valid `LAPIS_SECRET_ENCRYPTION_KEY` (the same variable, same validation as `WebhookConfig.load`).
 *
 * | SMTP | budget | key                | result |
 * |------|--------|--------------------|--------|
 * | no   | no     | any                | no outbox (no SMTP => nothing to deliver; NoOp transport writes no row) |
 * | no   | yes    | any                | fail fast -- a budget without a relay is a misconfiguration |
 * | yes  | no     | unset              | no outbox: today's in-memory path + a start WARN "durable queue inactive" |
 * | yes  | yes    | unset              | fail fast -- the budget makes mail wait, and waiting needs a durable queue |
 * | yes  | any    | set but invalid    | fail fast |
 * | yes  | any    | valid              | outbox |
 *
 * Messages name only the variable, never a value.
 */
object MailOutboxConfig {
    const val ENV_KEY = "LAPIS_SECRET_ENCRYPTION_KEY"

    fun load(
        smtpConfigured: Boolean,
        budgetEnabled: Boolean,
        env: (String) -> String? = System::getenv,
    ): MailOutboxSetup? {
        if (!smtpConfigured) {
            check(!budgetEnabled) { "${MailBudgetConfig.ENV_MAX} requires real SMTP configuration (LAPIS_SMTP_*) -- none is set." }
            return null
        }
        val raw = env(ENV_KEY)?.trim().orEmpty()
        if (raw.isEmpty()) {
            check(!budgetEnabled) {
                "${MailBudgetConfig.ENV_MAX} requires $ENV_KEY (the durable mail queue encrypts queued mail at rest) -- it is unset. " +
                    "Generate one with `openssl rand -base64 32`."
            }
            logger.warn {
                "Durable mail queue inactive: $ENV_KEY is not set. System mails are sent from memory as before (lost on a restart " +
                    "or when the relay is down). Set the key to enable the encrypted, persistent outbox."
            }
            return null
        }
        val decoded = runCatching { Base64.getDecoder().decode(raw) }.getOrNull()
        check(decoded != null) { "$ENV_KEY is set but is not valid base64." }
        check(decoded.size == SecretBox.KEY_SIZE_BYTES) {
            "$ENV_KEY must decode to exactly ${SecretBox.KEY_SIZE_BYTES} bytes (AES-256). Generate one with `openssl rand -base64 32`."
        }
        return MailOutboxSetup(secretBox = SecretBox(decoded), lookupHasher = MailRecipientHasher(decoded))
    }
}
