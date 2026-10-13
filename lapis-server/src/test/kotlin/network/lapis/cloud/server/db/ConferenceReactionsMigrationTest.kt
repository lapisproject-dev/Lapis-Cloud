package network.lapis.cloud.server.db

import io.kotest.core.annotation.EnabledIf
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import network.lapis.cloud.server.testdb.PostgresConfigured
import java.util.UUID

/**
 * Welle V1.9.127 -- `V101__conference_room_reactions_enabled.sql` on the upgrade path V100 -> V101, on H2 AND on PostgreSQL: existing rooms
 * keep the reactions (TRUE), the column is NOT NULL, a room can be switched off, and a re-run is a no-op. V100 stays untouched (it is part
 * of the released v0.34.0).
 */
abstract class ConferenceReactionsMigrationScenarios(
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

        fun flag(roomId: UUID) =
            rows("SELECT reactions_enabled FROM conference_room WHERE id = '$roomId'")
                .single()
                .single()
                .lowercase()
                .take(1)

        val memberId = UUID.randomUUID()
        val roomId = UUID.randomUUID()

        beforeSpec {
            h = newHarness()
            h.flyway("100").migrate()
            exec(
                "INSERT INTO member (id, display_name, email, status, joined_at) VALUES ('$memberId', 'M', 'react-$memberId@example.org', 'ACTIVE', DATE '2026-01-01')",
            )
            exec(
                "INSERT INTO conference_room (id, title, description, livekit_room_name, created_by_member_id, created_at, " +
                    "max_participants) " +
                    "VALUES ('$roomId', 'R', '', 'lc-$roomId', '$memberId', TIMESTAMP '2026-10-13 09:00:00', 25)",
            )
            h.flyway(null).migrate()
        }
        afterSpec { h.close() }

        test("an existing room keeps the reactions (TRUE)") {
            flag(roomId) shouldBe "t"
        }

        test("a room can be switched off and on again, NULL is rejected") {
            exec("UPDATE conference_room SET reactions_enabled = FALSE WHERE id = '$roomId'")
            flag(roomId) shouldBe "f"
            exec("UPDATE conference_room SET reactions_enabled = TRUE WHERE id = '$roomId'")
            flag(roomId) shouldBe "t"
            (rejection("UPDATE conference_room SET reactions_enabled = NULL WHERE id = '$roomId'") != null) shouldBe true
        }

        test("a room inserted without the column defaults to TRUE") {
            val other = UUID.randomUUID()
            exec(
                "INSERT INTO conference_room (id, title, description, livekit_room_name, created_by_member_id, created_at, " +
                    "max_participants) " +
                    "VALUES ('$other', 'R2', '', 'lc-$other', '$memberId', TIMESTAMP '2026-10-13 10:00:00', 25)",
            )
            flag(other) shouldBe "t"
        }

        test("a re-run is a no-op") {
            h.flyway(null).migrate().migrationsExecuted shouldBe 0
        }
    })

class ConferenceReactionsMigrationTest : ConferenceReactionsMigrationScenarios({ H2MigrationHarness() })

@Tags("Postgres")
@EnabledIf(PostgresConfigured::class)
class ConferenceReactionsMigrationPostgresTest : ConferenceReactionsMigrationScenarios({ PostgresMigrationHarness() })
