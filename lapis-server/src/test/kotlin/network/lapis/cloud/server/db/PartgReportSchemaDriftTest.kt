package network.lapis.cloud.server.db

import dev.kuml.erm.model.ErmDataType
import dev.kuml.erm.model.ErmModel
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import network.lapis.cloud.server.db.generated.PartgAccountMappingTable
import network.lapis.cloud.server.db.generated.PartgReportAnnexTable
import network.lapis.cloud.server.db.generated.PartgReportEnterpriseTable
import network.lapis.cloud.server.db.generated.PartgReportHoldingTable
import network.lapis.cloud.server.db.generated.PartgReportLargeDonorTable
import network.lapis.cloud.server.db.generated.PartgReportNoteTable
import network.lapis.cloud.server.db.generated.PartgReportNotesSheetTable
import network.lapis.cloud.server.db.generated.PartgReportPositionTable
import network.lapis.cloud.server.db.generated.PartgReportSheetTable
import network.lapis.cloud.server.db.generated.PartgReportSponsorTable
import network.lapis.cloud.server.db.generated.PartgReportSponsoringEntryTable
import network.lapis.cloud.server.db.generated.PartgReportUnitModeTable
import network.lapis.cloud.server.db.generated.PartgReportValueTable
import network.lapis.cloud.server.db.generated.PartgReportYouthOrgTable
import network.lapis.cloud.shared.domain.PartgBookkeepingMode
import network.lapis.cloud.shared.domain.PartgContributionKind
import network.lapis.cloud.shared.domain.PartgDeduction
import network.lapis.cloud.shared.domain.PartgKind
import network.lapis.cloud.shared.domain.PartgNoteBlock
import network.lapis.cloud.shared.domain.PartgPersonKind
import network.lapis.cloud.shared.domain.PartgSection
import network.lapis.cloud.shared.domain.PartgTriState
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

        test("the model declares the fourteen report entities plus the Member, RegionalChapter and LedgerAccount stubs") {
            model.entities.map { it.name }.toSet() shouldBe
                setOf(
                    "member",
                    "regional_chapter",
                    "partg_report_position",
                    "partg_report_sheet",
                    "partg_report_value",
                    "partg_report_annex",
                    "partg_report_large_donor",
                    "partg_report_notes_sheet",
                    "partg_report_note",
                    "partg_report_holding",
                    "partg_report_sponsor",
                    "partg_report_sponsoring_entry",
                    "partg_report_youth_org",
                    "partg_report_enterprise",
                    "ledger_account",
                    "partg_account_mapping",
                    "partg_report_unit_mode",
                )
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

        test("partg_report_annex matches the migrated schema and PartgReportAnnexTable 1:1") {
            check(
                table = "partg_report_annex",
                exposedColumns = PartgReportAnnexTable.columns.map { it.name },
                expectedForeignKeys = mapOf("regional_chapter_id" to "regional_chapter", "updated_by" to "member"),
                sqlOnlyForeignKeys = emptyMap(),
            )
        }

        test("partg_report_large_donor matches the migrated schema and PartgReportLargeDonorTable 1:1") {
            check(
                table = "partg_report_large_donor",
                exposedColumns = PartgReportLargeDonorTable.columns.map { it.name },
                expectedForeignKeys = mapOf("annex_id" to "partg_report_annex", "created_by" to "member", "updated_by" to "member"),
                sqlOnlyForeignKeys = emptyMap(),
            )
        }

        test("partg_report_notes_sheet matches the migrated schema and PartgReportNotesSheetTable 1:1") {
            check(
                table = "partg_report_notes_sheet",
                exposedColumns = PartgReportNotesSheetTable.columns.map { it.name },
                expectedForeignKeys = mapOf("updated_by" to "member"),
                sqlOnlyForeignKeys = emptyMap(),
            )
        }

        test("partg_report_note matches the migrated schema and PartgReportNoteTable 1:1") {
            check(
                table = "partg_report_note",
                exposedColumns = PartgReportNoteTable.columns.map { it.name },
                expectedForeignKeys = mapOf("notes_sheet_id" to "partg_report_notes_sheet", "updated_by" to "member"),
                sqlOnlyForeignKeys = emptyMap(),
            )
        }

        test("partg_report_holding matches the migrated schema and PartgReportHoldingTable 1:1") {
            check(
                table = "partg_report_holding",
                exposedColumns = PartgReportHoldingTable.columns.map { it.name },
                expectedForeignKeys = mapOf("notes_sheet_id" to "partg_report_notes_sheet", "updated_by" to "member"),
                sqlOnlyForeignKeys = emptyMap(),
            )
        }

        test("partg_report_sponsor matches the migrated schema and PartgReportSponsorTable 1:1") {
            check(
                table = "partg_report_sponsor",
                exposedColumns = PartgReportSponsorTable.columns.map { it.name },
                expectedForeignKeys = mapOf("annex_id" to "partg_report_annex", "created_by" to "member", "updated_by" to "member"),
                sqlOnlyForeignKeys = emptyMap(),
            )
        }

        test("partg_report_sponsoring_entry matches the migrated schema and PartgReportSponsoringEntryTable 1:1") {
            check(
                table = "partg_report_sponsoring_entry",
                exposedColumns = PartgReportSponsoringEntryTable.columns.map { it.name },
                expectedForeignKeys = mapOf("sponsor_id" to "partg_report_sponsor", "created_by" to "member", "updated_by" to "member"),
                sqlOnlyForeignKeys = emptyMap(),
            )
        }

        test("partg_report_youth_org matches the migrated schema and PartgReportYouthOrgTable 1:1") {
            check(
                table = "partg_report_youth_org",
                exposedColumns = PartgReportYouthOrgTable.columns.map { it.name },
                expectedForeignKeys = mapOf("annex_id" to "partg_report_annex", "created_by" to "member", "updated_by" to "member"),
                sqlOnlyForeignKeys = emptyMap(),
            )
        }

        test("partg_report_enterprise matches the migrated schema and PartgReportEnterpriseTable 1:1") {
            check(
                table = "partg_report_enterprise",
                exposedColumns = PartgReportEnterpriseTable.columns.map { it.name },
                expectedForeignKeys = mapOf("annex_id" to "partg_report_annex", "created_by" to "member", "updated_by" to "member"),
                sqlOnlyForeignKeys = emptyMap(),
            )
        }

        test("partg_account_mapping matches the migrated schema and PartgAccountMappingTable 1:1") {
            check(
                table = "partg_account_mapping",
                exposedColumns = PartgAccountMappingTable.columns.map { it.name },
                expectedForeignKeys = mapOf("ledger_account_id" to "ledger_account", "updated_by" to "member"),
                sqlOnlyForeignKeys = mapOf("position_code" to "partg_report_position"),
            )
            transaction { introspectPartgTable("partg_account_mapping") }.uniqueConstraints.distinct() shouldContainExactlyInAnyOrder
                listOf(setOf("ledger_account_id"))
        }

        test("partg_report_unit_mode matches the migrated schema and PartgReportUnitModeTable 1:1") {
            check(
                table = "partg_report_unit_mode",
                exposedColumns = PartgReportUnitModeTable.columns.map { it.name },
                expectedForeignKeys = mapOf("regional_chapter_id" to "regional_chapter", "updated_by" to "member"),
                sqlOnlyForeignKeys = emptyMap(),
            )
            transaction { introspectPartgTable("partg_report_unit_mode") }.uniqueConstraints shouldContainExactlyInAnyOrder
                listOf(setOf("fiscal_year", "unit_key"))
            PartgBookkeepingMode.entries.map { it.name } shouldBe listOf("A", "B", "C")
            transaction { PartgReportUnitModeTable.bookkeepingMode.columnType.sqlType() } shouldBe "VARCHAR(1)"
        }

        test("the R3b person kind of the sponsor is sized by its sqlType") {
            transaction { PartgReportSponsorTable.personKind.columnType.sqlType() } shouldBe "VARCHAR(7)"
        }

        test("the R3 enums are in exactly the order of the shared enums and sized by their sqlType") {
            PartgPersonKind.entries.map { it.name } shouldBe listOf("NATURAL", "LEGAL")
            PartgContributionKind.entries.map { it.name } shouldBe listOf("DONATION", "FEE", "BOTH")
            PartgNoteBlock.entries.map { it.name } shouldBe
                listOf("GENERAL_ACCOUNTING", "RELIEF_28", "HOLDINGS", "MEDIA_PRODUCTS", "REAL_ESTATE_VALUATION")
            PartgTriState.entries.map { it.name } shouldBe listOf("YES", "NO", "UNSPECIFIED")
            PartgDeduction.entries.map { it.name } shouldBe listOf("UNASSIGNABLE", "CASH_OVER_1000", "OVER_3300", "WRONGLY_REPORTED")
            transaction {
                listOf(
                    PartgReportLargeDonorTable.personKind.columnType.sqlType(),
                    PartgReportLargeDonorTable.contributionKind.columnType.sqlType(),
                    PartgReportNoteTable.blockCode.columnType.sqlType(),
                    PartgReportNoteTable.reliefAbs1.columnType.sqlType(),
                )
            } shouldBe listOf("VARCHAR(7)", "VARCHAR(8)", "VARCHAR(21)", "VARCHAR(11)")
            // the longest literal fits its column exactly
            PartgNoteBlock.entries.maxOf { it.name.length } shouldBe 21
            PartgContributionKind.entries.maxOf { it.name.length } shouldBe 8
        }

        test("the R3 unique rules") {
            transaction { introspectPartgTable("partg_report_annex") }.uniqueConstraints shouldContainExactlyInAnyOrder
                listOf(setOf("fiscal_year", "unit_key"))
            transaction { introspectPartgTable("partg_report_notes_sheet") }.uniqueConstraints shouldContainExactlyInAnyOrder
                listOf(setOf("fiscal_year"))
            transaction { introspectPartgTable("partg_report_note") }.uniqueConstraints shouldContainExactlyInAnyOrder
                listOf(setOf("notes_sheet_id", "block_code"))
            transaction { introspectPartgTable("partg_report_holding") }.uniqueConstraints shouldContainExactlyInAnyOrder
                listOf(setOf("notes_sheet_id", "sort_order"))
        }

        test("the enums are in exactly the order of the shared enums and sized by their sqlType") {
            PartgSection.entries.map { it.name } shouldBe listOf("INCOME", "EXPENSE", "ASSET", "LIABILITY")
            PartgKind.entries.map { it.name } shouldBe listOf("LEAF", "SUM")
            PartgUnitLevel.entries.map { it.name } shouldBe listOf("FEDERAL", "STATE", "SUBORDINATE")
            PartgValueSource.entries.map { it.name } shouldBe listOf("MANUAL", "IMPORT", "LEDGER")
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
