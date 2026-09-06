package network.lapis.cloud.server.db

import dev.kuml.erm.model.ErmModel
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import network.lapis.cloud.server.db.generated.MemberHonorTable
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.io.File

/**
 * ADR-0016 (MDA persistence pipeline) -- Welle V1.4.4.3 "Mitgliederlebenszyklus:
 * Ehrungsverwaltung". Mirrors [CrmSchemaDriftTest]'s shape for a single entity (`member_honor`)
 * plus the Member stub.
 */
class MemberHonorSchemaDriftTest :
    FunSpec({
        beforeSpec { DatabaseConfig.connect() }

        val scriptFile = File(KumlModelLoader.kumlSourceDir, "41-member-honor.kuml.kts")
        val model: ErmModel by lazy { KumlModelLoader.loadErmModel(scriptFile) }

        test("model declares exactly the member_honor entity plus the Member stub") {
            model.entities.map { it.name }.toSet() shouldBe setOf("member", "member_honor")
        }

        test("member_honor table shape matches the real migrated schema and MemberHonorTable 1:1") {
            val entity = model.entities.single { it.name == "member_honor" }
            val real = transaction { introspectMemberHonorTable("member_honor") }

            entity.attributes.map { it.name }.toSet() shouldBe real.columns.keys
            entity.attributes.map { it.name } shouldContainExactlyInAnyOrder MemberHonorTable.columns.map { it.name }

            real.primaryKeyColumns shouldBe setOf("id")
            real.foreignKeys["member_id"] shouldBe "member"
            real.foreignKeys["recorded_by"] shouldBe "member"

            entity.attributeByName("member_id")?.nullable shouldBe false
            entity.attributeByName("category")?.nullable shouldBe false
            entity.attributeByName("title")?.nullable shouldBe false
            entity.attributeByName("awarded_at")?.nullable shouldBe false
            entity.attributeByName("awarded_by")?.nullable shouldBe true
            entity.attributeByName("note")?.nullable shouldBe true
            entity.attributeByName("recorded_by")?.nullable shouldBe false
            entity.attributeByName("recorded_at")?.nullable shouldBe false
        }

        // S11 in the vault plan's Stolperfallen list (S2 here): the m2m-exposed codegen drops
        // index {} declarations, so all three indices MUST be checked against a real
        // information_schema introspection, never merely against generated Kotlin (which carries
        // no index at all).
        test("idx_member_honor_member exists on (member_id, awarded_at) in the real migrated schema") {
            val indexColumns =
                transaction { introspectNonUniqueIndexColumns(tableName = "member_honor", indexName = "idx_member_honor_member") }
            indexColumns shouldBe listOf("member_id", "awarded_at")
        }

        test("idx_member_honor_awarded_at exists on (awarded_at) in the real migrated schema") {
            val indexColumns =
                transaction { introspectNonUniqueIndexColumns(tableName = "member_honor", indexName = "idx_member_honor_awarded_at") }
            indexColumns shouldBe listOf("awarded_at")
        }

        test("idx_member_honor_category exists on (category) in the real migrated schema") {
            val indexColumns =
                transaction { introspectNonUniqueIndexColumns(tableName = "member_honor", indexName = "idx_member_honor_category") }
            indexColumns shouldBe listOf("category")
        }
    })

private data class IntrospectedMemberHonorTable(
    val columns: Map<String, IntrospectedMemberHonorColumn>,
    val foreignKeys: Map<String, String>,
    val primaryKeyColumns: Set<String>,
)

private data class IntrospectedMemberHonorColumn(
    val nullable: Boolean,
)

private fun JdbcTransaction.introspectMemberHonorTable(tableName: String): IntrospectedMemberHonorTable {
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

    val columns = nullableByColumn.mapValues { (_, nullable) -> IntrospectedMemberHonorColumn(nullable = nullable) }
    return IntrospectedMemberHonorTable(columns = columns, foreignKeys = fkByColumn, primaryKeyColumns = pkColumns)
}

/** Column names (in `ORDINAL_POSITION` order) of a specific non-unique index -- see [CrmSchemaDriftTest]'s own "S11" tests. */
private fun JdbcTransaction.introspectNonUniqueIndexColumns(
    tableName: String,
    indexName: String,
): List<String> {
    val columns = mutableListOf<Pair<Int, String>>()
    exec(
        """
        SELECT ic.column_name, ic.ordinal_position
        FROM information_schema.index_columns ic
        WHERE ic.table_name = '$tableName' AND ic.index_name = '$indexName'
        """.trimIndent(),
    ) { rs ->
        while (rs.next()) {
            columns += rs.getInt("ordinal_position") to rs.getString("column_name")
        }
    }
    return columns.sortedBy { it.first }.map { it.second }
}
