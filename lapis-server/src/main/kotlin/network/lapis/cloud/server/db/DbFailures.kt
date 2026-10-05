package network.lapis.cloud.server.db

import java.sql.SQLException
import java.util.Collections
import java.util.IdentityHashMap

/** Welle V1.9.55 -- database failure kinds that the application maps to "server busy". */
internal enum class DbFailureKind { LOCK_TIMEOUT, STATEMENT_TIMEOUT, IDLE_IN_TX_TIMEOUT, DEADLOCK, SERIALIZATION }

internal object DbSqlStates {
    const val UNIQUE_VIOLATION = "23505"
    const val LOCK_NOT_AVAILABLE = "55P03"
    const val QUERY_CANCELED = "57014"
    const val IDLE_IN_TX_TIMEOUT = "25P03"
    const val DEADLOCK = "40P01"
    const val SERIALIZATION = "40001"

    /** H2 "Timeout trying to lock table" -- lets the H2 lane exercise the mapping. */
    const val H2_LOCK_TIMEOUT = "HYT00"

    fun classify(sqlState: String?): DbFailureKind? =
        when (sqlState) {
            LOCK_NOT_AVAILABLE, H2_LOCK_TIMEOUT -> DbFailureKind.LOCK_TIMEOUT
            QUERY_CANCELED -> DbFailureKind.STATEMENT_TIMEOUT
            IDLE_IN_TX_TIMEOUT -> DbFailureKind.IDLE_IN_TX_TIMEOUT
            DEADLOCK -> DbFailureKind.DEADLOCK
            SERIALIZATION -> DbFailureKind.SERIALIZATION
            else -> null
        }
}

private const val MAX_CAUSE_DEPTH = 16

private fun Throwable.anySqlState(predicate: (String?) -> Boolean): Boolean {
    val seen: MutableSet<Throwable> = Collections.newSetFromMap(IdentityHashMap())

    fun walk(
        t: Throwable?,
        depth: Int,
    ): Boolean {
        if (t == null || depth > MAX_CAUSE_DEPTH || !seen.add(t)) return false
        if (t is SQLException && predicate(t.sqlState)) return true
        if (t is SQLException && walk(t.nextException, depth + 1)) return true
        return walk(t.cause, depth + 1)
    }
    return walk(this, 0)
}

/** The first classified failure kind in the cause / `nextException` chain, or `null`. */
internal fun Throwable.dbFailureKind(): DbFailureKind? {
    var found: DbFailureKind? = null
    anySqlState { state ->
        found = DbSqlStates.classify(state)
        found != null
    }
    return found
}

/** `true` iff a SQLSTATE 23505 (unique violation) is in the cause chain. */
internal fun Throwable.isUniqueViolation(): Boolean = anySqlState { it == DbSqlStates.UNIQUE_VIOLATION }
