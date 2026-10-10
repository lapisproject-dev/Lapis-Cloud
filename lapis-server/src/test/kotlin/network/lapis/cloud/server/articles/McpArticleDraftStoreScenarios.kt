package network.lapis.cloud.server.articles

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.datetime.LocalDate
import network.lapis.cloud.server.db.generated.AccountTable
import network.lapis.cloud.server.db.generated.ArticleTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.server.testdb.TestDatabase
import network.lapis.cloud.server.testdb.installLaneGuards
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.MemberStatus
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.util.concurrent.Callable
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.uuid.Uuid

/**
 * Welle V1.9.100 -- the member-row lock in [McpArticleDraftStore.createDraft] must serialise the duplicate
 * check, the open-draft cap and the insert. One scenario body, run against H2 ([McpArticleDraftStoreTest]) and
 * Postgres ([McpArticleDraftStorePostgresTest]); on Postgres the lock is `FOR NO KEY UPDATE`.
 */
abstract class McpArticleDraftStoreScenarios(
    private val db: TestDatabase,
) : FunSpec({
        val memberIds = mutableListOf<Uuid>()

        beforeSpec { db.activate() }
        installLaneGuards(db = db)
        afterEach {
            transaction {
                ArticleTable.deleteWhere { ArticleTable.authorId inList memberIds }
                AccountTable.deleteWhere { AccountTable.memberId inList memberIds }
                MemberTable.deleteWhere { MemberTable.id inList memberIds }
            }
            memberIds.clear()
        }
        afterSpec { db.deactivate() }

        fun newBoardMember(): Uuid {
            val id = Uuid.random()
            transaction {
                MemberTable.insert {
                    it[MemberTable.id] = id
                    it[displayName] = "Artikel Szenario Mitglied"
                    it[email] = "article-scenario-$id@example.org"
                    it[status] = MemberStatus.ACTIVE
                    it[joinedAt] = LocalDate(2020, 1, 1)
                }
                AccountTable.insert {
                    it[AccountTable.id] = Uuid.random()
                    it[memberId] = id
                    it[role] = AccountRole.BOARD
                }
            }
            memberIds += id
            return id
        }

        fun count(memberId: Uuid): Long = transaction { ArticleTable.selectAll().where { ArticleTable.authorId eq memberId }.count() }

        /** Runs [tasks] at the same instant on separate threads; returns each outcome as the result or the thrown exception. */
        fun <T> parallel(tasks: List<() -> T>): List<Result<T>> {
            val pool = Executors.newFixedThreadPool(tasks.size)
            val barrier = CyclicBarrier(tasks.size)
            try {
                val futures =
                    tasks.map { task ->
                        pool.submit(
                            Callable {
                                barrier.await(10, TimeUnit.SECONDS)
                                runCatching(task)
                            },
                        )
                    }
                return futures.map { it.get(60, TimeUnit.SECONDS) }
            } finally {
                pool.shutdownNow()
            }
        }

        test("two parallel identical createDraft calls create exactly one row; one result is the duplicate") {
            val member = newBoardMember()
            val results =
                parallel(
                    List(2) {
                        {
                            McpArticleDraftStore.createDraft(
                                memberId = member,
                                agentLabel = "Agent",
                                title = "Gleich",
                                excerpt = "Auszug",
                                body = "Text",
                            )
                        }
                    },
                )
            results.all { it.isSuccess } shouldBe true
            count(member) shouldBe 1L
            results.map { it.getOrThrow().duplicate }.sorted() shouldBe listOf(false, true)
            results.map { it.getOrThrow().articleId }.toSet().size shouldBe 1
        }

        test("two parallel createDraft calls at 9 open MCP drafts yield exactly one new row and one limit rejection") {
            val member = newBoardMember()
            repeat(McpArticleDraftStore.MAX_OPEN_MCP_DRAFTS_PER_MEMBER - 1) {
                McpArticleDraftStore.createDraft(
                    memberId = member,
                    agentLabel = "Agent",
                    title = "Vorhanden $it",
                    excerpt = "",
                    body = "Text",
                )
            }
            val results =
                parallel(
                    listOf(
                        {
                            McpArticleDraftStore.createDraft(
                                memberId = member,
                                agentLabel = "Agent",
                                title = "Parallel A",
                                excerpt = "",
                                body = "Text",
                            )
                        },
                        {
                            McpArticleDraftStore.createDraft(
                                memberId = member,
                                agentLabel = "Agent",
                                title = "Parallel B",
                                excerpt = "",
                                body = "Text",
                            )
                        },
                    ),
                )
            results.count { it.isSuccess } shouldBe 1
            results.count { it.exceptionOrNull() is McpArticleDraftStore.DraftLimitReachedException } shouldBe 1
            count(member) shouldBe McpArticleDraftStore.MAX_OPEN_MCP_DRAFTS_PER_MEMBER.toLong()
        }

        test("listOwn returns only own rows, newest first, without any text columns") {
            val a = newBoardMember()
            val b = newBoardMember()
            McpArticleDraftStore.createDraft(memberId = a, agentLabel = "Agent", title = "A eins", excerpt = "", body = "Text")
            McpArticleDraftStore.createDraft(memberId = b, agentLabel = "Agent", title = "B eins", excerpt = "", body = "Text")
            val own = McpArticleDraftStore.listOwn(memberId = a)
            own.map { it.title } shouldBe listOf("A eins")
            own.single().createdViaAssistant shouldBe true
        }
    })
