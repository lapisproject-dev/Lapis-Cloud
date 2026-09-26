package network.lapis.cloud.server.mcp.auth

import kotlin.uuid.Uuid

/**
 * The resolved caller of an MCP request. **Deliberately NOT [network.lapis.cloud.server.security
 * .CurrentMember], no derivation from it, no implicit conversion between the two** -- the type
 * itself is the structural guarantee that no code path can resolve an MCP caller through the
 * session/cookie/API-key machinery, see `McpTokenAuth` KDoc.
 */
internal data class McpPrincipal(
    val memberId: Uuid,
    val tokenId: Uuid,
    val scope: String,
    /**
     * Welle V1.8.2 -- `true` iff this token's stored scope set includes
     * [network.lapis.cloud.server.federation.OidcScopes.MCP_MEMBER_WRITE]. Resolved once in
     * `McpTokenAuth.resolve` from the ACTUAL stored scope, never a blanket upgrade -- a token
     * minted before this scope existed stays read-only forever. `McpToolDispatcher` rejects any
     * `writing` tool call for a principal with `canWrite = false` as `Forbidden`.
     */
    val canWrite: Boolean = false,
    /**
     * Welle V1.8.2 -- the connection's self-declared name at grant time (`oidc_issued_token
     * .connection_label`), read once here in [McpTokenAuth.resolve] rather than a second query per
     * write tool. Used exclusively by `mcp.tools.CreatePostDraftTool` to freeze
     * `mcp_post_draft.agent_label` (see that column's own KDoc for why a live lookup at read time
     * would not survive the connection's later revocation). The one production constructor site
     * ([McpTokenAuth.resolve]) falls back to a fixed placeholder in the practically-unreachable
     * case of a `NULL` column value (every MCP consent grant requires a non-blank label, see
     * `routes.McpConsentPage`) -- that fallback lives there, explicitly, at the call site, NOT as a
     * default here (Security-Review MINOR fix, Welle V1.8.2 MCP write-paths review): `agent_label`
     * is deliberately the ONLY provenance hint that survives a connection's revocation (see
     * `McpPostDraftTable.agentLabel` KDoc), so a stray production/test literal like `"Test Agent"`
     * defaulted in from HERE, at every future construction site that forgets to pass one, would
     * silently mislabel real drafts instead of failing to compile. Every constructor call --
     * production and test alike -- must state this argument explicitly.
     */
    val connectionLabel: String,
)
