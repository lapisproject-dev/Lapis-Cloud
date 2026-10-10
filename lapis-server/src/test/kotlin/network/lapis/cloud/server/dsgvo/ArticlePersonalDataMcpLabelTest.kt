package network.lapis.cloud.server.dsgvo

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import kotlinx.datetime.LocalDate
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import network.lapis.cloud.server.articles.ArticleStore
import network.lapis.cloud.server.db.DatabaseConfig
import network.lapis.cloud.server.db.DbClock
import network.lapis.cloud.server.db.generated.AccountTable
import network.lapis.cloud.server.db.generated.ArticleTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.ArticleDraftInput
import network.lapis.cloud.shared.domain.ErasureMode
import network.lapis.cloud.shared.domain.MemberStatus
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.uuid.Uuid

/** Welle V1.9.100 -- `article.mcp_agent_label` in the DSGVO export and on erasure. */
class ArticlePersonalDataMcpLabelTest :
    FunSpec({
        val memberId = Uuid.random()

        beforeSpec {
            DatabaseConfig.connect()
            transaction {
                MemberTable.insert {
                    it[MemberTable.id] = memberId
                    it[displayName] = "Artikel DSGVO Test"
                    it[email] = "article-dsgvo-$memberId@example.org"
                    it[status] = MemberStatus.ACTIVE
                    it[joinedAt] = LocalDate(2026, 1, 1)
                }
                AccountTable.insert {
                    it[AccountTable.id] = Uuid.random()
                    it[AccountTable.memberId] = memberId
                    it[role] = AccountRole.BOARD
                }
            }
        }

        afterSpec {
            transaction {
                ArticleTable.deleteWhere { authorId eq memberId }
                AccountTable.deleteWhere { AccountTable.memberId eq memberId }
                MemberTable.deleteWhere { id eq memberId }
            }
        }

        test(
            "export carries mcpAgentLabel for an assistant draft only; erasure replaces the label with the fixed placeholder and keeps the article",
        ) {
            val now = DbClock.nowLocalDateTime()
            val (mcpId, manualId) =
                transaction {
                    ArticleStore.insertMcpDraft(
                        authorId = memberId,
                        title = "Per Agent",
                        excerpt = "x",
                        body = "y",
                        agentLabel = "Max Mustermanns Laptop",
                        now = now,
                    ) to
                        ArticleStore.insertDraft(
                            authorId = memberId,
                            input = ArticleDraftInput(title = "Von Hand", excerpt = "x", body = "y"),
                            now = now,
                        )
                }

            val export = transaction { ArticlePersonalData.exportMember(memberId) }
            val authored = export["authoredArticles"]!!.jsonArray.map { it.jsonObject }
            authored.first { it["id"]!!.jsonPrimitive.content == mcpId.toString() }["mcpAgentLabel"]!!.jsonPrimitive.content shouldBe
                "Max Mustermanns Laptop"
            authored.first { it["id"]!!.jsonPrimitive.content == manualId.toString() }["mcpAgentLabel"].shouldBeNull()

            val outcomes = transaction { ArticlePersonalData.eraseMember(memberId = memberId, mode = ErasureMode.ANONYMIZE) }
            outcomes.single().rowsRetained shouldBe 2

            transaction {
                ArticleTable.selectAll().where { ArticleTable.id eq mcpId }.single()[ArticleTable.mcpAgentLabel] shouldBe "Agent"
                ArticleTable
                    .selectAll()
                    .where { ArticleTable.id eq manualId }
                    .single()[ArticleTable.mcpAgentLabel]
                    .shouldBeNull()
            }
        }
    })
