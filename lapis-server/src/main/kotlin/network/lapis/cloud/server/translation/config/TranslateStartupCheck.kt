package network.lapis.cloud.server.translation.config

import io.github.oshai.kotlinlogging.KotlinLogging
import network.lapis.cloud.server.ai.config.AiBaseUrlGuard

private val logger = KotlinLogging.logger {}

/**
 * Startup logging for [TranslateConfig], same "log inventory, never fail" shape as `AiStartupCheck`. Logs the provider dialect, the model name
 * and the base URL's **host only** -- never the API key and never the URL path.
 */
internal object TranslateStartupCheck {
    private val PROFILE_VARIABLES =
        setOf(TranslateConfig.ENV_PROVIDER, TranslateConfig.ENV_MODEL, TranslateConfig.ENV_API_KEY, TranslateConfig.ENV_BASE_URL)

    fun log(config: TranslateConfig) {
        if (!config.enabled) {
            logger.info { "Uebersetzung: aus (LAPIS_TRANSLATE_ENABLED nicht auf true gesetzt)." }
            return
        }
        if (!config.isOperational) {
            if (config.invalid.isNotEmpty()) {
                logger.warn {
                    "Uebersetzung: LAPIS_TRANSLATE_ENABLED=true, aber die Konfiguration ist unvollstaendig oder ungueltig " +
                        "(${config.invalid.joinToString(", ")}) -- Feature bleibt aus, der Server startet trotzdem."
                }
            }
            logger.warn { "Uebersetzung: nicht betriebsbereit (Provider/Modell/API-Key/Basis-URL unvollstaendig) -- Feature bleibt aus." }
            return
        }
        val rejectedTuning = config.invalid.filterNot { it in PROFILE_VARIABLES }
        if (rejectedTuning.isNotEmpty()) {
            logger.warn {
                "Uebersetzung: ungueltige Tuning-Variable(n) (${rejectedTuning.joinToString(", ")}) -- Standardwert wird verwendet, " +
                    "das Feature bleibt aktiv."
            }
        }
        val host = config.baseUrl?.let(AiBaseUrlGuard::hostOf)
        logger.info { "Uebersetzung: aktiv, provider=${config.provider?.auditName}, model=${config.model}, host=$host." }
        if (config.baseUrl?.let(AiBaseUrlGuard::isKnownProviderHost) == false) {
            logger.info { "Uebersetzung: Basis-URL-Host '$host' ist kein bekannter Anbieter-Host (operator-configured endpoint)." }
        }
        if (config.provider?.name == "OPENAI_COMPATIBLE" && baseUrlEndsWithV1(config.baseUrl)) {
            logger.warn {
                "Uebersetzung: Die Basis-URL endet auf /v1, der Client haengt aber selbst /v1/chat/completions an " +
                    "(Beispiel xAI: https://api.x.ai ohne /v1)."
            }
        }
    }

    internal fun baseUrlEndsWithV1(baseUrl: String?): Boolean = baseUrl?.trimEnd('/')?.endsWith("/v1") == true
}
