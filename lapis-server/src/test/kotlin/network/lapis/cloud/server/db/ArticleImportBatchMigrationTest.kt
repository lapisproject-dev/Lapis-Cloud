package network.lapis.cloud.server.db

import io.kotest.core.annotation.EnabledIf
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import network.lapis.cloud.server.testdb.PostgresConfigured
import java.util.UUID

/**
 * Welle V1.9.106 -- `V86__article_import_batch.sql` on the upgrade path V85 -> V86, on H2 AND on PostgreSQL: an existing article survives with
 * `import_batch_id = NULL`, the column is nullable, the CHECK bites (a batch id needs `imported = TRUE`), the lookup index exists, the audit-log
 * CHECK accepts both new types (the 29 character one included) and every earlier type, rejects unknown ones, and a re-run is a no-op.
 */
abstract class ArticleImportBatchMigrationScenarios(
    private val newHarness: () -> MigrationHarness,
) : FunSpec({
        lateinit var h: MigrationHarness

        fun exec(sql: String) = h.connection { c -> c.createStatement().use { it.execute(sql) } }

        fun rows(sql: String): List<List<String>> =
            h.connection { c ->
                c.createStatement().use { st ->
                    st.executeQuery(sql).use { rs ->
                        val n = rs.metaData.columnCount
                        buildList { while (rs.next()) add((1..n).map { rs.getString(it) ?: "null" }) }
                    }
                }
            }

        fun rejection(sql: String): String? =
            runCatching { exec(sql) }
                .exceptionOrNull()
                ?.let { generateSequence(it) { t -> t.cause }.joinToString(" | ") { t -> t.message.orEmpty() } }

        val memberId = UUID.randomUUID()
        val oldArticle = UUID.randomUUID()
        var seq = 9800
        val dummyHash = "d".repeat(64)

        fun articleInsert(
            id: UUID = UUID.randomUUID(),
            imported: Boolean,
            batch: UUID?,
        ) = "INSERT INTO article (id, slug, title, excerpt, body, author_id, status, created_at, updated_at, imported, import_batch_id) " +
            "VALUES ('$id', 'batch-$id', 'Titel $id', 'x', 'y', '$memberId', 'SUBMITTED', " +
            "TIMESTAMP '2026-10-11 10:00:00', TIMESTAMP '2026-10-11 10:00:00', ${if (imported) "TRUE" else "FALSE"}, " +
            "${batch?.let { "'$it'" } ?: "NULL"})"

        fun auditInsert(entityType: String) =
            "INSERT INTO audit_log_entry (id, sequence_number, occurred_at, entity_type, entity_id, action, entry_hash) " +
                "VALUES ('${UUID.randomUUID()}', ${seq++}, TIMESTAMP '2026-10-11 10:00:00', '$entityType', " +
                "'${UUID.randomUUID()}', 'UPDATE', '$dummyHash')"

        beforeSpec {
            h = newHarness()
            h.flyway("85").migrate()
            exec(
                "INSERT INTO member (id, display_name, email, status, joined_at) VALUES ('$memberId', 'M', 'batch-$memberId@example.org', 'ACTIVE', DATE '2026-01-01')",
            )
            exec(
                "INSERT INTO article (id, slug, title, excerpt, body, author_id, status, created_at, updated_at, imported) " +
                    "VALUES ('$oldArticle', 'old-$oldArticle', 'Alt', 'x', 'y', '$memberId', 'PUBLISHED', " +
                    "TIMESTAMP '2026-01-01 00:00:00', TIMESTAMP '2026-01-01 00:00:00', TRUE)",
            )
            h.flyway(null).migrate()
        }
        afterSpec { h.close() }

        test("an existing (imported) article survives with import_batch_id NULL: nothing is backfilled") {
            rows("SELECT import_batch_id FROM article WHERE id = '$oldArticle'").single().single() shouldBe "null"
        }

        test("the column is nullable and the CHECK demands imported = TRUE for a batch id") {
            rejection(articleInsert(imported = false, batch = null)) shouldBe null
            rejection(articleInsert(imported = true, batch = null)) shouldBe null
            rejection(articleInsert(imported = true, batch = UUID.randomUUID())) shouldBe null
            (rejection(articleInsert(imported = false, batch = UUID.randomUUID())) != null) shouldBe true
        }

        test("the (imported, status, import_batch_id) lookup index exists") {
            val index =
                h.connection { c ->
                    val result = mutableMapOf<String, MutableList<Pair<Short, String>>>()
                    c.metaData.getIndexInfo(null, null, "article", false, false).use { rs ->
                        while (rs.next()) {
                            val name = rs.getString("INDEX_NAME")?.lowercase() ?: continue
                            result.getOrPut(name) { mutableListOf() } +=
                                rs.getShort("ORDINAL_POSITION") to rs.getString("COLUMN_NAME").lowercase()
                        }
                    }
                    result["idx_article_imported_status_batch"]?.sortedBy { it.first }?.map { it.second }
                }
            index shouldBe listOf("imported", "status", "import_batch_id")
        }

        test("the audit-log CHECK accepts both new types (the 29 character one included), keeps earlier types and rejects an unknown one") {
            "ARTICLE_IMPORT_IMAGES_APPLIED".length shouldBe 29
            rejection(auditInsert("ARTICLE_BULK_APPROVED")) shouldBe null
            rejection(auditInsert("ARTICLE_IMPORT_IMAGES_APPLIED")) shouldBe null
            listOf("ARTICLE_IMPORT", "COMMITTEE_MEMBERSHIP", "EVENT_IMPORT", "ARTICLE", "JOURNAL_ENTRY", "CONFERENCE_STREAM_DESTINATION")
                .forEach { rejection(auditInsert(it)) shouldBe null }
            (rejection(auditInsert("BOGUS")) != null) shouldBe true
        }

        test("a re-run is a no-op") {
            h.flyway(null).migrate().migrationsExecuted shouldBe 0
        }
    })

class ArticleImportBatchMigrationTest : ArticleImportBatchMigrationScenarios({ H2MigrationHarness() })

@Tags("Postgres")
@EnabledIf(PostgresConfigured::class)
class ArticleImportBatchMigrationPostgresTest : ArticleImportBatchMigrationScenarios({ PostgresMigrationHarness() })
