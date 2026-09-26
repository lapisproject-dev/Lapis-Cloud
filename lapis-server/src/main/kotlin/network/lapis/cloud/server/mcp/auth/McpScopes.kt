package network.lapis.cloud.server.mcp.auth

/**
 * Local mirror of [network.lapis.cloud.server.federation.OidcScopes.MCP_MEMBER_READ]/
 * [network.lapis.cloud.server.federation.OidcScopes.MCP_MEMBER_WRITE] for the `mcp/` package's own
 * use -- kept as distinct constants (not a re-export) so the `mcp/` package never needs an
 * unrelated import chain into `federation/` just to name its own scope strings. Both pairs MUST
 * stay in sync; `McpTokenAuthTest` cross-checks the literals.
 */
internal object McpScopes {
    const val MEMBER_READ = "mcp:member_read"

    /** Welle V1.8.2 -- see [network.lapis.cloud.server.federation.OidcScopes.MCP_MEMBER_WRITE] KDoc. */
    const val MEMBER_WRITE = "mcp:member_write"
}
