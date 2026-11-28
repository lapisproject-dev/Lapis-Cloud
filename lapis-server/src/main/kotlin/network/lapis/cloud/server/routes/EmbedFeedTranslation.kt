package network.lapis.cloud.server.routes

import network.lapis.cloud.server.translation.TranslationIpRateLimiter
import network.lapis.cloud.server.translation.TranslationService
import network.lapis.cloud.server.translation.config.TranslateConfig

/** What the embed events feed needs for the optional language choice (Welle V1.9.343, T2): the service and the per-IP limiter for NEW jobs. */
internal class EmbedFeedTranslation(
    val service: TranslationService,
    val ipLimiter: TranslationIpRateLimiter,
) {
    companion object {
        /** For tests and callers without wiring: switched off, no model. */
        fun disabled(): EmbedFeedTranslation =
            EmbedFeedTranslation(
                service = TranslationService(config = TranslateConfig.disabled(), llm = null),
                ipLimiter = TranslationIpRateLimiter(perMinute = 1),
            )
    }
}
