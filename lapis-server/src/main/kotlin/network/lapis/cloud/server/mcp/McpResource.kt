package network.lapis.cloud.server.mcp

import network.lapis.cloud.server.federation.FederationConfig

/**
 * RFC 8707 `resource` indicator this server's own MCP endpoint identifies itself as -- a pure
 * function of [FederationConfig.publicBaseUrl], same derivation style as
 * `FederationConfig.actorUri`/`inboxUri`. Every MCP-scoped `/authorize` request MUST name exactly
 * this value; every minted MCP token is bound to it (see `routes.OidcRoutes` MCP branch KDoc).
 */
internal object McpResource {
    fun expected(): String = "${FederationConfig.publicBaseUrl.trimEnd('/')}/mcp"
}
