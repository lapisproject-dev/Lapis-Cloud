package network.lapis.cloud.server.mcp.audit

import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.server.db.DbClock
import network.lapis.cloud.server.db.generated.McpToolCallAuditTable
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.uuid.Uuid

internal enum class McpToolCallOutcome { OK, TIMEOUT, FORBIDDEN, ERROR, UNKNOWN_TOOL }

/** Metadata only -- see [network.lapis.cloud.server.db.generated.McpToolCallAuditTable] KDoc "never arguments, never results". */
internal data class McpToolCallAuditEntry(
    val memberId: Uuid?,
    val tokenId: Uuid?,
    val toolName: String,
    val outcome: McpToolCallOutcome,
    val durationMs: Int,
)

/**
 * Writes one row per `tools/call` that actually reached a tool -- including timed-out, forbidden,
 * unknown-tool and internal-error outcomes, but deliberately NOT a rate-limited call (see
 * `McpToolDispatcher.dispatch`'s early-return comment: auditing an already-rejected call would
 * itself be the unbounded DB write the rate limiter exists to prevent -- there is no
 * [McpToolCallOutcome] value for it, since it never reaches this recorder). Opens its own
 * `transaction {}` (this is a fire-and-forget side record of the request, not a value the caller's
 * own transaction depends on) -- same posture as
 * `network.lapis.cloud.server.audit.OidcLoginAuditRecorder.record`.
 */
internal object McpToolCallAuditRecorder {
    fun record(entry: McpToolCallAuditEntry) {
        val now: LocalDateTime = DbClock.nowLocalDateTime()
        transaction {
            McpToolCallAuditTable.insert {
                it[id] = Uuid.random()
                it[memberId] = entry.memberId
                it[tokenId] = entry.tokenId
                it[toolName] = entry.toolName
                it[outcome] = entry.outcome.name
                it[durationMs] = entry.durationMs
                it[calledAt] = now
            }
        }
    }
}
