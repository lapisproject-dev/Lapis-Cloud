package network.lapis.cloud.server.db

import dev.kuml.erm.model.ErmDataType
import dev.kuml.erm.model.ErmModel
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import network.lapis.cloud.server.db.generated.DunningComplianceAcknowledgmentTable
import network.lapis.cloud.server.db.generated.DunningLevelTable
import network.lapis.cloud.server.db.generated.DunningNoticeTable
import network.lapis.cloud.shared.domain.DunningNoticeStatus
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.io.File

/**
 * ADR-0016 (MDA persistence pipeline) — Dunning domain, Welle V1.2.7 "Automatisiertes Mahnwesen".
 *
 * Pins `lapis-server/src/main/kuml/34-dunning.kuml.kts` against both (a) the real, Flyway-migrated
 * H2 schema (`dunning_level`/`dunning_notice`/`dunning_compliance_acknowledgment`) and (b) the
 * generated `DunningLevelTable`/`DunningNoticeTable`/`DunningComplianceAcknowledgmentTable` Exposed
 * objects. Mirrors [PaymentsSchemaDriftTest]'s shape.
 *
 * This test was missing for the entire V1.2.7 wave despite [DunningNoticeStatus]'s own KDoc
 * claiming it existed ("literal order load-bearing -- `DunningSchemaDriftTest` pins it against
 * `34-dunning.kuml.kts`'s `dunningNoticeStatus` enum") -- every OTHER domain in this codebase,
 * including the direct template [PaymentsSchemaDriftTest], has one. See the security review
 * finding this fixes.
 */
class DunningSchemaDriftTest :
    FunSpec({
        beforeSpec {
            DatabaseConfig.connect()
            DevSeedData.seedIfEmpty(force = true)
        }

        val scriptFile = File(KumlModelLoader.kumlSourceDir, "34-dunning.kuml.kts")
        val model: ErmModel by lazy { KumlModelLoader.loadErmModel(scriptFile) }

        test(
            "model declares exactly the dunning_level/dunning_notice/dunning_compliance_acknowledgment entities plus the " +
                "Member/Contribution/Document/PostalDeliveryLog stubs",
        ) {
            model.entities.map { it.name }.toSet() shouldBe
                setOf(
                    "member",
                    "contribution",
                    "document",
                    "postal_delivery_log",
                    "dunning_level",
                    "dunning_notice",
                    "dunning_compliance_acknowledgment",
                )
        }

        test("dunning_level table shape matches the real migrated schema and DunningLevelTable 1:1") {
            val entity = model.entities.single { it.name == "dunning_level" }
            val real = transaction { introspectDunningTable("dunning_level") }

            entity.attributes.map { it.name }.toSet() shouldBe real.columns.keys
            entity.attributes.map { it.name } shouldContainExactlyInAnyOrder DunningLevelTable.columns.map { it.name }

            entity.attributeByName("fee_amount")?.nullable shouldBe true
            entity.attributeByName("fee_amount")?.type shouldBe ErmDataType.Decimal(12, 2)

            val uniqueIndexColumnSets =
                transaction { uniqueIndexColumnSetsOf(tableName = "dunning_level") }
            uniqueIndexColumnSets shouldContain setOf("level_number")
        }

        test("dunning_notice table shape matches the real migrated schema and DunningNoticeTable 1:1") {
            val entity = model.entities.single { it.name == "dunning_notice" }
            val real = transaction { introspectDunningTable("dunning_notice") }

            entity.attributes.map { it.name }.toSet() shouldBe real.columns.keys
            entity.attributes.map { it.name } shouldContainExactlyInAnyOrder DunningNoticeTable.columns.map { it.name }

            real.foreignKeys["contribution_id"] shouldBe "contribution"
            real.foreignKeys["dunning_level_id"] shouldBe "dunning_level"
            real.foreignKeys["document_id"] shouldBe "document"
            real.foreignKeys["postal_delivery_log_id"] shouldBe "postal_delivery_log"
            real.foreignKeys["created_by"] shouldBe "member"

            // NULL created_by == the poller/System issued this notice -- see DunningIssuance KDoc.
            entity.attributeByName("created_by")?.nullable shouldBe true
            entity.attributeByName("document_id")?.nullable shouldBe true
            entity.attributeByName("postal_delivery_log_id")?.nullable shouldBe true
            entity.attributeByName("cancelled_at")?.nullable shouldBe true
            entity.attributeByName("cancellation_reason")?.nullable shouldBe true

            // Literal order load-bearing -- see this test's own class KDoc.
            entity.attributeByName("status")?.type shouldBe
                ErmDataType.Enum(
                    name = "DunningNoticeStatus",
                    values = DunningNoticeStatus.entries.map { it.name },
                    externalFqName = "network.lapis.cloud.shared.domain.DunningNoticeStatus",
                )
            entity.attributeByName("fee_amount")?.type shouldBe ErmDataType.Decimal(12, 2)
            entity.attributeByName("amount_due")?.type shouldBe ErmDataType.Decimal(12, 2)

            // The `uq_dunning_notice_slot` idempotency anchor `DunningIssuance`'s own KDoc
            // documents -- at most one notice per (contribution, cycle, level).
            val uniqueIndexColumnSets =
                transaction { uniqueIndexColumnSetsOf(tableName = "dunning_notice") }
            uniqueIndexColumnSets shouldContain setOf("contribution_id", "cycle_number", "level_number")
        }

        test(
            "dunning_compliance_acknowledgment table shape matches the real migrated schema and " +
                "DunningComplianceAcknowledgmentTable 1:1",
        ) {
            val entity = model.entities.single { it.name == "dunning_compliance_acknowledgment" }
            val real = transaction { introspectDunningTable("dunning_compliance_acknowledgment") }

            entity.attributes.map { it.name }.toSet() shouldBe real.columns.keys
            entity.attributes.map { it.name } shouldContainExactlyInAnyOrder
                DunningComplianceAcknowledgmentTable.columns.map { it.name }

            real.foreignKeys["acknowledged_by_member_id"] shouldBe "member"
            entity.attributeByName("acknowledged_by_member_id")?.nullable shouldBe false
        }
    })

private data class IntrospectedDunningColumn(
    val nullable: Boolean,
)

private data class IntrospectedDunningTable(
    val columns: Map<String, IntrospectedDunningColumn>,
    val foreignKeys: Map<String, String>,
)

/** Generic `information_schema` walk for [tableName] -- mirrors `PaymentsSchemaDriftTest.introspectGenericTable`. */
private fun JdbcTransaction.introspectDunningTable(tableName: String): IntrospectedDunningTable {
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

    val columns = nullableByColumn.mapValues { (_, nullable) -> IntrospectedDunningColumn(nullable = nullable) }
    return IntrospectedDunningTable(columns = columns, foreignKeys = fkByColumn)
}

/** Set of unique-indexed column-name sets for [tableName] -- mirrors the inline queries `PaymentsSchemaDriftTest` repeats per table. */
private fun JdbcTransaction.uniqueIndexColumnSetsOf(tableName: String): Collection<Set<String>> {
    val byIndex = mutableMapOf<String, MutableSet<String>>()
    exec(
        """
        SELECT i.index_name AS name, ic.column_name
        FROM information_schema.index_columns ic
        JOIN information_schema.indexes i
            ON ic.index_name = i.index_name AND ic.table_name = i.table_name
        WHERE i.index_type_name = 'UNIQUE INDEX' AND ic.table_name = '$tableName'
        """.trimIndent(),
    ) { rs ->
        while (rs.next()) {
            byIndex.getOrPut(rs.getString("name")) { mutableSetOf() }.add(rs.getString("column_name"))
        }
    }
    return byIndex.values
}
