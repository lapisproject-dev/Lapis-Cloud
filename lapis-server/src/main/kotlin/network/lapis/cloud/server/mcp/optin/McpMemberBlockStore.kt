package network.lapis.cloud.server.mcp.optin

import network.lapis.cloud.server.db.DbClock
import network.lapis.cloud.server.db.generated.McpMemberBlockTable
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.exceptions.ExposedSQLException
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import kotlin.uuid.Uuid

/**
 * Per-member MCP kill-switch. **No row means "not blocked" -- the OPPOSITE polarity of
 * [network.lapis.cloud.server.ai.optin.AiMemberOptInStore]**, where no row means "not opted in".
 * Deliberately named/shaped this way, not as an `Ai*OptIn*` analogue: at `ai_member_opt_in`, "no
 * row" is the safe default (a feature the member never turned on). At `mcp_member_block`, "no row"
 * must ALSO be the safe default for a member who has never touched the switch, but here that means
 * "not blocked" -- the table records exceptions to an implicit "MCP allowed once the operator
 * turns the feature on" default, not consent to turn it on. Same "no server-wide pre-consent"
 * discipline `AiMemberOptInStore` KDoc documents applies in mirror image: there is no
 * `LAPIS_MCP_MEMBER_BLOCK_DEFAULT` variable, and there never will be -- see `AiConfig
 * .ENV_MEMBER_OPT_IN_DEFAULT` KDoc for the removed precedent this deliberately does not repeat.
 */
internal object McpMemberBlockStore {
    fun isBlocked(memberId: Uuid): Boolean =
        transaction {
            McpMemberBlockTable
                .selectAll()
                .where { McpMemberBlockTable.memberId eq memberId }
                .singleOrNull()
                ?.get(McpMemberBlockTable.blocked)
        } ?: false

    /** Upsert; idempotent. A concurrent first write is resolved through the unique index, same race-handling shape as `AiMemberOptInStore.set`. */
    fun setBlocked(
        memberId: Uuid,
        blocked: Boolean,
    ) {
        val now = DbClock.nowLocalDateTime()

        fun update(): Int =
            transaction {
                McpMemberBlockTable.update({ McpMemberBlockTable.memberId eq memberId }) {
                    it[McpMemberBlockTable.blocked] = blocked
                    it[updatedAt] = now
                }
            }
        if (update() > 0) return
        try {
            transaction {
                McpMemberBlockTable.insert {
                    it[id] = Uuid.random()
                    it[McpMemberBlockTable.memberId] = memberId
                    it[McpMemberBlockTable.blocked] = blocked
                    it[updatedAt] = now
                }
            }
        } catch (_: ExposedSQLException) {
            update()
        }
    }
}
