package network.lapis.cloud.server.payment.dunning

/**
 * Welle V1.2.7 "Automatisiertes Mahnwesen". Configuration for the automated dunning poller/postal
 * dispatch. Pure string parsing, no I/O -- **deliberately no fail-fast**, exactly the same posture
 * [network.lapis.cloud.server.payment.sepa.SepaConfig]'s own KDoc documents: the feature is gated
 * by a DB flag (`organization_settings.dunning_enabled`), so [load] cannot know at startup whether
 * dunning is actually used.
 */
class DunningConfig private constructor(
    /** `LAPIS_DUNNING_POLLER_ENABLED`, default `false`. Allows the poller to be enabled on exactly ONE instance. */
    val pollerEnabled: Boolean,
    /** `LAPIS_DUNNING_POLL_INTERVAL_SECONDS`, default 3600 (hourly -- every deadline is a calendar-day deadline). */
    val pollIntervalSeconds: Long,
    /** `LAPIS_DUNNING_MAX_NOTICES_PER_TICK`, default 200, `coerceIn(1, 5000)` -- cost-/DoS-deckel per poll pass. */
    val maxNoticesPerTick: Int,
    /**
     * `LAPIS_DUNNING_POSTAL_DISPATCH_ENABLED`, default `false`. A THIRD, independent gate on top of
     * `organizationSettings.postalMailEnabled` -- see [network.lapis.cloud.server.payment.dunning.DunningPoller]
     * KDoc "Phase B" for the full four-condition dispatch guard.
     */
    val postalDispatchEnabled: Boolean,
) {
    companion object {
        private const val DEFAULT_POLL_INTERVAL_SECONDS = 3600L

        /** Same busy-spin guard [network.lapis.cloud.server.payment.sepa.SepaConfig] already applies to its own interval. */
        private const val MIN_POLL_INTERVAL_SECONDS = 60L

        private const val DEFAULT_MAX_NOTICES_PER_TICK = 200
        private const val MIN_MAX_NOTICES_PER_TICK = 1
        private const val MAX_MAX_NOTICES_PER_TICK = 5000

        fun load(env: (String) -> String? = System::getenv): DunningConfig {
            val pollerEnabled = env("LAPIS_DUNNING_POLLER_ENABLED")?.trim().equals("true", ignoreCase = true)
            val pollIntervalSeconds =
                (env("LAPIS_DUNNING_POLL_INTERVAL_SECONDS")?.trim()?.toLongOrNull() ?: DEFAULT_POLL_INTERVAL_SECONDS)
                    .coerceAtLeast(MIN_POLL_INTERVAL_SECONDS)
            val maxNoticesPerTick =
                (env("LAPIS_DUNNING_MAX_NOTICES_PER_TICK")?.trim()?.toIntOrNull() ?: DEFAULT_MAX_NOTICES_PER_TICK)
                    .coerceIn(MIN_MAX_NOTICES_PER_TICK, MAX_MAX_NOTICES_PER_TICK)
            val postalDispatchEnabled = env("LAPIS_DUNNING_POSTAL_DISPATCH_ENABLED")?.trim().equals("true", ignoreCase = true)

            return DunningConfig(
                pollerEnabled = pollerEnabled,
                pollIntervalSeconds = pollIntervalSeconds,
                maxNoticesPerTick = maxNoticesPerTick,
                postalDispatchEnabled = postalDispatchEnabled,
            )
        }
    }
}
