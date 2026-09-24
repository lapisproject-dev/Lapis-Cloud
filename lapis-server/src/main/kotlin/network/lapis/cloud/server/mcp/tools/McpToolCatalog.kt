package network.lapis.cloud.server.mcp.tools

/**
 * The whitelist -- **exactly five tools, all lesend (read-only)**. Pinned by `McpStructureTest`
 * R4: a sixth entry is a new Welle, never a routine commit. No tool signature accepts a
 * caller-supplied `memberId` -- identity always comes from the resolved
 * [network.lapis.cloud.server.mcp.auth.McpPrincipal].
 */
internal object McpToolCatalog {
    val TOOLS: List<McpToolDefinition> =
        listOf(
            GetMyContributionStatusTool.definition,
            GetMyLtrBalanceTool.definition,
            SearchStatuteTool.definition,
            ListUpcomingEventsTool.definition,
            GetMyBallotsTool.definition,
        )

    init {
        check(TOOLS.size == 5) { "McpToolCatalog must expose exactly five tools -- see class KDoc" }
        check(TOOLS.map { it.name }.toSet().size == 5) { "McpToolCatalog tool names must be unique" }
    }
}
