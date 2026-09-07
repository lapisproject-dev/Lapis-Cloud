package network.lapis.cloud.server.db

import dev.kuml.erm.model.ErmDataType
import dev.kuml.erm.model.ErmModel
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import network.lapis.cloud.server.db.generated.AccountingExportCategoryMapTable
import network.lapis.cloud.server.db.generated.AccountingExportConnectionTable
import network.lapis.cloud.server.db.generated.AccountingExportItemTable
import network.lapis.cloud.server.db.generated.AccountingExportRunTable
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.io.File

/**
 * ADR-0016 (MDA persistence pipeline) -- lexoffice-live-export domain (Welle V1.4.5.3).
 *
 * Verifies that `lapis-server/src/main/kuml/43-accounting-export.kuml.kts` is a faithful model of
 * both (a) the real, Flyway-migrated H2 schema, and (b) the hand-written
 * `AccountingExportConnectionTable`/`AccountingExportRunTable`/`AccountingExportItemTable`/
 * `AccountingExportCategoryMapTable` Exposed objects. Mirrors [BankStatementSchemaDriftTest] exactly.
 */
class AccountingExportSchemaDriftTest :
    FunSpec({
        beforeSpec { DatabaseConfig.connect() }

        val scriptFile = File(KumlModelLoader.kumlSourceDir, "43-accounting-export.kuml.kts")
        val model: ErmModel by lazy { KumlModelLoader.loadErmModel(scriptFile) }

        test("model declares exactly the four real tables plus the member/journal_entry/ledger_account stubs") {
            model.entities.map { it.name }.toSet() shouldBe
                setOf(
                    "accounting_export_connection",
                    "accounting_export_run",
                    "accounting_export_item",
                    "accounting_export_category_map",
                    "member",
                    "journal_entry",
                    "ledger_account",
                )
        }

        test("accounting_export_connection table shape matches the real migrated schema") {
            val entity = model.entities.single { it.name == "accounting_export_connection" }
            val real = transaction { introspectAccountingExportTable("accounting_export_connection") }
            entity.attributes.map { it.name }.toSet() shouldBe real.columns.keys
            entity.attributes.forEach { attr ->
                val col = real.columns.getValue(attr.name!!)
                withClue(clue = "column '${attr.name}'") { col.nullable shouldBe attr.nullable }
            }
            real.foreignKeys["zero_vat_acknowledged_by"] shouldBe "member"
            real.uniqueConstraints shouldContainExactlyInAnyOrder listOf(setOf("provider"))
        }

        test("accounting_export_run table shape matches the real migrated schema") {
            val entity = model.entities.single { it.name == "accounting_export_run" }
            val real = transaction { introspectAccountingExportTable("accounting_export_run") }
            entity.attributes.map { it.name }.toSet() shouldBe real.columns.keys
            entity.attributes.forEach { attr ->
                val col = real.columns.getValue(attr.name!!)
                withClue(clue = "column '${attr.name}'") { col.nullable shouldBe attr.nullable }
            }
            real.foreignKeys["started_by"] shouldBe "member"
            // Portability note (see 43-accounting-export.kuml.kts file header): active_key is a
            // plain UNIQUE index, not a partial one -- multiple NULLs are allowed under it.
            real.uniqueConstraints shouldContainExactlyInAnyOrder listOf(setOf("active_key"))
        }

        test("accounting_export_item table shape matches the real migrated schema") {
            val entity = model.entities.single { it.name == "accounting_export_item" }
            val real = transaction { introspectAccountingExportTable("accounting_export_item") }
            entity.attributes.map { it.name }.toSet() shouldBe real.columns.keys
            entity.attributes.forEach { attr ->
                val col = real.columns.getValue(attr.name!!)
                withClue(clue = "column '${attr.name}'") { col.nullable shouldBe attr.nullable }
            }
            real.foreignKeys["run_id"] shouldBe "accounting_export_run"
            real.foreignKeys["journal_entry_id"] shouldBe "journal_entry"
            real.uniqueConstraints shouldContainExactlyInAnyOrder listOf(setOf("exported_key"))
        }

        test("accounting_export_category_map table shape matches the real migrated schema") {
            val entity = model.entities.single { it.name == "accounting_export_category_map" }
            val real = transaction { introspectAccountingExportTable("accounting_export_category_map") }
            entity.attributes.map { it.name }.toSet() shouldBe real.columns.keys
            entity.attributes.forEach { attr ->
                val col = real.columns.getValue(attr.name!!)
                withClue(clue = "column '${attr.name}'") { col.nullable shouldBe attr.nullable }
            }
            real.foreignKeys["ledger_account_id"] shouldBe "ledger_account"
            real.foreignKeys["mapped_by"] shouldBe "member"
            real.uniqueConstraints shouldContainExactlyInAnyOrder listOf(setOf("provider", "ledger_account_id"))
        }

        test("every entity column-name set matches its hand-written Table object 1:1") {
            model.entities
                .single { it.name == "accounting_export_connection" }
                .attributes
                .map { it.name } shouldContainExactlyInAnyOrder AccountingExportConnectionTable.columns.map { it.name }
            model.entities
                .single { it.name == "accounting_export_run" }
                .attributes
                .map { it.name } shouldContainExactlyInAnyOrder AccountingExportRunTable.columns.map { it.name }
            model.entities
                .single { it.name == "accounting_export_item" }
                .attributes
                .map { it.name } shouldContainExactlyInAnyOrder AccountingExportItemTable.columns.map { it.name }
            model.entities
                .single { it.name == "accounting_export_category_map" }
                .attributes
                .map { it.name } shouldContainExactlyInAnyOrder AccountingExportCategoryMapTable.columns.map { it.name }
        }

        test("enum columns are modelled as real ErmDataType.Enum columns with the exact pinned literal order") {
            val connectionEntity = model.entities.single { it.name == "accounting_export_connection" }
            val runEntity = model.entities.single { it.name == "accounting_export_run" }
            val itemEntity = model.entities.single { it.name == "accounting_export_item" }

            connectionEntity.attributeByName("provider")?.type shouldBe
                ErmDataType.Enum(
                    name = "AccountingExportProvider",
                    values = listOf("LEXOFFICE", "SEVDESK"),
                    externalFqName = "network.lapis.cloud.shared.domain.AccountingExportProvider",
                )
            runEntity.attributeByName("status")?.type shouldBe
                ErmDataType.Enum(
                    name = "AccountingExportRunStatus",
                    values = listOf("PLANNED", "RUNNING", "COMPLETED", "COMPLETED_WITH_ERRORS", "ABORTED"),
                    externalFqName = "network.lapis.cloud.shared.domain.AccountingExportRunStatus",
                )
            itemEntity.attributeByName("status")?.type shouldBe
                ErmDataType.Enum(
                    name = "AccountingExportItemStatus",
                    values = listOf("PENDING", "SENDING", "SUCCEEDED", "FAILED", "SKIPPED_ALREADY_EXPORTED", "UNKNOWN"),
                    externalFqName = "network.lapis.cloud.shared.domain.AccountingExportItemStatus",
                )
            itemEntity.attributeByName("direction")?.type shouldBe
                ErmDataType.Enum(
                    name = "AccountingExportDirection",
                    values = listOf("INCOME", "EXPENSE"),
                    externalFqName = "network.lapis.cloud.shared.domain.AccountingExportDirection",
                )
        }
    })

private data class IntrospectedAccountingExportTable(
    val columns: Map<String, IntrospectedAccountingExportColumn>,
    val foreignKeys: Map<String, String>,
    val uniqueConstraints: List<Set<String>>,
)

private data class IntrospectedAccountingExportColumn(
    val nullable: Boolean,
)

/** Same ANSI `information_schema` walk shape as [BankStatementSchemaDriftTest]'s own
 * `introspectBankStatementTable` -- see that function's KDoc. */
private fun JdbcTransaction.introspectAccountingExportTable(tableName: String): IntrospectedAccountingExportTable {
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
            uniqueColumnsByConstraint.getOrPut(rs.getString("name")) { mutableSetOf() }.add(rs.getString("column_name"))
        }
    }

    return IntrospectedAccountingExportTable(
        columns = nullableByColumn.mapValues { (_, nullable) -> IntrospectedAccountingExportColumn(nullable = nullable) },
        foreignKeys = fkByColumn,
        uniqueConstraints = uniqueColumnsByConstraint.values.map { it.toSet() },
    )
}

/** Small local stand-in for Kotest's `withClue` -- mirrors [BankStatementSchemaDriftTest]'s own. */
private inline fun <T> withClue(
    clue: String,
    block: () -> T,
): T =
    try {
        block()
    } catch (e: AssertionError) {
        throw AssertionError("$clue: ${e.message}", e)
    }
