package network.lapis.cloud.server.db

import dev.kuml.erm.model.ErmDataType
import dev.kuml.erm.model.ErmModel
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import network.lapis.cloud.server.db.generated.TravelExpenseAttachmentTable
import network.lapis.cloud.server.db.generated.TravelExpenseDocumentTable
import network.lapis.cloud.server.db.generated.TravelExpenseLineTable
import network.lapis.cloud.server.db.generated.TravelExpenseReceiptTable
import network.lapis.cloud.server.db.generated.TravelExpenseReportTable
import network.lapis.cloud.server.db.generated.TravelExpenseSepaBatchItemTable
import network.lapis.cloud.server.db.generated.TravelExpenseSepaBatchTable
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

        test("model declares exactly the travel-expense entities and the member/journal_entry/bank_account stubs") {
            model.entities.map { it.name }.toSet() shouldBe
                setOf(
                    "travel_expense_report",
                    "travel_expense_line",
                    "travel_expense_receipt",
                    "travel_expense_attachment",
                    "travel_expense_document",
                    "member",
                    "journal_entry",
                    "bank_account",
                    "travel_expense_sepa_batch",
                    "travel_expense_sepa_batch_item",
                )
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
            real.foreignKeys["sepa_export_batch_id"] shouldBe "travel_expense_sepa_batch"
            real.foreignKeys["sepa_exported_by"] shouldBe "member"
        }

        test("V1.9.123: the SEPA batch tables match the real migrated schema, FKs and unique indexes included") {
            val batch = model.entities.single { it.name == "travel_expense_sepa_batch" }
            val realBatch = transaction { introspectTeTable("travel_expense_sepa_batch") }
            batch.attributes.map { it.name }.toSet() shouldBe realBatch.columns.keys
            batch.attributes.forEach { attr ->
                withClue(clue = "batch column '${attr.name}'") { realBatch.columns.getValue(attr.name!!).nullable shouldBe attr.nullable }
            }
            realBatch.foreignKeys["debtor_bank_account_id"] shouldBe "bank_account"
            realBatch.foreignKeys["created_by"] shouldBe "member"
            val item = model.entities.single { it.name == "travel_expense_sepa_batch_item" }
            val realItem = transaction { introspectTeTable("travel_expense_sepa_batch_item") }
            item.attributes.map { it.name }.toSet() shouldBe realItem.columns.keys
            item.attributes.forEach { attr ->
                withClue(clue = "item column '${attr.name}'") { realItem.columns.getValue(attr.name!!).nullable shouldBe attr.nullable }
            }
            realItem.foreignKeys["batch_id"] shouldBe "travel_expense_sepa_batch"
            realItem.foreignKeys["report_id"] shouldBe "travel_expense_report"
            // end_to_end_id is the only SINGLE-column unique index; (batch_id, report_id) is a two-column one.
            realItem.uniqueSingleColumns shouldContainExactlyInAnyOrder listOf("end_to_end_id")
        }

        test("V1.9.123: the refund-account and export columns have the modelled nullability") {
            val entity = model.entities.single { it.name == "travel_expense_report" }
            listOf(
                "refund_iban_ciphertext",
                "refund_iban_last4",
                "refund_account_holder",
                "sepa_exported_at",
                "sepa_export_batch_id",
                "sepa_exported_by",
            ).forEach { name -> withClue(clue = "column '$name'") { entity.attributeByName(name)?.nullable shouldBe true } }
            listOf("refund_account_changed_after_submit", "sepa_reexport_count", "paid_outside_lapis").forEach { name ->
                withClue(clue = "column '$name'") { entity.attributeByName(name)?.nullable shouldBe false }
            }
        }

        test("V1.9.123: the generated batch Table objects match the modelled column sets 1:1") {
            model.entities
                .single { it.name == "travel_expense_sepa_batch" }
                .attributes
                .map { it.name } shouldContainExactlyInAnyOrder
                TravelExpenseSepaBatchTable.columns.map { it.name }
            model.entities
                .single { it.name == "travel_expense_sepa_batch_item" }
                .attributes
                .map { it.name } shouldContainExactlyInAnyOrder
                TravelExpenseSepaBatchItemTable.columns.map { it.name }
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

        test("travel_expense_attachment table shape matches the real migrated schema") {
            val entity = model.entities.single { it.name == "travel_expense_attachment" }
            val real = transaction { introspectTeTable("travel_expense_attachment") }
            entity.attributes.map { it.name }.toSet() shouldBe real.columns.keys
            real.foreignKeys["report_id"] shouldBe "travel_expense_report"
            real.foreignKeys["uploaded_by"] shouldBe "member"
            real.uniqueSingleColumns shouldContainExactlyInAnyOrder listOf("storage_key")
            model.entities
                .single { it.name == "travel_expense_attachment" }
                .attributes
                .map { it.name } shouldContainExactlyInAnyOrder
                TravelExpenseAttachmentTable.columns.map { it.name }
        }

        test("V1.9.199: travel_expense_document table shape matches the real migrated schema (uploaded_by is nullable)") {
            val entity = model.entities.single { it.name == "travel_expense_document" }
            val real = transaction { introspectTeTable("travel_expense_document") }
            entity.attributes.map { it.name }.toSet() shouldBe real.columns.keys
            entity.attributes.forEach { attr ->
                withClue(clue = "column '${attr.name}'") { real.columns.getValue(attr.name!!).nullable shouldBe attr.nullable }
            }
            real.foreignKeys["uploaded_by"] shouldBe "member"
            real.columns.getValue("uploaded_by").nullable shouldBe true
            real.uniqueSingleColumns shouldContainExactlyInAnyOrder listOf("storage_key")
            entity.attributes.map { it.name } shouldContainExactlyInAnyOrder TravelExpenseDocumentTable.columns.map { it.name }
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
                    values =
                        listOf(
                            "MILEAGE",
                            "PER_DIEM",
                            "RECEIPTED",
                            "DAY_ALLOWANCE",
                            "PUBLIC_TRANSPORT",
                            "ACCOMMODATION",
                            "HOSPITALITY",
                            "OTHER",
                        ),
                    externalFqName = "network.lapis.cloud.shared.domain.TravelExpenseLineKind",
                )
        }

        test("V1.9.122: payout_choice is a nullable real enum column, advance_amount is NOT NULL") {
            val entity = model.entities.single { it.name == "travel_expense_report" }
            entity.attributeByName("payout_choice")?.type shouldBe
                ErmDataType.Enum(
                    name = "TravelExpensePayoutChoice",
                    values = listOf("PAYOUT", "DONATION_WAIVER"),
                    externalFqName = "network.lapis.cloud.shared.domain.TravelExpensePayoutChoice",
                )
            entity.attributeByName("payout_choice")?.nullable shouldBe true
            entity.attributeByName("advance_amount")?.nullable shouldBe false
            entity.attributeByName("membership_number_snapshot")?.nullable shouldBe true
            entity.attributeByName("rates_snapshot")?.nullable shouldBe true
        }

        test("V1.9.122: new travel_expense_line columns have the modelled nullability") {
            val entity = model.entities.single { it.name == "travel_expense_line" }
            listOf(
                "destination",
                "day_date",
                "time_from",
                "time_to",
                "passenger_count",
                "breakfast_included",
                "computed_minutes",
                "base_amount",
            ).forEach { name ->
                withClue(clue = "column '$name'") { entity.attributeByName(name)?.nullable shouldBe true }
            }
            listOf("breakfast_provided", "lunch_provided", "dinner_provided", "deduction_amount").forEach { name ->
                withClue(clue = "column '$name'") { entity.attributeByName(name)?.nullable shouldBe false }
            }
        }

        test("V1.9.122: idx_tel_report_day is NOT a unique index (one-day-per-report is an application rule)") {
            val lineReal = transaction { introspectTeTable("travel_expense_line") }
            lineReal.uniqueSingleColumns shouldContainExactlyInAnyOrder emptyList()
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
