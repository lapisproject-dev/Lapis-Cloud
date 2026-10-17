package network.lapis.cloud.server.db

import io.kotest.core.annotation.EnabledIf
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import network.lapis.cloud.server.testdb.PostgresConfigured
import java.util.UUID

/**
 * Welle V1.9.152 -- `V109__encounter_room_layout.sql` on the upgrade path V108 -> V109, on H2 AND on PostgreSQL: existing rooms are backfilled
 * (church service -> NAVE, everything else -> HALL) BEFORE the NOT NULL and the CHECKs apply, every CHECK is exercised from the negative side
 * (unknown value, every unfitting profile combination), a pre-V109 style insert (no room_layout) still works because HALL fits both profiles,
 * a re-run is a no-op.
 */
abstract class EncounterRoomLayoutMigrationScenarios(
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
        val oldChurch = UUID.randomUUID()
        val oldAssembly = UUID.randomUUID()

        fun spaceInsert(
            id: UUID,
            profile: String,
            layout: String? = null,
        ) = "INSERT INTO encounter_space (id, title, description, theme_key, mode, profile, guest_policy, created_at, " +
            "created_by_member_id${if (layout != null) ", room_layout" else ""}) VALUES " +
            "('$id', 'T', '', 'CHURCH', 'SERVICE', '$profile', 'MEMBERS_ONLY', TIMESTAMP '2026-10-17 10:00:00', '$creator'" +
            "${if (layout != null) ", '$layout'" else ""})"

        beforeSpec {
            h = newHarness()
            h.flyway("108").migrate()
            exec(
                "INSERT INTO member (id, display_name, email, status, joined_at) VALUES ('$creator', 'M', 'enc-$creator@example.org', 'ACTIVE', DATE '2026-01-01')",
            )
            exec(spaceInsert(id = oldChurch, profile = "CHURCH_SERVICE"))
            exec(spaceInsert(id = oldAssembly, profile = "ASSEMBLY"))
            h.flyway(null).migrate()
        }
        afterSpec { h.close() }

        test("existing rooms are backfilled: church service -> NAVE, assembly -> HALL") {
            rows("SELECT room_layout FROM encounter_space WHERE id = '$oldChurch'").single().single() shouldBe "NAVE"
            rows("SELECT room_layout FROM encounter_space WHERE id = '$oldAssembly'").single().single() shouldBe "HALL"
        }

        test("a re-run is a no-op") {
            h.flyway(null).migrate().migrationsExecuted shouldBe 0
        }

        test("every fitting combination is accepted") {
            listOf(
                "CHURCH_SERVICE" to "NAVE",
                "CHURCH_SERVICE" to "HALL",
                "ASSEMBLY" to "HALL",
                "ASSEMBLY" to "FOYER",
                "ASSEMBLY" to "SMALL_GROUP",
            ).forEach { (profile, layout) ->
                rejection(spaceInsert(id = UUID.randomUUID(), profile = profile, layout = layout)) shouldBe null
            }
        }

        test("every unfitting combination and an unknown value are rejected") {
            listOf(
                "CHURCH_SERVICE" to "FOYER",
                "CHURCH_SERVICE" to "SMALL_GROUP",
                "ASSEMBLY" to "NAVE",
                "ASSEMBLY" to "GARDEN",
                "CHURCH_SERVICE" to "",
            ).forEach { (profile, layout) ->
                (rejection(spaceInsert(id = UUID.randomUUID(), profile = profile, layout = layout)) != null) shouldBe true
            }
        }

        test("a pre-V109 style insert without room_layout still works for both profiles (default HALL fits both)") {
            val church = UUID.randomUUID()
            rejection(spaceInsert(id = church, profile = "CHURCH_SERVICE")) shouldBe null
            rejection(spaceInsert(id = UUID.randomUUID(), profile = "ASSEMBLY")) shouldBe null
            rows("SELECT room_layout FROM encounter_space WHERE id = '$church'").single().single() shouldBe "HALL"
        }

        test("room_layout is NOT NULL") {
            (rejection("UPDATE encounter_space SET room_layout = NULL WHERE id = '$oldChurch'") != null) shouldBe true
        }

        test("the profile cannot be switched to one that no longer fits the template") {
            val id = UUID.randomUUID()
            exec(spaceInsert(id = id, profile = "ASSEMBLY", layout = "FOYER"))
            (rejection("UPDATE encounter_space SET profile = 'CHURCH_SERVICE' WHERE id = '$id'") != null) shouldBe true
        }
    })

class EncounterRoomLayoutMigrationTest : EncounterRoomLayoutMigrationScenarios({ H2MigrationHarness() })

@Tags("Postgres")
@EnabledIf(PostgresConfigured::class)
class EncounterRoomLayoutMigrationPostgresTest : EncounterRoomLayoutMigrationScenarios({ PostgresMigrationHarness() })
