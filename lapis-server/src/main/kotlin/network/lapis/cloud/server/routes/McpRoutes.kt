package network.lapis.cloud.server.routes

import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.RoutingContext
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import network.lapis.cloud.server.federation.FederationConfig
import network.lapis.cloud.server.mcp.McpResource
import network.lapis.cloud.server.mcp.auth.McpPrincipal
import network.lapis.cloud.server.mcp.auth.McpTokenAuth
import network.lapis.cloud.server.mcp.config.McpConfig
import network.lapis.cloud.server.mcp.tools.McpToolCallResult
import network.lapis.cloud.server.mcp.tools.McpToolCatalog
import network.lapis.cloud.server.mcp.tools.McpToolDispatcher
import network.lapis.cloud.server.mcp.transport.MCP_PROTOCOL_VERSION
import network.lapis.cloud.server.mcp.transport.McpJsonRpcErrorCode
import network.lapis.cloud.server.mcp.transport.McpJsonRpcRequest
import network.lapis.cloud.server.mcp.transport.mcpInitializeResult
import network.lapis.cloud.server.mcp.transport.receiveCappedTextOrNull

private val MCP_JSON = Json { ignoreUnknownKeys = true }

/**
 * `POST /mcp` -- the sole MCP transport endpoint, JSON-RPC 2.0, five methods
 * (`initialize`/`notifications/initialized`/`tools/list`/`tools/call`/`ping`), protocol version
 * hard-pinned to `2025-06-18` (see `mcp.transport.MCP_PROTOCOL_VERSION`). No `Mcp-Session-Id`, no
 * SSE, no server-initiated notifications -- every request is stateless and self-contained.
 *
 * **Auth runs before any body parsing** (besides the size check) -- see
 * `mcp.auth.McpTokenAuth.resolve` KDoc for why every failure mode collapses to one identical 401.
 * **Every response carries `Cache-Control: no-store` and `Vary: Authorization`**, including error
 * responses -- same posture `PublicApiSupport.applyPublicApiHeaders` already establishes elsewhere
 * in this codebase.
 *
 * **A tool's own exception never escapes to this route handler** -- `McpToolDispatcher.dispatch`
 * catches every failure mode itself (timeout, invalid arguments, `ForbiddenException`, any other
 * `Exception`) and returns a typed [network.lapis.cloud.server.mcp.tools.McpToolCallResult]
 * instead, so neither that exception's own `StatusPages` mapping (`Application.kt` global handler)
 * nor an unhandled 500 can ever produce a non-JSON-RPC-shaped body from this route.
 */
internal fun Route.registerMcpRoutes(
    config: McpConfig,
    dispatcher: McpToolDispatcher,
) {
    get("/mcp") {
        applyMcpHeaders(call = call)
        call.respond(HttpStatusCode.MethodNotAllowed)
    }

    post("/mcp") {
        applyMcpHeaders(call = call)

        val contentLength = call.request.headers[HttpHeaders.ContentLength]?.toLongOrNull()
        if (contentLength != null && contentLength > config.maxRequestBytes) {
            call.respond(HttpStatusCode.PayloadTooLarge)
            return@post
        }

        val origin = call.request.headers[HttpHeaders.Origin]
        if (origin != null && origin != FederationConfig.publicBaseUrl) {
            call.respond(HttpStatusCode.Forbidden)
            return@post
        }

        val protocolVersionHeader = call.request.headers["MCP-Protocol-Version"]
        if (protocolVersionHeader != null && protocolVersionHeader != MCP_PROTOCOL_VERSION) {
            call.respond(HttpStatusCode.BadRequest, "Unsupported MCP-Protocol-Version")
            return@post
        }

        val resolution = McpTokenAuth.resolve(call = call, expectedResource = McpResource.expected())
        if (resolution !is McpTokenAuth.Resolution.Valid) {
            call.response.header(
                HttpHeaders.WWWAuthenticate,
                "Bearer resource_metadata=\"${FederationConfig.publicBaseUrl}/.well-known/oauth-protected-resource/mcp\", error=\"invalid_token\"",
            )
            call.respond(HttpStatusCode.Unauthorized)
            return@post
        }
        val principal = resolution.principal

        val bodyText = call.receiveCappedTextOrNull(maxBytes = config.maxRequestBytes)
        if (bodyText == null) {
            call.respond(HttpStatusCode.PayloadTooLarge)
            return@post
        }

        val bodyElement = runCatching { MCP_JSON.parseToJsonElement(bodyText) }.getOrNull()
        if (bodyElement == null) {
            call.respondText(
                errorResponse(id = JsonNull, code = McpJsonRpcErrorCode.PARSE_ERROR, message = "Parse error"),
                contentType = ContentType.Application.Json,
            )
            return@post
        }
        if (bodyElement is JsonArray) {
            call.respondText(
                errorResponse(id = JsonNull, code = McpJsonRpcErrorCode.INVALID_REQUEST, message = "Batch requests are not supported"),
                contentType = ContentType.Application.Json,
            )
            return@post
        }
        val request =
            runCatching { MCP_JSON.decodeFromJsonElement(McpJsonRpcRequest.serializer(), bodyElement) }.getOrNull()
        if (request == null) {
            call.respondText(
                errorResponse(id = JsonNull, code = McpJsonRpcErrorCode.INVALID_REQUEST, message = "Invalid Request"),
                contentType = ContentType.Application.Json,
            )
            return@post
        }

        when (request.method) {
            "initialize" -> {
                // Same hard pin as the MCP-Protocol-Version HEADER check above (line ~84) --
                // `initialize`'s own params.protocolVersion is the OTHER place a client states its
                // version, and a mismatch there must be rejected the same way, never silently
                // accepted (see class KDoc + MCP_PROTOCOL_VERSION KDoc).
                val requestedProtocolVersion =
                    ((request.params as? JsonObject)?.get("protocolVersion") as? JsonPrimitive)?.contentOrNull
                if (requestedProtocolVersion != null && requestedProtocolVersion != MCP_PROTOCOL_VERSION) {
                    call.respondText(
                        errorResponse(
                            id = request.id,
                            code = McpJsonRpcErrorCode.INVALID_PARAMS,
                            message = "Unsupported protocolVersion: $requestedProtocolVersion",
                        ),
                        contentType = ContentType.Application.Json,
                    )
                    return@post
                }
                call.respondText(
                    successResponse(id = request.id, result = mcpInitializeResult()),
                    contentType = ContentType.Application.Json,
                )
            }
            "notifications/initialized" -> call.respond(HttpStatusCode.Accepted)
            "ping" ->
                call.respondText(
                    successResponse(id = request.id, result = JsonObject(emptyMap())),
                    contentType = ContentType.Application.Json,
                )
            "tools/list" ->
                call.respondText(
                    successResponse(id = request.id, result = toolsListResult()),
                    contentType = ContentType.Application.Json,
                )
            "tools/call" -> handleToolsCall(request = request, principal = principal, dispatcher = dispatcher)
            else ->
                call.respondText(
                    errorResponse(
                        id = request.id,
                        code = McpJsonRpcErrorCode.METHOD_NOT_FOUND,
                        message = "Method not found: ${request.method}",
                    ),
                    contentType = ContentType.Application.Json,
                )
        }
    }

    if (config.isOperational) {
        get("/.well-known/oauth-protected-resource/mcp") {
            call.respondText(
                buildJsonObject {
                    put("resource", McpResource.expected())
                    putJsonArray("authorization_servers") { add(JsonPrimitive(FederationConfig.publicBaseUrl)) }
                    putJsonArray("scopes_supported") { add(JsonPrimitive("mcp:member_read")) }
                    putJsonArray("bearer_methods_supported") { add(JsonPrimitive("header")) }
                }.toString(),
                contentType = ContentType.Application.Json,
            )
        }
    }
}

private suspend fun RoutingContext.handleToolsCall(
    request: McpJsonRpcRequest,
    principal: McpPrincipal,
    dispatcher: McpToolDispatcher,
) {
    val params = request.params as? JsonObject
    val toolName = (params?.get("name") as? JsonPrimitive)?.contentOrNull
    if (toolName.isNullOrBlank()) {
        call.respondText(
            errorResponse(id = request.id, code = McpJsonRpcErrorCode.INVALID_PARAMS, message = "Missing tool name"),
            contentType = ContentType.Application.Json,
        )
        return
    }
    val arguments = params["arguments"] as? JsonObject

    val outcome = dispatcher.dispatch(name = toolName, arguments = arguments, principal = principal)
    when (outcome) {
        is McpToolCallResult.Success ->
            call.respondText(
                successResponse(id = request.id, result = toolCallSuccess(content = outcome.content)),
                contentType = ContentType.Application.Json,
            )
        is McpToolCallResult.RateLimited -> {
            call.response.header(HttpHeaders.RetryAfter, outcome.retryAfterSeconds.toString())
            call.respondText(
                errorResponse(
                    id = request.id,
                    code = McpJsonRpcErrorCode.APPLICATION_ERROR,
                    message = "Rate limit exceeded",
                    data = buildJsonObject { put("retryAfterSeconds", outcome.retryAfterSeconds) },
                ),
                contentType = ContentType.Application.Json,
            )
        }
        McpToolCallResult.UnknownTool ->
            call.respondText(
                successResponse(id = request.id, result = toolCallError(text = "Unknown tool: $toolName")),
                contentType = ContentType.Application.Json,
            )
        is McpToolCallResult.InvalidArguments ->
            call.respondText(
                successResponse(id = request.id, result = toolCallError(text = outcome.reason)),
                contentType = ContentType.Application.Json,
            )
        McpToolCallResult.Forbidden ->
            call.respondText(
                successResponse(id = request.id, result = toolCallError(text = "Not permitted for this account")),
                contentType = ContentType.Application.Json,
            )
        McpToolCallResult.Timeout ->
            call.respondText(
                successResponse(id = request.id, result = toolCallError(text = "Tool timed out")),
                contentType = ContentType.Application.Json,
            )
        is McpToolCallResult.InternalError ->
            call.respondText(
                successResponse(id = request.id, result = toolCallError(text = outcome.reason)),
                contentType = ContentType.Application.Json,
            )
    }
}

private fun applyMcpHeaders(call: ApplicationCall) {
    call.response.header(HttpHeaders.CacheControl, "no-store")
    call.response.header(HttpHeaders.Vary, HttpHeaders.Authorization)
}

private fun toolsListResult(): JsonObject =
    buildJsonObject {
        putJsonArray("tools") {
            McpToolCatalog.TOOLS.forEach { tool ->
                add(
                    buildJsonObject {
                        put("name", tool.name)
                        put("title", tool.title)
                        put("description", tool.description)
                        put("inputSchema", tool.inputSchema)
                    },
                )
            }
        }
    }

private fun toolCallSuccess(content: JsonElement): JsonObject =
    buildJsonObject {
        putJsonArray("content") {
            add(
                buildJsonObject {
                    put("type", "text")
                    put("text", content.toString())
                },
            )
        }
        put("isError", false)
    }

private fun toolCallError(text: String): JsonObject =
    buildJsonObject {
        putJsonArray("content") {
            add(
                buildJsonObject {
                    put("type", "text")
                    put("text", text)
                },
            )
        }
        put("isError", true)
    }

private fun successResponse(
    id: JsonElement?,
    result: JsonElement,
): String =
    buildJsonObject {
        put("jsonrpc", "2.0")
        put("id", id ?: JsonNull)
        put("result", result)
    }.toString()

private fun errorResponse(
    id: JsonElement?,
    code: Int,
    message: String,
    data: JsonElement? = null,
): String =
    buildJsonObject {
        put("jsonrpc", "2.0")
        put("id", id ?: JsonNull)
        putJsonObject("error") {
            put("code", code)
            put("message", message)
            if (data != null) put("data", data)
        }
    }.toString()
