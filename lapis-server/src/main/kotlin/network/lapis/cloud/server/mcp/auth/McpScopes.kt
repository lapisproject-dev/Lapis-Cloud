package network.lapis.cloud.server.mcp.auth

/**
 * Local mirror of [network.lapis.cloud.server.federation.OidcScopes.MCP_MEMBER_READ] for the
 * `mcp/` package's own use -- kept as a distinct constant (not a re-export) so the `mcp/` package
 * never needs an unrelated import chain into `federation/` just to name its own scope string. Both
 * MUST stay in sync; `McpTokenAuthTest` cross-checks the literal.
 */
internal object McpScopes {
    const val MEMBER_READ = "mcp:member_read"
}
