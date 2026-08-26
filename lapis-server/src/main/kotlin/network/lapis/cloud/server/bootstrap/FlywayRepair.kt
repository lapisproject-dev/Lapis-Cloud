package network.lapis.cloud.server.bootstrap

import io.github.oshai.kotlinlogging.KotlinLogging
import org.flywaydb.core.Flyway
import org.postgresql.ds.PGSimpleDataSource

private val logger = KotlinLogging.logger {}

/**
 * One-time, operator-run CLI that runs Flyway's own `repair()` against a target database --
 * recomputes the checksum of every migration script on the classpath and rewrites the stored
 * checksum in `flyway_schema_history` to match, without re-running anything. Exists because every
 * in-place edit of `V1__baseline.sql` this repo's own convention makes (see that file's own header
 * comment "why editing an already-applied migration in place is safe here") changes its checksum,
 * and [network.lapis.cloud.server.db.DatabaseConfig]'s `Flyway.configure().load().migrate()` runs
 * with the default `validateOnMigrate = true` -- `migrate()` refuses to run AT ALL on an already-
 * migrated database whose stored `V1` checksum no longer matches the file on disk, even though the
 * actual NEW migration (e.g. this wave's `V9__dunning.sql`) is a genuinely untouched, never-applied
 * file. `repair()` must run BEFORE the next `docker compose up -d --build lapis-server` (i.e.
 * before [network.lapis.cloud.server.db.DatabaseConfig]'s own `migrate()` call ever executes) --
 * see `CHANGELOG.md`'s many "OPERATOR NOTE" entries for the exact recurring pattern.
 *
 * **Deliberately does NOT reuse [network.lapis.cloud.server.db.DatabaseConfig.connect]** -- that
 * function's own `buildAndMigrate()` calls `.migrate()` internally, which is precisely the call
 * this tool exists to unblock; calling it first would fail with the very checksum-mismatch error
 * this tool is meant to fix. This file builds its own minimal, unpooled `PGSimpleDataSource`
 * instead and calls ONLY `repair()`, never `migrate()`.
 *
 * **Deliberately NOT a network-reachable endpoint**, same trust boundary as
 * [network.lapis.cloud.server.bootstrap.AdminBootstrap] -- a plain `main()` invoked from a shell
 * with direct access to the deployment's environment (`LAPIS_DB_URL` etc.), the same trust boundary
 * as running `psql`/`flyway repair` by hand against the production database directly.
 *
 * Run via the Gradle `flywayRepair` task (see `build.gradle.kts`):
 * ```
 * LAPIS_DB_URL=jdbc:postgresql://localhost:<tunnelled-port>/lapiscloud \
 * LAPIS_DB_USER=lapiscloud \
 * LAPIS_DB_PASSWORD='...' \
 *   ./gradlew :lapis-server:flywayRepair
 * ```
 * Prints Flyway's own repair report (which entries it touched) at INFO before exiting 0. Idempotent
 * -- repairing an already-correct schema history is a documented no-op, safe to re-run.
 */
fun main() {
    val jdbcUrl = System.getenv("LAPIS_DB_URL") ?: error("LAPIS_DB_URL must be set")
    val username = System.getenv("LAPIS_DB_USER") ?: error("LAPIS_DB_USER must be set")
    val password = System.getenv("LAPIS_DB_PASSWORD") ?: error("LAPIS_DB_PASSWORD must be set")

    val dataSource =
        PGSimpleDataSource().apply {
            setUrl(jdbcUrl)
            setUser(username)
            setPassword(password)
        }

    val result =
        Flyway
            .configure()
            .dataSource(dataSource)
            .locations("classpath:db/migration")
            .load()
            .repair()

    logger.info {
        "Flyway repair complete: ${result.repairActions.size} action(s), " +
            "${result.migrationsAligned.size} checksum(s) realigned, " +
            "${result.migrationsDeleted.size} deleted, ${result.migrationsRemoved.size} removed."
    }
    result.migrationsAligned.forEach { logger.info { "  checksum realigned: V${it.version} -- ${it.description}" } }
    result.migrationsDeleted.forEach { logger.info { "  deleted: V${it.version} -- ${it.description}" } }
    result.migrationsRemoved.forEach { logger.info { "  removed: V${it.version} -- ${it.description}" } }
}
