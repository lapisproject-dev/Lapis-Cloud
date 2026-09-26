package network.lapis.cloud.shared.domain

import dev.kilua.rpc.types.Decimal
import kotlinx.datetime.LocalDateTime
import kotlinx.serialization.Serializable

/**
 * Welle V1.8.1 "MCP-Server für Mitglieder-Agenten (Fundament, lesend)" -- wire types for the
 * member-facing MCP access control surface, see
 * [network.lapis.cloud.shared.rpc.IMcpAccessService]. Distinct from every AI-assistance DTO
 * ([AiAssistantStateDto] etc.) -- the MCP layer is deliberately decoupled from the AI-assistance
 * layer, see `network.lapis.cloud.server.mcp.McpLayerBoundary` KDoc.
 *
 * This is the state behind one member's own "allow AI agents" switch plus their list of granted
 * MCP connections (one per OAuth grant, distinguished by the self-declared [McpConnectionDto
 * .connectionLabel] the member typed on the consent screen) -- NOT the tool catalog itself (see
 * `network.lapis.cloud.server.mcp.tools.McpToolCatalog` for that, server-only).
 */
@Serializable
data class McpAccessStateDto(
    /** Mirrors `McpConfig.isOperational` -- `false` means the client must not even show the switch. */
    val featureEnabled: Boolean,
    /** The member's own kill-switch -- `false` also means every existing MCP token was revoked (see `McpAccessService.setMcpAccessAllowed` KDoc). */
    val accessAllowed: Boolean,
    val connections: List<McpConnectionDto>,
)

/** One still-valid MCP OAuth grant for the calling member -- never another member's. */
@Serializable
data class McpConnectionDto(
    val tokenId: String,
    /** The agent-chosen connection name the member typed on the MCP consent screen, never a client-supplied identity claim. */
    val connectionLabel: String,
    val grantedAt: LocalDateTime,
    val lastUsedAt: LocalDateTime? = null,
)

// ================================================================================================
// Welle V1.8.2 "MCP-Server: Schreibwerkzeuge" -- an agent's unpublished draft. NOT a
// SocialPostDto/social_post row -- see `docs/architecture/mcp-server.adoc` "Why there is no DRAFT
// state in SocialPostState" and `network.lapis.cloud.server.db.generated.McpPostDraftTable` KDoc.
// ================================================================================================

/** Literal order is load-bearing, same convention as every other domain enum in this codebase. */
@Serializable
enum class McpPostDraftStatus { OPEN, RELEASED, DISCARDED }

/**
 * One draft an MCP agent created on the calling member's behalf via `create_post_draft`, surfaced
 * to the member's own "KI-Entwürfe" screen (`ISocialNetworkService.listMyPostDrafts`,
 * `client.AiDraftsScreen`).
 * [agentLabel] is a frozen snapshot from draft-creation time (see `McpPostDraftTable.agentLabel`
 * KDoc), not a live lookup of the connection -- it survives that connection's revocation.
 */
@Serializable
data class McpPostDraftDto(
    val id: String,
    val content: String,
    val visibility: SocialPostVisibility,
    val status: McpPostDraftStatus,
    val agentLabel: String,
    val createdAt: LocalDateTime,
    val updatedAt: LocalDateTime,
    val releasedPostId: String? = null,
    /**
     * Welle V1.8.2b -- `null` for a still-`OPEN` draft (never transitioned), otherwise when
     * [status] last changed. The client's `AiDraftsScreen` uses this on a `DISCARDED` draft to
     * compute "wiederherstellbar bis" (+7 days, see `social.PostDraftRetention
     * .DISCARDED_RETENTION_DAYS`) -- without it, that deadline is not calculable at all.
     */
    val statusChangedAt: LocalDateTime? = null,
)

/** `ISocialNetworkService.updateMyPostDraft` -- the member edits an OPEN draft before releasing it. */
@Serializable
data class McpPostDraftEditInput(
    val draftId: String,
    val content: String,
    val visibility: SocialPostVisibility,
)

/**
 * `ISocialNetworkService.releaseMyPostDraft` -- a draft carries no LTR stake of its own (a
 * write-tool call never moves/creates/destroys LTR, see `docs/architecture/mcp-server.adoc`
 * "Scopes"), so the member supplies it here, at release time, exactly like the normal compose
 * form does for [SocialPostInput.initialWeightLtr].
 */
@Serializable
data class McpPostDraftReleaseInput(
    val draftId: String,
    val initialWeightLtr: Decimal,
)
