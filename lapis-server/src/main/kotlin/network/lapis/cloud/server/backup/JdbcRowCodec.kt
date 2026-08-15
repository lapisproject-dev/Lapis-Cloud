package network.lapis.cloud.server.backup

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import network.lapis.cloud.server.backup.OrganizationSchemaCatalog.ColumnMetadata
import java.math.BigDecimal
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.UUID

/**
 * Generic JDBC row <-> JSON codec driven entirely by [ColumnMetadata.typeName] (the
 * `DatabaseMetaData.getColumns` `TYPE_NAME`, e.g. `UUID`/`CHARACTER VARYING`/`NUMERIC`/`BOOLEAN`/
 * `BIGINT`/`INTEGER`/`DATE`/`TIMESTAMP`) -- this is what lets [OrganizationExportService]/
 * [OrganizationRestoreService] round-trip literally any table in the schema without a single
 * table- or domain-specific `if` branch anywhere in this file.
 *
 * `NUMERIC`/`DECIMAL` values are encoded as JSON *strings* (`BigDecimal.toPlainString()`), never as
 * a JSON number -- exact decimal precision (e.g. a `Decimal(15,2)` ledger amount) must round-trip
 * byte-for-byte, and a JSON number would risk silent double-precision rounding on some JSON
 * parsers. `DATE`/`TIMESTAMP` are encoded as ISO-8601 strings (`java.time.LocalDate`/
 * `java.time.LocalDateTime` `toString()`/`parse()`), matching the format Exposed's own
 * `kotlinx.datetime` mapping already produces/consumes.
 */
object JdbcRowCodec {
    /** Reads one row of [rs] (already positioned via `next()`) into a [JsonObject] keyed by column name, in [columns] order. */
    fun rowToJson(
        rs: ResultSet,
        columns: List<ColumnMetadata>,
    ): JsonObject =
        buildJsonObject {
            columns.forEach { column -> put(column.name, encodeValue(rs = rs, column = column)) }
        }

    private fun encodeValue(
        rs: ResultSet,
        column: ColumnMetadata,
    ): JsonElement {
        val raw = rs.getObject(column.name)
        if (raw == null || rs.wasNull()) return JsonNull
        val typeName = column.typeName.uppercase()
        return when {
            typeName == "UUID" -> JsonPrimitive(raw.toString())
            typeName.contains("BOOL") -> JsonPrimitive(rs.getBoolean(column.name))
            typeName.contains("NUMERIC") || typeName.contains("DECIMAL") ->
                JsonPrimitive((rs.getBigDecimal(column.name) ?: BigDecimal(raw.toString())).toPlainString())
            typeName.contains("BIGINT") -> JsonPrimitive(rs.getLong(column.name))
            typeName.contains("INT") -> JsonPrimitive(rs.getLong(column.name))
            typeName.contains("DOUBLE") || typeName.contains("REAL") || typeName.contains("FLOAT") ->
                JsonPrimitive(rs.getDouble(column.name))
            typeName == "DATE" -> JsonPrimitive(rs.getDate(column.name).toLocalDate().toString())
            typeName.contains("TIMESTAMP") -> JsonPrimitive(rs.getTimestamp(column.name).toLocalDateTime().toString())
            else -> JsonPrimitive(rs.getString(column.name) ?: raw.toString())
        }
    }

    /**
     * Binds [row]'s values onto [statement] starting at parameter index [startIndex], in [columns]
     * order -- the restore-side inverse of [rowToJson]. Never string-interpolates a value into SQL
     * text; every value travels through a typed `PreparedStatement.setXxx` call.
     */
    fun bindRow(
        statement: PreparedStatement,
        row: JsonObject,
        columns: List<ColumnMetadata>,
        startIndex: Int = 1,
    ) {
        columns.forEachIndexed { offset, column ->
            bindValue(statement = statement, index = startIndex + offset, column = column, element = row[column.name] ?: JsonNull)
        }
    }

    private fun bindValue(
        statement: PreparedStatement,
        index: Int,
        column: ColumnMetadata,
        element: JsonElement,
    ) {
        if (element is JsonNull) {
            statement.setNull(index, column.jdbcType)
            return
        }
        val primitive = element.jsonPrimitive
        val content = primitive.content
        val typeName = column.typeName.uppercase()
        when {
            typeName == "UUID" -> statement.setObject(index, UUID.fromString(content))
            typeName.contains("BOOL") -> statement.setBoolean(index, primitive.boolean)
            typeName.contains("NUMERIC") || typeName.contains("DECIMAL") -> statement.setBigDecimal(index, BigDecimal(content))
            typeName.contains("BIGINT") -> statement.setLong(index, primitive.long)
            typeName.contains("INT") -> statement.setInt(index, primitive.int)
            typeName.contains(
                "DOUBLE",
            ) ||
                typeName.contains("REAL") ||
                typeName.contains("FLOAT") -> statement.setDouble(index, primitive.double)
            typeName == "DATE" -> statement.setDate(index, java.sql.Date.valueOf(LocalDate.parse(content)))
            typeName.contains("TIMESTAMP") -> statement.setTimestamp(index, java.sql.Timestamp.valueOf(LocalDateTime.parse(content)))
            else -> statement.setString(index, content)
        }
    }
}
