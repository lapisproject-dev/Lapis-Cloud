package network.lapis.cloud.server.mcp.tools

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import network.lapis.cloud.server.mcp.auth.McpPrincipal
import network.lapis.cloud.server.rpc.MemberReads
import network.lapis.cloud.server.social.PostDraftStore
import network.lapis.cloud.shared.domain.MemberStatusSets
import network.lapis.cloud.shared.domain.SocialPostVisibility
import network.lapis.cloud.shared.rpc.ForbiddenException
import org.jetbrains.exposed.v1.jdbc.transactions.transaction

/**
 * `create_post_draft` -- Welle V1.8.2's second write tool. Deliberately creates a [PostDraftStore]
 * row, **never** a `social_post` row -- see `docs/architecture/mcp-server.adoc` "Why there is no
 * DRAFT state in SocialPostState". No `initialWeightLtr` argument anywhere on this tool: a draft
 * carries no LTR stake, the member supplies one only at release time
 * (`rpc.SocialNetworkService.releaseMyPostDraft`, `McpPostDraftReleaseInput.initialWeightLtr`) --
 * see plan §0.2. This tool therefore NEVER touches `LtrLedgerEntryTable`, directly or indirectly.
 */
internal object CreatePostDraftTool {
    private const val MIN_CONTENT_LENGTH = 1

    /** Deliberately identical to `rpc.SocialNetworkService`'s own `MAX_CONTENT_LENGTH` -- a draft must never accept content the eventual release could not. */
    private const val MAX_CONTENT_LENGTH = 5_000

    val definition =
        McpToolDefinition(
            name = "create_post_draft",
            title = "Beitrags-Entwurf anlegen",
            description =
                "Legt einen UNVERÖFFENTLICHTEN Entwurf an. Der Entwurf wird erst sichtbar, wenn das Mitglied ihn selbst in der " +
                    "Lapis-Cloud-Weboberfläche freigibt. Dieses Werkzeug kann keinen Beitrag veröffentlichen.",
            inputSchema =
                buildJsonObject {
                    put("type", "object")
                    putJsonObject("properties") {
                        putJsonObject("content") {
                            put("type", "string")
                            put("minLength", MIN_CONTENT_LENGTH)
                            put("maxLength", MAX_CONTENT_LENGTH)
                            put("description", "Der Beitragstext.")
                        }
                        putJsonObject("visibility") {
                            put("type", "string")
                            putJsonArray("enum") { SocialPostVisibility.entries.forEach { add(JsonPrimitive(it.name)) } }
                            put("description", "Sichtbarkeitsstufe: PUBLIC, MEMBERS_ONLY oder MEMBERS_AND_EXTERNAL.")
                        }
                    }
                    putJsonArray("required") {
                        add(JsonPrimitive("content"))
                        add(JsonPrimitive("visibility"))
                    }
                },
            writing = true,
        )

    fun execute(
        principal: McpPrincipal,
        arguments: JsonObject?,
    ): JsonElement {
        val args = arguments ?: JsonObject(emptyMap())
        val content = args.requiredStringArg(key = "content", minLength = MIN_CONTENT_LENGTH, maxLength = MAX_CONTENT_LENGTH)
        val visibility = args.requiredEnumArg(key = "visibility") { SocialPostVisibility.valueOf(it) }

        // Same "a NON_MEMBER cannot choose MEMBERS_ONLY" guard as
        // rpc.SocialNetworkService.requireVisibilityAllowedFor -- checked again at release time by
        // that same method (this draft-time check is a convenience so an agent does not build up a
        // draft that could never be released), never bypassable through this tool alone since
        // releaseMyPostDraft re-validates from the actual createPost code path.
        val status =
            transaction { MemberReads.getStatus(memberId = principal.memberId) }
                ?: throw ForbiddenException("Not permitted for this account")
        if (status in MemberStatusSets.NON_MEMBER && visibility == SocialPostVisibility.MEMBERS_ONLY) {
            throw ForbiddenException("Not permitted for this account")
        }

        val (draftId, openDraftCount) =
            try {
                PostDraftStore.createDraft(
                    memberId = principal.memberId,
                    tokenId = principal.tokenId,
                    agentLabel = principal.connectionLabel,
                    content = content,
                    visibility = visibility,
                )
            } catch (e: PostDraftStore.DraftLimitReachedException) {
                throw McpDraftLimitReachedException(openDraftCount = e.openDraftCount)
            }

        return buildJsonObject {
            put("draftId", draftId.toString())
            put("openDraftCount", openDraftCount)
            put("requiresMemberApproval", true)
        }
    }
}

/**
 * Welle V1.8.2 -- thrown by [CreatePostDraftTool.execute] when [PostDraftStore
 * .MAX_OPEN_DRAFTS_PER_MEMBER] is already reached. `McpToolDispatcher` maps this to a dedicated
 * `McpToolCallResult.ToolError(code = "draft_limit_reached", ...)` -- deliberately NOT the generic
 * `Forbidden` outcome every other tool-side rejection uses, so the calling agent (and, through it,
 * the member) can see the actual reason and the count, rather than the same opaque "Not permitted"
 * text a scope/business-rule refusal produces.
 */
internal class McpDraftLimitReachedException(
    val openDraftCount: Int,
) : Exception("Maximum open draft count reached")
