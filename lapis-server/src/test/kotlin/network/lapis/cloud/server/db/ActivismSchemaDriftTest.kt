package network.lapis.cloud.server.db

import dev.kuml.erm.model.ErmModel
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import network.lapis.cloud.server.db.generated.ActivismActionTable
import network.lapis.cloud.server.db.generated.ActivismContributionTable
import network.lapis.cloud.server.db.generated.ActivismMaterialDocumentTable
import network.lapis.cloud.server.db.generated.ActivismNoticeTextTable
import network.lapis.cloud.server.db.generated.ActivismReportTable
import network.lapis.cloud.server.db.generated.ActivismResponsibleTable
import network.lapis.cloud.server.db.generated.ActivismSignalTable
import network.lapis.cloud.server.db.generated.ActivismTaskTable
import network.lapis.cloud.server.db.generated.ActivismTextBlockTable
import network.lapis.cloud.server.db.generated.OrganizationSettingsTable
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.io.File

/**
 * ADR-0016 (MDA persistence pipeline) -- Activism (V1.9.246). Verifies that `80-activism.kuml.kts` (and the two new columns of `organization_settings`
 * in `11-organization-settings.kuml.kts`) are a faithful model of the real, Flyway-migrated H2 schema (V133) and of the Exposed objects, column for column.
 */
class ActivismSchemaDriftTest :
    FunSpec({
        beforeSpec { DatabaseConfig.connect() }

        val model: ErmModel by lazy { KumlModelLoader.loadErmModel(File(KumlModelLoader.kumlSourceDir, "80-activism.kuml.kts")) }
        val settingsModel: ErmModel by lazy {
            KumlModelLoader.loadErmModel(File(KumlModelLoader.kumlSourceDir, "11-organization-settings.kuml.kts"))
        }

        fun ErmModel.entityNameOf(entityId: String): String? = entities.firstOrNull { it.id == entityId }?.name

        test("the model declares the nine activism tables plus the four id-only stubs") {
            model.entities.map { it.name }.toSet() shouldBe
                setOf(
                    "activism_action",
                    "activism_responsible",
                    "activism_task",
                    "activism_text_block",
                    "activism_material_document",
                    "activism_contribution",
                    "activism_signal",
                    "activism_report",
                    "activism_notice_text",
                    "member",
                    "cost_center",
                    "event",
                    "document",
                )
        }

        fun check(
            table: String,
            exposedColumns: List<String>,
            expectedForeignKeys: Map<String, String>,
        ) {
            val entity = model.entities.single { it.name == table }
            val real = transaction { introspectActivismTable(table) }
            entity.attributes.map { it.name }.toSet() shouldBe real.columns.keys
            entity.attributes.forEach { attr ->
                withClue("column '${attr.name}' of $table") { real.columns.getValue(attr.name!!).nullable shouldBe attr.nullable }
            }
            entity.attributes.map { it.name } shouldContainExactlyInAnyOrder exposedColumns
            real.primaryKeyColumns shouldBe setOf("id")
            entity.attributes.filter { it.foreignKey != null }.associate {
                it.name!! to model.entityNameOf(it.foreignKey!!.targetEntityId)
            } shouldBe expectedForeignKeys
            real.foreignKeys shouldBe expectedForeignKeys
        }

        test("activism_action matches the migrated schema and its Exposed object 1:1") {
            check(
                "activism_action",
                ActivismActionTable.columns.map { it.name },
                mapOf(
                    "cost_center_id" to "cost_center",
                    "event_id" to "event",
                    "created_by" to "member",
                    "approved_by" to "member",
                ),
            )
        }

        test("activism_responsible matches the migrated schema and its Exposed object 1:1") {
            check(
                "activism_responsible",
                ActivismResponsibleTable.columns.map { it.name },
                mapOf(
                    "action_id" to "activism_action",
                    "member_id" to "member",
                ),
            )
        }

        test("activism_task matches the migrated schema and its Exposed object 1:1") {
            check(
                "activism_task",
                ActivismTaskTable.columns.map { it.name },
                mapOf(
                    "action_id" to "activism_action",
                    "assignee_member_id" to "member",
                    "done_by" to "member",
                ),
            )
        }

        test("activism_text_block matches the migrated schema and its Exposed object 1:1") {
            check("activism_text_block", ActivismTextBlockTable.columns.map { it.name }, mapOf("action_id" to "activism_action"))
        }

        test("activism_material_document matches the migrated schema and its Exposed object 1:1") {
            check(
                "activism_material_document",
                ActivismMaterialDocumentTable.columns.map { it.name },
                mapOf(
                    "action_id" to "activism_action",
                    "document_id" to "document",
                ),
            )
        }

        test("activism_contribution matches the migrated schema and its Exposed object 1:1") {
            check(
                "activism_contribution",
                ActivismContributionTable.columns.map { it.name },
                mapOf(
                    "action_id" to "activism_action",
                    "member_id" to "member",
                    "decided_by" to "member",
                ),
            )
        }

        test("activism_signal matches the migrated schema and its Exposed object 1:1") {
            check(
                "activism_signal",
                ActivismSignalTable.columns.map { it.name },
                mapOf(
                    "action_id" to "activism_action",
                    "member_id" to "member",
                ),
            )
        }

        test("activism_report matches the migrated schema and its Exposed object 1:1") {
            check(
                "activism_report",
                ActivismReportTable.columns.map { it.name },
                mapOf(
                    "action_id" to "activism_action",
                    "reporter_member_id" to "member",
                    "resolved_by" to "member",
                ),
            )
        }

        test("activism_notice_text matches the migrated schema and its Exposed object 1:1") {
            check("activism_notice_text", ActivismNoticeTextTable.columns.map { it.name }, mapOf("updated_by" to "member"))
        }

        test("the two activism columns of organization_settings exist in the model, the table and the Exposed object") {
            val settings = settingsModel.entities.single { it.name == "organization_settings" }
            val real = transaction { introspectActivismTable("organization_settings") }
            listOf("activism_review_required", "activism_notice_country").forEach { column ->
                withClue("column $column") {
                    settings.attributes.first { it.name == column }.nullable shouldBe false
                    real.columns.getValue(column).nullable shouldBe false
                    OrganizationSettingsTable.columns.any { it.name == column } shouldBe true
                }
            }
        }

        test("the pairs of the association tables and the notice key are unique") {
            fun unique(table: String) = transaction { introspectActivismTable(table) }.uniqueConstraints
            unique("activism_responsible").contains(setOf("action_id", "member_id")) shouldBe true
            unique("activism_signal").contains(setOf("action_id", "member_id")) shouldBe true
            unique("activism_material_document").contains(setOf("action_id", "document_id")) shouldBe true
            unique("activism_notice_text").contains(setOf("country_code", "kind")) shouldBe true
            unique("activism_task").contains(setOf("action_id", "position")) shouldBe true
        }

        test("activism_signal has no time, text or photo column") {
            transaction { introspectActivismTable("activism_signal") }.columns.keys shouldBe setOf("id", "action_id", "member_id")
        }
    })

/** Result of introspecting one real table's shape via `information_schema`. */
private data class IntrospectedActivismTable(
    val columns: Map<String, IntrospectedActivismColumn>,
    val foreignKeys: Map<String, String>,
    val primaryKeyColumns: Set<String>,
    val uniqueConstraints: List<Set<String>>,
)

private data class IntrospectedActivismColumn(
    val nullable: Boolean,
)

private fun JdbcTransaction.introspectActivismTable(tableName: String): IntrospectedActivismTable {
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

    val columns = nullableByColumn.mapValues { (_, nullable) -> IntrospectedActivismColumn(nullable = nullable) }
    return IntrospectedActivismTable(
        columns = columns,
        foreignKeys = fkByColumn,
        primaryKeyColumns = pkColumns,
        uniqueConstraints = uniqueColumnsByConstraint.values.map { it.toSet() },
    )
}
