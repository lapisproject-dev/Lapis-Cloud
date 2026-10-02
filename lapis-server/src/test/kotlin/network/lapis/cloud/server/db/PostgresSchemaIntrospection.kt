package network.lapis.cloud.server.db

import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.sql.Connection

/** Backend-neutral, normalized column description (see [normalizeType]). */
internal data class NormalizedColumn(
    val type: String,
    val nullable: Boolean,
    val length: Int?,
    val precision: Int?,
    val scale: Int?,
    val generated: Boolean,
)

/** One database's schema, reduced to what must be identical between H2 (test suite) and Postgres (production). */
internal data class NormalizedSchema(
    /** table -> column -> description */
    val columns: Map<String, Map<String, NormalizedColumn>>,
    /** table -> set of `column->referencedTable` */
    val foreignKeys: Map<String, Set<String>>,
    /** table -> set of column-name sets (primary keys, unique constraints and unique indexes alike) */
    val uniqueColumnSets: Map<String, Set<Set<String>>>,
    /** table -> names of EXPLICITLY named check constraints (auto-generated names are backend specific and dropped) */
    val namedChecks: Map<String, Set<String>>,
)

/**
 * Normalizes a backend-specific `information_schema.columns.data_type` into one canonical name. An
 * unknown type deliberately yields `UNKNOWN:<raw>` so a new, unmapped type fails the equivalence test
 * instead of being silently ignored.
 */
internal fun normalizeType(
    raw: String,
    postgres: Boolean,
): String {
    val t = raw.lowercase()
    return when (t) {
        "character varying" -> "VARCHAR"
        "character" -> "CHAR"
        "text", "character large object" -> "TEXT"
        "uuid" -> "UUID"
        "integer" -> "INT"
        "bigint" -> "BIGINT"
        "smallint" -> "SMALLINT"
        "boolean" -> "BOOL"
        "numeric" -> "NUMERIC"
        "double precision" -> "DOUBLE"
        "real" -> "REAL"
        "date" -> "DATE"
        "time without time zone", "time" -> "TIME"
        "timestamp without time zone", "timestamp" -> "TIMESTAMP"
        "timestamp with time zone" -> "TIMESTAMPTZ"
        "bytea", "binary varying", "binary large object" -> "BYTES"
        "json", "jsonb" -> "JSON"
        else -> "UNKNOWN:${if (postgres) "pg" else "h2"}:$raw"
    }
}

private const val H2_UNBOUNDED_VARCHAR = 1_000_000_000

private val h2AutoNamePattern = Regex("^CONSTRAINT_[0-9A-F]+$", RegexOption.IGNORE_CASE)

/** Reads the [NormalizedSchema] of [db]. Postgres: schema `public`; H2: schema `PUBLIC`. */
internal fun introspectSchema(
    db: Database,
    postgres: Boolean,
): NormalizedSchema =
    transaction(db) {
        val conn = connection.connection as Connection
        val schema = conn.schema
        val tables = sortedSetOf<String>()
        conn.metaData.getTables(null, schema, "%", arrayOf("TABLE")).use { rs ->
            while (rs.next()) tables += rs.getString("TABLE_NAME").lowercase()
        }
        tables.remove("flyway_schema_history")

        val columns = mutableMapOf<String, MutableMap<String, NormalizedColumn>>()
        conn
            .prepareStatement(
                """
                SELECT table_name, column_name, is_nullable, data_type, character_maximum_length,
                       numeric_precision, numeric_scale, is_generated
                FROM information_schema.columns WHERE UPPER(table_schema) = UPPER(?)
                """.trimIndent(),
            ).use { ps ->
                ps.setString(1, schema)
                ps.executeQuery().use { rs ->
                    while (rs.next()) {
                        val table = rs.getString("table_name").lowercase()
                        if (table !in tables) continue
                        var type = normalizeType(raw = rs.getString("data_type"), postgres = postgres)
                        var length = rs.getInt("character_maximum_length").takeIf { !rs.wasNull() && type == "VARCHAR" }
                        // H2 has no unbounded `text`: it spells Postgres' `text` as VARCHAR(1000000000)
                        // (its maximum). Semantically the same thing -- an explicit, documented
                        // equivalence, NOT a tolerance: a real `VARCHAR(n)` still has to match exactly.
                        if (!postgres && type == "VARCHAR" && length == H2_UNBOUNDED_VARCHAR) {
                            type = "TEXT"
                            length = null
                        }
                        val precision = rs.getInt("numeric_precision").takeIf { !rs.wasNull() && type == "NUMERIC" }
                        val scale = rs.getInt("numeric_scale").takeIf { !rs.wasNull() && type == "NUMERIC" }
                        columns.getOrPut(table) { mutableMapOf() }[rs.getString("column_name").lowercase()] =
                            NormalizedColumn(
                                type = type,
                                nullable = rs.getString("is_nullable") == "YES",
                                length = length,
                                precision = precision,
                                scale = scale,
                                generated = rs.getString("is_generated").equals("ALWAYS", ignoreCase = true),
                            )
                    }
                }
            }

        val foreignKeys = mutableMapOf<String, MutableSet<String>>()
        val unique = mutableMapOf<String, MutableSet<Set<String>>>()
        for (table in tables) {
            conn.metaData.getImportedKeys(null, schema, table).use { rs ->
                while (rs.next()) {
                    foreignKeys.getOrPut(table) { mutableSetOf() } +=
                        "${rs.getString("FKCOLUMN_NAME").lowercase()}->${rs.getString("PKTABLE_NAME").lowercase()}"
                }
            }
            val byIndex = mutableMapOf<String, MutableSet<String>>()
            conn.metaData.getIndexInfo(null, schema, table, true, false).use { rs ->
                while (rs.next()) {
                    val index = rs.getString("INDEX_NAME") ?: continue
                    val column = rs.getString("COLUMN_NAME") ?: continue
                    byIndex.getOrPut(index) { mutableSetOf() } += column.lowercase()
                }
            }
            if (byIndex.isNotEmpty()) unique[table] = byIndex.values.map { it.toSet() }.toMutableSet()
        }

        val checks = mutableMapOf<String, MutableSet<String>>()
        val checkSql =
            if (postgres) {
                "SELECT c.relname AS table_name, k.conname AS constraint_name FROM pg_constraint k " +
                    "JOIN pg_class c ON c.oid = k.conrelid JOIN pg_namespace n ON n.oid = c.relnamespace " +
                    "WHERE k.contype = 'c' AND n.nspname = 'public'"
            } else {
                "SELECT table_name, constraint_name FROM information_schema.table_constraints " +
                    "WHERE constraint_type = 'CHECK' AND UPPER(table_schema) = 'PUBLIC'"
            }
        conn.createStatement().use { st ->
            st.executeQuery(checkSql).use { rs ->
                while (rs.next()) {
                    val table = rs.getString("table_name").lowercase()
                    val name = rs.getString("constraint_name")
                    // Postgres auto-names an unnamed CHECK `<table>_<column>_check` (or `<table>_check`), H2 names it
                    // CONSTRAINT_<hex>: both are backend noise. Every other name was written by a human.
                    val pgAuto = name == "${table}_check" || columns[table].orEmpty().keys.any { name == "${table}_${it}_check" }
                    if (table in tables && !h2AutoNamePattern.matches(name) && !pgAuto) {
                        checks.getOrPut(table) { mutableSetOf() } += name.lowercase()
                    }
                }
            }
        }
        NormalizedSchema(
            columns = columns.mapValues { it.value.toMap() },
            foreignKeys = foreignKeys.mapValues { it.value.toSet() },
            uniqueColumnSets = unique.mapValues { it.value.toSet() },
            namedChecks = checks.mapValues { it.value.toSet() },
        )
    }

/**
 * The "standalone UNIQUE index" half of the drift tests' unique-column UNION, per backend. The drift
 * tests started life on H2, whose `information_schema.indexes`/`index_columns` do not exist on
 * Postgres (V1.9.37: the poll/election/foundation drift tests now run on both). [withName] = also
 * select the index name (as `name`).
 */
internal fun uniqueIndexColumnSelect(
    tableName: String,
    postgres: Boolean,
    withName: Boolean = false,
): String =
    if (postgres) {
        val nameColumn = if (withName) "ic.relname AS name, " else ""
        """
        SELECT ${nameColumn}a.attname AS column_name
        FROM pg_index x
        JOIN pg_class ic ON ic.oid = x.indexrelid
        JOIN pg_class t ON t.oid = x.indrelid
        JOIN pg_namespace n ON n.oid = t.relnamespace
        JOIN pg_attribute a ON a.attrelid = t.oid AND a.attnum = ANY (x.indkey)
        WHERE x.indisunique AND NOT x.indisprimary AND t.relname = '$tableName' AND n.nspname = CURRENT_SCHEMA
        """.trimIndent()
    } else {
        val nameColumn = if (withName) "i.index_name AS name, " else ""
        """
        SELECT ${nameColumn}ic.column_name
        FROM information_schema.index_columns ic
        JOIN information_schema.indexes i
            ON ic.index_name = i.index_name AND ic.table_name = i.table_name
        WHERE i.index_type_name = 'UNIQUE INDEX' AND ic.table_name = '$tableName'
        """.trimIndent()
    }
