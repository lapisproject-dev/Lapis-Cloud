package network.lapis.cloud.server.db

import io.kotest.core.annotation.EnabledIf
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import network.lapis.cloud.server.testdb.PostgresConfigured
import java.util.UUID

/**
 * Welle V1.9.80 -- `V79__encounter_space_tables.sql` on the upgrade path V78 -> V79, on H2 AND on PostgreSQL: an existing room keeps tables
 * off with the defaults 4 x 6, every CHECK is exercised from the negative side (count, seats, "tables only in the ASSEMBLY profile"),
 * a re-run is a no-op.
 */
abstract class EncounterSpaceTablesMigrationScenarios(
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
            profile: String = "ASSEMBLY",
            extraColumns: String = "",
            extraValues: String = "",
        ) = "INSERT INTO encounter_space (id, title, description, theme_key, mode, profile, guest_policy, created_at, " +
            "created_by_member_id$extraColumns) VALUES " +
            "('$id', 'T', '', 'CHURCH', 'SERVICE', '$profile', 'MEMBERS_ONLY', TIMESTAMP '2026-10-08 10:00:00', '$creator'$extraValues)"

        beforeSpec {
            h = newHarness()
            h.flyway("78").migrate()
            exec(
                "INSERT INTO member (id, display_name, email, status, joined_at) VALUES ('$creator', 'M', 'enc-$creator@example.org', 'ACTIVE', DATE '2026-01-01')",
            )
            exec(spaceInsert(id = oldSpace, profile = "CHURCH_SERVICE"))
            h.flyway(null).migrate()
        }
        afterSpec { h.close() }

        test("an existing room keeps tables off and gets the defaults 4 x 6") {
            rows("SELECT tables_enabled, table_count, table_seats FROM encounter_space WHERE id = '$oldSpace'").single().let {
                (it[0].lowercase() in setOf("false", "f", "0")) shouldBe true
                it[1] shouldBe "4"
                it[2] shouldBe "6"
            }
        }

        test("a re-run is a no-op") {
            h.flyway(null).migrate().migrationsExecuted shouldBe 0
        }

        fun withTables(
            count: Int,
            seats: Int,
            enabled: Boolean = false,
            profile: String = "ASSEMBLY",
        ) = rejection(
            spaceInsert(
                id = UUID.randomUUID(),
                profile = profile,
                extraColumns = ", tables_enabled, table_count, table_seats",
                extraValues = ", $enabled, $count, $seats",
            ),
        )

        test("the table count CHECK accepts 1..12 and rejects 0 and 13") {
            withTables(count = 1, seats = 2) shouldBe null
            withTables(count = 12, seats = 8) shouldBe null
            (withTables(count = 0, seats = 4) != null) shouldBe true
            (withTables(count = 13, seats = 4) != null) shouldBe true
        }

        test("the seats CHECK accepts 2..8 and rejects 1 and 9") {
            withTables(count = 4, seats = 2) shouldBe null
            withTables(count = 4, seats = 8) shouldBe null
            (withTables(count = 4, seats = 1) != null) shouldBe true
            (withTables(count = 4, seats = 9) != null) shouldBe true
        }

        test("tables may only be enabled in the ASSEMBLY profile") {
            withTables(count = 4, seats = 6, enabled = true, profile = "ASSEMBLY") shouldBe null
            (withTables(count = 4, seats = 6, enabled = true, profile = "CHURCH_SERVICE") != null) shouldBe true
            withTables(count = 4, seats = 6, enabled = false, profile = "CHURCH_SERVICE") shouldBe null
        }

        test("tables_enabled, table_count and table_seats are NOT NULL") {
            (rejection(spaceInsert(id = UUID.randomUUID(), extraColumns = ", tables_enabled", extraValues = ", NULL")) != null) shouldBe
                true
            (rejection(spaceInsert(id = UUID.randomUUID(), extraColumns = ", table_count", extraValues = ", NULL")) != null) shouldBe true
            (rejection(spaceInsert(id = UUID.randomUUID(), extraColumns = ", table_seats", extraValues = ", NULL")) != null) shouldBe true
        }

        test("the profile cannot be switched to the church service while tables are enabled") {
            val id = UUID.randomUUID()
            exec(
                spaceInsert(id = id, extraColumns = ", tables_enabled", extraValues = ", TRUE"),
            )
            (rejection("UPDATE encounter_space SET profile = 'CHURCH_SERVICE' WHERE id = '$id'") != null) shouldBe true
        }
    })

class EncounterSpaceTablesMigrationTest : EncounterSpaceTablesMigrationScenarios({ H2MigrationHarness() })

@Tags("Postgres")
@EnabledIf(PostgresConfigured::class)
class EncounterSpaceTablesMigrationPostgresTest : EncounterSpaceTablesMigrationScenarios({ PostgresMigrationHarness() })
