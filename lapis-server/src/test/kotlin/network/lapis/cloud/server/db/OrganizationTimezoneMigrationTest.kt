package network.lapis.cloud.server.db

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.flywaydb.core.Flyway
import java.sql.DriverManager
import java.util.UUID

/**
 * V1.9.38 -- `V67__organization_timezone.sql` on the REAL production path: a database at the state of V66 (the schema that is on
 * staging, PdV and ELB today) with its seeded `organization_settings` row is migrated forward, and the existing row must get
 * `Europe/Berlin` (today's behaviour). Also: V66 stays untouched (its checksum is frozen), and a re-run is a clean no-op.
 */
class OrganizationTimezoneMigrationTest :
    FunSpec({
        fun flyway(
            jdbcUrl: String,
            target: String?,
        ): Flyway {
            val configuration =
                Flyway
                    .configure()
                    .dataSource(jdbcUrl, "sa", "")
                    .locations("classpath:db/migration")
            if (target != null) configuration.target(target)
            return configuration.load()
        }

        fun columnExists(
            jdbcUrl: String,
            column: String,
        ): Boolean =
            DriverManager.getConnection(jdbcUrl, "sa", "").use { c ->
                c
                    .prepareStatement(
                        "SELECT COUNT(*) FROM information_schema.columns WHERE LOWER(table_name) = 'organization_settings' AND LOWER(column_name) = ?",
                    ).use { ps ->
                        ps.setString(1, column)
                        ps.executeQuery().use { rs ->
                            rs.next()
                            rs.getInt(1) > 0
                        }
                    }
            }

        test("V67 gives the existing organization row Europe/Berlin; a re-run is a no-op; V66 is the state before") {
            val jdbcUrl = "jdbc:h2:mem:org-tz-migration-${UUID.randomUUID()};MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1"
            flyway(jdbcUrl, target = "66").migrate()
            columnExists(jdbcUrl, "timezone") shouldBe false
            val rowsBefore =
                DriverManager.getConnection(jdbcUrl, "sa", "").use { c ->
                    c.createStatement().use { st ->
                        st.executeQuery("SELECT COUNT(*) FROM organization_settings").use { rs ->
                            rs.next()
                            rs.getInt(1)
                        }
                    }
                }
            rowsBefore shouldBe 1

            val result = flyway(jdbcUrl, target = null).migrate()
            (result.migrationsExecuted >= 1) shouldBe true
            columnExists(jdbcUrl, "timezone") shouldBe true
            DriverManager.getConnection(jdbcUrl, "sa", "").use { c ->
                c.createStatement().use { st ->
                    st.executeQuery("SELECT timezone FROM organization_settings").use { rs ->
                        rs.next() shouldBe true
                        rs.getString(1) shouldBe "Europe/Berlin"
                    }
                }
            }
            flyway(jdbcUrl, target = null).migrate().migrationsExecuted shouldBe 0
            flyway(jdbcUrl, target = null).validate()
        }
    })
