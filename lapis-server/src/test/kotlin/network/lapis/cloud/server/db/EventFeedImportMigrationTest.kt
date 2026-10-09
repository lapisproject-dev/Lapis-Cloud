package network.lapis.cloud.server.db

import io.kotest.core.annotation.EnabledIf
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import network.lapis.cloud.server.testdb.PostgresConfigured
import java.util.UUID

/**
 * Welle V1.9.82 -- `V81__event_public_feed_and_import.sql` on the upgrade path V80 -> V81, on H2 AND on PostgreSQL: existing events keep
 * working and get the new defaults, the archive index exists, the audit-log CHECK accepts the two new entity types and still rejects
 * unknown ones, and a re-run is a no-op.
 */
abstract class EventFeedImportMigrationScenarios(
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

        /** H2 prints a BOOLEAN as TRUE/FALSE, PostgreSQL as t/f. */
        fun bool(raw: String): String =
            when (raw.lowercase()) {
                "t", "true" -> "true"
                "f", "false" -> "false"
                else -> raw.lowercase()
            }

        val memberId = UUID.randomUUID()
        val oldEvent = UUID.randomUUID()

        fun eventInsert(id: UUID) =
            "INSERT INTO event (id, slug, title, description, location_text, starts_at, ends_at, status, visibility, " +
                "created_at, created_by) " +
                "VALUES ('$id', 'slug-$id', 'T', 'D', 'Ort', TIMESTAMP '2030-01-01 10:00:00', TIMESTAMP '2030-01-01 12:00:00', " +
                "'PUBLISHED', 'PUBLIC', TIMESTAMP '2026-10-08 10:00:00', '$memberId')"

        var seq = 9000

        val dummyHash = "a".repeat(64)

        fun auditInsert(entityType: String) =
            "INSERT INTO audit_log_entry (id, sequence_number, occurred_at, entity_type, entity_id, action, entry_hash) " +
                "VALUES ('${UUID.randomUUID()}', ${seq++}, TIMESTAMP '2026-10-08 10:00:00', '$entityType', " +
                "'${UUID.randomUUID()}', 'CREATE', '$dummyHash')"

        beforeSpec {
            h = newHarness()
            h.flyway("80").migrate()
            exec(
                "INSERT INTO member (id, display_name, email, status, joined_at) VALUES ('$memberId', 'M', 'ev-$memberId@example.org', 'ACTIVE', DATE '2026-01-01')",
            )
            exec(eventInsert(oldEvent))
            h.flyway(null).migrate()
        }
        afterSpec { h.close() }

        test("an existing event survives: summary and alt text NULL, online link not public, not imported") {
            rows("SELECT summary, cover_image_alt, online_url_public, imported FROM event WHERE id = '$oldEvent'")
                .single()
                .map { bool(it) } shouldBe listOf("null", "null", "false", "false")
        }

        test("a new row without the new columns gets the defaults") {
            val id = UUID.randomUUID()
            exec(eventInsert(id))
            rows("SELECT online_url_public, imported FROM event WHERE id = '$id'").single().map { bool(it) } shouldBe
                listOf("false", "false")
        }

        test("the new columns are NOT NULL where promised (flags) and nullable where promised (texts)") {
            rows(
                "SELECT column_name, is_nullable FROM information_schema.columns WHERE table_name = 'event' " +
                    "AND column_name IN ('summary', 'cover_image_alt', 'online_url_public', 'imported') ORDER BY column_name",
            ).map { it[0].lowercase() to it[1] } shouldBe
                listOf("cover_image_alt" to "YES", "imported" to "NO", "online_url_public" to "NO", "summary" to "YES")
        }

        test("the archive index exists on (status, visibility, ends_at, id)") {
            val index =
                h.connection { c ->
                    val result = mutableMapOf<String, MutableList<Pair<Short, String>>>()
                    c.metaData.getIndexInfo(null, null, "event", false, false).use { rs ->
                        while (rs.next()) {
                            val name = rs.getString("INDEX_NAME")?.lowercase() ?: continue
                            result.getOrPut(name) { mutableListOf() } +=
                                rs.getShort("ORDINAL_POSITION") to rs.getString("COLUMN_NAME").lowercase()
                        }
                    }
                    result["idx_event_public_archive"]?.sortedBy { it.first }?.map { it.second }
                }
            index shouldBe listOf("status", "visibility", "ends_at", "id")
        }

        test("the audit-log CHECK accepts EVENT and EVENT_IMPORT and still rejects an unknown entity type") {
            rejection(auditInsert("EVENT")) shouldBe null
            rejection(auditInsert("EVENT_IMPORT")) shouldBe null
            rejection(auditInsert("ENCOUNTER_SPACE")) shouldBe null
            (rejection(auditInsert("BOGUS")) != null) shouldBe true
        }

        test("a re-run is a no-op") {
            h.flyway(null).migrate().migrationsExecuted shouldBe 0
        }
    })

class EventFeedImportMigrationTest : EventFeedImportMigrationScenarios({ H2MigrationHarness() })

@Tags("Postgres")
@EnabledIf(PostgresConfigured::class)
class EventFeedImportMigrationPostgresTest : EventFeedImportMigrationScenarios({ PostgresMigrationHarness() })
