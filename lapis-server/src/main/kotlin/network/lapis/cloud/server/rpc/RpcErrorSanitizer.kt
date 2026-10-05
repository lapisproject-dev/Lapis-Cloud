package network.lapis.cloud.server.rpc

import dev.kilua.rpc.AbstractServiceException
import dev.kilua.rpc.JsonRpcResponse
import dev.kilua.rpc.RpcSerialization
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.call
import io.ktor.server.request.path
import io.ktor.server.response.ApplicationSendPipeline
import io.ktor.util.AttributeKey
import kotlinx.coroutines.withContext
import network.lapis.cloud.server.db.DbFailureContext
import network.lapis.cloud.server.db.DbFailureHolder
import network.lapis.cloud.shared.rpc.ServiceBusyException

private val logger = KotlinLogging.logger {}

internal val DbFailureHolderKey = AttributeKey<DbFailureHolder>("lapis.dbFailureHolder")

private val SQL_MESSAGE_PREFIXES = listOf("org.postgresql.", "org.h2.", "java.sql.", "org.jetbrains.exposed.", "org.flywaydb.")

/**
 * Welle V1.9.55 -- closes a real information leak and maps database timeouts to a typed error.
 *
 * Kilua RPC (verified in the 0.0.45 bytecode) answers every non-typed service exception with
 * `JsonRpcResponse(error = e.message, exceptionType = <class>, exceptionJson = null)` and logs
 * `e.message`. For an `ExposedSQLException` the message is the full PSQL text including the `Detail:` line (with
 * values on unique violations) and the SQL, which the client shows verbatim as a toast.
 *
 * Because Kilua offers no hook to wrap exceptions, this installs (1) a per-call [DbFailureHolder] fed by the
 * Hikari exception override and (2) a send-pipeline transform that rewrites such responses:
 * - a classified timeout/deadlock -> a typed [ServiceBusyException] (fixed text on the client),
 * - any other SQL error (or an untyped message that looks like a driver/ORM message) -> an empty error text,
 * - everything else (typed exceptions, deliberate `require`/`error` messages) -> unchanged.
 *
 * Known limitation: [DbFailureHolder] is sticky for the whole call. If a service deliberately catches an SQL
 * error (e.g. a unique violation) and later throws an untyped message in the same call, that message is blanked
 * (or becomes ServiceBusy). It degrades safely (no leak, no data damage), but the text is lost.
 *
 * Must be installed BEFORE `initRpc` so this transform runs before ContentNegotiation serializes the body.
 */
fun Application.installRpcErrorSanitizer() {
    intercept(ApplicationCallPipeline.Setup) {
        val holder = DbFailureHolder()
        call.attributes.put(DbFailureHolderKey, holder)
        withContext(DbFailureContext(holder)) { proceed() }
    }
    sendPipeline.intercept(ApplicationSendPipeline.Transform) { body ->
        if (body is JsonRpcResponse) {
            val holder = call.attributes.getOrNull(DbFailureHolderKey)
            val rewritten = sanitizeRpcResponse(response = body, holder = holder)
            if (rewritten !== body) {
                logger.warn {
                    "RPC call failed with database ${holder?.kind ?: "error"} " +
                        "(sqlState=${holder?.sqlState ?: "n/a"}, path=${call.request.path()})"
                }
                proceedWith(rewritten)
            }
        }
    }
}

/** Pure rewrite rule, separated for unit tests. Returns [response] itself when nothing changes. */
internal fun sanitizeRpcResponse(
    response: JsonRpcResponse,
    holder: DbFailureHolder?,
): JsonRpcResponse {
    val error = response.error ?: return response
    // Typed service exceptions (and deliberately thrown ones) carry exceptionJson: never touched.
    if (response.exceptionJson != null) return response
    val kind = holder?.kind
    if (kind != null) {
        val json = RpcSerialization.getJson().encodeToString<AbstractServiceException>(ServiceBusyException())
        return response.copy(
            error = "",
            exceptionType = ServiceBusyException::class.java.canonicalName,
            exceptionJson = json,
        )
    }
    val looksLikeDriverMessage = SQL_MESSAGE_PREFIXES.any { error.startsWith(it) } || error.contains("org.postgresql.util.PSQLException")
    if (holder?.sawSqlException == true || looksLikeDriverMessage) {
        return response.copy(error = "")
    }
    return response
}
