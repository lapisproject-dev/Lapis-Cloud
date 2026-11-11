package network.lapis.cloud.server.db

import io.kotest.core.annotation.EnabledIf
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import network.lapis.cloud.server.testdb.PostgresConfigured
import java.util.UUID

/**
 * V151 `member_title`: the LupaBB title moves from `member_lupabb_profile.title` into `member.title`. The backfill trims, leaves blank and
 * NULL as NULL, never overwrites an existing manual title, and the side-table column is gone afterwards. The same scenarios run on H2 and
 * on PostgreSQL. (Flyway never re-runs a migration; the script is deliberately not repeatable after the DROP.)
 */
abstract class MemberTitleMigrationScenarios(
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

        val padded = UUID.randomUUID()
        val blank = UUID.randomUUID()
        val none = UUID.randomUUID()
        val manual = UUID.randomUUID()
        val noProfile = UUID.randomUUID()

        fun insertMember(id: UUID) =
            exec(
                "INSERT INTO member (id, display_name, email, status, joined_at) VALUES " +
                    "('$id', 'M $id', 'mt-$id@example.org', 'ACTIVE', DATE '2024-01-01')",
            )

        fun insertProfile(
            id: UUID,
            title: String?,
        ) = exec(
            "INSERT INTO member_lupabb_profile (member_id, title, last_synced_at) VALUES " +
                "('$id', ${title?.let { "'$it'" } ?: "NULL"}, TIMESTAMP '2026-01-01 00:00:00')",
        )

        fun title(id: UUID) = rows("SELECT title FROM member WHERE id = '$id'").single().single()

        beforeSpec {
            h = newHarness()
            h.flyway("150").migrate()
            listOf(padded, blank, none, manual, noProfile).forEach { insertMember(it) }
            insertProfile(padded, " Dr. ")
            insertProfile(blank, "   ")
            insertProfile(none, null)
            insertProfile(manual, "Prof.")
            exec("ALTER TABLE member ADD COLUMN title VARCHAR(50)")
            exec("UPDATE member SET title = 'Dipl.-Ing.' WHERE id = '$manual'")
        }
        afterSpec { h.close() }

        test("V151 backfills the trimmed LupaBB title, keeps blank and NULL as NULL and never overwrites a manual title") {
            // the manual title was created ahead of V151 by the setup above; V151's ADD COLUMN IF NOT EXISTS must tolerate it
            h.flyway(null).migrate()
            title(padded) shouldBe "Dr."
            title(blank) shouldBe "null"
            title(none) shouldBe "null"
            title(manual) shouldBe "Dipl.-Ing."
            title(noProfile) shouldBe "null"
        }

        test("the side-table title column is gone, the member column accepts 50 characters") {
            rows(
                "SELECT COUNT(*) FROM information_schema.columns WHERE LOWER(table_name) = 'member_lupabb_profile' AND LOWER(column_name) = 'title'",
            ) shouldBe
                listOf(listOf("0"))
            exec("UPDATE member SET title = '${"x".repeat(50)}' WHERE id = '$none'")
            runCatching { exec("UPDATE member SET title = '${"x".repeat(51)}' WHERE id = '$none'") }.isFailure shouldBe true
        }
    })

class MemberTitleMigrationTest : MemberTitleMigrationScenarios({ H2MigrationHarness() })

@Tags("Postgres")
@EnabledIf(PostgresConfigured::class)
class MemberTitleMigrationPostgresTest : MemberTitleMigrationScenarios({ PostgresMigrationHarness() })
