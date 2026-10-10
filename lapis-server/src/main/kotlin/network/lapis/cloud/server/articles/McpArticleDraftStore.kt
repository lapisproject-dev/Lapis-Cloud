package network.lapis.cloud.server.articles

import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.server.db.DbClock
import network.lapis.cloud.server.db.forMemberUpdate
import network.lapis.cloud.server.db.generated.ArticleTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.shared.domain.ArticleStatus
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.isNotNull
import org.jetbrains.exposed.v1.core.neq
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.uuid.Uuid

/**
 * Welle V1.9.100 "MCP-Artikelentwürfe" -- the write/read path of `create_article_draft` and
 * `list_my_articles`, deliberately living OUTSIDE `mcp/` (see `McpStructureTest` R1/R3: no Exposed
 * writes under `mcp/`), same split as `social.PostDraftStore`.
 *
 * Every row created here is a `DRAFT` owned by the calling member, flagged by a non-null
 * `article.mcp_agent_label`. Nothing in this object can submit, approve or publish -- see
 * `McpStructureTest` R7.
 */
internal object McpArticleDraftStore {
    /** Open (not yet `PUBLISHED`) MCP-created articles per member -- see [createDraft] step 4. */
    const val MAX_OPEN_MCP_DRAFTS_PER_MEMBER = 10

    /** Upper bound on how many same-title candidates the duplicate check loads and compares in Kotlin. */
    private const val DUPLICATE_CANDIDATE_LIMIT = 20

    private const val MAX_LIST_LIMIT = 50

    data class CreateResult(
        val articleId: Uuid,
        val duplicate: Boolean,
    )

    /** Message deliberately carries no caller-supplied text. */
    class DraftLimitReachedException(
        val openDraftCount: Int,
    ) : Exception("Maximum open MCP article draft count reached")

    /** Deliberately has no body/excerpt/agent-label text -- see [listOwn]. */
    data class OwnArticleRow(
        val id: Uuid,
        val title: String,
        val status: ArticleStatus,
        val updatedAt: LocalDateTime,
        val createdViaAssistant: Boolean,
    )

    /**
     * One transaction: editorial check -> member-row lock (`forMemberUpdate`, serialises duplicate
     * check, cap and insert per member) -> duplicate check -> caps -> insert.
     *
     * The duplicate check compares `title` in SQL (VARCHAR) but `body`/`excerpt` in Kotlin: H2 2.x
     * cannot compare a TEXT/CLOB column with `=`. It is restricted to `DRAFT` rows that came from
     * MCP, so a manually edited or submitted article is never "deduplicated" onto.
     */
    fun createDraft(
        memberId: Uuid,
        agentLabel: String,
        title: String,
        excerpt: String,
        body: String,
    ): CreateResult =
        transaction {
            ArticleEditorialAccess.requireEditorialMember(memberId)
            MemberTable
                .selectAll()
                .where { MemberTable.id eq memberId }
                .forMemberUpdate()
                .singleOrNull()
                ?: error("Member $memberId not found while locking for article draft creation")

            val duplicate =
                ArticleTable
                    .selectAll()
                    .where {
                        (ArticleTable.authorId eq memberId) and
                            (ArticleTable.status eq ArticleStatus.DRAFT) and
                            ArticleTable.mcpAgentLabel.isNotNull() and
                            (ArticleTable.title eq title)
                    }.limit(DUPLICATE_CANDIDATE_LIMIT)
                    .firstOrNull { it[ArticleTable.excerpt] == excerpt && it[ArticleTable.body] == body }
            if (duplicate != null) return@transaction CreateResult(articleId = duplicate[ArticleTable.id], duplicate = true)

            val openMcpCount =
                ArticleTable
                    .selectAll()
                    .where {
                        (ArticleTable.authorId eq memberId) and
                            ArticleTable.mcpAgentLabel.isNotNull() and
                            (ArticleTable.status neq ArticleStatus.PUBLISHED)
                    }.count()
                    .toInt()
            if (openMcpCount >= MAX_OPEN_MCP_DRAFTS_PER_MEMBER) throw DraftLimitReachedException(openDraftCount = openMcpCount)

            val editableCount = ArticleStore.countEditableByAuthor(memberId)
            if (editableCount >= ArticleStore.MAX_DRAFTS_PER_AUTHOR) {
                throw DraftLimitReachedException(openDraftCount = editableCount.toInt())
            }

            val id =
                ArticleStore.insertMcpDraft(
                    authorId = memberId,
                    title = title,
                    excerpt = excerpt,
                    body = body,
                    agentLabel = agentLabel,
                    now = DbClock.nowLocalDateTime(),
                )
            CreateResult(articleId = id, duplicate = false)
        }

    /**
     * The calling member's OWN articles, newest first. `body`/`excerpt`/`mcp_agent_label` are never
     * selected -- only the flag whether a label exists.
     */
    fun listOwn(
        memberId: Uuid,
        limit: Int = MAX_LIST_LIMIT,
    ): List<OwnArticleRow> =
        transaction {
            ArticleEditorialAccess.requireEditorialMember(memberId)
            ArticleTable
                .select(
                    ArticleTable.id,
                    ArticleTable.title,
                    ArticleTable.status,
                    ArticleTable.updatedAt,
                    ArticleTable.mcpAgentLabel,
                ).where { ArticleTable.authorId eq memberId }
                .orderBy(ArticleTable.updatedAt to SortOrder.DESC)
                .limit(limit.coerceIn(1, MAX_LIST_LIMIT))
                .map {
                    OwnArticleRow(
                        id = it[ArticleTable.id],
                        title = it[ArticleTable.title],
                        status = it[ArticleTable.status],
                        updatedAt = it[ArticleTable.updatedAt],
                        createdViaAssistant = it[ArticleTable.mcpAgentLabel] != null,
                    )
                }
        }
}
