package network.lapis.cloud.server.payment.psp

import io.github.oshai.kotlinlogging.KotlinLogging

private val logger = KotlinLogging.logger {}

/**
 * Welle V1.2.8 "PSP-Checkout (Stripe)" (GitHub Issue #6) -- startup-time verification of
 * [PspConfigState], exact mirror of `network.lapis.cloud.server.mail.SmtpStartupCheck` (see that
 * class's KDoc "Fail-fast or only loud" for the full reasoning this reuses unchanged: the presence
 * of ANY `LAPIS_STRIPE_*` variable IS the opt-in, so [PspConfigState.Incomplete] can only mean a
 * genuine misconfiguration, never a legitimate half-state -- a silently-degrading payment gateway is
 * exactly the "silent failure" this feature must not have).
 */
object PspStartupCheck {
    /**
     * Logs an informational message for [PspConfigState.NotConfigured]/[PspConfigState.Configured],
     * and throws [IllegalStateException] for [PspConfigState.Incomplete]. The thrown message names
     * every missing/invalid variable by NAME ONLY -- never a value, matching every other fail-fast
     * check in this codebase (see `ConferenceStreamingConfig.load`/`SmtpStartupCheck`).
     */
    fun verifyAndLog(state: PspConfigState) {
        when (state) {
            is PspConfigState.NotConfigured ->
                logger.info {
                    "Kein Zahlungsdienstleister konfiguriert (LAPIS_STRIPE_* unset) -- Online-Zahlung " +
                        "von Beiträgen/Spenden ist nicht verfügbar, bis ein ADMIN sowohl die Umgebungsvariablen " +
                        "als auch das Zahlungs-Gate aktiviert."
                }

            is PspConfigState.Configured ->
                logger.info {
                    "Stripe-Checkout-Transport aktiv: apiBaseUrl=${state.config.apiBaseUrl} " +
                        "webhookToleranceSeconds=${state.config.webhookToleranceSeconds} " +
                        "maxCheckoutAmountEur=${state.config.maxCheckoutAmountEur} " +
                        "checkoutTtlMinutes=${state.config.checkoutTtlMinutes} " +
                        "(Secret Key/Webhook Signing Secret redigiert)."
                }

            is PspConfigState.Incomplete -> {
                val missingPart = if (state.missing.isEmpty()) "" else "fehlend: ${state.missing.joinToString(", ")}"
                val invalidPart = if (state.invalid.isEmpty()) "" else "ungültig: ${state.invalid.joinToString(", ")}"
                val detail = listOf(missingPart, invalidPart).filter { it.isNotBlank() }.joinToString("; ")
                error(
                    "PSP-Konfiguration ist unvollständig ($detail) -- mindestens eine LAPIS_STRIPE_*-" +
                        "Variable ist gesetzt, damit ist ein Zahlungsdienstleister als aktiviert zu " +
                        "betrachten, aber die Konfiguration ist nicht vollständig/gültig. Entweder BEIDE " +
                        "Pflichtwerte (LAPIS_STRIPE_SECRET_KEY/LAPIS_STRIPE_WEBHOOK_SIGNING_SECRET) setzen " +
                        "oder GAR KEINE LAPIS_STRIPE_*-Variable setzen. Siehe PspConfig KDoc.",
                )
            }
        }
    }
}
