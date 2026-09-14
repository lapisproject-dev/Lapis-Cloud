package network.lapis.cloud.server.db

import dev.kuml.erm.model.ErmDataType
import dev.kuml.erm.model.ErmModel
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import network.lapis.cloud.server.db.generated.OpenItemNettingTable
import network.lapis.cloud.server.db.generated.OpenItemSettlementTable
import network.lapis.cloud.server.db.generated.OpenItemTable
import network.lapis.cloud.server.db.generated.ReceivableDunningLevelTable
import network.lapis.cloud.server.db.generated.ReceivableDunningNoticeTable
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.io.File

/**
 * ADR-0016 (MDA persistence pipeline) -- open-item domain (Welle V1.4.15
 * "Kreditoren-/Debitorenbuchhaltung"). Verifies that `48-open-item.kuml.kts` is a faithful model
 * of both (a) the real, Flyway-migrated H2 schema, and (b) the hand-written `OpenItemTable`/
 * `OpenItemNettingTable`/`OpenItemSettlementTable`/`ReceivableDunningLevelTable`/
 * `ReceivableDunningNoticeTable` Exposed objects. Same introspection-helper shape
 * [BankAccountSchemaDriftTest] already establishes.
 */
class OpenItemSchemaDriftTest :
    FunSpec({
        beforeSpec { DatabaseConfig.connect() }

        val scriptFile = File(KumlModelLoader.kumlSourceDir, "48-open-item.kuml.kts")
        val model: ErmModel by lazy { KumlModelLoader.loadErmModel(scriptFile) }

        test("model declares exactly the five real tables plus the five cross-domain stubs") {
            model.entities.map { it.name }.toSet() shouldBe
                setOf(
                    "open_item",
                    "open_item_netting",
                    "open_item_settlement",
                    "receivable_dunning_level",
                    "receivable_dunning_notice",
                    "member",
                    "journal_entry",
                    "crm_contact",
                    "document",
                    "ledger_account",
                )
        }

        test("open_item table shape matches the real migrated schema and the hand-written Exposed table") {
            val entity = model.entities.single { it.name == "open_item" }
            val real = transaction { introspectOpenItemTable("open_item") }
            entity.attributes.map { it.name }.toSet() shouldBe real.columns.keys
            entity.attributes.map { it.name } shouldContainExactlyInAnyOrder OpenItemTable.columns.map { it.name }
            real.foreignKeys["crm_contact_id"] shouldBe "crm_contact"
            real.foreignKeys["contra_account_id"] shouldBe "ledger_account"
            real.foreignKeys["created_by_member_id"] shouldBe "member"
            real.foreignKeys["cancelled_by_member_id"] shouldBe "member"
            real.foreignKeys["creation_journal_entry_id"] shouldBe "journal_entry"
            real.foreignKeys["cancellation_journal_entry_id"] shouldBe "journal_entry"
        }

        test("open_item's creation/cancellation journal-entry FKs each carry a PLAIN (not partial) unique index") {
            val real = transaction { introspectOpenItemTable("open_item") }
            real.uniqueConstraints shouldContainExactlyInAnyOrder
                listOf(setOf("creation_journal_entry_id"), setOf("cancellation_journal_entry_id"))
        }

        test("open_item_netting table shape matches the real migrated schema and the hand-written Exposed table") {
            val entity = model.entities.single { it.name == "open_item_netting" }
            val real = transaction { introspectOpenItemTable("open_item_netting") }
            entity.attributes.map { it.name }.toSet() shouldBe real.columns.keys
            entity.attributes.map { it.name } shouldContainExactlyInAnyOrder OpenItemNettingTable.columns.map { it.name }
            real.foreignKeys["payable_item_id"] shouldBe "open_item"
            real.foreignKeys["receivable_item_id"] shouldBe "open_item"
            real.uniqueConstraints shouldContainExactlyInAnyOrder listOf(setOf("journal_entry_id"))
        }

        test("open_item_settlement table shape matches the real migrated schema and the hand-written Exposed table") {
            val entity = model.entities.single { it.name == "open_item_settlement" }
            val real = transaction { introspectOpenItemTable("open_item_settlement") }
            entity.attributes.map { it.name }.toSet() shouldBe real.columns.keys
            entity.attributes.map { it.name } shouldContainExactlyInAnyOrder OpenItemSettlementTable.columns.map { it.name }
            real.foreignKeys["open_item_id"] shouldBe "open_item"
            real.foreignKeys["netting_id"] shouldBe "open_item_netting"
        }

        test("uq_ois_netting_item is a plain unique index on (netting_id, open_item_id) -- double-netting guard") {
            val real = transaction { introspectOpenItemTable("open_item_settlement") }
            real.uniqueConstraints shouldContainExactlyInAnyOrder listOf(setOf("netting_id", "open_item_id"))
        }

        test("receivable_dunning_level table shape matches the real migrated schema and the hand-written Exposed table") {
            val entity = model.entities.single { it.name == "receivable_dunning_level" }
            val real = transaction { introspectOpenItemTable("receivable_dunning_level") }
            entity.attributes.map { it.name }.toSet() shouldBe real.columns.keys
            entity.attributes.map { it.name } shouldContainExactlyInAnyOrder ReceivableDunningLevelTable.columns.map { it.name }
            real.uniqueConstraints shouldContainExactlyInAnyOrder listOf(setOf("level_number"))
        }

        test("receivable_dunning_notice table shape matches the real migrated schema and the hand-written Exposed table") {
            val entity = model.entities.single { it.name == "receivable_dunning_notice" }
            val real = transaction { introspectOpenItemTable("receivable_dunning_notice") }
            entity.attributes.map { it.name }.toSet() shouldBe real.columns.keys
            entity.attributes.map { it.name } shouldContainExactlyInAnyOrder ReceivableDunningNoticeTable.columns.map { it.name }
            real.foreignKeys["open_item_id"] shouldBe "open_item"
            real.foreignKeys["receivable_dunning_level_id"] shouldBe "receivable_dunning_level"
            real.foreignKeys["document_id"] shouldBe "document"
            real.uniqueConstraints shouldContainExactlyInAnyOrder listOf(setOf("open_item_id", "cycle_number", "level_number"))
        }

        // Literal order load-bearing (append only, never reorder) -- see OpenItem.kt KDoc.
        test("OpenItemDirection/OpenItemStatus/OpenItemSettlementKind/ReceivableDunningNoticeStatus literal order is pinned") {
            val openItem = model.entities.single { it.name == "open_item" }
            openItem.attributeByName("direction")?.type shouldBe
                ErmDataType.Enum(
                    name = "OpenItemDirection",
                    values = listOf("PAYABLE", "RECEIVABLE"),
                    externalFqName = "network.lapis.cloud.shared.domain.OpenItemDirection",
                )
            openItem.attributeByName("status")?.type shouldBe
                ErmDataType.Enum(
                    name = "OpenItemStatus",
                    values = listOf("OPEN", "PARTIALLY_SETTLED", "SETTLED", "CANCELLED"),
                    externalFqName = "network.lapis.cloud.shared.domain.OpenItemStatus",
                )
            val settlement = model.entities.single { it.name == "open_item_settlement" }
            settlement.attributeByName("kind")?.type shouldBe
                ErmDataType.Enum(
                    name = "OpenItemSettlementKind",
                    values = listOf("PAYMENT", "NETTING"),
                    externalFqName = "network.lapis.cloud.shared.domain.OpenItemSettlementKind",
                )
            val notice = model.entities.single { it.name == "receivable_dunning_notice" }
            notice.attributeByName("status")?.type shouldBe
                ErmDataType.Enum(
                    name = "ReceivableDunningNoticeStatus",
                    values = listOf("ISSUED", "SKIPPED", "CANCELLED"),
                    externalFqName = "network.lapis.cloud.shared.domain.ReceivableDunningNoticeStatus",
                )
        }
    })

private data class IntrospectedOpenItemTable(
    val columns: Map<String, IntrospectedOpenItemColumn>,
    val foreignKeys: Map<String, String>,
    val uniqueConstraints: List<Set<String>>,
)

private data class IntrospectedOpenItemColumn(
    val nullable: Boolean,
)

/** Same ANSI `information_schema` walk shape as [BankAccountSchemaDriftTest]'s own `introspectBankAccountTable`. */
private fun JdbcTransaction.introspectOpenItemTable(tableName: String): IntrospectedOpenItemTable {
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
            uniqueColumnsByConstraint.getOrPut(rs.getString("name")) { mutableSetOf() }.add(rs.getString("column_name"))
        }
    }

    return IntrospectedOpenItemTable(
        columns = nullableByColumn.mapValues { (_, nullable) -> IntrospectedOpenItemColumn(nullable = nullable) },
        foreignKeys = fkByColumn,
        uniqueConstraints = uniqueColumnsByConstraint.values.map { it.toSet() },
    )
}
