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
 * unknown-tool and internal-error outcomes, but deliberately NOT a rate-limited call, and (Welle
 * V1.8.2 wave 2 fix) NOT the write-scope check's early `Forbidden` either -- see
 * `McpToolDispatcher.dispatch`'s early-return comments on both branches: auditing a call that was
 * rejected before it ever reached a tool would itself be the unbounded DB write these checks exist
 * to prevent. [McpToolCallOutcome.FORBIDDEN] is still written for the OTHER `Forbidden` source --
 * a `ForbiddenException` a tool throws once it actually starts executing (after the rate limiter),
 * e.g. a business-rule rejection inside the tool itself -- that path still reaches this recorder.
 * Opens its own `transaction {}` (this is a fire-and-forget side record of the request, not a value
 * the caller's own transaction depends on) -- same posture as
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
