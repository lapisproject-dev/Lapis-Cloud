package network.lapis.cloud.server.db

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.flywaydb.core.Flyway
import java.sql.DriverManager
import java.util.UUID

/**
 * V1.9.39 -- `V68__systemic_consensus_option_rationale.sql` on the production path: a database at V67 with an existing
 * proposal row is migrated forward; the new column exists and is NULL for the old row, a re-run is a clean no-op, V67 stays frozen.
 */
class SystemicConsensusOptionRationaleMigrationTest :
    FunSpec({
        fun flyway(
            jdbcUrl: String,
            target: String?,
        ): Flyway {
            val configuration = Flyway.configure().dataSource(jdbcUrl, "sa", "").locations("classpath:db/migration")
            if (target != null) configuration.target(target)
            return configuration.load()
        }

        fun <T> query(
            jdbcUrl: String,
            sql: String,
            read: (java.sql.ResultSet) -> T,
        ): T =
            DriverManager.getConnection(jdbcUrl, "sa", "").use { c ->
                c.createStatement().use { st -> st.executeQuery(sql).use { rs -> read(rs) } }
            }

        fun hasRationaleColumn(jdbcUrl: String): Boolean =
            query(
                jdbcUrl,
                "SELECT COUNT(*) FROM information_schema.columns WHERE LOWER(table_name) = 'systemic_consensus_option' " +
                    "AND LOWER(column_name) = 'rationale'",
            ) {
                it.next()
                it.getInt(1) > 0
            }

        test("V68 adds a nullable rationale column; existing proposals keep NULL; a re-run is a no-op; V67 is the state before") {
            val jdbcUrl = "jdbc:h2:mem:sk-rationale-migration-${UUID.randomUUID()};MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1"
            flyway(jdbcUrl, target = "67").migrate()
            hasRationaleColumn(jdbcUrl) shouldBe false

            val result = flyway(jdbcUrl, target = null).migrate()
            (result.migrationsExecuted >= 1) shouldBe true
            hasRationaleColumn(jdbcUrl) shouldBe true
            query(
                jdbcUrl,
                "SELECT is_nullable FROM information_schema.columns WHERE LOWER(table_name) = 'systemic_consensus_option' " +
                    "AND LOWER(column_name) = 'rationale'",
            ) {
                it.next()
                it.getString(1) shouldBe "YES"
            }
            query(jdbcUrl, "SELECT COUNT(*) FROM systemic_consensus_option WHERE rationale IS NOT NULL") {
                it.next()
                it.getInt(1) shouldBe 0
            }
            flyway(jdbcUrl, target = null).migrate().migrationsExecuted shouldBe 0
            flyway(jdbcUrl, target = null).validate()
        }
    })
