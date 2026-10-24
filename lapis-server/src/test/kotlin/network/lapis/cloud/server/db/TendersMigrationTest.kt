package network.lapis.cloud.server.db

import io.kotest.core.annotation.EnabledIf
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import network.lapis.cloud.server.testdb.PostgresConfigured

/**
 * Welle V1.9.201 -- `V115__tenders.sql` on the upgrade path V114 -> V115, on H2 AND on PostgreSQL: the existing settings row gets the factory default of the
 * bid visibility, only the three known values are accepted, the four tender tables exist and are empty, the audit CHECK accepts TENDER (and still the
 * values before it), and a re-run is a no-op.
 */
abstract class TendersMigrationScenarios(
    private val newHarness: () -> MigrationHarness,
) : FunSpec({
        lateinit var h: MigrationHarness

        fun exec(sql: String) = h.connection { c -> c.createStatement().use { it.execute(sql) } }

        fun single(sql: String): String =
            h.connection { c ->
                c.createStatement().use { st ->
                    st.executeQuery(sql).use { rs ->
                        rs.next()
                        rs.getString(1) ?: "null"
                    }
                }
            }

        fun rejection(sql: String): String? =
            runCatching { exec(sql) }
                .exceptionOrNull()
                ?.let { generateSequence(it) { t -> t.cause }.joinToString(" | ") { t -> t.message.orEmpty() } }

        val rowId = "00000000-0000-0000-0000-0000000000f2"

        beforeSpec {
            h = newHarness()
            h.flyway("114").migrate()
            h.flyway(null).migrate()
        }
        afterSpec { h.close() }

        test("the existing settings row gets HIDDEN_UNTIL_DEADLINE, only the three known values are accepted, NULL is rejected") {
            single("SELECT tender_default_visibility FROM organization_settings WHERE id = '$rowId'") shouldBe "HIDDEN_UNTIL_DEADLINE"
            listOf("OPEN", "OWN_ONLY", "HIDDEN_UNTIL_DEADLINE").forEach {
                exec("UPDATE organization_settings SET tender_default_visibility = '$it' WHERE id = '$rowId'")
                single("SELECT tender_default_visibility FROM organization_settings WHERE id = '$rowId'") shouldBe it
            }
            (rejection("UPDATE organization_settings SET tender_default_visibility = 'EVERYONE' WHERE id = '$rowId'") != null) shouldBe true
            (rejection("UPDATE organization_settings SET tender_default_visibility = NULL WHERE id = '$rowId'") != null) shouldBe true
        }

        test("the four tender tables exist and are empty") {
            listOf("tender", "tender_bid", "tender_award", "tender_acceptance").forEach {
                single("SELECT COUNT(*) FROM $it") shouldBe "0"
            }
        }

        test("the audit CHECK accepts TENDER and still the earlier values") {
            val types = listOf("TENDER", "TRAVEL_EXPENSE_DOCUMENT", "NOT_A_TYPE")
            val sql = { type: String ->
                val sequence = 900000 + types.indexOf(type)
                "INSERT INTO audit_log_entry (id, sequence_number, occurred_at, actor_member_id, actor_role, entity_type, entity_id, " +
                    "action, " +
                    "before_snapshot, after_snapshot, entry_hash, previous_entry_hash) " +
                    "VALUES ('${java.util.UUID.randomUUID()}', $sequence, TIMESTAMP '2026-01-01 00:00:00', NULL, NULL, '$type', " +
                    "'${java.util.UUID.randomUUID()}', 'CREATE', NULL, NULL, '${"1".repeat(64)}', NULL)"
            }
            rejection(sql("TENDER")) shouldBe null
            rejection(sql("TRAVEL_EXPENSE_DOCUMENT")) shouldBe null
            (rejection(sql("NOT_A_TYPE")) != null) shouldBe true
        }

        test("V116 adds description_format NOT NULL with the default PLAIN, and the CHECK names exactly PLAIN and MARKDOWN") {
            single(
                "SELECT is_nullable FROM information_schema.columns WHERE UPPER(table_name) = 'TENDER' AND " +
                    "LOWER(column_name) = 'description_format'",
            ) shouldBe "NO"
            single(
                "SELECT column_default FROM information_schema.columns WHERE UPPER(table_name) = 'TENDER' AND " +
                    "LOWER(column_name) = 'description_format'",
            ).contains("PLAIN") shouldBe true
            single(
                "SELECT COUNT(*) FROM information_schema.check_constraints WHERE LOWER(constraint_name) = 'chk_tender_description_format'",
            ) shouldBe "1"
            // repeatable: running the statements of V116 again changes nothing and fails nowhere
            val script =
                Thread
                    .currentThread()
                    .contextClassLoader
                    .getResource(
                        "db/migration/V116__tender_description_format.sql",
                    )!!
                    .readText()
            script
                .lines()
                .filter {
                    it.isNotBlank() &&
                        !it.trimStart().startsWith(
                            "--",
                        )
                }.joinToString("\n")
                .split(";")
                .filter { it.isNotBlank() }
                .forEach { exec(it) }
            single("SELECT COUNT(*) FROM tender") shouldBe "0"
        }

        test("a re-run is a no-op") {
            h.flyway(null).migrate().migrationsExecuted shouldBe 0
        }
    })

class TendersMigrationTest : TendersMigrationScenarios({ H2MigrationHarness() })

@Tags("Postgres")
@EnabledIf(PostgresConfigured::class)
class TendersMigrationPostgresTest : TendersMigrationScenarios({ PostgresMigrationHarness() })
