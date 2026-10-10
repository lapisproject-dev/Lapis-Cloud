package network.lapis.cloud.server.mcp.tools

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import network.lapis.cloud.server.articles.ArticlePolicy
import network.lapis.cloud.server.articles.McpArticleDraftStore
import network.lapis.cloud.server.mcp.auth.McpPrincipal

/**
 * `create_article_draft` -- Welle V1.9.100. Creates an UNPUBLISHED `article` row (`status = DRAFT`)
 * through [McpArticleDraftStore]; there is deliberately no way to submit, approve or publish from
 * here (no status/cover parameter, `McpStructureTest` R7). Only editorial members (ACTIVE and
 * `BOARD`/`ADMIN`, read fresh from the database -- never from the token) may call it; everyone else
 * gets the opaque `ForbiddenException` the dispatcher already maps to `Forbidden`.
 *
 * The title/excerpt limits mirror [ArticlePolicy]; the body limit [BODY_MAX] is deliberately
 * harder than `ArticlePolicy.BODY_MAX` (100 000): an agent-supplied draft is capped to what fits
 * comfortably into the `McpConfig.DEFAULT_MAX_REQUEST_BYTES` request and a reviewable article.
 * No sanitisation is needed here: `ArticleMarkdown.render` already neutralises raw HTML and
 * `javascript:` links at render time.
 */
internal object CreateArticleDraftTool {
    const val TITLE_MIN = 1
    const val TITLE_MAX = ArticlePolicy.TITLE_MAX
    const val BODY_MIN = 1
    const val BODY_MAX = 20_000
    const val EXCERPT_MAX = ArticlePolicy.EXCERPT_MAX

    /** `article.mcp_agent_label` is VARCHAR(60), same width as `oidc_issued_token.connection_label`. */
    private const val AGENT_LABEL_MAX = 60

    private val ALLOWED_ARGS = setOf("title", "body", "excerpt")

    val definition =
        McpToolDefinition(
            name = "create_article_draft",
            title = "Artikel-Entwurf anlegen",
            description =
                "Legt einen UNVERÖFFENTLICHTEN Artikel-Entwurf an. Nur für Mitglieder mit Redaktionsrechten " +
                    "(Vorstand oder Administration); für alle anderen wird der Aufruf abgelehnt. Dieses Werkzeug kann " +
                    "nicht einreichen, freigeben oder veröffentlichen.",
            inputSchema =
                buildJsonObject {
                    put("type", "object")
                    put("additionalProperties", false)
                    putJsonObject("properties") {
                        putJsonObject("title") {
                            put("type", "string")
                            put("minLength", TITLE_MIN)
                            put("maxLength", TITLE_MAX)
                            put("description", "Der Titel des Artikels.")
                        }
                        putJsonObject("body") {
                            put("type", "string")
                            put("minLength", BODY_MIN)
                            put("maxLength", BODY_MAX)
                            put("description", "Der Artikeltext in Markdown.")
                        }
                        putJsonObject("excerpt") {
                            put("type", "string")
                            put("minLength", 0)
                            put("maxLength", EXCERPT_MAX)
                            put("description", "Optionale Kurzfassung (Teaser).")
                        }
                    }
                    putJsonArray("required") {
                        add(JsonPrimitive("title"))
                        add(JsonPrimitive("body"))
                    }
                },
            writing = true,
        )

    fun execute(
        principal: McpPrincipal,
        arguments: JsonObject?,
    ): JsonElement {
        val args = arguments ?: JsonObject(emptyMap())
        args.rejectUnknownArgs(ALLOWED_ARGS)
        val title =
            args
                .requiredStringArg(key = "title", minLength = TITLE_MIN, maxLength = TITLE_MAX)
                .requireNoControlChars(key = "title", allowNewlines = false)
        val body =
            args
                .requiredStringArg(key = "body", minLength = BODY_MIN, maxLength = BODY_MAX)
                .requireNoControlChars(key = "body", allowNewlines = true)
        val excerpt =
            args
                .optionalStringArg(key = "excerpt", maxLength = EXCERPT_MAX)
                .requireNoControlChars(key = "excerpt", allowNewlines = true)

        val result =
            try {
                McpArticleDraftStore.createDraft(
                    memberId = principal.memberId,
                    agentLabel = principal.connectionLabel.take(AGENT_LABEL_MAX),
                    title = title,
                    excerpt = excerpt,
                    body = body,
                )
            } catch (e: McpArticleDraftStore.DraftLimitReachedException) {
                throw McpArticleDraftLimitReachedException(openDraftCount = e.openDraftCount)
            }

        // No text is echoed back: neither title, excerpt nor body.
        return buildJsonObject {
            put("articleId", result.articleId.toString())
            put("status", "DRAFT")
            put("duplicate", result.duplicate)
        }
    }
}

/**
 * Welle V1.9.100 -- thrown by [CreateArticleDraftTool.execute] when an article-draft cap is reached.
 * `McpToolDispatcher` maps it to `ToolError(code = "article_draft_limit_reached")`, same reasoning as
 * [McpDraftLimitReachedException]. The message carries no caller-supplied text.
 */
internal class McpArticleDraftLimitReachedException(
    val openDraftCount: Int,
) : Exception("Maximum open article draft count reached")
