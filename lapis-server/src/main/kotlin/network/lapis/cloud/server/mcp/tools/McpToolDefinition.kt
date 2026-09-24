package network.lapis.cloud.server.mcp.tools

import kotlinx.serialization.json.JsonObject

/** One entry of `tools/list` -- name, human-readable description, and its JSON Schema input shape. */
internal data class McpToolDefinition(
    val name: String,
    val title: String,
    val description: String,
    val inputSchema: JsonObject,
)
