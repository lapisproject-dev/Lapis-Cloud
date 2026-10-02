package network.lapis.cloud.server.db

import io.kotest.core.annotation.EnabledIf
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import network.lapis.cloud.server.testdb.PgSpecDatabase
import network.lapis.cloud.server.testdb.PostgresConfigured
import network.lapis.cloud.server.testdb.PostgresTestSupport

/**
 * Welle V1.9.37 (Postgres test lane) -- THE drift guard for the whole suite. The ~80 `*SchemaDriftTest`
 * specs prove "kUML model == migrated H2 schema == Exposed objects"; this test closes the remaining
 * edge, "migrated H2 schema == migrated PostgreSQL schema", for EVERY table. Together they make the
 * un-parametrized drift tests valid for Postgres transitively.
 *
 * A difference is a FINDING, not something to whitelist away: the only tolerated deviations are
 * those encoded in [normalizeType]/[introspectSchema] (backend-specific type spellings, backend
 * auto-generated constraint names) and each carries a comment there.
 */
@Tags("Postgres")
@EnabledIf(PostgresConfigured::class)
class PostgresH2SchemaEquivalenceTest :
    FunSpec({
        lateinit var pg: PgSpecDatabase
        lateinit var h2: NormalizedSchema
        lateinit var postgres: NormalizedSchema

        beforeSpec {
            h2 = introspectSchema(db = DatabaseConfig.connect(), postgres = false)
            pg = PostgresTestSupport.createDatabase()
            postgres = introspectSchema(db = pg.database, postgres = true)
        }
        afterSpec { pg.close() }

        test("both databases expose the same set of tables (and plenty of them)") {
            h2.columns.size shouldBeGreaterThanOrEqual 150
            println(
                "schema equivalence: tables=${h2.columns.size} columns=${h2.columns.values.sumOf { it.size }} " +
                    "fks=${h2.foreignKeys.values.sumOf { it.size }} uniques=${h2.uniqueColumnSets.values.sumOf { it.size }} " +
                    "namedChecks=${h2.namedChecks.values.sumOf { it.size }}",
            )
            // The introspection must not run silently empty on either side.
            for (snap in listOf(h2, postgres)) {
                snap.foreignKeys.values.sumOf { it.size } shouldBeGreaterThanOrEqual 100
                snap.uniqueColumnSets.values.sumOf { it.size } shouldBeGreaterThanOrEqual 150
                snap.namedChecks.values.sumOf { it.size } shouldBeGreaterThanOrEqual 1
            }
            val onlyH2 = h2.columns.keys - postgres.columns.keys
            val onlyPg = postgres.columns.keys - h2.columns.keys
            (onlyH2.map { "only on H2: $it" } + onlyPg.map { "only on Postgres: $it" }).shouldBeEmpty()
        }

        test("every column matches: name, normalized type, length, precision/scale, nullability, generated") {
            val diffs = mutableListOf<String>()
            for (table in h2.columns.keys.intersect(postgres.columns.keys)) {
                val a = h2.columns.getValue(table)
                val b = postgres.columns.getValue(table)
                (a.keys - b.keys).forEach { diffs += "$table.$it only on H2" }
                (b.keys - a.keys).forEach { diffs += "$table.$it only on Postgres" }
                for (col in a.keys.intersect(b.keys)) {
                    if (a.getValue(col) != b.getValue(col)) diffs += "$table.$col: H2=${a.getValue(col)} Postgres=${b.getValue(col)}"
                }
            }
            diffs.shouldBeEmpty()
        }

        test("foreign keys (column to referenced table) are identical") {
            val diffs = mutableListOf<String>()
            for (table in h2.columns.keys.intersect(postgres.columns.keys)) {
                val a = h2.foreignKeys[table].orEmpty()
                val b = postgres.foreignKeys[table].orEmpty()
                (a - b).forEach { diffs += "$table FK $it only on H2" }
                (b - a).forEach { diffs += "$table FK $it only on Postgres" }
            }
            diffs.shouldBeEmpty()
        }

        test("primary keys, unique constraints and unique indexes (as column sets) are identical") {
            val diffs = mutableListOf<String>()
            for (table in h2.columns.keys.intersect(postgres.columns.keys)) {
                val a = h2.uniqueColumnSets[table].orEmpty()
                val b = postgres.uniqueColumnSets[table].orEmpty()
                (a - b).forEach { diffs += "$table unique $it only on H2" }
                (b - a).forEach { diffs += "$table unique $it only on Postgres" }
            }
            diffs.shouldBeEmpty()
        }

        test("explicitly named check constraints are identical") {
            val diffs = mutableListOf<String>()
            for (table in h2.columns.keys.intersect(postgres.columns.keys)) {
                val a = h2.namedChecks[table].orEmpty()
                val b = postgres.namedChecks[table].orEmpty()
                (a - b).forEach { diffs += "$table CHECK $it only on H2" }
                (b - a).forEach { diffs += "$table CHECK $it only on Postgres" }
            }
            diffs.shouldBeEmpty()
        }
    })
