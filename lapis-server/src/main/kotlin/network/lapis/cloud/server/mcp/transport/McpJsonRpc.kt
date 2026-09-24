package network.lapis.cloud.server.mcp.transport

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/** JSON-RPC 2.0 request -- `id` is `null` for a notification (e.g. `notifications/initialized`). */
@Serializable
internal data class McpJsonRpcRequest(
    val jsonrpc: String = "2.0",
    val id: JsonElement? = null,
    val method: String,
    val params: JsonElement? = null,
)

@Serializable
internal data class McpJsonRpcError(
    val code: Int,
    val message: String,
    val data: JsonElement? = null,
)

@Serializable
internal data class McpJsonRpcResponse(
    val jsonrpc: String = "2.0",
    val id: JsonElement? = null,
    val result: JsonElement? = null,
    val error: McpJsonRpcError? = null,
)

/** Standard JSON-RPC 2.0 error codes this transport uses. `-32000` is this server's own application-level code (rate limit/oversized result), not part of the JSON-RPC spec's reserved range. */
internal object McpJsonRpcErrorCode {
    const val PARSE_ERROR = -32700
    const val INVALID_REQUEST = -32600
    const val METHOD_NOT_FOUND = -32601
    const val INVALID_PARAMS = -32602
    const val INTERNAL_ERROR = -32603
    const val APPLICATION_ERROR = -32000
}
