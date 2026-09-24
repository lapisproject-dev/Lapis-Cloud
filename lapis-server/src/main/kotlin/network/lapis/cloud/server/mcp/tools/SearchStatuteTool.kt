package network.lapis.cloud.server.mcp.tools

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import network.lapis.cloud.server.ai.retrieval.KnowledgeRetriever
import network.lapis.cloud.server.mcp.auth.McpPrincipal
import network.lapis.cloud.shared.domain.DocumentAccessLevel
import org.jetbrains.exposed.v1.jdbc.transactions.transaction

/**
 * `search_statute` -- full-text retrieval over released, `PUBLIC_MEMBERS` documents only. **No LLM
 * call, no dependency on [network.lapis.cloud.server.ai.config.AiConfig]/`LAPIS_AI_ENABLED`** --
 * see `network.lapis.cloud.server.mcp.McpLayerBoundary` KDoc R5, the one deliberate exception to
 * "never import `ai/qa`/`ai/llm`". Returns passages plus citations; the calling agent -- not this
 * server -- synthesizes an answer.
 *
 * **`allowedLevels` is hard-wired to `[PUBLIC_MEMBERS]`**, never role-derived: [McpPrincipal]
 * carries no role (only `memberId`/`tokenId`/`scope`, see that class KDoc), and every valid MCP
 * principal is already guaranteed to be an `ORGANIZATION_MEMBER` by `McpTokenAuth` -- exactly the
 * membership condition `PUBLIC_MEMBERS` requires. This mirrors
 * `network.lapis.cloud.server.rpc.AiAssistantService`'s own private `AI_READABLE_LEVELS` policy
 * constant (kept as an independent literal here rather than importing that `private` value).
 */
internal class SearchStatuteTool(
    private val retriever: KnowledgeRetriever,
) {
    /** `AI_READABLE_LEVELS`-equivalent, see class KDoc. */
    private val allowedLevels = listOf(DocumentAccessLevel.PUBLIC_MEMBERS)

    @Suppress("UNUSED_PARAMETER")
    fun execute(
        principal: McpPrincipal,
        arguments: JsonObject?,
    ) = transaction {
        val args = arguments ?: throw McpInvalidToolArgumentsException("'query' is required")
        val query = args.requiredStringArg(key = "query", minLength = MIN_QUERY, maxLength = MAX_QUERY)
        val topK = args.optionalIntArg(key = "topK", default = DEFAULT_TOP_K, range = MIN_TOP_K..MAX_TOP_K)
        val chunks = retriever.search(query = query, allowedLevels = allowedLevels, topK = topK)
        buildJsonObject {
            putJsonArray("citations") {
                chunks.forEach { chunk ->
                    add(
                        buildJsonObject {
                            put("documentTitle", chunk.documentTitle)
                            put("versionNumber", chunk.versionNumber)
                            put("locator", chunk.sectionLabel ?: chunk.pageNumber?.let { "Seite $it" })
                            put("excerpt", chunk.text.take(500))
                        },
                    )
                }
            }
        }
    }

    companion object {
        private const val MIN_QUERY = 8
        private const val MAX_QUERY = 500
        private const val MIN_TOP_K = 1
        private const val MAX_TOP_K = 6
        private const val DEFAULT_TOP_K = 3

        val definition =
            McpToolDefinition(
                name = "search_statute",
                title = "Satzung/Dokumente durchsuchen",
                description =
                    "Volltextsuche über veröffentlichte, mitgliederöffentliche Dokumente (u. a. Satzung) -- liefert Textstellen " +
                        "mit Quellenangabe, kein KI-generierter Fließtext.",
                inputSchema =
                    buildJsonObject {
                        put("type", "object")
                        putJsonObject("properties") {
                            putJsonObject("query") {
                                put("type", "string")
                                put("minLength", MIN_QUERY)
                                put("maxLength", MAX_QUERY)
                                put("description", "Suchbegriff bzw. Frage.")
                            }
                            putJsonObject("topK") {
                                put("type", "integer")
                                put("minimum", MIN_TOP_K)
                                put("maximum", MAX_TOP_K)
                                put("description", "Maximale Anzahl Treffer (Default $DEFAULT_TOP_K).")
                            }
                        }
                        putJsonArray("required") { add(JsonPrimitive("query")) }
                    },
            )
    }
}
