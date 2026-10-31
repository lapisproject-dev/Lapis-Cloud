package network.lapis.cloud.server.db

import dev.kuml.erm.model.ErmModel
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import network.lapis.cloud.server.db.generated.OrganizationSettingsTable
import network.lapis.cloud.server.db.generated.PartnerBlocklistTable
import network.lapis.cloud.server.db.generated.PartnerDiscoveredServerTable
import network.lapis.cloud.server.db.generated.PartnerEventTable
import network.lapis.cloud.server.db.generated.PartnerServerTable
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.io.File

/**
 * ADR-0016 (MDA persistence pipeline) -- Partner servers (V1.9.244). Verifies that `79-partner-servers.kuml.kts` (and the two new columns of
 * `organization_settings` in `11-organization-settings.kuml.kts`) are a faithful model of the real, Flyway-migrated H2 schema (V131) and of the
 * Exposed objects, column for column.
 */
class PartnerServerSchemaDriftTest :
    FunSpec({
        beforeSpec { DatabaseConfig.connect() }

        val model: ErmModel by lazy { KumlModelLoader.loadErmModel(File(KumlModelLoader.kumlSourceDir, "79-partner-servers.kuml.kts")) }
        val settingsModel: ErmModel by lazy {
            KumlModelLoader.loadErmModel(File(KumlModelLoader.kumlSourceDir, "11-organization-settings.kuml.kts"))
        }

        fun ErmModel.entityNameOf(entityId: String): String? = entities.firstOrNull { it.id == entityId }?.name

        test("the model declares exactly the four partner tables") {
            model.entities.map { it.name }.toSet() shouldBe
                setOf("partner_server", "partner_event", "partner_discovered_server", "partner_blocklist")
        }

        fun check(
            table: String,
            exposedColumns: List<String>,
            expectedForeignKeys: Map<String, String>,
            pk: Set<String>,
        ) {
            val entity = model.entities.single { it.name == table }
            val real = transaction { introspectPartnerTable(table) }
            entity.attributes.map { it.name }.toSet() shouldBe real.columns.keys
            entity.attributes.forEach { attr ->
                withClue("column '${attr.name}' of $table") { real.columns.getValue(attr.name!!).nullable shouldBe attr.nullable }
            }
            entity.attributes.map { it.name } shouldContainExactlyInAnyOrder exposedColumns
            real.primaryKeyColumns shouldBe pk
            entity.attributes.filter { it.foreignKey != null }.associate {
                it.name!! to model.entityNameOf(it.foreignKey!!.targetEntityId)
            } shouldBe expectedForeignKeys
            real.foreignKeys shouldBe expectedForeignKeys
        }

        test("partner_server matches the migrated schema and its Exposed object 1:1") {
            check("partner_server", PartnerServerTable.columns.map { it.name }, emptyMap(), setOf("id"))
        }

        test("partner_event matches the migrated schema and its Exposed object 1:1, with the FK to partner_server") {
            check(
                "partner_event",
                PartnerEventTable.columns.map { it.name },
                mapOf("partner_server_id" to "partner_server", "discovered_server_id" to "partner_discovered_server"),
                setOf("id"),
            )
        }

        test("partner_discovered_server matches the migrated schema and its Exposed object 1:1, rooted at partner_server") {
            check(
                "partner_discovered_server",
                PartnerDiscoveredServerTable.columns.map { it.name },
                mapOf("root_partner_server_id" to "partner_server"),
                setOf("id"),
            )
        }

        test("partner_blocklist matches the migrated schema and its Exposed object 1:1") {
            check("partner_blocklist", PartnerBlocklistTable.columns.map { it.name }, emptyMap(), setOf("id"))
        }

        test("the two partner columns of organization_settings exist in the model, the table and the Exposed object") {
            val settings = settingsModel.entities.single { it.name == "organization_settings" }
            val real = transaction { introspectPartnerTable("organization_settings") }
            settings.attributes.first { it.name == "partner_list_hidden" }.nullable shouldBe false
            settings.attributes.first { it.name == "partner_server_id" }.nullable shouldBe true
            real.columns.getValue("partner_list_hidden").nullable shouldBe false
            real.columns.getValue("partner_server_id").nullable shouldBe true
            OrganizationSettingsTable.columns.map { it.name }.containsAll(listOf("partner_list_hidden", "partner_server_id")) shouldBe true
            listOf(
                "partner_recursion_enabled",
                "partner_recursion_depth",
                "partner_recursion_last_run_at",
                "partner_recursion_last_summary",
            ).forEach { column ->
                withClue("column $column") {
                    settings.attributes.any { it.name == column } shouldBe true
                    real.columns.containsKey(column) shouldBe true
                    OrganizationSettingsTable.columns.any { it.name == column } shouldBe true
                }
            }
            real.columns.getValue("partner_recursion_enabled").nullable shouldBe false
            real.columns.getValue("partner_recursion_depth").nullable shouldBe false
            real.columns.getValue("partner_recursion_last_run_at").nullable shouldBe true
        }

        test("host and server id are unique on partner_server; a foreign event exists once per announced server id and event id (V132)") {
            val server = transaction { introspectPartnerTable("partner_server") }.uniqueConstraints
            server.contains(setOf("host")) shouldBe true
            server.contains(setOf("remote_server_id")) shouldBe true
            transaction {
                introspectPartnerTable(
                    "partner_event",
                )
            }.uniqueConstraints.contains(setOf("remote_server_id", "remote_event_id")) shouldBe
                true
            val discovered = transaction { introspectPartnerTable("partner_discovered_server") }.uniqueConstraints
            discovered.contains(setOf("host")) shouldBe true
            discovered.contains(setOf("remote_server_id")) shouldBe true
            transaction { introspectPartnerTable("partner_blocklist") }.uniqueConstraints.contains(setOf("pattern")) shouldBe true
        }

        test("the model keeps the attributes in the order of the table") {
            model.entities
                .single { it.name == "partner_event" }
                .attributes
                .map { it.name } shouldContainExactly
                PartnerEventTable.columns.map { it.name }
        }
    })

/** Result of introspecting one real table's shape via `information_schema`. */
private data class IntrospectedPartnerTable(
    val columns: Map<String, IntrospectedPartnerColumn>,
    val foreignKeys: Map<String, String>,
    val primaryKeyColumns: Set<String>,
    val uniqueConstraints: List<Set<String>>,
)

private data class IntrospectedPartnerColumn(
    val nullable: Boolean,
)

private fun JdbcTransaction.introspectPartnerTable(tableName: String): IntrospectedPartnerTable {
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

    val columns = nullableByColumn.mapValues { (_, nullable) -> IntrospectedPartnerColumn(nullable = nullable) }
    return IntrospectedPartnerTable(
        columns = columns,
        foreignKeys = fkByColumn,
        primaryKeyColumns = pkColumns,
        uniqueConstraints = uniqueColumnsByConstraint.values.map { it.toSet() },
    )
}
