package network.lapis.cloud.server.mcp.auth

import kotlinx.datetime.TimeZone
import network.lapis.cloud.server.db.DbClock
import network.lapis.cloud.server.db.generated.OidcIssuedTokenTable
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import kotlin.uuid.Uuid

/**
 * Revokes MCP grants -- both entry points scope strictly on [network.lapis.cloud.server.mcp.auth
 * .McpScopes.MEMBER_READ] so neither can ever touch a guest-federation token belonging to the same
 * member.
 */
internal object McpTokenRevoker {
    /** Sets `revokedAt` on every not-yet-revoked MCP token of [memberId]. Returns the number of rows touched. Called by `McpAccessService.setMcpAccessAllowed(false)` and by every MCP-scoped refresh-grant block check. */
    fun revokeAllForMember(memberId: Uuid): Int {
        // UTC -- every other column of oidc_issued_token (issued_at/access_expires_at/
        // refresh_expires_at/revoked_at written by OidcRoutes) is UTC; a system-zone "now" here
        // would make revoked_at drift against them by the host's UTC offset.
        val now = DbClock.nowLocalDateTime(zone = TimeZone.UTC)
        return transaction {
            OidcIssuedTokenTable.update({
                (OidcIssuedTokenTable.memberId eq memberId) and
                    (OidcIssuedTokenTable.scope eq McpScopes.MEMBER_READ) and
                    OidcIssuedTokenTable.revokedAt.isNull()
            }) {
                it[revokedAt] = now
            }
        }
    }

    /** Revokes exactly one token -- ONLY if it belongs to [memberId] AND carries the MCP scope. A foreign or non-MCP [tokenId] has no effect; returns whether a row was actually touched. */
    fun revokeOne(
        memberId: Uuid,
        tokenId: Uuid,
    ): Boolean {
        val now = DbClock.nowLocalDateTime(zone = TimeZone.UTC)
        val updated =
            transaction {
                OidcIssuedTokenTable.update({
                    (OidcIssuedTokenTable.id eq tokenId) and
                        (OidcIssuedTokenTable.memberId eq memberId) and
                        (OidcIssuedTokenTable.scope eq McpScopes.MEMBER_READ) and
                        OidcIssuedTokenTable.revokedAt.isNull()
                }) {
                    it[revokedAt] = now
                }
            }
        return updated > 0
    }
}
