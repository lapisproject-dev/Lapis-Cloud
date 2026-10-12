package network.lapis.cloud.server.db

import io.kotest.core.annotation.EnabledIf
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import network.lapis.cloud.server.testdb.PostgresConfigured
import java.util.UUID

/**
 * Welle V1.9.120 -- `V95__conference_room_join_leave_sounds.sql` on the upgrade path V94 -> V95, on H2 AND on PostgreSQL: existing rooms keep the
 * tones (TRUE), the column is NOT NULL, a room can be switched off, and a re-run is a no-op.
 */
abstract class ConferenceJoinLeaveSoundsMigrationScenarios(
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

        beforeSpec {
            h = newHarness()
            h.flyway("94").migrate()
            exec(
                "INSERT INTO member (id, display_name, email, status, joined_at) VALUES ('$memberId', 'M', 'sound-$memberId@example.org', 'ACTIVE', DATE '2026-01-01')",
            )
            exec(
                "INSERT INTO conference_room (id, title, description, livekit_room_name, created_by_member_id, created_at, " +
                    "max_participants) " +
                    "VALUES ('$roomId', 'R', '', 'lc-$roomId', '$memberId', TIMESTAMP '2026-10-12 09:00:00', 25)",
            )
            h.flyway(null).migrate()
        }
        afterSpec { h.close() }

        test("an existing room keeps the tones (TRUE)") {
            rows("SELECT join_leave_sounds FROM conference_room WHERE id = '$roomId'")
                .single()
                .single()
                .lowercase()
                .take(1) shouldBe "t"
        }

        test("a room can be switched off and on again, NULL is rejected") {
            exec("UPDATE conference_room SET join_leave_sounds = FALSE WHERE id = '$roomId'")
            rows("SELECT join_leave_sounds FROM conference_room WHERE id = '$roomId'")
                .single()
                .single()
                .lowercase()
                .take(1) shouldBe "f"
            exec("UPDATE conference_room SET join_leave_sounds = TRUE WHERE id = '$roomId'")
            rows("SELECT join_leave_sounds FROM conference_room WHERE id = '$roomId'")
                .single()
                .single()
                .lowercase()
                .take(1) shouldBe "t"
            (rejection("UPDATE conference_room SET join_leave_sounds = NULL WHERE id = '$roomId'") != null) shouldBe true
        }

        test("a room inserted without the column defaults to TRUE") {
            val other = UUID.randomUUID()
            exec(
                "INSERT INTO conference_room (id, title, description, livekit_room_name, created_by_member_id, created_at, " +
                    "max_participants) " +
                    "VALUES ('$other', 'R2', '', 'lc-$other', '$memberId', TIMESTAMP '2026-10-12 10:00:00', 25)",
            )
            rows("SELECT join_leave_sounds FROM conference_room WHERE id = '$other'")
                .single()
                .single()
                .lowercase()
                .take(1) shouldBe "t"
        }

        test("a re-run is a no-op") {
            h.flyway(null).migrate().migrationsExecuted shouldBe 0
        }
    })

class ConferenceJoinLeaveSoundsMigrationTest : ConferenceJoinLeaveSoundsMigrationScenarios({ H2MigrationHarness() })

@Tags("Postgres")
@EnabledIf(PostgresConfigured::class)
class ConferenceJoinLeaveSoundsMigrationPostgresTest : ConferenceJoinLeaveSoundsMigrationScenarios({ PostgresMigrationHarness() })
