package network.lapis.cloud.server.db

import io.kotest.core.annotation.EnabledIf
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import network.lapis.cloud.server.testdb.PostgresConfigured
import java.util.UUID

/**
 * Welle V1.9.254 -- `V134__conference_capture_pause.sql` on the upgrade path V133 -> V134, on H2 AND on PostgreSQL: existing rooms, recordings and
 * track rows get "not paused / stage 0", `paused_at` is only allowed on a RECORDING, the pause table rejects a backwards range and a second row with
 * the same sequence number, deleting a recording cascades to its pauses, ROOM_PAUSE is a valid pause reason of a stream, and a re-run is a no-op.
 */
abstract class ConferenceCapturePauseMigrationScenarios(
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

        fun rejected(sql: String): Boolean = runCatching { exec(sql) }.isFailure

        val memberId = UUID.randomUUID()
        val roomId = UUID.randomUUID()
        val recordingId = UUID.randomUUID()
        val trackId = UUID.randomUUID()
        val streamId = UUID.randomUUID()

        fun pauseInsert(
            seq: Int,
            pausedAt: String,
            resumedAt: String?,
            recording: UUID = recordingId,
        ) = "INSERT INTO conference_recording_pause (id, recording_id, seq, paused_at, resumed_at) " +
            "VALUES ('${UUID.randomUUID()}', '$recording', $seq, TIMESTAMP '$pausedAt', " +
            (resumedAt?.let { "TIMESTAMP '$it'" } ?: "NULL") + ")"

        beforeSpec {
            h = newHarness()
            h.flyway("133").migrate()
            exec(
                "INSERT INTO member (id, display_name, email, status, joined_at) " +
                    "VALUES ('$memberId', 'M', 'pause-$memberId@example.org', 'ACTIVE', DATE '2026-01-01')",
            )
            exec(
                "INSERT INTO conference_room (id, title, description, livekit_room_name, created_by_member_id, created_at, " +
                    "max_participants) " +
                    "VALUES ('$roomId', 'R', '', 'lc-$roomId', '$memberId', TIMESTAMP '2026-10-30 09:00:00', 25)",
            )
            exec(
                "INSERT INTO conference_recording (id, room_id, started_by_member_id, started_at, status, access_level, raw_dir, " +
                    "compose_attempts) " +
                    "VALUES ('$recordingId', '$roomId', '$memberId', TIMESTAMP '2026-10-30 10:00:00', 'RECORDING', 'BOARD_ONLY', '$recordingId', 0)",
            )
            exec(
                "INSERT INTO conference_recording_track (id, recording_id, egress_id, livekit_track_id, participant_identity, " +
                    "track_source, status) " +
                    "VALUES ('$trackId', '$recordingId', 'EG_$trackId', 'TR_x', 'p', 'CAMERA', 'ACTIVE')",
            )
            exec(
                "INSERT INTO conference_stream (id, room_id, started_by_member_id, status, layout, latency_mode, started_at, " +
                    "restart_count) " +
                    "VALUES ('$streamId', '$roomId', '$memberId', 'PAUSED', 'GRID', 'STANDARD', TIMESTAMP '2026-10-30 10:00:00', 0)",
            )
            h.flyway(null).migrate()
        }
        afterSpec { h.close() }

        test("existing rooms, recordings and track rows are not paused and sit in stage 0") {
            rows("SELECT capture_paused_at FROM conference_room WHERE id = '$roomId'").single().single() shouldBe "null"
            rows("SELECT paused_at, stage FROM conference_recording WHERE id = '$recordingId'").single() shouldBe listOf("null", "0")
            rows("SELECT stage FROM conference_recording_track WHERE id = '$trackId'").single().single() shouldBe "0"
        }

        test("paused_at is only allowed while the recording is RECORDING") {
            rejected("UPDATE conference_recording SET paused_at = TIMESTAMP '2026-10-30 10:05:00' WHERE id = '$recordingId'") shouldBe false
            rejected("UPDATE conference_recording SET status = 'STOPPING' WHERE id = '$recordingId'") shouldBe true
            exec("UPDATE conference_recording SET paused_at = NULL, status = 'STOPPING' WHERE id = '$recordingId'")
            rejected("UPDATE conference_recording SET paused_at = TIMESTAMP '2026-10-30 10:06:00' WHERE id = '$recordingId'") shouldBe true
            exec("UPDATE conference_recording SET status = 'RECORDING' WHERE id = '$recordingId'")
        }

        test("a pause needs a forward range, a unique sequence number per recording and an existing recording") {
            rejected(pauseInsert(1, "2026-10-30 10:10:00", null)) shouldBe false
            rejected(pauseInsert(1, "2026-10-30 10:20:00", null)) shouldBe true // the same seq twice
            rejected(pauseInsert(2, "2026-10-30 10:20:00", "2026-10-30 10:19:00")) shouldBe true // resumed before paused
            rejected(pauseInsert(2, "2026-10-30 10:20:00", "2026-10-30 10:20:00")) shouldBe false
            rejected(pauseInsert(1, "2026-10-30 10:20:00", null, recording = UUID.randomUUID())) shouldBe true // no such recording
        }

        test("deleting a recording cascades to its pauses") {
            val recording2 = UUID.randomUUID()
            exec(
                "INSERT INTO conference_recording (id, room_id, started_by_member_id, started_at, status, access_level, raw_dir, " +
                    "compose_attempts) " +
                    "VALUES ('$recording2', '$roomId', '$memberId', TIMESTAMP '2026-10-30 11:00:00', 'READY', 'BOARD_ONLY', '$recording2', 1)",
            )
            exec(pauseInsert(1, "2026-10-30 11:10:00", "2026-10-30 11:12:00", recording = recording2))
            rows("SELECT COUNT(*) FROM conference_recording_pause WHERE recording_id = '$recording2'").single().single() shouldBe "1"
            exec("DELETE FROM conference_recording WHERE id = '$recording2'")
            rows("SELECT COUNT(*) FROM conference_recording_pause WHERE recording_id = '$recording2'").single().single() shouldBe "0"
        }

        test("ROOM_PAUSE is a valid pause reason of a stream, an unknown reason is still refused") {
            rejected("UPDATE conference_stream SET pause_reason = 'ROOM_PAUSE' WHERE id = '$streamId'") shouldBe false
            rejected("UPDATE conference_stream SET pause_reason = 'SECRET_BALLOT' WHERE id = '$streamId'") shouldBe false
            rejected("UPDATE conference_stream SET pause_reason = 'MANUAL' WHERE id = '$streamId'") shouldBe false
            rejected("UPDATE conference_stream SET pause_reason = 'BREAK' WHERE id = '$streamId'") shouldBe true
        }

        test("a re-run is a no-op") {
            h.flyway(null).migrate().migrationsExecuted shouldBe 0
        }
    })

class ConferenceCapturePauseMigrationTest : ConferenceCapturePauseMigrationScenarios({ H2MigrationHarness() })

@Tags("Postgres")
@EnabledIf(PostgresConfigured::class)
class ConferenceCapturePauseMigrationPostgresTest : ConferenceCapturePauseMigrationScenarios({ PostgresMigrationHarness() })
