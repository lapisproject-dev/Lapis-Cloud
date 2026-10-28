package network.lapis.cloud.server.db

import dev.kuml.erm.model.ErmModel
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import network.lapis.cloud.server.db.generated.ProtocolDraftBlockTable
import network.lapis.cloud.server.db.generated.ProtocolDraftTable
import network.lapis.cloud.server.db.generated.ProtocolDraftTaskTable
import network.lapis.cloud.server.db.generated.ProtocolTaskStatusTable
import network.lapis.cloud.server.db.generated.ProtocolVersionConfirmationTable
import network.lapis.cloud.server.db.generated.ProtocolVersionTable
import network.lapis.cloud.server.db.generated.ProtocolVersionTaskTable
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.io.File

/**
 * V1.9.239 (ADR-0016) -- `05-governance.kuml.kts` models the three Protokoll draft tables like V129 creates them, and the Exposed objects
 * carry the same columns, column for column (nullability against `information_schema`, foreign keys by target table).
 */
class ProtocolDraftSchemaDriftTest :
    FunSpec({
        beforeSpec { DatabaseConfig.connect() }

        val model: ErmModel by lazy { KumlModelLoader.loadErmModel(File(KumlModelLoader.kumlSourceDir, "05-governance.kuml.kts")) }

        fun nullability(table: String): Map<String, Boolean> =
            transaction {
                val result = mutableMapOf<String, Boolean>()
                exec("SELECT column_name, is_nullable FROM information_schema.columns WHERE table_name = '$table'") { rs ->
                    while (rs.next()) result[rs.getString("column_name")] = rs.getString("is_nullable") == "YES"
                }
                result
            }

        fun foreignKeys(table: String): Map<String, String> =
            transaction {
                val result = mutableMapOf<String, String>()
                exec(
                    """
                    SELECT kcu.column_name AS fk_column, tc2.table_name AS ref_table
                    FROM information_schema.table_constraints tc
                    JOIN information_schema.key_column_usage kcu
                        ON tc.constraint_name = kcu.constraint_name AND tc.table_schema = kcu.table_schema
                    JOIN information_schema.referential_constraints rc
                        ON tc.constraint_name = rc.constraint_name AND tc.constraint_schema = rc.constraint_schema
                    JOIN information_schema.table_constraints tc2
                        ON rc.unique_constraint_name = tc2.constraint_name AND rc.unique_constraint_schema = tc2.table_schema
                    WHERE tc.constraint_type = 'FOREIGN KEY' AND tc.table_name = '$table'
                    """.trimIndent(),
                ) { rs ->
                    while (rs.next()) result[rs.getString("fk_column")] = rs.getString("ref_table")
                }
                result
            }

        fun check(
            entityName: String,
            exposed: Table,
            expectedForeignKeys: Map<String, String>,
        ) {
            val entity = model.entities.single { it.name == entityName }
            val real = nullability(entityName)
            withClue("columns of $entityName") { entity.attributes.map { it.name }.toSet() shouldBe real.keys }
            entity.attributes.forEach { attr ->
                withClue("nullability of $entityName.${attr.name}") { real.getValue(attr.name!!) shouldBe attr.nullable }
            }
            entity.attributes.map { it.name } shouldContainExactlyInAnyOrder exposed.columns.map { it.name }
            foreignKeys(entityName) shouldBe expectedForeignKeys
            entity.attributes
                .filter { it.foreignKey != null }
                .associate { it.name!! to model.entities.first { e -> e.id == it.foreignKey!!.targetEntityId }.name } shouldBe
                expectedForeignKeys
        }

        test("protocol_draft matches the migrated schema and ProtocolDraftTable 1:1") {
            check("protocol_draft", ProtocolDraftTable, mapOf("meeting_id" to "meeting"))
        }

        test("protocol_draft_block matches the migrated schema and ProtocolDraftBlockTable 1:1") {
            check("protocol_draft_block", ProtocolDraftBlockTable, mapOf("draft_id" to "protocol_draft", "agenda_item_id" to "agenda_item"))
        }

        test("protocol_draft_task matches the migrated schema and ProtocolDraftTaskTable 1:1") {
            check(
                "protocol_draft_task",
                ProtocolDraftTaskTable,
                mapOf("draft_id" to "protocol_draft", "agenda_item_id" to "agenda_item", "assignee_member_id" to "member"),
            )
        }

        // V1.9.240 -- the four tables of the finalised Protokoll (V130) and the columns V130 adds to the existing ones.
        test("protocol_version matches the migrated schema and ProtocolVersionTable 1:1") {
            check(
                "protocol_version",
                ProtocolVersionTable,
                mapOf("meeting_id" to "meeting", "finalised_by" to "member", "pdf_document_version_id" to "document_version"),
            )
        }

        test("protocol_version_confirmation matches the migrated schema and its Exposed object 1:1") {
            check(
                "protocol_version_confirmation",
                ProtocolVersionConfirmationTable,
                mapOf("version_id" to "protocol_version", "member_id" to "member"),
            )
        }

        test("protocol_version_task matches the migrated schema and its Exposed object 1:1") {
            check(
                "protocol_version_task",
                ProtocolVersionTaskTable,
                mapOf(
                    "version_id" to "protocol_version",
                    "assignee_member_id" to "member",
                ),
            )
        }

        test("protocol_task_status matches the migrated schema and its Exposed object 1:1") {
            check("protocol_task_status", ProtocolTaskStatusTable, mapOf("meeting_id" to "meeting", "assignee_member_id" to "member"))
        }

        test(
            "the columns V130 adds to protocol_draft, protocol_draft_task, meeting and agenda_item are in the model and in the Exposed objects",
        ) {
            fun columns(entity: String) =
                model.entities
                    .single { it.name == entity }
                    .attributes
                    .map { it.name }
            columns("protocol_draft") shouldContainExactlyInAnyOrder ProtocolDraftTable.columns.map { it.name }
            columns("protocol_draft_task") shouldContainExactlyInAnyOrder ProtocolDraftTaskTable.columns.map { it.name }
            columns("meeting") shouldContainExactlyInAnyOrder
                network.lapis.cloud.server.db.generated.MeetingTable.columns
                    .map { it.name }
            columns("agenda_item") shouldContainExactlyInAnyOrder
                network.lapis.cloud.server.db.generated.AgendaItemTable.columns
                    .map { it.name }
            nullability("meeting").getValue("protocol_approval_source_meeting_id") shouldBe true
            // no database foreign key on the tombstone: meeting -> meeting is a cycle the backup cannot restore
            foreignKeys("meeting").containsKey("protocol_approval_source_meeting_id") shouldBe false
        }
    })
