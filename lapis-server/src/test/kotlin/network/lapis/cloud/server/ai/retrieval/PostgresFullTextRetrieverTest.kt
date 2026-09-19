package network.lapis.cloud.server.ai.retrieval

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import network.lapis.cloud.server.ai.AiTestFixtures
import network.lapis.cloud.server.db.DatabaseConfig
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.DocumentAccessLevel
import org.jetbrains.exposed.v1.core.QueryBuilder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.jdbc.transactions.transaction

/**
 * SQL-shape test for [PostgresFullTextKnowledgeRetriever] -- runs everywhere (CI included) because it
 * only RENDERS the statement, it never executes Postgres-only functions. It pins the two properties
 * that matter: the question is a bound parameter (never a literal) and every authorization/
 * consistency predicate is present.
 */
class PostgresFullTextRetrieverSqlTest :
    FunSpec({
        beforeSpec { DatabaseConfig.connect() }

        val question = "Beitrag zzqsecretterm"
        val tsQuery = ChunkQuerySupport.tokenize(question).joinToString(separator = " | ")

        fun renderedSql(): Pair<String, QueryBuilder> =
            transaction {
                val builder = QueryBuilder(prepared = true)
                ChunkQuerySupport.join
                    .select(ChunkQuerySupport.join.columns + PostgresFullTextKnowledgeRetriever.rankExpression(tsQuery))
                    .where {
                        ChunkQuerySupport.authorizedOp(DocumentAccessLevel.entries) and PostgresFullTextKnowledgeRetriever.matchOp(tsQuery)
                    }.prepareSQL(builder) to builder
            }

        test("the question is bound as a parameter and never appears in the SQL text") {
            val (sql, _) = renderedSql()
            sql shouldNotContain "zzqsecretterm"
            sql shouldNotContain "beitrag"
            sql shouldContain "to_tsquery('german', ?)"
            sql shouldContain "to_tsvector('german', "
        }

        test("all authorization and consistency predicates are present") {
            val (sql, _) = renderedSql()
            sql.lowercase() shouldContain "is_deleted"
            sql.lowercase() shouldContain "access_level in ("
            sql.lowercase() shouldContain "current_version_id"
            sql.lowercase() shouldContain "join ai_knowledge_release"
            sql.lowercase() shouldContain "join document_version"
        }

        test("the tsquery argument is the token OR-list, bound twice (match and rank)") {
            val (_, builder) = renderedSql()
            val texts = builder.args.map { it.second }.filterIsInstance<String>()
            texts.count { it == "beitrag | zzqsecretterm" } shouldBe 2
        }

        test("retriever selection follows LAPIS_DB_URL") {
            KnowledgeRetrievers.forCurrentDatabase { null }.shouldBeInstanceOfSimple()
            (KnowledgeRetrievers.forCurrentDatabase { "jdbc:postgresql://db/lapis" } is PostgresFullTextKnowledgeRetriever) shouldBe true
            KnowledgeRetrievers.isPostgres { "jdbc:h2:mem:x" } shouldBe false
        }
    })

private fun KnowledgeRetriever.shouldBeInstanceOfSimple() {
    (this is SimpleLikeKnowledgeRetriever) shouldBe true
}

/**
 * **Manual verification of the Postgres full-text path** (this repo has no Testcontainers, so CI
 * cannot run it -- see `PostgresFullTextKnowledgeRetriever` KDoc). Enabled only when
 * `LAPIS_TEST_POSTGRES_URL` (plus optional `LAPIS_TEST_POSTGRES_USER`/`_PASSWORD`) points at a
 * **migrated, disposable** PostgreSQL database. Run it once against the real instance before the
 * first productive use of the AI layer.
 */
class PostgresFullTextRetrieverLiveTest :
    FunSpec({
        val url = System.getenv("LAPIS_TEST_POSTGRES_URL")

        test("live: full-text search respects release, level and current version").config(enabled = url != null) {
            val previous = TransactionManager.defaultDatabase
            val db =
                Database.connect(
                    url = url!!,
                    user = System.getenv("LAPIS_TEST_POSTGRES_USER") ?: "",
                    password = System.getenv("LAPIS_TEST_POSTGRES_PASSWORD") ?: "",
                )
            TransactionManager.defaultDatabase = db
            val fixtures = AiTestFixtures()
            try {
                PostgresFullTextIndexInitializer.ensureIndexes()
                val admin = fixtures.member(role = AccountRole.ADMIN)
                val doc = fixtures.document(author = admin)
                fixtures.release(document = doc, by = admin)
                fixtures.chunk(document = doc, text = "Der Mitgliedsbeitrag beträgt zehn Euro im Monat.", label = "§ 7")
                val retriever = PostgresFullTextKnowledgeRetriever()
                retriever.search(
                    query = "Wie hoch ist der Mitgliedsbeitrag?",
                    allowedLevels = listOf(DocumentAccessLevel.PUBLIC_MEMBERS),
                    topK = 5,
                ) shouldHaveSize
                    1
                fixtures.setAccessLevel(document = doc, level = DocumentAccessLevel.BOARD_ONLY)
                retriever
                    .search(
                        query = "Mitgliedsbeitrag",
                        allowedLevels = listOf(DocumentAccessLevel.PUBLIC_MEMBERS),
                        topK = 5,
                    ).shouldBeEmpty()
            } finally {
                fixtures.cleanup()
                TransactionManager.defaultDatabase = previous
            }
        }
    })
