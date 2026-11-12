package network.lapis.cloud.server.db

import io.kotest.core.annotation.EnabledIf
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import network.lapis.cloud.server.testdb.PostgresConfigured

/**
 * V152 `committee_role_treasurer`: the four explicitly named CHECK constraints that carry `CommitteeRole` accept `TREASURER`, nothing is
 * converted, no old 8-value CHECK stays behind next to the new one (it would keep rejecting the new literal under AND semantics), and the
 * script is repeatable. Verified through the catalog on H2 and on PostgreSQL.
 */
abstract class CommitteeRoleTreasurerMigrationScenarios(
    private val newHarness: () -> MigrationHarness,
) : FunSpec({
        lateinit var h: MigrationHarness

        val targets =
            listOf(
                "committee_membership" to "committee_membership_role_check",
                "board_membership" to "board_membership_committee_role_check",
                "transparenzregister_reminder" to "transparenzregister_reminder_committee_role_check",
                "election" to "election_target_role_check",
            )

        fun rows(sql: String): List<List<String>> =
            h.connection { c ->
                c.createStatement().use { st ->
                    st.executeQuery(sql).use { rs ->
                        val n = rs.metaData.columnCount
                        buildList { while (rs.next()) add((1..n).map { rs.getString(it) ?: "null" }) }
                    }
                }
            }

        /** CHECK clauses of one table that mention the role literals -- exactly one is expected per table. */
        fun roleChecks(table: String): List<List<String>> =
            rows(
                "SELECT cc.constraint_name, cc.check_clause FROM information_schema.check_constraints cc " +
                    "JOIN information_schema.table_constraints tc ON tc.constraint_name = cc.constraint_name " +
                    "AND tc.constraint_schema = cc.constraint_schema " +
                    "WHERE LOWER(tc.table_name) = '$table' AND cc.check_clause LIKE '%MANAGING_DIRECTOR%'",
            )

        beforeSpec { h = newHarness() }
        afterSpec { h.close() }

        test("before V152 the four constraints are the 8-value CHECKs") {
            h.flyway("151").migrate()
            targets.forEach { (table, name) ->
                val checks = roleChecks(table)
                checks.size shouldBe 1
                checks.single()[0].lowercase() shouldBe name
                checks.single()[1] shouldNotContain "TREASURER"
            }
        }

        test("after V152 exactly one role CHECK per table remains and it accepts TREASURER") {
            h.flyway(null).migrate()
            targets.forEach { (table, name) ->
                val checks = roleChecks(table)
                checks.size shouldBe 1
                checks.single()[0].lowercase() shouldBe name
                checks.single()[1] shouldContain "TREASURER"
                checks.single()[1] shouldContain "ASSESSOR"
            }
        }

        test("V152 is repeatable and leaves election.target_role nullable") {
            val sql =
                CommitteeRoleTreasurerMigrationScenarios::class.java.classLoader
                    .getResourceAsStream("db/migration/V152__committee_role_treasurer.sql")!!
                    .bufferedReader()
                    .readText()
            h.connection { c -> c.createStatement().use { it.execute(sql) } }
            targets.forEach { (table, _) -> roleChecks(table).size shouldBe 1 }
            rows(
                "SELECT is_nullable FROM information_schema.columns WHERE LOWER(table_name) = 'election' AND LOWER(column_name) = 'target_role'",
            ) shouldBe listOf(listOf("YES"))
        }

        test("the columns stay VARCHAR(20): TREASURER fits") {
            targets.forEach { (table, _) ->
                val column =
                    if (table == "committee_membership") {
                        "role"
                    } else if (table == "election") {
                        "target_role"
                    } else {
                        "committee_role"
                    }
                rows(
                    "SELECT character_maximum_length FROM information_schema.columns " +
                        "WHERE LOWER(table_name) = '$table' AND LOWER(column_name) = '$column'",
                ) shouldBe listOf(listOf("20"))
            }
        }
    })

class CommitteeRoleTreasurerMigrationTest : CommitteeRoleTreasurerMigrationScenarios({ H2MigrationHarness() })

@Tags("Postgres")
@EnabledIf(PostgresConfigured::class)
class CommitteeRoleTreasurerMigrationPostgresTest : CommitteeRoleTreasurerMigrationScenarios({ PostgresMigrationHarness() })
