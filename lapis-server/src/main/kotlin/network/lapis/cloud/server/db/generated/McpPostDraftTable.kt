// Hand-written per ADR-0016 Option B (see 54-mcp-server.kuml.kts file header).

package network.lapis.cloud.server.db.generated

import kotlin.uuid.Uuid
import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.shared.domain.McpPostDraftStatus
import network.lapis.cloud.shared.domain.SocialPostVisibility
import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.datetime.datetime

/**
 * Welle V1.8.2 "MCP-Server: Schreibwerkzeuge". An agent's unpublished draft -- **not** a
 * [SocialPostTable] row. Only the member's own release action (`SocialNetworkService
 * .releaseMyPostDraft`) turns a draft into a real, LTR-staked, moderatable `social_post`; see
 * `docs/architecture/mcp-server.adoc` "Why there is no DRAFT state in SocialPostState".
 *
 * [tokenId] is deliberately **not** a foreign key and **not** the sole record of which connection
 * created the draft -- a token revocation must never break the FK, and (more importantly) must
 * never make the draft's own provenance unreadable, which is why [agentLabel] additionally freezes
 * the connection's self-declared name at creation time (see that column's own KDoc below).
 */
public object McpPostDraftTable : Table("mcp_post_draft") {
    public val id: Column<Uuid> = uuid("id")
    public val memberId: Column<Uuid> = reference("member_id", MemberTable.id)

    /**
     * The MCP OAuth token that created this draft, at creation time -- kept WITHOUT a foreign key
     * (same posture as [McpToolCallAuditTable.tokenId]) so a later token revocation/deletion never
     * touches this row. Deliberately NOT the only trace of provenance -- see [agentLabel].
     */
    public val tokenId: Column<Uuid?> = uuid("token_id").nullable()

    /**
     * A snapshot of the connection's self-declared `connection_label` at the moment the draft was
     * created. [tokenId] alone cannot carry provenance across a token revocation/deletion (the
     * label lives on `oidc_issued_token`, which the revoke path may eventually purge) -- exactly
     * the case this column exists to survive, per design-team requirement (Atkinson): the member
     * must still be able to tell which agent produced a draft even after revoking its connection.
     */
    public val agentLabel: Column<String> = varchar("agent_label", 60)
    public val content: Column<String> = text("content")
    public val visibility: Column<SocialPostVisibility> = enumerationByName<SocialPostVisibility>("visibility", 24)
    public val status: Column<McpPostDraftStatus> = enumerationByName<McpPostDraftStatus>("status", 20)
    public val createdAt: Column<LocalDateTime> = datetime("created_at")
    public val updatedAt: Column<LocalDateTime> = datetime("updated_at")
    public val statusChangedAt: Column<LocalDateTime?> = datetime("status_changed_at").nullable()
    public val releasedPostId: Column<Uuid?> = optReference("released_post_id", SocialPostTable.id)

    override val primaryKey: PrimaryKey = PrimaryKey(id)
}
