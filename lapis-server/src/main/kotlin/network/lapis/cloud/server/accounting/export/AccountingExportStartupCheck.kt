package network.lapis.cloud.server.accounting.export

import io.github.oshai.kotlinlogging.KotlinLogging

private val logger = KotlinLogging.logger {}

/**
 * Welle V1.4.5.3 "lexoffice-Live-Anbindung" -- one informational log line at startup, NEVER a
 * fail-fast (see [AccountingExportConfig] KDoc for why). Mirrors the shape
 * `network.lapis.cloud.server.payment.psp.PspStartupCheck` establishes, without an `Incomplete`
 * branch -- there is no "some but not all required variables set" state here, only "usable" or
 * "usable once a key is configured".
 */
object AccountingExportStartupCheck {
    fun verifyAndLog(config: AccountingExportConfig) {
        when {
            !config.enabled ->
                logger.info { "Buchhaltungs-Live-Export deaktiviert (LAPIS_ACCOUNTING_EXPORT_ENABLED=false)." }
            config.secretEncryptionKey == null ->
                logger.info {
                    "Buchhaltungs-Live-Export aktiv, aber LAPIS_SECRET_ENCRYPTION_KEY ist nicht gesetzt/ungültig -- " +
                        "bis ein gültiger Schlüssel konfiguriert ist, bleibt das Hinterlegen eines Anbieter-Tokens " +
                        "(setToken) nicht nutzbar (ConflictException, kein Klartext-Fallback)."
                }
            else ->
                logger.info {
                    "Buchhaltungs-Live-Export aktiv: pollIntervalSeconds=${config.pollIntervalSeconds} (Verschlüsselungsschlüssel gesetzt)."
                }
        }
    }
}
