package network.lapis.cloud.server.testdb

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain

/**
 * Welle V1.9.37 -- the URL whitelist and the DROP guard of the Postgres lane. Needs no database, so it
 * runs in the normal `test` task (and therefore also where Postgres is unavailable).
 */
class PostgresTestSupportUrlValidationTest :
    FunSpec({
        test("allowed hosts are accepted, port and database are parsed") {
            val url = PostgresTestSupport.validateUrl("jdbc:postgresql://localhost:55432/postgres")
            url shouldBe ValidatedPgUrl(host = "localhost", port = 55432, database = "postgres", sslMode = null)
            PostgresTestSupport.validateUrl("jdbc:postgresql://127.0.0.1/lapis_test").port shouldBe 5432
            PostgresTestSupport.validateUrl("jdbc:postgresql://[::1]:5433/x").host shouldBe "[::1]"
            PostgresTestSupport.validateUrl("jdbc:postgresql://postgres:5432/lapis_test?sslmode=disable").sslMode shouldBe "disable"
        }

        test("jdbcUrl rebuilds the URL for another database on the same server") {
            PostgresTestSupport
                .validateUrl("jdbc:postgresql://localhost:55432/postgres?sslmode=require")
                .jdbcUrl("lapis_pgtest_abc") shouldBe "jdbc:postgresql://localhost:55432/lapis_pgtest_abc?sslmode=require"
        }

        listOf(
            "jdbc:postgresql://10.0.0.5:5432/postgres",
            "jdbc:postgresql://db.example.com/postgres",
            "jdbc:postgresql://pdv2/postgres",
            "jdbc:postgresql://localhost.evil.example/postgres",
            "jdbc:postgresql://localhost:5432,evil.example:5432/postgres",
            "jdbc:postgresql://user:secret@localhost:5432/postgres",
            "jdbc:postgresql://localhost:5432/postgres?socketFactory=evil.Factory",
            "jdbc:postgresql://localhost:5432/postgres?sslfactory=evil.Factory",
            "jdbc:postgresql://localhost:5432/postgres?options=-c%20search_path%3Dx",
            "jdbc:postgresql://localhost:5432/postgres?loggerFile=/tmp/x",
            "jdbc:postgresql://localhost:5432/postgres?sslmode=bogus",
            "jdbc:postgresql://localhost:5432/Bad-Name",
            "jdbc:postgresql://localhost:5432/",
            "jdbc:postgresql://localhost:5432/postgres#frag",
            "jdbc:h2:mem:x",
            "postgresql://localhost/postgres",
        ).forEach { bad ->
            test("rejected: $bad") {
                shouldThrow<IllegalArgumentException> { PostgresTestSupport.validateUrl(bad) }
            }
        }

        test("error messages never echo credentials from the raw value") {
            val e =
                shouldThrow<IllegalArgumentException> {
                    PostgresTestSupport.validateUrl(
                        "jdbc:postgresql://user:hunter2@localhost/postgres",
                    )
                }
            e.message.orEmpty() shouldNotContain "hunter2"
            val e2 =
                shouldThrow<IllegalArgumentException> {
                    PostgresTestSupport.validateUrl(
                        "jdbc:postgresql://evil.example/postgres?password=hunter2",
                    )
                }
            e2.message.orEmpty() shouldNotContain "hunter2"
        }

        test("an unset URL means 'lane not configured', an invalid one is a hard error") {
            PostgresTestSupport.loadConfig(emptyMap()) shouldBe null
            PostgresTestSupport.loadConfig(mapOf("LAPIS_TEST_POSTGRES_URL" to "  ")) shouldBe null
            shouldThrow<IllegalArgumentException> {
                PostgresTestSupport.loadConfig(mapOf("LAPIS_TEST_POSTGRES_URL" to "jdbc:postgresql://10.0.0.5/postgres"))
            }
        }

        test("LAPIS_DB_URL must not be set together with the lane URL") {
            shouldThrow<IllegalStateException> {
                PostgresTestSupport.loadConfig(
                    mapOf(
                        "LAPIS_TEST_POSTGRES_URL" to "jdbc:postgresql://localhost/postgres",
                        "LAPIS_DB_URL" to "jdbc:postgresql://localhost/prod",
                    ),
                )
            }
        }

        test("the DROP guard refuses foreign and never-created names before touching any connection") {
            shouldThrow<IllegalArgumentException> { PostgresTestSupport.assertDroppable("postgres") }
            shouldThrow<IllegalArgumentException> { PostgresTestSupport.assertDroppable("lapis_prod") }
            shouldThrow<IllegalArgumentException> { PostgresTestSupport.assertDroppable("lapis_pgtest_tpl_x") }
            shouldThrow<IllegalArgumentException> { PostgresTestSupport.assertDroppable("lapis_pgtest_0123456789abcdef") }
            shouldThrow<IllegalArgumentException> { PostgresTestSupport.dropDatabase("lapis_pgtest_0123456789abcdef") }
        }
    })
