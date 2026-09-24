package network.lapis.cloud.server.mcp.tools

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import network.lapis.cloud.server.mcp.auth.McpPrincipal
import network.lapis.cloud.server.rpc.LtrReads
import org.jetbrains.exposed.v1.jdbc.transactions.transaction

/**
 * `get_my_ltr_balance` -- self-service LTR balance plus up to `limit` recent ledger entries.
 * [network.lapis.cloud.server.rpc.requireLtrEligibleMembership] is enforced by [LtrReads] itself,
 * unchanged -- an MCP-connected FRIEND with no LTR eligibility gets the same refusal the RPC
 * surface already gives (mapped by the dispatcher to `-32000 FORBIDDEN`, see `McpToolDispatcher`).
 */
internal object GetMyLtrBalanceTool {
    private const val MIN_LIMIT = 1
    private const val MAX_LIMIT = 50
    private const val DEFAULT_LIMIT = 10

    val definition =
        McpToolDefinition(
            name = "get_my_ltr_balance",
            title = "Eigener LTR-Kontostand",
            description =
                "Zeigt den eigenen freien LTR-Kontostand und die letzten Kontobewegungen -- ausschließlich für das anfragende " +
                    "Mitglied selbst.",
            inputSchema =
                buildJsonObject {
                    put("type", "object")
                    putJsonObject("properties") {
                        putJsonObject("limit") {
                            put("type", "integer")
                            put("minimum", MIN_LIMIT)
                            put("maximum", MAX_LIMIT)
                            put("description", "Anzahl zuletzt zurückzugebender Kontobewegungen (Default $DEFAULT_LIMIT).")
                        }
                    }
                },
        )

    fun execute(
        principal: McpPrincipal,
        arguments: JsonObject?,
    ) = transaction {
        val limit =
            (arguments ?: JsonObject(emptyMap())).optionalIntArg(
                key = "limit",
                default = DEFAULT_LIMIT,
                range =
                    MIN_LIMIT..MAX_LIMIT,
            )
        val balance = LtrReads.balanceFor(memberId = principal.memberId)
        val entries = LtrReads.entriesFor(memberId = principal.memberId, limit = limit)
        buildJsonObject {
            put("freeBalanceLtr", balance.toPlainString())
            putJsonArray("recentEntries") {
                entries.forEach { entry ->
                    add(
                        buildJsonObject {
                            put("entryType", entry.entryType.name)
                            put("amountLtr", entry.amountLtr.toPlainString())
                            put("note", entry.note)
                            put("createdAt", entry.createdAt.toString())
                        },
                    )
                }
            }
        }
    }
}
