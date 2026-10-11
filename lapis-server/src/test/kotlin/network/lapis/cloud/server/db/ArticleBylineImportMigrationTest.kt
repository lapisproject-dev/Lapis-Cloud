package network.lapis.cloud.server.db

import io.kotest.core.annotation.EnabledIf
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import network.lapis.cloud.server.testdb.PostgresConfigured
import java.util.UUID

/**
 * Welle V1.9.105 -- `V85__article_byline_and_import.sql` on the upgrade path V84 -> V85, on H2 AND on PostgreSQL: an existing article survives
 * with no byline and `imported = FALSE`, the byline pair CHECK, the consent CHECK (a PERSONAL line needs a consent moment, every other kind must
 * not carry one) and the kind CHECK bite, the audit-log CHECK accepts `ARTICLE_IMPORT` and still accepts every earlier type (V83 list) while
 * rejecting unknown ones, the archive index exists, and a re-run is a no-op.
 */
abstract class ArticleBylineImportMigrationScenarios(
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
        var seq = 9700
        val dummyHash = "c".repeat(64)

        fun articleInsert(
            id: UUID = UUID.randomUUID(),
            byline: String? = null,
            kind: String? = null,
            consent: Boolean = false,
            setBy: UUID? = null,
        ) = "INSERT INTO article (id, slug, title, excerpt, body, author_id, status, created_at, updated_at, byline, byline_kind, " +
            "byline_consent_at, byline_set_by) VALUES ('$id', 'mig-$id', 'Titel $id', 'x', 'y', '$memberId', 'PUBLISHED', " +
            "TIMESTAMP '2026-10-11 10:00:00', TIMESTAMP '2026-10-11 10:00:00', " +
            "${byline?.let { "'$it'" } ?: "NULL"}, ${kind?.let { "'$it'" } ?: "NULL"}, " +
            "${if (consent) "TIMESTAMP '2026-10-11 10:00:00'" else "NULL"}, ${setBy?.let { "'$it'" } ?: "NULL"})"

        fun auditInsert(entityType: String) =
            "INSERT INTO audit_log_entry (id, sequence_number, occurred_at, entity_type, entity_id, action, entry_hash) " +
                "VALUES ('${UUID.randomUUID()}', ${seq++}, TIMESTAMP '2026-10-11 10:00:00', '$entityType', " +
                "'${UUID.randomUUID()}', 'UPDATE', '$dummyHash')"

        beforeSpec {
            h = newHarness()
            h.flyway("84").migrate()
            exec(
                "INSERT INTO member (id, display_name, email, status, joined_at) VALUES ('$memberId', 'M', 'byline-$memberId@example.org', 'ACTIVE', DATE '2026-01-01')",
            )
            exec(
                "INSERT INTO article (id, slug, title, excerpt, body, author_id, status, created_at, updated_at) " +
                    "VALUES ('$oldArticle', 'old-$oldArticle', 'Alt', 'x', 'y', '$memberId', 'PUBLISHED', " +
                    "TIMESTAMP '2026-01-01 00:00:00', TIMESTAMP '2026-01-01 00:00:00')",
            )
            h.flyway(null).migrate()
        }
        afterSpec { h.close() }

        test("an existing article survives with no byline, no consent and imported = FALSE: nothing is backfilled") {
            rows(
                "SELECT byline, byline_kind, byline_consent_at, byline_set_by, byline_set_at, imported FROM article WHERE id = '$oldArticle'",
            ).single()
                .let { it.take(5) shouldBe List(5) { "null" } }
            rows("SELECT imported FROM article WHERE id = '$oldArticle'").single().single().lowercase().let {
                (it == "false" || it == "f" || it == "0") shouldBe true
            }
        }

        test("the byline pair CHECK: text and kind are both set or both NULL") {
            (rejection(articleInsert(byline = "Max Muster", kind = null)) != null) shouldBe true
            (rejection(articleInsert(byline = null, kind = "ORGANIZATION")) != null) shouldBe true
            rejection(articleInsert(byline = "Redaktion", kind = "ORGANIZATION")) shouldBe null
            rejection(articleInsert()) shouldBe null
        }

        test("the kind CHECK accepts exactly PERSONAL, ORGANIZATION and IMPORTED") {
            rejection(articleInsert(byline = "A", kind = "PERSONAL", consent = true)) shouldBe null
            rejection(articleInsert(byline = "A", kind = "ORGANIZATION")) shouldBe null
            rejection(articleInsert(byline = "A", kind = "IMPORTED")) shouldBe null
            (rejection(articleInsert(byline = "A", kind = "BOGUS")) != null) shouldBe true
        }

        test("the consent CHECK: PERSONAL needs a consent moment; ORGANIZATION and IMPORTED must not carry one") {
            (rejection(articleInsert(byline = "A", kind = "PERSONAL", consent = false)) != null) shouldBe true
            (rejection(articleInsert(byline = "A", kind = "ORGANIZATION", consent = true)) != null) shouldBe true
            (rejection(articleInsert(byline = "A", kind = "IMPORTED", consent = true)) != null) shouldBe true
            (rejection(articleInsert(consent = true)) != null) shouldBe true
        }

        test("byline_set_by is a foreign key to member") {
            rejection(articleInsert(byline = "A", kind = "ORGANIZATION", setBy = memberId)) shouldBe null
            (rejection(articleInsert(byline = "A", kind = "ORGANIZATION", setBy = UUID.randomUUID())) != null) shouldBe true
        }

        test("the byline column holds 120 characters and not 121") {
            rejection(articleInsert(byline = "x".repeat(120), kind = "ORGANIZATION")) shouldBe null
            (rejection(articleInsert(byline = "x".repeat(121), kind = "ORGANIZATION")) != null) shouldBe true
        }

        test("the (status, published_at, id) archive index exists") {
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
                    result["idx_article_published_archive"]?.sortedBy { it.first }?.map { it.second }
                }
            index shouldBe listOf("status", "published_at", "id")
        }

        test("the audit-log CHECK accepts ARTICLE_IMPORT, keeps every earlier type (V83 list) and still rejects an unknown one") {
            rejection(auditInsert("ARTICLE_IMPORT")) shouldBe null
            listOf(
                "COMMITTEE_MEMBERSHIP",
                "EVENT_IMPORT",
                "EVENT",
                "ENCOUNTER_SPACE",
                "POLL",
                "MEMBERSHIP_TIER",
                "REGIONAL_CHAPTER_OFFICER",
                "REGIONAL_CHAPTER",
                "ARTICLE",
                "DOCUMENT_FOLDER",
                "DOCUMENT",
                "JOURNAL_ENTRY",
                "BOARD_MEMBERSHIP",
            ).forEach { rejection(auditInsert(it)) shouldBe null }
            (rejection(auditInsert("BOGUS")) != null) shouldBe true
        }

        test("a re-run is a no-op") {
            h.flyway(null).migrate().migrationsExecuted shouldBe 0
        }
    })

class ArticleBylineImportMigrationTest : ArticleBylineImportMigrationScenarios({ H2MigrationHarness() })

@Tags("Postgres")
@EnabledIf(PostgresConfigured::class)
class ArticleBylineImportMigrationPostgresTest : ArticleBylineImportMigrationScenarios({ PostgresMigrationHarness() })
