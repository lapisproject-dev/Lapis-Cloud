package network.lapis.cloud.server.mcp.transport

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * Hard-pinned MCP protocol version -- this server implements exactly one revision, never
 * negotiates down/up. A client sending a different `MCP-Protocol-Version` header (or a different
 * `protocolVersion` inside `initialize`'s own params) is rejected, never silently accepted -- see
 * `routes.McpRoutes` KDoc.
 */
internal const val MCP_PROTOCOL_VERSION = "2025-06-18"

/**
 * `serverInfo.version` reported by `initialize` -- NOT wired to `build.gradle.kts`' own
 * `version = "0.X.Y"` (no build-time constant-injection mechanism exists in this codebase yet, see
 * CLAUDE.md "Release-Choreographie"). Bump by hand alongside that file until such a mechanism
 * exists -- checked by `McpConformanceTest` only for shape (a non-blank string), never for the
 * exact value.
 */
internal const val MCP_SERVER_VERSION = "0.23.0"

/** `initialize` result -- server capabilities are read-only tools, no resources, no prompts, no sampling, no `listChanged` (the catalog never changes at runtime). */
internal fun mcpInitializeResult(): JsonObject =
    buildJsonObject {
        put("protocolVersion", MCP_PROTOCOL_VERSION)
        putJsonObject("capabilities") {
            putJsonObject("tools") {
                put("listChanged", false)
            }
        }
        putJsonObject("serverInfo") {
            put("name", "lapis-cloud")
            put("version", MCP_SERVER_VERSION)
        }
    }
