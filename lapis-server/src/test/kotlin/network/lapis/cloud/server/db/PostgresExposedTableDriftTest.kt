package network.lapis.cloud.server.db

import io.kotest.core.annotation.EnabledIf
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import network.lapis.cloud.server.testdb.PgSpecDatabase
import network.lapis.cloud.server.testdb.PostgresConfigured
import network.lapis.cloud.server.testdb.PostgresTestSupport
import org.jetbrains.exposed.v1.core.Table
import java.io.File

/**
 * Welle V1.9.37 (Postgres test lane) -- every hand-written Exposed `Table` object in
 * `network.lapis.cloud.server.db.generated` against the REAL Postgres schema: same table, same
 * column names, same nullability. (Complements the per-wave `*SchemaDriftTest`s, which check the same
 * objects against the kUML model and H2.)
 */
@Tags("Postgres")
@EnabledIf(PostgresConfigured::class)
class PostgresExposedTableDriftTest :
    FunSpec({
        lateinit var pg: PgSpecDatabase

        beforeSpec { pg = PostgresTestSupport.createDatabase() }
        afterSpec { pg.close() }

        fun exposedTables(): List<Table> {
            val pkg = "network.lapis.cloud.server.db.generated"
            val root =
                File(
                    Class
                        .forName("$pkg.MemberTable")
                        .protectionDomain.codeSource.location
                        .toURI(),
                )
            val dir = File(root, pkg.replace('.', '/'))
            val classNames =
                dir
                    .listFiles()
                    .orEmpty()
                    .map { it.name }
                    .filter { it.endsWith(".class") && !it.contains('$') }
                    .map { "$pkg.${it.removeSuffix(".class")}" }
            return classNames.mapNotNull { Class.forName(it).kotlin.objectInstance as? Table }
        }

        test("every Exposed table object matches the Postgres schema (columns and nullability)") {
            val tables = exposedTables()
            // Guard against a silently empty classpath listing.
            tables.size shouldBeGreaterThanOrEqual 170
            val actual = introspectSchema(db = pg.database, postgres = true).columns
            val diffs = mutableListOf<String>()
            for (table in tables) {
                val name = table.tableName.lowercase()
                val real = actual[name]
                if (real == null) {
                    diffs += "$name: no such table in Postgres"
                    continue
                }
                val declared = table.columns.associateBy { it.name.lowercase() }
                (declared.keys - real.keys).forEach { diffs += "$name.$it declared in Exposed, missing in Postgres" }
                (real.keys - declared.keys).forEach { diffs += "$name.$it exists in Postgres, missing in the Exposed object" }
                for ((col, column) in declared) {
                    val r = real[col] ?: continue
                    if (column.columnType.nullable != r.nullable) {
                        diffs += "$name.$col: Exposed nullable=${column.columnType.nullable} Postgres nullable=${r.nullable}"
                    }
                }
            }
            diffs.shouldBeEmpty()
        }
    })
