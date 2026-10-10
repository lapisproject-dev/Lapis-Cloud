package network.lapis.cloud.server.db

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.datetime.LocalDate
import network.lapis.cloud.server.articles.ArticleStore
import network.lapis.cloud.server.db.generated.AccountTable
import network.lapis.cloud.server.db.generated.ArticleTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.ArticleDraftInput
import network.lapis.cloud.shared.domain.MemberStatus
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.uuid.Uuid

/** Welle V1.9.100 -- `V82__article_mcp_agent_label.sql`: one nullable VARCHAR(60) column, NULL for every ordinary article. */
class ArticleMcpLabelMigrationTest :
    FunSpec({
        beforeSpec { DatabaseConfig.connect() }

        test("article.mcp_agent_label exists, is nullable and 60 characters wide") {
            var nullable: String? = null
            var width: Int? = null
            transaction {
                exec(
                    "SELECT is_nullable, character_maximum_length FROM information_schema.columns " +
                        "WHERE LOWER(table_name) = 'article' AND LOWER(column_name) = 'mcp_agent_label'",
                ) { rs ->
                    if (rs.next()) {
                        nullable = rs.getString(1)
                        width = rs.getInt(2)
                    }
                }
            }
            nullable shouldBe "YES"
            width shouldBe 60
        }

        test("a manually created article has no label") {
            val memberId = Uuid.random()
            transaction {
                MemberTable.insert {
                    it[MemberTable.id] = memberId
                    it[displayName] = "Migration Test"
                    it[email] = "article-migration-$memberId@example.org"
                    it[status] = MemberStatus.ACTIVE
                    it[joinedAt] = LocalDate(2026, 1, 1)
                }
                AccountTable.insert {
                    it[AccountTable.id] = Uuid.random()
                    it[AccountTable.memberId] = memberId
                    it[role] = AccountRole.MEMBER
                }
            }
            try {
                val id =
                    transaction {
                        ArticleStore.insertDraft(
                            authorId = memberId,
                            input = ArticleDraftInput(title = "T", excerpt = "E", body = "B"),
                            now = DbClock.nowLocalDateTime(),
                        )
                    }
                transaction { ArticleTable.selectAll().where { ArticleTable.id eq id }.single()[ArticleTable.mcpAgentLabel] } shouldBe null
            } finally {
                transaction {
                    ArticleTable.deleteWhere { authorId eq memberId }
                    AccountTable.deleteWhere { AccountTable.memberId eq memberId }
                    MemberTable.deleteWhere { id eq memberId }
                }
            }
        }
    })
