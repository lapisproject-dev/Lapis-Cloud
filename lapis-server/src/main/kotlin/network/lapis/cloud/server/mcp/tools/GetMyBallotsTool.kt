package network.lapis.cloud.server.mcp.tools

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import network.lapis.cloud.server.mcp.auth.McpPrincipal
import network.lapis.cloud.server.rpc.GovernanceSelfReads
import org.jetbrains.exposed.v1.jdbc.transactions.transaction

/**
 * `get_my_ballots` -- self-disclosure only: the caller's own cast ballots (vote title, own stake,
 * own timestamp). **Never [network.lapis.cloud.server.rpc.GovernanceService.listVoteBallots]**,
 * which returns every voter's stake and display name for a vote to any caller -- see
 * [GovernanceSelfReads] KDoc "known finding, not fixed this wave". No aggregate, no vote outcome,
 * no other member's row -- the one tool of the five that touches the governance/voting domain at
 * all, kept deliberately narrow to a pure self-audit.
 */
internal object GetMyBallotsTool {
    private const val MIN_LIMIT = 1
    private const val MAX_LIMIT = 50
    private const val DEFAULT_LIMIT = 10

    val definition =
        McpToolDefinition(
            name = "get_my_ballots",
            title = "Eigene Stimmzettel",
            description =
                "Listet die eigenen abgegebenen Stimmzettel (Wahlbetreff, eigener Einsatz, Zeitpunkt) -- niemals die Stimmzettel " +
                    "anderer Mitglieder, kein Wahlausgang.",
            inputSchema =
                buildJsonObject {
                    put("type", "object")
                    putJsonObject("properties") {
                        putJsonObject("limit") {
                            put("type", "integer")
                            put("minimum", MIN_LIMIT)
                            put("maximum", MAX_LIMIT)
                            put("description", "Maximale Anzahl Stimmzettel (Default $DEFAULT_LIMIT).")
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
        val ballots = GovernanceSelfReads.listMyBallots(memberId = principal.memberId, limit = limit)
        buildJsonObject {
            putJsonArray("ballots") {
                ballots.forEach { ballot ->
                    add(
                        buildJsonObject {
                            put("voteTitle", ballot.voteTitle)
                            put("stakeLtr", ballot.stakeLtr.toPlainString())
                            put("castAt", ballot.castAt.toString())
                        },
                    )
                }
            }
        }
    }
}
