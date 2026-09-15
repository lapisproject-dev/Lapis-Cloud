package network.lapis.cloud.server.payment.psp

import io.github.oshai.kotlinlogging.KotlinLogging

private val logger = KotlinLogging.logger {}

/**
 * Welle V1.2.8b "PayPal-Anbindung" (GitHub Issue #6) -- exakter Spiegel von [PspStartupCheck], NUR
 * für [PaypalConfigState]. Siehe dessen KDoc "Fail-fast or only loud" für die volle Begründung, die
 * hier unverändert übernommen wird: die Präsenz IRGENDEINER `LAPIS_PAYPAL_*`-Variable IST das
 * Opt-in, also kann [PaypalConfigState.Incomplete] nur eine echte Fehlkonfiguration bedeuten.
 */
object PaypalStartupCheck {
    /**
     * Loggt eine Info-Meldung für [PaypalConfigState.NotConfigured]/[PaypalConfigState.Configured],
     * und wirft [IllegalStateException] für [PaypalConfigState.Incomplete]. Die geworfene Meldung
     * nennt jede fehlende/ungültige Variable NUR BEIM NAMEN -- niemals einen Wert.
     */
    fun verifyAndLog(state: PaypalConfigState) {
        when (state) {
            is PaypalConfigState.NotConfigured ->
                logger.info {
                    "Kein PayPal-Zahlungsdienstleister konfiguriert (LAPIS_PAYPAL_* unset) -- PayPal-Checkout " +
                        "ist nicht verfügbar, bis ein ADMIN sowohl die Umgebungsvariablen als auch das " +
                        "Zahlungs-Gate mit provider=PAYPAL aktiviert."
                }

            is PaypalConfigState.Configured ->
                logger.info {
                    "PayPal-Checkout-Transport aktiv: apiBaseUrl=${state.config.apiBaseUrl} " +
                        "webhookToleranceSeconds=${state.config.webhookToleranceSeconds} " +
                        "maxCheckoutAmountEur=${state.config.maxCheckoutAmountEur} " +
                        "checkoutTtlMinutes=${state.config.checkoutTtlMinutes} " +
                        "(Client-Id/Client-Secret/Webhook-Id redigiert)."
                }

            is PaypalConfigState.Incomplete -> {
                val missingPart = if (state.missing.isEmpty()) "" else "fehlend: ${state.missing.joinToString(", ")}"
                val invalidPart = if (state.invalid.isEmpty()) "" else "ungültig: ${state.invalid.joinToString(", ")}"
                val detail = listOf(missingPart, invalidPart).filter { it.isNotBlank() }.joinToString("; ")
                error(
                    "PayPal-Konfiguration ist unvollständig ($detail) -- mindestens eine LAPIS_PAYPAL_*-" +
                        "Variable ist gesetzt, damit ist PayPal als aktiviert zu betrachten, aber die " +
                        "Konfiguration ist nicht vollständig/gültig. Entweder ALLE DREI Pflichtwerte " +
                        "(LAPIS_PAYPAL_CLIENT_ID/LAPIS_PAYPAL_CLIENT_SECRET/LAPIS_PAYPAL_WEBHOOK_ID) setzen " +
                        "oder GAR KEINE LAPIS_PAYPAL_*-Variable setzen. Siehe PaypalConfig KDoc.",
                )
            }
        }
    }
}
