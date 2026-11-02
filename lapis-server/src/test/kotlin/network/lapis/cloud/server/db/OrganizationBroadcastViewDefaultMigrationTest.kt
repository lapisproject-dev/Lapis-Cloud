package network.lapis.cloud.server.db

import io.kotest.core.annotation.EnabledIf
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import network.lapis.cloud.server.testdb.PostgresConfigured

/**
 * Welle V1.9.262 -- `V138__organization_broadcast_view_default.sql` on the upgrade path V137 -> V138, on H2 AND on PostgreSQL: the existing
 * settings row gets RASTER, only RASTER and SPEAKER are accepted, NULL is rejected, and a re-run is a no-op.
 */
abstract class OrganizationBroadcastViewDefaultMigrationScenarios(
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

        fun rejected(sql: String): Boolean = runCatching { exec(sql) }.isFailure

        val rowId = "00000000-0000-0000-0000-0000000000f2"

        beforeSpec {
            h = newHarness()
            h.flyway("137").migrate()
            h.flyway(null).migrate()
        }
        afterSpec { h.close() }

        test("the existing settings row gets RASTER; SPEAKER is accepted; GRID and NULL are rejected") {
            single("SELECT conference_broadcast_view_default FROM organization_settings WHERE id = '$rowId'") shouldBe "RASTER"
            exec("UPDATE organization_settings SET conference_broadcast_view_default = 'SPEAKER' WHERE id = '$rowId'")
            single("SELECT conference_broadcast_view_default FROM organization_settings WHERE id = '$rowId'") shouldBe "SPEAKER"
            rejected("UPDATE organization_settings SET conference_broadcast_view_default = 'GRID' WHERE id = '$rowId'") shouldBe true
            rejected("UPDATE organization_settings SET conference_broadcast_view_default = NULL WHERE id = '$rowId'") shouldBe true
            exec("UPDATE organization_settings SET conference_broadcast_view_default = 'RASTER' WHERE id = '$rowId'")
        }

        test("a re-run is a no-op") {
            h.flyway(null).migrate().migrationsExecuted shouldBe 0
        }
    })

class OrganizationBroadcastViewDefaultMigrationTest : OrganizationBroadcastViewDefaultMigrationScenarios({ H2MigrationHarness() })

@Tags("Postgres")
@EnabledIf(PostgresConfigured::class)
class OrganizationBroadcastViewDefaultMigrationPostgresTest :
    OrganizationBroadcastViewDefaultMigrationScenarios({ PostgresMigrationHarness() })
