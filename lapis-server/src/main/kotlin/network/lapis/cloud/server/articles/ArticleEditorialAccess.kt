package network.lapis.cloud.server.articles

import network.lapis.cloud.server.db.generated.AccountTable
import network.lapis.cloud.server.rpc.requireActiveMembership
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.rpc.ForbiddenException
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.selectAll
import kotlin.uuid.Uuid

/**
 * Welle V1.9.100 -- the single source of truth for "who is editorial staff" of the article module.
 * `ArticleService` takes its review roles from [REVIEW_ROLES], and the MCP article-draft tools
 * (`mcp.tools.CreateArticleDraftTool`/`ListMyArticlesTool`, through [McpArticleDraftStore]) gate on
 * [requireEditorialMember] -- so both can never drift apart.
 *
 * The role is read FRESH from `account` x `member` on every call, never from a token or session:
 * an MCP token outlives role changes, so a role baked into it would be a second, ageing source of
 * authority.
 */
internal object ArticleEditorialAccess {
    val REVIEW_ROLES: Set<AccountRole> = setOf(AccountRole.BOARD, AccountRole.ADMIN)

    /**
     * Must run inside an open transaction. Throws an opaque [ForbiddenException] (no role names, no
     * reason) unless [memberId] is an `ACTIVE` member whose account holds one of [REVIEW_ROLES].
     */
    fun requireEditorialMember(memberId: Uuid) {
        requireActiveMembership(memberId = memberId)
        val isEditorial =
            AccountTable
                .selectAll()
                .where { (AccountTable.memberId eq memberId) and (AccountTable.role inList REVIEW_ROLES) }
                .limit(1)
                .any()
        if (!isEditorial) throw ForbiddenException("Not permitted for this account")
    }
}
