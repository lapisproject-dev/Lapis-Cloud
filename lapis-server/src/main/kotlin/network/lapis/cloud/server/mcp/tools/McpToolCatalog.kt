package network.lapis.cloud.server.mcp.tools

/**
 * The whitelist -- **exactly seven tools: five lesend (read-only), two schreibend**. Pinned by
 * `McpStructureTest` R4: an eighth entry is a new Welle, never a routine commit. No tool signature
 * accepts a caller-supplied `memberId` -- identity always comes from the resolved
 * [network.lapis.cloud.server.mcp.auth.McpPrincipal].
 *
 * **Welle V1.8.2** adds [RegisterForEventTool]/[CreatePostDraftTool] (`writing = true`, see
 * [McpToolDefinition.writing] KDoc) -- both require
 * [network.lapis.cloud.server.federation.OidcScopes.MCP_MEMBER_WRITE]. `routes.McpRoutes`'
 * `tools/list` filters this catalog down to the five read-only entries for a token that lacks
 * write scope, rather than advertising a tool the caller could never successfully invoke.
 */
internal object McpToolCatalog {
    val TOOLS: List<McpToolDefinition> =
        listOf(
            GetMyContributionStatusTool.definition,
            GetMyLtrBalanceTool.definition,
            SearchStatuteTool.definition,
            ListUpcomingEventsTool.definition,
            GetMyBallotsTool.definition,
            RegisterForEventTool.definition,
            CreatePostDraftTool.definition,
        )

    init {
        check(TOOLS.size == 7) { "McpToolCatalog must expose exactly seven tools -- five reading, two writing -- see class KDoc" }
        check(TOOLS.map { it.name }.toSet().size == 7) { "McpToolCatalog tool names must be unique" }
        check(TOOLS.count { it.writing } == 2) { "McpToolCatalog must expose exactly two writing tools -- see class KDoc" }
    }
}
