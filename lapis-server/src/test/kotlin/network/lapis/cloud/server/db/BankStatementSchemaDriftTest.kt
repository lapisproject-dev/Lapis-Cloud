package network.lapis.cloud.server.db

import dev.kuml.erm.model.ErmDataType
import dev.kuml.erm.model.ErmModel
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import network.lapis.cloud.server.db.generated.BankStatementImportTable
import network.lapis.cloud.server.db.generated.BankStatementLineTable
import network.lapis.cloud.server.db.generated.ContributionTable
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.io.File

/**
 * ADR-0016 (MDA persistence pipeline) -- bank-statement-import domain (Welle V1.4.5.1).
 *
 * Verifies that `lapis-server/src/main/kuml/40-bank-statement.kuml.kts` is a faithful model of
 * both (a) the real, Flyway-migrated H2 schema (`bank_statement_import`/`bank_statement_line`), and
 * (b) the hand-written `BankStatementImportTable`/`BankStatementLineTable` Exposed objects. Mirrors
 * [ContributionSchemaDriftTest]/[AuditLogSchemaDriftTest] exactly -- see [SchemaDriftTest] (foundation
 * domain) for the full designModelStrategy option B rationale this shares.
 */
class BankStatementSchemaDriftTest :
    FunSpec({
        beforeSpec { DatabaseConfig.connect() }

        val scriptFile = File(KumlModelLoader.kumlSourceDir, "40-bank-statement.kuml.kts")
        val model: ErmModel by lazy { KumlModelLoader.loadErmModel(scriptFile) }

        test("model declares exactly bank_statement_import, bank_statement_line, and the member/contribution/payment_transaction stubs") {
            model.entities.map { it.name }.toSet() shouldBe
                setOf("bank_statement_import", "bank_statement_line", "member", "contribution", "payment_transaction")
        }

        test("bank_statement_import table shape matches the real migrated schema") {
            val entity = model.entities.single { it.name == "bank_statement_import" }
            val real = transaction { introspectBankStatementTable("bank_statement_import") }
            entity.attributes.map { it.name }.toSet() shouldBe real.columns.keys
            entity.attributes.forEach { attr ->
                val col = real.columns.getValue(attr.name!!)
                withClue(clue = "column '${attr.name}'") { col.nullable shouldBe attr.nullable }
            }
            real.foreignKeys["uploaded_by"] shouldBe "member"
        }

        test("bank_statement_line table shape matches the real migrated schema") {
            val entity = model.entities.single { it.name == "bank_statement_line" }
            val real = transaction { introspectBankStatementTable("bank_statement_line") }
            entity.attributes.map { it.name }.toSet() shouldBe real.columns.keys
            entity.attributes.forEach { attr ->
                val col = real.columns.getValue(attr.name!!)
                withClue(clue = "column '${attr.name}'") { col.nullable shouldBe attr.nullable }
            }
            real.foreignKeys["import_id"] shouldBe "bank_statement_import"
            real.foreignKeys["matched_contribution_id"] shouldBe "contribution"
            real.foreignKeys["payment_transaction_id"] shouldBe "payment_transaction"
            real.foreignKeys["resolved_by"] shouldBe "member"
        }

        test("bank_statement_line.fingerprint carries a UNIQUE constraint (uq_bank_statement_line_fingerprint)") {
            val real = transaction { introspectBankStatementTable("bank_statement_line") }
            real.uniqueConstraints shouldContainExactlyInAnyOrder listOf(setOf("fingerprint"))
        }

        // Review fix (MINOR, model drift, Runde-2/3-Fund #9): the migration's own
        // uq_bank_statement_import_file_digest (V20__bank_statement_import.sql) backs the 409
        // "already imported" check with a real constraint, but this drift test previously checked
        // uniqueConstraints for bank_statement_line/contribution only -- bank_statement_import's own
        // column/nullability/FK test above never asserts on uniqueConstraints at all. A regression
        // that dropped the index (or a kUML model that never modelled it, see the "Index" stereotype
        // added to 40-bank-statement.kuml.kts alongside this test) would have gone unnoticed.
        test("bank_statement_import.file_digest carries a UNIQUE constraint (uq_bank_statement_import_file_digest)") {
            val real = transaction { introspectBankStatementTable("bank_statement_import") }
            real.uniqueConstraints shouldContainExactlyInAnyOrder listOf(setOf("file_digest"))
        }

        test("bank_statement_import entity column-name set matches the hand-written BankStatementImportTable 1:1") {
            model.entities
                .single { it.name == "bank_statement_import" }
                .attributes
                .map { it.name } shouldContainExactlyInAnyOrder BankStatementImportTable.columns.map { it.name }
        }

        test("bank_statement_line entity column-name set matches the hand-written BankStatementLineTable 1:1") {
            model.entities
                .single { it.name == "bank_statement_line" }
                .attributes
                .map { it.name } shouldContainExactlyInAnyOrder BankStatementLineTable.columns.map { it.name }
        }

        test("bank_statement_import.format and bank_statement_line.status are modelled as real ErmDataType.Enum columns") {
            val importEntity = model.entities.single { it.name == "bank_statement_import" }
            val lineEntity = model.entities.single { it.name == "bank_statement_line" }
            importEntity.attributeByName("format")?.type shouldBe
                ErmDataType.Enum(
                    name = "BankStatementFormat",
                    values = listOf("CSV", "MT940"),
                    externalFqName = "network.lapis.cloud.shared.domain.BankStatementFormat",
                )
            lineEntity.attributeByName("status")?.type shouldBe
                ErmDataType.Enum(
                    name = "BankStatementLineStatus",
                    values = listOf("UNMATCHED", "SUGGESTED", "AMBIGUOUS", "POSTED", "IGNORED"),
                    externalFqName = "network.lapis.cloud.shared.domain.BankStatementLineStatus",
                )
        }

        test("contribution.payment_reference is nullable and carries its own UNIQUE index") {
            val real = transaction { introspectBankStatementTable("contribution") }
            real.columns.getValue("payment_reference").nullable shouldBe true
            real.uniqueConstraints shouldContainExactlyInAnyOrder
                listOf(
                    setOf("member_id", "membership_tier_id", "period_start", "period_end"),
                    setOf("payment_reference"),
                )
            ContributionTable.paymentReference.name shouldBe "payment_reference"
        }
    })

private data class IntrospectedBankStatementTable(
    val columns: Map<String, IntrospectedBankStatementColumn>,
    val foreignKeys: Map<String, String>,
    val uniqueConstraints: List<Set<String>>,
)

private data class IntrospectedBankStatementColumn(
    val nullable: Boolean,
)

/** Same ANSI `information_schema` walk shape as [ContributionSchemaDriftTest]'s own `introspectContributionTable` -- see that function's KDoc. */
private fun JdbcTransaction.introspectBankStatementTable(tableName: String): IntrospectedBankStatementTable {
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

    return IntrospectedBankStatementTable(
        columns = nullableByColumn.mapValues { (_, nullable) -> IntrospectedBankStatementColumn(nullable = nullable) },
        foreignKeys = fkByColumn,
        uniqueConstraints = uniqueColumnsByConstraint.values.map { it.toSet() },
    )
}

/** Small local stand-in for Kotest's `withClue` -- mirrors [ContributionSchemaDriftTest]'s own. */
private inline fun <T> withClue(
    clue: String,
    block: () -> T,
): T =
    try {
        block()
    } catch (e: AssertionError) {
        throw AssertionError("$clue: ${e.message}", e)
    }
