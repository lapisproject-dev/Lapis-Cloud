package network.lapis.cloud.server.mcp.tools

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import network.lapis.cloud.server.mcp.auth.McpPrincipal
import network.lapis.cloud.server.rpc.ContributionReads
import org.jetbrains.exposed.v1.jdbc.transactions.transaction

/**
 * `get_my_contribution_status` -- self-service summary only, extracted from
 * [network.lapis.cloud.server.rpc.ContributionService.getMemberContributionSummary] via
 * [ContributionReads]. Takes no arguments; `memberId` is never a parameter, see
 * `network.lapis.cloud.server.mcp.McpLayerBoundary` KDoc R4.
 */
internal object GetMyContributionStatusTool {
    val definition =
        McpToolDefinition(
            name = "get_my_contribution_status",
            title = "Eigener Beitragsstand",
            description =
                "Zeigt den eigenen Mitgliedsbeitrags-Stand: fällig, bezahlt, offen -- ausschließlich für das anfragende Mitglied selbst.",
            inputSchema =
                buildJsonObject {
                    put("type", "object")
                    putJsonObject("properties") {}
                },
        )

    fun execute(principal: McpPrincipal): JsonElement =
        transaction {
            val summary = ContributionReads.summaryFor(memberId = principal.memberId)
            buildJsonObject {
                put("totalDue", summary.totalDue.toPlainString())
                put("totalPaid", summary.totalPaid.toPlainString())
                put("totalOpen", summary.totalOpen.toPlainString())
                put("openContributionCount", summary.openContributionCount)
            }
        }
}
