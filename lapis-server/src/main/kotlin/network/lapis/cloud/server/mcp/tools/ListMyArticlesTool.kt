package network.lapis.cloud.server.mcp.tools

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import network.lapis.cloud.server.articles.McpArticleDraftStore
import network.lapis.cloud.server.mcp.auth.McpPrincipal

/**
 * `list_my_articles` -- Welle V1.9.100, read-only companion of [CreateArticleDraftTool]. Lists the
 * calling member's OWN articles (title, status, last change, whether an assistant created it) --
 * never body, excerpt or the connection label. Same editorial-only gate as the write tool.
 * `ArticleStatus` appears only as `.name`, see `McpStructureTest` R7.
 */
internal object ListMyArticlesTool {
    private const val DEFAULT_LIMIT = 20
    private const val MAX_LIMIT = 50

    val definition =
        McpToolDefinition(
            name = "list_my_articles",
            title = "Eigene Artikel auflisten",
            description =
                "Listet Ihre eigenen Artikel (nur Titel, Status und Änderungszeitpunkt). Nur für Mitglieder mit " +
                    "Redaktionsrechten (Vorstand oder Administration); für alle anderen wird der Aufruf abgelehnt.",
            inputSchema =
                buildJsonObject {
                    put("type", "object")
                    put("additionalProperties", false)
                    putJsonObject("properties") {
                        putJsonObject("limit") {
                            put("type", "integer")
                            put("minimum", 1)
                            put("maximum", MAX_LIMIT)
                            put("description", "Maximale Anzahl, Standard $DEFAULT_LIMIT.")
                        }
                    }
                },
            writing = false,
        )

    fun execute(
        principal: McpPrincipal,
        arguments: JsonObject?,
    ): JsonElement {
        val args = arguments ?: JsonObject(emptyMap())
        args.rejectUnknownArgs(setOf("limit"))
        val limit = args.optionalIntArg(key = "limit", default = DEFAULT_LIMIT, range = 1..MAX_LIMIT)
        val rows = McpArticleDraftStore.listOwn(memberId = principal.memberId, limit = limit)
        return buildJsonObject {
            put(
                "articles",
                buildJsonArray {
                    rows.forEach { row ->
                        add(
                            buildJsonObject {
                                put("articleId", row.id.toString())
                                put("title", row.title)
                                put("status", row.status.name)
                                put("updatedAt", row.updatedAt.toString())
                                put("createdViaAssistant", row.createdViaAssistant)
                            },
                        )
                    }
                },
            )
        }
    }
}
