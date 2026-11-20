package network.lapis.cloud.server.carpool

import io.github.oshai.kotlinlogging.KotlinLogging
import network.lapis.cloud.server.config.SecretEnv

private val logger = KotlinLogging.logger {}

/**
 * V1.9.318 (wave 6): how often [CarpoolRetentionPoller] runs, in hours. `LAPIS_CARPOOL_RETENTION_INTERVAL_HOURS`, default and maximum 24 (the
 * retention promise to members never gets weaker than "within a day"); anything that is not a whole number from 1 to 24 falls back to 24. Pure string
 * parsing, safe to call unconditionally. A rejected value is logged WITHOUT echoing it.
 */
internal object CarpoolRetentionConfig {
    const val ENV = "LAPIS_CARPOOL_RETENTION_INTERVAL_HOURS"
    const val DEFAULT_HOURS = 24L
    private const val MIN_HOURS = 1L

    fun parse(raw: String?): Long {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty()) return DEFAULT_HOURS
        val hours = text.toLongOrNull()
        if (hours == null || hours !in MIN_HOURS..DEFAULT_HOURS) {
            logger.warn { "$ENV is not a whole number from $MIN_HOURS to $DEFAULT_HOURS, using $DEFAULT_HOURS" }
            return DEFAULT_HOURS
        }
        return hours
    }

    fun load(env: (String) -> String? = SecretEnv::get): Long = parse(env(ENV))
}
