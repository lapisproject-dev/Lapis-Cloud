package network.lapis.cloud.server.db

import dev.kuml.erm.model.ErmDataType
import dev.kuml.erm.model.ErmModel
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import network.lapis.cloud.server.db.generated.PostalDeliveryLogTable
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.io.File

/**
 * ADR-0016 (MDA persistence pipeline) — postal-mail domain (V0.4.2 Letterxpress postal-mail
 * dispatch).
 *
 * Verifies that `lapis-server/src/main/kuml/12-postal-mail.kuml.kts` is a faithful model of both
 * (a) the real, Flyway-migrated H2 schema (`postal_delivery_log`), and (b) the hand-written
 * [PostalDeliveryLogTable] Exposed object. Mirrors [CommunicationSchemaDriftTest] (which also has
 * a cross-domain Member stub) — see [SchemaDriftTest]'s KDoc for the full designModelStrategy
 * option B rationale.
 */
class PostalMailSchemaDriftTest :
    FunSpec({
        beforeSpec { DatabaseConfig.connect() }

        val scriptFile = File(KumlModelLoader.kumlSourceDir, "12-postal-mail.kuml.kts")
        val model: ErmModel by lazy { KumlModelLoader.loadErmModel(scriptFile) }

        /** Resolves an `ErmForeignKey.targetEntityId` back to its entity name within [model]. */
        fun ErmModel.entityNameOf(entityId: String): String? = entities.firstOrNull { it.id == entityId }?.name

        test("model declares exactly the postal_delivery_log entity plus the Member stub") {
            model.entities.map { it.name }.toSet() shouldBe setOf("member", "postal_delivery_log")
        }

        // ── (1) Model vs. real H2-migrated schema ───────────────────────────────

        test("postal_delivery_log table shape matches the real migrated schema") {
            val entity = model.entities.single { it.name == "postal_delivery_log" }
            val real = transaction { introspectPostalMailTable("postal_delivery_log") }

            entity.attributes.map { it.name }.toSet() shouldBe real.columns.keys

            entity.attributes.forEach { attr ->
                val col = real.columns.getValue(attr.name!!)
                withClue(clue = "column '${attr.name}'") {
                    col.nullable shouldBe attr.nullable
                }
            }
            // recipient_member_id has a real FK in the migrated schema. Same rationale as
            // mailing_list.created_by/mailing_message.sent_by -- the derived association default
            // name would be "member_id", not the real schema's "recipient_member_id" -- pinned
            // instead via «Column».fkEntity.
            real.foreignKeys["recipient_member_id"] shouldBe "member"
            model.entityNameOf(entity.attributeByName("recipient_member_id")?.foreignKey?.targetEntityId ?: "") shouldBe "member"
        }

        // ── (2) Model vs. hand-written Exposed Table object ─────────────────────

        test("postal_delivery_log entity column-name set matches the hand-written PostalDeliveryLogTable 1:1") {
            model.entities
                .single { it.name == "postal_delivery_log" }
                .attributes
                .map { it.name } shouldContainExactlyInAnyOrder PostalDeliveryLogTable.columns.map { it.name }
        }

        test("postal_delivery_log.status is modelled as a real ErmDataType.Enum column") {
            // Same gap-closure as mailing_message.status/mailing_delivery_log.delivery_status in
            // the communication domain -- with no «Column».sqlType override, kUML's enum-to-Enum+
            // CHECK fallback path applies.
            val status = model.entities.single { it.name == "postal_delivery_log" }.attributeByName("status")
            status?.type shouldBe
                ErmDataType.Enum(
                    name = "PostalDeliveryStatus",
                    values = listOf("QUEUED", "SENT", "FAILED"),
                    externalFqName = "network.lapis.cloud.shared.domain.PostalDeliveryStatus",
                )
        }
    })

/** Result of introspecting one real table's shape via `information_schema`. */
private data class IntrospectedPostalMailTable(
    val columns: Map<String, IntrospectedPostalMailColumn>,
    /** FK column name -> referenced table name. */
    val foreignKeys: Map<String, String>,
)

private data class IntrospectedPostalMailColumn(
    val nullable: Boolean,
)

/** ANSI `information_schema` walk for a single table's columns, nullability and FK targets. Mirrors [CommunicationSchemaDriftTest]'s own (private, communication-domain-scoped) `introspectCommunicationTable`. */
private fun JdbcTransaction.introspectPostalMailTable(tableName: String): IntrospectedPostalMailTable {
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

    val columns =
        nullableByColumn.mapValues { (_, nullable) ->
            IntrospectedPostalMailColumn(nullable = nullable)
        }
    return IntrospectedPostalMailTable(columns = columns, foreignKeys = fkByColumn)
}

/** Small local stand-in for Kotest's `withClue` to keep imports minimal (mirrors SchemaDriftTest's). */
private inline fun <T> withClue(
    clue: String,
    block: () -> T,
): T =
    try {
        block()
    } catch (e: AssertionError) {
        throw AssertionError("$clue: ${e.message}", e)
    }
