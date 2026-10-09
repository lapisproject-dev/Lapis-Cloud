package network.lapis.cloud.server.db

import dev.kuml.erm.model.ErmModel
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import network.lapis.cloud.server.db.generated.MailBudgetLockTable
import network.lapis.cloud.server.db.generated.MailOutboxTable
import network.lapis.cloud.server.db.generated.MailSendSlotTable
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.io.File

/**
 * ADR-0016 (MDA persistence pipeline) -- Welle V1.9.81. Verifies that `66-mail-outbox.kuml.kts` is a faithful model of the real
 * Flyway-migrated schema (`V80__mail_budget_and_outbox.sql`) and of the three hand-written Exposed objects. Mirrors
 * [EncounterSpaceSchemaDriftTest]. The hot-path indexes are checked against a real `information_schema` introspection because the
 * Exposed codegen drops `index {}` declarations.
 */
class MailOutboxSchemaDriftTest :
    FunSpec({
        beforeSpec { DatabaseConfig.connect() }

        val scriptFile = File(KumlModelLoader.kumlSourceDir, "66-mail-outbox.kuml.kts")
        val model: ErmModel by lazy { KumlModelLoader.loadErmModel(scriptFile) }

        test("model declares exactly the three mail tables") {
            model.entities.map { it.name }.toSet() shouldBe setOf("mail_outbox", "mail_send_slot", "mail_budget_lock")
        }

        listOf(
            Pair("mail_outbox", MailOutboxTable.columns.map { it.name }),
            Pair("mail_send_slot", MailSendSlotTable.columns.map { it.name }),
            Pair("mail_budget_lock", MailBudgetLockTable.columns.map { it.name }),
        ).forEach { (table, exposedColumns) ->
            test("$table matches the real migrated schema and its Exposed object 1:1") {
                val entity = model.entities.single { it.name == table }
                val real = transaction { introspectMailTable(table) }
                entity.attributes.map { it.name }.toSet() shouldBe real.keys
                entity.attributes.forEach { attr -> real.getValue(attr.name!!) shouldBe attr.nullable }
                entity.attributes.map { it.name } shouldContainExactlyInAnyOrder exposedColumns
            }
        }

        test("the payload columns and the lookup hash are nullable (a final row carries none of them)") {
            val real = transaction { introspectMailTable("mail_outbox") }
            listOf(
                "recipient_enc",
                "subject_enc",
                "text_enc",
                "html_enc",
                "recipient_lookup_hash",
                "expires_at",
                "claimed_at",
                "finished_at",
            ).forEach { real.getValue(it) shouldBe true }
            listOf("purpose", "priority", "status", "attempt_count", "next_attempt_at", "created_at", "log_recipient")
                .forEach { real.getValue(it) shouldBe false }
        }

        test("hot-path indexes exist in the real migrated schema") {
            transaction { indexColumns(tableName = "mail_outbox", indexName = "idx_mail_outbox_due") } shouldBe
                listOf("status", "priority", "next_attempt_at")
            transaction { indexColumns(tableName = "mail_outbox", indexName = "idx_mail_outbox_lookup") } shouldBe
                listOf("recipient_lookup_hash")
            transaction { indexColumns(tableName = "mail_outbox", indexName = "idx_mail_outbox_finished") } shouldBe
                listOf("status", "finished_at")
            transaction { indexColumns(tableName = "mail_send_slot", indexName = "idx_mail_send_slot_reserved") } shouldBe
                listOf("reserved_at")
            transaction { indexColumns(tableName = "mailing_delivery_log", indexName = "idx_mailing_delivery_log_msg_status") } shouldBe
                listOf("mailing_message_id", "delivery_status")
        }
    })

private fun JdbcTransaction.introspectMailTable(tableName: String): Map<String, Boolean> {
    val nullable = mutableMapOf<String, Boolean>()
    exec("SELECT column_name, is_nullable FROM information_schema.columns WHERE table_name = '$tableName'") { rs ->
        while (rs.next()) nullable[rs.getString("column_name")] = rs.getString("is_nullable") == "YES"
    }
    return nullable
}

private fun JdbcTransaction.indexColumns(
    tableName: String,
    indexName: String,
): List<String> {
    val columns = mutableListOf<Pair<Int, String>>()
    exec(
        "SELECT ic.column_name, ic.ordinal_position FROM information_schema.index_columns ic " +
            "WHERE ic.table_name = '$tableName' AND ic.index_name = '$indexName'",
    ) { rs ->
        while (rs.next()) columns += rs.getInt("ordinal_position") to rs.getString("column_name")
    }
    return columns.sortedBy { it.first }.map { it.second }
}
