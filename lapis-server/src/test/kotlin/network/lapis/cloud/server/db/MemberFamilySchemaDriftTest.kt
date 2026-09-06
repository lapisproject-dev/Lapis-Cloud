package network.lapis.cloud.server.db

import dev.kuml.erm.model.ErmModel
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import network.lapis.cloud.server.db.generated.MemberFamilyLinkTable
import network.lapis.cloud.server.db.generated.MemberFamilyTable
import network.lapis.cloud.shared.domain.FamilyMemberRole
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.io.File

/**
 * ADR-0016 (MDA persistence pipeline) -- Welle V1.4.4.4 "Mitgliederlebenszyklus:
 * Familienmitgliedschaften". Mirrors [MemberHonorSchemaDriftTest]'s shape for two entities
 * (`member_family`, `member_family_link`) plus the Member stub.
 */
class MemberFamilySchemaDriftTest :
    FunSpec({
        beforeSpec { DatabaseConfig.connect() }

        val scriptFile = File(KumlModelLoader.kumlSourceDir, "42-member-family.kuml.kts")
        val model: ErmModel by lazy { KumlModelLoader.loadErmModel(scriptFile) }

        test("model declares exactly member, member_family, member_family_link") {
            model.entities.map { it.name }.toSet() shouldBe setOf("member", "member_family", "member_family_link")
        }

        test("member_family table shape matches the real migrated schema and MemberFamilyTable 1:1") {
            val entity = model.entities.single { it.name == "member_family" }
            val real = transaction { introspectColumns("member_family") }

            entity.attributes.map { it.name }.toSet() shouldBe real.keys
            entity.attributes.map { it.name } shouldContainExactlyInAnyOrder MemberFamilyTable.columns.map { it.name }

            entity.attributeByName("name")?.nullable shouldBe false
            entity.attributeByName("created_by")?.nullable shouldBe false
            entity.attributeByName("created_at")?.nullable shouldBe false
        }

        test("member_family_link table shape matches the real migrated schema and MemberFamilyLinkTable 1:1") {
            val entity = model.entities.single { it.name == "member_family_link" }
            val real = transaction { introspectColumns("member_family_link") }

            entity.attributes.map { it.name }.toSet() shouldBe real.keys
            entity.attributes.map { it.name } shouldContainExactlyInAnyOrder MemberFamilyLinkTable.columns.map { it.name }

            entity.attributeByName("family_id")?.nullable shouldBe false
            entity.attributeByName("member_id")?.nullable shouldBe false
            entity.attributeByName("role")?.nullable shouldBe false
            entity.attributeByName("payer_family_id")?.nullable shouldBe true
            entity.attributeByName("linked_at")?.nullable shouldBe false
            entity.attributeByName("linked_by")?.nullable shouldBe false
        }

        test("foreign keys point at the expected tables, payer_family_id has NO FK") {
            val familyFks = transaction { introspectForeignKeys("member_family") }
            familyFks["created_by"] shouldBe "member"

            val linkFks = transaction { introspectForeignKeys("member_family_link") }
            linkFks["family_id"] shouldBe "member_family"
            linkFks["member_id"] shouldBe "member"
            linkFks["linked_by"] shouldBe "member"
            (linkFks.containsKey("payer_family_id")) shouldBe false
        }

        test("FamilyMemberRole literal order/width is pinned -- longest literal DEPENDENT (9) -> VARCHAR(9)") {
            FamilyMemberRole.entries.map { it.name } shouldBe listOf("PAYER", "DEPENDENT")
            FamilyMemberRole.entries.maxOf { it.name.length } shouldBe 9
        }

        test("uq_member_family_link_member exists and is unique, on (member_id)") {
            val index = transaction { introspectIndex(tableName = "member_family_link", indexName = "uq_member_family_link_member") }
            index.columns shouldBe listOf("member_id")
            index.unique shouldBe true
        }

        test("uq_member_family_link_payer exists and is unique, on (payer_family_id)") {
            val index = transaction { introspectIndex(tableName = "member_family_link", indexName = "uq_member_family_link_payer") }
            index.columns shouldBe listOf("payer_family_id")
            index.unique shouldBe true
        }

        test("idx_member_family_link_family exists and is NOT unique, on (family_id)") {
            val index = transaction { introspectIndex(tableName = "member_family_link", indexName = "idx_member_family_link_family") }
            index.columns shouldBe listOf("family_id")
            index.unique shouldBe false
        }
    })

private data class IndexIntrospection(
    val columns: List<String>,
    val unique: Boolean,
)

private fun JdbcTransaction.introspectColumns(tableName: String): Map<String, Boolean> {
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
    return nullableByColumn
}

private fun JdbcTransaction.introspectForeignKeys(tableName: String): Map<String, String> {
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
    return fkByColumn
}

/** Column names (in `ORDINAL_POSITION` order) plus the unique flag of a specific index -- see [MemberHonorSchemaDriftTest]'s own "S11" tests. */
private fun JdbcTransaction.introspectIndex(
    tableName: String,
    indexName: String,
): IndexIntrospection {
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
    // H2 2.x's information_schema.indexes has no boolean is_unique column -- index_type_name
    // carries 'UNIQUE INDEX' / 'INDEX' / 'PRIMARY KEY' instead (verified empirically against
    // this project's pinned H2 version; MemberHonorSchemaDriftTest's own non-unique-only helper
    // never needed this distinction).
    var unique = false
    exec(
        """
        SELECT i.index_type_name
        FROM information_schema.indexes i
        WHERE i.table_name = '$tableName' AND i.index_name = '$indexName'
        """.trimIndent(),
    ) { rs ->
        if (rs.next()) unique = rs.getString("index_type_name").equals("UNIQUE INDEX", ignoreCase = true)
    }
    return IndexIntrospection(columns = columns.sortedBy { it.first }.map { it.second }, unique = unique)
}
