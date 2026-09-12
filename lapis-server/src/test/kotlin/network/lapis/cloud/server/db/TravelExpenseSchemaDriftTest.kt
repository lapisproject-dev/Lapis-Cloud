package network.lapis.cloud.server.db

import dev.kuml.erm.model.ErmDataType
import dev.kuml.erm.model.ErmModel
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import network.lapis.cloud.server.db.generated.TravelExpenseLineTable
import network.lapis.cloud.server.db.generated.TravelExpenseReceiptTable
import network.lapis.cloud.server.db.generated.TravelExpenseReportTable
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.io.File

/**
 * Welle V1.4.11 "Reisekostenabrechnung für Vorstand und Funktionsträger" -- mirrors
 * [ContributionReliefSchemaDriftTest]'s structure exactly for the three new entities.
 */
class TravelExpenseSchemaDriftTest :
    FunSpec({
        beforeSpec { DatabaseConfig.connect() }

        val scriptFile = File(KumlModelLoader.kumlSourceDir, "45-travel-expense.kuml.kts")
        val model: ErmModel by lazy { KumlModelLoader.loadErmModel(scriptFile) }

        test("model declares exactly the three travel-expense entities and the member/journal_entry stubs") {
            model.entities.map { it.name }.toSet() shouldBe
                setOf("travel_expense_report", "travel_expense_line", "travel_expense_receipt", "member", "journal_entry")
        }

        test("travel_expense_report table shape matches the real migrated schema") {
            val entity = model.entities.single { it.name == "travel_expense_report" }
            val real = transaction { introspectTeTable("travel_expense_report") }
            entity.attributes.map { it.name }.toSet() shouldBe real.columns.keys
            entity.attributes.forEach { attr ->
                val col = real.columns.getValue(attr.name!!)
                withClue(clue = "column '${attr.name}'") { col.nullable shouldBe attr.nullable }
            }
            real.foreignKeys["subject_member_id"] shouldBe "member"
            real.foreignKeys["requested_by"] shouldBe "member"
            real.foreignKeys["decided_by"] shouldBe "member"
            real.foreignKeys["posted_journal_entry_id"] shouldBe "journal_entry"
        }

        test("travel_expense_line table shape matches the real migrated schema") {
            val entity = model.entities.single { it.name == "travel_expense_line" }
            val real = transaction { introspectTeTable("travel_expense_line") }
            entity.attributes.map { it.name }.toSet() shouldBe real.columns.keys
            real.foreignKeys["report_id"] shouldBe "travel_expense_report"
        }

        test("travel_expense_receipt table shape matches the real migrated schema") {
            val entity = model.entities.single { it.name == "travel_expense_receipt" }
            val real = transaction { introspectTeTable("travel_expense_receipt") }
            entity.attributes.map { it.name }.toSet() shouldBe real.columns.keys
            real.foreignKeys["line_id"] shouldBe "travel_expense_line"
            real.foreignKeys["uploaded_by"] shouldBe "member"
        }

        test("uq_ter_posted_journal_entry and uq_terc_storage_key are PLAIN (non-partial) unique indexes") {
            val reportReal = transaction { introspectTeTable("travel_expense_report") }
            reportReal.uniqueSingleColumns shouldContainExactlyInAnyOrder listOf("posted_journal_entry_id")
            val receiptReal = transaction { introspectTeTable("travel_expense_receipt") }
            receiptReal.uniqueSingleColumns shouldContainExactlyInAnyOrder listOf("storage_key")
        }

        test("generated *Table objects match the modelled column-name sets 1:1") {
            model.entities
                .single { it.name == "travel_expense_report" }
                .attributes
                .map { it.name } shouldContainExactlyInAnyOrder
                TravelExpenseReportTable.columns.map { it.name }
            model.entities
                .single { it.name == "travel_expense_line" }
                .attributes
                .map { it.name } shouldContainExactlyInAnyOrder
                TravelExpenseLineTable.columns.map { it.name }
            model.entities
                .single { it.name == "travel_expense_receipt" }
                .attributes
                .map { it.name } shouldContainExactlyInAnyOrder
                TravelExpenseReceiptTable.columns.map { it.name }
        }

        test("status/kind are modelled as real ErmDataType.Enum columns, literal order pinned") {
            val reportEntity = model.entities.single { it.name == "travel_expense_report" }
            reportEntity.attributeByName("status")?.type shouldBe
                ErmDataType.Enum(
                    name = "TravelExpenseReportStatus",
                    values = listOf("DRAFT", "REQUESTED", "APPROVED", "REJECTED", "EXECUTED", "WITHDRAWN"),
                    externalFqName = "network.lapis.cloud.shared.domain.TravelExpenseReportStatus",
                )
            val lineEntity = model.entities.single { it.name == "travel_expense_line" }
            lineEntity.attributeByName("kind")?.type shouldBe
                ErmDataType.Enum(
                    name = "TravelExpenseLineKind",
                    values = listOf("MILEAGE", "PER_DIEM", "RECEIPTED"),
                    externalFqName = "network.lapis.cloud.shared.domain.TravelExpenseLineKind",
                )
        }

        test("subject_member_id/status/purpose/travel_from/travel_to/total_amount/created_at/requested_by are NOT NULL") {
            val entity = model.entities.single { it.name == "travel_expense_report" }
            listOf(
                "subject_member_id",
                "status",
                "purpose",
                "travel_from",
                "travel_to",
                "total_amount",
                "created_at",
                "requested_by",
            ).forEach { name ->
                withClue(clue = "column '$name'") { entity.attributeByName(name)?.nullable shouldBe false }
            }
        }
    })

private data class IntrospectedTeTable(
    val columns: Map<String, IntrospectedTeColumn>,
    val foreignKeys: Map<String, String>,
    val uniqueSingleColumns: List<String>,
)

private data class IntrospectedTeColumn(
    val nullable: Boolean,
)

/** Mirrors [ContributionReliefSchemaDriftTest]'s private `introspectCrrTable`, generalized to any table name. */
private fun JdbcTransaction.introspectTeTable(tableName: String): IntrospectedTeTable {
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
            uniqueColumnsByConstraint
                .getOrPut(rs.getString("name")) { mutableSetOf() }
                .add(rs.getString("column_name"))
        }
    }

    val columns = nullableByColumn.mapValues { (_, nullable) -> IntrospectedTeColumn(nullable = nullable) }
    val uniqueSingleColumns = uniqueColumnsByConstraint.values.filter { it.size == 1 }.map { it.single() }
    return IntrospectedTeTable(columns = columns, foreignKeys = fkByColumn, uniqueSingleColumns = uniqueSingleColumns)
}

/** Small local stand-in for Kotest's `withClue` -- mirrors [ContributionReliefSchemaDriftTest]'s own. */
private inline fun <T> withClue(
    clue: String,
    block: () -> T,
): T =
    try {
        block()
    } catch (e: AssertionError) {
        throw AssertionError("$clue: ${e.message}", e)
    }
