package network.lapis.cloud.server.db

import io.kotest.core.annotation.EnabledIf
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import network.lapis.cloud.server.testdb.PostgresConfigured

/**
 * Welle V1.9.124 -- `V99__organization_noise_suppression_default.sql` on the upgrade path V98 -> V99, on H2 AND on PostgreSQL: the existing settings row keeps the
 * filter off (FALSE), the column is NOT NULL, it can be switched on, and a re-run is a no-op.
 */
abstract class OrganizationSettingsNoiseSuppressionDefaultMigrationScenarios(
    private val newHarness: () -> MigrationHarness,
) : FunSpec({
        lateinit var h: MigrationHarness

        fun exec(sql: String) = h.connection { c -> c.createStatement().use { it.execute(sql) } }

        fun single(sql: String): String =
            h.connection { c ->
                c.createStatement().use { st ->
                    st.executeQuery(sql).use { rs ->
                        rs.next()
                        rs.getString(1) ?: "null"
                    }
                }
            }

        fun flag(sql: String) = single(sql).lowercase().take(1)

        fun rejection(sql: String): String? =
            runCatching { exec(sql) }
                .exceptionOrNull()
                ?.let { generateSequence(it) { t -> t.cause }.joinToString(" | ") { t -> t.message.orEmpty() } }

        val rowId = "00000000-0000-0000-0000-0000000000f2"

        beforeSpec {
            h = newHarness()
            h.flyway("98").migrate()
            h.flyway(null).migrate()
        }
        afterSpec { h.close() }

        test("the existing settings row keeps the filter off (FALSE)") {
            flag("SELECT noise_suppression_default FROM organization_settings WHERE id = '$rowId'") shouldBe "f"
        }

        test("it can be switched on and off again, NULL is rejected") {
            exec("UPDATE organization_settings SET noise_suppression_default = TRUE WHERE id = '$rowId'")
            flag("SELECT noise_suppression_default FROM organization_settings WHERE id = '$rowId'") shouldBe "t"
            exec("UPDATE organization_settings SET noise_suppression_default = FALSE WHERE id = '$rowId'")
            flag("SELECT noise_suppression_default FROM organization_settings WHERE id = '$rowId'") shouldBe "f"
            (rejection("UPDATE organization_settings SET noise_suppression_default = NULL WHERE id = '$rowId'") != null) shouldBe true
        }

        test("a re-run is a no-op") {
            h.flyway(null).migrate().migrationsExecuted shouldBe 0
        }
    })

class OrganizationSettingsNoiseSuppressionDefaultMigrationTest :
    OrganizationSettingsNoiseSuppressionDefaultMigrationScenarios({ H2MigrationHarness() })

@Tags("Postgres")
@EnabledIf(PostgresConfigured::class)
class OrganizationSettingsNoiseSuppressionDefaultMigrationPostgresTest :
    OrganizationSettingsNoiseSuppressionDefaultMigrationScenarios({ PostgresMigrationHarness() })
