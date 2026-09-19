package network.lapis.cloud.server.ai.config

import io.github.oshai.kotlinlogging.KotlinLogging

private val logger = KotlinLogging.logger {}

/**
 * Startup logging for [AiConfig], same "log inventory, never fail" shape as
 * `network.lapis.cloud.server.branding.BrandingStartupCheck`. Logs the provider dialect, the model
 * name and the base URL's **host only** -- never the API key and never the full URL (a gateway may
 * carry credentials in a path or query, and the guard rejects a query string but not every shape).
 */
internal object AiStartupCheck {
    /** Variables that decide whether the feature can run at all; every other rejected variable is tuning. */
    private val PROFILE_VARIABLES =
        setOf(AiConfig.ENV_PROVIDER, AiConfig.ENV_MODEL, AiConfig.ENV_API_KEY, AiConfig.ENV_BASE_URL)

    fun log(config: AiConfig) {
        if (!config.enabled) {
            logger.info { "AI-Assistenz: aus (LAPIS_AI_ENABLED nicht auf true gesetzt)." }
            return
        }
        if (!config.isOperational) {
            if (config.invalid.isNotEmpty()) {
                logger.warn {
                    "AI-Assistenz: LAPIS_AI_ENABLED=true, aber die Konfiguration ist unvollständig oder ungültig " +
                        "(${config.invalid.joinToString(", ")}) -- Feature bleibt aus, der Server startet trotzdem."
                }
            }
            logger.warn { "AI-Assistenz: nicht betriebsbereit (Provider/Modell/API-Key/Basis-URL unvollständig) -- Feature bleibt aus." }
            return
        }
        // Operational: a rejected tuning variable only falls back to its default, the feature stays on.
        val rejectedTuning = config.invalid.filterNot { it in PROFILE_VARIABLES }
        if (rejectedTuning.isNotEmpty()) {
            logger.warn {
                "AI-Assistenz: ungültige Tuning-Variable(n) (${rejectedTuning.joinToString(", ")}) -- Standardwert wird verwendet, " +
                    "das Feature bleibt aktiv."
            }
        }
        val host = config.baseUrl?.let(AiBaseUrlGuard::hostOf)
        logger.info { "AI-Assistenz: aktiv, provider=${config.provider?.auditName}, model=${config.model}, host=$host." }
        if (config.baseUrl?.let(AiBaseUrlGuard::isKnownProviderHost) == false) {
            logger.info { "AI-Assistenz: Basis-URL-Host '$host' ist kein bekannter Anbieter-Host (operator-configured endpoint)." }
        }
    }
}
