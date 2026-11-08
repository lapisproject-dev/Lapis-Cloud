package network.lapis.cloud.server.db

import io.kotest.core.annotation.EnabledIf
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import network.lapis.cloud.server.testdb.PostgresConfigured
import java.util.UUID

/**
 * Welle V1.9.294 -- `V148__conference_recording_layout.sql` on the upgrade path V147 -> V148, on H2 AND on PostgreSQL: rows that exist before the
 * migration keep their view (SPEAKER stays SPEAKER, the rest is GRID), both CHECK constraints bite, and a re-run is a no-op.
 */
abstract class ConferenceRecordingLayoutMigrationScenarios(
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
        val gridRecording = UUID.randomUUID()
        val speakerRecording = UUID.randomUUID()

        fun recordingInsert(
            id: UUID,
            view: String,
        ) = "INSERT INTO conference_recording (id, room_id, started_by_member_id, started_at, status, access_level, raw_dir, " +
            "compose_attempts, broadcast_view) " +
            "VALUES ('$id', '$roomId', '$memberId', TIMESTAMP '2026-10-11 10:00:00', 'READY', 'BOARD_ONLY', '$id', 1, '$view')"

        beforeSpec {
            h = newHarness()
            h.flyway("147").migrate()
            exec(
                "INSERT INTO member (id, display_name, email, status, joined_at) VALUES ('$memberId', 'M', 'layout-$memberId@example.org', 'ACTIVE', DATE '2026-01-01')",
            )
            exec(
                "INSERT INTO conference_room (id, title, description, livekit_room_name, created_by_member_id, created_at, " +
                    "max_participants) " +
                    "VALUES ('$roomId', 'R', '', 'lc-$roomId', '$memberId', TIMESTAMP '2026-10-11 09:00:00', 25)",
            )
            exec(recordingInsert(gridRecording, "RASTER"))
            exec(recordingInsert(speakerRecording, "SPEAKER"))
            h.flyway(null).migrate()
        }
        afterSpec { h.close() }

        test("rows from before the migration keep their view: SPEAKER stays SPEAKER, RASTER becomes GRID, no identity") {
            rows("SELECT layout, single_participant_identity FROM conference_recording WHERE id = '$gridRecording'").single() shouldBe
                listOf("GRID", "null")
            rows("SELECT layout, single_participant_identity FROM conference_recording WHERE id = '$speakerRecording'").single() shouldBe
                listOf("SPEAKER", "null")
        }

        test("the layout CHECK rejects an unknown literal and NULL") {
            (rejection("UPDATE conference_recording SET layout = 'PINNED' WHERE id = '$gridRecording'") != null) shouldBe true
            (rejection("UPDATE conference_recording SET layout = NULL WHERE id = '$gridRecording'") != null) shouldBe true
        }

        test("a single image needs an identity, every other layout must not have one") {
            val sql = "UPDATE conference_recording SET layout = 'SINGLE_PARTICIPANT' WHERE id = '$gridRecording'"
            (rejection(sql) != null) shouldBe true
            (rejection("UPDATE conference_recording SET single_participant_identity = 'abc' WHERE id = '$gridRecording'") != null) shouldBe
                true
            rejection(
                "UPDATE conference_recording SET layout = 'SINGLE_PARTICIPANT', single_participant_identity = 'abc' WHERE id = '$gridRecording'",
            ) shouldBe null
            rows("SELECT layout, single_participant_identity FROM conference_recording WHERE id = '$gridRecording'").single() shouldBe
                listOf("SINGLE_PARTICIPANT", "abc")
            (rejection("UPDATE conference_recording SET layout = 'GRID' WHERE id = '$gridRecording'") != null) shouldBe true
            rejection(
                "UPDATE conference_recording SET layout = 'GRID', single_participant_identity = NULL WHERE id = '$gridRecording'",
            ) shouldBe null
        }

        test("the identity column holds at most 64 characters") {
            val tooLong = "x".repeat(65)
            (
                rejection(
                    "UPDATE conference_recording SET layout = 'SINGLE_PARTICIPANT', single_participant_identity = '$tooLong' " +
                        "WHERE id = '$gridRecording'",
                ) != null
            ) shouldBe true
        }

        test("a re-run is a no-op") {
            h.flyway(null).migrate().migrationsExecuted shouldBe 0
        }
    })

class ConferenceRecordingLayoutMigrationTest : ConferenceRecordingLayoutMigrationScenarios({ H2MigrationHarness() })

@Tags("Postgres")
@EnabledIf(PostgresConfigured::class)
class ConferenceRecordingLayoutMigrationPostgresTest : ConferenceRecordingLayoutMigrationScenarios({ PostgresMigrationHarness() })
