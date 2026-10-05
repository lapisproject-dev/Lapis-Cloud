package network.lapis.cloud.server.db

import com.zaxxer.hikari.SQLExceptionOverride
import kotlinx.coroutines.ThreadContextElement
import java.sql.SQLException
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/**
 * Welle V1.9.55 -- per-call record of database failures. Kilua RPC does not let the server wrap
 * service exceptions, so the HTTP-level sanitizer (`RpcErrorSanitizer`) learns about a DB failure
 * through this holder: the Hikari [SQLExceptionOverride] records into the holder that the current
 * coroutine context carries via [DbFailureContext].
 */
internal class DbFailureHolder {
    @Volatile var sawSqlException: Boolean = false

    @Volatile var sqlState: String? = null

    @Volatile var kind: DbFailureKind? = null
}

private val currentHolder = ThreadLocal<DbFailureHolder?>()

internal class DbFailureContext(
    private val holder: DbFailureHolder,
) : AbstractCoroutineContextElement(Key),
    ThreadContextElement<DbFailureHolder?> {
    companion object Key : CoroutineContext.Key<DbFailureContext>

    override fun updateThreadContext(context: CoroutineContext): DbFailureHolder? {
        val old = currentHolder.get()
        currentHolder.set(holder)
        return old
    }

    override fun restoreThreadContext(
        context: CoroutineContext,
        oldState: DbFailureHolder?,
    ) {
        currentHolder.set(oldState)
    }
}

/**
 * Records the failure into the current call's holder and ALWAYS returns [SQLExceptionOverride.Override.CONTINUE_EVICT],
 * which keeps Hikari's default behaviour unchanged. Never throws, never logs.
 */
internal object DbFailureRecordingOverride : SQLExceptionOverride {
    override fun adjudicate(sqlException: SQLException): SQLExceptionOverride.Override {
        runCatching {
            currentHolder.get()?.let { h ->
                h.sawSqlException = true
                h.sqlState = sqlException.sqlState
                DbSqlStates.classify(sqlException.sqlState)?.let { h.kind = it }
            }
        }
        return SQLExceptionOverride.Override.CONTINUE_EVICT
    }
}
