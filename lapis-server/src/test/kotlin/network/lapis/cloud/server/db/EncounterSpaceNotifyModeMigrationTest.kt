package network.lapis.cloud.server.db

import io.kotest.core.annotation.EnabledIf
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import network.lapis.cloud.server.testdb.PostgresConfigured
import java.util.UUID

/**
 * Welle V1.9.76 -- `V78__encounter_space_notify_mode.sql` on the upgrade path V77 -> V78, on H2 AND on PostgreSQL: an existing room
 * becomes NONE (no mail, behaviour unchanged), the CHECK accepts exactly the three modes, a re-run is a no-op.
 */
abstract class EncounterSpaceNotifyModeMigrationScenarios(
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

        val creator = UUID.randomUUID()
        val oldSpace = UUID.randomUUID()

        fun spaceInsert(
            id: UUID,
            extra: String = "",
        ) = "INSERT INTO encounter_space (id, title, description, theme_key, mode, guest_policy, created_at, " +
            "created_by_member_id$extra) VALUES " +
            "('$id', 'T', '', 'CHURCH', 'SERVICE', 'MEMBERS_ONLY', TIMESTAMP '2026-10-08 10:00:00', '$creator'"

        beforeSpec {
            h = newHarness()
            h.flyway("77").migrate()
            exec(
                "INSERT INTO member (id, display_name, email, status, joined_at) VALUES ('$creator', 'M', 'enc-$creator@example.org', 'ACTIVE', DATE '2026-01-01')",
            )
            exec(spaceInsert(id = oldSpace) + ")")
            h.flyway(null).migrate()
        }
        afterSpec { h.close() }

        test("an existing room becomes NONE and keeps its profile and reactions") {
            rows("SELECT notify_mode, profile, reaction_set FROM encounter_space WHERE id = '$oldSpace'") shouldBe
                listOf(listOf("NONE", "CHURCH_SERVICE", "HAND,AMEN"))
        }

        test("a re-run is a no-op") {
            h.flyway(null).migrate().migrationsExecuted shouldBe 0
        }

        test("the CHECK accepts NONE, FIRST_GUEST and EVERY_GUEST and rejects anything else") {
            fun withMode(value: String) = rejection(spaceInsert(id = UUID.randomUUID(), extra = ", notify_mode") + ", '$value')")
            withMode("NONE") shouldBe null
            withMode("FIRST_GUEST") shouldBe null
            withMode("EVERY_GUEST") shouldBe null
            (withMode("ALL") != null) shouldBe true
            (withMode("") != null) shouldBe true
            (withMode("first_guest") != null) shouldBe true
        }

        test("notify_mode is NOT NULL") {
            (rejection(spaceInsert(id = UUID.randomUUID(), extra = ", notify_mode") + ", NULL)") != null) shouldBe true
        }
    })

class EncounterSpaceNotifyModeMigrationTest : EncounterSpaceNotifyModeMigrationScenarios({ H2MigrationHarness() })

@Tags("Postgres")
@EnabledIf(PostgresConfigured::class)
class EncounterSpaceNotifyModeMigrationPostgresTest : EncounterSpaceNotifyModeMigrationScenarios({ PostgresMigrationHarness() })
