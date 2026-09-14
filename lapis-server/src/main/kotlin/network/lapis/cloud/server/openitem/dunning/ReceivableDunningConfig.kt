package network.lapis.cloud.server.openitem.dunning

/**
 * Welle V1.4.15 -- configuration for the automated receivable-dunning poller, structurally
 * independent from [network.lapis.cloud.server.payment.dunning.DunningConfig] (the pre-existing
 * member-contribution dunning domain). Pure string parsing, no I/O, no fail-fast -- same posture
 * [DunningConfig]'s own KDoc documents: the feature is ALSO gated by a DB flag
 * (`organization_settings.receivable_dunning_enabled`), so [load] cannot know at startup whether
 * it is actually used.
 */
class ReceivableDunningConfig private constructor(
    /** `LAPIS_RECEIVABLE_DUNNING_POLLER_ENABLED`, default `false`. */
    val pollerEnabled: Boolean,
    /** `LAPIS_RECEIVABLE_DUNNING_POLL_INTERVAL_SECONDS`, default 3600 (hourly). */
    val pollIntervalSeconds: Long,
    /** `LAPIS_RECEIVABLE_DUNNING_MAX_NOTICES_PER_TICK`, default 200, `coerceIn(1, 5000)`. */
    val maxNoticesPerTick: Int,
) {
    companion object {
        private const val DEFAULT_POLL_INTERVAL_SECONDS = 3600L
        private const val MIN_POLL_INTERVAL_SECONDS = 60L
        private const val DEFAULT_MAX_NOTICES_PER_TICK = 200
        private const val MIN_MAX_NOTICES_PER_TICK = 1
        private const val MAX_MAX_NOTICES_PER_TICK = 5000

        fun load(env: (String) -> String? = System::getenv): ReceivableDunningConfig {
            val pollerEnabled = env("LAPIS_RECEIVABLE_DUNNING_POLLER_ENABLED")?.trim().equals("true", ignoreCase = true)
            val pollIntervalSeconds =
                (env("LAPIS_RECEIVABLE_DUNNING_POLL_INTERVAL_SECONDS")?.trim()?.toLongOrNull() ?: DEFAULT_POLL_INTERVAL_SECONDS)
                    .coerceAtLeast(MIN_POLL_INTERVAL_SECONDS)
            val maxNoticesPerTick =
                (env("LAPIS_RECEIVABLE_DUNNING_MAX_NOTICES_PER_TICK")?.trim()?.toIntOrNull() ?: DEFAULT_MAX_NOTICES_PER_TICK)
                    .coerceIn(MIN_MAX_NOTICES_PER_TICK, MAX_MAX_NOTICES_PER_TICK)
            return ReceivableDunningConfig(
                pollerEnabled = pollerEnabled,
                pollIntervalSeconds = pollIntervalSeconds,
                maxNoticesPerTick = maxNoticesPerTick,
            )
        }
    }
}
