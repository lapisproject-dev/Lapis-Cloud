package network.lapis.cloud.server.db

import dev.kuml.erm.model.ErmDataType
import dev.kuml.erm.model.ErmModel
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import network.lapis.cloud.server.db.generated.PaymentGatewayComplianceAcknowledgmentTable
import network.lapis.cloud.server.db.generated.PaymentTransactionTable
import network.lapis.cloud.server.db.generated.SepaComplianceAcknowledgmentTable
import network.lapis.cloud.shared.domain.PaymentIntent
import network.lapis.cloud.shared.domain.PaymentProvider
import network.lapis.cloud.shared.domain.PaymentTransactionStatus
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.io.File

/**
 * ADR-0016 (MDA persistence pipeline) — Zahlungsverkehr domain, Welle V1.2.1 "Zahlungs-Fundament".
 *
 * Verifies that `lapis-server/src/main/kuml/33-payments.kuml.kts` is a faithful model of both (a)
 * the real, Flyway-migrated H2 schema (`payment_transaction`/`sepa_compliance_acknowledgment`/
 * `payment_gateway_compliance_acknowledgment`), and (b) the generated `PaymentTransactionTable`/
 * `SepaComplianceAcknowledgmentTable`/`PaymentGatewayComplianceAcknowledgmentTable` Exposed
 * objects. Mirrors [SocialNetworkSchemaDriftTest]'s shape.
 */
class PaymentsSchemaDriftTest :
    FunSpec({
        beforeSpec {
            DatabaseConfig.connect()
            DevSeedData.seedIfEmpty(force = true)
        }

        val scriptFile = File(KumlModelLoader.kumlSourceDir, "33-payments.kuml.kts")
        val model: ErmModel by lazy { KumlModelLoader.loadErmModel(scriptFile) }

        test(
            "model declares exactly the payment_transaction/sepa_compliance_acknowledgment/" +
                "payment_gateway_compliance_acknowledgment entities plus the Member/Contribution/JournalEntry stubs",
        ) {
            model.entities.map { it.name }.toSet() shouldBe
                setOf(
                    "member",
                    "contribution",
                    "journal_entry",
                    "payment_transaction",
                    "sepa_compliance_acknowledgment",
                    "payment_gateway_compliance_acknowledgment",
                )
        }

        test("payment_transaction table shape matches the real migrated schema and PaymentTransactionTable 1:1") {
            val entity = model.entities.single { it.name == "payment_transaction" }
            val real = transaction { introspectGenericTable("payment_transaction") }

            entity.attributes.map { it.name }.toSet() shouldBe real.columns.keys
            entity.attributes.forEach { attr ->
                withClue(clue = "column '${attr.name}'") {
                    real.columns.getValue(attr.name!!).nullable shouldBe attr.nullable
                }
            }
            entity.attributes.map { it.name } shouldContainExactlyInAnyOrder PaymentTransactionTable.columns.map { it.name }

            real.foreignKeys["contribution_id"] shouldBe "contribution"
            real.foreignKeys["member_id"] shouldBe "member"
            real.foreignKeys["reconciled_by"] shouldBe "member"
            real.foreignKeys["journal_entry_id"] shouldBe "journal_entry"

            entity.attributeByName("contribution_id")?.nullable shouldBe true
            entity.attributeByName("member_id")?.nullable shouldBe true
            entity.attributeByName("provider")?.type shouldBe
                ErmDataType.Enum(
                    name = "PaymentProvider",
                    values = PaymentProvider.entries.map { it.name },
                    externalFqName = "network.lapis.cloud.shared.domain.PaymentProvider",
                )
            entity.attributeByName("status")?.type shouldBe
                ErmDataType.Enum(
                    name = "PaymentTransactionStatus",
                    values = PaymentTransactionStatus.entries.map { it.name },
                    externalFqName = "network.lapis.cloud.shared.domain.PaymentTransactionStatus",
                )
            entity.attributeByName("intent")?.type shouldBe
                ErmDataType.Enum(
                    name = "PaymentIntent",
                    values = PaymentIntent.entries.map { it.name },
                    externalFqName = "network.lapis.cloud.shared.domain.PaymentIntent",
                )
            entity.attributeByName("amount")?.type shouldBe ErmDataType.Decimal(14, 2)
        }

        test(
            "sepa_compliance_acknowledgment table shape matches the real migrated schema and SepaComplianceAcknowledgmentTable 1:1",
        ) {
            val entity = model.entities.single { it.name == "sepa_compliance_acknowledgment" }
            val real = transaction { introspectGenericTable("sepa_compliance_acknowledgment") }

            entity.attributes.map { it.name }.toSet() shouldBe real.columns.keys
            entity.attributes.map { it.name } shouldContainExactlyInAnyOrder SepaComplianceAcknowledgmentTable.columns.map { it.name }

            real.foreignKeys["acknowledged_by_member_id"] shouldBe "member"
            entity.attributeByName("acknowledged_by_member_id")?.nullable shouldBe false
        }

        test(
            "payment_gateway_compliance_acknowledgment table shape matches the real migrated schema and " +
                "PaymentGatewayComplianceAcknowledgmentTable 1:1",
        ) {
            val entity = model.entities.single { it.name == "payment_gateway_compliance_acknowledgment" }
            val real = transaction { introspectGenericTable("payment_gateway_compliance_acknowledgment") }

            entity.attributes.map { it.name }.toSet() shouldBe real.columns.keys
            entity.attributes.map { it.name } shouldContainExactlyInAnyOrder
                PaymentGatewayComplianceAcknowledgmentTable.columns.map { it.name }

            real.foreignKeys["acknowledged_by_member_id"] shouldBe "member"
            entity.attributeByName("provider")?.type shouldBe
                ErmDataType.Enum(
                    name = "PaymentProvider",
                    values = PaymentProvider.entries.map { it.name },
                    externalFqName = "network.lapis.cloud.shared.domain.PaymentProvider",
                )
        }

        // Regression guard for the idempotency anchor against webhook retries (Plan § 3.4) -- no
        // V1.2.1 code path writes this table yet, but the constraint must exist from the schema's
        // own first migration, not added later once the webhook route needs it.
        test("payment_transaction has a UNIQUE index covering (provider, provider_event_id)") {
            val uniqueIndexColumnSets =
                transaction {
                    val byIndex = mutableMapOf<String, MutableSet<String>>()
                    exec(
                        """
                        SELECT i.index_name AS name, ic.column_name
                        FROM information_schema.index_columns ic
                        JOIN information_schema.indexes i
                            ON ic.index_name = i.index_name AND ic.table_name = i.table_name
                        WHERE i.index_type_name = 'UNIQUE INDEX' AND ic.table_name = 'payment_transaction'
                        """.trimIndent(),
                    ) { rs ->
                        while (rs.next()) {
                            byIndex.getOrPut(rs.getString("name")) { mutableSetOf() }.add(rs.getString("column_name"))
                        }
                    }
                    byIndex.values
                }
            uniqueIndexColumnSets shouldContain setOf("provider", "provider_event_id")
        }
    })

private data class IntrospectedPaymentsColumn(
    val nullable: Boolean,
)

private data class IntrospectedPaymentsTable(
    val columns: Map<String, IntrospectedPaymentsColumn>,
    val foreignKeys: Map<String, String>,
)

/** Generic `information_schema` walk for [tableName] -- mirrors `SocialNetworkSchemaDriftTest.introspectGenericTable`. */
private fun JdbcTransaction.introspectGenericTable(tableName: String): IntrospectedPaymentsTable {
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

    val columns = nullableByColumn.mapValues { (_, nullable) -> IntrospectedPaymentsColumn(nullable = nullable) }
    return IntrospectedPaymentsTable(columns = columns, foreignKeys = fkByColumn)
}

/** Small local stand-in for Kotest's `withClue` to keep imports minimal (mirrors `SchemaDriftTest`'s). */
private inline fun <T> withClue(
    clue: String,
    block: () -> T,
): T =
    try {
        block()
    } catch (e: AssertionError) {
        throw AssertionError("$clue: ${e.message}", e)
    }
