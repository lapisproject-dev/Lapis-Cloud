package network.lapis.cloud.server.db

import io.kotest.core.annotation.EnabledIf
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import network.lapis.cloud.server.testdb.PostgresConfigured
import java.util.UUID

/**
 * Welle V1.9.115 -- `V94__conference_broadcast_view_and_speaker_timeline.sql` on the upgrade path V93 -> V94, on H2 AND on PostgreSQL: existing rooms and
 * recordings get RASTER / "no timeline", the CHECK constraints bite, the span table rejects empty ranges, deleting a recording or a track cascades to
 * its spans, and a re-run is a no-op.
 */
abstract class ConferenceBroadcastViewMigrationScenarios(
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
        val recordingId = UUID.randomUUID()
        val trackId = UUID.randomUUID()

        fun spanInsert(
            id: UUID,
            recording: UUID,
            track: UUID,
            start: Long,
            end: Long,
        ) = "INSERT INTO conference_recording_speaker_span (id, recording_id, track_id, start_ms, end_ms) " +
            "VALUES ('$id', '$recording', '$track', $start, $end)"

        beforeSpec {
            h = newHarness()
            h.flyway("93").migrate()
            exec(
                "INSERT INTO member (id, display_name, email, status, joined_at) VALUES ('$memberId', 'M', 'view-$memberId@example.org', 'ACTIVE', DATE '2026-01-01')",
            )
            exec(
                "INSERT INTO conference_room (id, title, description, livekit_room_name, created_by_member_id, created_at, " +
                    "max_participants) " +
                    "VALUES ('$roomId', 'R', '', 'lc-$roomId', '$memberId', TIMESTAMP '2026-10-11 09:00:00', 25)",
            )
            exec(
                "INSERT INTO conference_recording (id, room_id, started_by_member_id, started_at, status, access_level, raw_dir, " +
                    "compose_attempts) " +
                    "VALUES ('$recordingId', '$roomId', '$memberId', TIMESTAMP '2026-10-11 10:00:00', 'READY', 'BOARD_ONLY', '$recordingId', 1)",
            )
            exec(
                "INSERT INTO conference_recording_track (id, recording_id, egress_id, livekit_track_id, participant_identity, " +
                    "track_source, status) " +
                    "VALUES ('$trackId', '$recordingId', 'EG_$trackId', 'TR_x', 'p', 'CAMERA', 'COMPLETE')",
            )
            h.flyway(null).migrate()
        }
        afterSpec { h.close() }

        test("existing rooms and recordings default to RASTER and to no speaker timeline") {
            rows("SELECT broadcast_view FROM conference_room WHERE id = '$roomId'").single().single() shouldBe "RASTER"
            rows("SELECT broadcast_view, speaker_timeline_computed FROM conference_recording WHERE id = '$recordingId'")
                .single()
                .map { it.lowercase().replace("^f$".toRegex(), "false") } shouldBe listOf("raster", "false")
        }

        test("the CHECK constraints accept RASTER and SPEAKER and reject everything else") {
            exec("UPDATE conference_room SET broadcast_view = 'SPEAKER' WHERE id = '$roomId'")
            rows("SELECT broadcast_view FROM conference_room WHERE id = '$roomId'").single().single() shouldBe "SPEAKER"
            (rejection("UPDATE conference_room SET broadcast_view = 'GALLERY' WHERE id = '$roomId'") != null) shouldBe true
            (rejection("UPDATE conference_recording SET broadcast_view = 'PINNED' WHERE id = '$recordingId'") != null) shouldBe true
            (rejection("UPDATE conference_room SET broadcast_view = NULL WHERE id = '$roomId'") != null) shouldBe true
            rejection("UPDATE conference_recording SET broadcast_view = 'SPEAKER' WHERE id = '$recordingId'") shouldBe null
        }

        test("a span needs a positive range and an existing recording and track") {
            rejection(spanInsert(UUID.randomUUID(), recordingId, trackId, 0, 1_000)) shouldBe null
            (rejection(spanInsert(UUID.randomUUID(), recordingId, trackId, 1_000, 1_000)) != null) shouldBe true
            (rejection(spanInsert(UUID.randomUUID(), recordingId, trackId, 2_000, 1_000)) != null) shouldBe true
            (rejection(spanInsert(UUID.randomUUID(), recordingId, trackId, -1, 1_000)) != null) shouldBe true
            (rejection(spanInsert(UUID.randomUUID(), UUID.randomUUID(), trackId, 0, 1_000)) != null) shouldBe true
            (rejection(spanInsert(UUID.randomUUID(), recordingId, UUID.randomUUID(), 0, 1_000)) != null) shouldBe true
        }

        test("deleting a track and deleting a recording cascade to the spans") {
            val recording2 = UUID.randomUUID()
            val track2 = UUID.randomUUID()
            exec(
                "INSERT INTO conference_recording (id, room_id, started_by_member_id, started_at, status, access_level, raw_dir, " +
                    "compose_attempts) " +
                    "VALUES ('$recording2', '$roomId', '$memberId', TIMESTAMP '2026-10-11 11:00:00', 'READY', 'BOARD_ONLY', '$recording2', 1)",
            )
            exec(
                "INSERT INTO conference_recording_track (id, recording_id, egress_id, livekit_track_id, participant_identity, " +
                    "track_source, status) " +
                    "VALUES ('$track2', '$recording2', 'EG_$track2', 'TR_y', 'q', 'CAMERA', 'COMPLETE')",
            )
            exec(spanInsert(UUID.randomUUID(), recording2, track2, 0, 500))
            exec("DELETE FROM conference_recording_track WHERE id = '$track2'")
            rows("SELECT COUNT(*) FROM conference_recording_speaker_span WHERE recording_id = '$recording2'").single().single() shouldBe "0"

            val track3 = UUID.randomUUID()
            exec(
                "INSERT INTO conference_recording_track (id, recording_id, egress_id, livekit_track_id, participant_identity, " +
                    "track_source, status) " +
                    "VALUES ('$track3', '$recording2', 'EG_$track3', 'TR_z', 'r', 'CAMERA', 'COMPLETE')",
            )
            exec(spanInsert(UUID.randomUUID(), recording2, track3, 0, 500))
            exec("DELETE FROM conference_recording_track WHERE recording_id = '$recording2'")
            exec("DELETE FROM conference_recording WHERE id = '$recording2'")
            rows("SELECT COUNT(*) FROM conference_recording_speaker_span WHERE recording_id = '$recording2'").single().single() shouldBe "0"
        }

        test("a re-run is a no-op") {
            h.flyway(null).migrate().migrationsExecuted shouldBe 0
        }
    })

class ConferenceBroadcastViewMigrationTest : ConferenceBroadcastViewMigrationScenarios({ H2MigrationHarness() })

@Tags("Postgres")
@EnabledIf(PostgresConfigured::class)
class ConferenceBroadcastViewMigrationPostgresTest : ConferenceBroadcastViewMigrationScenarios({ PostgresMigrationHarness() })
