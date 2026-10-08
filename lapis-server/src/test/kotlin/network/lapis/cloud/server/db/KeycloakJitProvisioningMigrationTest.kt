package network.lapis.cloud.server.db

import io.kotest.core.annotation.EnabledIf
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import network.lapis.cloud.server.testdb.PostgresConfigured
import java.util.UUID

/**
 * Welle V1.9.73 -- `V77__keycloak_jit_provisioning.sql` on the upgrade path V76 -> V77, on H2 AND on PostgreSQL: existing rows survive,
 * `KEYCLOAK_JIT` is a runtime source (needs `recorded_at`), `IDP_SYNC` is a valid change kind, everything else is still rejected, the
 * BACKFILL rules are unchanged, a re-run is a no-op.
 */
abstract class KeycloakJitProvisioningMigrationScenarios(
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

        val member = UUID.randomUUID()

        fun history(
            at: String,
            source: String,
            recorded: String?,
        ) = "INSERT INTO member_status_history (member_id, effective_from, status, previous_status, source, recorded_at) VALUES " +
            "('$member', TIMESTAMP '$at', 'ACTIVE', NULL, '$source', ${recorded ?: "NULL"})"

        fun change(kind: String) =
            "INSERT INTO member_email_change (id, member_id, open_member_id, pending_email, kind, status, created_at, expires_at) VALUES " +
                "('${UUID.randomUUID()}', '$member', NULL, 'x-${UUID.randomUUID()}@example.org', '$kind', 'APPLIED', " +
                "TIMESTAMP '2026-01-01 10:00:00', TIMESTAMP '2026-01-01 10:00:00')"

        beforeSpec {
            h = newHarness()
            h.flyway("76").migrate()
            exec(
                "INSERT INTO member (id, display_name, email, status, joined_at) VALUES ('$member', 'M', 'jit-$member@example.org', 'ACTIVE', DATE '2026-01-01')",
            )
            exec(history(at = "2026-01-01 00:00:00", source = "LIVE", recorded = "TIMESTAMP '2026-01-01 00:00:00'"))
            exec(change("SELF"))
            h.flyway(null).migrate()
        }
        afterSpec { h.close() }

        test("the rows written before V77 survive") {
            rows("SELECT source FROM member_status_history WHERE member_id = '$member'") shouldBe listOf(listOf("LIVE"))
            rows("SELECT kind FROM member_email_change WHERE member_id = '$member'") shouldBe listOf(listOf("SELF"))
        }

        test("a re-run is a no-op") {
            h.flyway(null).migrate().migrationsExecuted shouldBe 0
        }

        test("KEYCLOAK_JIT is accepted with recorded_at and rejected without") {
            rejection(history(at = "2030-01-01 00:00:00", source = "KEYCLOAK_JIT", recorded = "TIMESTAMP '2030-01-01 00:00:00'")) shouldBe
                null
            (rejection(history(at = "2030-01-02 00:00:00", source = "KEYCLOAK_JIT", recorded = null)) != null) shouldBe true
        }

        test("the other sources keep their rules: unknown source rejected, BACKFILL needs NO recorded_at, LIVE needs one") {
            (
                rejection(
                    history(at = "2030-02-01 00:00:00", source = "BOGUS", recorded = "TIMESTAMP '2030-02-01 00:00:00'"),
                ) != null
            ) shouldBe
                true
            (
                rejection(
                    history(at = "2030-02-02 00:00:00", source = "BACKFILL_AUDIT", recorded = "TIMESTAMP '2030-02-02 00:00:00'"),
                ) != null
            ) shouldBe
                true
            rejection(history(at = "2030-02-03 00:00:00", source = "BACKFILL_ASSUMED", recorded = null)) shouldBe null
            (rejection(history(at = "2030-02-04 00:00:00", source = "LIVE", recorded = null)) != null) shouldBe true
            rejection(history(at = "2030-02-05 00:00:00", source = "IMPORT", recorded = "TIMESTAMP '2030-02-05 00:00:00'")) shouldBe null
            rejection(history(at = "2030-02-06 00:00:00", source = "SEED", recorded = "TIMESTAMP '2030-02-06 00:00:00'")) shouldBe null
        }

        test("IDP_SYNC is an accepted change kind, an unknown kind is still rejected") {
            rejection(change("IDP_SYNC")) shouldBe null
            rejection(change("ADMIN_OVERRIDE")) shouldBe null
            (rejection(change("BOGUS")) != null) shouldBe true
            (rejection(change("idp_sync")) != null) shouldBe true
        }
    })

class KeycloakJitProvisioningMigrationTest : KeycloakJitProvisioningMigrationScenarios({ H2MigrationHarness() })

@Tags("Postgres")
@EnabledIf(PostgresConfigured::class)
class KeycloakJitProvisioningMigrationPostgresTest : KeycloakJitProvisioningMigrationScenarios({ PostgresMigrationHarness() })
