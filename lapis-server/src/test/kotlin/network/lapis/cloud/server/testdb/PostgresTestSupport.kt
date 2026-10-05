package network.lapis.cloud.server.testdb

import com.zaxxer.hikari.HikariDataSource
import network.lapis.cloud.server.db.DatabaseConfig
import network.lapis.cloud.server.db.DbSessionTimeouts
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import java.net.URI
import java.security.SecureRandom
import java.sql.Connection
import java.sql.DriverManager
import java.util.concurrent.ConcurrentHashMap

/** A `jdbc:postgresql://` URL that passed [PostgresTestSupport.validateUrl]. */
internal data class ValidatedPgUrl(
    val host: String,
    val port: Int,
    val database: String,
    val sslMode: String?,
) {
    /** The same server, another database. [db] must already be regex-validated by the caller. */
    fun jdbcUrl(db: String = database): String = "jdbc:postgresql://$host:$port/$db" + (sslMode?.let { "?sslmode=$it" } ?: "")
}

/** Connection settings of the Postgres test lane (environment only -- never Gradle properties). */
internal data class PostgresTestConfig(
    val url: ValidatedPgUrl,
    val user: String,
    val password: String,
)

/**
 * Welle V1.9.37 -- infrastructure of the Postgres test lane.
 *
 * Isolation model: ONE FRESH DATABASE PER SPEC, cloned from a once-per-JVM, fully Flyway-migrated
 * template database (`CREATE DATABASE ... TEMPLATE`). Extensions, `search_path` and leftovers can
 * therefore never leak between specs, and `information_schema` only ever shows the spec's own
 * schema. All destructive statements are guarded (see [validateUrl], [checkThrowawayInstance],
 * [dropDatabase]); the lane refuses to run against anything that is not a disposable instance.
 *
 * The lane builds its OWN Hikari pool / Flyway / Exposed `Database` via the very same factory
 * functions production uses ([DatabaseConfig.buildDataSource]/[DatabaseConfig.flywayFor]); it never
 * goes through the [DatabaseConfig] JVM singleton, and must never set `LAPIS_DB_URL`.
 */
internal object PostgresTestSupport {
    private val allowedHosts = setOf("localhost", "127.0.0.1", "[::1]", "postgres")
    private val allowedSslModes = setOf("disable", "allow", "prefer", "require", "verify-ca", "verify-full")
    private val dbNameRegex = Regex("^[a-z_][a-z0-9_]{0,62}$")
    private val ownedNameRegex = Regex("^lapis_pgtest_(tpl_)?[0-9a-f]{16}$")
    private const val OWNED_PREFIX = "lapis_pgtest_"
    private val random = SecureRandom()
    private val createdNames: MutableSet<String> = ConcurrentHashMap.newKeySet()

    @Volatile private var skipReasonLogged = false
    private val lock = Any()
    private var guardsPassed = false
    private var templateName: String? = null
    private var hookInstalled = false

    /** `null` when `LAPIS_TEST_POSTGRES_URL` is unset; an invalid URL is a HARD error, never a silent skip. */
    val config: PostgresTestConfig? by lazy { loadConfig(System.getenv()) }

    internal fun loadConfig(env: Map<String, String>): PostgresTestConfig? {
        val raw = env["LAPIS_TEST_POSTGRES_URL"]?.takeIf { it.isNotBlank() } ?: return null
        check(env["LAPIS_DB_URL"] == null) {
            "LAPIS_DB_URL must NOT be set when the Postgres test lane runs (DevSeedData's production guard keys off it)"
        }
        return PostgresTestConfig(
            url = validateUrl(raw),
            user = env["LAPIS_TEST_POSTGRES_USER"] ?: "",
            password = env["LAPIS_TEST_POSTGRES_PASSWORD"] ?: "",
        )
    }

    /**
     * URL whitelist. Error messages name at most the host, never the raw value (it could carry
     * credentials in a misconfigured environment).
     */
    fun validateUrl(raw: String): ValidatedPgUrl {
        val prefix = "jdbc:postgresql://"
        require(raw.startsWith(prefix)) { "LAPIS_TEST_POSTGRES_URL must start with $prefix" }
        val rest = raw.removePrefix(prefix)
        val authority = rest.substringBefore('/').substringBefore('?')
        require(!authority.contains(',')) { "LAPIS_TEST_POSTGRES_URL: multi-host URLs are not allowed" }
        require(!authority.contains('@')) { "LAPIS_TEST_POSTGRES_URL: credentials in the URL are not allowed" }
        val uri =
            try {
                URI("postgresql://$rest")
            } catch (e: java.net.URISyntaxException) {
                throw IllegalArgumentException("LAPIS_TEST_POSTGRES_URL is not a parseable URL", e)
            }
        require(uri.userInfo == null) { "LAPIS_TEST_POSTGRES_URL: credentials in the URL are not allowed" }
        require(uri.fragment == null) { "LAPIS_TEST_POSTGRES_URL: fragments are not allowed" }
        val host = uri.host
        require(host != null && host in allowedHosts) {
            "LAPIS_TEST_POSTGRES_URL: host '${host ?: "<none>"}' is not allowed (only ${allowedHosts.sorted()})"
        }
        val database = uri.path.removePrefix("/")
        require(dbNameRegex.matches(database)) { "LAPIS_TEST_POSTGRES_URL: invalid database name" }
        var sslMode: String? = null
        uri.rawQuery?.split('&')?.filter { it.isNotEmpty() }?.forEach { pair ->
            val key = pair.substringBefore('=')
            val value = pair.substringAfter('=', "")
            require(key == "sslmode") { "LAPIS_TEST_POSTGRES_URL: query parameter '$key' is not allowed (only sslmode)" }
            require(value in allowedSslModes) { "LAPIS_TEST_POSTGRES_URL: invalid sslmode" }
            sslMode = value
        }
        return ValidatedPgUrl(host = host, port = if (uri.port == -1) 5432 else uri.port, database = database, sslMode = sslMode)
    }

    /**
     * Defense in depth: the lane only runs against a throwaway instance. Any database besides
     * `postgres`/`template0`/`template1`/the URL's own database/our own `lapis_pgtest_*` ones, or any
     * user table in the URL's database, aborts the run. There is deliberately no escape hatch.
     */
    fun checkThrowawayInstance(
        maint: Connection,
        urlDatabase: String,
    ) {
        val allowed = setOf("postgres", "template0", "template1", urlDatabase)
        maint.createStatement().use { st ->
            st.executeQuery("SELECT datname FROM pg_database").use { rs ->
                while (rs.next()) {
                    val name = rs.getString(1)
                    check(name in allowed || name.startsWith(OWNED_PREFIX)) {
                        "not a throwaway instance: unexpected database '$name' present -- refusing to run the Postgres lane"
                    }
                }
            }
            st
                .executeQuery(
                    "SELECT count(*) FROM pg_tables WHERE schemaname NOT IN ('pg_catalog','information_schema')",
                ).use { rs ->
                    rs.next()
                    check(rs.getLong(1) == 0L) {
                        "not a throwaway instance: database '$urlDatabase' already contains user tables -- refusing to run the Postgres lane"
                    }
                }
        }
    }

    fun postgresSkipReason(): String? = if (config == null) "LAPIS_TEST_POSTGRES_URL not set -- Postgres lane skipped" else null

    internal fun logSkipOnce() {
        if (!skipReasonLogged) {
            skipReasonLogged = true
            System.err.println(postgresSkipReason())
        }
    }

    private fun maintenanceConnection(cfg: PostgresTestConfig): Connection {
        val conn = DriverManager.getConnection(cfg.url.jdbcUrl(), cfg.user, cfg.password)
        conn.autoCommit = true
        return conn
    }

    private fun randomHex16(): String = ByteArray(8).also { random.nextBytes(it) }.joinToString("") { "%02x".format(it) }

    private fun requireOwnedName(name: String) {
        require(ownedNameRegex.matches(name)) { "refusing to touch database '$name': not a lane-owned name" }
    }

    private fun installHookLocked() {
        if (hookInstalled) return
        hookInstalled = true
        Runtime.getRuntime().addShutdownHook(
            Thread {
                val cfg = config ?: return@Thread
                runCatching {
                    maintenanceConnection(cfg).use { c -> createdNames.toList().forEach { dropOwned(maint = c, name = it) } }
                }
            },
        )
    }

    private fun dropOwned(
        maint: Connection,
        name: String,
    ) {
        assertDroppable(name)
        maint.createStatement().use { it.execute("DROP DATABASE IF EXISTS \"$name\" WITH (FORCE)") }
        createdNames.remove(name)
    }

    /** Name guard of every DROP: lane-owned pattern AND created by this very JVM. Checked before any connection. */
    internal fun assertDroppable(name: String) {
        requireOwnedName(name)
        require(name in createdNames) { "refusing to drop a database this JVM did not create" }
    }

    fun dropDatabase(name: String) {
        assertDroppable(name)
        val cfg = requireNotNull(config) { "Postgres lane not configured" }
        maintenanceConnection(cfg).use { dropOwned(maint = it, name = name) }
    }

    private fun newPool(
        cfg: PostgresTestConfig,
        name: String,
        size: Int,
        timeouts: DbSessionTimeouts = DbSessionTimeouts.DEFAULTS,
    ): HikariDataSource =
        DatabaseConfig.buildDataSource(
            jdbcUrl = cfg.url.jdbcUrl(name),
            username = cfg.user,
            password = cfg.password,
            poolSize = size,
            connectionTimeoutMs = 30_000L,
            poolName = "lapis-pgtest-$name",
            sessionTimeouts = timeouts,
        )

    /** Runs once per JVM: instance guard + migrated template. Returns the template name. */
    private fun ensureTemplate(cfg: PostgresTestConfig): String =
        synchronized(lock) {
            installHookLocked()
            if (!guardsPassed) {
                maintenanceConnection(cfg).use { checkThrowawayInstance(maint = it, urlDatabase = cfg.url.database) }
                guardsPassed = true
            }
            templateName?.let { return it }
            val tpl = "lapis_pgtest_tpl_${randomHex16()}"
            requireOwnedName(tpl)
            maintenanceConnection(cfg).use { c ->
                createdNames.add(tpl)
                c.createStatement().use { it.execute("CREATE DATABASE \"$tpl\"") }
            }
            newPool(cfg = cfg, name = tpl, size = 2, timeouts = DbSessionTimeouts.DISABLED).use { ds ->
                DatabaseConfig.flywayFor(ds).migrate()
            }
            // The pool is closed before any CREATE DATABASE ... TEMPLATE (no open connection to the template allowed).
            templateName = tpl
            tpl
        }

    /**
     * A fresh database for one spec. [migrated] = clone of the migrated template (fast); `false` = an
     * EMPTY database (used by the migration test, which must run the migrations itself).
     */
    fun createDatabase(
        migrated: Boolean = true,
        timeouts: DbSessionTimeouts = DbSessionTimeouts.DEFAULTS,
    ): PgSpecDatabase {
        val cfg = requireNotNull(config) { "Postgres lane not configured" }
        val tpl = ensureTemplate(cfg)
        val name = "lapis_pgtest_${randomHex16()}"
        requireOwnedName(name)
        maintenanceConnection(cfg).use { c ->
            createdNames.add(name)
            val sql = if (migrated) "CREATE DATABASE \"$name\" TEMPLATE \"$tpl\"" else "CREATE DATABASE \"$name\""
            c.createStatement().use { it.execute(sql) }
        }
        val ds = newPool(cfg = cfg, name = name, size = 10, timeouts = timeouts)
        return PgSpecDatabase(name = name, jdbcUrl = cfg.url.jdbcUrl(name), user = cfg.user, password = cfg.password, dataSource = ds)
    }
}

/** One spec's private Postgres database plus its Hikari pool and Exposed [Database]. */
class PgSpecDatabase(
    val name: String,
    val jdbcUrl: String,
    val user: String,
    val password: String,
    val dataSource: HikariDataSource,
) {
    val database: Database = Database.connect(dataSource)
    private var previousDefault: Database? = null
    private var activated = false

    /** Makes this database Exposed's default (restored by [close]). */
    fun activate() {
        previousDefault = TransactionManager.defaultDatabase
        TransactionManager.defaultDatabase = database
        activated = true
    }

    /** A raw JDBC connection (own transaction control) for lock/isolation experiments. */
    fun rawConnection(): Connection = dataSource.connection

    /**
     * Blocks until some backend of THIS database is waiting on a heavyweight lock while running a
     * statement containing [queryFragment] (case-insensitive) -- the deterministic "the other
     * transaction is now blocked behind mine" signal that replaces every `sleep`. Fails after [timeoutMillis].
     */
    fun awaitLockWaiter(
        queryFragment: String,
        timeoutMillis: Long = 15_000,
    ) {
        val deadline = System.nanoTime() + timeoutMillis * 1_000_000
        while (System.nanoTime() < deadline) {
            val waiting =
                rawConnection().use { c ->
                    c
                        .prepareStatement(
                            "SELECT count(*) FROM pg_stat_activity WHERE datname = current_database() " +
                                "AND wait_event_type = 'Lock' AND query ILIKE ?",
                        ).use { ps ->
                            ps.setString(1, "%$queryFragment%")
                            ps.executeQuery().use { rs ->
                                rs.next()
                                rs.getLong(1)
                            }
                        }
                }
            if (waiting > 0) return
            Thread.sleep(25)
        }
        error("no backend started waiting on a lock for '$queryFragment' within ${timeoutMillis}ms")
    }

    fun close() {
        try {
            if (activated) TransactionManager.defaultDatabase = previousDefault
            TransactionManager.closeAndUnregister(database)
        } finally {
            runCatching { dataSource.close() }
            PostgresTestSupport.dropDatabase(name)
        }
    }
}
