package network.lapis.cloud.server.db

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.github.oshai.kotlinlogging.KotlinLogging
import org.flywaydb.core.Flyway
import org.jetbrains.exposed.v1.jdbc.Database
import java.util.UUID
import javax.sql.DataSource

/**
 * Wires HikariCP + Flyway + Exposed together.
 *
 * Defaults to an in-memory H2 database (`MODE=PostgreSQL` for SQL-dialect parity with prod;
 * `DATABASE_TO_LOWER=TRUE` so H2 folds unquoted identifiers to lowercase like Postgres does —
 * without it, the unquoted `CREATE TABLE member (...)` from the Flyway migrations ends up
 * stored as `MEMBER`, while Exposed's generated queries quote the lowercase `"member"` from the
 * Kotlin `Table` definitions, and H2's quoted-identifier lookup is case-sensitive) whenever
 * `LAPIS_DB_URL` is not set, so local `./gradlew run` and `./gradlew test` work with zero
 * external setup. Point `LAPIS_DB_URL` at a real `jdbc:postgresql://...` URL (plus
 * `LAPIS_DB_USER`/`LAPIS_DB_PASSWORD`) for a real deployment — credentials are read from
 * environment variables only, never hardcoded or logged.
 *
 * [connect] is idempotent: the underlying [Database] (and, for the H2 default, the specific
 * in-memory instance name) is created once per JVM and reused on every subsequent call, and
 * Flyway's own schema history table makes repeated `migrate()` calls no-ops once the schema is
 * current.
 */
object DatabaseConfig {
    private val logger = KotlinLogging.logger {}

    // Unique per JVM run so concurrent test JVMs (or a stray leftover process) never share
    // in-memory state; stable across repeated connect() calls within the same run.
    private val inMemoryDatabaseName = "lapis-${UUID.randomUUID()}"

    private val database: Database by lazy { buildAndMigrate() }

    fun connect(): Database = database

    private fun buildAndMigrate(): Database {
        val jdbcUrl =
            System.getenv("LAPIS_DB_URL")
                ?: "jdbc:h2:mem:$inMemoryDatabaseName;DB_CLOSE_DELAY=-1;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE"
        val username = System.getenv("LAPIS_DB_USER") ?: "sa"
        val password = System.getenv("LAPIS_DB_PASSWORD") ?: ""
        val poolSize = System.getenv("LAPIS_DB_POOL_SIZE")?.toIntOrNull() ?: 10
        // `connectionTimeout` bounds how long a caller waits to ACQUIRE a pooled connection; it does NOT
        // bound a wait on a database-level row lock. That backstop is the Postgres-side session timeouts
        // introduced in V1.9.55 (see DbSessionTimeouts and docs/architecture/database-timeouts-and-retries.adoc).
        val connectionTimeoutMs = System.getenv("LAPIS_DB_CONNECTION_TIMEOUT_MS")?.toLongOrNull() ?: 30_000L

        // Invalid timeout variables abort startup (fail fast) -- see DbSessionTimeouts.fromEnv.
        val timeouts = DbSessionTimeouts.fromEnv()

        // Migrations run on their OWN short-lived pool WITHOUT session timeouts (a long index build must
        // not be cancelled), so no session-wide SET can ever leak into the application pool.
        migrateWithDedicatedPool(jdbcUrl = jdbcUrl, username = username, password = password, connectionTimeoutMs = connectionTimeoutMs)

        val dataSource =
            buildDataSource(
                jdbcUrl = jdbcUrl,
                username = username,
                password = password,
                poolSize = poolSize,
                connectionTimeoutMs = connectionTimeoutMs,
                poolName = "lapis-cloud-db-pool",
                sessionTimeouts = timeouts,
            )
        logger.info {
            "DB session timeouts (ms): lock=${timeouts.lockTimeoutMs} statement=${timeouts.statementTimeoutMs} " +
                "idleInTransaction=${timeouts.idleInTransactionTimeoutMs}"
        }

        return Database.connect(dataSource)
    }

    /**
     * Builds the Hikari pool exactly the way production does (driver selection by URL prefix, pool
     * size, acquisition timeout). `internal` so the Postgres test lane (V1.9.37) reuses the very same
     * wiring instead of a parallel, drift-prone copy.
     */
    internal fun buildDataSource(
        jdbcUrl: String,
        username: String,
        password: String,
        poolSize: Int,
        connectionTimeoutMs: Long,
        poolName: String,
        sessionTimeouts: DbSessionTimeouts,
    ): HikariDataSource {
        val driverClassName = if (jdbcUrl.startsWith("jdbc:postgresql")) "org.postgresql.Driver" else "org.h2.Driver"
        val hikariConfig =
            HikariConfig().apply {
                this.jdbcUrl = jdbcUrl
                this.username = username
                this.password = password
                this.driverClassName = driverClassName
                this.maximumPoolSize = poolSize
                this.connectionTimeout = connectionTimeoutMs
                this.poolName = poolName
                // Postgres-only: H2 has no equivalent session variables.
                if (jdbcUrl.startsWith("jdbc:postgresql")) {
                    this.connectionInitSql = sessionTimeouts.toPostgresInitSql()
                }
                // Records the SQLSTATE of failures for the RPC error sanitizer; always CONTINUE_EVICT.
                this.exceptionOverride = DbFailureRecordingOverride
            }
        return HikariDataSource(hikariConfig)
    }

    /** Runs Flyway on a dedicated, timeout-free, short-lived pool. */
    internal fun migrateWithDedicatedPool(
        jdbcUrl: String,
        username: String,
        password: String,
        connectionTimeoutMs: Long = 30_000L,
    ) {
        buildDataSource(
            jdbcUrl = jdbcUrl,
            username = username,
            password = password,
            poolSize = 2,
            connectionTimeoutMs = connectionTimeoutMs,
            poolName = "lapis-cloud-db-migrate",
            sessionTimeouts = DbSessionTimeouts.DISABLED,
        ).use { flywayFor(it).migrate() }
    }

    /** The Flyway configuration used in production (classpath migrations, nothing else). */
    internal fun flywayFor(dataSource: DataSource): Flyway =
        Flyway
            .configure()
            .dataSource(dataSource)
            .locations("classpath:db/migration")
            .load()
}
