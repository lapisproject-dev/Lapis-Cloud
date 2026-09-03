package network.lapis.cloud.server.db

import dev.kuml.erm.model.ErmModel
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import network.lapis.cloud.server.db.generated.CrmContactTable
import network.lapis.cloud.server.db.generated.CrmInteractionTable
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.io.File

/**
 * ADR-0016 (MDA persistence pipeline) -- Welle V1.4.2 "Interessenten-/Sympathisanten-CRM". Mirrors
 * [WebhookSchemaDriftTest]'s shape for TWO entities (`crm_contact`/`crm_interaction`) plus the
 * Member/ExternalDonor stubs.
 */
class CrmSchemaDriftTest :
    FunSpec({
        beforeSpec { DatabaseConfig.connect() }

        val scriptFile = File(KumlModelLoader.kumlSourceDir, "38-crm.kuml.kts")
        val model: ErmModel by lazy { KumlModelLoader.loadErmModel(scriptFile) }

        test("model declares exactly the crm_contact/crm_interaction entities plus the Member and ExternalDonor stubs") {
            model.entities.map { it.name }.toSet() shouldBe setOf("member", "external_donor", "crm_contact", "crm_interaction")
        }

        test("crm_contact table shape matches the real migrated schema and CrmContactTable 1:1") {
            val entity = model.entities.single { it.name == "crm_contact" }
            val real = transaction { introspectCrmTable("crm_contact") }

            entity.attributes.map { it.name }.toSet() shouldBe real.columns.keys
            entity.attributes.map { it.name } shouldContainExactlyInAnyOrder CrmContactTable.columns.map { it.name }

            real.primaryKeyColumns shouldBe setOf("id")
            real.foreignKeys["external_donor_id"] shouldBe "external_donor"
            real.foreignKeys["member_id"] shouldBe "member"
            real.foreignKeys["created_by"] shouldBe "member"

            entity.attributeByName("email")?.nullable shouldBe true
            entity.attributeByName("external_donor_id")?.nullable shouldBe true
            entity.attributeByName("member_id")?.nullable shouldBe true
            entity.attributeByName("created_by")?.nullable shouldBe false
            entity.attributeByName("retention_review_due_at")?.nullable shouldBe false
            entity.attributeByName("archived_at")?.nullable shouldBe true

            real.uniqueConstraints shouldContainExactlyInAnyOrder
                listOf(setOf("email"), setOf("external_donor_id"), setOf("member_id"))
        }

        test("crm_interaction table shape matches the real migrated schema and CrmInteractionTable 1:1") {
            val entity = model.entities.single { it.name == "crm_interaction" }
            val real = transaction { introspectCrmTable("crm_interaction") }

            entity.attributes.map { it.name }.toSet() shouldBe real.columns.keys
            entity.attributes.map { it.name } shouldContainExactlyInAnyOrder CrmInteractionTable.columns.map { it.name }

            real.primaryKeyColumns shouldBe setOf("id")
            real.foreignKeys["contact_id"] shouldBe "crm_contact"
            real.foreignKeys["recorded_by"] shouldBe "member"

            entity.attributeByName("summary")?.nullable shouldBe false
            entity.attributeByName("kind")?.nullable shouldBe false
        }

        // S11 in the vault plan's Stolperfallen list: the m2m-exposed codegen drops index {}
        // declarations, so both new indices MUST be checked against a real information_schema
        // introspection, never merely against generated Kotlin (which carries no index at all).
        test("idx_crm_contact_retention_due exists on (archived_at, retention_review_due_at) in the real migrated schema") {
            val indexColumns =
                transaction { introspectNonUniqueIndexColumns(tableName = "crm_contact", indexName = "idx_crm_contact_retention_due") }
            indexColumns shouldBe listOf("archived_at", "retention_review_due_at")
        }

        test("idx_crm_interaction_contact exists on (contact_id, occurred_at) in the real migrated schema") {
            val indexColumns =
                transaction { introspectNonUniqueIndexColumns(tableName = "crm_interaction", indexName = "idx_crm_interaction_contact") }
            indexColumns shouldBe listOf("contact_id", "occurred_at")
        }
    })

private data class IntrospectedCrmTable(
    val columns: Map<String, IntrospectedCrmColumn>,
    val foreignKeys: Map<String, String>,
    val primaryKeyColumns: Set<String>,
    val uniqueConstraints: List<Set<String>>,
)

private data class IntrospectedCrmColumn(
    val nullable: Boolean,
)

private fun JdbcTransaction.introspectCrmTable(tableName: String): IntrospectedCrmTable {
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

    val columns = nullableByColumn.mapValues { (_, nullable) -> IntrospectedCrmColumn(nullable = nullable) }
    return IntrospectedCrmTable(
        columns = columns,
        foreignKeys = fkByColumn,
        primaryKeyColumns = pkColumns,
        uniqueConstraints = uniqueColumnsByConstraint.values.map { it.toSet() },
    )
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
