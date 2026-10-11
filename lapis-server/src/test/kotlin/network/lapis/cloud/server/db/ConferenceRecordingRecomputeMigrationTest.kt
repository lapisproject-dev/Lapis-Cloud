package network.lapis.cloud.server.db

import io.kotest.core.annotation.EnabledIf
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import network.lapis.cloud.server.testdb.PostgresConfigured
import java.util.UUID

/**
 * Welle V1.9.109 -- `V89__conference_recording_recompute.sql` on the upgrade path V88 -> V89, on H2 AND on PostgreSQL: the three new columns exist
 * and start NULL on existing rows, the two CHECK constraints bite (an unknown failure code, a recompute marker on a recording that is not READY),
 * every valid combination is accepted, and a re-run is a no-op.
 */
abstract class ConferenceRecordingRecomputeMigrationScenarios(
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
        val roomId = UUID.randomUUID()
        val readyRow = UUID.randomUUID()
        val failedRow = UUID.randomUUID()

        fun recordingInsert(
            id: UUID,
            status: String,
            extraColumns: String = "",
            extraValues: String = "",
        ) = "INSERT INTO conference_recording (id, room_id, started_by_member_id, started_at, status, access_level, " +
            "raw_dir, compose_attempts$extraColumns) VALUES ('$id', '$roomId', '$memberId', " +
            "TIMESTAMP '2026-10-11 10:00:00', '$status', 'BOARD_ONLY', '$id', 5$extraValues)"

        beforeSpec {
            h = newHarness()
            h.flyway("88").migrate()
            exec(
                "INSERT INTO member (id, display_name, email, status, joined_at) VALUES ('$memberId', 'M', 'rec-$memberId@example.org', 'ACTIVE', DATE '2026-01-01')",
            )
            exec(
                "INSERT INTO conference_room (id, title, description, livekit_room_name, created_by_member_id, created_at, " +
                    "max_participants) " +
                    "VALUES ('$roomId', 'R', '', 'lc-$roomId', '$memberId', TIMESTAMP '2026-10-11 09:00:00', 25)",
            )
            exec(recordingInsert(readyRow, "READY"))
            exec(recordingInsert(failedRow, "FAILED", ", failure_code", ", 'COMPOSE_FAILED'"))
            h.flyway(null).migrate()
        }
        afterSpec { h.close() }

        test("existing rows get the three new columns, all NULL") {
            rows(
                "SELECT recompute_started_at, recomputed_at, last_recompute_failure_code FROM conference_recording WHERE id = '$readyRow'",
            ).single() shouldBe listOf("null", "null", "null")
            rows(
                "SELECT recompute_started_at, recomputed_at, last_recompute_failure_code FROM conference_recording WHERE id = '$failedRow'",
            ).single() shouldBe listOf("null", "null", "null")
        }

        test("the existing V87 columns and rows are untouched") {
            rows("SELECT manual_retry_count, failure_code FROM conference_recording WHERE id = '$failedRow'").single() shouldBe
                listOf("0", "COMPOSE_FAILED")
        }

        test("a recompute marker is only allowed on a READY recording") {
            val marker = ", recompute_started_at"
            val value = ", TIMESTAMP '2026-10-11 11:00:00'"
            (rejection(recordingInsert(UUID.randomUUID(), "PROCESSING", marker, value)) != null) shouldBe true
            (rejection(recordingInsert(UUID.randomUUID(), "FAILED", marker, value)) != null) shouldBe true
            (rejection(recordingInsert(UUID.randomUUID(), "RECORDING", marker, value)) != null) shouldBe true
            rejection(recordingInsert(UUID.randomUUID(), "READY", marker, value)) shouldBe null
        }

        test("only COMPOSE_FAILED is a valid last_recompute_failure_code") {
            val column = ", last_recompute_failure_code"
            (rejection(recordingInsert(UUID.randomUUID(), "READY", column, ", 'BOGUS'")) != null) shouldBe true
            (rejection(recordingInsert(UUID.randomUUID(), "READY", column, ", 'NO_TRACKS'")) != null) shouldBe true
            rejection(recordingInsert(UUID.randomUUID(), "READY", column, ", 'COMPOSE_FAILED'")) shouldBe null
            rejection(recordingInsert(UUID.randomUUID(), "READY", column, ", NULL")) shouldBe null
        }

        test("a finished recompute: marker cleared, time and code stored, on a READY row") {
            exec(
                "UPDATE conference_recording SET recompute_started_at = TIMESTAMP '2026-10-11 11:00:00' WHERE id = '$readyRow'",
            )
            exec(
                "UPDATE conference_recording SET recompute_started_at = NULL, recomputed_at = TIMESTAMP '2026-10-11 11:30:00', " +
                    "last_recompute_failure_code = NULL WHERE id = '$readyRow'",
            )
            rows("SELECT recompute_started_at, recomputed_at FROM conference_recording WHERE id = '$readyRow'").single()[0] shouldBe "null"
            // a READY row cannot become FAILED while a marker is set (the poller clears it first)
            exec("UPDATE conference_recording SET recompute_started_at = TIMESTAMP '2026-10-11 12:00:00' WHERE id = '$readyRow'")
            (rejection("UPDATE conference_recording SET status = 'FAILED' WHERE id = '$readyRow'") != null) shouldBe true
            exec("UPDATE conference_recording SET recompute_started_at = NULL WHERE id = '$readyRow'")
        }

        test("a re-run is a no-op") {
            h.flyway(null).migrate().migrationsExecuted shouldBe 0
        }
    })

class ConferenceRecordingRecomputeMigrationTest : ConferenceRecordingRecomputeMigrationScenarios({ H2MigrationHarness() })

@Tags("Postgres")
@EnabledIf(PostgresConfigured::class)
class ConferenceRecordingRecomputeMigrationPostgresTest : ConferenceRecordingRecomputeMigrationScenarios({ PostgresMigrationHarness() })
