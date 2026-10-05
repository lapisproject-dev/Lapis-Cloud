package network.lapis.cloud.server.db

import org.jetbrains.exposed.v1.core.vendors.PostgreSQLDialect
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction

/**
 * Welle V1.9.55 -- transaction-local relaxation of the session timeouts (`SET LOCAL`: reverts at
 * COMMIT/ROLLBACK, therefore pool-safe). No-op on H2.
 *
 * RULE: main code never issues a session-wide SET of the lock, statement or idle-in-transaction timeout
 * variables without the LOCAL keyword (it would poison pooled connections). The only
 * exception is [DbSessionTimeouts] itself; a tripwire test enforces this.
 */
internal fun JdbcTransaction.relaxSessionTimeouts(
    statementTimeoutMs: Long = 0,
    idleInTransactionTimeoutMs: Long = 0,
    lockTimeoutMs: Long? = null,
) {
    if (db.dialect !is PostgreSQLDialect) return
    require(statementTimeoutMs in 0..DbSessionTimeouts.MAX_TIMEOUT_MS)
    require(idleInTransactionTimeoutMs in 0..DbSessionTimeouts.MAX_TIMEOUT_MS)
    exec("SET LOCAL statement_timeout = $statementTimeoutMs")
    exec("SET LOCAL idle_in_transaction_session_timeout = $idleInTransactionTimeoutMs")
    if (lockTimeoutMs != null) {
        require(lockTimeoutMs in 0..DbSessionTimeouts.MAX_TIMEOUT_MS)
        exec("SET LOCAL lock_timeout = $lockTimeoutMs")
    }
}
