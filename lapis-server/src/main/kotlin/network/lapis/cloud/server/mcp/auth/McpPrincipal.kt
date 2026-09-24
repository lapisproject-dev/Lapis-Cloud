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
)
