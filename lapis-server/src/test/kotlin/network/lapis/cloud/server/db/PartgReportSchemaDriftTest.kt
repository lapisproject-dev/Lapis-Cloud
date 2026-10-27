package network.lapis.cloud.server.db

import dev.kuml.erm.model.ErmDataType
import dev.kuml.erm.model.ErmModel
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import network.lapis.cloud.server.db.generated.PartgReportPositionTable
import network.lapis.cloud.server.db.generated.PartgReportSheetTable
import network.lapis.cloud.server.db.generated.PartgReportValueTable
import network.lapis.cloud.shared.domain.PartgKind
import network.lapis.cloud.shared.domain.PartgSection
import network.lapis.cloud.shared.domain.PartgUnitLevel
import network.lapis.cloud.shared.domain.PartgValueSource
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.io.File

/**
 * ADR-0016 (MDA persistence pipeline) -- party report (V1.9.225). Verifies that `76-partg-report.kuml.kts` is a faithful model of the real,
 * Flyway-migrated H2 schema (`partg_report_position`, `partg_report_sheet`, `partg_report_value`) and of the three Exposed objects, column for
 * column. The two foreign keys on the natural key `code` exist only in SQL (see the model's file header) and are pinned here explicitly.
 */
class PartgReportSchemaDriftTest :
    FunSpec({
        beforeSpec { DatabaseConfig.connect() }

        val model: ErmModel by lazy { KumlModelLoader.loadErmModel(File(KumlModelLoader.kumlSourceDir, "76-partg-report.kuml.kts")) }

        fun ErmModel.entityNameOf(entityId: String): String? = entities.firstOrNull { it.id == entityId }?.name

        test("the model declares the three report entities plus the Member and RegionalChapter stubs") {
            model.entities.map { it.name }.toSet() shouldBe
                setOf("member", "regional_chapter", "partg_report_position", "partg_report_sheet", "partg_report_value")
        }

        fun check(
            table: String,
            exposedColumns: List<String>,
            expectedForeignKeys: Map<String, String>,
            sqlOnlyForeignKeys: Map<String, String>,
        ) {
            val entity = model.entities.single { it.name == table }
            val real = transaction { introspectPartgTable(table) }
            entity.attributes.map { it.name }.toSet() shouldBe real.columns.keys
            entity.attributes.forEach { attr ->
                withClue("column '${attr.name}' of $table") { real.columns.getValue(attr.name!!).nullable shouldBe attr.nullable }
            }
            entity.attributes.map { it.name } shouldContainExactlyInAnyOrder exposedColumns
            real.primaryKeyColumns shouldBe setOf("id")
            entity.attributes.filter { it.foreignKey != null }.associate {
                it.name!! to model.entityNameOf(it.foreignKey!!.targetEntityId)
            } shouldBe expectedForeignKeys
            real.foreignKeys shouldBe expectedForeignKeys + sqlOnlyForeignKeys
        }

        test("partg_report_position matches the migrated schema and PartgReportPositionTable 1:1") {
            check(
                table = "partg_report_position",
                exposedColumns = PartgReportPositionTable.columns.map { it.name },
                expectedForeignKeys = mapOf("org_hint_updated_by" to "member"),
                sqlOnlyForeignKeys = mapOf("parent_code" to "partg_report_position"),
            )
        }

        test("partg_report_sheet matches the migrated schema and PartgReportSheetTable 1:1") {
            check(
                table = "partg_report_sheet",
                exposedColumns = PartgReportSheetTable.columns.map { it.name },
                expectedForeignKeys = mapOf("regional_chapter_id" to "regional_chapter", "updated_by" to "member"),
                sqlOnlyForeignKeys = emptyMap(),
            )
        }

        test("partg_report_value matches the migrated schema and PartgReportValueTable 1:1") {
            check(
                table = "partg_report_value",
                exposedColumns = PartgReportValueTable.columns.map { it.name },
                expectedForeignKeys = mapOf("sheet_id" to "partg_report_sheet", "updated_by" to "member"),
                sqlOnlyForeignKeys = mapOf("position_code" to "partg_report_position"),
            )
        }

        test("the enums are in exactly the order of the shared enums and sized by their sqlType") {
            PartgSection.entries.map { it.name } shouldBe listOf("INCOME", "EXPENSE", "ASSET", "LIABILITY")
            PartgKind.entries.map { it.name } shouldBe listOf("LEAF", "SUM")
            PartgUnitLevel.entries.map { it.name } shouldBe listOf("FEDERAL", "STATE", "SUBORDINATE")
            PartgValueSource.entries.map { it.name } shouldBe listOf("MANUAL", "IMPORT")
            model.entities
                .single { it.name == "partg_report_position" }
                .attributeByName("section")
                ?.type shouldBe
                ErmDataType.Varchar(length = 9)
            model.entities
                .single { it.name == "partg_report_sheet" }
                .attributeByName("unit_level")
                ?.type shouldBe
                ErmDataType.Varchar(length = 11)
            model.entities
                .single { it.name == "partg_report_value" }
                .attributeByName("value_source")
                ?.type shouldBe
                ErmDataType.Varchar(length = 6)
            transaction {
                listOf(
                    PartgReportPositionTable.section.columnType.sqlType(),
                    PartgReportPositionTable.kind.columnType.sqlType(),
                    PartgReportSheetTable.unitLevel.columnType.sqlType(),
                    PartgReportValueTable.valueSource.columnType.sqlType(),
                )
            } shouldBe listOf("VARCHAR(9)", "VARCHAR(4)", "VARCHAR(11)", "VARCHAR(6)")
        }

        test("the unique rules: code and sort order of a position, year and unit of a sheet, sheet and position of a value") {
            // a UNIQUE constraint shows up twice (the constraint and its backing index): compare the distinct column sets
            transaction { introspectPartgTable("partg_report_position") }.uniqueConstraints.distinct() shouldContainExactlyInAnyOrder
                listOf(setOf("code"), setOf("sort_order"))
            transaction { introspectPartgTable("partg_report_sheet") }.uniqueConstraints shouldContainExactlyInAnyOrder
                listOf(setOf("fiscal_year", "unit_key"))
            transaction { introspectPartgTable("partg_report_value") }.uniqueConstraints shouldContainExactlyInAnyOrder
                listOf(setOf("sheet_id", "position_code"))
        }

        test("the model keeps its own declaration of the attributes in the order the table has them") {
            model.entities
                .single { it.name == "partg_report_value" }
                .attributes
                .map { it.name } shouldContainExactly
                PartgReportValueTable.columns.map { it.name }
        }
    })

/** Result of introspecting one real table's shape via `information_schema`. */
private data class IntrospectedPartgTable(
    val columns: Map<String, IntrospectedPartgColumn>,
    val foreignKeys: Map<String, String>,
    val primaryKeyColumns: Set<String>,
    val uniqueConstraints: List<Set<String>>,
)

private data class IntrospectedPartgColumn(
    val nullable: Boolean,
)

private fun JdbcTransaction.introspectPartgTable(tableName: String): IntrospectedPartgTable {
    val nullableByColumn = mutableMapOf<String, Boolean>()
    exec(
        """
        SELECT column_name, is_nullable
        FROM information_schema.columns
        WHERE table_name = '$tableName'
        """.trimIndent(),
    ) { rs ->
        while (rs.next()) {
            nullableByColumn[rs.getString("column_name")] = rs.getString("is_nullable") == "YES"
        }
    }

    val fkByColumn = mutableMapOf<String, String>()
    exec(
        """
        SELECT kcu.column_name AS fk_column, tc2.table_name AS ref_table
        FROM information_schema.table_constraints tc
        JOIN information_schema.key_column_usage kcu
            ON tc.constraint_name = kcu.constraint_name
            AND tc.table_schema = kcu.table_schema
        JOIN information_schema.referential_constraints rc
            ON tc.constraint_name = rc.constraint_name
            AND tc.constraint_schema = rc.constraint_schema
        JOIN information_schema.table_constraints tc2
            ON rc.unique_constraint_name = tc2.constraint_name
            AND rc.unique_constraint_schema = tc2.table_schema
        WHERE tc.constraint_type = 'FOREIGN KEY' AND tc.table_name = '$tableName'
        """.trimIndent(),
    ) { rs ->
        while (rs.next()) {
            fkByColumn[rs.getString("fk_column")] = rs.getString("ref_table")
        }
    }

    val pkColumns = mutableSetOf<String>()
    exec(
        """
        SELECT kcu.column_name
        FROM information_schema.table_constraints tc
        JOIN information_schema.key_column_usage kcu
            ON tc.constraint_name = kcu.constraint_name
            AND tc.table_schema = kcu.table_schema
        WHERE tc.constraint_type = 'PRIMARY KEY' AND tc.table_name = '$tableName'
        """.trimIndent(),
    ) { rs ->
        while (rs.next()) {
            pkColumns += rs.getString("column_name")
        }
    }

    val uniqueColumnsByConstraint = mutableMapOf<String, MutableSet<String>>()
    exec(
        """
        SELECT tc.constraint_name AS name, kcu.column_name
        FROM information_schema.table_constraints tc
        JOIN information_schema.key_column_usage kcu
            ON tc.constraint_name = kcu.constraint_name
            AND tc.table_schema = kcu.table_schema
        WHERE tc.constraint_type = 'UNIQUE' AND tc.table_name = '$tableName'
        UNION
        SELECT i.index_name AS name, ic.column_name
        FROM information_schema.index_columns ic
        JOIN information_schema.indexes i
            ON ic.index_name = i.index_name AND ic.table_name = i.table_name
        WHERE i.index_type_name = 'UNIQUE INDEX' AND ic.table_name = '$tableName'
        """.trimIndent(),
    ) { rs ->
        while (rs.next()) {
            uniqueColumnsByConstraint
                .getOrPut(rs.getString("name")) { mutableSetOf() }
                .add(rs.getString("column_name"))
        }
    }

    val columns = nullableByColumn.mapValues { (_, nullable) -> IntrospectedPartgColumn(nullable = nullable) }
    return IntrospectedPartgTable(
        columns = columns,
        foreignKeys = fkByColumn,
        primaryKeyColumns = pkColumns,
        uniqueConstraints = uniqueColumnsByConstraint.values.map { it.toSet() },
    )
}
