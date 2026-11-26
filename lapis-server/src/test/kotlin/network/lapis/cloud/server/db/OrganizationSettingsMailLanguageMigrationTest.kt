package network.lapis.cloud.server.db

import io.kotest.core.annotation.EnabledIf
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import network.lapis.cloud.server.testdb.PostgresConfigured

/**
 * Welle V1.9.332 -- `V176__organization_mail_language.sql` on the upgrade path V175 -> V176, on H2 AND on PostgreSQL: the existing settings row
 * gets `de`, the column is NOT NULL, it takes another code, and a re-run is a no-op.
 */
abstract class OrganizationSettingsMailLanguageMigrationScenarios(
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

        val rowId = "00000000-0000-0000-0000-0000000000f2"

        beforeSpec {
            h = newHarness()
            h.flyway("175").migrate()
            h.flyway(null).migrate()
        }
        afterSpec { h.close() }

        test("the existing settings row gets the default language de") {
            single("SELECT mail_language FROM organization_settings WHERE id = '$rowId'") shouldBe "de"
        }

        test("it takes another code, NULL is rejected") {
            exec("UPDATE organization_settings SET mail_language = 'pl' WHERE id = '$rowId'")
            single("SELECT mail_language FROM organization_settings WHERE id = '$rowId'") shouldBe "pl"
            (runCatching { exec("UPDATE organization_settings SET mail_language = NULL WHERE id = '$rowId'") }.isFailure) shouldBe true
            exec("UPDATE organization_settings SET mail_language = 'de' WHERE id = '$rowId'")
        }

        test("a re-run is a no-op") {
            h.flyway(null).migrate().migrationsExecuted shouldBe 0
        }
    })

class OrganizationSettingsMailLanguageMigrationTest : OrganizationSettingsMailLanguageMigrationScenarios({ H2MigrationHarness() })

@Tags("Postgres")
@EnabledIf(PostgresConfigured::class)
class OrganizationSettingsMailLanguageMigrationPostgresTest :
    OrganizationSettingsMailLanguageMigrationScenarios({ PostgresMigrationHarness() })
