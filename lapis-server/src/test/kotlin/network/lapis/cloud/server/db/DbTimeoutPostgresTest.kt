package network.lapis.cloud.server.db

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.annotation.EnabledIf
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.longs.shouldBeLessThan
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import network.lapis.cloud.server.testdb.PgSpecDatabase
import network.lapis.cloud.server.testdb.PostgresConfigured
import network.lapis.cloud.server.testdb.PostgresTestSupport
import org.jetbrains.exposed.v1.exceptions.ExposedSQLException
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.sql.SQLException
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Welle V1.9.55 -- the session timeouts against a REAL PostgreSQL: they are applied per physical connection, classified by
 * SQLSTATE, recorded for the RPC sanitizer, and `SET LOCAL` relaxation never leaks into the pool.
 *
 * Every test uses its own freshly cloned database (and its own pool) and addresses it explicitly via `transaction(database)`,
 * so the very short timeouts used here cannot disturb any other spec.
 */
@Tags("Postgres")
@EnabledIf(PostgresConfigured::class)
class DbTimeoutPostgresTest :
    FunSpec({
        val dbs = mutableListOf<PgSpecDatabase>()

        fun newDb(timeouts: DbSessionTimeouts): PgSpecDatabase = PostgresTestSupport.createDatabase(timeouts = timeouts).also { dbs += it }

        afterSpec { dbs.forEach { runCatching { it.close() } } }

        fun show(
            db: PgSpecDatabase,
            variable: String,
        ): String =
            transaction(db.database) {
                var value = ""
                exec("SHOW $variable") { rs -> if (rs.next()) value = rs.getString(1) }
                value
            }

        test("the application pool applies 10s / 1min / 2min, the migration pool all zero") {
            val app = newDb(DbSessionTimeouts.DEFAULTS)
            show(app, "lock_timeout") shouldBe "10s"
            show(app, "statement_timeout") shouldBe "1min"
            show(app, "idle_in_transaction_session_timeout") shouldBe "2min"

            val cfg = requireNotNull(PostgresTestSupport.config)
            DatabaseConfig
                .buildDataSource(
                    jdbcUrl = app.jdbcUrl,
                    username = cfg.user,
                    password = cfg.password,
                    poolSize = 2,
                    connectionTimeoutMs = 10_000,
                    poolName = "dbtimeout-migration-probe",
                    sessionTimeouts = DbSessionTimeouts.DISABLED,
                ).use { ds ->
                    ds.connection.use { c ->
                        listOf("lock_timeout", "statement_timeout", "idle_in_transaction_session_timeout").forEach { variable ->
                            c.createStatement().use { st ->
                                st.executeQuery("SHOW $variable").use { rs ->
                                    rs.next()
                                    rs.getString(1) shouldBe "0"
                                }
                            }
                        }
                    }
                }
        }

        test("a value of 0 renders an explicit SET = 0 (off) on every pooled connection") {
            val d = newDb(DbSessionTimeouts.DISABLED)
            show(d, "lock_timeout") shouldBe "0"
            show(d, "statement_timeout") shouldBe "0"
            show(d, "idle_in_transaction_session_timeout") shouldBe "0"
        }

        test("a statement timeout is SQLSTATE 57014, classified STATEMENT_TIMEOUT and recorded for the sanitizer") {
            val d = newDb(DbSessionTimeouts(lockTimeoutMs = 300, statementTimeoutMs = 1_500, idleInTransactionTimeoutMs = 0))
            val holder = DbFailureHolder()
            val started = System.nanoTime()
            val failure =
                runBlocking {
                    withContext(DbFailureContext(holder)) {
                        shouldThrow<ExposedSQLException> {
                            transaction(d.database) {
                                maxAttempts = 1
                                // Server-side load (no synchronization sleep): cancelled by statement_timeout.
                                exec("SELECT pg_sleep(30)")
                            }
                        }
                    }
                }
            val elapsedMs = (System.nanoTime() - started) / 1_000_000
            failure.sqlState shouldBe "57014"
            failure.dbFailureKind() shouldBe DbFailureKind.STATEMENT_TIMEOUT
            holder.kind shouldBe DbFailureKind.STATEMENT_TIMEOUT
            holder.sqlState shouldBe "57014"
            elapsedMs shouldBeLessThan 15_000L
        }

        test("a lock timeout is SQLSTATE 55P03 and recorded; the pool stays usable afterwards") {
            val d = newDb(DbSessionTimeouts(lockTimeoutMs = 300, statementTimeoutMs = 5_000, idleInTransactionTimeoutMs = 0))
            val holderConn = d.rawConnection()
            holderConn.autoCommit = false
            try {
                holderConn.createStatement().use { it.execute("UPDATE organization_settings SET payment_gateway_enabled = false") }
                val holder = DbFailureHolder()
                val failure =
                    runBlocking {
                        withContext(DbFailureContext(holder)) {
                            shouldThrow<ExposedSQLException> {
                                transaction(d.database) {
                                    maxAttempts = 1
                                    exec("UPDATE organization_settings SET payment_gateway_enabled = false")
                                }
                            }
                        }
                    }
                failure.sqlState shouldBe "55P03"
                failure.dbFailureKind() shouldBe DbFailureKind.LOCK_TIMEOUT
                holder.kind shouldBe DbFailureKind.LOCK_TIMEOUT
            } finally {
                holderConn.rollback()
                holderConn.close()
            }
            show(d, "lock_timeout") shouldBe "300ms"
        }

        test("an idle-in-transaction timeout kills the backend; the next statement fails and the pool is usable again") {
            val d = newDb(DbSessionTimeouts(lockTimeoutMs = 300, statementTimeoutMs = 5_000, idleInTransactionTimeoutMs = 600))
            val conn = d.rawConnection()
            conn.autoCommit = false
            val pid =
                conn.createStatement().use { st ->
                    st.executeQuery("SELECT pg_backend_pid()").use { rs ->
                        rs.next()
                        rs.getInt(1)
                    }
                }
            // Poll (bounded) from a second connection until the server has terminated the idle backend.
            val deadline = System.nanoTime() + 15_000L * 1_000_000
            var gone = false
            while (!gone && System.nanoTime() < deadline) {
                gone =
                    d.rawConnection().use { c ->
                        c.prepareStatement("SELECT count(*) FROM pg_stat_activity WHERE pid = ?").use { ps ->
                            ps.setInt(1, pid)
                            ps.executeQuery().use { rs ->
                                rs.next()
                                rs.getLong(1) == 0L
                            }
                        }
                    }
                if (!gone) Thread.sleep(50)
            }
            gone shouldBe true
            val failure = shouldThrow<SQLException> { conn.createStatement().use { it.execute("SELECT 1") } }
            // pgjdbc surfaces the server's FATAL (25P03) when it is read; a closed-socket state is the only acceptable alternative.
            val kind = failure.dbFailureKind()
            (kind == DbFailureKind.IDLE_IN_TX_TIMEOUT || failure.sqlState?.startsWith("08") == true) shouldBe true
            runCatching { conn.close() }
            // Hikari evicts the dead connection; the pool keeps serving.
            show(d, "idle_in_transaction_session_timeout") shouldBe "600ms"
        }

        test("relaxSessionTimeouts is transaction-local: after commit every pooled connection shows the configured values again") {
            val d = newDb(DbSessionTimeouts(lockTimeoutMs = 300, statementTimeoutMs = 1_500, idleInTransactionTimeoutMs = 2_000))
            val threads = 10
            val pool = Executors.newFixedThreadPool(threads)
            try {
                // One transaction per thread, all open at the same time -> ten DISTINCT pooled connections are relaxed.
                val relaxedBarrier = CyclicBarrier(threads)
                val relaxed =
                    (1..threads)
                        .map {
                            pool.submit<Pair<String, String>> {
                                transaction(d.database) {
                                    relaxSessionTimeouts(statementTimeoutMs = 0, idleInTransactionTimeoutMs = 0)
                                    relaxedBarrier.await(30, TimeUnit.SECONDS)
                                    var stmt = ""
                                    var idle = ""
                                    exec("SHOW statement_timeout") { rs -> if (rs.next()) stmt = rs.getString(1) }
                                    exec("SHOW idle_in_transaction_session_timeout") { rs -> if (rs.next()) idle = rs.getString(1) }
                                    stmt to idle
                                }
                            }
                        }.map { it.get(60, TimeUnit.SECONDS) }
                relaxed.forEach { it shouldBe ("0" to "0") }

                val afterBarrier = CyclicBarrier(threads)
                val after =
                    (1..threads)
                        .map {
                            pool.submit<Triple<String, String, String>> {
                                transaction(d.database) {
                                    afterBarrier.await(30, TimeUnit.SECONDS)
                                    var lock = ""
                                    var stmt = ""
                                    var idle = ""
                                    exec("SHOW lock_timeout") { rs -> if (rs.next()) lock = rs.getString(1) }
                                    exec("SHOW statement_timeout") { rs -> if (rs.next()) stmt = rs.getString(1) }
                                    exec("SHOW idle_in_transaction_session_timeout") { rs -> if (rs.next()) idle = rs.getString(1) }
                                    Triple(lock, stmt, idle)
                                }
                            }
                        }.map { it.get(60, TimeUnit.SECONDS) }
                after.forEach { it shouldBe Triple("300ms", "1500ms", "2s") }
            } finally {
                pool.shutdownNow()
            }
        }
    })
