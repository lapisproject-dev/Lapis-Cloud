package network.lapis.cloud.shared.domain

import kotlinx.datetime.LocalDateTime
import kotlinx.serialization.Serializable

/**
 * Welle V1.8.1 "MCP-Server für Mitglieder-Agenten (Fundament, lesend)" -- wire types for the
 * member-facing MCP access control surface, see
 * [network.lapis.cloud.shared.rpc.IMcpAccessService]. Distinct from every AI-assistance DTO
 * ([AiAssistantStateDto] etc.) -- the MCP layer is deliberately decoupled from the AI-assistance
 * layer, see `network.lapis.cloud.server.mcp.McpLayerBoundary` KDoc.
 *
 * This is the state behind one member's own "allow AI agents" switch plus their list of granted
 * MCP connections (one per OAuth grant, distinguished by the self-declared [McpConnectionDto
 * .connectionLabel] the member typed on the consent screen) -- NOT the tool catalog itself (see
 * `network.lapis.cloud.server.mcp.tools.McpToolCatalog` for that, server-only).
 */
@Serializable
data class McpAccessStateDto(
    /** Mirrors `McpConfig.isOperational` -- `false` means the client must not even show the switch. */
    val featureEnabled: Boolean,
    /** The member's own kill-switch -- `false` also means every existing MCP token was revoked (see `McpAccessService.setMcpAccessAllowed` KDoc). */
    val accessAllowed: Boolean,
    val connections: List<McpConnectionDto>,
)

/** One still-valid MCP OAuth grant for the calling member -- never another member's. */
@Serializable
data class McpConnectionDto(
    val tokenId: String,
    /** The agent-chosen connection name the member typed on the MCP consent screen, never a client-supplied identity claim. */
    val connectionLabel: String,
    val grantedAt: LocalDateTime,
    val lastUsedAt: LocalDateTime? = null,
)
