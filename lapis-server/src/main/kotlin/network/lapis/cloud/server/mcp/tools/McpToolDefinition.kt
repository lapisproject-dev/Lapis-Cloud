package network.lapis.cloud.server.mcp.tools

import kotlinx.serialization.json.JsonObject

/** One entry of `tools/list` -- name, human-readable description, and its JSON Schema input shape. */
internal data class McpToolDefinition(
    val name: String,
    val title: String,
    val description: String,
    val inputSchema: JsonObject,
    /**
     * Welle V1.8.2 -- `true` for a write tool (`register_for_event`/`create_post_draft`), which
     * requires [network.lapis.cloud.server.federation.OidcScopes.MCP_MEMBER_WRITE] and is subject
     * to its own, stricter per-tool rate-limit quota (see `ratelimit.McpToolCallRateLimiter`).
     * `McpToolDispatcher` rejects such a call as `Forbidden` for a read-only principal, and
     * `routes.McpRoutes`' `tools/list` omits it entirely from a read-only token's catalog.
     */
    val writing: Boolean = false,
)
