package network.lapis.cloud.server.db

/**
 * Welle V1.9.55 -- Postgres session timeouts applied to every pooled connection of the application
 * pool (via Hikari's `connectionInitSql`, i.e. once per physical connection).
 *
 * A value of `0` renders an explicit `SET ... = 0` ("off"), which deliberately overrides any role-
 * or database-level default instead of inheriting it. Only integers are ever rendered into SQL, so
 * there is no injection surface even though the values come from environment variables.
 *
 * NOTE (Exposed retry multiplication): Exposed retries a failed transaction on ANY `SQLException`
 * (default 3 attempts), including lock/statement timeouts -- the worst-case wait of one request is
 * therefore `3 x lockTimeoutMs`. See `docs/architecture/database-timeouts-and-retries.adoc`.
 */
internal data class DbSessionTimeouts(
    val lockTimeoutMs: Long,
    val statementTimeoutMs: Long,
    val idleInTransactionTimeoutMs: Long,
) {
    init {
        require(lockTimeoutMs in 0..MAX_TIMEOUT_MS) { "lock timeout out of range" }
        require(statementTimeoutMs in 0..MAX_TIMEOUT_MS) { "statement timeout out of range" }
        require(idleInTransactionTimeoutMs in 0..MAX_TIMEOUT_MS) { "idle-in-transaction timeout out of range" }
        // A statement timeout that fires before the lock timeout would mask lock waits as generic cancels.
        require(statementTimeoutMs == 0L || lockTimeoutMs == 0L || statementTimeoutMs > lockTimeoutMs) {
            "statement timeout must be greater than lock timeout (or one of them 0 = off)"
        }
    }

    /** Postgres-only init SQL; integers only. */
    fun toPostgresInitSql(): String =
        "SET lock_timeout = $lockTimeoutMs; SET statement_timeout = $statementTimeoutMs; " +
            "SET idle_in_transaction_session_timeout = $idleInTransactionTimeoutMs"

    companion object {
        const val MAX_TIMEOUT_MS: Long = Int.MAX_VALUE.toLong()
        const val ENV_LOCK = "LAPIS_DB_LOCK_TIMEOUT_MS"
        const val ENV_STATEMENT = "LAPIS_DB_STATEMENT_TIMEOUT_MS"
        const val ENV_IDLE_TX = "LAPIS_DB_IDLE_TX_TIMEOUT_MS"
        val DEFAULTS = DbSessionTimeouts(lockTimeoutMs = 10_000, statementTimeoutMs = 60_000, idleInTransactionTimeoutMs = 120_000)

        /** Flyway migration pool: migrations may legitimately run long. */
        val DISABLED = DbSessionTimeouts(lockTimeoutMs = 0, statementTimeoutMs = 0, idleInTransactionTimeoutMs = 0)

        /**
         * Unset -> default. Blank/garbage/negative/out-of-range -> [IllegalStateException] naming ONLY
         * the variable (fail fast at startup, never echoing the raw value).
         */
        fun fromEnv(env: Map<String, String> = System.getenv()): DbSessionTimeouts {
            fun read(
                name: String,
                default: Long,
            ): Long {
                val raw = env[name] ?: return default
                val parsed = raw.trim().toLongOrNull()
                check(parsed != null && parsed in 0..MAX_TIMEOUT_MS) {
                    "$name must be an integer number of milliseconds between 0 and $MAX_TIMEOUT_MS"
                }
                return parsed
            }
            val lock = read(ENV_LOCK, DEFAULTS.lockTimeoutMs)
            val statement = read(ENV_STATEMENT, DEFAULTS.statementTimeoutMs)
            val idle = read(ENV_IDLE_TX, DEFAULTS.idleInTransactionTimeoutMs)
            check(statement == 0L || lock == 0L || statement > lock) {
                "$ENV_STATEMENT must be greater than $ENV_LOCK (or one of them 0 = off)"
            }
            return DbSessionTimeouts(lockTimeoutMs = lock, statementTimeoutMs = statement, idleInTransactionTimeoutMs = idle)
        }
    }
}
