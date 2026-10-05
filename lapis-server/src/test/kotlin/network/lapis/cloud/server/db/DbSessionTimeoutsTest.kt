package network.lapis.cloud.server.db

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain

class DbSessionTimeoutsTest :
    FunSpec({
        test("defaults apply when no variable is set") {
            DbSessionTimeouts.fromEnv(emptyMap()) shouldBe
                DbSessionTimeouts(lockTimeoutMs = 10_000, statementTimeoutMs = 60_000, idleInTransactionTimeoutMs = 120_000)
        }

        test("each variable overrides its own value only") {
            val t = DbSessionTimeouts.fromEnv(mapOf(DbSessionTimeouts.ENV_LOCK to "5000"))
            t.lockTimeoutMs shouldBe 5_000
            t.statementTimeoutMs shouldBe 60_000
            t.idleInTransactionTimeoutMs shouldBe 120_000
        }

        test("0 means off and is rendered as an explicit SET = 0") {
            val t =
                DbSessionTimeouts.fromEnv(
                    mapOf(
                        DbSessionTimeouts.ENV_LOCK to "0",
                        DbSessionTimeouts.ENV_STATEMENT to "0",
                        DbSessionTimeouts.ENV_IDLE_TX to "0",
                    ),
                )
            t.toPostgresInitSql() shouldBe
                "SET lock_timeout = 0; SET statement_timeout = 0; SET idle_in_transaction_session_timeout = 0"
        }

        test("init SQL is rendered exactly from the integers") {
            DbSessionTimeouts.DEFAULTS.toPostgresInitSql() shouldBe
                "SET lock_timeout = 10000; SET statement_timeout = 60000; SET idle_in_transaction_session_timeout = 120000"
        }

        listOf("", "  ", "abc", "-1", "1.5", "99999999999999", "1; DROP TABLE member").forEach { bad ->
            test("invalid value '$bad' fails fast naming only the variable") {
                val e = shouldThrow<IllegalStateException> { DbSessionTimeouts.fromEnv(mapOf(DbSessionTimeouts.ENV_STATEMENT to bad)) }
                e.message!! shouldContain DbSessionTimeouts.ENV_STATEMENT
                if (bad.isNotBlank()) e.message!! shouldNotContain bad
            }
        }

        test("statement timeout must exceed lock timeout") {
            shouldThrow<IllegalStateException> {
                DbSessionTimeouts.fromEnv(
                    mapOf(DbSessionTimeouts.ENV_LOCK to "5000", DbSessionTimeouts.ENV_STATEMENT to "5000"),
                )
            }
            shouldThrow<IllegalArgumentException> {
                DbSessionTimeouts(
                    lockTimeoutMs = 10,
                    statementTimeoutMs = 5,
                    idleInTransactionTimeoutMs = 0,
                )
            }
        }

        test("a pool for an H2 URL gets no init SQL, a Postgres URL does not need a live server to be configured") {
            DatabaseConfig
                .buildDataSource(
                    jdbcUrl = "jdbc:h2:mem:timeouts-${System.nanoTime()};DB_CLOSE_DELAY=-1;MODE=PostgreSQL",
                    username = "sa",
                    password = "",
                    poolSize = 1,
                    connectionTimeoutMs = 5_000,
                    poolName = "timeouts-h2",
                    sessionTimeouts = DbSessionTimeouts.DEFAULTS,
                ).use { ds ->
                    ds.connectionInitSql shouldBe null
                    ds.connection.use { it.isValid(2) shouldBe true }
                }
        }
    })
