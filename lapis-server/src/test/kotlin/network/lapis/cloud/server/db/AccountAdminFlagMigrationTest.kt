package network.lapis.cloud.server.db

import io.kotest.core.annotation.EnabledIf
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import network.lapis.cloud.server.testdb.PostgresConfigured
import java.util.UUID

/** V155 on the upgrade path V154 -> V155, H2 AND PostgreSQL: ADMIN accounts become MEMBER + is_admin, the CHECK drops ADMIN. */
abstract class AccountAdminFlagMigrationScenarios(
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

        // JDBC getString() of a BOOLEAN is "true"/"false" on H2 but "t"/"f" on PostgreSQL.
        fun isTrue(raw: String) = raw.lowercase() in setOf("true", "t")

        val adminStamped = UUID.randomUUID()
        val adminSeeded = UUID.randomUUID()
        val board = UUID.randomUUID()
        val treasurer = UUID.randomUUID()

        fun addAccount(
            id: UUID,
            role: String,
            changedAt: String?,
        ) {
            exec(
                "INSERT INTO member (id, display_name, email, status, joined_at) VALUES ('$id', 'M', 'af-$id@example.org', 'ACTIVE', DATE '2026-01-01')",
            )
            exec(
                "INSERT INTO account (id, member_id, role, role_changed_at) VALUES ('${UUID.randomUUID()}', '$id', '$role', " +
                    "${changedAt?.let { "TIMESTAMP '$it'" } ?: "NULL"})",
            )
        }

        beforeSpec {
            h = newHarness()
            h.flyway("154").migrate()
            addAccount(adminStamped, "ADMIN", "2026-03-01 10:00:00")
            addAccount(adminSeeded, "ADMIN", null)
            addAccount(board, "BOARD", "2026-03-02 10:00:00")
            addAccount(treasurer, "TREASURER", null)
            h.flyway(null).migrate()
        }
        afterSpec { h.close() }

        fun row(id: UUID) = rows("SELECT role, is_admin, admin_changed_at, role_changed_at FROM account WHERE member_id = '$id'").single()

        test("an ADMIN account becomes MEMBER with the admin flag and keeps its stamp as the tenure clock") {
            val r = row(adminStamped)
            r[0] shouldBe "MEMBER"
            isTrue(r[1]) shouldBe true
            r[2].take(19) shouldBe "2026-03-01 10:00:00"
            r[3].take(19) shouldBe "2026-03-01 10:00:00"
        }

        test("a never-stamped admin stays tenured (admin_changed_at NULL)") {
            val r = row(adminSeeded)
            r[0] shouldBe "MEMBER"
            isTrue(r[1]) shouldBe true
            r[2] shouldBe "null"
        }

        test("BOARD and TREASURER are untouched and carry no admin flag") {
            row(board).let {
                it[0] shouldBe "BOARD"
                isTrue(it[1]) shouldBe false
                it[2] shouldBe "null"
            }
            row(treasurer).let {
                it[0] shouldBe "TREASURER"
                isTrue(it[1]) shouldBe false
            }
        }

        test("exactly one CHECK guards role, and it is the named one") {
            val checks =
                rows(
                    "SELECT tc.constraint_name FROM information_schema.table_constraints tc " +
                        "JOIN information_schema.check_constraints cc ON cc.constraint_name = tc.constraint_name " +
                        "AND cc.constraint_schema = tc.constraint_schema " +
                        "WHERE LOWER(tc.table_name) = 'account' AND tc.constraint_type = 'CHECK' " +
                        "AND LOWER(cc.check_clause) LIKE '%role%' AND LOWER(cc.check_clause) NOT LIKE '%is not null%'",
                ).map { it[0].lowercase() }
            checks shouldBe listOf("chk_account_role")
        }

        test("role ADMIN is rejected by the CHECK") {
            val id = UUID.randomUUID()
            exec(
                "INSERT INTO member (id, display_name, email, status, joined_at) VALUES ('$id', 'M', 'af-$id@example.org', 'ACTIVE', DATE '2026-01-01')",
            )
            val err =
                runCatching {
                    exec("INSERT INTO account (id, member_id, role) VALUES ('${UUID.randomUUID()}', '$id', 'ADMIN')")
                }.exceptionOrNull()
            (err != null) shouldBe true
        }

        test("is_admin defaults to FALSE for an insert without the column") {
            val id = UUID.randomUUID()
            exec(
                "INSERT INTO member (id, display_name, email, status, joined_at) VALUES ('$id', 'M', 'af-$id@example.org', 'ACTIVE', DATE '2026-01-01')",
            )
            exec("INSERT INTO account (id, member_id, role) VALUES ('${UUID.randomUUID()}', '$id', 'MEMBER')")
            isTrue(row(id)[1]) shouldBe false
        }

        test("a re-run is a no-op") {
            h.flyway(null).migrate().migrationsExecuted shouldBe 0
        }
    })

class AccountAdminFlagMigrationTest : AccountAdminFlagMigrationScenarios({ H2MigrationHarness() })

@Tags("Postgres")
@EnabledIf(PostgresConfigured::class)
class AccountAdminFlagMigrationPostgresTest : AccountAdminFlagMigrationScenarios({ PostgresMigrationHarness() })
