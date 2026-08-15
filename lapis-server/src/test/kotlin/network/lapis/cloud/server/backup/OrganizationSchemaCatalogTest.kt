package network.lapis.cloud.server.backup

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import network.lapis.cloud.server.db.DatabaseConfig
import org.jetbrains.exposed.v1.jdbc.transactions.transaction

/**
 * Independently re-derives "every real table in the live schema" via its own separate
 * `information_schema` query (not by calling [OrganizationSchemaCatalog]'s own helper twice) so a
 * bug inside the catalog itself -- not just a hand-maintained-list drift -- would be caught. This is
 * the "hard completeness" check the V0.5.4 security-loop review explicitly asked for: "every table
 * with organizational data is genuinely in scope, not merely presumed to be".
 */
class OrganizationSchemaCatalogTest :
    FunSpec({
        beforeSpec { DatabaseConfig.connect() }

        test("exportableTables() returns every real base table in the public schema minus exactly the documented exclusions") {
            val (actual, expected) =
                transaction {
                    val actualNames = OrganizationSchemaCatalog.exportableTables(this).map { it.tableName }.toSet()
                    val expectedNames = independentlyQueryBaseTableNames(this) - OrganizationSchemaCatalog.EXCLUDED_TABLES.keys
                    actualNames to expectedNames
                }
            actual shouldContainExactlyInAnyOrder expected
        }

        test("flyway_schema_history is the only documented exclusion, and it is genuinely absent from the result") {
            OrganizationSchemaCatalog.EXCLUDED_TABLES.keys shouldBe setOf("flyway_schema_history")
            val names = transaction { OrganizationSchemaCatalog.exportableTables(this).map { it.tableName } }
            ("flyway_schema_history" in names) shouldBe false
        }

        test("every returned table has at least one primary key column") {
            val tables = transaction { OrganizationSchemaCatalog.exportableTables(this) }
            tables.forEach { table ->
                (table.primaryKeyColumns.isNotEmpty()) shouldBe true
            }
        }

        test("every returned table's columns are non-empty and ordinal-sorted") {
            val tables = transaction { OrganizationSchemaCatalog.exportableTables(this) }
            tables.forEach { table ->
                (table.columns.isNotEmpty()) shouldBe true
                table.columns.map { it.ordinal } shouldBe table.columns.map { it.ordinal }.sorted()
            }
        }

        test("restoreOrder places every table after every table it depends on") {
            val tables = transaction { OrganizationSchemaCatalog.exportableTables(this) }
            val ordered = OrganizationSchemaCatalog.restoreOrder(tables)
            ordered.map { it.tableName }.toSet() shouldBe tables.map { it.tableName }.toSet()

            val position = ordered.mapIndexed { index, table -> table.tableName to index }.toMap()
            ordered.forEach { table ->
                table.dependsOnTables.forEach { dependency ->
                    (position.getValue(dependency) < position.getValue(table.tableName)) shouldBe true
                }
            }
        }

        test("restoreOrder throws SchemaIntegrityException on a synthetic FK dependency cycle") {
            val a =
                OrganizationSchemaCatalog.TableMetadata(
                    tableName = "cycle_a",
                    columns =
                        listOf(
                            OrganizationSchemaCatalog.ColumnMetadata(
                                name = "id",
                                jdbcType = 0,
                                typeName = "INTEGER",
                                nullable = false,
                                ordinal = 1,
                            ),
                        ),
                    primaryKeyColumns = listOf("id"),
                    dependsOnTables = setOf("cycle_b"),
                )
            val b =
                OrganizationSchemaCatalog.TableMetadata(
                    tableName = "cycle_b",
                    columns =
                        listOf(
                            OrganizationSchemaCatalog.ColumnMetadata(
                                name = "id",
                                jdbcType = 0,
                                typeName = "INTEGER",
                                nullable = false,
                                ordinal = 1,
                            ),
                        ),
                    primaryKeyColumns = listOf("id"),
                    dependsOnTables = setOf("cycle_a"),
                )
            try {
                OrganizationSchemaCatalog.restoreOrder(listOf(a, b))
                throw AssertionError("Expected SchemaIntegrityException for a synthetic FK cycle")
            } catch (e: SchemaIntegrityException) {
                (e.message?.contains("cycle_a") == true || e.message?.contains("cycle_b") == true) shouldBe true
            }
        }

        test("schemaChecksum is stable across repeated calls and changes when a column is added") {
            val tables = transaction { OrganizationSchemaCatalog.exportableTables(this) }
            val checksum1 = OrganizationSchemaCatalog.schemaChecksum(tables)
            val checksum2 = OrganizationSchemaCatalog.schemaChecksum(tables)
            checksum1 shouldBe checksum2

            val mutatedFirstTable =
                tables.first().let { table ->
                    table.copy(
                        columns =
                            table.columns +
                                OrganizationSchemaCatalog.ColumnMetadata(
                                    name = "synthetic_extra_column",
                                    jdbcType = 0,
                                    typeName = "INTEGER",
                                    nullable = true,
                                    ordinal = table.columns.size + 1,
                                ),
                    )
                }
            val mutatedTables = listOf(mutatedFirstTable) + tables.drop(1)
            val checksum3 = OrganizationSchemaCatalog.schemaChecksum(mutatedTables)
            (checksum3 != checksum1) shouldBe true
        }
    })

/**
 * ANSI `information_schema` walk, deliberately independent from [OrganizationSchemaCatalog]'s own
 * implementation -- mirrors `network.lapis.cloud.server.dsgvo.PersonalDataCoverageTest`'s
 * `tablesReferencingMember` idiom.
 */
private fun independentlyQueryBaseTableNames(tx: org.jetbrains.exposed.v1.jdbc.JdbcTransaction): Set<String> {
    val names = mutableSetOf<String>()
    tx.exec(
        """
        SELECT table_name FROM information_schema.tables
        WHERE table_schema = 'public' AND table_type = 'BASE TABLE'
        """.trimIndent(),
    ) { rs ->
        while (rs.next()) names += rs.getString(1)
    }
    return names
}
