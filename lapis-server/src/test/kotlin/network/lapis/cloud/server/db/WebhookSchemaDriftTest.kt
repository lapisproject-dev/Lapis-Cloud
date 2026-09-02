package network.lapis.cloud.server.db

import dev.kuml.erm.model.ErmModel
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import network.lapis.cloud.server.db.generated.WebhookDeliveryTable
import network.lapis.cloud.server.db.generated.WebhookEndpointTable
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.io.File

/**
 * ADR-0016 (MDA persistence pipeline) -- Welle V1.3.2 "Webhooks" (ausgehend). Mirrors
 * [ApiKeySchemaDriftTest]'s shape for TWO entities (`webhook_endpoint`/`webhook_delivery`) plus the
 * Member/ApiKey stubs.
 */
class WebhookSchemaDriftTest :
    FunSpec({
        beforeSpec { DatabaseConfig.connect() }

        val scriptFile = File(KumlModelLoader.kumlSourceDir, "37-webhook.kuml.kts")
        val model: ErmModel by lazy { KumlModelLoader.loadErmModel(scriptFile) }

        test("model declares exactly the webhook_endpoint/webhook_delivery entities plus the Member and ApiKey stubs") {
            model.entities.map { it.name }.toSet() shouldBe setOf("member", "api_key", "webhook_endpoint", "webhook_delivery")
        }

        test("webhook_endpoint table shape matches the real migrated schema and WebhookEndpointTable 1:1") {
            val entity = model.entities.single { it.name == "webhook_endpoint" }
            val real = transaction { introspectWebhookTable("webhook_endpoint") }

            entity.attributes.map { it.name }.toSet() shouldBe real.columns.keys
            entity.attributes.map { it.name } shouldContainExactlyInAnyOrder WebhookEndpointTable.columns.map { it.name }

            real.primaryKeyColumns shouldBe setOf("id")
            real.foreignKeys["api_key_id"] shouldBe "api_key"
            real.foreignKeys["created_by_member_id"] shouldBe "member"
            entity.attributeByName("created_by_member_id")?.nullable shouldBe false
            real.foreignKeys["updated_by_member_id"] shouldBe "member"
            entity.attributeByName("updated_by_member_id")?.nullable shouldBe true
            real.foreignKeys["deactivated_by_member_id"] shouldBe "member"
            entity.attributeByName("deactivated_by_member_id")?.nullable shouldBe true

            entity.attributeByName("url")?.nullable shouldBe false
            entity.attributeByName("secret_sealed")?.nullable shouldBe false
            entity.attributeByName("secret_prefix")?.nullable shouldBe false
            entity.attributeByName("active")?.nullable shouldBe false
            entity.attributeByName("deactivation_reason")?.nullable shouldBe true

            real.uniqueConstraints shouldContainExactlyInAnyOrder listOf(setOf("api_key_id"))
        }

        test("webhook_delivery table shape matches the real migrated schema and WebhookDeliveryTable 1:1") {
            val entity = model.entities.single { it.name == "webhook_delivery" }
            val real = transaction { introspectWebhookTable("webhook_delivery") }

            entity.attributes.map { it.name }.toSet() shouldBe real.columns.keys
            entity.attributes.map { it.name } shouldContainExactlyInAnyOrder WebhookDeliveryTable.columns.map { it.name }

            real.primaryKeyColumns shouldBe setOf("id")
            real.foreignKeys["endpoint_id"] shouldBe "webhook_endpoint"

            entity.attributeByName("event_id")?.nullable shouldBe false
            entity.attributeByName("event_type")?.nullable shouldBe false
            entity.attributeByName("entity_id")?.nullable shouldBe false
            entity.attributeByName("payload")?.nullable shouldBe false
            entity.attributeByName("status")?.nullable shouldBe false
            entity.attributeByName("attempt_count")?.nullable shouldBe false
            entity.attributeByName("next_attempt_at")?.nullable shouldBe true
            entity.attributeByName("last_attempt_at")?.nullable shouldBe true
            entity.attributeByName("last_http_status")?.nullable shouldBe true
            entity.attributeByName("last_error")?.nullable shouldBe true
            entity.attributeByName("delivered_at")?.nullable shouldBe true

            real.uniqueConstraints shouldContainExactlyInAnyOrder listOf(setOf("endpoint_id", "event_id"))
        }

        // S11 in the plan's Stolperfallen list: the m2m-exposed codegen drops index {}
        // declarations, so the hot-path index MUST be checked against a real information_schema
        // introspection, never merely against generated Kotlin (which carries no index at all).
        test("idx_webhook_delivery_due exists on (status, next_attempt_at) in the real migrated schema") {
            val indexColumns =
                transaction {
                    introspectNonUniqueIndexColumns(tableName = "webhook_delivery", indexName = "idx_webhook_delivery_due")
                }
            indexColumns shouldBe listOf("status", "next_attempt_at")
        }
    })

private data class IntrospectedWebhookTable(
    val columns: Map<String, IntrospectedWebhookColumn>,
    val foreignKeys: Map<String, String>,
    val primaryKeyColumns: Set<String>,
    val uniqueConstraints: List<Set<String>>,
)

private data class IntrospectedWebhookColumn(
    val nullable: Boolean,
)

private fun JdbcTransaction.introspectWebhookTable(tableName: String): IntrospectedWebhookTable {
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

    val columns = nullableByColumn.mapValues { (_, nullable) -> IntrospectedWebhookColumn(nullable = nullable) }
    return IntrospectedWebhookTable(
        columns = columns,
        foreignKeys = fkByColumn,
        primaryKeyColumns = pkColumns,
        uniqueConstraints = uniqueColumnsByConstraint.values.map { it.toSet() },
    )
}

/** Column names (in `ORDINAL_POSITION` order) of a specific non-unique index -- see [WebhookSchemaDriftTest]'s own "S11" test. */
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
