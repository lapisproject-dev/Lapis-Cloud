// Hand-written per ADR-0016 Option B (see 54-mcp-server.kuml.kts file header).

package network.lapis.cloud.server.db.generated

import kotlin.uuid.Uuid
import kotlinx.datetime.LocalDateTime
import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.datetime.datetime

/**
 * One row per `tools/call` that actually reached a tool -- metadata only, **never arguments,
 * never results**. Rate-limited calls are never audited (see
 * `network.lapis.cloud.server.mcp.audit.McpToolCallAuditRecorder` KDoc): a row here does **not**
 * mean "one call attempt", so counting rows to estimate an agent's total request volume
 * systematically undercounts any rejected burst. `memberId` is nullable (DSGVO retain-and-redact,
 * see `network.lapis.cloud.server.dsgvo.McpPersonalData`).
 * `tokenId` is nullable and **deliberately carries no foreign key** -- a token may be deleted (or
 * simply expire and get purged by a later wave) while this audit row is retained, same "outlives
 * its parent" posture `oidc_guest_login_event.member_id` already establishes elsewhere in this
 * schema (see that table's own KDoc). A future wave adding `.references(...)` here would be a
 * regression -- pinned by `McpServerSchemaDriftTest`.
 */
public object McpToolCallAuditTable : Table("mcp_tool_call_audit") {
    public val id: Column<Uuid> = uuid("id")
    public val memberId: Column<Uuid?> = reference("member_id", MemberTable.id).nullable()
    public val tokenId: Column<Uuid?> = uuid("token_id").nullable()
    public val toolName: Column<String> = varchar("tool_name", 60)
    public val outcome: Column<String> = varchar("outcome", 30)
    public val durationMs: Column<Int> = integer("duration_ms")
    public val calledAt: Column<LocalDateTime> = datetime("called_at")

    override val primaryKey: PrimaryKey = PrimaryKey(id)
}
