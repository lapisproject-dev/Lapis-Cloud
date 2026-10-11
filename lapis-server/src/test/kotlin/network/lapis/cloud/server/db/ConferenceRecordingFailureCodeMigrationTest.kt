package network.lapis.cloud.server.db

import io.kotest.core.annotation.EnabledIf
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import network.lapis.cloud.server.testdb.PostgresConfigured
import java.util.UUID

/**
 * Welle V1.9.107 -- `V87__conference_recording_failure_code_and_retry.sql` on the upgrade path V86 -> V87, on H2 AND on PostgreSQL: the backfill maps the
 * four exact failure texts to their codes and leaves unknown text (and every non-FAILED row) at NULL, the new bookkeeping columns default to 0/NULL,
 * the three CHECK constraints bite, and a re-run is a no-op.
 */
abstract class ConferenceRecordingFailureCodeMigrationScenarios(
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
        val composeFailed = UUID.randomUUID()
        val timeout = UUID.randomUUID()
        val noTracks = UUID.randomUUID()
        val noTrackStored = UUID.randomUUID()
        val unknownText = UUID.randomUUID()
        val readyRow = UUID.randomUUID()

        fun recordingInsert(
            id: UUID,
            status: String,
            reason: String?,
            extraColumns: String = "",
            extraValues: String = "",
        ) = "INSERT INTO conference_recording (id, room_id, started_by_member_id, started_at, status, access_level, " +
            "raw_dir, compose_attempts, failure_reason$extraColumns) VALUES ('$id', '$roomId', '$memberId', " +
            "TIMESTAMP '2026-10-11 10:00:00', '$status', 'BOARD_ONLY', " +
            "'$id', 5, ${reason?.let { "'$it'" } ?: "NULL"}$extraValues)"

        beforeSpec {
            h = newHarness()
            h.flyway("86").migrate()
            exec(
                "INSERT INTO member (id, display_name, email, status, joined_at) VALUES ('$memberId', 'M', 'rec-$memberId@example.org', 'ACTIVE', DATE '2026-01-01')",
            )
            exec(
                "INSERT INTO conference_room (id, title, description, livekit_room_name, created_by_member_id, created_at, " +
                    "max_participants) " +
                    "VALUES ('$roomId', 'R', '', 'lc-$roomId', '$memberId', TIMESTAMP '2026-10-11 09:00:00', 25)",
            )
            exec(recordingInsert(composeFailed, "FAILED", "Die Aufzeichnung konnte nicht zusammengesetzt werden."))
            exec(recordingInsert(timeout, "FAILED", "Zeitüberschreitung beim Abschluss der Aufzeichnung."))
            exec(recordingInsert(noTracks, "FAILED", "Es wurde keine Audio- oder Videospur aufgezeichnet."))
            exec(
                recordingInsert(
                    noTrackStored,
                    "FAILED",
                    "Der Aufzeichnungsdienst konnte keine Spur speichern (Server-Konfiguration prüfen).",
                ),
            )
            exec(recordingInsert(unknownText, "FAILED", "Irgendein alter Text"))
            exec(recordingInsert(readyRow, "READY", null))
            h.flyway(null).migrate()
        }
        afterSpec { h.close() }

        fun codeOf(id: UUID) = rows("SELECT failure_code FROM conference_recording WHERE id = '$id'").single().single()

        test("the backfill maps the four exact texts to their codes") {
            codeOf(composeFailed) shouldBe "COMPOSE_FAILED"
            codeOf(timeout) shouldBe "EGRESS_TIMEOUT"
            codeOf(noTracks) shouldBe "NO_TRACKS"
            codeOf(noTrackStored) shouldBe "NO_TRACK_STORED"
        }

        test("unknown text and non-FAILED rows stay NULL; the bookkeeping columns start at 0/NULL") {
            codeOf(unknownText) shouldBe "null"
            codeOf(readyRow) shouldBe "null"
            rows(
                "SELECT manual_retry_count, manual_retry_window_started_at, last_manual_retry_at FROM conference_recording WHERE id = '$composeFailed'",
            ).single() shouldBe listOf("0", "null", "null")
        }

        test("the CHECK constraints: unknown code, code on a non-FAILED row and a negative counter are rejected") {
            val bad = UUID.randomUUID()
            (rejection(recordingInsert(bad, "FAILED", null, ", failure_code", ", 'BOGUS'")) != null) shouldBe true
            (rejection(recordingInsert(bad, "PROCESSING", null, ", failure_code", ", 'COMPOSE_FAILED'")) != null) shouldBe true
            (rejection(recordingInsert(bad, "FAILED", null, ", manual_retry_count", ", -1")) != null) shouldBe true
            rejection(recordingInsert(UUID.randomUUID(), "FAILED", null, ", failure_code", ", 'NO_TRACKS'")) shouldBe null
        }

        test("a re-run is a no-op") {
            h.flyway(null).migrate().migrationsExecuted shouldBe 0
        }
    })

class ConferenceRecordingFailureCodeMigrationTest : ConferenceRecordingFailureCodeMigrationScenarios({ H2MigrationHarness() })

@Tags("Postgres")
@EnabledIf(PostgresConfigured::class)
class ConferenceRecordingFailureCodeMigrationPostgresTest : ConferenceRecordingFailureCodeMigrationScenarios({ PostgresMigrationHarness() })
