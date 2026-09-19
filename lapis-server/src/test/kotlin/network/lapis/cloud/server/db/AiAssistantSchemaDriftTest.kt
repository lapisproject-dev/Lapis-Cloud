package network.lapis.cloud.server.db

import dev.kuml.erm.model.ErmModel
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import network.lapis.cloud.server.db.generated.AiCallAuditTable
import network.lapis.cloud.server.db.generated.AiKnowledgeChunkTable
import network.lapis.cloud.server.db.generated.AiKnowledgeIndexStateTable
import network.lapis.cloud.server.db.generated.AiKnowledgeReleaseTable
import network.lapis.cloud.server.db.generated.AiMemberOptInTable
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.io.File

/**
 * ADR-0016 (MDA persistence pipeline) -- Welle V1.6.1 "KI-Fundament + Pilot Satzungs-Q&A". Verifies
 * that `lapis-server/src/main/kuml/53-ai-assistant.kuml.kts` is a faithful model of (a) the real,
 * Flyway-migrated H2 schema (`V44__ai_assistant.sql`) and (b) the five hand-written `Ai*Table`
 * Exposed objects. Mirrors [MemberCardSchemaDriftTest].
 */
class AiAssistantSchemaDriftTest :
    FunSpec({
        beforeSpec { DatabaseConfig.connect() }

        val scriptFile = File(KumlModelLoader.kumlSourceDir, "53-ai-assistant.kuml.kts")
        val model: ErmModel by lazy { KumlModelLoader.loadErmModel(scriptFile) }

        val tables: Map<String, Table> =
            mapOf(
                "ai_knowledge_release" to AiKnowledgeReleaseTable,
                "ai_knowledge_index_state" to AiKnowledgeIndexStateTable,
                "ai_knowledge_chunk" to AiKnowledgeChunkTable,
                "ai_member_opt_in" to AiMemberOptInTable,
                "ai_call_audit" to AiCallAuditTable,
            )

        test("model declares exactly the five AI tables and the three cross-domain stubs") {
            model.entities.map { it.name }.toSet() shouldBe tables.keys + setOf("member", "document", "document_version")
        }

        tables.forEach { (name, exposed) ->
            test("$name: column set and nullability match the real migrated schema") {
                val entity = model.entities.single { it.name == name }
                val real = transaction { introspectAiTable(name) }
                entity.attributes.map { it.name }.toSet() shouldBe real.columns.keys
                entity.attributes.forEach { attr ->
                    val col = real.columns.getValue(attr.name!!)
                    withClue(clue = "column '${attr.name}'") { col.nullable shouldBe attr.nullable }
                }
            }

            test("$name: entity column-name set matches the hand-written Exposed table 1:1") {
                model.entities
                    .single { it.name == name }
                    .attributes
                    .map { it.name } shouldContainExactlyInAnyOrder
                    exposed.columns.map { it.name }
            }
        }

        test("foreign keys point at the right tables") {
            transaction { introspectAiTable("ai_knowledge_release") }.foreignKeys shouldBe
                mapOf("document_id" to "document", "released_by" to "member")
            transaction { introspectAiTable("ai_knowledge_index_state") }.foreignKeys shouldBe
                mapOf("document_id" to "document", "document_version_id" to "document_version")
            transaction { introspectAiTable("ai_knowledge_chunk") }.foreignKeys shouldBe
                mapOf("document_id" to "document", "document_version_id" to "document_version")
            transaction { introspectAiTable("ai_member_opt_in") }.foreignKeys shouldBe mapOf("member_id" to "member")
            transaction { introspectAiTable("ai_call_audit") }.foreignKeys shouldBe mapOf("member_id" to "member")
        }

        test("the two member references that DSGVO erasure nulls are nullable in the real schema") {
            transaction { introspectAiTable("ai_call_audit") }.columns.getValue("member_id").nullable shouldBe true
            transaction { introspectAiTable("ai_knowledge_release") }.columns.getValue("released_by").nullable shouldBe true
        }

        test("uniqueness: one release and one index state per document, one opt-in per (member, feature)") {
            transaction { introspectAiTable("ai_knowledge_release") }.uniqueColumns shouldContainExactlyInAnyOrder listOf("document_id")
            transaction { introspectAiTable("ai_knowledge_index_state") }.uniqueColumns shouldContainExactlyInAnyOrder listOf("document_id")
            transaction { introspectAiTable("ai_member_opt_in") }.uniqueColumns shouldContainExactlyInAnyOrder
                listOf("member_id", "feature")
        }

        test("neither chunk nor index state carries a copy of the document's access level (live join only)") {
            transaction { introspectAiTable("ai_knowledge_chunk") }.columns.keys.none { it.contains("access") } shouldBe true
            transaction { introspectAiTable("ai_knowledge_index_state") }.columns.keys.none { it.contains("access") } shouldBe true
        }
    })

private data class IntrospectedAiTable(
    val columns: Map<String, IntrospectedAiColumn>,
    val foreignKeys: Map<String, String>,
    val uniqueColumns: Set<String>,
)

private data class IntrospectedAiColumn(
    val nullable: Boolean,
)

/** Same ANSI `information_schema` walk shape as [EventVolunteerSchemaDriftTest]'s own `introspectEventVolunteerTable`. */
private fun JdbcTransaction.introspectAiTable(tableName: String): IntrospectedAiTable {
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

    val uniqueColumns = mutableSetOf<String>()
    exec(
        """
        SELECT kcu.column_name
        FROM information_schema.table_constraints tc
        JOIN information_schema.key_column_usage kcu
            ON tc.constraint_name = kcu.constraint_name
            AND tc.table_schema = kcu.table_schema
        WHERE tc.constraint_type = 'UNIQUE' AND tc.table_name = '$tableName'
        UNION
        SELECT ic.column_name
        FROM information_schema.index_columns ic
        JOIN information_schema.indexes i
            ON ic.index_name = i.index_name AND ic.table_name = i.table_name
        WHERE i.index_type_name = 'UNIQUE INDEX' AND ic.table_name = '$tableName'
        """.trimIndent(),
    ) { rs ->
        while (rs.next()) {
            uniqueColumns += rs.getString("column_name")
        }
    }

    return IntrospectedAiTable(
        columns = nullableByColumn.mapValues { (_, nullable) -> IntrospectedAiColumn(nullable = nullable) },
        foreignKeys = fkByColumn,
        uniqueColumns = uniqueColumns,
    )
}

/** Small local stand-in for Kotest's `withClue` -- mirrors [EventVolunteerSchemaDriftTest]'s own. */
private inline fun <T> withClue(
    clue: String,
    block: () -> T,
): T =
    try {
        block()
    } catch (e: AssertionError) {
        throw AssertionError("$clue: ${e.message}", e)
    }
