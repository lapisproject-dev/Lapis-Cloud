package network.lapis.cloud.server.economy.oracle

/**
 * Welle "Price-Oracle-Preishistorie". Configuration for [PriceOracleSnapshotPoller] -- pure string
 * parsing, same "safe to call unconditionally" posture as `SepaConfig.load`/`FinTsConfig.load`.
 */
class PriceOracleSnapshotConfig private constructor(
    /** `LAPIS_ORACLE_SNAPSHOT_ENABLED`, default `false` -- same opt-in posture as `SepaConfig.pollerEnabled`: `load()` is pure string parsing and safe to call unconditionally (including in tests, where `Application.module()` is booted repeatedly against a shared in-memory H2 DB), but the poller itself must be explicitly enabled per deployment rather than starting implicitly on every module boot. */
    val enabled: Boolean,
    /** `LAPIS_ORACLE_SNAPSHOT_INTERVAL_SECONDS`, default 3600 (hourly). Floored at 300s -- a misconfigured near-zero interval must never busy-spin the loop, same `.coerceAtLeast` discipline as `SepaConfig.MIN_POLL_INTERVAL_SECONDS`. */
    val intervalSeconds: Long,
) {
    companion object {
        private const val DEFAULT_INTERVAL_SECONDS = 3600L
        private const val MIN_INTERVAL_SECONDS = 300L

        fun load(env: (String) -> String? = System::getenv): PriceOracleSnapshotConfig {
            val enabled = env("LAPIS_ORACLE_SNAPSHOT_ENABLED")?.trim().equals("true", ignoreCase = true)
            val intervalSeconds =
                (env("LAPIS_ORACLE_SNAPSHOT_INTERVAL_SECONDS")?.trim()?.toLongOrNull() ?: DEFAULT_INTERVAL_SECONDS)
                    .coerceAtLeast(MIN_INTERVAL_SECONDS)
            return PriceOracleSnapshotConfig(enabled = enabled, intervalSeconds = intervalSeconds)
        }
    }
}
