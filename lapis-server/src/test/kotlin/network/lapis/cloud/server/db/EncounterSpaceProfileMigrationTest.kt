package network.lapis.cloud.server.db

import io.kotest.core.annotation.EnabledIf
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import network.lapis.cloud.server.testdb.PostgresConfigured
import java.util.UUID

/**
 * Welle V1.9.67 -- `V76__encounter_space_profile.sql` on the upgrade path V75 -> V76, on H2 AND on PostgreSQL: an existing room becomes
 * CHURCH_SERVICE with HAND,AMEN (ELB's behaviour is unchanged), theme_key is untouched, both CHECKs bite, a re-run is a no-op.
 */
abstract class EncounterSpaceProfileMigrationScenarios(
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
            "('$id', 'T', '', 'CHURCH', 'SERVICE', 'MEMBERS_ONLY', TIMESTAMP '2026-10-06 10:00:00', '$creator'"

        beforeSpec {
            h = newHarness()
            h.flyway("75").migrate()
            exec(
                "INSERT INTO member (id, display_name, email, status, joined_at) VALUES ('$creator', 'M', 'enc-$creator@example.org', 'ACTIVE', DATE '2026-01-01')",
            )
            exec(spaceInsert(id = oldSpace) + ")")
            h.flyway(null).migrate()
        }
        afterSpec { h.close() }

        test("an existing room becomes CHURCH_SERVICE with HAND,AMEN and keeps theme_key CHURCH") {
            rows("SELECT profile, reaction_set, theme_key FROM encounter_space WHERE id = '$oldSpace'") shouldBe
                listOf(listOf("CHURCH_SERVICE", "HAND,AMEN", "CHURCH"))
        }

        test("a re-run is a no-op") {
            h.flyway(null).migrate().migrationsExecuted shouldBe 0
        }

        test("the profile CHECK accepts CHURCH_SERVICE and ASSEMBLY and rejects anything else") {
            rejection(spaceInsert(id = UUID.randomUUID(), extra = ", profile") + ", 'ASSEMBLY')") shouldBe null
            rejection(spaceInsert(id = UUID.randomUUID(), extra = ", profile") + ", 'CHURCH_SERVICE')") shouldBe null
            (rejection(spaceInsert(id = UUID.randomUUID(), extra = ", profile") + ", 'FOO')") != null) shouldBe true
            (rejection(spaceInsert(id = UUID.randomUUID(), extra = ", profile") + ", 'AMEN')") != null) shouldBe true
        }

        test(
            "the reaction_set CHECK requires HAND first: HAND, HAND,x and HAND,AMEN,HEART pass; AMEN, HANDX and an empty value are rejected",
        ) {
            fun withSet(value: String) = rejection(spaceInsert(id = UUID.randomUUID(), extra = ", reaction_set") + ", '$value')")
            withSet("HAND") shouldBe null
            withSet("HAND,APPLAUSE") shouldBe null
            withSet("HAND,AMEN,HEART") shouldBe null
            (withSet("AMEN") != null) shouldBe true
            (withSet("HANDX") != null) shouldBe true
            (withSet("") != null) shouldBe true
            (withSet("AMEN,HAND") != null) shouldBe true
        }
    })

class EncounterSpaceProfileMigrationTest : EncounterSpaceProfileMigrationScenarios({ H2MigrationHarness() })

@Tags("Postgres")
@EnabledIf(PostgresConfigured::class)
class EncounterSpaceProfileMigrationPostgresTest : EncounterSpaceProfileMigrationScenarios({ PostgresMigrationHarness() })
