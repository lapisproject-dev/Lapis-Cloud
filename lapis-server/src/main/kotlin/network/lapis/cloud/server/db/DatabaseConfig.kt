package network.lapis.cloud.server.db

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.flywaydb.core.Flyway
import org.jetbrains.exposed.v1.jdbc.Database
import java.util.UUID

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
        val isPostgres = jdbcUrl.startsWith("jdbc:postgresql")
        val driverClassName = if (isPostgres) "org.postgresql.Driver" else "org.h2.Driver"
        val poolSize = System.getenv("LAPIS_DB_POOL_SIZE")?.toIntOrNull() ?: 10
        // Security-Audit-Fund S-A1 (2026-08-18, Welle V1.1.2 "Kommentarbaum, Boosts, rekursive
        // Gesamtgewichtung"): the real fix for the pool-exhaustion risk this fund described (two
        // concurrent SocialNetworkService writes against the same large thread's root post, one
        // blocked on the other's `SELECT ... FOR UPDATE` row lock) is SocialNetworkService's own
        // change -- the expensive subtree aggregation moved out of the write transaction, see
        // `SocialNetworkService.loadPostAfterCommit` KDoc -- NOT this line.
        //
        // Corrected (Review Runde 2 / Security-Audit Runde 2, 2026-08-18, Fund N-3): an earlier
        // revision of this comment overstated what `connectionTimeout` does. HikariConfig's field
        // default is already 30s regardless of whether this codebase sets it -- so this line is
        // behaviorally a no-op except for making it `LAPIS_DB_CONNECTION_TIMEOUT_MS`-configurable.
        // More importantly, `connectionTimeout` bounds how long a caller waits to ACQUIRE a
        // connection from the pool when none are free -- it does NOT bound how long an
        // already-acquired connection may be held waiting on a database-level row lock, so it was
        // never a backstop against the S-A1 scenario itself. A genuine backstop against a stuck
        // `FOR UPDATE` wait would be a Postgres-side `lock_timeout`/`statement_timeout`, which this
        // codebase does not yet set anywhere -- noted as a follow-up, not yet actioned.
        val connectionTimeoutMs = System.getenv("LAPIS_DB_CONNECTION_TIMEOUT_MS")?.toLongOrNull() ?: 30_000L

        val hikariConfig =
            HikariConfig().apply {
                this.jdbcUrl = jdbcUrl
                this.username = username
                this.password = password
                this.driverClassName = driverClassName
                this.maximumPoolSize = poolSize
                this.connectionTimeout = connectionTimeoutMs
                this.poolName = "lapis-cloud-db-pool"
            }
        val dataSource = HikariDataSource(hikariConfig)

        Flyway
            .configure()
            .dataSource(dataSource)
            .locations("classpath:db/migration")
            .load()
            .migrate()

        return Database.connect(dataSource)
    }
}
