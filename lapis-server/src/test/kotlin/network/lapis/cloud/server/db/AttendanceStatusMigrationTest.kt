package network.lapis.cloud.server.db

import io.kotest.core.annotation.EnabledIf
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import network.lapis.cloud.server.testdb.PostgresConfigured
import java.util.UUID

/**
 * Welle V1.9.236 -- `V127__meeting_conference_session.sql` on the upgrade path V126 -> V127, on H2 AND PostgreSQL: existing attendance rows
 * survive, `GUEST` is accepted while an unknown status is still rejected (pins the drop of the unnamed inline CHECK of V1 by its generated H2 name
 * -- a shifted name fails here), exactly ONE status constraint remains, the quorum-count CHECK bites from both sides, the agenda marker has
 * no foreign key, `MEETING_SESSION` is a valid audit entity type, and a re-run is a no-op.
 */
abstract class AttendanceStatusMigrationScenarios(
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

        fun guestMember(id: UUID) =
            exec(
                "INSERT INTO member (id, display_name, email, status, joined_at) VALUES ('$id', 'G', 'g-$id@example.org', 'ACTIVE', DATE '2026-01-01')",
            )

        val memberId = UUID.randomUUID()
        val committeeId = UUID.randomUUID()
        val meetingId = UUID.randomUUID()
        val agendaId = UUID.randomUUID()
        val oldAttendance = UUID.randomUUID()

        beforeSpec {
            h = newHarness()
            h.flyway("126").migrate()
            exec(
                "INSERT INTO member (id, display_name, email, status, joined_at) VALUES ('$memberId', 'M', 'at-$memberId@example.org', 'ACTIVE', DATE '2026-01-01')",
            )
            exec(
                "INSERT INTO committee (id, name, type, description, active, quorum_percent, created_at) " +
                    "VALUES ('$committeeId', 'C-$committeeId', 'WORKING_GROUP', 'd', TRUE, 50, TIMESTAMP '2026-01-01 10:00:00')",
            )
            exec(
                "INSERT INTO meeting (id, title, scheduled_at, format, status, created_at, committee_id) " +
                    "VALUES ('$meetingId', 'T', TIMESTAMP '2026-11-01 10:00:00', 'ONLINE', 'PLANNED', TIMESTAMP '2026-01-01 10:00:00', '$committeeId')",
            )
            exec("INSERT INTO agenda_item (id, position, title, meeting_id) VALUES ('$agendaId', 1, 'TOP 1', '$meetingId')")
            exec(
                "INSERT INTO attendance (id, status, recorded_at, meeting_id, member_id) " +
                    "VALUES ('$oldAttendance', 'PRESENT', TIMESTAMP '2026-11-01 10:05:00', '$meetingId', '$memberId')",
            )
            h.flyway(null).migrate()
        }
        afterSpec { h.close() }

        test("an existing attendance row survives; the new meeting columns default to not opened / not established") {
            rows("SELECT status FROM attendance WHERE id = '$oldAttendance'").single() shouldBe listOf("PRESENT")
            rows(
                "SELECT opened_at, quorum_established_at, quorum_present_count, quorum_overridden, current_agenda_item_id " +
                    "FROM meeting WHERE id = '$meetingId'",
            ).single().let { it[0] to it[1] } shouldBe ("null" to "null")
            rows("SELECT CAST(quorum_overridden AS VARCHAR(5)) FROM meeting WHERE id = '$meetingId'").single()[0].lowercase() shouldBe
                "false"
        }

        test("GUEST is accepted as an attendance status, an unknown status is still rejected, and exactly one status constraint remains") {
            fun insert(status: String) =
                "INSERT INTO attendance (id, status, recorded_at, meeting_id, member_id) VALUES ('${UUID.randomUUID()}', '$status', " +
                    "TIMESTAMP '2026-11-01 10:05:00', '$meetingId', '${UUID.randomUUID().also { guestMember(it) }}')"
            listOf("PRESENT", "EXCUSED", "UNEXCUSED", "REPRESENTED", "GUEST").forEach { rejection(insert(it)) shouldBe null }
            (rejection(insert("VISITOR")) != null) shouldBe true
            // the constraint set on the status column: the named one only (the inline V1 CHECK is gone)
            val statusChecks =
                rows(
                    "SELECT constraint_name FROM information_schema.check_constraints " +
                        "WHERE LOWER(check_clause) LIKE '%represented%' AND LOWER(check_clause) LIKE '%unexcused%'",
                ).map { it[0].lowercase() }
            statusChecks.filter { it.contains("attendance") || it.startsWith("constraint_") }.size shouldBe 1
        }

        test("quorum counts are all NULL or all set; negative numbers are rejected") {
            exec(
                "UPDATE meeting SET quorum_established_at = TIMESTAMP '2026-11-01 10:10:00', quorum_present_count = 3, quorum_required_count = 2, quorum_eligible_count = 4 WHERE id = '$meetingId'",
            )
            (rejection("UPDATE meeting SET quorum_present_count = NULL WHERE id = '$meetingId'") != null) shouldBe true
            (rejection("UPDATE meeting SET quorum_present_count = -1 WHERE id = '$meetingId'") != null) shouldBe true
            exec(
                "UPDATE meeting SET quorum_established_at = NULL, quorum_present_count = NULL, quorum_required_count = NULL, quorum_eligible_count = NULL WHERE id = '$meetingId'",
            )
            (rejection("UPDATE meeting SET quorum_present_count = 1 WHERE id = '$meetingId'") != null) shouldBe true
        }

        test("current_agenda_item_id is a plain nullable column without a foreign key (no meeting <-> agenda_item cycle for the backup)") {
            exec("UPDATE meeting SET current_agenda_item_id = '$agendaId' WHERE id = '$meetingId'")
            rows("SELECT current_agenda_item_id FROM meeting WHERE id = '$meetingId'").single()[0].lowercase() shouldBe agendaId.toString()
            // no constraint ties the column to agenda_item: an unknown id is accepted by the schema (the code guards it)
            exec("UPDATE meeting SET current_agenda_item_id = '${UUID.randomUUID()}' WHERE id = '$meetingId'")
            exec("UPDATE meeting SET current_agenda_item_id = NULL WHERE id = '$meetingId'")
            rows(
                "SELECT COUNT(*) FROM information_schema.key_column_usage WHERE LOWER(table_name) = 'meeting' AND LOWER(column_name) = 'current_agenda_item_id'",
            ).single() shouldBe listOf("0")
        }

        test("MEETING_SESSION is a valid audit entity type, an unknown one is rejected") {
            rejection(auditInsert("MEETING_SESSION")) shouldBe null
            rejection(auditInsert("MEETING_EVENT_LINK")) shouldBe null
            (rejection(auditInsert("NOT_AN_ENTITY")) != null) shouldBe true
        }

        test("a re-run is a no-op") {
            h.flyway(null).migrate().migrationsExecuted shouldBe 0
        }
    })

private fun auditInsert(entityType: String): String =
    "INSERT INTO audit_log_entry (id, sequence_number, occurred_at, actor_role, entity_type, entity_id, action, entry_hash) " +
        "VALUES ('${UUID.randomUUID()}', ${(UUID.randomUUID().mostSignificantBits ushr 2)}, " +
        "TIMESTAMP '2026-11-01 10:00:00', 'ADMIN', '$entityType', " +
        "'${UUID.randomUUID()}', 'CREATE', 'h')"

class AttendanceStatusMigrationTest : AttendanceStatusMigrationScenarios({ H2MigrationHarness() })

@Tags("Postgres")
@EnabledIf(PostgresConfigured::class)
class AttendanceStatusMigrationPostgresTest : AttendanceStatusMigrationScenarios({ PostgresMigrationHarness() })
