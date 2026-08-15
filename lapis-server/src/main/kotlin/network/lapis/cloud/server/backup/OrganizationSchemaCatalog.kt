package network.lapis.cloud.server.backup

import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import java.security.MessageDigest
import java.sql.Connection
import java.sql.DatabaseMetaData

/** Thrown when the live schema itself is not shaped the way [OrganizationSchemaCatalog] requires to do its job safely. */
class SchemaIntegrityException(
    message: String,
) : RuntimeException(message)

/**
 * Structural, `information_schema`/`DatabaseMetaData`-driven discovery of "every table that holds
 * organizational data" -- the mechanism [OrganizationExportService]/[OrganizationRestoreService]
 * rely on so that "every table is in scope" is a guarantee of the schema itself, not a
 * hand-maintained Kotlin list someone has to remember to update (see
 * `network.lapis.cloud.server.dsgvo.PersonalDataCoverageTest`'s own `information_schema` walk,
 * which this generalizes from "every table with a FK to member" to "every table, period").
 *
 * `public` is used as the fixed schema filter throughout -- both this codebase's H2 test/dev
 * database (`MODE=PostgreSQL`, see `DatabaseConfig`) and a real Postgres deployment default to the
 * lowercase `public` schema; verified empirically against H2 2.4 (see V0.5.4 implementation notes).
 */
object OrganizationSchemaCatalog {
    /**
     * The one documented, non-organizational exclusion. Nothing else is excluded for
     * PII-sensitivity reasons (unlike the DSGVO per-member export) -- see
     * [OrganizationExportService] KDoc's scope-decision note: this is an ADMIN-only, whole-
     * organization operational backup, not a data-subject-rights export.
     */
    val EXCLUDED_TABLES: Map<String, String> =
        mapOf(
            "flyway_schema_history" to "Flyway migration bookkeeping, not organizational data.",
        )

    data class ColumnMetadata(
        val name: String,
        val jdbcType: Int,
        val typeName: String,
        val nullable: Boolean,
        val ordinal: Int,
    )

    data class TableMetadata(
        val tableName: String,
        val columns: List<ColumnMetadata>,
        val primaryKeyColumns: List<String>,
        /** Tables this table has a real FK to (within the exportable set) -- drives [restoreOrder]. */
        val dependsOnTables: Set<String>,
    )

    /**
     * Every exportable table (see [EXCLUDED_TABLES]), each with its full column shape, primary key,
     * and FK dependency edges -- discovered fresh on every call via [tx]'s live JDBC connection,
     * never cached. Must run inside an open `transaction {}`/`transaction(database) {}` block.
     */
    fun exportableTables(tx: JdbcTransaction): List<TableMetadata> {
        val connection = tx.rawConnection()
        val tableNames = allBaseTableNames(connection).filterNot { it in EXCLUDED_TABLES }.toSet()
        return tableNames.sorted().map { tableName ->
            tableMetadataOf(connection = connection, tableName = tableName, exportableTableNames = tableNames)
        }
    }

    private fun allBaseTableNames(connection: Connection): List<String> {
        val names = mutableListOf<String>()
        connection.createStatement().use { statement ->
            statement
                .executeQuery(
                    """
                    SELECT table_name FROM information_schema.tables
                    WHERE table_schema = 'public' AND table_type = 'BASE TABLE'
                    """.trimIndent(),
                ).use { rs ->
                    while (rs.next()) names += rs.getString(1)
                }
        }
        return names
    }

    private fun tableMetadataOf(
        connection: Connection,
        tableName: String,
        exportableTableNames: Set<String>,
    ): TableMetadata {
        val columns = mutableListOf<ColumnMetadata>()
        connection.metaData.getColumns(null, "public", tableName, null).use { rs ->
            while (rs.next()) {
                columns +=
                    ColumnMetadata(
                        name = rs.getString("COLUMN_NAME"),
                        jdbcType = rs.getInt("DATA_TYPE"),
                        typeName = rs.getString("TYPE_NAME"),
                        nullable = rs.getInt("NULLABLE") == DatabaseMetaData.columnNullable,
                        ordinal = rs.getInt("ORDINAL_POSITION"),
                    )
            }
        }
        columns.sortBy { it.ordinal }

        val primaryKeyColumns = mutableListOf<Pair<Int, String>>()
        connection.metaData.getPrimaryKeys(null, "public", tableName).use { rs ->
            while (rs.next()) {
                primaryKeyColumns += rs.getInt("KEY_SEQ") to rs.getString("COLUMN_NAME")
            }
        }
        if (primaryKeyColumns.isEmpty()) {
            throw SchemaIntegrityException(
                "Table '$tableName' has no primary key -- the generic upsert-based restore path cannot " +
                    "identify rows to update without one",
            )
        }

        val dependsOn = mutableSetOf<String>()
        connection.metaData.getImportedKeys(null, "public", tableName).use { rs ->
            while (rs.next()) {
                val referencedTable = rs.getString("PKTABLE_NAME")
                if (referencedTable != tableName && referencedTable in exportableTableNames) {
                    dependsOn += referencedTable
                }
            }
        }

        return TableMetadata(
            tableName = tableName,
            columns = columns,
            primaryKeyColumns = primaryKeyColumns.sortedBy { it.first }.map { it.second },
            dependsOnTables = dependsOn,
        )
    }

    /**
     * Topological (FK-dependency, parents-before-children) order over [tables] -- Kahn's algorithm,
     * deterministic (ties broken alphabetically by table name) so the resulting order is stable
     * across runs and easy to assert against in tests. Throws [SchemaIntegrityException] on an
     * (unexpected, currently never-occurring in this schema) FK dependency cycle rather than
     * silently producing a partial/wrong order.
     */
    fun restoreOrder(tables: List<TableMetadata>): List<TableMetadata> {
        val byName = tables.associateBy { it.tableName }
        val remainingDeps =
            tables.associate { table -> table.tableName to table.dependsOnTables.filterTo(mutableSetOf()) { it in byName } }.toMutableMap()

        val result = mutableListOf<TableMetadata>()
        val emitted = mutableSetOf<String>()
        var ready =
            remainingDeps
                .filterValues { it.isEmpty() }
                .keys
                .sorted()
                .toMutableList()

        while (ready.isNotEmpty()) {
            val name = ready.removeAt(0)
            if (name in emitted) continue
            emitted += name
            result += byName.getValue(name)

            val newlyReady = mutableListOf<String>()
            for ((tableName, deps) in remainingDeps) {
                if (tableName !in emitted && name in deps) {
                    deps.remove(name)
                    if (deps.isEmpty()) newlyReady += tableName
                }
            }
            ready = (ready + newlyReady).distinct().sorted().toMutableList()
        }

        if (result.size != tables.size) {
            val stuck = tables.map { it.tableName }.toSet() - emitted
            throw SchemaIntegrityException("Foreign-key dependency cycle detected among tables: ${stuck.sorted()}")
        }
        return result
    }

    /**
     * Stable SHA-256 fingerprint over every exportable table's shape (name, and per-column
     * name/type/nullability, both sorted for determinism) -- this is
     * [network.lapis.cloud.server.backup.BackupManifest.schemaChecksum], compared before
     * [OrganizationRestoreService] writes a single row, so a bundle produced against a different
     * schema version is rejected with a clear error rather than partially/incorrectly applied.
     */
    fun schemaChecksum(tables: List<TableMetadata>): String {
        val digest = MessageDigest.getInstance("SHA-256")
        tables.sortedBy { it.tableName }.forEach { table ->
            digest.update(table.tableName.toByteArray(Charsets.UTF_8))
            digest.update(0.toByte())
            table.columns.sortedBy { it.name }.forEach { column ->
                digest.update(column.name.toByteArray(Charsets.UTF_8))
                digest.update(column.typeName.uppercase().toByteArray(Charsets.UTF_8))
                digest.update((if (column.nullable) 1 else 0).toByte())
            }
            digest.update(1.toByte())
        }
        return digest.digest().toHexLower()
    }
}
