package network.lapis.cloud.server.db

import io.kotest.core.annotation.EnabledIf
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import network.lapis.cloud.server.testdb.PostgresConfigured
import network.lapis.cloud.shared.domain.AuditEntityType
import java.util.UUID

/**
 * Welle V1.9.143 -- `V106__pulpit_recording.sql` on the upgrade path V105 -> V106, on H2 AND on PostgreSQL: the new settings columns with their defaults, the
 * two new agreement-text kinds in both CHECK constraints (and nothing else), the marker table (three columns, no person, both foreign keys), and the
 * audit CHECK that accepts PULPIT_RECORDING and every other entity type. V104 and V105 stay untouched; a re-run is a no-op.
 */
abstract class PulpitRecordingMigrationScenarios(
    private val newHarness: () -> MigrationHarness,
) : FunSpec({
        lateinit var h: MigrationHarness

        fun exec(sql: String) = h.connection { c -> c.createStatement().use { it.execute(sql) } }

        fun rejection(sql: String): String? =
            runCatching { exec(sql) }
                .exceptionOrNull()
                ?.let { generateSequence(it) { t -> t.cause }.joinToString(" | ") { t -> t.message.orEmpty() } }

        fun columnDefault(
            table: String,
            column: String,
        ): String? =
            h.connection { c ->
                c.createStatement().use { st ->
                    st
                        .executeQuery(
                            "SELECT column_default FROM information_schema.columns WHERE table_name = '$table' AND column_name = '$column'",
                        ).use { rs -> if (rs.next()) rs.getString(1) else "<missing>" }
                }
            }

        fun columns(table: String): Set<String> =
            h.connection { c ->
                c.createStatement().use { st ->
                    st.executeQuery("SELECT column_name FROM information_schema.columns WHERE table_name = '$table'").use { rs ->
                        buildSet { while (rs.next()) add(rs.getString(1)) }
                    }
                }
            }

        val author = UUID.randomUUID().toString()

        fun version(
            kind: String,
            label: String,
        ): String =
            "INSERT INTO agreement_text_version (id, kind, sequence_no, version_label, valid_from, body, sha256, char_count, " +
                "author_member_id, published_at) VALUES ('${UUID.randomUUID()}', '$kind', 1, '$label', DATE '2026-10-16', 'hallo', " +
                "'${"a".repeat(64)}', 5, '$author', TIMESTAMP '2026-10-16 10:00:00')"

        fun draft(kind: String): String =
            "INSERT INTO agreement_text_draft (kind, id, version_label, valid_from, body, sha256, char_count, updated_by, updated_at) " +
                "VALUES ('$kind', '${UUID.randomUUID()}', 'x.v1', DATE '2026-10-16', 'hallo', '${"b".repeat(64)}', 5, '$author', " +
                "TIMESTAMP '2026-10-16 10:00:00')"

        var sequence = 820_000_000L

        fun audit(type: String): String {
            sequence += 1
            return "INSERT INTO audit_log_entry (id, sequence_number, occurred_at, actor_member_id, actor_role, entity_type, entity_id, " +
                "action, entry_hash, previous_entry_hash) VALUES ('${UUID.randomUUID()}', $sequence, TIMESTAMP '2026-10-16 10:00:00', " +
                "NULL, NULL, '$type', '${UUID.randomUUID()}', 'CREATE', '${sequence.toString().padStart(64, '0')}', NULL)"
        }

        beforeSpec {
            h = newHarness()
            h.flyway("105").migrate()
            h.flyway(null).migrate()
            exec(
                "INSERT INTO member (id, display_name, email, status, joined_at) VALUES " +
                    "('$author', 'Autor', 'pulpit-mig-$author@example.org', 'ACTIVE', DATE '2026-01-01')",
            )
        }
        afterSpec { h.close() }

        test("the settings columns default to 'off', no version and 'the pulpit confirms'; the room option defaults to off") {
            columnDefault("organization_settings", "pulpit_recording_enabled")!!.uppercase() shouldContain "FALSE"
            columnDefault("organization_settings", "pulpit_recording_require_confirmation")!!.uppercase() shouldContain "TRUE"
            columnDefault(
                "organization_settings",
                "pulpit_recording_notice_version",
            ).let { it == null || it.uppercase() == "NULL" } shouldBe
                true
            columnDefault("encounter_space", "pulpit_recording_allowed")!!.uppercase() shouldContain "FALSE"
            h.connection { c ->
                c.createStatement().use { st ->
                    st
                        .executeQuery(
                            "SELECT pulpit_recording_enabled, pulpit_recording_notice_version, pulpit_recording_require_confirmation FROM organization_settings",
                        ).use { rs ->
                            rs.next() shouldBe true
                            rs.getBoolean(1) shouldBe false
                            rs.getString(2) shouldBe null
                            rs.getBoolean(3) shouldBe true
                        }
                }
            }
        }

        test("both agreement tables accept the two new kinds and still refuse an unknown one") {
            rejection(version("REC_ROOM", "rec-room.v1")) shouldBe null
            rejection(version("REC_PULPIT", "rec-pulpit.v1")) shouldBe null
            rejection(draft("REC_ROOM")) shouldBe null
            rejection(draft("REC_PULPIT")) shouldBe null
            (rejection(version("REC_OTHER", "x.v1"))!!).shouldContain("chk_agreement_text_version_kind")
            (rejection(draft("REC_OTHER"))!!).shouldContain("chk_agreement_text_draft_kind")
            // the old kinds are still there
            rejection(version("MEMBERSHIP", "old.v1")) shouldBe null
            rejection(draft("FRIEND")) shouldBe null
            exec("DELETE FROM agreement_text_version")
            exec("DELETE FROM agreement_text_draft")
        }

        test("the marker table has exactly three columns and none that could name a person; both foreign keys fire") {
            columns("pulpit_recording_document") shouldBe setOf("document_id", "space_id", "recorded_at")
            val orphan =
                "INSERT INTO pulpit_recording_document (document_id, space_id, recorded_at) VALUES " +
                    "('${UUID.randomUUID()}', '${UUID.randomUUID()}', TIMESTAMP '2026-10-16 10:00:00')"
            (rejection(orphan)!!).shouldContain("fk_pulpit_recording_document")
        }

        test("audit_log_entry accepts PULPIT_RECORDING and EVERY entity type of the shared enum, and still refuses an unknown one") {
            (rejection(audit("NOT_A_REAL_ENTITY"))!!).shouldContain("chk_audit_log_entry_entity_type")
            for (type in AuditEntityType.entries) {
                (rejection(audit(type.name)) == null) shouldBe true
            }
            exec("DELETE FROM audit_log_entry WHERE sequence_number > 820000000")
        }

        test("a re-run is a no-op") {
            h.flyway(null).migrate().migrationsExecuted shouldBe 0
        }
    })

class PulpitRecordingMigrationTest : PulpitRecordingMigrationScenarios({ H2MigrationHarness() })

@Tags("Postgres")
@EnabledIf(PostgresConfigured::class)
class PulpitRecordingMigrationPostgresTest : PulpitRecordingMigrationScenarios({ PostgresMigrationHarness() })
