package network.lapis.cloud.server.db

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.flywaydb.core.Flyway
import org.jetbrains.exposed.v1.jdbc.Database
import java.util.UUID

/**
 * A private, freshly migrated in-memory H2 database (unique name per call, so JVM forks and specs never share state).
 *
 * The staging seed only runs against an EMPTY `member` table, so its tests need their own database: the shared H2 of the test JVM may
 * already hold members, which would turn the seed into the no-op it is designed to be. Wired exactly like `DatabaseConfig.buildAndMigrate`
 * (Flyway + Exposed), just against a private URL.
 */
internal object IsolatedH2Database {
    fun create(): Database {
        val jdbcUrl = "jdbc:h2:mem:staging-seed-${UUID.randomUUID()};DB_CLOSE_DELAY=-1;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE"
        val dataSource =
            HikariDataSource(
                HikariConfig().apply {
                    this.jdbcUrl = jdbcUrl
                    this.username = "sa"
                    this.password = ""
                    this.driverClassName = "org.h2.Driver"
                    this.maximumPoolSize = 4
                },
            )
        Flyway
            .configure()
            .dataSource(dataSource)
            .locations("classpath:db/migration")
            .load()
            .migrate()
        return Database.connect(dataSource)
    }
}
