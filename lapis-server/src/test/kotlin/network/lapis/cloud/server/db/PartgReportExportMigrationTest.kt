package network.lapis.cloud.server.db

import io.kotest.core.annotation.EnabledIf
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import network.lapis.cloud.server.testdb.PostgresConfigured
import network.lapis.cloud.shared.domain.AuditEntityType
import java.util.UUID

/**
 * V1.9.256 -- `V136__partg_report_export_audit.sql` on the upgrade path V135 -> V136, on H2 AND on PostgreSQL: the audit CHECK accepts
 * `PARTG_REPORT_EXPORT` and every entity type of the shared enum, still refuses an unknown one, and a re-run is a no-op.
 */
abstract class PartgReportExportMigrationScenarios(
    private val newHarness: () -> MigrationHarness,
) : FunSpec({
        lateinit var h: MigrationHarness

        fun exec(sql: String) = h.connection { c -> c.createStatement().use { it.execute(sql) } }

        fun rejection(sql: String): String? =
            runCatching { exec(sql) }
                .exceptionOrNull()
                ?.let { generateSequence(it) { t -> t.cause }.joinToString(" | ") { t -> t.message.orEmpty() } }

        var sequence = 900_000_000L

        fun audit(type: String): String {
            sequence += 1
            return "INSERT INTO audit_log_entry (id, sequence_number, occurred_at, actor_member_id, actor_role, entity_type, entity_id, " +
                "action, entry_hash, previous_entry_hash) VALUES ('${UUID.randomUUID()}', $sequence, TIMESTAMP '2026-11-02 10:00:00', " +
                "NULL, NULL, '$type', '${UUID.randomUUID()}', 'CREATE', '${sequence.toString().padStart(64, '0')}', NULL)"
        }

        beforeSpec {
            h = newHarness()
            h.flyway("135").migrate()
            h.flyway(null).migrate()
        }
        afterSpec { h.close() }

        test("the new literal is accepted after V136, an unknown one stays refused") {
            (rejection(audit("NOT_A_REAL_ENTITY"))?.contains("chk_audit_log_entry_entity_type", ignoreCase = true) == true) shouldBe true
            rejection(audit("PARTG_REPORT_EXPORT")) shouldBe null
        }

        test("audit_log_entry accepts EVERY entity type of the shared enum") {
            for (type in AuditEntityType.entries) {
                val failure = rejection(audit(type.name))
                if (failure != null) throw AssertionError("entity type ${type.name}: $failure")
            }
            exec("DELETE FROM audit_log_entry WHERE sequence_number > 900000000")
        }

        test("a re-run is a no-op") {
            h.flyway(null).migrate().migrationsExecuted shouldBe 0
        }
    })

class PartgReportExportMigrationTest : PartgReportExportMigrationScenarios({ H2MigrationHarness() })

@Tags("Postgres")
@EnabledIf(PostgresConfigured::class)
class PartgReportExportMigrationPostgresTest : PartgReportExportMigrationScenarios({ PostgresMigrationHarness() })
