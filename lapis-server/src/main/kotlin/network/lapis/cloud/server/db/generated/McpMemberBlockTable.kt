// Hand-written per ADR-0016 Option B (see 54-mcp-server.kuml.kts file header).

package network.lapis.cloud.server.db.generated

import kotlin.uuid.Uuid
import kotlinx.datetime.LocalDateTime
import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.datetime.datetime

/**
 * One row per member who has ever flipped their MCP kill-switch. **No row means "not blocked" --
 * the polarity is the opposite of [AiMemberOptInTable]**, where no row means "not opted in". See
 * `network.lapis.cloud.server.mcp.optin.McpMemberBlockStore` KDoc for why this table is named
 * `mcp_member_block`/`blocked`, never an `Ai*OptIn*`-shaped name.
 */
public object McpMemberBlockTable : Table("mcp_member_block") {
    public val id: Column<Uuid> = uuid("id")
    public val memberId: Column<Uuid> = reference("member_id", MemberTable.id)
    public val blocked: Column<Boolean> = bool("blocked")
    public val updatedAt: Column<LocalDateTime> = datetime("updated_at")

    override val primaryKey: PrimaryKey = PrimaryKey(id)
}
