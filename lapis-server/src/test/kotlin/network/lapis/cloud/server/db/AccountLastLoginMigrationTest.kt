package network.lapis.cloud.server.db

import io.kotest.core.annotation.EnabledIf
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import network.lapis.cloud.server.testdb.PostgresConfigured
import java.util.UUID

/**
 * Welle V1.9.207 -- `V118__account_last_login.sql` on the upgrade path V117 -> V118, on H2 AND PostgreSQL: existing accounts survive with
 * NULL/NULL (no backfill), the pair CHECK and the method CHECK bite, and a re-run is a no-op.
 */
abstract class AccountLastLoginMigrationScenarios(
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

        val oldMember = UUID.randomUUID()
        val newMember = UUID.randomUUID()

        fun addMember(id: UUID) =
            exec(
                "INSERT INTO member (id, display_name, email, status, joined_at) VALUES ('$id', 'M', 'll-$id@example.org', 'ACTIVE', DATE '2026-01-01')",
            )

        fun accountUpdate(
            at: String?,
            method: String?,
        ) = "UPDATE account SET last_login_at = ${at?.let { "TIMESTAMP '$it'" } ?: "NULL"}, " +
            "last_login_method = ${method?.let { "'$it'" } ?: "NULL"} WHERE member_id = '$newMember'"

        beforeSpec {
            h = newHarness()
            h.flyway("117").migrate()
            addMember(oldMember)
            exec("INSERT INTO account (id, member_id, role) VALUES ('${UUID.randomUUID()}', '$oldMember', 'MEMBER')")
            h.flyway(null).migrate()
            addMember(newMember)
            exec("INSERT INTO account (id, member_id, role) VALUES ('${UUID.randomUUID()}', '$newMember', 'MEMBER')")
        }
        afterSpec { h.close() }

        test("an account that existed before V118 survives with NULL/NULL: nothing is backfilled") {
            rows("SELECT last_login_at, last_login_method FROM account WHERE member_id = '$oldMember'").single() shouldBe
                listOf("null", "null")
        }

        test("the pair CHECK: time and method are both set or both NULL") {
            (rejection(accountUpdate("2026-10-24 10:00:00", null)) != null) shouldBe true
            (rejection(accountUpdate(null, "PASSWORD")) != null) shouldBe true
            rejection(accountUpdate("2026-10-24 10:00:00", "PASSWORD")) shouldBe null
            rejection(accountUpdate(null, null)) shouldBe null
        }

        test("the method CHECK accepts exactly PASSWORD, KEYCLOAK and OIDC_FEDERATION") {
            listOf("PASSWORD", "KEYCLOAK", "OIDC_FEDERATION").forEach {
                rejection(accountUpdate("2026-10-24 10:00:00", it)) shouldBe null
            }
            (rejection(accountUpdate("2026-10-24 10:00:00", "PASSKEY")) != null) shouldBe true
            (rejection(accountUpdate("2026-10-24 10:00:00", "password")) != null) shouldBe true
        }

        test("a re-run is a no-op") {
            h.flyway(null).migrate().migrationsExecuted shouldBe 0
        }
    })

class AccountLastLoginMigrationTest : AccountLastLoginMigrationScenarios({ H2MigrationHarness() })

@Tags("Postgres")
@EnabledIf(PostgresConfigured::class)
class AccountLastLoginMigrationPostgresTest : AccountLastLoginMigrationScenarios({ PostgresMigrationHarness() })
