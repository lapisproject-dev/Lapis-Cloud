package network.lapis.cloud.shared.rpc

import dev.kilua.rpc.annotations.RpcService
import network.lapis.cloud.shared.domain.McpAccessStateDto

/**
 * Welle V1.8.1 "MCP-Server für Mitglieder-Agenten (Fundament, lesend)" -- member-facing kill-switch
 * and connection list for the MCP layer (see `network.lapis.cloud.server.mcp.McpLayerBoundary`).
 * Registered unconditionally in `Application.module` (unlike [IAiAssistantService], which is only
 * conditionally registered) -- when MCP is not operational every method throws
 * [McpFeatureDisabledException] through the normal RPC protocol instead of an unhandled 500 (same
 * `DisabledAiAssistantService` reasoning, see that class KDoc).
 *
 * The switch is the ONLY write path here (besides [revokeConnection], itself a narrowing of the
 * same access, never a grant). There is no `askX`/`callTool` method on this interface -- tool
 * invocation happens exclusively over the separate `POST /mcp` JSON-RPC transport
 * (`network.lapis.cloud.server.routes.registerMcpRoutes`), authenticated by an MCP bearer token,
 * never by the caller's own browser session -- see `McpTokenAuth` KDoc "Cookies are never read".
 */
@RpcService
interface IMcpAccessService {
    /** Current state for the calling member: feature flag, own switch, own connections. */
    suspend fun getMcpAccessState(): McpAccessStateDto

    /**
     * Sets the caller's own switch. Setting it to `false` immediately revokes every MCP token
     * already granted to the caller -- see `McpAccessService.setMcpAccessAllowed` KDoc "the switch
     * always wins".
     */
    suspend fun setMcpAccessAllowed(allowed: Boolean): McpAccessStateDto

    /** Revokes exactly one of the caller's own MCP connections; a foreign [tokenId] has no effect. */
    suspend fun revokeConnection(tokenId: String): McpAccessStateDto
}
