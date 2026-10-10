package network.lapis.cloud.server.mcp.tools

/**
 * The whitelist -- **exactly nine tools: six lesend (read-only), three schreibend**. Pinned by
 * `McpStructureTest` R4: a tenth entry is a new Welle, never a routine commit. No tool signature
 * accepts a caller-supplied `memberId` -- identity always comes from the resolved
 * [network.lapis.cloud.server.mcp.auth.McpPrincipal].
 *
 * **Welle V1.9.100** adds [ListMyArticlesTool] (lesend) and [CreateArticleDraftTool] (schreibend, editorial members only -- checked at call time, not in `tools/list`).
 * **Welle V1.8.2** adds [RegisterForEventTool]/[CreatePostDraftTool] (`writing = true`, see
 * [McpToolDefinition.writing] KDoc) -- both require
 * [network.lapis.cloud.server.federation.OidcScopes.MCP_MEMBER_WRITE]. `routes.McpRoutes`'
 * `tools/list` filters this catalog down to the six read-only entries for a token that lacks
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
            ListMyArticlesTool.definition,
            CreateArticleDraftTool.definition,
        )

    init {
        check(TOOLS.size == 9) { "McpToolCatalog must expose exactly nine tools -- six reading, three writing -- see class KDoc" }
        check(TOOLS.map { it.name }.toSet().size == 9) { "McpToolCatalog tool names must be unique" }
        check(TOOLS.count { it.writing } == 3) { "McpToolCatalog must expose exactly three writing tools -- see class KDoc" }
    }
}
