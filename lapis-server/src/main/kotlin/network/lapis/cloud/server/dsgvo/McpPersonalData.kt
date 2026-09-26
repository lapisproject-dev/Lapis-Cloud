package network.lapis.cloud.server.dsgvo

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import network.lapis.cloud.server.db.generated.McpMemberBlockTable
import network.lapis.cloud.server.db.generated.McpPostDraftTable
import network.lapis.cloud.server.db.generated.McpToolCallAuditTable
import network.lapis.cloud.shared.domain.ErasureMode
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import kotlin.uuid.Uuid

/**
 * Welle V1.8.1 "MCP-Server für Mitglieder-Agenten (Fundament, lesend)" -- owns the two tables
 * modelled in `54-mcp-server.kuml.kts`.
 *
 * - `mcp_member_block`: **hard DELETE** -- a blocking decision for a person no longer in the
 *   system has no retention interest of its own, same posture [AiAssistantPersonalData] already
 *   takes for `ai_member_opt_in`.
 * - `mcp_tool_call_audit`: **retain-and-redact** -- the row is a usage/traceability record
 *   carrying only a tool name, an outcome code, and a duration (never arguments, never results);
 *   erasure sets `member_id` to `NULL`, same posture `ai_call_audit` already gets.
 * - `mcp_post_draft` (Welle V1.8.2): **hard DELETE** -- a draft is, by definition, never published
 *   (a released draft's real content lives on in its `social_post` row, covered by
 *   `SocialNetworkPersonalData`, not here); an unpublished draft for a person leaving the system
 *   has no retention interest of its own, same posture `mcp_member_block` already takes. Deleted
 *   regardless of `status` (`OPEN`/`RELEASED`/`DISCARDED`) -- a `RELEASED` draft's `released_post_id`
 *   is a pointer only, never re-derived from here.
 *
 * Export never includes a token hash (there is none on these tables to begin with) and never
 * includes `tokenId` (an opaque UUID with no personal meaning of its own, and NOT covered by any
 * FK -- see `McpToolCallAuditTable`/`McpPostDraftTable` KDoc).
 */
object McpPersonalData : MemberPersonalDataContributor {
    override val sectionKey = "mcp"
    override val displayName = "MCP-Zugang für KI-Agenten"
    override val coveredTables = setOf(McpMemberBlockTable, McpToolCallAuditTable, McpPostDraftTable)

    override fun exportMember(memberId: Uuid) =
        buildJsonObject {
            val blockRow = McpMemberBlockTable.selectAll().where { McpMemberBlockTable.memberId eq memberId }.singleOrNull()
            put("blocked", blockRow?.get(McpMemberBlockTable.blocked) ?: false)
            putJsonArray("toolCalls") {
                McpToolCallAuditTable
                    .selectAll()
                    .where { McpToolCallAuditTable.memberId eq memberId }
                    .forEach { row ->
                        add(
                            buildJsonObject {
                                put("toolName", row[McpToolCallAuditTable.toolName])
                                put("outcome", row[McpToolCallAuditTable.outcome])
                                put("durationMs", row[McpToolCallAuditTable.durationMs])
                                put("calledAt", row[McpToolCallAuditTable.calledAt].toString())
                            },
                        )
                    }
            }
            putJsonArray("postDrafts") {
                McpPostDraftTable
                    .selectAll()
                    .where { McpPostDraftTable.memberId eq memberId }
                    .forEach { row ->
                        add(
                            buildJsonObject {
                                put("content", row[McpPostDraftTable.content])
                                put("visibility", row[McpPostDraftTable.visibility].name)
                                put("status", row[McpPostDraftTable.status].name)
                                put("agentLabel", row[McpPostDraftTable.agentLabel])
                                put("createdAt", row[McpPostDraftTable.createdAt].toString())
                            },
                        )
                    }
            }
        }

    override fun eraseMember(
        memberId: Uuid,
        mode: ErasureMode,
    ): List<TableErasureOutcome> {
        val blocksDeleted = McpMemberBlockTable.deleteWhere { McpMemberBlockTable.memberId eq memberId }
        val auditRedacted =
            McpToolCallAuditTable.update({ McpToolCallAuditTable.memberId eq memberId }) {
                it[McpToolCallAuditTable.memberId] = null
            }
        val draftsDeleted = McpPostDraftTable.deleteWhere { McpPostDraftTable.memberId eq memberId }
        return listOf(
            TableErasureOutcome(table = "mcp_member_block", rowsDeleted = blocksDeleted),
            TableErasureOutcome(
                table = "mcp_tool_call_audit",
                rowsAnonymized = auditRedacted,
                retentionReason =
                    "Usage traceability record: only tool name, outcome and duration are retained, the member reference is removed",
            ),
            TableErasureOutcome(table = "mcp_post_draft", rowsDeleted = draftsDeleted),
        )
    }
}
