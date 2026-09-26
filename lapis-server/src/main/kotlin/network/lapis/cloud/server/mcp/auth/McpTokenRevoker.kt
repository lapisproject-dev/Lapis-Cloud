package network.lapis.cloud.server.mcp.auth

import kotlinx.datetime.TimeZone
import network.lapis.cloud.server.db.DbClock
import network.lapis.cloud.server.db.generated.OidcIssuedTokenTable
import network.lapis.cloud.server.federation.OidcScopes
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import kotlin.uuid.Uuid

/**
 * Revokes MCP grants -- both entry points scope strictly on tokens whose stored `scope` column is
 * an MCP scope SET (see [OidcScopes.isMcpScopeSet]) so neither can ever touch a guest-federation
 * token belonging to the same member.
 *
 * **Welle V1.8.2 fix**: the stored `scope` column is no longer necessarily the single literal
 * [McpScopes.MEMBER_READ] -- since write tools shipped, a grant may carry
 * `"mcp:member_read mcp:member_write"` (space-joined, see `OidcRoutes.issueTokens`). An exact-string
 * `scope eq MEMBER_READ` comparison therefore silently matches ZERO rows for every write-capable
 * token: `revokeOne`/`revokeAllForMember` would report success (or a truthful "0 rows touched") while
 * leaving the token live until its TTL, and `revokeAllForMember` (the kill-switch path) would leave
 * exactly the write-capable grants active while every read-only grant is correctly revoked. Fixed by
 * mirroring [McpTokenAuth.resolve]'s own Kotlin-side set comparison: select the not-yet-revoked
 * candidate rows first, filter by [OidcScopes.isMcpScopeSet] on the split scope string, THEN update
 * only those ids -- SQL has no portable "space-split column contains token" operator here.
 */
internal object McpTokenRevoker {
    /** Sets `revokedAt` on every not-yet-revoked MCP token of [memberId]. Returns the number of rows touched. Called by `McpAccessService.setMcpAccessAllowed(false)` and by every MCP-scoped refresh-grant block check. */
    fun revokeAllForMember(memberId: Uuid): Int {
        // UTC -- every other column of oidc_issued_token (issued_at/access_expires_at/
        // refresh_expires_at/revoked_at written by OidcRoutes) is UTC; a system-zone "now" here
        // would make revoked_at drift against them by the host's UTC offset.
        val now = DbClock.nowLocalDateTime(zone = TimeZone.UTC)
        return transaction {
            val idsToRevoke =
                OidcIssuedTokenTable
                    .selectAll()
                    .where {
                        (OidcIssuedTokenTable.memberId eq memberId) and
                            OidcIssuedTokenTable.revokedAt.isNull()
                    }.mapNotNull { row -> row[OidcIssuedTokenTable.id].takeIf { row.isMcpScoped() } }
            if (idsToRevoke.isEmpty()) {
                0
            } else {
                OidcIssuedTokenTable.update({
                    (OidcIssuedTokenTable.id inList idsToRevoke) and OidcIssuedTokenTable.revokedAt.isNull()
                }) {
                    it[revokedAt] = now
                }
            }
        }
    }

    /** Revokes exactly one token -- ONLY if it belongs to [memberId] AND carries an MCP scope set. A foreign or non-MCP [tokenId] has no effect; returns whether a row was actually touched. */
    fun revokeOne(
        memberId: Uuid,
        tokenId: Uuid,
    ): Boolean {
        val now = DbClock.nowLocalDateTime(zone = TimeZone.UTC)
        return transaction {
            val row =
                OidcIssuedTokenTable
                    .selectAll()
                    .where {
                        (OidcIssuedTokenTable.id eq tokenId) and
                            (OidcIssuedTokenTable.memberId eq memberId) and
                            OidcIssuedTokenTable.revokedAt.isNull()
                    }.singleOrNull() ?: return@transaction false
            if (!row.isMcpScoped()) return@transaction false
            val updated =
                OidcIssuedTokenTable.update({
                    (OidcIssuedTokenTable.id eq tokenId) and OidcIssuedTokenTable.revokedAt.isNull()
                }) {
                    it[revokedAt] = now
                }
            updated > 0
        }
    }

    /** `true` iff this row's stored space-joined `scope` string is an MCP scope set -- see [OidcScopes.isMcpScopeSet]. */
    private fun ResultRow.isMcpScoped(): Boolean {
        val scopeSet = this[OidcIssuedTokenTable.scope].split(" ").filter { it.isNotBlank() }.toSet()
        return OidcScopes.isMcpScopeSet(scopeSet)
    }
}
