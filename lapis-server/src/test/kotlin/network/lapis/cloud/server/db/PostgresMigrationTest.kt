package network.lapis.cloud.server.db

import io.kotest.core.annotation.EnabledIf
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import io.kotest.matchers.shouldBe
import network.lapis.cloud.server.ai.retrieval.PostgresFullTextIndexInitializer
import network.lapis.cloud.server.testdb.PgSpecDatabase
import network.lapis.cloud.server.testdb.PostgresConfigured
import network.lapis.cloud.server.testdb.PostgresTestSupport
import org.flywaydb.core.api.MigrationVersion
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.io.File
import java.util.jar.JarFile

/**
 * Welle V1.9.37 (Postgres test lane) -- the Flyway chain against a REAL, EMPTY PostgreSQL database
 * (deliberately not cloned from the lane's template: this test must run the migrations itself).
 * Until now every migration had only ever been applied to H2 in the test suite; Postgres saw it
 * first in production.
 */
@Tags("Postgres")
@EnabledIf(PostgresConfigured::class)
class PostgresMigrationTest :
    FunSpec({
        lateinit var pg: PgSpecDatabase
        val flyway by lazy { DatabaseConfig.flywayFor(pg.dataSource) }

        fun migrationFileVersions(): List<Int> {
            val url =
                requireNotNull(Thread.currentThread().contextClassLoader.getResource("db/migration")) { "db/migration not on classpath" }
            val names =
                if (url.protocol == "file") {
                    File(url.toURI()).list().orEmpty().toList()
                } else {
                    val jarPath = url.path.substringAfter("file:").substringBefore("!")
                    JarFile(jarPath).use { jar ->
                        jar
                            .entries()
                            .asSequence()
                            .map { it.name }
                            .filter { it.startsWith("db/migration/") }
                            .map { it.substringAfterLast('/') }
                            .toList()
                    }
                }
            return names.filter { it.endsWith(".sql") }.map { it.removePrefix("V").substringBefore("__").toInt() }.sorted()
        }

        // V1.9.55: the spec's own pool carries no session timeouts; the migration itself runs through the production
        // dedicated migration pool (DatabaseConfig.migrateWithDedicatedPool), which is timeout-free by construction.
        beforeSpec { pg = PostgresTestSupport.createDatabase(migrated = false, timeouts = DbSessionTimeouts.DISABLED) }
        afterSpec { pg.close() }

        var executed = -1

        test("migrate() applies every migration file to an empty PostgreSQL database") {
            val files = migrationFileVersions()
            files.size shouldBeGreaterThanOrEqual 66
            flyway.info().applied().size shouldBe 0
            // V1.9.55: the production path -- a dedicated pool with all session timeouts disabled.
            DatabaseConfig.migrateWithDedicatedPool(jdbcUrl = pg.jdbcUrl, username = pg.user, password = pg.password)
            executed = flyway.info().applied().size
            executed shouldBe files.size
        }

        test("the schema version is the highest migration file version, nothing pending") {
            val highest = migrationFileVersions().max()
            flyway
                .info()
                .current()
                .version shouldBe MigrationVersion.fromVersion(highest.toString())
            flyway
                .info()
                .pending()
                .toList()
                .shouldBeEmpty()
        }

        test("validate() reports a clean, error-free chain") {
            val validation = flyway.validateWithResult()
            validation.validationSuccessful shouldBe true
            validation.invalidMigrations.orEmpty().shouldBeEmpty()
        }

        test("a second migrate() is a no-op (idempotence)") {
            val again = flyway.migrate()
            again.migrationsExecuted shouldBe 0
            flyway
                .info()
                .pending()
                .toList()
                .shouldBeEmpty()
        }

        test("repair() followed by validate() stays green (the operator flywayRepair task on Postgres)") {
            flyway.repair()
            flyway.validateWithResult().validationSuccessful shouldBe true
        }

        test("PostgresFullTextIndexInitializer creates the extensions and the GIN full-text index") {
            pg.activate()
            PostgresFullTextIndexInitializer.ensureIndexes()
            val extensions = mutableSetOf<String>()
            val indexes = mutableSetOf<String>()
            transaction {
                exec("SELECT extname FROM pg_extension") { rs -> while (rs.next()) extensions += rs.getString(1) }
                exec("SELECT indexname FROM pg_indexes WHERE tablename = 'ai_knowledge_chunk'") { rs ->
                    while (rs.next()) indexes += rs.getString(1)
                }
            }
            // The initializer swallows failures by design, so the RESULT is what is asserted here.
            extensions shouldContain "unaccent"
            extensions shouldContain "pg_trgm"
            indexes shouldContain "idx_ai_knowledge_chunk_fts"
        }

        test("server version is logged and at least 17") {
            var version = 0
            pg.rawConnection().use { c ->
                c.createStatement().use { st ->
                    st.executeQuery("SHOW server_version_num").use { rs ->
                        rs.next()
                        version = rs.getInt(1)
                    }
                }
            }
            println("Postgres test lane: server_version_num=$version")
            version shouldBeGreaterThanOrEqual 170000
        }
    })
