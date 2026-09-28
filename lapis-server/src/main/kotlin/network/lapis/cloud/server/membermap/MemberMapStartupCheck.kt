package network.lapis.cloud.server.membermap

import io.github.oshai.kotlinlogging.KotlinLogging

private val logger = KotlinLogging.logger {}

/**
 * Welle V1.9.5 "Vorstands-Karte" -- one-time startup log line, same "log inventory, never fail-fast"
 * posture as [network.lapis.cloud.server.branding.BrandingStartupCheck] (see that class' KDoc for
 * the full argument). The PER-REQUEST re-probe that actually gates serving lives in
 * [PmtilesBasemap.probe] -- this object only logs what the operator sees ONCE at boot, so a stale
 * "map active" log line is never load-bearing for security.
 */
object MemberMapStartupCheck {
    fun log(
        config: MemberMapConfig,
        basemap: PmtilesBasemap,
    ) {
        if (config.invalid.isNotEmpty()) {
            logger.warn {
                "Vorstands-Karte: ${config.invalid.joinToString(", ")} ist gesetzt, aber ungültig " +
                    "(kein absoluter Pfad, oder nicht auf '.pmtiles' endend) -- Karte bleibt deaktiviert."
            }
            return
        }
        when (val probe = basemap.probe()) {
            is PmtilesProbe.NotConfigured ->
                logger.info {
                    "Vorstands-Karte: ${MemberMapConfig.ENV_PMTILES_PATH} nicht gesetzt -- Kartenfunktion deaktiviert (optional)."
                }
            is PmtilesProbe.Missing ->
                logger.warn { "Vorstands-Karte: konfigurierte PMTiles-Datei nicht gefunden -- Kartenfunktion bleibt deaktiviert." }
            is PmtilesProbe.NotRegularFile ->
                logger.warn { "Vorstands-Karte: konfigurierter Pfad ist kein regulärer Datei-Pfad -- Kartenfunktion bleibt deaktiviert." }
            is PmtilesProbe.Unreadable ->
                logger.warn { "Vorstands-Karte: konfigurierte PMTiles-Datei ist nicht lesbar -- Kartenfunktion bleibt deaktiviert." }
            is PmtilesProbe.InvalidHeader ->
                logger.warn {
                    "Vorstands-Karte: konfigurierte Datei hat keinen gültigen PMTiles-v3-Header -- Kartenfunktion bleibt deaktiviert."
                }
            is PmtilesProbe.Available ->
                logger.info { "Vorstands-Karte: PMTiles-Basiskarte aktiv ('${probe.file}')." }
        }
    }
}
