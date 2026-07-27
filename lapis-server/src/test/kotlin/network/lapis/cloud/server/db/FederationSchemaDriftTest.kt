package network.lapis.cloud.server.db

import dev.kuml.erm.model.ErmDataType
import dev.kuml.erm.model.ErmModel
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import network.lapis.cloud.server.db.generated.FederationActorKeyTable
import network.lapis.cloud.server.db.generated.FederationInboxDeliveryLogTable
import network.lapis.cloud.server.db.generated.FederationRelationshipEventTable
import network.lapis.cloud.server.db.generated.FederationRelationshipTable
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.io.File

/**
 * ADR-0016 (MDA persistence pipeline) — Federation-Grundgerüst domain (V0.8.1).
 *
 * Verifies that `lapis-server/src/main/kuml/24-federation.kuml.kts` is a faithful model of both
 * (a) the real, Flyway-migrated H2 schema (`federation_actor_key`/`federation_relationship`/
 * `federation_relationship_event`/`federation_inbox_delivery_log`), and (b) the hand-written
 * `FederationActorKeyTable`/`FederationRelationshipTable`/`FederationRelationshipEventTable`/
 * `FederationInboxDeliveryLogTable` Exposed objects. Mirrors [PriceOracleSchemaDriftTest]'s shape.
 *
 * The domain-specific structural points this test pins: `federation_actor_key` has NO FK at all
 * (pure singleton keypair row, no seed row -- see `.kuml.kts` file header); `federation_relationship`
 * has NO FK either (the remote side is identified by URI, not a local row); `federation_relationship_event.relationship_id`
 * is a real FK to `federation_relationship` via a plain «Column».fkEntity attribute (not a UML
 * association); `federation_inbox_delivery_log` has no FK to anything (remote_host identifies a
 * remote server, not a local member).
 */
class FederationSchemaDriftTest :
    FunSpec({
        beforeSpec { DatabaseConfig.connect() }

        val scriptFile = File(KumlModelLoader.kumlSourceDir, "24-federation.kuml.kts")
        val model: ErmModel by lazy { KumlModelLoader.loadErmModel(scriptFile) }

        fun ErmModel.entityNameOf(entityId: String): String? = entities.firstOrNull { it.id == entityId }?.name

        test("model declares exactly the four federation entities") {
            model.entities.map { it.name }.toSet() shouldBe
                setOf(
                    "federation_actor_key",
                    "federation_relationship",
                    "federation_relationship_event",
                    "federation_inbox_delivery_log",
                )
        }

        // ── federation_actor_key ──────────────────────────────────────────

        test("federation_actor_key table shape matches the real migrated schema and FederationActorKeyTable 1:1, no FK") {
            val entity = model.entities.single { it.name == "federation_actor_key" }
            val real = transaction { introspectFederationTable("federation_actor_key") }

            entity.attributes.map { it.name }.toSet() shouldBe real.columns.keys
            entity.attributes.forEach { attr ->
                val col = real.columns.getValue(attr.name!!)
                withClue("column '${attr.name}'") {
                    col.nullable shouldBe attr.nullable
                }
            }
            entity.attributes.map { it.name } shouldContainExactlyInAnyOrder FederationActorKeyTable.columns.map { it.name }

            real.primaryKeyColumns shouldBe setOf("id")
            entity.attributeByName("id")?.primaryKey shouldBe true
            real.foreignKeys.isEmpty() shouldBe true
        }

        // ── federation_relationship ───────────────────────────────────────

        test("federation_relationship table shape matches the real migrated schema and FederationRelationshipTable 1:1, no FK") {
            val entity = model.entities.single { it.name == "federation_relationship" }
            val real = transaction { introspectFederationTable("federation_relationship") }

            entity.attributes.map { it.name }.toSet() shouldBe real.columns.keys
            entity.attributes.forEach { attr ->
                val col = real.columns.getValue(attr.name!!)
                withClue("column '${attr.name}'") {
                    col.nullable shouldBe attr.nullable
                }
            }
            entity.attributes.map { it.name } shouldContainExactlyInAnyOrder FederationRelationshipTable.columns.map { it.name }

            real.primaryKeyColumns shouldBe setOf("id")
            entity.attributeByName("id")?.primaryKey shouldBe true
            real.foreignKeys.isEmpty() shouldBe true

            entity.attributeByName("remote_public_key_pem")?.nullable shouldBe true

            entity.attributeByName("direction")?.type shouldBe
                ErmDataType.Enum(
                    name = "FederationRelationshipDirection",
                    values = listOf("OUTBOUND", "INBOUND"),
                    externalFqName = "network.lapis.cloud.shared.domain.FederationRelationshipDirection",
                )
            entity.attributeByName("status")?.type shouldBe
                ErmDataType.Enum(
                    name = "FederationRelationshipStatus",
                    values = listOf("PENDING", "ACTIVE", "REJECTED", "UNDONE"),
                    externalFqName = "network.lapis.cloud.shared.domain.FederationRelationshipStatus",
                )
        }

        // ── federation_relationship_event ─────────────────────────────────

        test("federation_relationship_event table shape matches the real migrated schema and FederationRelationshipEventTable 1:1") {
            val entity = model.entities.single { it.name == "federation_relationship_event" }
            val real = transaction { introspectFederationTable("federation_relationship_event") }

            entity.attributes.map { it.name }.toSet() shouldBe real.columns.keys
            entity.attributes.forEach { attr ->
                val col = real.columns.getValue(attr.name!!)
                withClue("column '${attr.name}'") {
                    col.nullable shouldBe attr.nullable
                }
            }
            entity.attributes.map { it.name } shouldContainExactlyInAnyOrder
                FederationRelationshipEventTable.columns.map { it.name }

            real.primaryKeyColumns shouldBe setOf("id")
            entity.attributeByName("id")?.primaryKey shouldBe true

            // relationship_id: plain «Column».fkEntity -- see file header "FK-naming" paragraph.
            real.foreignKeys["relationship_id"] shouldBe "federation_relationship"
            model.entityNameOf(entity.attributeByName("relationship_id")?.foreignKey?.targetEntityId ?: "") shouldBe
                "federation_relationship"
            entity.attributeByName("relationship_id")?.nullable shouldBe false

            entity.attributeByName("event_type")?.type shouldBe
                ErmDataType.Enum(
                    name = "FederationEventType",
                    values =
                        listOf(
                            "FOLLOW_SENT",
                            "FOLLOW_RECEIVED",
                            "ACCEPT_SENT",
                            "ACCEPT_RECEIVED",
                            "REJECT_SENT",
                            "REJECT_RECEIVED",
                            "UNDO_SENT",
                            "UNDO_RECEIVED",
                        ),
                    externalFqName = "network.lapis.cloud.shared.domain.FederationEventType",
                )
        }

        // ── federation_inbox_delivery_log ─────────────────────────────────

        test(
            "federation_inbox_delivery_log table shape matches the real migrated schema and FederationInboxDeliveryLogTable 1:1, no FK",
        ) {
            val entity = model.entities.single { it.name == "federation_inbox_delivery_log" }
            val real = transaction { introspectFederationTable("federation_inbox_delivery_log") }

            entity.attributes.map { it.name }.toSet() shouldBe real.columns.keys
            entity.attributes.forEach { attr ->
                val col = real.columns.getValue(attr.name!!)
                withClue("column '${attr.name}'") {
                    col.nullable shouldBe attr.nullable
                }
            }
            entity.attributes.map { it.name } shouldContainExactlyInAnyOrder
                FederationInboxDeliveryLogTable.columns.map { it.name }

            real.primaryKeyColumns shouldBe setOf("id")
            entity.attributeByName("id")?.primaryKey shouldBe true
            real.foreignKeys.isEmpty() shouldBe true

            entity.attributeByName("key_id")?.nullable shouldBe true
            entity.attributeByName("reject_reason")?.nullable shouldBe true
            entity.attributeByName("activity_type")?.nullable shouldBe true
            entity.attributeByName("activity_id")?.nullable shouldBe true
            entity.attributeByName("body_sha256")?.nullable shouldBe true
            entity.attributeByName("body_byte_size")?.nullable shouldBe true
        }
    })

/** Result of introspecting one real table's shape via `information_schema`. Mirrors [PriceOracleSchemaDriftTest]'s own private helper. */
private data class IntrospectedFederationTable(
    val columns: Map<String, IntrospectedFederationColumn>,
    val foreignKeys: Map<String, String>,
    val primaryKeyColumns: Set<String>,
)

private data class IntrospectedFederationColumn(
    val nullable: Boolean,
)

private fun JdbcTransaction.introspectFederationTable(tableName: String): IntrospectedFederationTable {
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

    val columns = nullableByColumn.mapValues { (_, nullable) -> IntrospectedFederationColumn(nullable = nullable) }
    return IntrospectedFederationTable(columns = columns, foreignKeys = fkByColumn, primaryKeyColumns = pkColumns)
}

/** Small local stand-in for Kotest's `withClue` to keep imports minimal (mirrors [PriceOracleSchemaDriftTest]'s). */
private inline fun <T> withClue(
    clue: String,
    block: () -> T,
): T =
    try {
        block()
    } catch (e: AssertionError) {
        throw AssertionError("$clue: ${e.message}", e)
    }
